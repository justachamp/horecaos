-- ADR 0036 / ADR 0038: point tenant.channel_payment_methods.payment_method_code
-- at payments.payment_methods, the tenant-scoped payment-method registry ADR
-- 0038 owns.
--
-- V0020's own comment on this column explained why it was left unconstrained:
-- "payment_method_code is a code column and not yet a foreign key: ADR 0038
-- owns the tenant-scoped payments.payment_methods registry ... and that table
-- does not exist yet." It landed in V0042. Both ADR 0036's checklist and ADR
-- 0038's decision record name pointing this column at that table as the one
-- thing still outstanding: "a channel row enables or disables a method the
-- tenant has already registered and can never invent one."
--
-- What the foreign key needs to reference: platform/AGENTS.md is explicit that
-- a foreign key must reference a unique constraint on exactly its own columns
-- -- a three-column unique does not satisfy a two-column reference, which is
-- why V0046 had to add tenant.uq_variant_tenant_identity for an identical
-- shape. Checked before writing this one: payments.payment_methods already
-- carries `uq_payment_method_code UNIQUE (tenant_id, code)` from V0042 --
-- exactly the two columns this reference needs, and nothing wider -- so no new
-- unique constraint is required here. (It also carries `uq_payment_method_identity
-- UNIQUE (tenant_id, id)` and the three-column `uq_payment_method_balance_flag
-- UNIQUE (tenant_id, id, settles_from_balance)`, neither of which would have
-- satisfied a (tenant_id, code) reference.)
--
-- Existing rows must not be orphaned by the ALTER TABLE below. ADR 0055 means
-- there is no production tenant yet, but this migration still runs against
-- whatever a shared dev or test database already holds, and every writer this
-- platform's own code has for tenant.channel_payment_methods -- the local
-- fixture, the onboarding step tests, and the storefront payment-options
-- tests -- only ever names one of five provisional codes: the four PaymentMethod
-- enum constants (CASH, CLICK, PAYME, TELEGRAM) and MARKETPLACE, ADR 0040's
-- aggregator-collected tender. CheckoutSettlementPlanner.responsibilityOf
-- already assigns each of the five a fiscal responsibility the moment a
-- checkout tenders against it; the backfill below assigns the identical
-- responsibility so a row this migration creates and a row the application
-- would have lazily registered are indistinguishable. A code outside this set
-- is not one this platform's code ever wrote, and is deliberately left for the
-- ALTER TABLE to refuse loudly rather than for this migration to guess a
-- responsibility nobody decided.
INSERT INTO payments.payment_methods (
    id, tenant_id, code, display_name, responsibility, settles_from_balance,
    status, version, created_at, updated_at)
SELECT gen_random_uuid(), missing.tenant_id, missing.code, missing.code,
    missing.responsibility, false, 'ACTIVE', 1, now(), now()
FROM (
    SELECT DISTINCT cpm.tenant_id, cpm.payment_method_code AS code,
        CASE cpm.payment_method_code
            WHEN 'CASH' THEN 'OPERATOR'
            WHEN 'CLICK' THEN 'PARTNER'
            WHEN 'PAYME' THEN 'PARTNER'
            WHEN 'TELEGRAM' THEN 'PARTNER'
            WHEN 'MARKETPLACE' THEN 'MARKETPLACE'
        END AS responsibility
    FROM tenant.channel_payment_methods cpm
    WHERE cpm.payment_method_code IN ('CASH', 'CLICK', 'PAYME', 'TELEGRAM', 'MARKETPLACE')
) missing
WHERE NOT EXISTS (
    SELECT 1 FROM payments.payment_methods pm
    WHERE pm.tenant_id = missing.tenant_id AND pm.code = missing.code
);

ALTER TABLE tenant.channel_payment_methods
    ADD CONSTRAINT fk_channel_payment_method_code FOREIGN KEY (tenant_id, payment_method_code)
    REFERENCES payments.payment_methods (tenant_id, code);
