-- cargotech.outbox_events определение

-- Drop table

-- DROP TABLE cargotech.outbox_events;

CREATE TABLE cargotech.outbox_events (
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
CREATE INDEX idx_outbox_aggregate ON cargotech.outbox_events USING btree (aggregate_id);
CREATE INDEX idx_outbox_pending ON cargotech.outbox_events USING btree (status, created_at) WHERE ((status)::text = ANY ((ARRAY['NEW'::character varying, 'FAILED'::character varying])::text[]));


-- cargotech.auth_permissions определение

-- Drop table

-- DROP TABLE cargotech.auth_permissions;

CREATE TABLE cargotech.auth_permissions (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	code varchar(128) NOT NULL,
	"name" varchar(255) NOT NULL,
	description text NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT auth_permissions_code_key UNIQUE (code),
	CONSTRAINT auth_permissions_pkey PRIMARY KEY (id)
);


-- cargotech.auth_roles определение

-- Drop table

-- DROP TABLE cargotech.auth_roles;

CREATE TABLE cargotech.auth_roles (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	code varchar(64) NOT NULL,
	"name" varchar(255) NOT NULL,
	description text NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT auth_roles_code_key UNIQUE (code),
	CONSTRAINT auth_roles_pkey PRIMARY KEY (id)
);


-- cargotech.auth_organizations определение

-- Drop table

-- DROP TABLE cargotech.auth_organizations;

CREATE TABLE cargotech.auth_organizations (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	"name" varchar(255) NOT NULL,
	inn varchar(12) NULL,
	status varchar(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT auth_organizations_pkey PRIMARY KEY (id),
	CONSTRAINT auth_organizations_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'BLOCKED'::character varying, 'ARCHIVED'::character varying])::text[])))
);
CREATE UNIQUE INDEX uq_auth_organizations_inn ON cargotech.auth_organizations USING btree (inn) WHERE (inn IS NOT NULL);

-- Table Triggers

CREATE TRIGGER trg_auth_organizations_touch BEFORE
UPDATE
    ON
    cargotech.auth_organizations FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.auth_role_permissions определение

-- Drop table

-- DROP TABLE cargotech.auth_role_permissions;

CREATE TABLE cargotech.auth_role_permissions (
	role_id uuid NOT NULL,
	permission_id uuid NOT NULL,
	CONSTRAINT auth_role_permissions_pkey PRIMARY KEY (role_id, permission_id),
	CONSTRAINT auth_role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES cargotech.auth_permissions(id) ON DELETE CASCADE,
	CONSTRAINT auth_role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES cargotech.auth_roles(id) ON DELETE CASCADE
);


-- cargotech.auth_users определение

-- Drop table

-- DROP TABLE cargotech.auth_users;

CREATE TABLE cargotech.auth_users (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	organization_id uuid NULL,
	full_name varchar(255) NOT NULL,
	email varchar(320) NOT NULL,
	password_hash varchar(255) NOT NULL,
	active bool DEFAULT true NOT NULL,
	blocked_at timestamptz NULL,
	last_login_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	"version" int8 DEFAULT 0 NOT NULL,
	CONSTRAINT auth_users_pkey PRIMARY KEY (id),
	CONSTRAINT fk_auth_users_organization FOREIGN KEY (organization_id) REFERENCES cargotech.auth_organizations(id)
);
CREATE INDEX idx_auth_users_organization ON cargotech.auth_users USING btree (organization_id);
CREATE UNIQUE INDEX uq_auth_users_email_lower ON cargotech.auth_users USING btree (lower((email)::text));

-- Table Triggers

CREATE TRIGGER trg_auth_users_touch BEFORE
UPDATE
    ON
    cargotech.auth_users FOR EACH ROW EXECUTE FUNCTION cargotech.touch_updated_at();


-- cargotech.auth_user_roles определение

-- Drop table

-- DROP TABLE cargotech.auth_user_roles;

CREATE TABLE cargotech.auth_user_roles (
	user_id uuid NOT NULL,
	role_id uuid NOT NULL,
	assigned_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	assigned_by uuid NULL,
	CONSTRAINT auth_user_roles_pkey PRIMARY KEY (user_id, role_id),
	CONSTRAINT auth_user_roles_assigned_by_fkey FOREIGN KEY (assigned_by) REFERENCES cargotech.auth_users(id),
	CONSTRAINT auth_user_roles_role_id_fkey FOREIGN KEY (role_id) REFERENCES cargotech.auth_roles(id) ON DELETE CASCADE,
	CONSTRAINT auth_user_roles_user_id_fkey FOREIGN KEY (user_id) REFERENCES cargotech.auth_users(id) ON DELETE CASCADE
);


-- cargotech.auth_refresh_tokens определение

-- Drop table

-- DROP TABLE cargotech.auth_refresh_tokens;

CREATE TABLE cargotech.auth_refresh_tokens (
	id uuid DEFAULT gen_random_uuid() NOT NULL,
	user_id uuid NOT NULL,
	token_hash varchar(255) NOT NULL,
	expires_at timestamptz NOT NULL,
	revoked_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	created_ip varchar(64) NULL,
	user_agent text NULL,
	CONSTRAINT auth_refresh_tokens_pkey PRIMARY KEY (id),
	CONSTRAINT auth_refresh_tokens_token_hash_key UNIQUE (token_hash),
	CONSTRAINT auth_refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES cargotech.auth_users(id) ON DELETE CASCADE
);
CREATE INDEX idx_auth_refresh_tokens_user ON cargotech.auth_refresh_tokens USING btree (user_id);


-- cargotech.auth_user_effective_permissions исходный текст

CREATE OR REPLACE VIEW cargotech.auth_user_effective_permissions
AS SELECT DISTINCT u.id AS user_id,
    u.organization_id,
    r.code AS role_code,
    p.code AS permission_code
   FROM cargotech.auth_users u
     JOIN cargotech.auth_user_roles ur ON ur.user_id = u.id
     JOIN cargotech.auth_roles r ON r.id = ur.role_id
     JOIN cargotech.auth_role_permissions rp ON rp.role_id = r.id
     JOIN cargotech.auth_permissions p ON p.id = rp.permission_id
  WHERE u.active = true;