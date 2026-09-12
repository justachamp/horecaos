-- 10.10b (operations-gap-map): the tenant's own trading calendar. ADR 0090
-- seeded the platform's per-country public holidays (tenant.public_holidays)
-- and ADR 0043 built the business-day boundary a tenant cannot yet set
-- (reporting.business_day_policies, BusinessDayService.setBoundary — no
-- production caller until this wave). Neither answers what settings.md 10.10
-- promises next to them: a tenant closes on days the platform's per-country
-- list does not know about (a lease renewal, a one-off closure, a movable
-- date its own management announces before the platform catalogues it), and
-- a tenant's weekend is its own to declare, not inferred.
--
-- Deliberately its own table in the tenant schema rather than a column on
-- reporting.business_day_policies: the boundary dates every reporting fact
-- and is versioned for that reason (ADR 0043's whole point); a tenant's own
-- holiday list and weekend declaration are profile facts nothing recuts
-- against today. Folding them into the reporting table would borrow
-- versioning semantics that do not apply to them yet.
CREATE TABLE tenant.business_calendars (
    tenant_id uuid PRIMARY KEY,

    -- ISO-8601 day-of-week numbers (1=Monday .. 7=Sunday), the days this
    -- tenant does not trade. Empty by default: a restaurant trades every day
    -- until it says otherwise, the same "assume nothing until told" default
    -- BusinessDayBoundary.midnight() uses for the boundary itself.
    weekend_days smallint[] NOT NULL DEFAULT '{}',

    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_business_calendars_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_business_calendars_weekend_days CHECK (
        weekend_days <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::smallint[]
    )
);

COMMENT ON TABLE tenant.business_calendars IS
    '10.10b. One row per tenant once it declares a weekend. Absence means "trades every day", not "unset" — a tenant that never opens this screen keeps today''s behaviour exactly.';
COMMENT ON COLUMN tenant.business_calendars.weekend_days IS
    'ISO-8601 day-of-week numbers this tenant does not trade. Declarative today: no report yet varies its computation on this column, the same honesty settings.md''s SLA-bucket card asks for at line 1105.';

GRANT SELECT, INSERT, UPDATE ON tenant.business_calendars TO horecaos_application;

-- The tenant's own holidays, additional to (never a replacement for) the
-- platform's per-country tenant.public_holidays. Same recurring-or-dated
-- shape as that table, because a movable Islamic date entered here is
-- announced the same way: once, for the year it falls in.
CREATE TABLE tenant.business_calendar_holidays (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    name varchar(200) NOT NULL,
    month smallint,
    day smallint,
    holiday_date date,

    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_business_calendar_holidays_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_business_calendar_holiday_shape CHECK (
        (holiday_date IS NULL AND month BETWEEN 1 AND 12 AND day BETWEEN 1 AND 31)
        OR (holiday_date IS NOT NULL AND month IS NULL AND day IS NULL)
    ),
    CONSTRAINT ck_business_calendar_holidays_name CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX ux_business_calendar_holiday_recurring
    ON tenant.business_calendar_holidays (tenant_id, month, day) WHERE holiday_date IS NULL;
CREATE UNIQUE INDEX ux_business_calendar_holiday_dated
    ON tenant.business_calendar_holidays (tenant_id, holiday_date) WHERE holiday_date IS NOT NULL;

COMMENT ON TABLE tenant.business_calendar_holidays IS
    '10.10b. A tenant''s own closures, in addition to tenant.public_holidays. settings.md notes a holiday here should offer to create tenant.service_schedule_exceptions rows, never create them silently — not built by this migration, left as a follow-up so a first cut does not surprise a tenant with schedule changes it did not ask for.';

GRANT SELECT, INSERT, DELETE ON tenant.business_calendar_holidays TO horecaos_application;
