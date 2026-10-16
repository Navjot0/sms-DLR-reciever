-- Bulk-push DLR callbacks: {"batchId": "...", "events": [ {...DLR...}, ... ]} (or a top-level JSON array).
-- The whole request stays in webhook_requests (raw_body, unchanged). Every event is interpreted on its own:
-- recognised DLRs go through the normal pipeline into dlr_events / dlr_message_status, and EVERY event (also
-- unknown or malformed ones) gets a row here with its position in the batch and its exact original text.

ALTER TABLE webhook_requests
    ADD COLUMN batch_id          VARCHAR(255),   -- batchId / batch_id from the payload, when present
    ADD COLUMN batch_event_count INTEGER,        -- set only for bulk requests (0 = empty events array)
    ADD COLUMN batch_summary     JSONB;          -- per-outcome counts {"applied":..,"duplicate":..,...}

CREATE INDEX ix_webhook_requests_batch    ON webhook_requests (id) WHERE batch_event_count IS NOT NULL;
CREATE INDEX ix_webhook_requests_batch_id ON webhook_requests (batch_id) WHERE batch_id IS NOT NULL;

CREATE TABLE webhook_batch_events (
    id                BIGSERIAL    PRIMARY KEY,
    capture_id        UUID         NOT NULL,          -- parent webhook_requests.capture_id
    batch_id          VARCHAR(255),
    event_index       INTEGER      NOT NULL,          -- 0-based position in the incoming array
    message_id        VARCHAR(255),                   -- exactly as sent, e.g. "<id>:886"
    base_message_id   VARCHAR(255),                   -- without the ":<part>" suffix
    part_number       INTEGER,
    recipient         VARCHAR(320),
    source            VARCHAR(50),
    provider_status   VARCHAR(100),
    normalized_status VARCHAR(50),                    -- only when a DLR adapter produced one
    outcome           VARCHAR(30)  NOT NULL,          -- APPLIED | IGNORED | DUPLICATE | REJECTED | UNRECOGNIZED | MALFORMED | ERROR
    reason            TEXT,
    dlr_event_id      BIGINT,
    raw_event         TEXT,                           -- the event's original text, as it appears in the request
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_webhook_batch_events UNIQUE (capture_id, event_index)
);

CREATE INDEX ix_webhook_batch_events_message ON webhook_batch_events (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX ix_webhook_batch_events_base    ON webhook_batch_events (base_message_id) WHERE base_message_id IS NOT NULL;
CREATE INDEX ix_webhook_batch_events_outcome ON webhook_batch_events (capture_id, outcome, event_index);
CREATE INDEX ix_webhook_batch_events_created ON webhook_batch_events (created_at);

-- DLRs that came inside a bulk request point back to it, so the live feed can show one row per request.
ALTER TABLE dlr_events ADD COLUMN batch_capture_id UUID;
CREATE INDEX ix_dlr_events_batch_capture ON dlr_events (batch_capture_id) WHERE batch_capture_id IS NOT NULL;

-- Per-recipient lookups ("<id>:<n>") of bulk campaigns: one message_id with thousands of parts.
CREATE INDEX ix_dlr_events_message_part ON dlr_events (message_id, part_number) WHERE part_number IS NOT NULL;
