-- cargotech.payment_imports определение

-- Drop table

-- DROP TABLE cargotech.payment_imports;

-- Transactional outbox used by payment-service. It must live in the same
-- database as payments so business changes and their events commit atomically.
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


CREATE TABLE cargotech.payment_imports (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NOT NULL,
	source_system varchar(64) NOT NULL,
	source_file_id uuid NULL,
	status varchar(32) DEFAULT 'CREATED'::character varying NOT NULL,
	total_rows int4 DEFAULT 0 NOT NULL,
	imported_rows int4 DEFAULT 0 NOT NULL,
	failed_rows int4 DEFAULT 0 NOT NULL,
	error_details jsonb NULL,
	started_at timestamptz NULL,
	completed_at timestamptz NULL,
	created_by uuid NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT payment_imports_pkey PRIMARY KEY (id),
	CONSTRAINT payment_imports_source_system_check CHECK (((source_system)::text = ANY ((ARRAY['ONE_C'::character varying, 'BANK_STATEMENT'::character varying, 'MANUAL_EXCEL'::character varying])::text[]))),
	CONSTRAINT payment_imports_status_check CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'PARTIALLY_COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


-- cargotech.payment_checks определение

-- Drop table

-- DROP TABLE cargotech.payment_checks;

CREATE TABLE cargotech.payment_checks (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NOT NULL,
	target_type varchar(32) NOT NULL,
	target_id uuid NOT NULL,
	service_amount numeric(19, 2) NOT NULL,
	paid_amount numeric(19, 2) DEFAULT 0 NOT NULL,
	remaining_amount numeric(19, 2) NOT NULL,
	payment_status varchar(32) NOT NULL,
	"source" varchar(64) NULL,
	matched_payment_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
	checked_by uuid NOT NULL,
	checked_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	"comment" text NULL,
	CONSTRAINT chk_payment_remaining CHECK ((remaining_amount = GREATEST((service_amount - paid_amount), (0)::numeric))),
	CONSTRAINT payment_checks_payment_status_check CHECK (((payment_status)::text = ANY ((ARRAY['NOT_PAID'::character varying, 'PARTIALLY_PAID'::character varying, 'FULLY_PAID'::character varying, 'OVERPAID'::character varying, 'UNKNOWN'::character varying])::text[]))),
	CONSTRAINT payment_checks_pkey PRIMARY KEY (id),
	CONSTRAINT payment_checks_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['SHIPMENT'::character varying, 'CLAIM'::character varying])::text[])))
);
CREATE INDEX idx_payment_checks_org ON cargotech.payment_checks USING btree (organization_id);
CREATE INDEX idx_payment_checks_target ON cargotech.payment_checks USING btree (target_type, target_id, checked_at DESC);


-- cargotech.payment_reconciliation_runs определение

-- Drop table

-- DROP TABLE cargotech.payment_reconciliation_runs;

CREATE TABLE cargotech.payment_reconciliation_runs (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NOT NULL,
	status varchar(32) DEFAULT 'CREATED'::character varying NOT NULL,
	payments_checked int4 DEFAULT 0 NOT NULL,
	matches_created int4 DEFAULT 0 NOT NULL,
	unmatched_count int4 DEFAULT 0 NOT NULL,
	parameters jsonb NULL,
	started_by uuid NOT NULL,
	started_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	completed_at timestamptz NULL,
	error_message text NULL,
	CONSTRAINT payment_reconciliation_runs_pkey PRIMARY KEY (id),
	CONSTRAINT payment_reconciliation_runs_status_check CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


-- cargotech.payment_payments определение

-- Drop table

-- DROP TABLE cargotech.payment_payments;

CREATE TABLE cargotech.payment_payments (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NOT NULL,
	import_id uuid NULL,
	source_system varchar(64) NOT NULL,
	external_payment_id varchar(255) NULL,
	payment_number varchar(128) NULL,
	payment_date date NOT NULL,
	payer_inn varchar(12) NULL,
	payer_name varchar(500) NULL,
	recipient_inn varchar(12) NULL,
	recipient_name varchar(500) NULL,
	amount numeric(19, 2) NOT NULL,
	currency varchar(10) DEFAULT 'RUB'::character varying NOT NULL,
	purpose text NULL,
	status varchar(32) DEFAULT 'IMPORTED'::character varying NOT NULL,
	raw_data jsonb NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT payment_payments_amount_check CHECK ((amount <> (0)::numeric)),
	CONSTRAINT payment_payments_pkey PRIMARY KEY (id),
	CONSTRAINT payment_payments_status_check CHECK (((status)::text = ANY ((ARRAY['IMPORTED'::character varying, 'PARTIALLY_MATCHED'::character varying, 'MATCHED'::character varying, 'REJECTED'::character varying])::text[]))),
	CONSTRAINT payment_payments_import_id_fkey FOREIGN KEY (import_id) REFERENCES cargotech.payment_imports(id)
);
CREATE INDEX idx_payment_date ON cargotech.payment_payments USING btree (payment_date DESC);
CREATE INDEX idx_payment_org ON cargotech.payment_payments USING btree (organization_id);
CREATE INDEX idx_payment_payer_inn ON cargotech.payment_payments USING btree (payer_inn);
CREATE INDEX idx_payment_recipient_inn ON cargotech.payment_payments USING btree (recipient_inn);
CREATE INDEX idx_payment_status ON cargotech.payment_payments USING btree (status);
CREATE UNIQUE INDEX uq_payment_external ON cargotech.payment_payments USING btree (organization_id, source_system, external_payment_id) WHERE (external_payment_id IS NOT NULL);


-- cargotech.payment_matches определение

-- Drop table

-- DROP TABLE cargotech.payment_matches;

CREATE TABLE cargotech.payment_matches (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	payment_id uuid NOT NULL,
	target_type varchar(32) NOT NULL,
	target_id uuid NOT NULL,
	matched_amount numeric(19, 2) NOT NULL,
	match_type varchar(32) NOT NULL,
	confidence numeric(5, 4) NULL,
	active bool DEFAULT true NOT NULL,
	matched_by uuid NULL,
	matched_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	unmatched_by uuid NULL,
	unmatched_at timestamptz NULL,
	unmatch_reason text NULL,
	CONSTRAINT payment_matches_match_type_check CHECK (((match_type)::text = ANY ((ARRAY['AUTOMATIC'::character varying, 'MANUAL'::character varying])::text[]))),
	CONSTRAINT payment_matches_pkey PRIMARY KEY (id),
	CONSTRAINT payment_matches_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['SHIPMENT'::character varying, 'ACT'::character varying, 'INVOICE'::character varying, 'CLAIM'::character varying])::text[]))),
	CONSTRAINT payment_matches_payment_id_fkey FOREIGN KEY (payment_id) REFERENCES cargotech.payment_payments(id) ON DELETE CASCADE
);
CREATE INDEX idx_payment_matches_payment ON cargotech.payment_matches USING btree (payment_id);
CREATE INDEX idx_payment_matches_target ON cargotech.payment_matches USING btree (target_type, target_id);
