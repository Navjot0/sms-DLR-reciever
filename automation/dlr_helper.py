"""
DLR verification helpers for SMS automation.

Covers both status DLRs and billing DLRs (billing is correlated to the status DLR by message_id).

The DLR Receiver is the source of truth. A test passes only when the expected DLR has been
received, correlated by message_id, persisted, and read back from the receiver's database
through its query API. The HTTP status of the DLR callback itself is deliberately ignored.

Environment:
    DLR_BASE_URL   receiver base URL (default http://localhost:8080)
    DLR_API_KEY    optional; sent as X-API-Key when the receiver protects its APIs
"""
from __future__ import annotations

import os
import time
import uuid
from dataclasses import dataclass
from decimal import Decimal
from typing import Iterable

import requests

DLR_BASE_URL = os.getenv("DLR_BASE_URL", "http://localhost:8080").rstrip("/")
DLR_API_KEY = os.getenv("DLR_API_KEY")

# Statuses after which the receiver will not change the message state (default state machine).
FINAL_STATUSES = {"DELIVERED", "FAILED", "EXPIRED", "REJECTED"}

_session = requests.Session()
if DLR_API_KEY:
    _session.headers["X-API-Key"] = DLR_API_KEY


class DlrVerificationError(AssertionError):
    """Raised when the expected DLR is not persisted in time or has the wrong status."""


# --------------------------------------------------------------------------------------
# Query side (what tests assert on)
# --------------------------------------------------------------------------------------

def get_dlr(message_id: str, include_events: bool = False, base_url: str = DLR_BASE_URL) -> dict:
    """Current persisted DLR for a message_id. received=False / status=PENDING if none yet."""
    params = {"include_events": "true"} if include_events else None
    response = _session.get(f"{base_url}/api/v1/dlr/{message_id}", params=params, timeout=10)
    response.raise_for_status()
    return response.json()


def wait_for_dlr(message_id: str, expected_status: str, timeout: float = 120, poll_interval: float = 2,
                 base_url: str = DLR_BASE_URL) -> dict:
    """
    Poll the receiver until the DLR for ``message_id`` reaches ``expected_status``.

    Improvements over a naive "first received DLR" check:
      * an intermediate status (e.g. SENT before DELIVERED) keeps polling instead of failing;
      * a *different final* status (e.g. FAILED when DELIVERED was expected) fails immediately;
      * the timeout error includes the last observed state and any rejected callbacks, which is
        usually enough to tell "provider never called back" from "callback was malformed".
    """
    expected_status = expected_status.upper()
    deadline = time.monotonic() + timeout
    last: dict | None = None

    while time.monotonic() < deadline:
        try:
            last = get_dlr(message_id, base_url=base_url)
        except requests.RequestException as exc:  # receiver restarting etc. - keep polling
            last = {"error": str(exc)}
            time.sleep(poll_interval)
            continue

        if last.get("received"):
            status = last.get("status")
            if status == expected_status:
                return last
            if status in FINAL_STATUSES:
                raise DlrVerificationError(
                    f"DLR for message_id={message_id} reached final status {status} "
                    f"(provider_status={last.get('provider_status')}, status_code={last.get('status_code')}), "
                    f"expected {expected_status}")
        time.sleep(poll_interval)

    detail = ""
    if last and last.get("rejected_events"):
        detail = (f"; {last['rejected_events']} callback(s) for this message were REJECTED, "
                  f"last reason: {last.get('last_rejection_reason')}")
    raise DlrVerificationError(
        f"DLR not received with status {expected_status} for message_id={message_id} within {timeout}s; "
        f"last state: {last}{detail}")


def verify_dlrs(message_ids: Iterable[str], expected_status: str | None = None, require_billing: bool = False,
                require_click: bool = False, base_url: str = DLR_BASE_URL) -> dict:
    """One call to POST /api/v1/dlr/verify."""
    body: dict = {"message_ids": list(message_ids)}
    if expected_status:
        body["expected_status"] = expected_status.upper()
    if require_billing:
        body["require_billing"] = True
    if require_click:
        body["require_click"] = True
    response = _session.post(f"{base_url}/api/v1/dlr/verify", json=body, timeout=60)
    response.raise_for_status()
    return response.json()


def wait_for_dlrs(message_ids: Iterable[str], expected_status: str, timeout: float = 300, poll_interval: float = 3,
                  require_billing: bool = False, base_url: str = DLR_BASE_URL) -> dict:
    """
    Bulk version of wait_for_dlr: waits until every message has ``expected_status``
    (and, with ``require_billing=True``, a billing DLR as well).
    """
    ids = list(message_ids)
    expected_status = expected_status.upper()
    deadline = time.monotonic() + timeout
    result: dict = {}
    while time.monotonic() < deadline:
        result = verify_dlrs(ids, expected_status, require_billing=require_billing, base_url=base_url)
        if result.get("all_matched"):
            return result
        wrong_final = [r for r in result["results"]
                       if r.get("received") and r["status"] in FINAL_STATUSES and r["status"] != expected_status]
        if wrong_final:
            raise DlrVerificationError(
                f"{len(wrong_final)} message(s) reached a different final status than {expected_status}: "
                f"{wrong_final[:10]}")
        time.sleep(poll_interval)
    pending = [r["message_id"] for r in result.get("results", []) if not r.get("matched")]
    raise DlrVerificationError(
        f"{len(pending)}/{len(ids)} message(s) did not reach {expected_status}"
        f"{' with billing' if require_billing else ''} within {timeout}s "
        f"(received={result.get('received')}, missing={result.get('missing')}, "
        f"billing_missing={result.get('billing_missing')}); first pending: {pending[:10]}")


def get_clicks(message_id: str, base_url: str = DLR_BASE_URL) -> dict:
    """Short-link click summary + click events for a message (GET /api/v1/dlr/{id}/clicks)."""
    response = _session.get(f"{base_url}/api/v1/dlr/{message_id}/clicks", timeout=10)
    response.raise_for_status()
    return response.json()


def wait_for_click(message_id: str, min_clicks: int = 1, url_key: str | None = None, timeout: float = 120,
                   poll_interval: float = 2, base_url: str = DLR_BASE_URL) -> dict:
    """Poll until at least ``min_clicks`` short-link clicks (optionally on ``url_key``) are recorded."""
    deadline = time.monotonic() + timeout
    summary: dict = {}
    while time.monotonic() < deadline:
        summary = get_clicks(message_id, base_url=base_url)["clicks"]
        if summary.get("clicked") and summary.get("clicks", 0) >= min_clicks and (
                url_key is None or url_key in (summary.get("url_keys") or [])):
            return summary
        time.sleep(poll_interval)
    raise DlrVerificationError(
        f"Expected >= {min_clicks} click(s){' on ' + url_key if url_key else ''} for message_id={message_id} "
        f"within {timeout}s; last clicks: {summary}")


def get_billing(message_id: str, base_url: str = DLR_BASE_URL) -> dict:
    """Billing summary + billing events for a message (GET /api/v1/dlr/{id}/billing)."""
    response = _session.get(f"{base_url}/api/v1/dlr/{message_id}/billing", timeout=10)
    response.raise_for_status()
    return response.json()


def wait_for_billing(message_id: str, expected_units: int | None = None, expected_amount: float | str | None = None,
                     expected_currency: str | None = None, timeout: float = 120, poll_interval: float = 2,
                     base_url: str = DLR_BASE_URL) -> dict:
    """
    Poll until a billing DLR for ``message_id`` is persisted, then check the billed totals.

    ``expected_units`` / ``expected_amount`` are compared with the NET values (debits minus refunds/credits)
    summed over all parts ("<message_id>:1", "<message_id>:2", ...). Keeps polling while the totals are still
    below the expected values, because the parts of a multipart SMS may be billed in separate callbacks.
    Returns the billing summary.
    """
    deadline = time.monotonic() + timeout
    summary: dict = {}
    while time.monotonic() < deadline:
        summary = get_billing(message_id, base_url=base_url)["billing"]
        if summary.get("billed"):
            units_ok = expected_units is None or summary.get("units") == expected_units
            amount_ok = expected_amount is None or Decimal(str(summary.get("total_amount"))) == Decimal(str(expected_amount))
            currency_ok = expected_currency is None or summary.get("currency") == expected_currency.upper()
            if units_ok and amount_ok and currency_ok:
                return summary
        time.sleep(poll_interval)
    raise DlrVerificationError(
        f"Billing DLR for message_id={message_id} not received with units={expected_units}, "
        f"amount={expected_amount}, currency={expected_currency} within {timeout}s; last billing: {summary}")


# --------------------------------------------------------------------------------------
# Simulator side (stands in for the SMS gateway in local automation)
# --------------------------------------------------------------------------------------

@dataclass
class SimulatedDlr:
    message_id: str
    http_status: int  # informational only - never assert on it


def new_message_id() -> str:
    return str(uuid.uuid4())


def send_default_sms_dlr(message_id: str, status: str = "DELIVRD", mobile: str = "917973059161",
                         code: str = "000", correlation_id: str | None = None,
                         dlr_received_at: str | None = None, base_url: str = DLR_BASE_URL,
                         headers: dict | None = None) -> SimulatedDlr:
    now = time.strftime("%Y-%m-%d %H:%M:%S")
    payload = {"payload": {
        "message_id": message_id, "service": "T", "sender": "MSEFSL", "mobile": mobile,
        "status": status, "code": code, "submit_at": now, "dlr_received_at": dlr_received_at or now,
        "entity_id": "", "template_id": "1507165786055955979", "units": "1",
        "correlation_id": correlation_id or f"corr-{message_id}",
    }}
    response = _session.post(f"{base_url}/api/v1/dlr/receive", json=payload,
                             headers={"X-DLR-Source": "DEFAULT_SMS", **(headers or {})}, timeout=10)
    return SimulatedDlr(message_id, response.status_code)


def send_webengage_dlr(message_id: str, status: str = "sms_sent", to_number: str = "919014305913",
                       status_code: str = "0", sms_count: str = "1", base_url: str = DLR_BASE_URL,
                       headers: dict | None = None) -> SimulatedDlr:
    payload = {"version": "1.0", "messageId": message_id, "toNumber": to_number,
               "status": status, "statusCode": status_code, "smsCount": sms_count}
    response = _session.post(f"{base_url}/api/v1/dlr/receive", json=payload,
                             headers={"X-DLR-Source": "WEBENGAGE", **(headers or {})}, timeout=10)
    return SimulatedDlr(message_id, response.status_code)


def send_billing_dlr(message_id: str, parts: int = 1, amount_per_part: str = "1", currency: str = "INR",
                     transaction_type: str = "debit", product: str = "SMS Transactional",
                     base_url: str = DLR_BASE_URL, headers: dict | None = None) -> SimulatedDlr:
    """Simulates the gateway's billing DLR: one event per part, message_id "<id>:<part>"."""
    events = [{
        "transaction_type": transaction_type, "message_id": f"{message_id}:{part}", "product": product,
        "units": 1, "sale_price": float(amount_per_part), "currency": currency, "surcharge": 0,
        "total_amount": float(amount_per_part),
    } for part in range(1, parts + 1)]
    response = _session.post(f"{base_url}/api/v1/dlr/receive", json={"event_type": "billing", "events": events},
                             headers={"X-DLR-Source": "DEFAULT_SMS", **(headers or {})}, timeout=10)
    return SimulatedDlr(message_id, response.status_code)


def send_click_event(message_id: str, url_key: str = "ZIO7ER", visited_count: int = 1,
                     contact: str = "919177873237", clicked_at: str | None = None,
                     base_url: str = DLR_BASE_URL) -> SimulatedDlr:
    """Simulates the link service's short-link click callback."""
    now = time.strftime("%Y-%m-%d %H:%M:%S")
    payload = {"event": "short_link", "url_type": "dynamic", "received_at": now, "data": {
        "visited_count": visited_count, "url_type": "dynamic", "contact": contact, "url_key": url_key,
        "short_url": f"stqa.gtls.in/DUMMY/bBz/{url_key}", "destination_url": "https://example.com/landing",
        "channel": "sms", "ip_address": "152.58.121.146", "operating_system": "Windows",
        "operating_system_version": "10", "browser": "Chrome", "browser_version": "153", "device_type": "desktop",
        "clicked_at": clicked_at or now, "message_id": message_id, "correlation_id": ""}}
    response = _session.post(f"{base_url}/api/v1/dlr/receive", json=payload, timeout=10)
    return SimulatedDlr(message_id, response.status_code)


def send_raw_dlr(body: str, source: str | None = None, base_url: str = DLR_BASE_URL) -> SimulatedDlr:
    headers = {"Content-Type": "application/json"}
    if source:
        headers["X-DLR-Source"] = source
    response = _session.post(f"{base_url}/api/v1/dlr/receive", data=body.encode(), headers=headers, timeout=10)
    return SimulatedDlr("", response.status_code)
