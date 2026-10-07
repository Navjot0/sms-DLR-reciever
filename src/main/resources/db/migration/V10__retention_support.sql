-- Automatic clean-up of old data (dlr.retention): rows are deleted in batches once they are older than the
-- retention period. A newer row may still point at a deleted one through duplicate_of, so those links are
-- cleared instead of blocking the delete.
ALTER TABLE dlr_events DROP CONSTRAINT IF EXISTS dlr_events_duplicate_of_fkey;
ALTER TABLE dlr_events ADD CONSTRAINT dlr_events_duplicate_of_fkey
    FOREIGN KEY (duplicate_of) REFERENCES dlr_events (id) ON DELETE SET NULL;

ALTER TABLE dlr_billing_events DROP CONSTRAINT IF EXISTS dlr_billing_events_duplicate_of_fkey;
ALTER TABLE dlr_billing_events ADD CONSTRAINT dlr_billing_events_duplicate_of_fkey
    FOREIGN KEY (duplicate_of) REFERENCES dlr_billing_events (id) ON DELETE SET NULL;

ALTER TABLE dlr_click_events DROP CONSTRAINT IF EXISTS dlr_click_events_duplicate_of_fkey;
ALTER TABLE dlr_click_events ADD CONSTRAINT dlr_click_events_duplicate_of_fkey
    FOREIGN KEY (duplicate_of) REFERENCES dlr_click_events (id) ON DELETE SET NULL;

-- make the reference checks of those deletes index lookups instead of table scans
CREATE INDEX IF NOT EXISTS ix_dlr_events_duplicate_of  ON dlr_events (duplicate_of) WHERE duplicate_of IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_dlr_billing_duplicate_of ON dlr_billing_events (duplicate_of) WHERE duplicate_of IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_dlr_click_duplicate_of   ON dlr_click_events (duplicate_of) WHERE duplicate_of IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_dlr_status_last_event_id ON dlr_message_status (last_event_id);
CREATE INDEX IF NOT EXISTS ix_dlr_status_last_received ON dlr_message_status (last_received_at);
