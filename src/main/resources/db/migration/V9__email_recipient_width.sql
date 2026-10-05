-- Email DLRs store the recipient address in "mobile"; 30 characters is too short for an address.
ALTER TABLE dlr_events         ALTER COLUMN mobile TYPE VARCHAR(320);
ALTER TABLE dlr_message_status ALTER COLUMN mobile TYPE VARCHAR(320);
