-- ADR 0095 (Accepted 2026-10-07): "invoices for prepaid money".
--
-- A tenant that pays by bank transfer needs a document to pay against before there is anything to
-- pay: the amount, where to send it, and a number to quote so finance can tell whose money arrived.
-- A statement answers that for what is OWED; this answers it for what a tenant wants to PUT IN. It
-- is a request for payment, not a tax invoice: the wallet computes no tax until finance says how
-- money held in advance is taxed (decision 7), so every figure here is before tax.
--
-- Immutable once issued, like a statement (ADR 0088): amount, currency, number and the bank details
-- as they were at issue never change, because the tenant pays what the document SAID. The only
-- thing that can happen to one is a cancellation, which the application role may write (and only
-- those three columns, by column grant, so neither stop depends on the other).
--
-- Whether it was paid is not stored. It is the sum of the TOP_UP entries that name it, the way a
-- statement's paid figure is the sum of its entries: a status column beside the ledger is the gap
-- every earlier ledger defect lived in (CLAUDE.md, ADR 0095 decision 1).

CREATE SEQUENCE commercial.prepayment_invoice_number_seq;

CREATE TABLE commercial.prepayment_invoices (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    number varchar(32) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    valid_until timestamptz NOT NULL,
    bank_beneficiary varchar(255) NOT NULL,
    bank_name varchar(255) NOT NULL,
    bank_account varchar(64) NOT NULL,
    bank_mfo varchar(32) NOT NULL,
    bank_tax_id varchar(32) NOT NULL,
    issued_by varchar(255) NOT NULL,
    issued_at timestamptz NOT NULL,
    cancelled_by varchar(255),
    cancelled_at timestamptz,
    cancel_reason varchar(1000),
    CONSTRAINT fk_prepayment_invoice_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_prepayment_invoice_number UNIQUE (number),
    CONSTRAINT uq_prepayment_invoice_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_prepayment_invoice_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_prepayment_invoice_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_prepayment_invoice_validity CHECK (valid_until > issued_at),
    CONSTRAINT ck_prepayment_invoice_cancel CHECK (
        (cancelled_by IS NULL) = (cancelled_at IS NULL)
        AND (cancelled_by IS NULL) = (cancel_reason IS NULL)
    )
);

COMMENT ON TABLE commercial.prepayment_invoices IS
    'ADR 0095. A request for payment of money to be held in a tenant''s wallet, before tax. Frozen at issue with the bank details of that moment; only a cancellation may be written afterwards. Paid-ness is the sum of the TOP_UP entries naming it, never a column.';

CREATE INDEX ix_prepayment_invoice_tenant ON commercial.prepayment_invoices (tenant_id, issued_at DESC);

CREATE OR REPLACE FUNCTION commercial.freeze_prepayment_invoice() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A prepayment invoice is never deleted (ADR 0095)';
    END IF;
    IF OLD.cancelled_at IS NOT NULL THEN
        RAISE EXCEPTION 'A cancelled prepayment invoice is never changed (ADR 0095)';
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.number, NEW.amount_minor, NEW.currency, NEW.valid_until,
        NEW.bank_beneficiary, NEW.bank_name, NEW.bank_account, NEW.bank_mfo, NEW.bank_tax_id,
        NEW.issued_by, NEW.issued_at)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.number, OLD.amount_minor, OLD.currency, OLD.valid_until,
        OLD.bank_beneficiary, OLD.bank_name, OLD.bank_account, OLD.bank_mfo, OLD.bank_tax_id,
        OLD.issued_by, OLD.issued_at) THEN
        RAISE EXCEPTION 'An issued prepayment invoice is frozen; only a cancellation may be written (ADR 0095)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prepayment_invoices_frozen
    BEFORE UPDATE OR DELETE ON commercial.prepayment_invoices
    FOR EACH ROW EXECUTE FUNCTION commercial.freeze_prepayment_invoice();

GRANT SELECT, INSERT ON commercial.prepayment_invoices TO horecaos_application;
GRANT UPDATE (cancelled_by, cancelled_at, cancel_reason) ON commercial.prepayment_invoices TO horecaos_application;
GRANT USAGE, SELECT ON SEQUENCE commercial.prepayment_invoice_number_seq TO horecaos_application;

-- The entry that pays an invoice names it. The ledger stays append-only (the trigger refuses UPDATE
-- and DELETE, not a new column), and only money in may name one.
ALTER TABLE commercial.wallet_entries
    ADD COLUMN prepayment_invoice_id uuid,
    ADD CONSTRAINT fk_wallet_entry_prepayment_invoice FOREIGN KEY (tenant_id, prepayment_invoice_id)
        REFERENCES commercial.prepayment_invoices (tenant_id, id),
    ADD CONSTRAINT ck_wallet_entry_prepayment_invoice CHECK (
        prepayment_invoice_id IS NULL OR entry_type = 'TOP_UP'
    );

CREATE INDEX ix_wallet_entry_prepayment_invoice
    ON commercial.wallet_entries (tenant_id, prepayment_invoice_id)
    WHERE prepayment_invoice_id IS NOT NULL;
