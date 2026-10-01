-- Bulk-campaign billing callbacks carry hundreds of events. The complete callback is now stored once
-- (on the first event of the batch); every row still keeps its own raw_event.
ALTER TABLE dlr_billing_events ALTER COLUMN raw_payload DROP NOT NULL;
