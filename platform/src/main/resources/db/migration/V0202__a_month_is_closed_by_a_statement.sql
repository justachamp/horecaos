-- ADR 0088: a month of a tenant's commercial terms is closed by issuing a
-- statement, and a subscription remembers when its status last moved.
--
-- A statement is the evidence an invoice is made from (ADR 0021): the plan,
-- the modules and the overage for one calendar month in the tenant's own
-- timezone, frozen with a number when a person issues it. It is not a tax
-- document and records nothing about how it is paid.

ALTER TABLE commercial.subscriptions ADD COLUMN status_changed_at timestamptz;
UPDATE commercial.subscriptions SET status_changed_at = updated_at;
ALTER TABLE commercial.subscriptions
    ALTER COLUMN status_changed_at SET NOT NULL,
    ALTER COLUMN status_changed_at SET DEFAULT now();

COMMENT ON COLUMN commercial.subscriptions.status_changed_at IS
    'ADR 0089. When the status last moved, so the arrears board can say how long a tenant has been past due. Rows that existed before this column read their last update.';

CREATE INDEX ix_subscription_arrears ON commercial.subscriptions (status_changed_at)
    WHERE status IN ('PAST_DUE', 'SUSPENDED');

CREATE SEQUENCE commercial.statement_number_seq;

CREATE TABLE commercial.statements (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    number varchar(32) NOT NULL,
    period_key varchar(7) NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    currency char(3) NOT NULL,
    total_minor bigint NOT NULL,
    subscription_id uuid,
    status varchar(8) NOT NULL,
    issued_by varchar(255) NOT NULL,
    issued_at timestamptz NOT NULL,
    issue_reason varchar(1000) NOT NULL,
    voided_by varchar(255),
    voided_at timestamptz,
    void_reason varchar(1000),
    CONSTRAINT fk_statement_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT uq_statement_number UNIQUE (number),
    CONSTRAINT uq_statement_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_statement_period_key CHECK (period_key ~ '^\d{4}-\d{2}$'),
    CONSTRAINT ck_statement_period CHECK (period_end > period_start),
    CONSTRAINT ck_statement_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_statement_total CHECK (total_minor >= 0),
    CONSTRAINT ck_statement_status CHECK (status IN ('ISSUED', 'VOID')),
    CONSTRAINT ck_statement_void CHECK (
        (status = 'VOID') = (voided_at IS NOT NULL)
        AND (voided_at IS NULL) = (voided_by IS NULL)
        AND (voided_at IS NULL) = (void_reason IS NULL)
    )
);

-- One standing statement per tenant and month. Voiding one is how a mistake
-- is corrected, and it frees the month for the statement that replaces it.
CREATE UNIQUE INDEX ux_statement_issued_period ON commercial.statements (tenant_id, period_key)
    WHERE status = 'ISSUED';
CREATE INDEX ix_statement_tenant ON commercial.statements (tenant_id, period_key DESC);

COMMENT ON TABLE commercial.statements IS
    'ADR 0088. One issued month of what a tenant owes under its plan and modules, before tax. Frozen when issued; corrected by voiding and issuing again.';

CREATE TABLE commercial.statement_lines (
    tenant_id uuid NOT NULL,
    statement_id uuid NOT NULL,
    line_number integer NOT NULL,
    kind varchar(8) NOT NULL,
    reference_code varchar(128) NOT NULL,
    description varchar(300) NOT NULL,
    quantity bigint NOT NULL,
    unit_price_minor bigint NOT NULL,
    amount_minor bigint NOT NULL,
    CONSTRAINT pk_statement_line PRIMARY KEY (statement_id, line_number),
    CONSTRAINT fk_statement_line_statement FOREIGN KEY (tenant_id, statement_id)
        REFERENCES commercial.statements (tenant_id, id),
    CONSTRAINT ck_statement_line_number CHECK (line_number > 0),
    CONSTRAINT ck_statement_line_kind CHECK (kind IN ('PLAN', 'MODULE', 'OVERAGE')),
    CONSTRAINT ck_statement_line_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_statement_line_price CHECK (unit_price_minor >= 0),
    CONSTRAINT ck_statement_line_amount CHECK (amount_minor = quantity * unit_price_minor)
);

-- An issued statement is a document. The only change it ever takes is being
-- voided, and its lines take none at all.
CREATE OR REPLACE FUNCTION commercial.reject_issued_statement_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'An issued statement is never deleted; void it (ADR 0088)';
    END IF;
    IF OLD.status <> 'ISSUED' OR NEW.status <> 'VOID'
        OR NEW.number IS DISTINCT FROM OLD.number
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.period_key IS DISTINCT FROM OLD.period_key
        OR NEW.period_start IS DISTINCT FROM OLD.period_start
        OR NEW.period_end IS DISTINCT FROM OLD.period_end
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.total_minor IS DISTINCT FROM OLD.total_minor
        OR NEW.subscription_id IS DISTINCT FROM OLD.subscription_id
        OR NEW.issued_by IS DISTINCT FROM OLD.issued_by
        OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
        OR NEW.issue_reason IS DISTINCT FROM OLD.issue_reason THEN
        RAISE EXCEPTION 'An issued statement only ever moves to VOID (ADR 0088)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_statements_frozen
    BEFORE UPDATE OR DELETE ON commercial.statements
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_issued_statement_change();

CREATE OR REPLACE FUNCTION commercial.reject_statement_line_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Statement lines are written once with their statement (ADR 0088)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_statement_lines_frozen
    BEFORE UPDATE OR DELETE ON commercial.statement_lines
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_statement_line_change();

GRANT SELECT, INSERT, UPDATE ON commercial.statements TO horecaos_application;
GRANT SELECT, INSERT ON commercial.statement_lines TO horecaos_application;
GRANT USAGE, SELECT ON SEQUENCE commercial.statement_number_seq TO horecaos_application;
