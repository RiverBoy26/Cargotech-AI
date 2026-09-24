BEGIN;

ALTER TABLE cargotech.claim_claims
    DROP CONSTRAINT IF EXISTS claim_claims_status_check;

ALTER TABLE cargotech.claim_claims
    ADD CONSTRAINT claim_claims_status_check CHECK (
        status::text = ANY (ARRAY[
            'DRAFT',
            'PENDING_LEGAL_REVIEW',
            'LEGAL_APPROVED',
            'SENT',
            'AWAITING_RESPONSE',
            'PAID',
            'ESCALATED_TO_COURT',
            'CANCELLED',
            'CANCELLED_PAID',
            'CLOSED_IN_COURT'
        ]::text[])
    );

DROP INDEX IF EXISTS cargotech.uq_active_claim_per_shipment;

CREATE UNIQUE INDEX uq_active_claim_per_shipment
    ON cargotech.claim_claims (shipment_id)
    WHERE status::text <> ALL (ARRAY[
        'PAID',
        'CANCELLED',
        'CANCELLED_PAID',
        'CLOSED_IN_COURT'
    ]::text[]);

COMMIT;
