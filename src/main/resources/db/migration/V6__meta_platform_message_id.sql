-- Meta (WhatsApp) statuses forwarded by the platform carry its own id next to Meta's wamid:
--   {"status":{"id":"wamid...","message_id":"<uuid>", ...}}
-- message_id is now that platform id (what the send API returned); the wamid moves to external_message_id.
-- Re-key the rows stored before this change.
CREATE TEMP TABLE meta_id_map ON COMMIT DROP AS
SELECT message_id AS old_id, min(raw_payload -> 'status' ->> 'message_id') AS new_id
FROM dlr_events
WHERE source = 'META'
  AND message_id IS NOT NULL
  AND coalesce(raw_payload -> 'status' ->> 'message_id', '') <> ''
  AND message_id <> raw_payload -> 'status' ->> 'message_id'
GROUP BY message_id;

-- keep only mappings that cannot collide with an existing message
DELETE FROM meta_id_map m
WHERE EXISTS (SELECT 1 FROM dlr_message_status s WHERE s.message_id = m.new_id)
   OR (SELECT count(*) FROM meta_id_map x WHERE x.new_id = m.new_id) > 1;

UPDATE dlr_message_status s
SET message_id = m.new_id, external_message_id = m.old_id
FROM meta_id_map m
WHERE s.message_id = m.old_id;

UPDATE dlr_events e
SET message_id = m.new_id, external_message_id = m.old_id
FROM meta_id_map m
WHERE e.message_id = m.old_id AND e.source = 'META';
