-- =====================================================================
-- SMS DLR Receiver schema
--
-- dlr_events          : append-only log of EVERY callback received
--                       (accepted, duplicate, ignored/out-of-order, rejected).
--                       raw_payload is always stored.
-- dlr_message_status  : one row per message_id holding the current
--                       (state-machine-resolved) delivery state. This is what
--                       the query / verify APIs read.
-- =====================================================================

CREATE TABLE dlr_events (
    id                  BIGSERIAL PRIMARY KEY,

    source              VARCHAR(50)  NOT NULL,

    -- NULL only allowed for REJECTED events (e.g. "message_id is missing")
    message_id          VARCHAR(255),

    external_message_id VARCHAR(255),
    correlation_id      VARCHAR(500),
    campaign_id         VARCHAR(255),
    request_id          VARCHAR(255),
    provider_event_id   VARCHAR(255),

    mobile              VARCHAR(30),
    sender              VARCHAR(100),
    service             VARCHAR(50),

    provider_status     VARCHAR(100),
    normalized_status   VARCHAR(50),
    status_code         VARCHAR(100),
    error_code          VARCHAR(100),
    error_reason        TEXT,

    submit_at           TIMESTAMP,
    dlr_received_at     TIMESTAMP,

    entity_id           VARCHAR(255),
    template_id         VARCHAR(255),
    units               INTEGER,

    raw_payload         JSONB        NOT NULL,

    -- APPLIED | IGNORED | DUPLICATE | REJECTED
    processing_status   VARCHAR(50)  NOT NULL,
    processing_note     TEXT,
    rejection_reason    TEXT,

    -- SHA-256 of the configured idempotency key fields
    dedup_key           VARCHAR(64),
    duplicate_of        BIGINT REFERENCES dlr_events (id),
    receiver_instance   VARCHAR(100),

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_dlr_events_message_id
        CHECK (message_id IS NOT NULL OR processing_status = 'REJECTED'),
    CONSTRAINT chk_dlr_events_processing_status
        CHECK (processing_status IN ('APPLIED', 'IGNORED', 'DUPLICATE', 'REJECTED'))
);

-- Database-level idempotency: the first event per dedup_key wins, across all
-- receiver instances. Duplicates are still stored (processing_status =
-- 'DUPLICATE') for audit, so they are excluded from the unique index.
CREATE UNIQUE INDEX ux_dlr_events_dedup_key
    ON dlr_events (dedup_key)
    WHERE dedup_key IS NOT NULL AND processing_status <> 'DUPLICATE';

CREATE INDEX ix_dlr_events_message_id          ON dlr_events (message_id);
CREATE INDEX ix_dlr_events_external_message_id ON dlr_events (external_message_id);
CREATE INDEX ix_dlr_events_correlation_id      ON dlr_events (correlation_id);
CREATE INDEX ix_dlr_events_mobile              ON dlr_events (mobile);
CREATE INDEX ix_dlr_events_normalized_status   ON dlr_events (normalized_status);
CREATE INDEX ix_dlr_events_source              ON dlr_events (source);
CREATE INDEX ix_dlr_events_created_at          ON dlr_events (created_at);
CREATE INDEX ix_dlr_events_processing_status   ON dlr_events (processing_status);


CREATE TABLE dlr_message_status (
    message_id          VARCHAR(255) PRIMARY KEY,
    source              VARCHAR(50)  NOT NULL,

    external_message_id VARCHAR(255),
    correlation_id      VARCHAR(500),
    campaign_id         VARCHAR(255),
    request_id          VARCHAR(255),

    mobile              VARCHAR(30),
    sender              VARCHAR(100),
    service             VARCHAR(50),

    provider_status     VARCHAR(100),
    normalized_status   VARCHAR(50)  NOT NULL,
    status_code         VARCHAR(100),
    error_code          VARCHAR(100),
    error_reason        TEXT,

    submit_at           TIMESTAMP,
    dlr_received_at     TIMESTAMP,

    entity_id           VARCHAR(255),
    template_id         VARCHAR(255),
    units               INTEGER,

    last_event_id       BIGINT       NOT NULL REFERENCES dlr_events (id),
    event_count         INTEGER      NOT NULL DEFAULT 1,
    duplicate_count     INTEGER      NOT NULL DEFAULT 0,

    first_received_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- when the CURRENT status was set (receiver clock); used as received_at when the provider sends no timestamp
    status_updated_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- when ANY callback (incl. duplicates / ignored) for this message was last received
    last_received_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_dlr_status_external_message_id ON dlr_message_status (external_message_id);
CREATE INDEX ix_dlr_status_correlation_id      ON dlr_message_status (correlation_id);
CREATE INDEX ix_dlr_status_mobile              ON dlr_message_status (mobile);
CREATE INDEX ix_dlr_status_normalized_status   ON dlr_message_status (normalized_status);
CREATE INDEX ix_dlr_status_source              ON dlr_message_status (source);
CREATE INDEX ix_dlr_status_created_at          ON dlr_message_status (created_at);
