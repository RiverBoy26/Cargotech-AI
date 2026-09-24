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
	kpp varchar(9) NULL,
	ogrn varchar(15) NULL,
	legal_address text NULL,
	postal_address text NULL,
	email varchar(320) NULL,
	phone varchar(64) NULL,
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
	first_name varchar(100) NOT NULL,
	last_name varchar(100) NOT NULL,
	middle_name varchar(100) NULL,
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


-- Standard roles, permissions and their assignments.
INSERT INTO cargotech.auth_roles (code, name, description)
VALUES
    (
        'ACCOUNTANT',
        'Бухгалтер',
        'Работа с просрочками и оплатами: просмотр, подтверждение неуплаты и отметка оплаты'
    ),
    (
        'LAWYER',
        'Юрист',
        'Работа с претензиями, расчётами, судебными пакетами и аудитом'
    ),
    (
        'EXPEDITOR_ADMIN',
        'Администратор экспедитора',
        'Администрирование пользователей и просмотр данных в рамках своей организации'
    ),
    (
        'SUPER_ADMIN',
        'Суперадминистратор',
        'Глобальное администрирование системы и назначение администраторов экспедиторов'
    )
ON CONFLICT (code) DO UPDATE
SET
    name = EXCLUDED.name,
    description = EXCLUDED.description;

INSERT INTO cargotech.auth_permissions (code, name, description)
VALUES
    -- Претензии
    ('CLAIM_CREATE', 'Создание претензий', 'Создание новой претензии'),
    ('CLAIM_READ', 'Просмотр претензий', 'Просмотр списка и карточки претензии'),
    ('CLAIM_UPDATE', 'Изменение претензий', 'Редактирование претензии и её черновика'),
    ('CLAIM_DELETE', 'Удаление претензий', 'Удаление либо административная архивация претензии'),

    -- Расчёты
    ('CALCULATION_READ', 'Просмотр расчётов', 'Просмотр расчёта долга и неустойки'),
    ('CALCULATION_GENERATE', 'Формирование расчётов', 'Создание или пересчёт расчёта'),
    ('CALCULATION_DOWNLOAD', 'Скачивание расчётов', 'Скачивание расчёта в XLSX или PDF'),

    -- Судебный пакет
    ('COURT_PACKAGE_READ', 'Просмотр судебного пакета', 'Просмотр состава и готовности судебного пакета'),
    ('COURT_PACKAGE_GENERATE', 'Формирование судебного пакета', 'Формирование или пересборка судебного пакета'),
    ('COURT_PACKAGE_DOWNLOAD', 'Скачивание судебного пакета', 'Скачивание ZIP судебного пакета'),

    -- Аудит
    ('AUDIT_READ', 'Просмотр аудита', 'Просмотр истории изменений и действий'),

    -- Просрочки
    ('OVERDUE_CREATE', 'Создание просрочек', 'Создание записи о просрочке'),
    ('OVERDUE_READ', 'Просмотр просрочек', 'Просмотр списка и карточки просрочки'),
    ('OVERDUE_UPDATE', 'Изменение просрочек', 'Изменение состояния просрочки'),
    ('OVERDUE_DELETE', 'Удаление просрочек', 'Удаление либо административная архивация просрочки'),
    (
        'OVERDUE_CONFIRM_NON_PAYMENT',
        'Подтверждение неуплаты',
        'Подтверждение бухгалтером отсутствия оплаты'
    ),
    ('OVERDUE_MARK_PAID', 'Отметка оплаты просрочки', 'Отметка просрочки как оплаченной'),

    -- Платежи
    ('PAYMENT_CREATE', 'Создание платежей', 'Создание или ручная регистрация платежа'),
    ('PAYMENT_READ', 'Просмотр платежей', 'Просмотр платежей и результатов сверки'),
    ('PAYMENT_UPDATE', 'Изменение платежей', 'Сопоставление, отмена сопоставления и корректировка платежа'),
    ('PAYMENT_DELETE', 'Удаление платежей', 'Удаление либо административная архивация платежа'),
    ('PAYMENT_IMPORT', 'Импорт платежей', 'Импорт платежей из 1С или банковской выписки'),
    ('PAYMENT_RECONCILE', 'Сверка платежей', 'Запуск и обработка сверки платежей'),
    ('PAYMENT_MARK_PAID', 'Отметка оплаты претензии', 'Подтверждение полной оплаты по претензии'),

    -- Пользователи
    ('USER_READ', 'Просмотр пользователей', 'Просмотр пользователей'),
    ('USER_CREATE', 'Создание пользователей', 'Создание пользователей'),
    ('USER_UPDATE', 'Изменение пользователей', 'Изменение пользователей и их состояния'),
    ('USER_ROLE_ASSIGN', 'Назначение ролей', 'Назначение разрешённых ролей пользователям'),
    (
        'EXPEDITOR_ADMIN_ASSIGN',
        'Назначение администратора экспедитора',
        'Назначение роли EXPEDITOR_ADMIN конкретной организации'
    )
ON CONFLICT (code) DO UPDATE
SET
    name = EXCLUDED.name,
    description = EXCLUDED.description;

DELETE FROM cargotech.auth_role_permissions arp
USING cargotech.auth_roles r
WHERE arp.role_id = r.id
  AND r.code IN ('ACCOUNTANT', 'LAWYER', 'EXPEDITOR_ADMIN', 'SUPER_ADMIN');

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p
    ON p.code IN (
        'CLAIM_CREATE',
        'CLAIM_READ',
        'CLAIM_UPDATE',
        'CLAIM_DELETE',
        'CALCULATION_READ',
        'CALCULATION_GENERATE',
        'CALCULATION_DOWNLOAD',
        'COURT_PACKAGE_READ',
        'COURT_PACKAGE_GENERATE',
        'COURT_PACKAGE_DOWNLOAD',
        'AUDIT_READ',
        'OVERDUE_READ',
        'PAYMENT_READ',
        'USER_READ'
    )
WHERE r.code = 'LAWYER'
ON CONFLICT DO NOTHING;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p
    ON p.code IN (
        'CLAIM_READ',
        'CALCULATION_READ',
        'CALCULATION_DOWNLOAD',
        'COURT_PACKAGE_READ',
        'COURT_PACKAGE_DOWNLOAD',
        'AUDIT_READ',
        'OVERDUE_READ',
        'OVERDUE_UPDATE',
        'OVERDUE_CONFIRM_NON_PAYMENT',
        'OVERDUE_MARK_PAID',
        'PAYMENT_CREATE',
        'PAYMENT_READ',
        'PAYMENT_UPDATE',
        'PAYMENT_DELETE',
        'PAYMENT_IMPORT',
        'PAYMENT_RECONCILE',
        'PAYMENT_MARK_PAID'
    )
WHERE r.code = 'ACCOUNTANT'
ON CONFLICT DO NOTHING;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p
    ON p.code IN (
        'CLAIM_READ',
        'CALCULATION_READ',
        'COURT_PACKAGE_READ',
        'AUDIT_READ',
        'OVERDUE_READ',
        'PAYMENT_READ',
        'USER_READ',
        'USER_CREATE',
        'USER_UPDATE',
        'USER_ROLE_ASSIGN'
    )
WHERE r.code = 'EXPEDITOR_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p
    ON p.code IN (
        'CLAIM_CREATE',
        'CLAIM_READ',
        'CLAIM_UPDATE',
        'CLAIM_DELETE',
        'CALCULATION_READ',
        'CALCULATION_DOWNLOAD',
        'COURT_PACKAGE_READ',
        'COURT_PACKAGE_DOWNLOAD',
        'AUDIT_READ',
        'OVERDUE_CREATE',
        'OVERDUE_READ',
        'OVERDUE_UPDATE',
        'OVERDUE_DELETE',
        'OVERDUE_CONFIRM_NON_PAYMENT',
        'OVERDUE_MARK_PAID',
        'PAYMENT_CREATE',
        'PAYMENT_READ',
        'PAYMENT_UPDATE',
        'PAYMENT_DELETE',
        'PAYMENT_IMPORT',
        'PAYMENT_RECONCILE',
        'PAYMENT_MARK_PAID',
        'USER_READ',
        'USER_CREATE',
        'USER_UPDATE',
        'USER_ROLE_ASSIGN',
        'EXPEDITOR_ADMIN_ASSIGN'
    )
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

-- Organization-expeditor management.
INSERT INTO cargotech.auth_permissions (code, name, description)
VALUES
    (
        'ORGANIZATION_READ',
        'Просмотр организаций',
        'Просмотр организаций-экспедиторов'
    ),
    (
        'ORGANIZATION_CREATE',
        'Создание организаций',
        'Создание организаций-экспедиторов и их юридических реквизитов'
    ),
    (
        'ORGANIZATION_UPDATE',
        'Изменение организаций',
        'Изменение и синхронизация организаций-экспедиторов'
    )
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name, description = EXCLUDED.description;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p ON p.code LIKE 'ORGANIZATION_%'
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

-- Document module permissions. Kept as a separate idempotent block so this
-- script can be safely applied to an existing auth database.
INSERT INTO cargotech.auth_permissions (code, name, description)
VALUES
    ('DOCUMENT_READ', 'Просмотр документов', 'Просмотр карточек и истории документов'),
    ('DOCUMENT_DOWNLOAD', 'Скачивание документов', 'Скачивание DOCX и PDF файлов'),
    ('DOCUMENT_UPLOAD', 'Загрузка документов', 'Загрузка файлов и приложений'),
    ('DOCUMENT_GENERATE', 'Формирование документов', 'Формирование DOCX и PDF претензий'),
    ('DOCUMENT_SEND', 'Отправка документов', 'Отправка готовых документов клиенту по email'),
    ('DOCUMENT_DELETE', 'Удаление документов', 'Архивация или удаление документов'),
    ('DOCUMENT_LINK', 'Связывание документов', 'Связывание документов с претензиями и договорами'),
    ('DOCUMENT_TEMPLATE_READ', 'Просмотр шаблонов', 'Просмотр и предварительный просмотр шаблонов'),
    ('DOCUMENT_TEMPLATE_CREATE', 'Создание шаблонов', 'Создание шаблонов претензий'),
    ('DOCUMENT_TEMPLATE_UPDATE', 'Изменение шаблонов', 'Создание и активация версий шаблонов')
ON CONFLICT (code) DO UPDATE
SET
    name = EXCLUDED.name,
    description = EXCLUDED.description;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p ON p.code IN (
    'DOCUMENT_READ',
    'DOCUMENT_DOWNLOAD',
    'DOCUMENT_GENERATE',
    'DOCUMENT_SEND',
    'DOCUMENT_LINK',
    'DOCUMENT_TEMPLATE_READ'
)
WHERE r.code = 'LAWYER'
ON CONFLICT DO NOTHING;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p ON p.code IN (
    'DOCUMENT_READ',
    'DOCUMENT_DOWNLOAD',
    'DOCUMENT_UPLOAD',
    'DOCUMENT_LINK',
    'DOCUMENT_TEMPLATE_READ'
)
WHERE r.code = 'EXPEDITOR_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p ON p.code LIKE 'DOCUMENT_%'
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

-- Справочники претензионной работы. Эти разрешения отделены от CLAIM_CREATE,
-- чтобы администратор экспедитора мог вести мастер-данные, не создавая претензии.
INSERT INTO cargotech.auth_permissions (code, name, description)
VALUES
    ('PARTY_READ', 'Просмотр контрагентов', 'Просмотр кредиторов и должников'),
    ('PARTY_CREATE', 'Создание контрагентов', 'Создание кредиторов и должников'),
    ('PARTY_UPDATE', 'Изменение контрагентов', 'Изменение реквизитов контрагентов'),
    ('PARTY_DELETE', 'Удаление контрагентов', 'Архивация контрагентов'),
    ('CONTRACT_READ', 'Просмотр договоров', 'Просмотр договоров организации'),
    ('CONTRACT_CREATE', 'Создание договоров', 'Создание договоров организации'),
    ('CONTRACT_UPDATE', 'Изменение договоров', 'Изменение договоров организации'),
    ('CONTRACT_DELETE', 'Удаление договоров', 'Архивация договоров'),
    ('SHIPMENT_READ', 'Просмотр перевозок', 'Просмотр перевозок организации'),
    ('SHIPMENT_CREATE', 'Создание перевозок', 'Создание перевозок организации'),
    ('SHIPMENT_UPDATE', 'Изменение перевозок', 'Изменение перевозок организации'),
    ('SHIPMENT_IMPORT', 'Импорт перевозок', 'Массовый импорт перевозок')
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name, description = EXCLUDED.description;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p ON p.code IN (
    'PARTY_READ', 'CONTRACT_READ', 'SHIPMENT_READ'
)
WHERE r.code IN ('LAWYER', 'ACCOUNTANT')
ON CONFLICT DO NOTHING;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p ON p.code IN (
    'PARTY_READ', 'PARTY_CREATE', 'PARTY_UPDATE', 'PARTY_DELETE',
    'CONTRACT_READ', 'CONTRACT_CREATE', 'CONTRACT_UPDATE', 'CONTRACT_DELETE',
    'SHIPMENT_READ', 'SHIPMENT_CREATE', 'SHIPMENT_UPDATE', 'SHIPMENT_IMPORT'
)
WHERE r.code = 'EXPEDITOR_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO cargotech.auth_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM cargotech.auth_roles r
JOIN cargotech.auth_permissions p ON (
    p.code LIKE 'PARTY_%'
    OR p.code LIKE 'CONTRACT_%'
    OR p.code LIKE 'SHIPMENT_%'
)
WHERE r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;
