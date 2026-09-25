-- =====================================================================
-- Multipart SMS message ids
--
-- Status DLRs (like billing DLRs) may carry "<message_id>:<part>", e.g. "c9b2e601-...:1".
-- From now on the receiver stores:
--   message_id           = "c9b2e601-..."      (correlation / lookup key, same as billing)
--   provider_message_id  = "c9b2e601-...:1"    (exactly as received)
--   part_number          = 1
-- =====================================================================

ALTER TABLE dlr_events ADD COLUMN provider_message_id VARCHAR(255);
ALTER TABLE dlr_events ADD COLUMN part_number INTEGER;
CREATE INDEX ix_dlr_events_provider_message_id ON dlr_events (provider_message_id);

-- Backfill rows stored before this version (separator ':' = default of dlr.message-id-part-separator).
UPDATE dlr_events
SET provider_message_id = message_id,
    part_number         = CAST(substring(message_id FROM ':([0-9]{1,6})$') AS INTEGER),
    message_id          = regexp_replace(message_id, ':[0-9]{1,6}$', '')
WHERE message_id ~ '^.+:[0-9]{1,6}$';

-- Move current-state rows keyed by "<id>:<part>" to "<id>" (one per message; never overwrite an existing row).
UPDATE dlr_message_status s
SET message_id = x.base
FROM (SELECT DISTINCT ON (regexp_replace(message_id, ':[0-9]{1,6}$', ''))
             message_id                                  AS old_id,
             regexp_replace(message_id, ':[0-9]{1,6}$', '') AS base
      FROM dlr_message_status
      WHERE message_id ~ '^.+:[0-9]{1,6}$'
      ORDER BY regexp_replace(message_id, ':[0-9]{1,6}$', ''), message_id) x
WHERE s.message_id = x.old_id
  AND NOT EXISTS (SELECT 1 FROM dlr_message_status b WHERE b.message_id = x.base);
