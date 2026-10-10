# SMS DLR Receiver

A generic, stateless service that **captures every webhook it receives** (any provider, any format) and then **validates, normalizes, correlates and persists** the delivery reports (DLRs) it recognises, exposing query APIs that automation uses to verify delivery.

> **Universal webhook capture.** Every request to `/api/v1/dlr/receive` is stored first, exactly as received (method, path, query, headers, content type, the body byte for byte), whatever its format: JSON, malformed JSON, text, XML, form data, binary or empty. Interpretation as a DLR happens afterwards and is best effort. A payload the receiver does not recognise is a **successful capture**, not a rejected DLR. See [Universal webhook capture](#universal-webhook-capture).

> **The receiver is the source of truth for DLR verification.**
> An HTTP 200 on the DLR callback does not mean a test passes. A test passes only when the expected DLR has been received, correlated by `message_id`, persisted in PostgreSQL, and read back through `GET /api/v1/dlr/{messageId}` or `POST /api/v1/dlr/verify`.

Supported out of the box: **Default SMS DLR** (`DEFAULT_SMS`), **WebEngage SMS DLR** (`WEBENGAGE`), and the **billing DLR** the SMS gateway sends alongside the status DLR (see [Billing DLRs](#billing-dlrs)). You can add another provider by writing one class (see [Adding a provider](#adding-a-provider)).

---

## Contents

- [Quick start](#quick-start)
- [Live UI](#live-ui)
- [Architecture](#architecture)
- [Universal webhook capture](#universal-webhook-capture)
- [API](#api)
- [Normalization and status mapping](#normalization-and-status-mapping)
- [Correlation](#correlation)
- [Idempotency and duplicates](#idempotency-and-duplicates)
- [State transitions and out-of-order DLRs](#state-transitions-and-out-of-order-dlrs)
- [Validation and rejected DLRs](#validation-and-rejected-dlrs)
- [Billing DLRs](#billing-dlrs)
- [Short-link click events](#short-link-click-events)
- [Meta WhatsApp DLRs](#meta-whatsapp-dlrs)
- [RCS DLRs](#rcs-dlrs)
- [Email DLRs](#email-dlrs)
- [Database](#database)
- [Data retention](#data-retention)
- [Security](#security)
- [Logging](#logging)
- [Monitoring and health](#monitoring-and-health)
- [Automation (pytest)](#automation-pytest)
- [Testing](#testing)
- [Configuration reference](#configuration-reference)
- [Adding a provider](#adding-a-provider)
- [Production notes](#production-notes)

---

## Quick start

### Option A: Docker

Requires Docker with Compose v2.

```bash
cp .env.example .env                   # optional: change ports, DB password, auth settings
docker compose up -d --build           # PostgreSQL 16 + receiver on http://localhost:8080
curl -s localhost:8080/actuator/health
./scripts/curl-examples.sh             # walk through every API with the sample payloads
```

| Command | What it starts |
|---|---|
| `docker compose up -d --build` | PostgreSQL + one receiver on `:8080` |
| `docker compose up -d postgres` | PostgreSQL only, with `dlr` and `dlr_test` databases. Use it for Option B and `mvn verify` |
| `docker compose --profile ha up -d --build` | Adds a second receiver and an nginx load balancer on `:8081`. Both instances share one database, which demonstrates statelessness and cross-instance idempotency |
| `docker compose --profile test run --rm automation` | Runs the pytest examples in a Python container against the receiver |
| `docker compose logs -f dlr-receiver` | Follows the receiver's logs (JSON, from the `docker` profile) |
| `docker compose down` | Stops everything. Add `-v` to also delete the database volume |

Files:

- `Dockerfile`: multi-stage build (Maven + JDK 21 → JRE 21 Alpine), runs as a non-root user, JVM in UTC, health check on `/actuator/health/readiness`.
- `docker-compose.yml`: the services above. Settings come from `.env` (see `.env.example`).
- `docker/postgres-init/01-create-test-db.sql`: creates `dlr_test` the first time the volume is initialised.
- `docker/nginx.conf`: round-robin load balancer for the `ha` profile.

To run the container with authentication, set in `.env` for example `DLR_AUTH_ENABLED=true`, `DLR_API_KEY_ENABLED=true` and `DLR_API_KEYS=<key>`. With `SPRING_PROFILES_ACTIVE=prod` the receiver refuses to start unless authentication is configured.

### Option B: Local JVM

Requires Java 21, Maven 3.9 and PostgreSQL 13+ (for example `docker compose up -d postgres`).

1. Create the database (the defaults are database `dlr`, user `dlr`, password `dlr`):

```sql
CREATE USER dlr WITH PASSWORD 'dlr';
CREATE DATABASE dlr OWNER dlr;
CREATE DATABASE dlr_test OWNER dlr;   -- used by the integration tests
```

2. Run the receiver:

```bash
mvn spring-boot:run
# or
mvn package -DskipTests && java -Duser.timezone=UTC -jar target/sms-dlr-receiver-1.0.0.jar
```

To point at another database, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.

3. Try it:

```bash
curl -s localhost:8080/actuator/health
./scripts/curl-examples.sh             # walk through every API with the sample payloads
```

Flyway creates the schema on startup (`src/main/resources/db/migration`).

---

## Live UI

Open **`http://<host>:8080/`** (it redirects to `/ui/`). For example, `http://140.245.230.67:8080/`.

The page is served by the receiver itself and needs no build step or external assets. It refreshes every 2, 5 or 10 seconds:

| Area | What it shows |
|---|---|
| Header | Service and database health, a live/paused indicator, the time window (15 min to 30 days, or all time), the refresh interval, an API key and a light/dark toggle |
| Totals | Total DLRs, Delivered, Failed (including expired), Rejected, Billing (events and net amount) and Short URL clicks |
| Chart | DLRs per minute (per hour for 7 days and longer), stacked by Default SMS, WebEngage and Short URL clicks, with a hover tooltip |
| **Live feed** | Incoming status DLRs, billing events and clicks, newest first. New rows are highlighted. You can filter by category, show or hide billing, filter by status, and search by message id, mobile or status. Clicking a message id opens the lookup |
| **Message lookup** | One message, with or without `:part`: delivery state and parts, billing summary, click summary, and every callback as a timeline with its raw JSON. It can auto-refresh. `/ui/?message_id=<id>` links directly to it |
| **Bulk verify** | Paste ids and choose the expected status, require billing and/or require click. Shows PASS/FAIL, the counts, and a row per id with missing ids marked |
| **Incoming requests** | Every HTTP request captured on the callback endpoint, newest first, whatever its format: capture id, time, method and path, content type and size, extracted message id, source and interpretation (✓ DLR / unrecognized / malformed JSON / invalid DLR / error) next to the DLR status. Filter by interpretation, search by capture id, message id, body text or header value, load older pages. Selecting a row opens the capture: **Raw body** (exact text, base64 for binary), **JSON** (formatted, with a raw-text fallback when it does not parse), **Headers**, **Query**, **Interpretation**, plus **Copy original body** (fetched from `/raw`, byte-exact), **Copy as JSON** (only enabled for valid JSON) and **Download body**. Pause/Resume and the refresh interval apply to it too |

If a whole billing callback could not be processed, the Billing tile shows a warning with the count. Duplicate and invalid (unparseable) callbacks are not counted or shown in the UI. They are still stored, and the `/api/v1/dlr/events/rejected` and reprocess APIs still work.

The UI reads these endpoints:

- `GET /api/v1/dlr/live/feed?after_status=&after_billing=&after_click=&limit=` returns the newest events across the three tables plus a cursor. Passing the cursor back returns only newer events.
- `GET /api/v1/dlr/live/message?id=<id>:<n>` returns one recipient's status, billing, clicks and callbacks, plus a per-status count of all recipients of the message. Bulk campaigns use one message_id for every recipient, with `:<n>` per recipient. Without `:<n>` it returns the whole message.
- `GET /api/v1/webhooks/requests?after_id=&status=&q=&before_id=&limit=` returns captured requests (Incoming requests tab).
- `GET /api/v1/dlr/live/stats?minutes=60` returns the totals, one entry per category (`DEFAULT_SMS`, `WEBENGAGE`, `SHORT_URL`) and the chart series. `minutes=0` means all time.

Both read from PostgreSQL. The UI polls them rather than using server push, so it shows the same data on every instance behind a load balancer.

With `DLR_PROTECT_QUERY_API=true`, click **API key** in the header and enter a key. It is sent as `X-API-Key` and kept in that browser's local storage. The page itself is static and contains no data.

---

## Architecture

```text
Provider / SMS gateway / any webhook sender
        │  any method · any content type → /api/v1/dlr/receive   (X-DLR-Source optional)
        ▼
CallbackAuthenticationFilter   API key · Bearer · IP allowlist · HMAC (configurable, all off locally)
        ▼                      (auth failures are answered 401/403 and NOT captured)
DlrReceiverController          reads the body as bytes (bounded by DLR_MAX_PAYLOAD_BYTES)
        ▼
WebhookCaptureService
   ├─ 1. capture  ───────────► webhook_requests (committed first; DB down → 503, nothing acknowledged)
   └─ 2. interpret ──────────► result written back on the capture (interpretation_status, message ids, …)
        ▼
DlrProcessingService           provider-independent DLR pipeline (unchanged adapters)
   ├─ SourceResolver           header → query param → payload structure detection
   ├─ event_type = "billing"? ─► DefaultBillingDlrAdapter ─► BillingProcessingService ─► dlr_billing_events
   ├─ DlrAdapterRegistry ──►  DlrProviderAdapter
   │                            ├─ DefaultSmsDlrAdapter
   │                            ├─ WebEngageDlrAdapter
   │                            └─ (your adapter)
   │                                   │ validate + normalize
   │                                   ▼
   │                             NormalizedDlr  (+ StatusNormalizer)
   ├─ DedupKeyGenerator        idempotency key
   ├─ DlrStateMachine          configurable transitions
   └─ repositories             one DB transaction
        ▼
PostgreSQL
   ├─ webhook_requests         every request, raw bytes + headers + query, immutable; derived interpretation
   ├─ dlr_events               every callback: APPLIED / IGNORED / DUPLICATE / REJECTED + raw_payload
   └─ dlr_message_status       current state per message_id  ◄── GET /api/v1/dlr/{id}, POST /verify
   └─ dlr_billing_events       every billing event, joined by message_id ◄── billing summary in the same APIs
```

Design choices:

- **Raw capture is separate from interpretation.** `webhook_requests` keeps what was sent; the DLR tables keep what it meant. Unrecognised payloads only ever live in `webhook_requests`, so they can never mark a message as delivered.
- **Two DLR tables.** `dlr_events` is an append-only audit log of every callback, including its raw payload. `dlr_message_status` holds one row per `message_id` with the state resolved by the state machine. Automation reads the second table. Debugging reads the first.
- **Explicit SQL (Spring JDBC) instead of an ORM.** Idempotency and concurrency depend on `INSERT … ON CONFLICT` and `SELECT … FOR UPDATE`, so they are written out explicitly.
- **No in-memory state.** Every decision is made inside PostgreSQL, so any number of instances can run behind a load balancer.

### Project layout

```text
src/main/java/com/smsframework/dlr
├── controller   DlrReceiverController, DlrQueryController, WebhookCaptureController
├── capture      WebhookCaptureService, WebhookCaptureRepository, GenericFieldExtractor,
│                CapturedRequest, Interpretation, InterpretationStatus
├── service      DlrProcessingService, DlrQueryService, StatusNormalizer, DlrStateMachine,
│                DedupKeyGenerator, SourceResolver, DlrMetrics
├── adapter      DlrProviderAdapter, AbstractJsonDlrAdapter, DefaultSmsDlrAdapter,
│                WebEngageDlrAdapter, DlrAdapterRegistry
├── dto          DefaultSmsDlrRequest, WebEngageDlrRequest, NormalizedDlr, DlrStatusResponse,
│                DlrVerificationRequest/Response, DlrReceiveResponse, DlrEventView, ErrorResponse
├── entity       DlrEvent, DlrMessageStatus
├── repository   DlrEventRepository, DlrMessageStatusRepository
├── mapper       DlrMapper
├── exception    DlrExceptionHandler, DlrValidationException, DlrPersistenceException
├── security     CallbackAuthenticationFilter, CallbackAuthenticator, IpAllowlist, CachedBodyHttpServletRequest
├── billing      BillingDlrAdapter, DefaultBillingDlrAdapter, BillingProcessingService, DlrBillingRepository,
│                DlrBillingEvent, NormalizedBillingEvent, BillingEventRequest, BillingSummary, BillingResult, …
├── config       DlrProperties, DlrConfiguration
├── domain       NormalizedStatus, ProcessingStatus
└── util         MobileMasker, DlrValues
src/main/resources
├── application.yml
└── db/migration/V1__create_dlr_tables.sql, V2__create_dlr_billing_events.sql
automation/      pytest helper + example tests
samples/         sample DLR payloads
scripts/         curl-examples.sh
docker/          postgres-init (creates dlr_test), nginx.conf (ha profile)
Dockerfile, docker-compose.yml, .env.example
```

---

## Universal webhook capture

`/api/v1/dlr/receive` accepts **any** request: `GET`, `POST`, `PUT`, `PATCH` or `DELETE`, any `Content-Type` (or none), any body. No source header is needed. Two steps run for every request:

1. **Capture.** The request is written to `webhook_requests` and committed: method, path, raw query string and parsed parameters (repeated names kept), all headers (names lower-cased, credentials masked), content type, body size, the **exact body bytes** (`BYTEA`, never parsed and re-serialised, so duplicate keys, whitespace, key order, escapes and line endings are kept) and the sender address. Only after the commit is the request acknowledged. If the database is unavailable the answer is **503** and nothing is acknowledged, so the sender retries.
2. **Interpret.** The existing DLR pipeline runs over the body (Default SMS, WebEngage, Meta, RCS, email, billing, short-link clicks). The outcome is written next to the capture: `interpretation_status`, detected source, message id(s), recipient, provider status, normalized status (only when a DLR adapter produced one) and the `dlr_events` row it created.

| `interpretation_status` | Meaning |
|---|---|
| `INTERPRETED` | A known DLR format; processed as before (`APPLIED` / `IGNORED` / `DUPLICATE`) |
| `PARTIAL` | Batch callback (Meta, email, billing) where some items were invalid |
| `INVALID_DLR` | Recognised DLR format with invalid content; stored in `dlr_events` as `REJECTED` as before |
| `NO_DLR` | Known webhook without delivery statuses (e.g. Meta inbound message, SNS confirmation) |
| `UNRECOGNIZED` | Valid JSON that no adapter recognises (or an unknown `X-DLR-Source`) |
| `MALFORMED_JSON` | Looks like JSON (JSON content type or starts with `{` / `[`) but does not parse |
| `NOT_JSON` | Text, XML, form data or binary |
| `EMPTY` | No body |
| `TOO_LARGE` | Body above `DLR_MAX_PAYLOAD_BYTES`: metadata recorded, body not stored, answered 413 |
| `ERROR` | Unexpected error while interpreting; the capture itself is kept |

**Unknown payloads never produce a DLR status.** They are not written to `dlr_events`, so `GET /api/v1/dlr/{id}` keeps `received: false` / `PENDING` and `POST /verify` does not pass. Their provider status is stored exactly as sent (`provider_status`) and never normalized.

**Generic extractor.** For payloads no adapter knows, the receiver still looks for a message id, status and recipient in JSON (breadth-first, up to `extract-max-depth` levels, arrays included) and in form fields, using the configurable field lists `dlr.capture.message-id-fields` / `status-fields` / `recipient-fields`. **`id` is deliberately not a message-id field**: in most webhooks it is the sender's own event id. With duplicate JSON keys the extractor sees the last value (the raw body keeps all of them). For `<id>:<n>` both `<id>:<n>` and `<id>` are recorded, so `?message_id=<id>` finds every capture of the message and `?message_id=<id>:<n>` only that recipient's.

**Capture ID vs message ID.** A `capture_id` (UUID, returned in the `X-Capture-Id` response header and the ack body) identifies one HTTP request received by this service. A `message_id` is the platform's id found inside a payload. One message usually has several captures (sent, delivered, billing, …); a capture may carry no message id at all. Use capture ids to inspect what was sent, and message ids with `/api/v1/dlr/{message_id}` and `/verify` for delivery state.

### Acknowledgement

| Code | Meaning |
|---|---|
| `DLR_CAPTURE_ACK_STATUS` (default **200**) | Captured and committed, whatever the interpretation (also for invalid or unrecognised DLRs). Headers `X-Capture-Id`, `X-Interpretation-Status` |
| 413 | Body larger than `DLR_MAX_PAYLOAD_BYTES`. Body not stored, `captured: false` (request metadata is recorded as `TOO_LARGE`) |
| 401 / 403 | Authentication failed. **Not** captured |
| 503 | Database unavailable. **Not** acknowledged; the sender should retry |

```bash
curl -i -X POST 'localhost:8080/api/v1/dlr/receive?trace=abc' -H 'Content-Type: application/json' \
  --data-binary '{"event":"delivered","data":{"msgId":"demo-77:1","deliveryStatus":"DELIVERED"}}'
```

```text
HTTP/1.1 200
X-Capture-Id: 5d3c9ab1-a6d1-4eaf-bba4-b65d61cfa1cb
X-Interpretation-Status: UNRECOGNIZED

{"source":"UNKNOWN","processing_status":"UNRECOGNIZED","note":"payload structure not recognised by any DLR adapter",
 "captured":true,"capture_id":"5d3c9ab1-…","interpretation_status":"UNRECOGNIZED","extracted_message_id":"demo-77:1"}
```

Known DLRs keep their previous ack bodies (status DLR, Meta / email batch, billing, click) with `captured`, `capture_id`, `interpretation_status` and `extracted_message_id` added to the status-DLR ack.

### Capture API

Protected like the DLR query API (`DLR_PROTECT_QUERY_API=true` requires `X-API-Key`).

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/webhooks/requests` | Newest first. `after_id` (live polling: only newer), `before_id` (older pages), `status`, `source`, `message_id`, `q`, `limit` (≤200). Returns `items`, `cursor`, `next_before_id` |
| `GET` | `/api/v1/webhooks/requests/search?q=…` | `q` matches a capture id, a message id, or text in the body, headers or query string |
| `GET` | `/api/v1/webhooks/requests/latest?message_id=…` | Newest capture (optionally for one message id), full detail |
| `GET` | `/api/v1/webhooks/requests/{captureId}` | Full detail: query params, headers, `body_text` (or `body_base64` for binary), message ids, interpretation |
| `GET` | `/api/v1/webhooks/requests/{captureId}/raw` | The body **exactly as received** (same bytes) with its original `Content-Type` |

`status` also accepts the groups `interpreted` (`INTERPRETED`, `PARTIAL`), `unrecognized` (`UNRECOGNIZED`, `NOT_JSON`, `EMPTY`, `NO_DLR`) and `errors` (`ERROR`, `TOO_LARGE`, `PENDING`).

```bash
curl 'localhost:8080/api/v1/webhooks/requests?message_id=demo-77&limit=10'
curl  localhost:8080/api/v1/webhooks/requests/5d3c9ab1-a6d1-4eaf-bba4-b65d61cfa1cb
curl  localhost:8080/api/v1/webhooks/requests/5d3c9ab1-a6d1-4eaf-bba4-b65d61cfa1cb/raw
```

**Existing automation APIs** are unchanged for recognised DLRs. Additions:

- `GET /api/v1/dlr/{id}` adds `captures` (`total`, `by_interpretation_status`, `latest_capture_id`, `latest_interpretation_status`, `latest_received_at`) when requests carrying that id were captured. It never changes `received` / `status`.
- `GET /api/v1/dlr/{id}/json`: when no DLR (accepted or rejected) exists, it falls back to the newest **uninterpreted** capture for that id and returns its body exactly as received with its original content type and `X-DLR-Processing-Status: UNRECOGNIZED`, `X-Capture-Id`. `?all=true` and `?status=` still return DLRs only.
- Python helpers: `list_captures`, `get_capture`, `get_capture_raw`, `get_latest_capture`, `wait_for_capture`, `send_raw_request` in `automation/dlr_helper.py`.

---

## API

| Method | Path | Purpose |
|---|---|---|
| any | `/api/v1/dlr/receive` | Universal callback endpoint: captures every request, then interprets known DLRs (status, billing, clicks, Meta, RCS, email) |
| `GET` | `/api/v1/webhooks/requests…` | Raw webhook captures (see [Capture API](#capture-api)) |
| `GET` | `/api/v1/dlr/{messageId}` | Current DLR for a message (`?include_events=true` adds the history) |
| `POST` | `/api/v1/dlr/verify` | Bulk verification for automation |
| `GET` | `/api/v1/dlr/{messageId}/events` | Every callback for a message, with raw payloads |
| `GET` | `/api/v1/dlr/{messageId}/billing` | Billing summary and every billing event for a message |
| `GET` | `/api/v1/dlr/{messageId}/clicks` | Short-link click summary and every click event for a message |
| `GET` | `/api/v1/dlr/search?correlation_id=…` or `?external_message_id=…` | Lookup by secondary keys |
| `GET` | `/api/v1/dlr/events/rejected?limit=50` | Most recent rejected callbacks |
| `GET` | `/api/v1/dlr/events/billing/rejected?limit=50` | Most recent rejected billing events |
| `POST` | `/api/v1/dlr/events/{eventId}/reprocess?source=` | Re-run one stored `REJECTED` callback through the pipeline |
| `POST` | `/api/v1/dlr/events/rejected/reprocess?limit=100&source=` | Re-run every `REJECTED` callback not reprocessed yet |
| `GET` | `/actuator/health` | Liveness, readiness and PostgreSQL status |
| `GET` | `/actuator/prometheus` | Metrics |

Repeated slashes in the path are collapsed. For example, `http://host:8080//api/v1/dlr/{id}` (a base URL configured with a trailing slash) works the same as `/api/v1/dlr/{id}`.

### `POST /api/v1/dlr/receive`

How the source is resolved (configurable under `dlr.source-resolution`):

1. Headers `X-DLR-Source`, `X-DLR-Provider`, `source`, `provider`
2. Query parameters `?source=` or `?provider=`
3. Payload structure detection. Each adapter checks whether it recognizes the body.

Aliases are accepted, for example `WE` → `WEBENGAGE` and `default-sms` → `DEFAULT_SMS`.

```bash
curl -X POST localhost:8080/api/v1/dlr/receive \
  -H 'Content-Type: application/json' -H 'X-DLR-Source: DEFAULT_SMS' \
  --data @samples/default-sms-delivered.json
```

```json
{"event_id":1,"source":"DEFAULT_SMS","message_id":"2ee98174-eec2-46b1-9b3c-baa0853c9538",
 "processing_status":"APPLIED","normalized_status":"DELIVERED","current_status":"DELIVERED",
 "note":"first DLR for message"}
```

Response codes are meant for the sender; automation must not depend on them. Every request that is captured is answered with `DLR_CAPTURE_ACK_STATUS` (default 200), **including** invalid and unrecognised DLRs (previously 400). See [Acknowledgement](#acknowledgement) for 413 / 401 / 403 / 503. `dlr.api.rejected-http-status` is no longer used by this endpoint.

### `GET /api/v1/dlr/{messageId}`

**The original DLR is returned as `dlr`.** Both this endpoint and `POST /api/v1/dlr/verify` include a `dlr` field holding the DLR JSON exactly as CPaaS (or the provider) sent it, with every field and null preserved, so automation can check the DLR format and values:

```json
{
  "message_id": "21cc3333-c705-48dd-bf66-c8bf5f8399bb:3",
  "received": true,
  "status": "DELIVERED",
  "provider_status": "DELIVRD",
  "status_code": "000",
  "source": "DEFAULT_SMS",
  "billed": false,
  "clicked": false,
  "dlr": {
    "message_id": "21cc3333-c705-48dd-bf66-c8bf5f8399bb:3", "service": "T", "sender": "DUMMY",
    "mobile": "919000000003", "status": "DELIVRD", "code": "000",
    "submit_at": "2026-10-06 15:29:58", "dlr_received_at": "2026-10-06 15:30:03",
    "entity_id": "17011580464447946654", "template_id": null, "units": "1", "correlation_id": null
  }
}
```

For an id with a recipient suffix (`<id>:<n>`, one recipient of a bulk send), `dlr`, `status`, `provider_status` and `status_code` in `/verify` are that recipient's own. When several DLRs exist for one id, `dlr` is the one that decided the status: the first final one, or the read receipt for WhatsApp, RCS and email.

```json
{
  "message_id": "2ee98174-eec2-46b1-9b3c-baa0853c9538",
  "source": "DEFAULT_SMS",
  "received": true,
  "provider_status": "DELIVRD",
  "status": "DELIVERED",
  "status_code": "000",
  "mobile": "917973059161",
  "units": 2,
  "received_at": "2026-06-22T11:47:33",
  "submit_at": "2026-06-22T11:47:33",
  "correlation_id": "7589298579…",
  "sender": "MSEFSL",
  "template_id": "1507165786055955979",
  "event_count": 1,
  "duplicate_count": 0,
  "first_received_at": "2026-06-22T06:17:33.102Z",
  "status_updated_at": "2026-06-22T06:17:33.102Z"
}
```

`received_at` is the provider's DLR timestamp. If the provider sends none (WebEngage), it is the time the receiver recorded the current status, expressed in `dlr.timezone`.

If no DLR exists yet:

```json
{"message_id":"unknown","received":false,"status":"PENDING"}
```

If callbacks for that `message_id` arrived but were **rejected**, the response adds `rejected_events` and `last_rejection_reason`. This lets automation tell "the provider never called back" apart from "the callback was malformed".

### `GET /api/v1/dlr/{messageId}/json` – DLR JSON only

Returns **only** the DLR, exactly as CPaaS (or the provider) sent it: the original text byte for byte, with the same key order, spacing and escaping (for example `https:\/\/…` stays escaped). Nothing is added. The text is kept in `dlr_events.raw_body` (migration `V11`); DLRs stored before that come back as the stored JSON, with the same values but keys in PostgreSQL's order. The live UI's JSON view and Copy button also show and copy the original text.

```bash
curl http://localhost:8080/api/v1/dlr/1522ff82-2818-4670-b24e-306f0cf9266b/json
```

```json
{
  "message_id": "1522ff82-2818-4670-b24e-306f0cf9266b", "service": "T", "sender": "DUMMY",
  "mobile": "919034424020", "status": "REJECTED", "code": "807",
  "submit_at": "2026-10-06 15:29:58", "dlr_received_at": "2026-10-06 15:29:58",
  "entity_id": "17011580464447946654", "template_id": null, "units": "2", "correlation_id": null
}
```

- **404** with `{"message_id": …, "received": false}` while no DLR has arrived, so automation can poll it.
- A DLR that arrived but could not be processed (stored as `REJECTED`, e.g. a format the receiver did not recognise yet) is still returned exactly as sent, with the header `X-DLR-Processing-Status: REJECTED` and the reason in `X-DLR-Rejection-Reason`. Accepted DLRs carry `X-DLR-Processing-Status: ACCEPTED`.
- `<id>:<n>` returns that recipient's own DLR.
- When several DLRs exist for one id, it returns the one that decided the status. Add **`?all=true`** to get a JSON array of every DLR received for the id, oldest first (`[]` when none).
- **`?status=<status>`** returns the DLR the platform sent for one particular status, for example `?status=submitted`, `sent`, `delivered`, `read`, `failed` or `rejected`. It matches the provider's own value (`Submitted`, `DELIVRD`, `sms_failed`, …) or the normalized one (`SENT`, `DELIVERED`, `READ`, `FAILED`, `REJECTED`), case-insensitively; `submit` also matches `Submitted`. It answers 404 while no DLR with that status has arrived.
- `?all=true` includes every DLR received for the id, including ones that could not be processed; only exact repeats are left out.
- **`POST /api/v1/dlr/json`** with `{"message_ids": ["id1", "id2:3", …]}` returns a JSON array of DLRs in the same order, with `null` for an id that has no DLR yet.

Python: `get_dlr_payload(id)`, `wait_for_dlr_payload(id)`, `get_all_dlr_payloads(id)` and `get_dlr_payloads([ids])` in `automation/dlr_helper.py`.

### `POST /api/v1/dlr/verify`

```json
{ "message_ids": ["MSG-001", "MSG-002", "MSG-003"], "expected_status": "DELIVERED" }
```

`expected_status` is optional. When you send it, each result gets a `matched` flag and the response adds `matched` and `all_matched`.

```json
{
  "total": 3, "received": 3, "delivered": 2, "failed": 1, "expired": 0, "rejected": 0,
  "sent": 0, "unknown": 0, "missing": 0,
  "expected_status": "DELIVERED", "matched": 2, "all_matched": false,
  "results": [
    {"message_id":"MSG-001","received":true,"status":"DELIVERED","provider_status":"DELIVRD","status_code":"000","source":"DEFAULT_SMS","matched":true},
    {"message_id":"MSG-002","received":true,"status":"DELIVERED", "...": "..."},
    {"message_id":"MSG-003","received":true,"status":"FAILED", "...": "..."}
  ]
}
```

A request may contain up to 10,000 ids (`dlr.api.max-verify-ids`). They are resolved with a single `WHERE message_id = ANY(?)` query.

---

## Normalization and status mapping

| Provider status (case-insensitive) | Normalized |
|---|---|
| `DELIVRD`, `DELIVERED`, `SMS_DELIVERED` | `DELIVERED` |
| `UNDELIV`, `FAILED`, `SMS_FAILED` | `FAILED` |
| `EXPIRED`, `SMS_EXPIRED` | `EXPIRED` |
| `REJECTD`, `REJECTED`, `SMS_REJECTED` | `REJECTED` |
| `READ` / `read` (WhatsApp) | `READ` |
| `SMS_SENT` / `sms_sent`, `SUBMITTED`, `sent` (WhatsApp) | `SENT` |
| anything else | `UNKNOWN` |

The original value is always kept in `provider_status`. The mapping is set in `dlr.status-mapping`, and you can override it per provider with `dlr.provider-status-mapping.<SOURCE>`. Conflicting mappings stop the service at startup.

WebEngage `sms_sent` is **`SENT`, never `DELIVERED`**. A later `sms_delivered` moves the message to `DELIVERED`.

The Default SMS DLR is accepted in two shapes with the same fields: **wrapped** as `{"payload": {"message_id": …, "mobile": …, "status": …}}`, and **flat** as `{"message_id": …, "mobile": …, "status": …, "code": …}`. The flat shape is what the live gateway sends (`samples/default-sms-flat.json`). Both are detected without an `X-DLR-Source` header.

| Default SMS field | Normalized | WebEngage field | Normalized |
|---|---|---|---|
| `payload.message_id` | `message_id` | `messageId` | `message_id` |
| `payload.mobile` | `mobile` | `toNumber` | `mobile` |
| `payload.status` | `provider_status` | `status` | `provider_status` |
| `payload.code` | `status_code` (and `error_code` on failures) | `statusCode` | `status_code` (and `error_code` on failures) |
| `payload.units` | `units` | `smsCount` | `units` |
| `payload.correlation_id` | `correlation_id` | `message` | `error_reason` (failures) |
| `sender`, `service`, `entity_id`, `template_id`, `submit_at`, `dlr_received_at` | same name | `timestamp` (optional) | `dlr_received_at` |

Empty strings become `NULL`, for example `"entity_id": ""`. An unparseable timestamp or unit count becomes `NULL` without rejecting the DLR, because the raw payload is kept either way.

---

## Correlation

`message_id` is the **primary key for automation**. `dlr_message_status.message_id` is the primary key of that table.

These secondary keys are stored and indexed: `external_message_id`, `correlation_id`, `campaign_id`, `request_id` and `provider_event_id`. The Default SMS adapter reads them when the gateway sends them. When a later DLR omits a correlation field, the value already stored is kept (`COALESCE`), so correlation data is never lost.

---

## Idempotency and duplicates

- Each valid DLR gets a `dedup_key`: the SHA-256 of `source | message_id | provider_status | status_code | provider_event_id | dlr_received_at`. You can change the fields with `dlr.idempotency.key-fields`.
- `dlr_events` has a **partial unique index** on `dedup_key` (`WHERE processing_status <> 'DUPLICATE'`). Events are written with `INSERT … ON CONFLICT DO NOTHING`, so when the same callback reaches two instances at the same moment, PostgreSQL lets exactly one win.
- The losing copy is stored as `processing_status = DUPLICATE` with `duplicate_of = <original id>`, and `dlr_message_status.duplicate_count` is incremented. The state is never touched. Set `dlr.idempotency.store-duplicates=false` to only count duplicates without storing them.
- Different statuses always produce different keys, so a legitimate progression such as `SENT → DELIVERED` is never treated as a duplicate.
- The same normalized status with a different key (for example `DELIVERED` again with a new timestamp) is stored as `IGNORED` ("message already in state DELIVERED"). The first `DELIVERED` wins.

## State transitions and out-of-order DLRs

Default rules in `dlr.state-machine.transitions`:

```text
UNKNOWN   ─► SENT | DELIVERED | READ | FAILED | EXPIRED | REJECTED
SENT      ─► DELIVERED | READ | FAILED | EXPIRED | REJECTED
DELIVERED ─► READ   (WhatsApp read receipt)
READ, FAILED, EXPIRED, REJECTED ─► (final, nothing)
```

Every transition is evaluated against the persisted state while that row is locked (`SELECT … FOR UPDATE`):

- `DELIVERED` followed by a late `SENT` is stored as `IGNORED`. The status stays `DELIVERED`, so out-of-order DLRs are handled.
- `DELIVERED` followed by `FAILED` is stored as `IGNORED`. Final states are protected by default.
- To allow an override, for example a late `DELIVERED` replacing `FAILED` after an operator retry, set `FAILED: [DELIVERED]`.

The integration tests send 60 messages × 3 callbacks (`SUBMITTED`, final, `SUBMITTED`) shuffled across 24 threads. Every message ends in its correct final state.

---

## Validation and rejected DLRs

| Provider | Required | Optional |
|---|---|---|
| Default SMS | `message_id`, `mobile`, `status` (inside `payload` for the wrapped form) | `entity_id`, `template_id`, `correlation_id`, … |
| WebEngage | `messageId`, `toNumber`, `status` | `statusCode`, `smsCount`, `version` |

Field lengths are also checked against the column sizes. Every request is kept in `webhook_requests`. Payloads in a **recognised** DLR format that fail validation are additionally stored in `dlr_events` with `processing_status = REJECTED`, a `rejection_reason` and the raw payload (capture `interpretation_status = INVALID_DLR`):

| Situation | `rejection_reason` |
|---|---|
| required field missing | `message_id is missing` (several reasons are joined with `; `) |
| wrong JSON type | `invalid value for field 'payload.message_id'` |
| billing / click / batch item invalid | `billing: events is missing`, `short_link: data.url_key is missing`, … |

Payloads that are **not** a recognised DLR are no longer `REJECTED` DLRs. They are captures only (no `dlr_events` row):

| Situation | capture `interpretation_status` |
|---|---|
| not JSON / malformed JSON | `NOT_JSON` / `MALFORMED_JSON` |
| empty body | `EMPTY` |
| unknown `X-DLR-Source` (e.g. `ACME`) | `UNRECOGNIZED` |
| no source and structure not recognised | `UNRECOGNIZED` |
| too large | `TOO_LARGE` (413) |

When a rejected DLR still contains a readable message id, it is stored in `message_id`, so `GET /api/v1/dlr/{id}` can report it (`rejected_events`, `last_rejection_reason`). Unrecognised payloads with a message id appear there under `captures`.

### Reprocessing rejected callbacks

Rejected callbacks keep their complete raw payload, so they can be applied later, for example after deploying support for a new payload shape:

```bash
curl -X POST 'localhost:8080/api/v1/dlr/events/6/reprocess'                          # one row (id from dlr_events)
curl -X POST 'localhost:8080/api/v1/dlr/events/6/reprocess?source=DEFAULT_SMS'       # force the provider
curl -X POST 'localhost:8080/api/v1/dlr/events/rejected/reprocess?limit=100'         # all not yet reprocessed
```

How reprocessing behaves:

- The stored raw payload goes through the normal pipeline, including idempotency and the state machine. Running it twice gives a `DUPLICATE`, never a second `APPLIED`.
- The original row stays `REJECTED` for the audit trail, and its `processing_note` is set to `reprocessed -> APPLIED (event N)`.
- Rows that were not valid JSON when received are marked `reprocess skipped`.
- A retry that is rejected again is marked, so bulk reprocessing never loops over it.

These endpoints sit under `/api/v1/dlr/`, so `DLR_PROTECT_QUERY_API=true` puts them behind the API key.

---

## Billing DLRs

For each SMS the gateway sends two callbacks to the same `POST /api/v1/dlr/receive` endpoint: the **status DLR** (`DELIVRD`, `UNDELIV`, …) and a **billing DLR**:

```json
{
  "event_type": "billing",
  "events": [
    {
      "transaction_type": "debit",
      "message_id": "9b1b0309-4d49-48be-b2c1-0283892ded9e:1",
      "product": "SMS Transactional",
      "units": 1,
      "sale_price": 1,
      "currency": "INR",
      "surcharge": 0,
      "total_amount": 1
    }
  ]
}
```

**Detection.** A body with `"event_type": "billing"` is handled as billing, whatever the headers say. It never goes through the status adapters and never changes the status DLR state. `X-DLR-Source` is optional. Without it the source is `DEFAULT_SMS` (`dlr.billing.default-source`). Only sources in `dlr.billing.sources` may send billing. Any other source is rejected.

**Correlation.** The billing `message_id` carries a part suffix: `<message_id>:<part>`. The receiver stores it three ways:

| Column | Value for `9b1b0309-…:1` | Used for |
|---|---|---|
| `billing_message_id` | `9b1b0309-…:1` | Exactly as sent (audit, idempotency) |
| `message_id` | `9b1b0309-…` | Join key with the status DLR |
| `part_number` | `1` | Multipart SMS: each part is billed separately |

Only a numeric suffix after the last `:` is treated as a part number. `abc-123` or `urn:msg:abc` are kept unchanged. The separator is set by `dlr.message-id-part-separator`; an empty value disables splitting.

**Status DLRs use the same rule.** For campaigns the gateway sends the status DLR as `"message_id": "<id>:1"` (often only for part 1) and the billing DLR as `<id>:1` … `<id>:4`. The status DLR is stored with:

- `message_id`: `<id>`
- `provider_message_id`: `<id>:1`
- `part_number`: `1`

Both DLRs therefore land on the same message.

- **Lookups:** `GET /api/v1/dlr/<id>` and `GET /api/v1/dlr/<id>:1` return the same message. The second form adds `requested_id`. `POST /verify` accepts either form and reports each id as it was sent.
- **Per-part status:** when parts are present, the response carries `"parts": [{"part": 1, "provider_message_id": "<id>:1", "provider_status": "DELIVRD", "status": "DELIVERED"}, …]`.
- **State machine:** it applies per message. A second part with the same status is stored as `IGNORED`. An exact repeat of a part is a `DUPLICATE`.
- **Existing rows:** migration `V3__multipart_message_ids.sql` moves rows stored earlier with `<id>:<part>` onto `<id>`.

**Totals.** Per message, over `APPLIED` billing events: `units` and `total_amount` are **net**. Debit types (`debit`) add; credit types (`credit`, `refund`, `reversal`) subtract. Both lists are configurable. `debit_amount` and `credit_amount` are also returned. `currency` is `MIXED` if events disagree.

**Order does not matter.** Billing may arrive before or after the status DLR. `GET /api/v1/dlr/{id}` shows the billing as soon as it exists, even while `status` is still `PENDING`.

**Idempotency.** Each event gets a dedup key: SHA-256 of `source`, `billing_message_id`, `transaction_type`, `units`, `currency` and `total_amount`. `1` and `1.0` count as the same amount. It is protected by a partial unique index, like status DLRs. A resent billing callback is stored as `DUPLICATE` and never double-counts. A later refund for the same part is a different event and is applied.

**Validation.** Per event, `message_id` and `transaction_type` are required. `units` must be a non-negative whole number. `sale_price`, `surcharge` and `total_amount` must be numbers, sent either as JSON numbers or as numeric strings.

- An invalid **event** is stored in `dlr_billing_events` as `REJECTED` with its reason and raw JSON. The other events in the same callback are still applied, and the batch status becomes `PARTIAL`.
- An invalid **callback** is stored in `dlr_events` as `REJECTED`, like any other rejected callback. That covers `events` missing, empty or not an array, a disallowed source, and malformed JSON.

**Acknowledgement** (for the gateway, not for automation):

```json
{"event_type":"billing","source":"DEFAULT_SMS","batch_id":"c3162625-…","processing_status":"APPLIED",
 "total":1,"applied":1,"duplicates":0,"rejected":0,
 "events":[{"index":0,"event_id":61,"message_id":"9b1b0309-…","billing_message_id":"9b1b0309-…:1","processing_status":"APPLIED"}]}
```

`processing_status` is one of `APPLIED`, `DUPLICATE`, `PARTIAL` or `REJECTED`. HTTP 200 is returned unless every event was rejected (400).

**Billing in the query APIs.**

`GET /api/v1/dlr/{messageId}` includes:

```json
"billing": {"billed": true, "events": 2, "parts": 2, "units": 2, "debit_amount": 2, "credit_amount": 0,
            "total_amount": 2, "currency": "INR", "last_billed_at": "2026-06-22T06:17:34Z"}
```

When nothing has been billed, this is `{"billed": false, "events": 0}`.

`GET /api/v1/dlr/{messageId}/billing` returns that summary plus every billing event (`APPLIED`, `DUPLICATE`, `REJECTED`) with its raw JSON.

`POST /api/v1/dlr/verify` always reports `billed` and `billing_missing` counts. Each result carries `billed`, `billed_units`, `billed_amount` and `currency`. With `"require_billing": true`, a message only counts as `matched` when it has the expected status **and** a billing DLR:

```json
{"message_ids": ["MSG-001", "MSG-002"], "expected_status": "DELIVERED", "require_billing": true}
```

---

## Short-link click events

When a recipient opens a short link in the SMS, the link service posts a click event to the same `POST /api/v1/dlr/receive` endpoint:

```json
{"event": "short_link", "url_type": "dynamic", "received_at": "2026-09-25 23:27:25",
 "data": {"visited_count": 2, "contact": "919177873237", "url_key": "ZIO7ER",
          "short_url": "stqa.gtls.in/DUMMY/bBz/ZIO7ER", "destination_url": "https://login.microsoftonline.com/common/login",
          "channel": "sms", "ip_address": "152.58.121.146", "operating_system": "Windows", "browser": "Chrome",
          "device_type": "desktop", "clicked_at": "2026-09-25 23:27:24",
          "message_id": "68c3d2ef-9ced-46b8-aa1c-13be73ad9321:1", "correlation_id": ""}}
```

**Detection and storage.** A body whose `event` is `short_link` (configurable: `dlr.clicks.event-types`) is a click. It never changes the delivery state. `X-DLR-Source` is optional, and the default source is `DEFAULT_SMS`. Each click becomes one row in `dlr_click_events` (migration `V4`), holding every field above plus the raw payload. `message_id` is stored without the `:<part>` suffix (the original is kept in `provider_message_id`), so clicks correlate with the status and billing DLRs.

**Every click counts once.** `visited_count` 2, then 3, gives two events. A resent identical callback is stored as `DUPLICATE`. The dedup key covers message id, `url_key`, `clicked_at`, `visited_count` and `ip_address`.

**Validation.** Required: `event`, `data`, `data.message_id` and `data.url_key`. An invalid click is stored in `dlr_events` as `REJECTED`, for example `short_link: data.url_key is missing`.

**In the query APIs.**

`GET /api/v1/dlr/{id}` (with the base id or `…:1`) includes:

```json
"clicks": {"clicked": true, "clicks": 2, "visited_count": 3, "unique_ips": 1, "url_keys": ["ZIO7ER"],
           "first_clicked_at": "2026-09-25T23:27:24", "last_clicked_at": "2026-09-25T23:30:25",
           "last_destination_url": "https://login.microsoftonline.com/common/login", "last_device_type": "desktop"}
```

In that block, `clicks` counts the click callbacks received, while `visited_count` is the highest counter the link service reported. When nothing has been clicked, the block is `{"clicked": false, "clicks": 0}`.

`GET /api/v1/dlr/{id}/clicks` returns the summary plus every click event with its raw payload.

`POST /api/v1/dlr/verify` always reports `clicked` and `click_missing`, and each result carries `clicked` and `click_count`. With `"require_click": true`, a message only counts as `matched` once it has been clicked.

**Pytest helpers.** `wait_for_click(message_id, min_clicks=1, url_key=None)`, `get_clicks(message_id)`, `verify_dlrs(..., require_click=True)` and the simulator `send_click_event(...)`.

**Metrics.** `dlr_click_received_total`, `dlr_click_applied_total`, `dlr_click_duplicate_total` and `dlr_click_rejected_total`.

---

## Meta WhatsApp DLRs

Meta (WhatsApp Cloud API) status webhooks are posted to the same `POST /api/v1/dlr/receive`. They are detected from `"object": "whatsapp_business_account"`, or you can send `?source=META` (aliases `WHATSAPP`, `WA`). Sample: `samples/meta-whatsapp-status.json`.

```json
{"object":"whatsapp_business_account","entry":[{"id":"<WABA>","changes":[{"field":"messages","value":{
  "messaging_product":"whatsapp","metadata":{"display_phone_number":"…","phone_number_id":"…"},
  "statuses":[{"id":"wamid.HBgL…","status":"delivered","timestamp":"1727780000","recipient_id":"9190…",
               "biz_opaque_callback_data":"…","conversation":{"id":"…"},"pricing":{"category":"utility"}}]}}]}]}
```

- **One webhook can carry several statuses.** Each one is validated, de-duplicated, run through the state machine and stored as its own row in `dlr_events`, with source `META`. The stored raw payload is that one status plus the webhook's `metadata`.
- **Status mapping:** `sent` → `SENT`, `delivered` → `DELIVERED`, `read` → `READ`, `failed` → `FAILED`, anything else → `UNKNOWN`. A `read` after `delivered` moves the message to `READ`. A late `delivered` after `read` is `IGNORED`. In `/verify`, `READ` counts as delivered and satisfies `expected_status: DELIVERED`.
- **Message id:** every status must carry the platform's own `message_id` (`{"id":"wamid…","message_id":"<uuid>"}`). It is stored as `message_id`, and the wamid as `external_message_id` (find it with `/search?external_message_id=wamid…`). A status with only the wamid is stored as `REJECTED` ("message_id is missing") and still answered with 200. Set `DLR_META_REQUIRE_MESSAGE_ID=false` to fall back to the wamid instead.
- **Field mapping:** `recipient_id` → `mobile`, `timestamp` (epoch seconds) → `dlr_received_at`, `errors[0].code` → `status_code` / `error_code`, `errors[0].title` + `message` + `error_data.details` → `error_reason`, `biz_opaque_callback_data` → `correlation_id`, `conversation.id` → `request_id`, `pricing.category` → `service`, `metadata.display_phone_number` → `sender`.
- **Always answered with 200.** Meta retries non-2xx responses and eventually disables the webhook, so invalid statuses are stored as `REJECTED` but still acknowledged. The response lists each status's outcome.
- **Webhooks without statuses** (inbound messages, template or account updates) are acknowledged with 200 and not stored.
- **Callback URL check:** when the URL is saved in Meta, Meta calls `GET /api/v1/dlr/receive?hub.mode=subscribe&hub.verify_token=…&hub.challenge=…`. Set `DLR_META_VERIFY_TOKEN` to the same token, and the receiver answers with the challenge.
- **Signature:** Meta signs with `X-Hub-Signature-256: sha256=<hex>`. To check it, set `DLR_HMAC_ENABLED=true`, `dlr.security.hmac.header-name=X-Hub-Signature-256` and `DLR_HMAC_SECRET=<app secret>`. HMAC applies to every callback, so only enable it if all senders sign.
- **Live UI:** a **Meta WhatsApp** card (Delivered, Read, Failed, Rejected, Sent/pending), a Meta series in the chart, a Meta filter in the live feed, and a **Read** tile.
- **Automation:** `dlr_helper.send_meta_dlr(message_id, status="delivered" | "read" | "sent" | "failed", …)` simulates a webhook. `wait_for_dlr` and `verify_dlrs` use that `message_id`.

---

## RCS DLRs

RCS delivery reports are posted to the same `POST /api/v1/dlr/receive`, in the operator's own wire format. Four formats are supported: the same ones the RCS Simulator sends. The operator is detected from the payload, so no header is needed. You can also send `?source=RCS` (aliases `JIO`, `DOTGO`, `VI`, `AIRTEL`). All of them are stored with source `RCS`, and the operator goes in the `service` column. Samples: `samples/rcs-platform-dispatch.json`, `rcs-platform-delivery.json`, `rcs-platform-jio-sent.json` (the platform's own webhook), and the operator formats `rcs-jio.json`, `rcs-dotgo.json`, `rcs-vi.json`, `rcs-airtel.json`. For the platform webhook the operator (JIO / DOTGO / VI / AIRTEL) is worked out from `additional_data.provider_type`, `agent.provider_type` or `additional_data.provider`; the agent name is stored as `sender`; inbound messages (`message.direction` = `inbound`) are rejected.

| Operator | Detected by | Message id | Status | Phone | Time | Error |
|---|---|---|---|---|---|---|
| **CPaaS platform webhook** | `event_type` = `message_*` + `message` / `agent` object | `message_id` (the platform id the send API returns); `external_message_id` and `corelation_id` are kept | `status` (`Submitted`, `sent`, `delivered`, `read`, `failed`) | `message.number` | `timestamp` | `delivery_info.failure_reason` / `error_message` |
| Jio | `entityType` + `entity` | `entity.messageId` | `entity.eventType` (`MESSAGE_SENT`, `MESSAGE_DELIVERED`, `MESSAGE_READ`, `MESSAGE_FAILED`) | `userPhoneNumber` | `entity.sendTime` | `entity.error.code` / `message` |
| Dotgo | `message.data` (base64 JSON) | `messageId` | `eventType` (`SENT`, `DELIVERED`, `READ`, `FAILED`) | `senderPhoneNumber` | `sendTime` | `code` / `reason` |
| Vi | `RCSMessage` | `RCSMessage.msgId` | `RCSMessage.status` (`sent`, `delivered`, `read`, `failed`) | `messageContact.userContact` | `RCSMessage.timestamp` | – |
| Airtel | flat `messageId` + `eventType` | `messageId` | `eventType` (`DELIVERED`, `READ`, `FAILED`, `INTERNAL_ERROR`) | – | `sendTime` | `error.code` / `message` |

- **Statuses** normalize to `SENT`, `DELIVERED`, `READ` and `FAILED`, with the same state machine as WhatsApp: `SENT → DELIVERED → READ`. Airtel has no "sent" webhook.
- **Sender:** the Jio `botId`, Dotgo `business_id` or Airtel `agentId` is stored as `sender`. A leading `+` is removed from the phone number.
- **Dotgo** wraps the event as base64 JSON in a Pub/Sub push envelope. If `message.data` cannot be decoded, the DLR is stored as `REJECTED`.
- **Live UI:** an **RCS** card that counts messages (Delivered including read, Read, Failed, Rejected, Sent/pending), an RCS series in the chart and an RCS filter in the live feed. The feed's Details column shows the operator.
- **Automation:** `dlr_helper.send_rcs_dlr(message_id, status="delivered", operator="jio" | "dotgo" | "vi" | "airtel")`.

---

## Email DLRs

Email delivery reports are posted to the same `POST /api/v1/dlr/receive`, in the provider's own format. Two providers are supported, the same ones the platform's mail package consumes: **Amazon SES** and **Kenscio**. The provider is detected from the payload, or you can send `?source=EMAIL` (aliases `SES`, `AMAZON_SES`, `KENSCIO`, `MAIL`). All are stored with source `EMAIL`, with the provider in the `service` column. Samples: `samples/email-ses-delivery.json`, `email-ses-bounce.json`, `email-ses-sns-notification.json`, `email-kenscio.json`.

| | Amazon SES | Kenscio |
|---|---|---|
| Shape | One event object, or the same event wrapped by SNS (`{"Type":"Notification","Message":"<json>"}`) | One event object or a list of events |
| Message id | `mail.messageId` | `xJob` / `x-job` (the message hash) |
| Event | `eventType` (or `notificationType`): `Send`, `Delivery`, `Open`, `Click`, `Bounce`, `Complaint`, `Reject`, `DeliveryDelay` | `eventType` / `event-type`: `DELIVER`, `OPEN`, `CLICK`, `BOUNCE`, `COMPLAINT`, `UNSUB` |
| Recipient | bounced / delivered recipient, else `mail.destination[0]` | `address` |
| Time | the event's own `timestamp`, else `mail.timestamp` | `eventTimestamp` / `event-timestamp` |
| Sender | `mail.source` | – |
| Bounce details | recipient `status` (e.g. `5.1.1`) and `diagnosticCode`, else bounce type and sub-type | – |

- **Statuses:** Send → `SENT`, Delivery / DELIVER → `DELIVERED`, Open and Click → `READ` (opened), Bounce → `FAILED`, Reject → `REJECTED`. Complaint and Unsubscribe are stored as events with status `UNKNOWN`; they do not change the state of a delivered message. The original event name is always kept in `provider_status`.
- **Recipient address** is stored in the `mobile` column, which migration `V9` widens to 320 characters.
- **Several events per callback** (a Kenscio list) are each stored as their own row. The callback is always answered with 200, and invalid events are stored as `REJECTED`.
- **SNS subscription:** a `SubscriptionConfirmation` is acknowledged and not stored. The receiver does not open the `SubscribeURL` itself; it returns it in the response and logs it so you can confirm the subscription once.
- **Live UI:** an **Email** card that counts messages (Delivered including opened, Opened, Clicked, Bounced, Rejected, Sent/pending), an Email series in the chart and an Email filter in the live feed.
- **Automation:** `dlr_helper.send_email_dlr(message_id, event="delivery", to="user@example.com", provider="ses" | "kenscio")`.

---

## Database

The schema is in `src/main/resources/db/migration/`. `V12__webhook_requests.sql` adds the capture table (see below). Earlier migrations: (`V1__create_dlr_tables.sql`, `V2__create_dlr_billing_events.sql`, `V3__multipart_message_ids.sql`, which adds `provider_message_id` and `part_number` to `dlr_events`, and `V4__create_dlr_click_events.sql`).

- `dlr_events` has the columns you specified: `id`, `source`, `message_id`, `external_message_id`, `correlation_id`, `mobile`, `sender`, `service`, `provider_status`, `normalized_status`, `status_code`, `error_code`, `error_reason`, `submit_at`, `dlr_received_at`, `entity_id`, `template_id`, `units`, `raw_payload JSONB NOT NULL`, `processing_status`, `created_at`, `updated_at`. It adds `campaign_id`, `request_id`, `provider_event_id`, `rejection_reason`, `processing_note`, `dedup_key`, `duplicate_of` and `receiver_instance`.
  - `message_id` may be `NULL` **only** for `REJECTED` rows. A `CHECK` constraint enforces this, so a callback missing its id can still be stored.
  - Indexes: `message_id`, `external_message_id`, `correlation_id`, `mobile`, `normalized_status`, `source`, `created_at`, `processing_status`, plus the partial unique index on `dedup_key`.
- `dlr_message_status` has one row per `message_id` with the current state, `last_event_id`, `event_count`, `duplicate_count` and timestamps. It has the same secondary indexes.
- `dlr_billing_events` (`V2__create_dlr_billing_events.sql`) has one row per billing event: `message_id`, `billing_message_id`, `part_number`, `transaction_type`, `product`, `units`, `sale_price`, `currency`, `surcharge`, `total_amount` (`NUMERIC(18,6)`), `raw_event` and `raw_payload` (JSONB), `processing_status` (`APPLIED` / `DUPLICATE` / `REJECTED`), `rejection_reason`, `dedup_key`, `duplicate_of` and `batch_id` / `batch_index`, which group the events of one callback.

- `webhook_requests` (`V12__webhook_requests.sql`) has one row per HTTP request received on the callback endpoint: `capture_id` (UUID, unique), `received_at`, `http_method`, `request_path`, `query_string`, `query_params` (JSONB), `headers` (JSONB, credentials masked), `content_type`, `raw_body` (`BYTEA`, exact bytes, `NULL` when too large), `body_size_bytes`, `body_truncated`, `body_encoding`, `body_text` (decoded copy for search/display; NUL replaced), `remote_address`, and the derived `interpretation_status`, `interpretation_error`, `detected_source`, `extracted_message_id`, `message_ids` (`TEXT[]`), `extracted_recipient`, `provider_status`, `normalized_status`, `dlr_event_id`, `interpretation` (JSONB ack). Indexes: `received_at`, `extracted_message_id` (partial), GIN on `message_ids`, `(interpretation_status, id)`, `(detected_source, id)`. A trigger refuses any update of the captured request columns (`raw_body`, headers, query, …); only the derived columns can change.

Useful queries:

```sql
-- what arrived that no adapter understood (last hour)
SELECT capture_id, received_at, content_type, interpretation_status, left(body_text, 200)
FROM webhook_requests WHERE interpretation_status IN ('UNRECOGNIZED','MALFORMED_JSON','NOT_JSON')
  AND received_at > now() - interval '1 hour' ORDER BY id DESC;

-- full history of one message, raw payloads included
SELECT id, processing_status, provider_status, processing_note, raw_payload
FROM dlr_events WHERE message_id = '…' ORDER BY id;

-- what got rejected today and why
SELECT rejection_reason, count(*) FROM dlr_events
WHERE processing_status = 'REJECTED' AND created_at > now() - interval '1 day' GROUP BY 1;

-- billing vs delivery for one message
SELECT s.message_id, s.normalized_status, b.billing_message_id, b.transaction_type, b.units, b.total_amount, b.currency
FROM dlr_message_status s LEFT JOIN dlr_billing_events b
  ON b.message_id = s.message_id AND b.processing_status = 'APPLIED'
WHERE s.message_id = '…';

-- delivered but never billed (last 24h)
SELECT s.message_id FROM dlr_message_status s
WHERE s.normalized_status = 'DELIVERED' AND s.created_at > now() - interval '1 day'
  AND NOT EXISTS (SELECT 1 FROM dlr_billing_events b WHERE b.message_id = s.message_id AND b.processing_status = 'APPLIED');

-- unexpected provider statuses
SELECT source, provider_status, count(*) FROM dlr_events WHERE normalized_status = 'UNKNOWN' GROUP BY 1, 2;
```

---

## Data retention

Old data is deleted automatically. By default, anything older than **7 days** is removed: raw webhook captures (`webhook_requests`, by `received_at`), status DLRs (`dlr_events`), message states (`dlr_message_status`), billing events and short-link clicks.

- The clean-up runs one minute after start-up and then every hour, on every instance. It deletes in batches of 5,000 rows, each in its own short transaction, so incoming callbacks are not blocked.
- A message state is deleted once it has received nothing for 7 days. An old event that still backs the current status of an active message is kept until that message itself ages out.
- After deletion, `GET /api/v1/dlr/{id}` answers `received: false` for that message, and the live UI's longer windows (30 days, All time) show at most the retained period.
- `GET /api/v1/dlr/retention` shows the settings. `POST /api/v1/dlr/retention/run` runs the clean-up immediately and returns how many rows were deleted per table.

| Environment variable | Default | Purpose |
|---|---|---|
| `DLR_RETENTION_ENABLED` | `true` | `false` keeps everything for ever |
| `DLR_RETENTION_DAYS` | `7` | Age in days after which data is deleted |
| `DLR_RETENTION_INTERVAL_MS` | `3600000` | Time between clean-up runs (1 hour) |

---

## Security

Callback authentication is off by default for local automation. **It is required in production.** When the `prod` or `production` profile is active (`dlr.security.enforce-in-profiles`), the service refuses to start unless authentication is enabled and fully configured.

All enabled mechanisms must pass. No credential is hard-coded. They all come from the environment.

| Mechanism | Enable | Credentials |
|---|---|---|
| API key (`X-API-Key`) | `DLR_API_KEY_ENABLED=true` | `DLR_API_KEYS=key1,key2` (several keys allow rotation) |
| Bearer token | `DLR_BEARER_ENABLED=true` | `DLR_BEARER_TOKENS=t1,t2` |
| IP allowlist | `DLR_IP_ALLOWLIST_ENABLED=true` | `DLR_IP_ALLOWLIST=10.0.0.0/8,203.0.113.7` (IPv4 or IPv6 CIDR). Set `DLR_TRUST_FORWARDED_FOR=true` only behind a trusted load balancer |
| HMAC | `DLR_HMAC_ENABLED=true` | `DLR_HMAC_SECRET` (≥ 16 chars). Header `X-DLR-Signature: <hex or base64>` or `sha256=<hex>` over the raw body. If `X-DLR-Timestamp` is sent, the signature covers `"<timestamp>.<body>"` and requests older than 300 s are refused. Set `DLR_HMAC_REQUIRE_TIMESTAMP=true` to require the timestamp |

Master switch: `DLR_AUTH_ENABLED=true`. Set `DLR_PROTECT_QUERY_API=true` to also require the API key on the query and verify endpoints. Secrets are compared in constant time. Failed attempts are counted in `dlr_auth_failed_total{mechanism=…}` and are not persisted.

```bash
# HMAC example
BODY=$(cat samples/default-sms-delivered.json)
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DLR_HMAC_SECRET" -hex | awk '{print $2}')
curl -X POST localhost:8080/api/v1/dlr/receive -H 'Content-Type: application/json' \
     -H "X-API-Key: $DLR_API_KEY" -H "X-DLR-Signature: sha256=$SIG" --data "$BODY"
```

---

## Logging

Each DLR produces one log line with `source`, `message_id`, `correlation_id`, `mobile` (masked), `provider_status`, `normalized_status` and `processing_status`. The same fields are also set in the MDC.

```text
DLR received source=DEFAULT_SMS message_id=2ee98174-eec... correlation_id=7589298579837987... mobile=9179******61
  provider_status=DELIVRD normalized_status=DELIVERED processing_status=APPLIED current_status=DELIVERED event_id=1
DLR rejected source=DEFAULT_SMS message_id=null processing_status=REJECTED event_id=9 reason="message_id is missing"
```

Mobile numbers are always masked: `917973059161` → `9179******61`. The log shortens ids. The database keeps them in full.

With the `docker`, `prod` or `production` profile, logs are emitted as **JSON (logstash format)** using Spring Boot's structured logging, and the MDC fields become JSON attributes.

---

## Monitoring and health

`/actuator/prometheus` exposes the following. All counters are tagged with `source`.

| Metric | Meaning |
|---|---|
| `dlr_received_total` | Every callback that reached processing, including rejected ones |
| `dlr_delivered_total`, `dlr_failed_total`, `dlr_expired_total`, `dlr_sent_total`, `dlr_unknown_total` | State changes by normalized status (`APPLIED` events only, so repeats never inflate them) |
| `dlr_rejected_total` | Invalid or malformed callbacks |
| `dlr_duplicate_total` | Exact duplicates |
| `dlr_ignored_total{reason=same_state\|transition_not_allowed}` | Valid callbacks the state machine did not apply |
| `dlr_processing_error_total` | Unexpected or database errors (returned as 503) |
| `dlr_auth_failed_total{mechanism}` | Refused callbacks |
| `dlr_billing_received_total`, `dlr_billing_applied_total`, `dlr_billing_duplicate_total`, `dlr_billing_rejected_total` | Billing events by outcome |
| `dlr_billing_units_total` | Units of applied debit billing events |
| `dlr_processing_latency_seconds` | Histogram of end-to-end processing time |

`GET /actuator/health` reports PostgreSQL connectivity (`components.db`) and includes Kubernetes-style probes at `/actuator/health/liveness` and `/actuator/health/readiness`.

---

## Automation (pytest)

`automation/dlr_helper.py` provides:

- `wait_for_dlr(message_id, expected_status, timeout=120, poll_interval=2)` polls `GET /api/v1/dlr/{id}`. It keeps polling through intermediate statuses (SENT before DELIVERED). It **fails immediately** when a different final status arrives. On timeout, it reports the last state and any rejected callbacks.
- `wait_for_dlrs(message_ids, expected_status, timeout=300)` does the same for many messages through `POST /api/v1/dlr/verify`.
- `wait_for_billing(message_id, expected_units=None, expected_amount=None, expected_currency=None, timeout=120)` polls `GET /api/v1/dlr/{id}/billing` until the billing DLR is persisted with the expected net units, amount and currency, summed over all parts.
- `wait_for_dlrs(..., require_billing=True)` also requires a billing DLR for every message.
- `get_dlr`, `get_billing`, `verify_dlrs`, plus the simulator helpers `send_default_sms_dlr`, `send_webengage_dlr`, `send_billing_dlr` and `send_raw_dlr`.

```python
from dlr_helper import wait_for_billing, wait_for_dlr

def test_otp_sms_is_delivered(sms_api):
    message_id = sms_api.send(mobile="917973059161", text="Your OTP is 123456")["message_id"]

    dlr = wait_for_dlr(message_id, expected_status="DELIVERED")   # verified from PostgreSQL

    assert dlr["received"] is True
    assert dlr["provider_status"] == "DELIVRD"

    billing = wait_for_billing(message_id, expected_units=1, expected_amount="1", expected_currency="INR")
    assert billing["billed"] is True
```

Run the examples against a running receiver:

```bash
cd automation
pip install -r requirements.txt
DLR_BASE_URL=http://localhost:8080 pytest -q
```

`test_dlr_verification.py` covers delivered, WebEngage `SENT → DELIVERED`, failing fast on `FAILED`, duplicates and out-of-order callbacks, timeouts, rejected-callback diagnostics, a 100-message bulk run, and billing: delivered-and-billed, multipart sums with retries, bulk `require_billing`, and missing-billing detection.

---

## Testing

```bash
mvn verify            # everything: unit + integration + bulk (1 / 100 / 1,000 / 10,000 DLRs)
mvn verify -Pfast     # skip the bulk tests
```

Integration tests need a running PostgreSQL (`docker compose up -d postgres` provides one). By default they use `jdbc:postgresql://localhost:5432/dlr_test` with `dlr` / `dlr`; Flyway creates the schema and each test truncates the tables. To use a different database:

```bash
DLR_TEST_DB_URL=jdbc:postgresql://dbhost:5432/dlr_test DLR_TEST_DB_USERNAME=dlr DLR_TEST_DB_PASSWORD=secret mvn verify
```

| Suite | What it covers |
|---|---|
| `StatusNormalizerTest` | Every mapping (`DELIVRD`→`DELIVERED`, `UNDELIV`→`FAILED`, `EXPIRED`, `REJECTED`, `sms_sent`→`SENT`, unknown→`UNKNOWN`), per-provider overrides, conflict detection |
| `DefaultSmsDlrAdapterTest`, `WebEngageDlrAdapterTest` | Exact normalization of the reference payloads, missing message id or other required fields, wrong types, oversized fields, lenient timestamps and units, structure detection |
| `DlrStateMachineTest` | All default transitions, out-of-order handling, final-state protection, configurable overrides |
| `DedupKeyGeneratorTest` | Duplicate versus legitimate progression, case handling, configuration validation |
| `DlrProcessingServiceTest` | Pipeline with mocked DB: applied, progression, out-of-order, duplicate, rejected, malformed, unknown source, detection, NUL sanitizing |
| `SecurityUnitTest`, `DlrConfigurationTest` | API key, bearer, IP/CIDR, HMAC (with replay window), production startup guard |
| `DlrReceiverIntegrationTest` | POST → PostgreSQL → GET with exact column-level assertions, WebEngage lifecycle, all source-resolution modes, duplicates, 40 concurrent identical callbacks (exactly one `APPLIED`), concurrent shuffled progressions, rejected, malformed and oversized storage, verify API, correlation search, health, metrics |
| `DefaultBillingDlrAdapterTest` | Exact normalization of the reference billing payload, `<id>:<part>` parsing, numeric strings and decimals, per-event and whole-callback validation |
| `DlrBillingIntegrationTest` | Status + billing correlated by message_id (exact column assertions), billing before status, multipart sums, refunds, duplicates (incl. `1` vs `1.0`), 30 concurrent identical callbacks, partial batches, rejected callbacks, `require_billing` verification, metrics |
| `DlrFlatPayloadAndReprocessIntegrationTest` | Live-gateway flat payload with no source header, `//api/...` URLs, message id kept on unrecognised payloads, single and bulk reprocessing of rejected rows (idempotent, no loops, non-JSON rows skipped) |
| `DlrMultipartIntegrationTest` | Status DLR `<id>:1` + 4-part billing correlate (lookup by `<id>` or `<id>:1`, verify with `require_billing`), per-part statuses, part duplicates, V3 backfill of old rows, ids without numeric suffix untouched |
| `DlrClickIntegrationTest` | Real click payloads (incl. escaped slashes) stored field by field, correlated with the status DLR by `<id>` or `<id>:1`, 2 clicks summed, resend = duplicate, click before status, `require_click` verification, rejected clicks, metrics |
| `DlrLiveUiIntegrationTest` | `/` redirects to `/ui/` and the page is served; the live feed merges status, billing and click newest first and its cursor returns only newer events; window stats count states, duplicates, rejections, billed amount, clicks and per-minute buckets exactly |
| `DlrSecurityIntegrationTest` | Authentication enforced end to end; refused callbacks are not stored |
| `DlrBulkIntegrationTest` | 1 / 100 / 1,000 / 10,000 DLRs over HTTP, 48 at a time, mixed providers and statuses, progressions, duplicates and multipart billing DLRs. Every message's status, mobile, correlation id and billed units and amount are checked against the database |

---

## Configuration reference

The full, commented configuration is in `src/main/resources/application.yml`, with defaults in `DlrProperties`.

| Environment variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/dlr`, `dlr`, `dlr` | Database |
| `DB_POOL_SIZE` | `20` | Hikari pool size |
| `SERVER_PORT` | `8080` | HTTP port |
| `DLR_TIMEZONE` | `Asia/Kolkata` | Zone for zoned or epoch provider timestamps and the receiver-time `received_at` fallback |
| `DLR_INSTANCE_ID` | hostname | Written to `dlr_events.receiver_instance` |
| `DLR_META_REQUIRE_MESSAGE_ID` | `true` | Reject Meta statuses that have no platform `message_id` |
| `DLR_RETENTION_DAYS` | `7` | Data older than this many days is deleted automatically (see [Data retention](#data-retention)) |
| `DLR_META_VERIFY_TOKEN` | – | Token for Meta's callback URL check (`GET /api/v1/dlr/receive?hub.mode=subscribe…`) |
| `DLR_BILLING_ENABLED` | `true` | Accept billing DLRs (other billing settings are under `dlr.billing` in `application.yml`) |
| `DLR_MAX_PAYLOAD_BYTES` | `10485760` | Max callback body (10 MB); larger bodies get 413 and are not stored. Bulk-campaign billing callbacks carry one event per recipient, about 200 bytes each |
| `DLR_CAPTURE_ACK_STATUS` | `200` | Status answered once a request is captured (any interpretation) |
| `DLR_CAPTURE_MESSAGE_ID_FIELDS` | `message_id,messageId,msg_id,…` | Field names the generic extractor tries for a message id in unrecognised payloads (`id` is not included on purpose) |
| `DLR_CAPTURE_STATUS_FIELDS` / `DLR_CAPTURE_RECIPIENT_FIELDS` | see `application.yml` | Same for status and recipient |
| `DLR_CAPTURE_MASKED_HEADERS` | `authorization,proxy-authorization,cookie,…` | Header values stored as `***` (the API-key header is always masked) |
| `DLR_TRUST_FORWARDED_FOR` | `false` | Use `X-Forwarded-For` as the sender address (only behind a trusted proxy) |
| `DLR_PROTECT_QUERY_API` | `false` | Require `X-API-Key` for the query APIs, the capture API and the raw bodies |
| `DLR_BILLING_MAX_EVENTS` | `50000` | Max events in one billing callback |
| `DLR_DETECT_FROM_PAYLOAD` | `true` | Allow detecting the source from the payload structure when no header or parameter is sent |
| `DLR_AUTH_ENABLED` … | see [Security](#security) | Callback authentication |
| `SPRING_PROFILES_ACTIVE` | – (`docker` in the container) | `docker` (JSON logs), `prod` (JSON logs, auth enforced) |

---

## Adding a provider

1. Create a request DTO with Bean Validation annotations, for example `AcmeDlrRequest` with `@NotBlank` on `msgId`.
2. Create an adapter:

```java
@Component
@Order(300)
public class AcmeDlrAdapter extends AbstractJsonDlrAdapter {

    public AcmeDlrAdapter(ObjectMapper m, Validator v, StatusNormalizer s, DlrProperties p) { super(m, v, s, p); }

    @Override public String source() { return "ACME"; }

    @Override protected boolean matchesStructure(JsonNode p) { return p.has("msgId") && p.has("dlrStatus"); }

    @Override public NormalizedDlr normalize(JsonNode payload) {
        AcmeDlrRequest r = bindAndValidate(payload, AcmeDlrRequest.class);
        return NormalizedDlr.of("ACME")
                .messageId(r.getMsgId())
                .mobile(r.getTo())
                .providerStatus(r.getDlrStatus())
                .normalizedStatus(statusNormalizer.normalize("ACME", r.getDlrStatus()))
                .statusCode(r.getErr());
    }
}
```

3. If the provider has its own status names, add them to `dlr.provider-status-mapping.ACME`.

Nothing else changes: the controller, processing service, idempotency, state machine, schema, metrics and query APIs are all provider-independent. Callers send `X-DLR-Source: ACME`, or rely on structure detection.

---

## Production notes

- **Horizontal scaling.** Instances share nothing but PostgreSQL. Idempotency uses a unique index, and state changes use row locks, so the same DLR delivered to different instances is recorded exactly once. The `ha` compose profile demonstrates this with two instances behind nginx.
- **Retries.** Database failures return 503, so providers retry, and idempotency makes retries safe.
- **Graceful shutdown** is enabled (`server.shutdown=graceful`).
- **Timezone.** Run the JVM in UTC (`-Duser.timezone=UTC`, already set in the Dockerfile). Provider timestamps without a zone are stored exactly as sent.
- **Retention.** `dlr_events` grows with every callback. For high volume, partition it by `created_at` (monthly) or add a scheduled purge of rows older than your audit window. `dlr_message_status` is small (one row per message).
- **Event ids may have gaps.** `INSERT … ON CONFLICT DO NOTHING` consumes a sequence value for each duplicate. This is expected.
