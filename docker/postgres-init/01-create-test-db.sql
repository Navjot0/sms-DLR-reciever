-- Runs once, on first start of an empty data volume.
-- dlr (application DB) is created by POSTGRES_DB; this adds the integration-test DB.
CREATE DATABASE dlr_test OWNER dlr;
