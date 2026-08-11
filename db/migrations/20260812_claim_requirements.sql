ALTER TABLE cargotech.claim_shipments
    ADD COLUMN IF NOT EXISTS ttn_signed_at date,
    ADD COLUMN IF NOT EXISTS invoice_date date;

ALTER TABLE cargotech.claim_claims
    ADD COLUMN IF NOT EXISTS recipient_name text,
    ADD COLUMN IF NOT EXISTS recipient_email text,
    ADD COLUMN IF NOT EXISTS recipient_address text,
    ADD COLUMN IF NOT EXISTS bank_details text,
    ADD COLUMN IF NOT EXISTS response_deadline_days integer,
    ADD COLUMN IF NOT EXISTS signer_full_name text,
    ADD COLUMN IF NOT EXISTS signer_position text,
    ADD COLUMN IF NOT EXISTS signer_authority text,
    ADD COLUMN IF NOT EXISTS non_payment_confirmation_requested_at timestamptz,
    ADD COLUMN IF NOT EXISTS non_payment_confirmation_requested_by uuid,
    ADD COLUMN IF NOT EXISTS document_validation_status varchar(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS document_validation_errors text,
    ADD COLUMN IF NOT EXISTS manual_review_required boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS manual_review_reason text,
    ADD COLUMN IF NOT EXISTS used_sources text,
    ADD COLUMN IF NOT EXISTS validation_overridden_at timestamptz,
    ADD COLUMN IF NOT EXISTS validation_overridden_by uuid,
    ADD COLUMN IF NOT EXISTS validation_override_reason text;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_claim_response_deadline') THEN
        ALTER TABLE cargotech.claim_claims
            ADD CONSTRAINT chk_claim_response_deadline
            CHECK (response_deadline_days IS NULL OR response_deadline_days >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'claim_document_validation_status_check') THEN
        ALTER TABLE cargotech.claim_claims
            ADD CONSTRAINT claim_document_validation_status_check
            CHECK (document_validation_status IN ('PENDING', 'PASSED', 'FAILED', 'OVERRIDDEN'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS cargotech.article_395_rates (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    effective_from date NOT NULL UNIQUE,
    rate numeric(8, 4) NOT NULL CHECK (rate > 0),
    source varchar(500) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL
);

INSERT INTO cargotech.article_395_rates (effective_from, rate, source)
VALUES ('2026-07-27', 14.0000, 'https://www.cbr.ru/hd_base/KeyRate/')
ON CONFLICT (effective_from) DO UPDATE
SET rate = EXCLUDED.rate, source = EXCLUDED.source;

ALTER TABLE cargotech.claim_contracts
    ADD COLUMN IF NOT EXISTS extraction_status varchar(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN IF NOT EXISTS extraction_confirmed_at timestamptz,
    ADD COLUMN IF NOT EXISTS extraction_confirmed_by uuid;

ALTER TABLE cargotech.claim_contract_clauses
    ADD COLUMN IF NOT EXISTS extracted boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS cargotech.claim_contract_extractions (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    contract_id uuid NOT NULL REFERENCES cargotech.claim_contracts(id) ON DELETE CASCADE,
    field_name varchar(64) NOT NULL,
    extracted_value text,
    source_text text NOT NULL,
    source_page integer CHECK (source_page IS NULL OR source_page > 0),
    confidence numeric(5, 4) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    clause_number varchar(64),
    clause_type varchar(64),
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by uuid
);
CREATE INDEX IF NOT EXISTS idx_contract_extractions_contract
    ON cargotech.claim_contract_extractions (contract_id);
