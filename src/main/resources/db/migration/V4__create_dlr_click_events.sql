-- =====================================================================
-- Short-link click events
--
--   {"event":"short_link","url_type":"dynamic","received_at":"...",
--    "data":{"visited_count":2,"contact":"91...","url_key":"ZIO7ER","short_url":"...",
--            "destination_url":"...","clicked_at":"...","message_id":"<id>:1", ...}}
--
-- One row per click callback. message_id is the SMS message id without the
-- ":<part>" suffix, so clicks correlate with the status and billing DLRs.
-- =====================================================================

CREATE TABLE dlr_click_events (
    id                       BIGSERIAL PRIMARY KEY,

    source                   VARCHAR(50)  NOT NULL,
    event_type               VARCHAR(50)  NOT NULL,

    message_id               VARCHAR(255) NOT NULL,
    provider_message_id      VARCHAR(255),
    part_number              INTEGER,
    correlation_id           VARCHAR(500),

    contact                  VARCHAR(30),
    url_key                  VARCHAR(100),
    url_type                 VARCHAR(50),
    short_url                TEXT,
    destination_url          TEXT,
    channel                  VARCHAR(30),
    visited_count            INTEGER,

    ip_address               VARCHAR(64),
    operating_system         VARCHAR(100),
    operating_system_version VARCHAR(50),
    browser                  VARCHAR(100),
    browser_version          VARCHAR(50),
    device_type              VARCHAR(50),

    clicked_at               TIMESTAMP,
    provider_received_at     TIMESTAMP,

    raw_payload              JSONB        NOT NULL,

    -- APPLIED | DUPLICATE
    processing_status        VARCHAR(50)  NOT NULL,
    processing_note          TEXT,
    dedup_key                VARCHAR(64),
    duplicate_of             BIGINT REFERENCES dlr_click_events (id),
    receiver_instance        VARCHAR(100),

    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_dlr_click_processing_status CHECK (processing_status IN ('APPLIED', 'DUPLICATE'))
);

CREATE UNIQUE INDEX ux_dlr_click_dedup_key
    ON dlr_click_events (dedup_key)
    WHERE dedup_key IS NOT NULL AND processing_status <> 'DUPLICATE';

CREATE INDEX ix_dlr_click_message_id ON dlr_click_events (message_id);
CREATE INDEX ix_dlr_click_url_key    ON dlr_click_events (url_key);
CREATE INDEX ix_dlr_click_contact    ON dlr_click_events (contact);
CREATE INDEX ix_dlr_click_created_at ON dlr_click_events (created_at);
