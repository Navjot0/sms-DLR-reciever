#!/usr/bin/env bash
# Sample calls against a running receiver. Usage: ./scripts/curl-examples.sh [base_url]
set -euo pipefail
BASE="${1:-${DLR_BASE_URL:-http://localhost:8080}}"
DIR="$(cd "$(dirname "$0")/../samples" && pwd)"
# Optional auth headers when the receiver runs with authentication enabled:
#   export DLR_API_KEY=...   -> sends X-API-Key
AUTH=()
if [[ -n "${DLR_API_KEY:-}" ]]; then AUTH+=(-H "X-API-Key: ${DLR_API_KEY}"); fi

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

step "1. Default SMS DLR (DELIVRD) with explicit source header"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: DEFAULT_SMS' "${AUTH[@]}" --data @"$DIR/default-sms-delivered.json"; echo

step "2. Same DLR again -> DUPLICATE (state unchanged)"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: DEFAULT_SMS' "${AUTH[@]}" --data @"$DIR/default-sms-delivered.json"; echo

step "3. Default SMS failure (UNDELIV) - source detected from payload structure (no header)"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     "${AUTH[@]}" --data @"$DIR/default-sms-failed.json"; echo

step "3b. Default SMS DLR in flat form (no \"payload\" wrapper, no source header) - as the live gateway sends it"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     "${AUTH[@]}" --data @"$DIR/default-sms-flat.json"; echo

step "4. WebEngage sms_sent (-> SENT, not DELIVERED)"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: WEBENGAGE' "${AUTH[@]}" --data @"$DIR/webengage-sent.json"; echo

step "5. WebEngage sms_delivered (SENT -> DELIVERED), source via query parameter"
curl -sS -X POST "$BASE/api/v1/dlr/receive?source=WEBENGAGE" -H 'Content-Type: application/json' \
     "${AUTH[@]}" --data @"$DIR/webengage-delivered.json"; echo

step "6a. Repeat of the earlier WebEngage sms_sent -> DUPLICATE, stays DELIVERED"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: WEBENGAGE' "${AUTH[@]}" --data @"$DIR/webengage-sent.json"; echo

step "6b. Late SUBMITTED for an already DELIVERED default-SMS message (out of order) -> IGNORED, stays DELIVERED"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: DEFAULT_SMS' "${AUTH[@]}" --data @"$DIR/default-sms-late-submitted.json"; echo

step "7. Invalid DLR (message_id missing) -> 400, stored as REJECTED"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: DEFAULT_SMS' "${AUTH[@]}" --data @"$DIR/default-sms-invalid-missing-message-id.json"; echo

step "8. Malformed JSON -> 400, stored as REJECTED with raw body"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: DEFAULT_SMS' "${AUTH[@]}" --data '{"payload": {"message_id": "oops"'; echo

step "8b. Billing DLR for the delivered default-SMS message (2 parts: <id>:1, <id>:2)"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: DEFAULT_SMS' "${AUTH[@]}" --data @"$DIR/billing-debit.json"; echo

step "8c. Same billing DLR again -> DUPLICATE (not double counted)"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: DEFAULT_SMS' "${AUTH[@]}" --data @"$DIR/billing-debit.json"; echo

step "8d. Billing DLR with an invalid event (message_id missing) -> 400, stored as REJECTED"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     -H 'X-DLR-Source: DEFAULT_SMS' "${AUTH[@]}" --data @"$DIR/billing-invalid-event.json"; echo

step "8e. Short-link click event for the delivered message"
curl -sS -X POST "$BASE/api/v1/dlr/receive" -H 'Content-Type: application/json' \
     "${AUTH[@]}" --data @"$DIR/short-link-click.json"; echo

step "9. Query one DLR (automation lookup by message_id) - includes the billing summary"
curl -sS "$BASE/api/v1/dlr/2ee98174-eec2-46b1-9b3c-baa0853c9538" "${AUTH[@]}"; echo

step "10. Query with full event history + raw payloads"
curl -sS "$BASE/api/v1/dlr/f1189190-3fab-4a74-9130-f932be1de679?include_events=true" "${AUTH[@]}"; echo

step "10b. Billing events for a message"
curl -sS "$BASE/api/v1/dlr/2ee98174-eec2-46b1-9b3c-baa0853c9538/billing" "${AUTH[@]}"; echo

step "10c. Short-link clicks for a message"
curl -sS "$BASE/api/v1/dlr/2ee98174-eec2-46b1-9b3c-baa0853c9538/clicks" "${AUTH[@]}"; echo

step "11. Unknown message -> received=false, status=PENDING"
curl -sS "$BASE/api/v1/dlr/unknown" "${AUTH[@]}"; echo

step "12. Bulk verification"
curl -sS -X POST "$BASE/api/v1/dlr/verify" -H 'Content-Type: application/json' "${AUTH[@]}" \
     --data @"$DIR/verify-request.json"; echo

step "12b. Bulk verification requiring DELIVERED + billing"
curl -sS -X POST "$BASE/api/v1/dlr/verify" -H 'Content-Type: application/json' "${AUTH[@]}" \
     --data '{"message_ids":["2ee98174-eec2-46b1-9b3c-baa0853c9538","7c1d2b0e-3f4a-4c55-9d6e-0a1b2c3d4e5f"],"expected_status":"DELIVERED","require_billing":true}'; echo

step "13. Lookup by correlation_id"
curl -sS "$BASE/api/v1/dlr/search?correlation_id=corr-7c1d2b0e" "${AUTH[@]}"; echo

step "14. Recent rejected callbacks"
curl -sS "$BASE/api/v1/dlr/events/rejected?limit=5" "${AUTH[@]}"; echo

step "14b. Recent rejected billing events"
curl -sS "$BASE/api/v1/dlr/events/billing/rejected?limit=5" "${AUTH[@]}"; echo

step "14c. Reprocess rejected callbacks (e.g. after deploying a fix)"
curl -sS -X POST "$BASE/api/v1/dlr/events/rejected/reprocess?limit=100" "${AUTH[@]}"; echo

step "15. Health + metrics"
curl -sS "$BASE/actuator/health"; echo
curl -sS "$BASE/actuator/prometheus" 2>/dev/null | grep -E '^dlr_(received|delivered|failed|duplicate|rejected|ignored|billing_[a-z]+|click_[a-z]+)_total' || \
  curl -sS "$BASE/actuator/metrics/dlr.received"; echo
