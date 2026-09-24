-- Phase 1.2: contractual payment-day schedule (e.g. Tuesday/Thursday).
-- Safe upgrade for an existing database. Apply before restarting claim-service.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE cargotech.claim_contracts
    ADD COLUMN IF NOT EXISTS payment_schedule_type varchar(32),
    ADD COLUMN IF NOT EXISTS payment_week_days varchar(128);

ALTER TABLE cargotech.claim_contracts
    DROP CONSTRAINT IF EXISTS claim_contracts_payment_schedule_type_check;
ALTER TABLE cargotech.claim_contracts
    ADD CONSTRAINT claim_contracts_payment_schedule_type_check
    CHECK (
        payment_schedule_type IS NULL OR payment_schedule_type = 'NEXT_PAYMENT_DAY'
    );

ALTER TABLE cargotech.claim_contracts
    DROP CONSTRAINT IF EXISTS claim_contracts_payment_schedule_pair_check;
ALTER TABLE cargotech.claim_contracts
    ADD CONSTRAINT claim_contracts_payment_schedule_pair_check
    CHECK (
        (payment_schedule_type IS NULL AND payment_week_days IS NULL)
        OR
        (payment_schedule_type IS NOT NULL AND payment_week_days IS NOT NULL)
    );

COMMIT;
