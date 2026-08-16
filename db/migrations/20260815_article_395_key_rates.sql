-- Article 395 GK RF: deterministic Bank of Russia key-rate history.
-- Source of truth: https://www.cbr.ru/hd_base/KeyRate/
-- The application must fail closed for periods earlier than the first row.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

CREATE TABLE IF NOT EXISTS cargotech.article_395_rates (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    effective_from date NOT NULL UNIQUE,
    rate numeric(8, 4) NOT NULL CHECK (rate > 0),
    source varchar(500) NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL
);

INSERT INTO cargotech.article_395_rates (effective_from, rate, source)
VALUES
    ('2023-01-01', 7.5000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2023-07-24', 8.5000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2023-08-15', 12.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2023-09-18', 13.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2023-10-30', 15.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2023-12-18', 16.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2024-07-29', 18.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2024-09-16', 19.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2024-10-28', 21.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2025-06-09', 20.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2025-07-28', 18.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2025-09-15', 17.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2025-10-27', 16.5000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2025-12-22', 16.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2026-02-16', 15.5000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2026-03-23', 15.0000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2026-04-27', 14.5000, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2026-06-22', 14.2500, 'https://www.cbr.ru/hd_base/KeyRate/'),
    ('2026-07-27', 14.0000, 'https://www.cbr.ru/hd_base/KeyRate/')
ON CONFLICT (effective_from) DO UPDATE
SET rate = EXCLUDED.rate,
    source = EXCLUDED.source;

COMMENT ON TABLE cargotech.article_395_rates IS
    'Bank of Russia key-rate change points used for deterministic Article 395 GK RF calculations';

COMMIT;
