-- (Replaces V7, which failed on databases holding DUPLICATE copies of such statuses: a status and its
--  duplicates share a dedup_key, and marking them all REJECTED broke the unique index. V7 no longer exists.)
-- Meta statuses must carry the platform's message_id. Rows stored earlier under Meta's wamid (no message_id
-- in the status) become REJECTED, so the UI and the counts only show message_id-keyed DLRs.
DELETE FROM dlr_message_status s
WHERE s.source = 'META'
  AND NOT EXISTS (SELECT 1 FROM dlr_events e
                  WHERE e.message_id = s.message_id AND e.source = 'META'
                    AND coalesce(e.raw_payload -> 'status' ->> 'message_id', '') <> '');

UPDATE dlr_events
SET processing_status = 'REJECTED',
    rejection_reason  = 'meta: message_id is missing (only Meta''s wamid was sent)',
    external_message_id = coalesce(external_message_id, message_id),
    dedup_key = NULL,     -- a status and its DUPLICATE copies share a key; rejected rows need none
    message_id = NULL
WHERE source = 'META'
  AND processing_status <> 'REJECTED'
  AND coalesce(raw_payload -> 'status' ->> 'message_id', '') = '';
