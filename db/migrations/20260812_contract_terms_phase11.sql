-- Phase 1.1: preserve contractual day units and non-standard payment anchors.
-- Safe upgrade for an existing database. Apply before restarting claim-service.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE cargotech.claim_contracts
    ADD COLUMN IF NOT EXISTS payment_day_type varchar(32),
    ADD COLUMN IF NOT EXISTS claim_response_day_type varchar(32);

ALTER TABLE cargotech.claim_shipments
    ADD COLUMN IF NOT EXISTS payment_start_event_date date;

-- Existing records historically treated terms as calendar days. Preserve that
-- behavior explicitly instead of changing old calculations after deployment.
UPDATE cargotech.claim_contracts
SET payment_day_type = 'CALENDAR_DAYS'
WHERE payment_days IS NOT NULL AND payment_day_type IS NULL;

UPDATE cargotech.claim_contracts
SET claim_response_day_type = 'CALENDAR_DAYS'
WHERE claim_response_days IS NOT NULL AND claim_response_day_type IS NULL;

ALTER TABLE cargotech.claim_contracts
    DROP CONSTRAINT IF EXISTS claim_contracts_payment_start_event_check;

ALTER TABLE cargotech.claim_contracts
    ADD CONSTRAINT claim_contracts_payment_start_event_check
    CHECK (
        payment_start_event IS NULL OR payment_start_event IN (
            'ACT_SIGNED',
            'UNLOADING_DATE',
            'TTN_SIGNED',
            'INVOICE_DATE',
            'REGISTRY_INCLUDED',
            'DOCUMENT_PACKAGE_RECEIVED'
        )
    );

ALTER TABLE cargotech.claim_contracts
    DROP CONSTRAINT IF EXISTS claim_contracts_payment_day_type_check;
ALTER TABLE cargotech.claim_contracts
    ADD CONSTRAINT claim_contracts_payment_day_type_check
    CHECK (
        payment_day_type IS NULL OR payment_day_type IN (
            'CALENDAR_DAYS', 'WORKING_DAYS', 'BANKING_DAYS'
        )
    );

ALTER TABLE cargotech.claim_contracts
    DROP CONSTRAINT IF EXISTS claim_contracts_claim_response_day_type_check;
ALTER TABLE cargotech.claim_contracts
    ADD CONSTRAINT claim_contracts_claim_response_day_type_check
    CHECK (
        claim_response_day_type IS NULL OR claim_response_day_type IN (
            'CALENDAR_DAYS', 'WORKING_DAYS', 'BANKING_DAYS'
        )
    );

COMMIT;
