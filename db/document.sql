CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS cargotech;

CREATE OR REPLACE FUNCTION cargotech.touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TABLE IF NOT EXISTS cargotech.document_files (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    storage_provider varchar(32) NOT NULL,
    bucket_name varchar(255),
    storage_key varchar(1000) NOT NULL,
    original_name varchar(500) NOT NULL,
    content_type varchar(255),
    size_bytes bigint NOT NULL,
    checksum varchar(128) NOT NULL,
    uploaded_by uuid NOT NULL,
    uploaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT document_files_size_check CHECK (size_bytes >= 0),
    CONSTRAINT document_files_provider_check
        CHECK (storage_provider IN ('LOCAL', 'MINIO', 'S3'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_files_storage_key
    ON cargotech.document_files(storage_provider, storage_key);
CREATE INDEX IF NOT EXISTS idx_document_files_organization
    ON cargotech.document_files(organization_id, uploaded_at DESC);

CREATE TABLE IF NOT EXISTS cargotech.document_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    file_id uuid NOT NULL UNIQUE
        REFERENCES cargotech.document_files(id),
    document_type varchar(64) NOT NULL,
    document_number varchar(255),
    document_date date,
    description text,
    source varchar(32) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT document_documents_source_check
        CHECK (source IN ('UPLOADED', 'GENERATED')),
    CONSTRAINT document_documents_status_check
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED'))
);

CREATE INDEX IF NOT EXISTS idx_document_documents_organization
    ON cargotech.document_documents(organization_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_document_documents_type
    ON cargotech.document_documents(organization_id, document_type, status);

DROP TRIGGER IF EXISTS trg_document_documents_touch
    ON cargotech.document_documents;
CREATE TRIGGER trg_document_documents_touch
BEFORE UPDATE ON cargotech.document_documents
FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();

CREATE TABLE IF NOT EXISTS cargotech.document_document_links (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL
        REFERENCES cargotech.document_documents(id) ON DELETE CASCADE,
    entity_type varchar(32) NOT NULL,
    entity_id uuid NOT NULL,
    link_type varchar(64) NOT NULL DEFAULT 'ATTACHMENT',
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT document_links_entity_type_check CHECK (
        entity_type IN (
            'CONTRACT',
            'SHIPMENT',
            'CLAIM',
            'COURT_PACKAGE',
            'PAYMENT_IMPORT'
        )
    ),
    UNIQUE (document_id, entity_type, entity_id, link_type)
);

CREATE INDEX IF NOT EXISTS idx_document_links_entity
    ON cargotech.document_document_links(entity_type, entity_id, created_at DESC);

CREATE TABLE IF NOT EXISTS cargotech.document_document_texts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL UNIQUE
        REFERENCES cargotech.document_documents(id) ON DELETE CASCADE,
    text_content text NOT NULL,
    extraction_method varchar(64) NOT NULL,
    page_count integer,
    language varchar(16),
    extraction_metadata jsonb,
    extracted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT document_texts_page_count_check
        CHECK (page_count IS NULL OR page_count >= 0)
);

CREATE TABLE IF NOT EXISTS cargotech.document_claim_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid,
    code varchar(128) NOT NULL,
    name varchar(500) NOT NULL,
    claim_type varchar(64) NOT NULL,
    client_id uuid,
    description text,
    is_default boolean NOT NULL DEFAULT false,
    priority integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT document_claim_templates_global_scope_check
        CHECK (organization_id IS NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_template_code
    ON cargotech.document_claim_templates(
        COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid),
        code
    );
CREATE INDEX IF NOT EXISTS idx_document_template_selection
    ON cargotech.document_claim_templates(
        organization_id,
        claim_type,
        client_id,
        active,
        is_default,
        priority DESC
    );

DROP TRIGGER IF EXISTS trg_document_claim_templates_touch
    ON cargotech.document_claim_templates;
CREATE TRIGGER trg_document_claim_templates_touch
BEFORE UPDATE ON cargotech.document_claim_templates
FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();

CREATE TABLE IF NOT EXISTS cargotech.document_claim_template_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id uuid NOT NULL
        REFERENCES cargotech.document_claim_templates(id) ON DELETE CASCADE,
    version_number integer NOT NULL,
    encrypted_content text NOT NULL,
    content_sha256 varchar(128) NOT NULL,
    encryption_key_id varchar(128) NOT NULL,
    encryption_algorithm varchar(64) NOT NULL DEFAULT 'AES-256-GCM',
    content_format varchar(32) NOT NULL DEFAULT 'MUSTACHE_TEXT',
    variables jsonb NOT NULL DEFAULT '[]'::jsonb,
    change_comment text,
    active boolean NOT NULL DEFAULT false,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT document_template_version_positive
        CHECK (version_number > 0),
    CONSTRAINT document_template_content_format_check
        CHECK (content_format IN ('MUSTACHE_TEXT')),
    UNIQUE (template_id, version_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_template_active_version
    ON cargotech.document_claim_template_versions(template_id)
    WHERE active = true;

CREATE TABLE IF NOT EXISTS cargotech.document_generation_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    claim_id uuid NOT NULL,
    document_id uuid
        REFERENCES cargotech.document_documents(id),
    output_type varchar(64) NOT NULL,
    source_version_id uuid,
    request_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(32) NOT NULL DEFAULT 'CREATED',
    error_message text,
    generated_by uuid,
    generated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT document_generation_status_check
        CHECK (status IN ('CREATED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_document_generation_claim
    ON cargotech.document_generation_logs(
        organization_id,
        claim_id,
        generated_at DESC
    );

CREATE TABLE IF NOT EXISTS cargotech.document_email_deliveries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    document_id uuid NOT NULL
        REFERENCES cargotech.document_documents(id),
    recipient varchar(320) NOT NULL,
    cc varchar(320),
    subject varchar(500) NOT NULL,
    message_body text,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    error_message text,
    sent_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT document_email_status_check
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    CONSTRAINT document_email_attempt_check
        CHECK (attempt_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_document_email_document
    ON cargotech.document_email_deliveries(
        organization_id,
        document_id,
        created_at DESC
    );

DROP TRIGGER IF EXISTS trg_document_email_deliveries_touch
    ON cargotech.document_email_deliveries;
CREATE TRIGGER trg_document_email_deliveries_touch
BEFORE UPDATE ON cargotech.document_email_deliveries
FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();

-- Global fallback template. The ciphertext is AES-256-GCM in the same
-- iv(12 bytes) + ciphertext + tag(16 bytes) format used by
-- DocumentTemplateCryptoService. Development key:
-- Base64("0123456789abcdef0123456789abcdef")
-- MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
INSERT INTO cargotech.document_claim_templates (
    id,
    organization_id,
    code,
    name,
    claim_type,
    client_id,
    description,
    is_default,
    priority,
    active,
    created_by
)
VALUES (
    '00000000-0000-0000-0000-00000000d001',
    NULL,
    'PAYMENT_DELAY_DEFAULT',
    'Базовый шаблон претензии по просрочке оплаты',
    'PAYMENT_DELAY',
    NULL,
    'Системный демонстрационный шаблон. Замените его рабочей версией.',
    true,
    0,
    true,
    '00000000-0000-0000-0000-000000000000'
)
ON CONFLICT (id) DO UPDATE
SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    active = EXCLUDED.active;

INSERT INTO cargotech.document_claim_template_versions (
    id,
    template_id,
    version_number,
    encrypted_content,
    content_sha256,
    encryption_key_id,
    encryption_algorithm,
    content_format,
    variables,
    change_comment,
    active,
    created_by
)
VALUES (
    '00000000-0000-0000-0000-00000000d101',
    '00000000-0000-0000-0000-00000000d001',
    1,
    'Y2FyZ290ZWNoaXYxIRfMW43GwyuypxzhTHZPjtPijnyVcjXhJVqBrNToZK0iZVlKUAAH7kYtpuu2Jqw+NtOU9He6Hf6r3X3OTfjpVe8c2GOlzoyKkWW1SUjZXRwXyGDEfUkb8hKbyjYdhN7kET1j+Qau8F1nraetk+q27uicE1JT/C+VKZJNc4wxe97v/5GaR68D5auK/Bherk1EtEgSEurPpgXOp3mLuScjTkWa5BpE9jveGGqIO+9p3K9iUUGAxVKGxWvs2Dx0P7sfzdFlLU7mkhRf0ECB0mjTkVJX7Y43BKuDv1EXaiIwJfhGy8yhxd8/Amfl90MYL0Lx9SmBEbbx+wJWh5gRJZQIPcuT/s1tZLH8/a1zz9cPkpzfBXBVtvTgkkQHUZY/a+mhUU9ATAqxBDTQzicTWkHJM+D6NyNcCRSUHppexPokeDh8o1D6WRroOm9O1mAp91WUE5VwJMjRTi9bqFFTNgESMUnSjzNxKWYNlRcK2LDV4OT2/an5Nn+ouSawkOM1Xc2iqWlIAn0/EYuG7iU94OZ0QzodfsFit5i7byUcpGQ4ds8Y72wq6m/Zq2caLC7W8R46XSdk92Wqr4LuPJeyrt7h3Y9WvIY60pqbwE63chzZ7+RJd2hZcMmdDiB1JJvvQ+tWSkirE3P05wKA0csuVBwnQwTLVS8iyv9J3/xjG7pdP80M2RAeExWL9AjgxWSZwkvXU3iFGgNauZiooEn71Ui8mPhy9gkSpWFRr3U/0W/uKCLA1OkS8xvtx2dDYtjcCNKTMMlHakfp1ZaVbuPZO47U4Cc0gEMVWgkY8E3IcE0PEjks2yKeH0inCiK+8Xqy99Pis/o07X9OfqYuVUy6hnFIL4oZi6YZIcKT1AZs4hfjkOUwz+YAqIGB3b5UC8WcvUuFJPii36vP9kjemRJt3mfoMtoD7GcSS7Qtg5Bafnq2J7PZZD/NzUA3dfTf6IOCPCvg5Fh8k7xJsGbh/UhM9rAnjSi4fJi8JP+oCvoTzl5qIhlDNrZzir4NwY6sTCy5wHLI0nYgrI/pCFbM/GvKtzHnYbYbc0guyJ1gcAlN8xBUL+RUTFe2EmxBk5s3fYj7Z05V0KLmjk6PiXZd28mooB2pnsAGeqs+g+t/6yvSJR0p63CRfTM61CazxylBVScMGisGkBt0cTGBt/9jrKQyNsvslgPM8W4A2SuL+22AzzpMAqvaHTDfdMXr3cHpQzJ+Q8yZxsRRJvb4st2YWSgy7N58KPkYvXsxlGa/YF/YgLV/NJldUcBnBJEdo5NrZy1OySlXRwt6zkoMUAyZ0EDxgRadTeA=',
    '770fc4e644daed1bdcdcffeb4bcbcc4d190c1a2e7ea5268e01d69c76c5792157',
    'dev-seed-v1',
    'AES-256-GCM',
    'MUSTACHE_TEXT',
    '[
      "creditor.name",
      "creditor.inn",
      "creditor.legalAddress",
      "debtor.name",
      "debtor.inn",
      "debtor.legalAddress",
      "claim.number",
      "claim.date",
      "contract.number",
      "contract.date",
      "claim.generatedText",
      "calculation.principalDebt",
      "calculation.currency",
      "calculation.penaltyAmount",
      "calculation.totalAmount",
      "contract.claimResponseDays",
      "claim.attachments",
      "signer.position",
      "signer.fullName"
    ]'::jsonb,
    'Первая демонстрационная версия',
    false,
    '00000000-0000-0000-0000-000000000000'
)
ON CONFLICT (id) DO UPDATE
SET
    encrypted_content = EXCLUDED.encrypted_content,
    content_sha256 = EXCLUDED.content_sha256,
    variables = EXCLUDED.variables,
    active = EXCLUDED.active;

-- The original demo version is retained for history, but it must not be used:
-- its ciphertext was created with a key that does not match dev-seed-v1.
UPDATE cargotech.document_claim_template_versions
SET active = false
WHERE template_id = '00000000-0000-0000-0000-00000000d001';

-- Base template recreated with DOCUMENT_TEMPLATE_ENCRYPTION_KEY_ID=dev-seed-v1
-- and Base64("0123456789abcdef0123456789abcdef").
INSERT INTO cargotech.document_claim_template_versions (
    id,
    template_id,
    version_number,
    encrypted_content,
    content_sha256,
    encryption_key_id,
    encryption_algorithm,
    content_format,
    variables,
    change_comment,
    active,
    created_by
)
VALUES (
    '00000000-0000-0000-0000-00000000d102',
    '00000000-0000-0000-0000-00000000d001',
    2,
    '56OQbmYsr4eNhhJCXNyKL3l8o5thzZZUV+oSv21n3hSV0+6CodCU02acNO/6tmESs6f0vumeoiNWbqQ9al5a1d4ZqJrTFRmPUcz5OlsjRygl0cwyDdHHGbBHbppF0x1uNJVZ72cD27Iav6+Jq2AVBTiYD3JuxU1AuQeaJuRWO596vhypHWpJfYXdQ5FvHgJdnXScrxGpnXLi5voWH8dgUZCMckEDmz+GbpWOrd7/KZx/Rlj527cUaslEj1s/9F/PnjL8bCDnnsyq67PVajPjEjtm5bXfRcKGWNtR3eZwAGyS6n/2WPG6z4zIN4PzE7CCNQZ+0fGWtYDeGcsBeznynOtqri7yKlaeGccwCdIk3InShV5LA7pIJNWf1wd2+nzdMnmragPSFIAUKPF3qGcGUn2gnAqcYpB28hAwhPoU7DwzvIrjXkD0uP1MaskDcj1M3CVbvGwQA3evKG0lFg86p+yX72XhmYEj0eZcOGfXptwK5arhZ3eNylxSyOEmjcNYDm6f5OQ0JeSmo2umjI2pbJXDCMrrJ0JDXUY9z9HilTvESPr4tFdeZOjGLy9zU7yhyOMx8XPRSBKz06b2fwf74PujxyUYkw6KFHpgTW+0QJMhnvyAzKEQWHpTAP4BYDvkZrw/MpX/BNoTWSFquSsj4pDsa/nh3d5EBk8EIjL9MqpIoNbjkMI784vk',
    '9384d668d7c288d50296e1ac7a46a106506a787210113b91ba0773a5f5b80d3e',
    'dev-seed-v1',
    'AES-256-GCM',
    'MUSTACHE_TEXT',
    '[
      "claim.number",
      "debtor.name",
      "debtor.inn",
      "creditor.name",
      "creditor.inn",
      "contract.number",
      "claim.generatedText",
      "calculation.principalDebt",
      "calculation.currency",
      "calculation.penaltyAmount",
      "calculation.totalAmount",
      "signer.position",
      "signer.fullName"
    ]'::jsonb,
    'Пересоздание шаблона с ключом dev-seed-v1',
    true,
    '00000000-0000-0000-0000-000000000000'
)
ON CONFLICT (id) DO UPDATE
SET
    encrypted_content = EXCLUDED.encrypted_content,
    content_sha256 = EXCLUDED.content_sha256,
    encryption_key_id = EXCLUDED.encryption_key_id,
    encryption_algorithm = EXCLUDED.encryption_algorithm,
    content_format = EXCLUDED.content_format,
    variables = EXCLUDED.variables,
    change_comment = EXCLUDED.change_comment,
    active = EXCLUDED.active;
