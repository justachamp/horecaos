-- ADR 0095 (Accepted 2026-10-07): card charging, the recurring token flow.
--
-- Until now the only way a card token reached commercial.tenant_billing was a HorecaOS staff
-- member typing one in, and the only thing a card was ever asked for was the remainder of an issued
-- statement. Two changes:
--
-- 1. A tenant binds its OWN card, through the provider's hosted form (the card number never
--    reaches HorecaOS, ADR 0028), and the row keeps what is safe to show back -- the last four
--    digits, the brand and the expiry month -- beside the token reference, so a screen can say which
--    card is on file and a sweep can say it is about to lapse.
--
--    The token used to be allowed ONLY while the method was CARD (ck_tenant_billing_card_token).
--    That tied a stored card to autopay, and a tenant on WALLET that tops up by card needs a card on
--    file without consenting to be charged for every statement. The constraint is dropped; what
--    decides whether a statement's remainder is charged is still payment_method = 'CARD', and
--    choosing CARD is the tenant's own recorded consent.
--
-- 2. A tenant tops its wallet up by card. One row per attempt, and the row's id is the idempotency
--    key the provider is handed -- the same contract as commercial.card_charge_attempts (V0214),
--    for the same reason: a key is re-sent only when the SAME attempt for the SAME amount is
--    retried. The row is committed BEFORE the provider is asked, so "we asked and never learned the
--    answer" survives a crash, and a success always names the ledger entry it credited
--    (ck_card_top_up_credit): money that left a card and is not on the ledger cannot be an
--    outcome this table can hold.

ALTER TABLE commercial.tenant_billing
    DROP CONSTRAINT ck_tenant_billing_card_token;

ALTER TABLE commercial.tenant_billing
    ADD COLUMN card_last4 char(4),
    ADD COLUMN card_brand varchar(24),
    ADD COLUMN card_expiry_month smallint,
    ADD COLUMN card_expiry_year smallint,
    ADD COLUMN card_bound_at timestamptz,
    ADD COLUMN card_bound_by varchar(255),
    ADD CONSTRAINT ck_tenant_billing_card_display CHECK (
        card_token_reference IS NOT NULL
        OR (card_last4 IS NULL AND card_brand IS NULL AND card_expiry_month IS NULL
            AND card_expiry_year IS NULL AND card_bound_at IS NULL AND card_bound_by IS NULL)
    ),
    ADD CONSTRAINT ck_tenant_billing_card_last4 CHECK (card_last4 IS NULL OR card_last4 ~ '^[0-9]{4}$'),
    ADD CONSTRAINT ck_tenant_billing_card_expiry CHECK (
        (card_expiry_month IS NULL) = (card_expiry_year IS NULL)
        AND (card_expiry_month IS NULL OR card_expiry_month BETWEEN 1 AND 12)
        AND (card_expiry_year IS NULL OR card_expiry_year BETWEEN 2000 AND 2999)
    );

COMMENT ON COLUMN commercial.tenant_billing.card_token_reference IS
    'ADR 0095, ADR 0028. A reference into the provider''s vault, never a card number. Composed as "<platform card installation id>:<provider token>" when the tenant bound the card itself, so a card bound under one merchant account is never sent to another; a bare value is a staff-typed reference. Independent of payment_method since V0505: a WALLET tenant may hold a card to top up with.';

CREATE TABLE commercial.card_top_ups (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    -- Pinned at insert, like card_charge_attempts.card_token_reference: an attempt retried under
    -- its own key must be retried under the card it was first asked about.
    card_token_reference varchar(128) NOT NULL,
    outcome varchar(16) NOT NULL,
    -- The provider's reference on a success, its reason code on a decline. Never a token.
    provider_detail varchar(255),
    wallet_entry_id uuid,
    requested_by varchar(255) NOT NULL,
    requested_at timestamptz NOT NULL,
    settled_at timestamptz,
    CONSTRAINT fk_card_top_up_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_card_top_up_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_card_top_up_entry FOREIGN KEY (tenant_id, wallet_entry_id)
        REFERENCES commercial.wallet_entries (tenant_id, id),
    CONSTRAINT ck_card_top_up_outcome CHECK (outcome IN ('PENDING', 'SUCCEEDED', 'FAILED', 'NOT_CONFIGURED')),
    CONSTRAINT ck_card_top_up_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_card_top_up_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_card_top_up_settled CHECK ((outcome = 'PENDING') = (settled_at IS NULL)),
    CONSTRAINT ck_card_top_up_credit CHECK ((outcome = 'SUCCEEDED') = (wallet_entry_id IS NOT NULL))
);

COMMENT ON TABLE commercial.card_top_ups IS
    'ADR 0095. One attempt to charge a tenant''s card for money to hold in its wallet. The row id is the idempotency key the provider is handed; the row is committed before the provider is asked; a success always names the ledger entry it credited.';

-- At most one unresolved top-up per tenant: a second click, or a second user, while the first is
-- still waiting for the provider would otherwise be two charges for what the tenant sees as one.
CREATE UNIQUE INDEX ux_card_top_up_one_pending
    ON commercial.card_top_ups (tenant_id)
    WHERE outcome = 'PENDING';

CREATE INDEX ix_card_top_up_tenant ON commercial.card_top_ups (tenant_id, requested_at DESC);
CREATE INDEX ix_card_top_up_pending_age ON commercial.card_top_ups (requested_at) WHERE outcome = 'PENDING';

GRANT SELECT, INSERT, UPDATE ON commercial.card_top_ups TO horecaos_application;
