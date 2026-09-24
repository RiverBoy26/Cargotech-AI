BEGIN;

ALTER TABLE cargotech.document_generation_logs
    ADD COLUMN IF NOT EXISTS claim_version_id uuid;

DROP INDEX IF EXISTS cargotech.uq_document_generation_claim_version;

WITH resolved_generations AS (
    SELECT
        generation.id AS generation_id,
        generation.claim_version_id AS stored_claim_version_id,
        coalesce(generation.claim_version_id, version.id) AS resolved_claim_version_id,
        generation.organization_id,
        generation.claim_id,
        generation.output_type,
        generation.generated_at
    FROM cargotech.document_generation_logs generation
    LEFT JOIN cargotech.document_documents document
      ON document.id = generation.document_id
    LEFT JOIN cargotech.claim_versions version
      ON generation.claim_version_id IS NULL
     AND version.claim_id = generation.claim_id
     AND version.version_number = substring(
         coalesce(document.description, '')
         FROM '([0-9]+)[^0-9]*$'
     )::integer
    WHERE generation.status IN ('PROCESSING', 'COMPLETED')
),
ranked_generations AS (
    SELECT
        generation_id,
        stored_claim_version_id,
        resolved_claim_version_id,
        row_number() OVER (
            PARTITION BY organization_id,
                         claim_id,
                         resolved_claim_version_id,
                         output_type
            ORDER BY (stored_claim_version_id IS NOT NULL) DESC,
                     generated_at DESC,
                     generation_id
        ) AS duplicate_rank
    FROM resolved_generations
    WHERE resolved_claim_version_id IS NOT NULL
)
UPDATE cargotech.document_generation_logs generation
   SET claim_version_id = ranked.resolved_claim_version_id
  FROM ranked_generations ranked
 WHERE generation.id = ranked.generation_id
   AND ranked.stored_claim_version_id IS NULL
   AND ranked.duplicate_rank = 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_generation_claim_version_format
    ON cargotech.document_generation_logs(
        organization_id,
        claim_id,
        claim_version_id,
        output_type
    )
    WHERE claim_version_id IS NOT NULL
      AND status IN ('PROCESSING', 'COMPLETED');

COMMIT;
