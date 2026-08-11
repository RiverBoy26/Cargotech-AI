-- cargotech.claim_parties определение

-- Drop table

-- DROP TABLE cargotech.claim_parties;

CREATE TABLE cargotech.claim_parties (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NOT NULL,
	"type" varchar(32) NOT NULL,
	"name" varchar(500) NOT NULL,
	inn varchar(12) NULL,
	kpp varchar(9) NULL,
	ogrn varchar(15) NULL,
	legal_address text NULL,
	postal_address text NULL,
	email varchar(320) NULL,
	phone varchar(64) NULL,
	active bool DEFAULT true NOT NULL,
	deleted_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	created_by uuid NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_by uuid NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT claim_parties_pkey PRIMARY KEY (id),
	CONSTRAINT claim_parties_type_check CHECK (((type)::text = ANY ((ARRAY['CLIENT'::character varying, 'EXPEDITOR'::character varying, 'CARRIER'::character varying, 'THIRD_PARTY'::character varying])::text[])))
);
CREATE INDEX idx_claim_parties_deleted ON cargotech.claim_parties USING btree (deleted_at) WHERE (deleted_at IS NULL);
CREATE INDEX idx_claim_parties_inn ON cargotech.claim_parties USING btree (organization_id, inn);
CREATE INDEX idx_claim_parties_name ON cargotech.claim_parties USING btree (organization_id, name);
CREATE INDEX idx_claim_parties_name_trgm ON cargotech.claim_parties USING gin (name gin_trgm_ops);
CREATE INDEX idx_claim_parties_type ON cargotech.claim_parties USING btree (organization_id, type);

-- Table Triggers

CREATE TRIGGER trg_claim_parties_touch BEFORE
UPDATE
    ON
    cargotech.claim_parties FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.outbox_events определение

-- Drop table

-- DROP TABLE cargotech.outbox_events;

CREATE TABLE IF NOT EXISTS cargotech.outbox_events (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	module_name varchar(64) NOT NULL,
	aggregate_type varchar(128) NOT NULL,
	aggregate_id uuid NOT NULL,
	event_type varchar(128) NOT NULL,
	event_version int4 DEFAULT 1 NOT NULL,
	payload jsonb NOT NULL,
	status varchar(32) DEFAULT 'NEW'::character varying NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	published_at timestamptz NULL,
	retry_count int4 DEFAULT 0 NOT NULL,
	error_message text NULL,
	CONSTRAINT outbox_events_module_name_check CHECK (((module_name)::text = ANY ((ARRAY['AUTH'::character varying, 'CLAIM'::character varying, 'PAYMENT'::character varying, 'DOCUMENT'::character varying, 'AI'::character varying, 'NOTIFICATION'::character varying, 'AUDIT'::character varying])::text[]))),
	CONSTRAINT outbox_events_pkey PRIMARY KEY (id),
	CONSTRAINT outbox_events_status_check CHECK (((status)::text = ANY ((ARRAY['NEW'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying])::text[])))
);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON cargotech.outbox_events USING btree (aggregate_id);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON cargotech.outbox_events USING btree (status, created_at) WHERE ((status)::text = ANY ((ARRAY['NEW'::character varying, 'FAILED'::character varying])::text[]));


-- cargotech.claim_contracts определение

-- Drop table

-- DROP TABLE cargotech.claim_contracts;

CREATE TABLE cargotech.claim_contracts (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NOT NULL,
	"number" varchar(128) NOT NULL,
	client_id uuid NOT NULL,
	expeditor_id uuid NOT NULL,
	signed_at date NULL,
	valid_from date NULL,
	valid_to date NULL,
	status varchar(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
	payment_days int4 NULL,
	payment_start_event varchar(64) NULL,
	penalty_type varchar(64) NULL,
	penalty_rate numeric(12, 6) NULL,
	claim_response_days int4 NULL,
	jurisdiction text NULL,
	document_id uuid NULL,
	deleted_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	created_by uuid NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_by uuid NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT chk_contract_dates CHECK (((valid_to IS NULL) OR (valid_from IS NULL) OR (valid_to >= valid_from))),
	CONSTRAINT claim_contracts_claim_response_days_check CHECK (((claim_response_days IS NULL) OR (claim_response_days >= 0))),
	CONSTRAINT claim_contracts_payment_days_check CHECK (((payment_days IS NULL) OR (payment_days >= 0))),
	CONSTRAINT claim_contracts_payment_start_event_check CHECK (((payment_start_event IS NULL) OR ((payment_start_event)::text = ANY ((ARRAY['ACT_SIGNED'::character varying, 'UNLOADING_DATE'::character varying, 'TTN_SIGNED'::character varying, 'INVOICE_DATE'::character varying])::text[])))),
	CONSTRAINT claim_contracts_penalty_rate_check CHECK (((penalty_rate IS NULL) OR (penalty_rate >= (0)::numeric))),
	CONSTRAINT claim_contracts_penalty_type_check CHECK (((penalty_type IS NULL) OR ((penalty_type)::text = ANY ((ARRAY['CONTRACT_PENALTY'::character varying, 'ARTICLE_395'::character varying, 'NONE'::character varying])::text[])))),
	CONSTRAINT claim_contracts_pkey PRIMARY KEY (id),
	CONSTRAINT claim_contracts_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ACTIVE'::character varying, 'EXPIRED'::character varying, 'TERMINATED'::character varying, 'ARCHIVED'::character varying])::text[]))),
	CONSTRAINT claim_contracts_client_id_fkey FOREIGN KEY (client_id) REFERENCES cargotech.claim_parties(id),
	CONSTRAINT claim_contracts_expeditor_id_fkey FOREIGN KEY (expeditor_id) REFERENCES cargotech.claim_parties(id)
);
CREATE INDEX idx_claim_contracts_client ON cargotech.claim_contracts USING btree (client_id);
CREATE INDEX idx_claim_contracts_deleted ON cargotech.claim_contracts USING btree (deleted_at) WHERE (deleted_at IS NULL);
CREATE INDEX idx_claim_contracts_expeditor ON cargotech.claim_contracts USING btree (expeditor_id);
CREATE INDEX idx_claim_contracts_number_trgm ON cargotech.claim_contracts USING gin (number gin_trgm_ops);
CREATE INDEX idx_claim_contracts_org ON cargotech.claim_contracts USING btree (organization_id);
CREATE INDEX idx_claim_contracts_status ON cargotech.claim_contracts USING btree (status);
CREATE UNIQUE INDEX uq_claim_contracts_number ON cargotech.claim_contracts USING btree (organization_id, number);

-- Table Triggers

CREATE TRIGGER trg_claim_contracts_touch BEFORE
UPDATE
    ON
    cargotech.claim_contracts FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.claim_contract_clauses определение

-- Drop table

-- DROP TABLE cargotech.claim_contract_clauses;

CREATE TABLE cargotech.claim_contract_clauses (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	contract_id uuid NOT NULL,
	clause_number varchar(64) NULL,
	clause_type varchar(64) NOT NULL,
	section_name varchar(255) NULL,
	text text NOT NULL,
	source_page int4 NULL,
	active bool DEFAULT true NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	created_by uuid NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_by uuid NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT claim_contract_clauses_clause_type_check CHECK (((clause_type)::text = ANY ((ARRAY['PAYMENT_TERMS'::character varying, 'PENALTY'::character varying, 'CLAIM_PROCEDURE'::character varying, 'JURISDICTION'::character varying, 'LIABILITY'::character varying, 'OTHER'::character varying])::text[]))),
	CONSTRAINT claim_contract_clauses_pkey PRIMARY KEY (id),
	CONSTRAINT claim_contract_clauses_source_page_check CHECK (((source_page IS NULL) OR (source_page > 0))),
	CONSTRAINT claim_contract_clauses_contract_id_fkey FOREIGN KEY (contract_id) REFERENCES cargotech.claim_contracts(id) ON DELETE CASCADE
);
CREATE INDEX idx_contract_clauses_contract ON cargotech.claim_contract_clauses USING btree (contract_id);

-- Table Triggers

CREATE TRIGGER trg_claim_contract_clauses_touch BEFORE
UPDATE
    ON
    cargotech.claim_contract_clauses FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.claim_shipments определение

-- Drop table

-- DROP TABLE cargotech.claim_shipments;

CREATE TABLE cargotech.claim_shipments (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NOT NULL,
	order_number varchar(128) NOT NULL,
	client_id uuid NOT NULL,
	expeditor_id uuid NOT NULL,
	contract_id uuid NOT NULL,
	route_from varchar(500) NULL,
	route_to varchar(500) NULL,
	loading_date date NULL,
	unloading_date date NULL,
	act_signed_at date NULL,
	service_amount numeric(19, 2) NOT NULL,
	currency varchar(10) DEFAULT 'RUB'::character varying NOT NULL,
	status varchar(32) DEFAULT 'CREATED'::character varying NOT NULL,
	external_id varchar(255) NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	created_by uuid NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_by uuid NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT chk_shipment_dates CHECK (((unloading_date IS NULL) OR (loading_date IS NULL) OR (unloading_date >= loading_date))),
	CONSTRAINT claim_shipments_pkey PRIMARY KEY (id),
	CONSTRAINT claim_shipments_service_amount_check CHECK ((service_amount >= (0)::numeric)),
	CONSTRAINT claim_shipments_status_check CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
	CONSTRAINT claim_shipments_client_id_fkey FOREIGN KEY (client_id) REFERENCES cargotech.claim_parties(id),
	CONSTRAINT claim_shipments_contract_id_fkey FOREIGN KEY (contract_id) REFERENCES cargotech.claim_contracts(id),
	CONSTRAINT claim_shipments_expeditor_id_fkey FOREIGN KEY (expeditor_id) REFERENCES cargotech.claim_parties(id)
);
CREATE INDEX idx_claim_shipments_client ON cargotech.claim_shipments USING btree (client_id);
CREATE INDEX idx_claim_shipments_contract ON cargotech.claim_shipments USING btree (contract_id);
CREATE INDEX idx_claim_shipments_expeditor ON cargotech.claim_shipments USING btree (expeditor_id);
CREATE INDEX idx_claim_shipments_order_trgm ON cargotech.claim_shipments USING gin (order_number gin_trgm_ops);
CREATE INDEX idx_claim_shipments_org ON cargotech.claim_shipments USING btree (organization_id);
CREATE INDEX idx_claim_shipments_status ON cargotech.claim_shipments USING btree (status);
CREATE UNIQUE INDEX uq_claim_shipments_order ON cargotech.claim_shipments USING btree (organization_id, order_number);

-- Table Triggers

CREATE TRIGGER trg_claim_shipments_touch BEFORE
UPDATE
    ON
    cargotech.claim_shipments FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.claim_court_packages определение

-- Drop table

-- DROP TABLE cargotech.claim_court_packages;

CREATE TABLE cargotech.claim_court_packages (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	claim_id uuid NOT NULL,
	status varchar(32) DEFAULT 'DRAFT'::character varying NOT NULL,
	readiness_percent int4 DEFAULT 0 NOT NULL,
	archive_document_id uuid NULL,
	created_by uuid NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT claim_court_packages_claim_id_key UNIQUE (claim_id),
	CONSTRAINT claim_court_packages_pkey PRIMARY KEY (id),
	CONSTRAINT claim_court_packages_readiness_percent_check CHECK (((readiness_percent >= 0) AND (readiness_percent <= 100))),
	CONSTRAINT claim_court_packages_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'READY'::character varying, 'ARCHIVED'::character varying])::text[])))
);

-- Table Triggers

CREATE TRIGGER trg_claim_court_packages_touch BEFORE
UPDATE
    ON
    cargotech.claim_court_packages FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.claim_court_package_items определение

-- Drop table

-- DROP TABLE cargotech.claim_court_package_items;

CREATE TABLE cargotech.claim_court_package_items (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	court_package_id uuid NOT NULL,
	item_type varchar(64) NOT NULL,
	required bool DEFAULT true NOT NULL,
	present bool DEFAULT false NOT NULL,
	document_id uuid NULL,
	"comment" text NULL,
	checked_by uuid NULL,
	checked_at timestamptz NULL,
	CONSTRAINT claim_court_package_items_court_package_id_item_type_key UNIQUE (court_package_id, item_type),
	CONSTRAINT claim_court_package_items_pkey PRIMARY KEY (id)
);


-- cargotech.claim_versions определение

-- Drop table

-- DROP TABLE cargotech.claim_versions;

CREATE TABLE cargotech.claim_versions (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	claim_id uuid NOT NULL,
	version_number int4 NOT NULL,
	"source" varchar(32) NOT NULL,
	base_version_id uuid NULL,
	"content" text NOT NULL,
	"comment" text NULL,
	is_final bool DEFAULT false NOT NULL,
	created_by uuid NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT claim_versions_claim_id_version_number_key UNIQUE (claim_id, version_number),
	CONSTRAINT claim_versions_pkey PRIMARY KEY (id),
	CONSTRAINT claim_versions_source_check CHECK (((source)::text = ANY ((ARRAY['AI'::character varying, 'LAWYER'::character varying, 'RESTORED'::character varying])::text[])))
);
CREATE INDEX idx_claim_versions_claim ON cargotech.claim_versions USING btree (claim_id);
CREATE UNIQUE INDEX uq_final_claim_version ON cargotech.claim_versions USING btree (claim_id) WHERE (is_final = true);


-- cargotech.claim_claims определение

-- Drop table

-- DROP TABLE cargotech.claim_claims;

CREATE TABLE cargotech.claim_claims (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NOT NULL,
	claim_number varchar(128) NOT NULL,
	shipment_id uuid NOT NULL,
	contract_id uuid NOT NULL,
	creditor_id uuid NOT NULL,
	debtor_id uuid NOT NULL,
	claim_type varchar(64) DEFAULT 'PAYMENT_DELAY'::character varying NOT NULL,
	status varchar(64) DEFAULT 'DRAFT'::character varying NOT NULL,
	reason text NULL,
	principal_debt numeric(19, 2) DEFAULT 0 NOT NULL,
	penalty_amount numeric(19, 2) DEFAULT 0 NOT NULL,
	total_amount numeric(19, 2) DEFAULT 0 NOT NULL,
	non_payment_confirmed bool DEFAULT false NOT NULL,
	non_payment_confirmed_at timestamptz NULL,
	non_payment_confirmed_by uuid NULL,
	non_payment_confirmation_comment text NULL,
	last_payment_check_id uuid NULL,
	assigned_lawyer_id uuid NULL,
	final_version_id uuid NULL,
	approved_at timestamptz NULL,
	approved_by uuid NULL,
	sent_at timestamptz NULL,
	paid_at timestamptz NULL,
	cancelled_at timestamptz NULL,
	cancellation_reason_code varchar(64) NULL,
	cancellation_reason text NULL,
	escalated_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	created_by uuid NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_by uuid NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT chk_claim_parties CHECK ((creditor_id <> debtor_id)),
	CONSTRAINT chk_claim_total CHECK ((total_amount = (principal_debt + penalty_amount))),
	CONSTRAINT chk_non_payment_confirmation CHECK (((non_payment_confirmed = false) OR ((non_payment_confirmed_at IS NOT NULL) AND (non_payment_confirmed_by IS NOT NULL)))),
	CONSTRAINT claim_claims_pkey PRIMARY KEY (id),
	CONSTRAINT claim_claims_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING_LEGAL_REVIEW'::character varying, 'LEGAL_APPROVED'::character varying, 'SENT'::character varying, 'AWAITING_RESPONSE'::character varying, 'PAID'::character varying, 'ESCALATED_TO_COURT'::character varying, 'CANCELLED'::character varying, 'CANCELLED_PAID'::character varying, 'CLOSED_IN_COURT'::character varying])::text[])))
);
CREATE INDEX idx_claim_created_by ON cargotech.claim_claims USING btree (created_by);
CREATE INDEX idx_claim_lawyer ON cargotech.claim_claims USING btree (assigned_lawyer_id);
CREATE INDEX idx_claim_number_trgm ON cargotech.claim_claims USING gin (claim_number gin_trgm_ops);
CREATE INDEX idx_claim_org_creditor ON cargotech.claim_claims USING btree (organization_id, creditor_id);
CREATE INDEX idx_claim_org_debtor ON cargotech.claim_claims USING btree (organization_id, debtor_id);
CREATE INDEX idx_claim_status ON cargotech.claim_claims USING btree (organization_id, status);
CREATE INDEX idx_claim_updated_at ON cargotech.claim_claims USING btree (updated_at DESC);
CREATE UNIQUE INDEX uq_active_claim_per_shipment ON cargotech.claim_claims USING btree (shipment_id) WHERE ((status)::text <> ALL ((ARRAY['PAID'::character varying, 'CANCELLED'::character varying, 'CANCELLED_PAID'::character varying, 'CLOSED_IN_COURT'::character varying])::text[]));
CREATE UNIQUE INDEX uq_claim_number ON cargotech.claim_claims USING btree (organization_id, claim_number);

-- Table Triggers

CREATE TRIGGER trg_claim_claims_touch BEFORE
UPDATE
    ON
    cargotech.claim_claims FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.claim_calculations определение

-- Drop table

-- DROP TABLE cargotech.claim_calculations;

CREATE TABLE cargotech.claim_calculations (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	claim_id uuid NOT NULL,
	calculation_version int4 NOT NULL,
	principal_debt numeric(19, 2) NOT NULL,
	paid_amount numeric(19, 2) DEFAULT 0 NOT NULL,
	remaining_debt numeric(19, 2) NOT NULL,
	overdue_start_date date NULL,
	calculation_date date NOT NULL,
	overdue_days int4 DEFAULT 0 NOT NULL,
	penalty_type varchar(64) NOT NULL,
	penalty_rate numeric(12, 6) NULL,
	penalty_amount numeric(19, 2) DEFAULT 0 NOT NULL,
	total_amount numeric(19, 2) NOT NULL,
	formula text NULL,
	input_snapshot jsonb NOT NULL,
	created_by uuid NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT chk_calculation_remaining CHECK ((remaining_debt = GREATEST((principal_debt - paid_amount), (0)::numeric))),
	CONSTRAINT chk_calculation_total CHECK ((total_amount = (remaining_debt + penalty_amount))),
	CONSTRAINT claim_calculations_claim_id_calculation_version_key UNIQUE (claim_id, calculation_version),
	CONSTRAINT claim_calculations_penalty_type_check CHECK (((penalty_type)::text = ANY ((ARRAY['CONTRACT_PENALTY'::character varying, 'ARTICLE_395'::character varying, 'NONE'::character varying])::text[]))),
	CONSTRAINT claim_calculations_pkey PRIMARY KEY (id)
);


-- cargotech.claim_comments определение

-- Drop table

-- DROP TABLE cargotech.claim_comments;

CREATE TABLE cargotech.claim_comments (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	claim_id uuid NOT NULL,
	author_id uuid NOT NULL,
	text text NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	deleted_at timestamptz NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT claim_comments_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_claim_comments_claim ON cargotech.claim_comments USING btree (claim_id);

-- Table Triggers

CREATE TRIGGER trg_claim_comments_touch BEFORE
UPDATE
    ON
    cargotech.claim_comments FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.claim_snapshots определение

-- Drop table

-- DROP TABLE cargotech.claim_snapshots;

CREATE TABLE cargotech.claim_snapshots (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	claim_id uuid NOT NULL,
	snapshot_type varchar(64) NOT NULL,
	snapshot_version int4 DEFAULT 1 NOT NULL,
	"data" jsonb NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	created_by uuid NULL,
	CONSTRAINT claim_snapshots_claim_id_snapshot_type_snapshot_version_key UNIQUE (claim_id, snapshot_type, snapshot_version),
	CONSTRAINT claim_snapshots_pkey PRIMARY KEY (id),
	CONSTRAINT claim_snapshots_snapshot_type_check CHECK (((snapshot_type)::text = ANY ((ARRAY['PARTIES'::character varying, 'CONTRACT'::character varying, 'SHIPMENT'::character varying, 'PAYMENT_STATUS'::character varying, 'APPROVAL'::character varying, 'SENDING'::character varying])::text[])))
);


-- cargotech.claim_status_history определение

-- Drop table

-- DROP TABLE cargotech.claim_status_history;

CREATE TABLE cargotech.claim_status_history (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	claim_id uuid NOT NULL,
	previous_status varchar(64) NULL,
	new_status varchar(64) NOT NULL,
	reason text NULL,
	changed_by uuid NULL,
	changed_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT claim_status_history_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_claim_status_history_claim ON cargotech.claim_status_history USING btree (claim_id);


-- cargotech.claim_court_packages внешние включи

ALTER TABLE cargotech.claim_court_packages ADD CONSTRAINT claim_court_packages_claim_id_fkey FOREIGN KEY (claim_id) REFERENCES cargotech.claim_claims(id) ON DELETE CASCADE;


-- cargotech.claim_court_package_items внешние включи

ALTER TABLE cargotech.claim_court_package_items ADD CONSTRAINT claim_court_package_items_court_package_id_fkey FOREIGN KEY (court_package_id) REFERENCES cargotech.claim_court_packages(id) ON DELETE CASCADE;


-- cargotech.claim_versions внешние включи

ALTER TABLE cargotech.claim_versions ADD CONSTRAINT claim_versions_base_version_id_fkey FOREIGN KEY (base_version_id) REFERENCES cargotech.claim_versions(id);
ALTER TABLE cargotech.claim_versions ADD CONSTRAINT claim_versions_claim_id_fkey FOREIGN KEY (claim_id) REFERENCES cargotech.claim_claims(id) ON DELETE CASCADE;


-- cargotech.claim_claims внешние включи

ALTER TABLE cargotech.claim_claims ADD CONSTRAINT claim_claims_contract_id_fkey FOREIGN KEY (contract_id) REFERENCES cargotech.claim_contracts(id);
ALTER TABLE cargotech.claim_claims ADD CONSTRAINT claim_claims_creditor_id_fkey FOREIGN KEY (creditor_id) REFERENCES cargotech.claim_parties(id);
ALTER TABLE cargotech.claim_claims ADD CONSTRAINT claim_claims_debtor_id_fkey FOREIGN KEY (debtor_id) REFERENCES cargotech.claim_parties(id);
ALTER TABLE cargotech.claim_claims ADD CONSTRAINT claim_claims_shipment_id_fkey FOREIGN KEY (shipment_id) REFERENCES cargotech.claim_shipments(id);
ALTER TABLE cargotech.claim_claims ADD CONSTRAINT fk_claim_final_version FOREIGN KEY (final_version_id) REFERENCES cargotech.claim_versions(id);


-- cargotech.claim_calculations внешние включи

ALTER TABLE cargotech.claim_calculations ADD CONSTRAINT claim_calculations_claim_id_fkey FOREIGN KEY (claim_id) REFERENCES cargotech.claim_claims(id) ON DELETE CASCADE;


-- cargotech.claim_comments внешние включи

ALTER TABLE cargotech.claim_comments ADD CONSTRAINT claim_comments_claim_id_fkey FOREIGN KEY (claim_id) REFERENCES cargotech.claim_claims(id) ON DELETE CASCADE;


-- cargotech.claim_snapshots внешние включи

ALTER TABLE cargotech.claim_snapshots ADD CONSTRAINT claim_snapshots_claim_id_fkey FOREIGN KEY (claim_id) REFERENCES cargotech.claim_claims(id) ON DELETE CASCADE;


-- cargotech.claim_status_history внешние включи

ALTER TABLE cargotech.claim_status_history ADD CONSTRAINT claim_status_history_claim_id_fkey FOREIGN KEY (claim_id) REFERENCES cargotech.claim_claims(id) ON DELETE CASCADE;


-- Normalize legacy expeditor references after organizations from auth-service
-- have been synchronized into claim_parties. A system EXPEDITOR uses
-- organization_id as its party id.
UPDATE cargotech.claim_contracts contract
SET expeditor_id = contract.organization_id,
    updated_at = CURRENT_TIMESTAMP
WHERE contract.expeditor_id <> contract.organization_id
  AND EXISTS (
      SELECT 1
      FROM cargotech.claim_parties projection
      WHERE projection.id = contract.organization_id
        AND projection.organization_id = contract.organization_id
        AND projection.type = 'EXPEDITOR'
        AND projection.deleted_at IS NULL
  );

UPDATE cargotech.claim_shipments shipment
SET expeditor_id = shipment.organization_id,
    updated_at = CURRENT_TIMESTAMP
WHERE shipment.expeditor_id <> shipment.organization_id
  AND EXISTS (
      SELECT 1
      FROM cargotech.claim_parties projection
      WHERE projection.id = shipment.organization_id
        AND projection.organization_id = shipment.organization_id
        AND projection.type = 'EXPEDITOR'
        AND projection.deleted_at IS NULL
  );

UPDATE cargotech.claim_claims claim
SET creditor_id = claim.organization_id,
    updated_at = CURRENT_TIMESTAMP
WHERE claim.creditor_id <> claim.organization_id
  AND EXISTS (
      SELECT 1
      FROM cargotech.claim_parties old_expeditor
      WHERE old_expeditor.id = claim.creditor_id
        AND old_expeditor.organization_id = claim.organization_id
        AND old_expeditor.type = 'EXPEDITOR'
  )
  AND EXISTS (
      SELECT 1
      FROM cargotech.claim_parties projection
      WHERE projection.id = claim.organization_id
        AND projection.organization_id = claim.organization_id
        AND projection.type = 'EXPEDITOR'
        AND projection.deleted_at IS NULL
  );

UPDATE cargotech.claim_parties old_expeditor
SET active = false,
    deleted_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE old_expeditor.type = 'EXPEDITOR'
  AND old_expeditor.id <> old_expeditor.organization_id
  AND EXISTS (
      SELECT 1
      FROM cargotech.claim_parties projection
      WHERE projection.id = old_expeditor.organization_id
        AND projection.organization_id = old_expeditor.organization_id
        AND projection.type = 'EXPEDITOR'
        AND projection.deleted_at IS NULL
  );
