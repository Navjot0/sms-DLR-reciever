"""
Example SMS automation tests.

In the real flow the test calls your SMS API, captures the returned message_id, and the SMS
gateway (or simulator) later calls the DLR receiver. Here `send_*_dlr` plays the gateway so the
examples run against a local receiver. Replace `create_sms()` with a call to your SMS API.

Note: the tests never assert on the callback's HTTP status. They verify the persisted DLR.
"""
import pytest

from dlr_helper import (DlrVerificationError, get_billing, get_dlr, new_message_id, send_billing_dlr, send_click_event,
                        wait_for_click, verify_dlrs,
                        send_default_sms_dlr, send_raw_dlr, send_webengage_dlr, wait_for_billing, wait_for_dlr,
                        wait_for_dlrs)


def create_sms(mobile: str = "917973059161") -> str:
    """Placeholder for: POST {SMS_API}/send -> message_id. Returns a fresh id for the simulator."""
    return new_message_id()


def test_default_sms_delivered():
    message_id = create_sms()
    send_default_sms_dlr(message_id, status="DELIVRD")

    dlr = wait_for_dlr(message_id, expected_status="DELIVERED", timeout=30, poll_interval=0.5)

    assert dlr["received"] is True
    assert dlr["message_id"] == message_id
    assert dlr["source"] == "DEFAULT_SMS"
    assert dlr["provider_status"] == "DELIVRD"
    assert dlr["correlation_id"] == f"corr-{message_id}"


def test_webengage_sent_is_not_delivered_until_delivery_dlr_arrives():
    message_id = create_sms()
    send_webengage_dlr(message_id, status="sms_sent")

    sent = wait_for_dlr(message_id, expected_status="SENT", timeout=30, poll_interval=0.5)
    assert sent["provider_status"] == "sms_sent"

    send_webengage_dlr(message_id, status="sms_delivered")
    delivered = wait_for_dlr(message_id, expected_status="DELIVERED", timeout=30, poll_interval=0.5)
    assert delivered["provider_status"] == "sms_delivered"


def test_failed_delivery_fails_fast_when_delivered_was_expected():
    message_id = create_sms()
    send_default_sms_dlr(message_id, status="UNDELIV", code="034")

    with pytest.raises(DlrVerificationError, match="final status FAILED"):
        wait_for_dlr(message_id, expected_status="DELIVERED", timeout=30, poll_interval=0.5)
    assert get_dlr(message_id)["status"] == "FAILED"


def test_duplicate_and_out_of_order_callbacks_do_not_change_final_state():
    message_id = create_sms()
    send_default_sms_dlr(message_id, status="DELIVRD", dlr_received_at="2026-06-22 11:48:00")
    send_default_sms_dlr(message_id, status="DELIVRD", dlr_received_at="2026-06-22 11:48:00")   # duplicate
    send_default_sms_dlr(message_id, status="SUBMITTED", dlr_received_at="2026-06-22 11:47:40")  # late / older

    dlr = wait_for_dlr(message_id, expected_status="DELIVERED", timeout=30, poll_interval=0.5)
    history = get_dlr(message_id, include_events=True)["events"]
    assert [e["processing_status"] for e in history] == ["APPLIED", "DUPLICATE", "IGNORED"]
    assert dlr["duplicate_count"] == 1


def test_missing_dlr_times_out_with_clear_message():
    with pytest.raises(DlrVerificationError, match="DLR not received"):
        wait_for_dlr(new_message_id(), expected_status="DELIVERED", timeout=2, poll_interval=0.5)


def test_rejected_callback_is_reported_in_timeout_error():
    message_id = create_sms()
    # Gateway bug: mobile missing -> receiver stores it as REJECTED
    send_raw_dlr('{"payload":{"message_id":"%s","status":"DELIVRD"}}' % message_id, source="DEFAULT_SMS")

    with pytest.raises(DlrVerificationError, match="REJECTED, last reason: mobile is missing"):
        wait_for_dlr(message_id, expected_status="DELIVERED", timeout=2, poll_interval=0.5)


@pytest.mark.parametrize("count", [100])
def test_bulk_campaign_all_delivered(count):
    message_ids = [create_sms() for _ in range(count)]
    for i, mid in enumerate(message_ids):
        if i % 2:
            send_default_sms_dlr(mid, status="DELIVRD", mobile=f"91{i:010d}")
        else:
            send_webengage_dlr(mid, status="sms_delivered", to_number=f"91{i:010d}")

    result = wait_for_dlrs(message_ids, expected_status="DELIVERED", timeout=60, poll_interval=1)

    assert result["total"] == count
    assert result["delivered"] == count
    assert result["missing"] == 0


# ------------------------------------------------------------------ billing DLRs

def test_delivered_sms_is_billed():
    message_id = create_sms()
    send_default_sms_dlr(message_id, status="DELIVRD")
    send_billing_dlr(message_id, parts=1, amount_per_part="1")

    dlr = wait_for_dlr(message_id, expected_status="DELIVERED", timeout=30, poll_interval=0.5)
    billing = wait_for_billing(message_id, expected_units=1, expected_amount="1", expected_currency="INR",
                               timeout=30, poll_interval=0.5)

    assert billing["billed"] is True
    assert dlr["billing"]["billed"] is True   # the status lookup carries the same billing summary


def test_multipart_sms_billing_is_summed_and_duplicates_ignored():
    message_id = create_sms()
    send_default_sms_dlr(message_id, status="DELIVRD")
    send_billing_dlr(message_id, parts=3, amount_per_part="0.20")
    send_billing_dlr(message_id, parts=3, amount_per_part="0.20")   # gateway retry -> duplicates

    billing = wait_for_billing(message_id, expected_units=3, expected_amount="0.60", timeout=30, poll_interval=0.5)
    assert billing["parts"] == 3
    statuses = [e["processing_status"] for e in get_billing(message_id)["events"]]
    assert statuses.count("APPLIED") == 3 and statuses.count("DUPLICATE") == 3


def test_bulk_delivered_and_billed():
    message_ids = [create_sms() for _ in range(50)]
    for mid in message_ids:
        send_default_sms_dlr(mid, status="DELIVRD")
        send_billing_dlr(mid)

    result = wait_for_dlrs(message_ids, expected_status="DELIVERED", require_billing=True, timeout=60, poll_interval=1)
    assert result["billed"] == 50
    assert result["billing_missing"] == 0


def test_missing_billing_is_reported():
    message_id = create_sms()
    send_default_sms_dlr(message_id, status="DELIVRD")   # delivered but never billed

    with pytest.raises(DlrVerificationError, match="Billing DLR .* not received"):
        wait_for_billing(message_id, timeout=2, poll_interval=0.5)
    with pytest.raises(DlrVerificationError, match="billing_missing=1"):
        wait_for_dlrs([message_id], expected_status="DELIVERED", require_billing=True, timeout=2, poll_interval=0.5)


# ------------------------------------------------------------------ short-link clicks

def test_short_link_clicks_are_counted_per_message():
    message_id = create_sms()
    send_default_sms_dlr(message_id, status="DELIVRD")
    send_click_event(f"{message_id}:1", visited_count=1, clicked_at="2026-09-25 23:27:24")
    send_click_event(f"{message_id}:1", visited_count=2, clicked_at="2026-09-25 23:30:25")
    send_click_event(f"{message_id}:1", visited_count=2, clicked_at="2026-09-25 23:30:25")   # resent -> duplicate

    clicks = wait_for_click(message_id, min_clicks=2, url_key="ZIO7ER", timeout=30, poll_interval=0.5)
    assert clicks["clicks"] == 2
    assert clicks["visited_count"] == 2
    assert verify_dlrs([message_id], require_click=True)["all_matched"] is True
