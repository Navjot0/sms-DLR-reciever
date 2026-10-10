-- Short-link click callbacks exactly as received (key order, spacing and escaping such as "\/" untouched).
-- raw_payload is JSONB, which PostgreSQL normalizes, so it cannot hand back the original text.
-- Rows stored before this migration have raw_body NULL; the APIs then fall back to raw_payload.
ALTER TABLE dlr_click_events ADD COLUMN raw_body TEXT;
