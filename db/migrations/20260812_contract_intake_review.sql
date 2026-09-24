-- Safe, idempotent upgrade for the human-in-the-loop contract intake flow.
-- Apply explicitly to an existing PostgreSQL database before deploying the
-- updated claim-service. Docker entrypoint init scripts run only for a new volume.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

-- A provisional DRAFT contract is created before its business number has been
-- extracted and confirmed by a user. PostgreSQL unique indexes allow multiple
-- NULL values, while still protecting confirmed contract numbers.
ALTER TABLE cargotech.claim_contracts
    ALTER COLUMN number DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_contract_number_required_when_active'
          AND conrelid = 'cargotech.claim_contracts'::regclass
    ) THEN
        ALTER TABLE cargotech.claim_contracts
            ADD CONSTRAINT chk_contract_number_required_when_active
            CHECK (status = 'DRAFT' OR number IS NOT NULL);
    END IF;
END $$;

-- Missing parser results have no source or confidence. Manual corrections are
-- tracked separately so the UI never presents rule-based confidence as applying
-- to a value entered by a person.
ALTER TABLE cargotech.claim_contract_extractions
    ALTER COLUMN source_text DROP NOT NULL,
    ALTER COLUMN confidence DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS manually_edited boolean NOT NULL DEFAULT false;

COMMIT;
