-- Individual-message view over single and bulk DLRs (GET /api/v1/dlr/messages) and bulk-request counts.

-- Bulk requests: counts kept as columns (also in batch_summary) for the Bulk Received list.
ALTER TABLE webhook_requests
    ADD COLUMN processed_event_count INTEGER,     -- events stored as DLRs (applied / ignored / duplicate)
    ADD COLUMN failed_event_count    INTEGER;     -- events that are not: invalid, unknown, malformed, database error

-- Split events: the provider's status code next to the status.
ALTER TABLE webhook_batch_events ADD COLUMN status_code VARCHAR(50);

CREATE INDEX ix_webhook_batch_events_dlr_event ON webhook_batch_events (dlr_event_id) WHERE dlr_event_id IS NOT NULL;
CREATE INDEX ix_webhook_batch_events_batch_id  ON webhook_batch_events (batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX ix_webhook_batch_events_recipient ON webhook_batch_events (recipient) WHERE recipient IS NOT NULL;
CREATE INDEX ix_webhook_requests_dlr_event     ON webhook_requests (dlr_event_id) WHERE dlr_event_id IS NOT NULL;
