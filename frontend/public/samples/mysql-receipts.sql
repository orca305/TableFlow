-- Run once in the selected target schema/database with a schema owner account.
-- Loader runtime requires SELECT/INSERT on this table and SELECT/INSERT on the target.
CREATE TABLE `tableflow_load_receipts` (
    request_id VARCHAR(36) PRIMARY KEY,
    payload_hash VARCHAR(64) NOT NULL,
    table_name VARCHAR(128) NOT NULL,
    inserted_rows INTEGER NOT NULL
) ENGINE=InnoDB;
