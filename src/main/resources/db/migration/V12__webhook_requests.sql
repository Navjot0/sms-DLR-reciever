-- Universal webhook capture: every request that reaches the callback endpoint is stored here first, exactly as
-- received and whatever its format, before any DLR interpretation. Derived fields are filled in afterwards and
-- never touch raw_body.
CREATE TABLE webhook_requests (
    id                      BIGSERIAL    PRIMARY KEY,                -- cursor for the live UI / pagination
    capture_id              UUID         NOT NULL UNIQUE,            -- public id of the capture (not a message_id)
    received_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    http_method             VARCHAR(16)  NOT NULL,
    request_path            TEXT         NOT NULL,
    query_string            TEXT,                                    -- exactly as sent
    query_params            JSONB,                                   -- {"name": ["value", ...]}
    headers                 JSONB        NOT NULL,                   -- {"name": ["value", ...]}, secrets masked
    content_type            TEXT,
    raw_body                BYTEA,                                   -- original bytes, never modified; NULL when too large
    body_size_bytes         BIGINT       NOT NULL,
    body_truncated          BOOLEAN      NOT NULL DEFAULT FALSE,     -- true: body exceeded the limit and was not stored
    body_encoding           VARCHAR(40),                             -- charset used to read the body as text, or 'binary'
    body_text               TEXT,                                    -- raw_body decoded as text (search / display), NULL when binary
    remote_address          VARCHAR(100),
    -- derived, filled in by DLR interpretation (best effort)
    interpretation_status   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    interpretation_error    TEXT,
    detected_source         VARCHAR(50),
    extracted_message_id    VARCHAR(255),
    message_ids             TEXT[],                                  -- every message id of a batch callback
    extracted_recipient     VARCHAR(320),
    provider_status         VARCHAR(100),
    normalized_status       VARCHAR(50),                             -- only when the DLR pipeline set one
    dlr_event_id            BIGINT,                                  -- dlr_events row it produced, if any
    interpretation          JSONB,                                   -- the receiver's answer for this callback
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_webhook_requests_received_at   ON webhook_requests (received_at);
CREATE INDEX ix_webhook_requests_message_id    ON webhook_requests (extracted_message_id) WHERE extracted_message_id IS NOT NULL;
CREATE INDEX ix_webhook_requests_message_ids   ON webhook_requests USING GIN (message_ids);
CREATE INDEX ix_webhook_requests_status        ON webhook_requests (interpretation_status, id);
CREATE INDEX ix_webhook_requests_source        ON webhook_requests (detected_source, id);

-- The captured request is immutable: only the derived interpretation columns may change after insert.
CREATE FUNCTION webhook_requests_immutable() RETURNS trigger AS $$
BEGIN
    IF NEW.capture_id      IS DISTINCT FROM OLD.capture_id
       OR NEW.received_at  IS DISTINCT FROM OLD.received_at
       OR NEW.http_method  IS DISTINCT FROM OLD.http_method
       OR NEW.request_path IS DISTINCT FROM OLD.request_path
       OR NEW.query_string IS DISTINCT FROM OLD.query_string
       OR NEW.headers      IS DISTINCT FROM OLD.headers
       OR NEW.content_type IS DISTINCT FROM OLD.content_type
       OR NEW.raw_body     IS DISTINCT FROM OLD.raw_body
       OR NEW.body_size_bytes IS DISTINCT FROM OLD.body_size_bytes
       OR NEW.body_text    IS DISTINCT FROM OLD.body_text THEN
        RAISE EXCEPTION 'webhook_requests: captured request data is immutable (capture_id %)', OLD.capture_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_webhook_requests_immutable BEFORE UPDATE ON webhook_requests
    FOR EACH ROW EXECUTE FUNCTION webhook_requests_immutable();
