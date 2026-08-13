-- Phase 2: persistent synchronization state for production Contract RAG.
-- Safe to apply before deploying the updated claim-service.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE cargotech.claim_contracts
    ADD COLUMN IF NOT EXISTS rag_index_status varchar(32) NOT NULL DEFAULT 'NOT_INDEXED',
    ADD COLUMN IF NOT EXISTS rag_indexed_at timestamptz NULL,
    ADD COLUMN IF NOT EXISTS rag_index_error varchar(1000) NULL,
    ADD COLUMN IF NOT EXISTS rag_source_document_id uuid NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'claim_contracts_rag_index_status_check'
          AND conrelid = 'cargotech.claim_contracts'::regclass
    ) THEN
        ALTER TABLE cargotech.claim_contracts
            ADD CONSTRAINT claim_contracts_rag_index_status_check
            CHECK (rag_index_status IN ('NOT_INDEXED', 'PENDING', 'INDEXED', 'FAILED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_claim_contracts_rag_status
    ON cargotech.claim_contracts (organization_id, rag_index_status)
    WHERE deleted_at IS NULL;

COMMIT;
