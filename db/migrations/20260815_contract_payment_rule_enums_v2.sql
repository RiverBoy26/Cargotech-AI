BEGIN;

ALTER TABLE cargotech.claim_contracts
    DROP CONSTRAINT IF EXISTS claim_contracts_payment_start_event_check;

ALTER TABLE cargotech.claim_contracts
    ADD CONSTRAINT claim_contracts_payment_start_event_check
    CHECK (
        payment_start_event IS NULL
        OR payment_start_event IN (
            'ACT_SIGNED',
            'UNLOADING_DATE',
            'TTN_SIGNED',
            'INVOICE_DATE',
            'REGISTRY_INCLUDED',
            'DOCUMENT_PACKAGE_RECEIVED',
            'LATEST_ACT_OR_DOCUMENT_PACKAGE',
            'ACT_SIGNED_REQUIRES_DOCUMENT_PACKAGE'
        )
    );

ALTER TABLE cargotech.claim_contracts
    DROP CONSTRAINT IF EXISTS claim_contracts_payment_schedule_type_check;

ALTER TABLE cargotech.claim_contracts
    ADD CONSTRAINT claim_contracts_payment_schedule_type_check
    CHECK (
        payment_schedule_type IS NULL
        OR payment_schedule_type IN (
            'NEXT_PAYMENT_DAY',
            'NEXT_PAYMENT_DAY_AFTER_TERM'
        )
    );

COMMIT;
