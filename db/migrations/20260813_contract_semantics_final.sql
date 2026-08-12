BEGIN;

ALTER TABLE cargotech.claim_contracts
    ADD COLUMN IF NOT EXISTS penalty_cap_percent numeric(12, 6),
    ADD COLUMN IF NOT EXISTS penalty_cap_base varchar(32);

ALTER TABLE cargotech.claim_contracts
    DROP CONSTRAINT IF EXISTS claim_contracts_penalty_cap_percent_check,
    DROP CONSTRAINT IF EXISTS claim_contracts_penalty_cap_base_check,
    DROP CONSTRAINT IF EXISTS claim_contracts_penalty_cap_pair_check;

ALTER TABLE cargotech.claim_contracts
    ADD CONSTRAINT claim_contracts_penalty_cap_percent_check
        CHECK (penalty_cap_percent IS NULL OR penalty_cap_percent >= 0),
    ADD CONSTRAINT claim_contracts_penalty_cap_base_check
        CHECK (penalty_cap_base IS NULL OR penalty_cap_base IN (
            'PRINCIPAL_DEBT', 'OUTSTANDING_DEBT', 'SHIPMENT_COST', 'INVOICE_AMOUNT'
        )),
    ADD CONSTRAINT claim_contracts_penalty_cap_pair_check
        CHECK (
            (penalty_cap_percent IS NULL AND penalty_cap_base IS NULL)
            OR (penalty_cap_percent IS NOT NULL AND penalty_cap_base IS NOT NULL)
        );

COMMIT;
