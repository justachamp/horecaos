-- ADR 0095: a tenant's wallet keeps money it paid HorecaOS apart from bonus
-- money HorecaOS granted it. One append-only ledger per tenant, in its
-- billing currency; a balance is the SUM of its own entries and none is
-- stored beside them -- the loyalty ledger's defects all lived in that gap
-- (see CLAUDE.md), and this table is built so the database itself refuses to
-- reopen it: no UPDATE, no DELETE, not even to the application role.

CREATE TABLE commercial.wallet_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    money_kind varchar(5) NOT NULL,
    entry_type varchar(20) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    -- The statement a STATEMENT_PAYMENT entry pays down, or that a
    -- STATEMENT_REVERSAL gives back when the statement is voided. Null for
    -- every other type.
    statement_id uuid,
    -- For a BONUS_GRANT entry, this is null: the row's own id is the grant.
    -- Every other bonus entry names the grant it moves, so a grant's remainder
    -- is the sum of the entries naming it and the bonus balance is the sum of
    -- every grant's remainder. That is why a BONUS ADJUSTMENT names one too: a
    -- bonus that belonged to no grant could never be spent (a statement draws
    -- grant by grant) and would never lapse.
    grant_id uuid,
    -- A BONUS_GRANT entry's own lapse date. Null on every other type.
    expires_at timestamptz,
    -- The bank's reference (a transfer or the deposit), a refund's payout
    -- reference, or a card charge's provider reference. Never a card number
    -- (ADR 0028): only a reference ever reaches this column.
    external_reference varchar(128),
    -- The same reference with the rendering a human chose taken out of it:
    -- upper case, no whitespace, no hyphens, no leading '#'. The column the
    -- uniqueness of money in is actually enforced on, because "MT103-7" and
    -- " mt103 7" are one transfer typed twice and a raw-column index would
    -- credit the tenant for both. Stored and generated rather than written by
    -- the application, so the ledger keeps verbatim what the recorder typed
    -- and the two can never disagree. Same rule as partner.ExternalReference
    -- (V0038), which indexes the normalised value for the same reason.
    external_reference_normalised varchar(128)
        GENERATED ALWAYS AS (upper(regexp_replace(external_reference, '^#|[[:space:]-]', '', 'g'))) STORED,
    reason varchar(1000) NOT NULL,
    recorded_by varchar(255) NOT NULL,
    -- Who gave the second signature on an ADJUSTMENT, BONUS_GRANT or REFUND
    -- (ADR 0027). Null on every entry one person records alone.
    approved_by varchar(255),
    approval_request_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_wallet_entry_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT uq_wallet_entry_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_wallet_entry_statement FOREIGN KEY (tenant_id, statement_id)
        REFERENCES commercial.statements (tenant_id, id),
    CONSTRAINT fk_wallet_entry_grant FOREIGN KEY (tenant_id, grant_id)
        REFERENCES commercial.wallet_entries (tenant_id, id),
    CONSTRAINT ck_wallet_entry_money_kind CHECK (money_kind IN ('PAID', 'BONUS')),
    CONSTRAINT ck_wallet_entry_type CHECK (entry_type IN (
        'TOP_UP', 'DEPOSIT', 'BONUS_GRANT', 'BONUS_EXPIRY', 'STATEMENT_PAYMENT',
        'STATEMENT_REVERSAL', 'ADJUSTMENT', 'REFUND', 'DEPOSIT_REVERSAL')),
    CONSTRAINT ck_wallet_entry_amount CHECK (amount_minor <> 0),
    CONSTRAINT ck_wallet_entry_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- Money in (TOP_UP, DEPOSIT) is always PAID; a refund and the reversal of
    -- a deposit recorded in error are always PAID money leaving; a bonus grant
    -- and its expiry are always BONUS. A statement payment, the reversal of
    -- one, or a correction can be either kind.
    CONSTRAINT ck_wallet_entry_kind_for_type CHECK (
        (entry_type IN ('TOP_UP', 'DEPOSIT', 'REFUND', 'DEPOSIT_REVERSAL') AND money_kind = 'PAID')
        OR (entry_type IN ('BONUS_GRANT', 'BONUS_EXPIRY') AND money_kind = 'BONUS')
        OR (entry_type IN ('STATEMENT_PAYMENT', 'STATEMENT_REVERSAL', 'ADJUSTMENT'))
    ),
    CONSTRAINT ck_wallet_entry_statement_ref CHECK (
        (entry_type IN ('STATEMENT_PAYMENT', 'STATEMENT_REVERSAL')) = (statement_id IS NOT NULL)
    ),
    -- Every bonus entry but the grant itself names the grant it moves.
    CONSTRAINT ck_wallet_entry_grant_ref CHECK (
        (grant_id IS NOT NULL) = (
            entry_type = 'BONUS_EXPIRY'
            OR (money_kind = 'BONUS'
                AND entry_type IN ('STATEMENT_PAYMENT', 'STATEMENT_REVERSAL', 'ADJUSTMENT'))
        )
    ),
    CONSTRAINT ck_wallet_entry_expiry CHECK (
        (entry_type = 'BONUS_GRANT') = (expires_at IS NOT NULL)
    ),
    -- Money in is a positive entry; a statement payment, an expiry, a refund
    -- and the reversal of a deposit recorded in error always take money away.
    -- Voiding a statement gives back what it drew, which is money in again.
    CONSTRAINT ck_wallet_entry_sign CHECK (
        (entry_type IN ('TOP_UP', 'DEPOSIT', 'BONUS_GRANT', 'STATEMENT_REVERSAL') AND amount_minor > 0)
        OR (entry_type IN ('STATEMENT_PAYMENT', 'BONUS_EXPIRY', 'REFUND', 'DEPOSIT_REVERSAL')
            AND amount_minor < 0)
        OR (entry_type = 'ADJUSTMENT')
    ),
    -- Nothing moves until a different person approves it (ADR 0027); a
    -- one-person act carries no approver.
    CONSTRAINT ck_wallet_entry_approval CHECK (
        (entry_type IN ('ADJUSTMENT', 'BONUS_GRANT', 'REFUND', 'DEPOSIT_REVERSAL'))
            = (approved_by IS NOT NULL)
        AND (approved_by IS NULL) = (approval_request_id IS NULL)
    ),
    -- Four eyes on the row itself, the way every other money table in this
    -- schema states it (V0033's ck_usage_adjustment_four_eyes,
    -- ck_entitlement_override_four_eyes, V0201's ck_module_four_eyes): a
    -- reader of the ledger alone must never find one name in both columns.
    -- The approval model already forbids a maker deciding their own request;
    -- this forbids the row that would *read* as one person having recorded
    -- and approved money leaving the platform.
    CONSTRAINT ck_wallet_entry_four_eyes CHECK (
        approved_by IS NULL OR approved_by <> recorded_by
    ),
    -- A transfer carries the bank's reference that proves it; a refund names
    -- the payout it left on, and a deposit reversal names the deposit it takes
    -- back (ADR 0095 items 2 and 5).
    CONSTRAINT ck_wallet_entry_reference CHECK (
        entry_type NOT IN ('TOP_UP', 'DEPOSIT', 'REFUND', 'DEPOSIT_REVERSAL')
        OR external_reference IS NOT NULL
    )
);

COMMENT ON TABLE commercial.wallet_entries IS
    'ADR 0095. A tenant''s wallet: append-only, PAID money apart from BONUS money. A balance is the SUM of its own entries; none is stored beside them. No entry is ever updated or deleted.';

CREATE INDEX ix_wallet_entry_tenant ON commercial.wallet_entries (tenant_id, created_at);
CREATE INDEX ix_wallet_entry_statement ON commercial.wallet_entries (statement_id) WHERE statement_id IS NOT NULL;
CREATE INDEX ix_wallet_entry_grant ON commercial.wallet_entries (grant_id) WHERE grant_id IS NOT NULL;
-- Money in is recorded once. A bank reference identifies one transfer and a
-- provider reference one charge, so the same reference arriving twice for a
-- tenant is a transfer recorded twice -- which credits money that never came
-- and pays statements nobody paid. The database refuses it; the service turns
-- the refusal into a conflict the recorder can read.
--
-- On the normalised value, not the typed one: two recorders reconciling the
-- same statement type "MT103-7" and " mt103 7", and an index on the raw column
-- reads those as two transfers. Normalising narrows the hole rather than
-- closing it -- "MT103-7" against "MT1037-A" still passes -- so one recorder
-- per statement, reconciled against the bank feed, stays the real control.
CREATE UNIQUE INDEX ux_wallet_entry_money_in_reference
    ON commercial.wallet_entries (tenant_id, external_reference_normalised)
    WHERE entry_type IN ('TOP_UP', 'DEPOSIT');
-- A deposit recorded against the wrong tenant is taken back once, by the
-- reversal that names it. Without this a second approved reversal of the same
-- deposit would re-arm the obligation twice over.
CREATE UNIQUE INDEX ux_wallet_entry_deposit_reversal
    ON commercial.wallet_entries (tenant_id, external_reference_normalised)
    WHERE entry_type = 'DEPOSIT_REVERSAL';
-- Live bonus grants, earliest expiry first: exactly the order a statement is
-- paid from bonus money in (ADR 0095 item 3).
CREATE INDEX ix_wallet_entry_live_grants ON commercial.wallet_entries (tenant_id, expires_at)
    WHERE entry_type = 'BONUS_GRANT';
-- The hourly expiry sweep is estate-wide: it asks for the oldest expired
-- grants across every tenant, so ix_wallet_entry_live_grants (tenant-leading)
-- gives it neither a seek on expires_at nor the ORDER BY, and its LIMIT would
-- buy nothing. Same pair as loyalty.lots (V0042): one tenant-scoped index for
-- the draw order, one estate-wide index for the sweep.
CREATE INDEX ix_wallet_entry_expiry_sweep ON commercial.wallet_entries (expires_at)
    WHERE entry_type = 'BONUS_GRANT';

-- Refused at the database, not only in the service: a ledger this platform's
-- loyalty module already got wrong once (CLAUDE.md) is not trusted to a
-- convention a second time.
CREATE OR REPLACE FUNCTION commercial.reject_wallet_entry_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'A wallet entry is never changed or deleted (ADR 0095)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_wallet_entries_immutable
    BEFORE UPDATE OR DELETE ON commercial.wallet_entries
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_wallet_entry_change();

-- INSERT and SELECT only. No UPDATE, no DELETE -- the trigger above is the
-- second stop, this GRANT is the first, and neither depends on the other.
GRANT SELECT, INSERT ON commercial.wallet_entries TO horecaos_application;

-- Every tenant's payment method (ADR 0095 item 8): INVOICE waits for a bank
-- transfer, WALLET waits for a top-up, CARD is charged automatically once a
-- merchant account exists. Also the row every wallet mutation locks with
-- FOR UPDATE, so the ledger itself never needs a row lock (ADR 0095's own
-- "no stored balance" rule would make locking a ledger row meaningless
-- anyway -- there is no balance column on it to protect).
CREATE TABLE commercial.tenant_billing (
    tenant_id uuid PRIMARY KEY,
    payment_method varchar(7) NOT NULL DEFAULT 'INVOICE',
    -- A token reference, never a card number (ADR 0028). Set only at CARD.
    card_token_reference varchar(128),
    updated_by varchar(255) NOT NULL DEFAULT 'migration V0211',
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tenant_billing_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_tenant_billing_method CHECK (payment_method IN ('INVOICE', 'WALLET', 'CARD')),
    CONSTRAINT ck_tenant_billing_card_token CHECK (
        (payment_method = 'CARD') OR (card_token_reference IS NULL)
    )
);

COMMENT ON TABLE commercial.tenant_billing IS
    'ADR 0095. How a tenant is collected: INVOICE waits for a bank transfer, WALLET waits for a top-up, CARD is charged automatically once a merchant account exists. Staff-changeable with a reason; also the lock every wallet mutation takes so the append-only ledger never needs a row lock of its own.';

GRANT SELECT, INSERT, UPDATE ON commercial.tenant_billing TO horecaos_application;

-- The deposit becomes due the moment a subscription with one starts (ADR
-- 0093/0095, decided 2026-09-11: "credit the first statement"). Paying it is
-- a DEPOSIT top-up in the wallet, which clears this back to zero; the
-- statement no longer bills a DEPOSIT line at all.
ALTER TABLE commercial.subscriptions
    ADD COLUMN deposit_due_minor bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_subscription_deposit_due CHECK (deposit_due_minor >= 0);

COMMENT ON COLUMN commercial.subscriptions.deposit_due_minor IS
    'ADR 0095. Set to the plan version''s activation deposit when the subscription starts; cleared to zero when the deposit is recorded as a wallet top-up, and restored when an approved DEPOSIT_REVERSAL takes a deposit recorded in error back. Not a balance -- the wallet ledger is the only source of truth for money moved; this is a due-or-not flag a statement''s draft never reads.';

-- ADR 0027: four actions that move a tenant's money by hand, each proposed
-- by one person and approved by a different one. Seeded at platform scope so
-- they are governed from the first day, exactly as V0203 seeds
-- tenant.country.change -- a wallet correction, a bonus grant, a refund and
-- the reversal of a deposit recorded against the wrong tenant are exactly the
-- decisions a second signature exists for.
INSERT INTO audit.approval_policies (
    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
    valid_from, version, approved_by)
VALUES
    ('0192d1a0-0000-7000-8000-000000000211', NULL, 'commercial.wallet.adjustment', 'PLATFORM',
     '{"description": "Every correction to a tenant''s wallet, either money kind, up or down"}'::jsonb,
     'commercial.wallet.manage', '2026-09-11T00:00:00Z', 1, 'migration V0211'),
    ('0192d1a0-0000-7000-8000-000000000212', NULL, 'commercial.wallet.bonus-grant', 'PLATFORM',
     '{"description": "Every bonus money grant to a tenant"}'::jsonb,
     'commercial.wallet.manage', '2026-09-11T00:00:00Z', 1, 'migration V0211'),
    ('0192d1a0-0000-7000-8000-000000000213', NULL, 'commercial.wallet.refund', 'PLATFORM',
     '{"description": "Every refund of a tenant''s paid money"}'::jsonb,
     'commercial.wallet.manage', '2026-09-11T00:00:00Z', 1, 'migration V0211'),
    ('0192d1a0-0000-7000-8000-000000000214', NULL, 'commercial.wallet.deposit-reversal', 'PLATFORM',
     '{"description": "Every reversal of an activation deposit recorded in error"}'::jsonb,
     'commercial.wallet.manage', '2026-09-11T00:00:00Z', 1, 'migration V0211');
