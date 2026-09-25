# SMS DLR Receiver

A generic, stateless service that **receives, validates, normalizes, correlates and persists** SMS delivery reports (DLRs) from multiple providers, and exposes query APIs that SMS automation uses to verify delivery.

> **The receiver is the source of truth for DLR verification.**
> An HTTP 200 on the DLR callback does not mean a test passes. A test passes only when the expected DLR has been received, correlated by `message_id`, persisted in PostgreSQL, and read back through `GET /api/v1/dlr/{messageId}` or `POST /api/v1/dlr/verify`.

Supported out of the box: **Default SMS DLR** (`DEFAULT_SMS`), **WebEngage SMS DLR** (`WEBENGAGE`), and the **billing DLR** the SMS gateway sends alongside the status DLR (see [Billing DLRs](#billing-dlrs)). You can add another provider by writing one class (see [Adding a provider](#adding-a-provider)).

---

## Contents

- [Quick start](#quick-start)
- [Architecture](#architecture)
- [API](#api)
- [Normalization and status mapping](#normalization-and-status-mapping)
- [Correlation](#correlation)
- [Idempotency and duplicates](#idempotency-and-duplicates)
- [State transitions and out-of-order DLRs](#state-transitions-and-out-of-order-dlrs)
- [Validation and rejected DLRs](#validation-and-rejected-dlrs)
- [Billing DLRs](#billing-dlrs)
- [Database](#database)
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

## Architecture

```text
Provider / SMS gateway / simulator
        │  POST /api/v1/dlr/receive   (X-DLR-Source: DEFAULT_SMS | WEBENGAGE | …)
        ▼
CallbackAuthenticationFilter   API key · Bearer · IP allowlist · HMAC (configurable, all off locally)
        ▼
DlrReceiverController          raw body (so malformed JSON can still be stored)
        ▼
DlrProcessingService           provider-independent pipeline
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
   ├─ dlr_events               every callback: APPLIED / IGNORED / DUPLICATE / REJECTED + raw_payload
   └─ dlr_message_status       current state per message_id  ◄── GET /api/v1/dlr/{id}, POST /verify
   └─ dlr_billing_events       every billing event, joined by message_id ◄── billing summary in the same APIs
```

Design choices:

- **Two tables.** `dlr_events` is an append-only audit log of every callback, including its raw payload. `dlr_message_status` holds one row per `message_id` with the state resolved by the state machine. Automation reads the second table. Debugging reads the first.
- **Explicit SQL (Spring JDBC) instead of an ORM.** Idempotency and concurrency depend on `INSERT … ON CONFLICT` and `SELECT … FOR UPDATE`, so they are written out explicitly.
- **No in-memory state.** Every decision is made inside PostgreSQL, so any number of instances can run behind a load balancer.

### Project layout

```text
src/main/java/com/smsframework/dlr
├── controller   DlrReceiverController, DlrQueryController
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

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/dlr/receive` | Generic callback endpoint for every provider, for status and billing DLRs |
| `GET` | `/api/v1/dlr/{messageId}` | Current DLR for a message (`?include_events=true` adds the history) |
| `POST` | `/api/v1/dlr/verify` | Bulk verification for automation |
| `GET` | `/api/v1/dlr/{messageId}/events` | Every callback for a message, with raw payloads |
| `GET` | `/api/v1/dlr/{messageId}/billing` | Billing summary and every billing event for a message |
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

Response codes. These are meant for the provider. Automation must not depend on them.

| Code | Meaning |
|---|---|
| 200 | Persisted as `APPLIED`, `IGNORED` or `DUPLICATE` |
| 400 | Persisted as `REJECTED`: invalid, malformed or unsupported source. Tells the provider not to retry. Configurable with `dlr.api.rejected-http-status` |
| 413 | Body larger than `dlr.api.max-payload-bytes`. Persisted as `REJECTED` with the first 4 KB |
| 401 / 403 | Authentication failed. **Not** persisted |
| 503 | Database unavailable. The provider should retry. Retrying is safe because of idempotency |

### `GET /api/v1/dlr/{messageId}`

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
| `REJECTD`, `REJECTED` | `REJECTED` |
| `SMS_SENT` / `sms_sent`, `SUBMITTED` | `SENT` |
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
UNKNOWN ─► SENT | DELIVERED | FAILED | EXPIRED | REJECTED
SENT    ─► DELIVERED | FAILED | EXPIRED | REJECTED
DELIVERED, FAILED, EXPIRED, REJECTED ─► (final, nothing)
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

Field lengths are also checked against the column sizes. Invalid callbacks are **never discarded**. They are stored with `processing_status = REJECTED`, a `rejection_reason`, and the complete raw payload:

| Situation | `rejection_reason` |
|---|---|
| required field missing | `message_id is missing` (several reasons are joined with `; `) |
| wrong JSON type | `invalid value for field 'payload.message_id'` |
| not JSON | `malformed JSON payload` (raw text stored as `{"_unparseable_body": "…"}`) |
| empty body | `request body is empty` |
| unknown header source | `unsupported DLR source: ACME (supported: [DEFAULT_SMS, WEBENGAGE])` |
| no source and structure not recognized | `unable to determine DLR source from headers, query parameters or payload structure` |
| too large | `payload exceeds 65536 bytes` |

When a rejected payload still contains a readable message id, it is stored in `message_id`, so `GET /api/v1/dlr/{id}` can report it. This also applies when the source could not be determined.

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

## Database

The schema is in `src/main/resources/db/migration/` (`V1__create_dlr_tables.sql`, `V2__create_dlr_billing_events.sql`, `V3__multipart_message_ids.sql`, which adds `provider_message_id` and `part_number` to `dlr_events`).

- `dlr_events` has the columns you specified: `id`, `source`, `message_id`, `external_message_id`, `correlation_id`, `mobile`, `sender`, `service`, `provider_status`, `normalized_status`, `status_code`, `error_code`, `error_reason`, `submit_at`, `dlr_received_at`, `entity_id`, `template_id`, `units`, `raw_payload JSONB NOT NULL`, `processing_status`, `created_at`, `updated_at`. It adds `campaign_id`, `request_id`, `provider_event_id`, `rejection_reason`, `processing_note`, `dedup_key`, `duplicate_of` and `receiver_instance`.
  - `message_id` may be `NULL` **only** for `REJECTED` rows. A `CHECK` constraint enforces this, so a callback missing its id can still be stored.
  - Indexes: `message_id`, `external_message_id`, `correlation_id`, `mobile`, `normalized_status`, `source`, `created_at`, `processing_status`, plus the partial unique index on `dedup_key`.
- `dlr_message_status` has one row per `message_id` with the current state, `last_event_id`, `event_count`, `duplicate_count` and timestamps. It has the same secondary indexes.
- `dlr_billing_events` (`V2__create_dlr_billing_events.sql`) has one row per billing event: `message_id`, `billing_message_id`, `part_number`, `transaction_type`, `product`, `units`, `sale_price`, `currency`, `surcharge`, `total_amount` (`NUMERIC(18,6)`), `raw_event` and `raw_payload` (JSONB), `processing_status` (`APPLIED` / `DUPLICATE` / `REJECTED`), `rejection_reason`, `dedup_key`, `duplicate_of` and `batch_id` / `batch_index`, which group the events of one callback.

Useful queries:

```sql
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
| `DLR_BILLING_ENABLED` | `true` | Accept billing DLRs (other billing settings are under `dlr.billing` in `application.yml`) |
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
