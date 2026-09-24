-- Persist the production hotfix that allows accountant-authored claim versions.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE cargotech.claim_versions
    DROP CONSTRAINT IF EXISTS claim_versions_source_check;

ALTER TABLE cargotech.claim_versions
    ADD CONSTRAINT claim_versions_source_check
    CHECK (source IN ('AI', 'LAWYER', 'ACCOUNTANT', 'RESTORED'));

COMMIT;
