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
        'CALCULATION_READ',
        'CALCULATION_GENERATE',
        'CALCULATION_DOWNLOAD',
        'COURT_PACKAGE_READ',
        'COURT_PACKAGE_GENERATE',
        'COURT_PACKAGE_DOWNLOAD',
        'AUDIT_READ',
        'OVERDUE_READ',
        'PAYMENT_READ'
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
        'PAYMENT_READ',
        'PAYMENT_UPDATE',
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