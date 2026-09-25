-- =====================================================================
-- Billing DLRs
--
-- The gateway sends a billing callback alongside the status DLR:
--   {"event_type":"billing","events":[{"transaction_type":"debit","message_id":"<uuid>:1",...}]}
--
-- One row per billing event (a callback may carry several events).
--   billing_message_id : message_id exactly as sent ("9b1b0309-...:1")
--   message_id         : correlation key = part before the last ':' -> joins dlr_message_status.message_id
--   part_number        : numeric suffix after ':' (SMS part / segment), NULL when absent
--
-- Callbacks that are not valid billing batches at all (malformed JSON, "events" missing/empty) are stored
-- in dlr_events as REJECTED, like every other rejected callback.
-- =====================================================================

CREATE TABLE dlr_billing_events (
    id                  BIGSERIAL PRIMARY KEY,

    source              VARCHAR(50)    NOT NULL,
    batch_id            UUID           NOT NULL,   -- groups the events of one callback
    batch_index         INTEGER        NOT NULL,   -- position of the event inside "events"

    message_id          VARCHAR(255),              -- NULL only for REJECTED events
    billing_message_id  VARCHAR(255),
    part_number         INTEGER,

    transaction_type    VARCHAR(50),
    product             VARCHAR(255),
    units               INTEGER,
    sale_price          NUMERIC(18, 6),
    currency            VARCHAR(10),
    surcharge           NUMERIC(18, 6),
    total_amount        NUMERIC(18, 6),

    raw_event           JSONB          NOT NULL,   -- this event exactly as received
    raw_payload         JSONB          NOT NULL,   -- the complete callback

    -- APPLIED | DUPLICATE | REJECTED
    processing_status   VARCHAR(50)    NOT NULL,
    processing_note     TEXT,
    rejection_reason    TEXT,

    dedup_key           VARCHAR(64),
    duplicate_of        BIGINT REFERENCES dlr_billing_events (id),
    receiver_instance   VARCHAR(100),

    created_at          TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_dlr_billing_message_id
        CHECK (message_id IS NOT NULL OR processing_status = 'REJECTED'),
    CONSTRAINT chk_dlr_billing_processing_status
        CHECK (processing_status IN ('APPLIED', 'DUPLICATE', 'REJECTED'))
);

-- Same idempotency approach as dlr_events: first event per key wins across all instances.
CREATE UNIQUE INDEX ux_dlr_billing_dedup_key
    ON dlr_billing_events (dedup_key)
    WHERE dedup_key IS NOT NULL AND processing_status <> 'DUPLICATE';

CREATE INDEX ix_dlr_billing_message_id         ON dlr_billing_events (message_id);
CREATE INDEX ix_dlr_billing_billing_message_id ON dlr_billing_events (billing_message_id);
CREATE INDEX ix_dlr_billing_batch_id           ON dlr_billing_events (batch_id);
CREATE INDEX ix_dlr_billing_processing_status  ON dlr_billing_events (processing_status);
CREATE INDEX ix_dlr_billing_created_at         ON dlr_billing_events (created_at);
