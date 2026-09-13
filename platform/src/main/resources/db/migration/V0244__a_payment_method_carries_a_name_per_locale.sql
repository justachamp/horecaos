-- ADR 0038, row 10.6: a tenant's own localized name for a registered payment
-- method.
--
-- payments.payment_methods.display_name (V0042) is one string, and the console
-- shows staff a payment method's name in whichever of the three platform
-- locales (ADR 0035: ru, uz-Latn, en) they work in. Every other per-locale text
-- table in this codebase (ordering.order_reject_reason_texts, V0119;
-- tenant.terms_documents, V0160) is a sibling row per locale rather than a
-- JSONB blob, so a name in an unsupported locale is refused by a CHECK
-- constraint rather than silently accepted. This table follows the same shape,
-- tenant-scoped because the registry itself is (V0042) and platform-curated
-- reference data (order_reject_reason_texts) is not the pattern here.
--
-- display_name on payments.payment_methods is unchanged and stays what it
-- has always been: the fallback shown when a locale has no row here yet, and
-- the value payment_methods.responsibility resolution and every existing
-- reader (JdbcSettlementStore, CheckoutSettlementPlanner) already use. Nothing
-- reads this table until the registry service introduced alongside it starts
-- writing.
CREATE TABLE payments.payment_method_translations (
    tenant_id uuid NOT NULL,
    payment_method_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    display_name varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (payment_method_id, locale),
    CONSTRAINT ck_payment_method_translation_locale CHECK (locale IN ('ru', 'uz-Latn', 'en')),
    CONSTRAINT ck_payment_method_translation_present CHECK (length(btrim(display_name)) > 0),
    -- (tenant_id, payment_method_id) rather than payment_method_id alone: the
    -- same composite-key discipline V0020's own header explains for
    -- sales_channel_locations, so a translation row can never be attached to
    -- another tenant's method even if a caller passed the wrong tenant_id.
    CONSTRAINT fk_payment_method_translation_method FOREIGN KEY (tenant_id, payment_method_id)
        REFERENCES payments.payment_methods (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_payment_method_translations_tenant
    ON payments.payment_method_translations (tenant_id, payment_method_id);

COMMENT ON TABLE payments.payment_method_translations IS
    'ADR 0038, row 10.6: a tenant''s own name for a registered payment method, per platform locale. Absent for a locale falls back to payment_methods.display_name.';

GRANT SELECT, INSERT, UPDATE, DELETE ON payments.payment_method_translations TO horecaos_application;
