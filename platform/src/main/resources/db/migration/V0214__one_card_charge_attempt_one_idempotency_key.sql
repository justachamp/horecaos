-- ADR 0095: one attempt to charge a tenant's card is one row, and its id is the
-- idempotency key the provider is handed.
--
-- The key used to be the statement id, on the stated reasoning that "one
-- statement is charged for its remainder at most once per attempt". That is
-- true within one settlement pass and false across them: the settlement runs
-- again on every transfer, deposit, approved grant and upward correction, and
-- charges whatever the statement still owes at that moment. So a statement
-- declined at 1 000 000, part-paid by a 400 000 transfer, is charged 600 000
-- under the same key -- the one thing a provider-side idempotency key must
-- never carry. A provider that honours keys then either replays the stored
-- decline forever, so the statement can never be collected by card however good
-- the card becomes, or replays an earlier success for a different amount and the
-- wallet credits money nobody took.
--
-- A row per attempt fixes the contract rather than patching the key: the same
-- key is re-sent only when retrying that same attempt for that same amount, and
-- a charge for a different amount necessarily carries a new one. It also gives
-- finance the history the decline audit fact alone cannot -- how often this
-- statement was tried, at what amounts, and what the provider said each time.
CREATE TABLE commercial.card_charge_attempts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    statement_id uuid NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    -- PENDING is written before the provider is called, so the key exists and
    -- is durable before anything can be charged under it. The outcome replaces
    -- it when the provider answers.
    outcome varchar(16) NOT NULL,
    -- The provider's own reference on a success, or its reason code on a
    -- decline. Never a card number and never a token (ADR 0028).
    provider_detail varchar(255),
    attempted_at timestamptz NOT NULL,
    settled_at timestamptz,
    CONSTRAINT fk_card_charge_attempt_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_card_charge_attempt_statement FOREIGN KEY (tenant_id, statement_id)
        REFERENCES commercial.statements (tenant_id, id),
    CONSTRAINT ck_card_charge_attempt_outcome CHECK (
        outcome IN ('PENDING', 'SUCCEEDED', 'FAILED', 'NOT_CONFIGURED')
    ),
    CONSTRAINT ck_card_charge_attempt_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_card_charge_attempt_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_card_charge_attempt_settled CHECK (
        (outcome = 'PENDING') = (settled_at IS NULL)
    )
);

COMMENT ON TABLE commercial.card_charge_attempts IS
    'ADR 0095. One attempt to charge a tenant''s card for a statement''s remainder. The row id is the idempotency key handed to the provider, so a retry of the same attempt is deduplicated and a charge for a different amount never is.';

CREATE INDEX ix_card_charge_attempt_statement
    ON commercial.card_charge_attempts (tenant_id, statement_id, attempted_at DESC);

GRANT SELECT, INSERT, UPDATE ON commercial.card_charge_attempts TO horecaos_application;
