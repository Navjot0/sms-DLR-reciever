-- The DLR body exactly as received (key order, spacing and escaping untouched). raw_payload is JSONB, which
-- PostgreSQL normalizes (reorders keys, drops spacing, unescapes "\/"), so it cannot hand back the original text.
ALTER TABLE dlr_events ADD COLUMN raw_body TEXT;
