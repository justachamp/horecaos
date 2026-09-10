-- ADR 0090: a tenant records the country it trades in and the kind of
-- business it is, a change of country needs a second signature, and the
-- platform keeps the public holidays of the countries it serves.
--
-- Every tenant so far trades in Uzbekistan and is a restaurant, which is what
-- the defaults record for the rows that exist. The country is a market, not a
-- hosting location: all data is hosted in one place (ADR 0073), and the
-- control plane says so rather than inventing a region per tenant.

ALTER TABLE tenant.tenants
    ADD COLUMN country_code char(2) NOT NULL DEFAULT 'UZ',
    ADD COLUMN business_type varchar(32) NOT NULL DEFAULT 'RESTAURANT';

ALTER TABLE tenant.tenants
    ADD CONSTRAINT ck_tenant_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT ck_tenant_business_type CHECK (
        business_type IN ('RESTAURANT', 'CAFE', 'FAST_FOOD', 'BAKERY', 'DARK_KITCHEN',
                          'CATERING', 'COURIER_SERVICE', 'PHARMACY', 'FLORIST')
    );

COMMENT ON COLUMN tenant.tenants.country_code IS
    'ADR 0090. The market the tenant trades in (ISO 3166-1 alpha-2). Changing it needs a second signature; it does not move the tenant''s data, which is hosted in one place for every tenant.';
COMMENT ON COLUMN tenant.tenants.business_type IS
    'ADR 0090. What kind of business the tenant is. Recorded and shown; onboarding uses the default template for every type today.';

-- A change of country is a residency decision, and ADR 0027's maker-checker
-- is how the platform asks for a second signature. The policy is seeded at
-- platform scope so the action is governed from the first day rather than
-- waiting on somebody to author it.
INSERT INTO audit.approval_policies (
    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
    valid_from, version, approved_by)
VALUES (
    '0192d1a0-0000-7000-8000-000000000203', NULL, 'tenant.country.change', 'PLATFORM',
    '{"description": "Every change of the country a tenant trades in"}'::jsonb, 'tenant.write',
    '2026-09-11T00:00:00Z', 1, 'migration V0203');

CREATE TABLE tenant.public_holidays (
    id uuid PRIMARY KEY,
    country_code char(2) NOT NULL,
    name varchar(200) NOT NULL,
    -- Either a date every year, or one dated occurrence for holidays that
    -- move with the lunar calendar and are announced each year.
    month smallint,
    day smallint,
    holiday_date date,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_public_holiday_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_public_holiday_shape CHECK (
        (holiday_date IS NULL AND month BETWEEN 1 AND 12 AND day BETWEEN 1 AND 31)
        OR (holiday_date IS NOT NULL AND month IS NULL AND day IS NULL)
    )
);

CREATE UNIQUE INDEX ux_public_holiday_recurring ON tenant.public_holidays (country_code, month, day)
    WHERE holiday_date IS NULL;
CREATE UNIQUE INDEX ux_public_holiday_dated ON tenant.public_holidays (country_code, holiday_date)
    WHERE holiday_date IS NOT NULL;

COMMENT ON TABLE tenant.public_holidays IS
    'ADR 0090. Public holidays per country the platform serves: fixed dates recur every year, movable ones are entered for the year they fall in. Platform reference data, not tenant data.';

-- Uzbekistan's fixed-date public holidays. Ramazon Hayit and Qurbon Hayit
-- move with the lunar calendar and are entered each year once announced.
INSERT INTO tenant.public_holidays (id, country_code, name, month, day, created_by) VALUES
    ('0192d1a0-0000-7000-8000-000000000301', 'UZ', 'New Year', 1, 1, 'migration V0203'),
    ('0192d1a0-0000-7000-8000-000000000302', 'UZ', 'International Women''s Day', 3, 8, 'migration V0203'),
    ('0192d1a0-0000-7000-8000-000000000303', 'UZ', 'Navruz', 3, 21, 'migration V0203'),
    ('0192d1a0-0000-7000-8000-000000000304', 'UZ', 'Day of Remembrance and Honour', 5, 9, 'migration V0203'),
    ('0192d1a0-0000-7000-8000-000000000305', 'UZ', 'Independence Day', 9, 1, 'migration V0203'),
    ('0192d1a0-0000-7000-8000-000000000306', 'UZ', 'Teachers'' Day', 10, 1, 'migration V0203'),
    ('0192d1a0-0000-7000-8000-000000000307', 'UZ', 'Constitution Day', 12, 8, 'migration V0203');

GRANT SELECT, INSERT, DELETE ON tenant.public_holidays TO horecaos_application;
