-- ADR 0093: a plan version sells more than a monthly price. It can offer a
-- discount for committing to a longer term, a trial length, and an activation
-- deposit, all fixed with the version once a second person activates it.
--
-- A subscription records the term it was sold on, and a statement can carry
-- the deposit as its own line.

CREATE TABLE commercial.plan_version_terms (
    plan_version_id uuid PRIMARY KEY,
    trial_days integer,
    activation_deposit_minor bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_plan_terms_version FOREIGN KEY (plan_version_id)
        REFERENCES commercial.plan_versions (id),
    CONSTRAINT ck_plan_terms_trial CHECK (trial_days IS NULL OR trial_days BETWEEN 1 AND 90),
    CONSTRAINT ck_plan_terms_deposit CHECK (activation_deposit_minor >= 0)
);

CREATE TABLE commercial.plan_term_discounts (
    plan_version_id uuid NOT NULL,
    term_months integer NOT NULL,
    discount_basis_points integer NOT NULL,
    CONSTRAINT pk_plan_term_discount PRIMARY KEY (plan_version_id, term_months),
    CONSTRAINT fk_plan_term_discount_version FOREIGN KEY (plan_version_id)
        REFERENCES commercial.plan_versions (id),
    CONSTRAINT ck_plan_term_months CHECK (term_months IN (3, 6, 12, 24)),
    CONSTRAINT ck_plan_term_discount CHECK (discount_basis_points BETWEEN 1 AND 5000)
);

COMMENT ON TABLE commercial.plan_version_terms IS
    'ADR 0093. A plan version''s trial length and activation deposit. Immutable once the version is activated.';
COMMENT ON TABLE commercial.plan_term_discounts IS
    'ADR 0093. The discount, in basis points of the monthly price, for committing to a longer term. Immutable once the version is activated.';

-- The same rule as the version and its entitlements (V0033): once a second
-- person has approved the terms, nothing about them changes.
CREATE OR REPLACE FUNCTION commercial.reject_activated_plan_terms_change() RETURNS trigger AS $$
DECLARE
    version_id uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        version_id := OLD.plan_version_id;
    ELSE
        version_id := NEW.plan_version_id;
    END IF;
    IF EXISTS (SELECT 1 FROM commercial.plan_versions WHERE id = version_id AND activated_at IS NOT NULL) THEN
        RAISE EXCEPTION 'The terms of an activated plan version are immutable; issue a new version instead (ADR 0093)';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_plan_version_terms_immutable_after_activation
    BEFORE INSERT OR UPDATE OR DELETE ON commercial.plan_version_terms
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_activated_plan_terms_change();

CREATE TRIGGER trg_plan_term_discounts_immutable_after_activation
    BEFORE INSERT OR UPDATE OR DELETE ON commercial.plan_term_discounts
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_activated_plan_terms_change();

ALTER TABLE commercial.subscriptions
    ADD COLUMN term_months integer NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_subscription_term_months CHECK (term_months IN (1, 3, 6, 12, 24));

COMMENT ON COLUMN commercial.subscriptions.term_months IS
    'ADR 0093. The term the subscription was sold on; 1 is month to month. A longer term takes the plan version''s discount for it.';

ALTER TABLE commercial.statement_lines DROP CONSTRAINT ck_statement_line_kind;
ALTER TABLE commercial.statement_lines
    ADD CONSTRAINT ck_statement_line_kind CHECK (kind IN ('PLAN', 'MODULE', 'OVERAGE', 'DEPOSIT'));

GRANT SELECT, INSERT ON commercial.plan_version_terms TO horecaos_application;
GRANT SELECT, INSERT ON commercial.plan_term_discounts TO horecaos_application;
