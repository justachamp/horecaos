-- ADR 0090, as decided on 2026-09-11: a tenant's business type pre-selects its
-- onboarding template, and the operator can still choose another.
--
-- A template version names the business types it suits. Starting a run
-- without naming a template takes the newest ACTIVE version that suits the
-- tenant's type, and the platform's 'default' template when none does. The
-- default names no type: it is what every type falls back to, not a template
-- any one of them chose.
--
-- The list is checked against the same nine values V0203 allows on
-- tenant.tenants.business_type, so a template cannot claim a type no tenant
-- can have.

ALTER TABLE tenant.onboarding_templates
    ADD COLUMN business_types text[] NOT NULL DEFAULT '{}';

ALTER TABLE tenant.onboarding_templates
    ADD CONSTRAINT ck_onboarding_template_business_types CHECK (
        business_types <@ ARRAY['RESTAURANT', 'CAFE', 'FAST_FOOD', 'BAKERY', 'DARK_KITCHEN',
                                'CATERING', 'COURIER_SERVICE', 'PHARMACY', 'FLORIST']::text[]
    );

COMMENT ON COLUMN tenant.onboarding_templates.business_types IS
    'ADR 0090. The business types this template version suits; a run started without a template takes the newest ACTIVE one suiting the tenant''s type, else the default.';
