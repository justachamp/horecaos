-- ADR 0171 (row 6.3b): who owns a loyalty balance when the till also keeps one.
--
-- The platform's append-only loyalty ledger is the single authority for every balance the
-- platform issues (ADR 0046). A till's balance is a MIRROR kept equal by an adapter capability,
-- LOYALTY_BALANCE_SYNC; what happened at the till is imported as facts (order-free ADJUSTMENT
-- entries, bounded by caps); a reconciliation report compares the two and names every
-- difference; and a vendor that cannot do this is reported as unable, never as working.
--
-- What this migration is, and is not.
--
--   * Four tables, all append-mostly evidence of what moved: no DELETE is granted to the
--     application role, and retention follows the ledger's. None of them is a balance. The
--     platform keeps no second stored balance: `last_till_balance_minor` below is an OBSERVATION
--     of the till's number beside the account it was read against, never an input to
--     `loyalty.accounts.balance_minor`, which is still the projection of `loyalty.entries`.
--   * `limits` on the vendor ceiling row: the capability's sub-properties (reads a balance, lists
--     movements since a cursor, credits, debits, sets a balance, holds, one-to-one programme,
--     idempotency) recorded as data, because PARTIAL is the honest value for a vendor that can
--     write a balance and cannot read movements, and the rationale has to be able to name the half.
--   * One ceiling row, for the controlled fake. The real vendors are DELIBERATELY ABSENT:
--     integration.pos_capability_is_supported() refuses a capability nobody has assessed, so
--     Clopos (whose notes record no balance field) and iiko and R-Keeper (of which nothing in this
--     repository says what either exposes for a customer wallet) cannot be given LOYALTY_BALANCE_SYNC
--     until a provider note exists in docs/providers/ and a row exists here with evidence. That
--     refusal is the feature for those vendors, not a gap in it.

ALTER TABLE integration.pos_provider_capabilities
    ADD COLUMN limits jsonb;

ALTER TABLE integration.pos_provider_capabilities
    ADD CONSTRAINT ck_pos_provider_capability_limits CHECK (limits IS NULL OR jsonb_typeof(limits) = 'object');

COMMENT ON COLUMN integration.pos_provider_capabilities.limits IS
    'ADR 0171. Sub-properties of a capability as data: for LOYALTY_BALANCE_SYNC, reads_balance, reads_movements, credits, debits, sets_balance, holds, one_to_one, and idempotency (KEYED, NATURALLY_IDEMPOTENT or NONE). Null when the capability has none worth carrying.';

INSERT INTO integration.pos_provider_capabilities
    (provider_type, capability_code, support_level, rationale, evidence_source, assessed_on, limits)
VALUES
    ('fake-pos', 'LOYALTY_BALANCE_SYNC', 'SUPPORTED',
     'The controlled fake keeps an in-memory customer wallet it can read, list movements of, credit, debit and set, deduplicating on the idempotency key, with its failure modes injectable (a reply lost after a write is applied, a duplicate page, a till spend during a push, a currency mismatch, an unreadable movement feed). It exists so the sync is proved against more than the absence Clopos and the other vendors present.',
     'ADR 0171 controlled fake provider', DATE '2026-10-08',
     '{"reads_balance": true, "reads_movements": true, "credits": true, "debits": true, "sets_balance": true, "holds": false, "one_to_one": true, "idempotency": "KEYED"}'::jsonb);

-- ---------------------------------------------------------------------------
-- The reconciliation report's header
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty.pos_sync_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    binding_id uuid NOT NULL,

    -- PULL imports what happened at the till; PUSH sends the platform's number; RECONCILE compares
    -- every matched account with a fresh read and is the report an operator reads.
    kind varchar(16) NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,

    compared integer NOT NULL DEFAULT 0,
    matched integer NOT NULL DEFAULT 0,
    till_higher integer NOT NULL DEFAULT 0,
    till_lower integer NOT NULL DEFAULT 0,
    till_only integer NOT NULL DEFAULT 0,
    platform_only integer NOT NULL DEFAULT 0,
    ambiguous integer NOT NULL DEFAULT 0,
    imported integer NOT NULL DEFAULT 0,
    held integer NOT NULL DEFAULT 0,
    unverifiable integer NOT NULL DEFAULT 0,
    pushed integer NOT NULL DEFAULT 0,

    status varchar(16) NOT NULL DEFAULT 'RUNNING',
    failure_code varchar(64),

    -- Who asked: a person for "reconcile now", the scheduler's job name otherwise. An opaque subject
    -- or a system component name (ADR 0029), never a name.
    requested_by varchar(255) NOT NULL,

    CONSTRAINT fk_pos_sync_run_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT fk_pos_sync_run_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_pos_sync_run_identity UNIQUE (tenant_id, id),
    CONSTRAINT ck_pos_sync_run_kind CHECK (kind IN ('PUSH', 'PULL', 'RECONCILE')),
    CONSTRAINT ck_pos_sync_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_pos_sync_run_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT ck_pos_sync_run_failure CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT ck_pos_sync_run_counts CHECK (
        compared >= 0 AND matched >= 0 AND till_higher >= 0 AND till_lower >= 0 AND till_only >= 0
        AND platform_only >= 0 AND ambiguous >= 0 AND imported >= 0 AND held >= 0
        AND unverifiable >= 0 AND pushed >= 0)
);

CREATE INDEX ix_pos_sync_run_brand ON loyalty.pos_sync_runs (tenant_id, brand_id, started_at DESC);
CREATE INDEX ix_pos_sync_run_binding ON loyalty.pos_sync_runs (tenant_id, binding_id, kind, started_at DESC);

COMMENT ON TABLE loyalty.pos_sync_runs IS
    'ADR 0171. One row per push, pull or reconcile pass over one binding: the header of the reconciliation report, with the counts the report names. A difference is never silently corrected, so a run records what it found and a person resolves it.';

-- ---------------------------------------------------------------------------
-- Which till customer is which points account
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty.pos_balance_sync_state (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    account_id uuid NOT NULL,

    -- The ADR 0026 mapping (entity type LOYALTY_CUSTOMER, horecaos id = the points account's id).
    -- Not a foreign key: provider_entity_mappings is keyed by id alone, and a reference that is
    -- not tenant-qualified is exactly the kind this repository's tenant-boundary sweep refuses.
    mapping_id uuid NOT NULL,
    -- The till's own customer id, copied from the mapping so the report can name a till customer
    -- without a join. Classified INTERNAL as every external id in provider_entity_mappings is. The
    -- phone the till returned to find the account is hashed by customers and discarded: it is
    -- stored nowhere here.
    external_customer_id varchar(255) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'MATCHED',

    last_pushed_version integer,
    last_pushed_balance_minor bigint,
    last_pushed_at timestamptz,

    last_pull_cursor varchar(255),
    last_pulled_at timestamptz,

    -- An OBSERVATION, beside the account it was read against. Never an input to the platform's
    -- balance (ADR 0171 Decision 1).
    last_till_balance_minor bigint,
    last_till_observed_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_pos_sync_state_account FOREIGN KEY (tenant_id, account_id)
        REFERENCES loyalty.accounts (tenant_id, id),
    CONSTRAINT fk_pos_sync_state_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT uq_pos_sync_state_account UNIQUE (tenant_id, binding_id, account_id),
    CONSTRAINT uq_pos_sync_state_external UNIQUE (tenant_id, binding_id, external_customer_id),
    CONSTRAINT uq_pos_sync_state_identity UNIQUE (tenant_id, id),
    CONSTRAINT ck_pos_sync_state_status CHECK (status IN ('MATCHED', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_pos_sync_state_version CHECK (version >= 1),
    CONSTRAINT ck_pos_sync_state_pushed CHECK (
        (last_pushed_version IS NULL) = (last_pushed_balance_minor IS NULL))
);

CREATE INDEX ix_pos_sync_state_binding ON loyalty.pos_balance_sync_state (tenant_id, binding_id, status);

COMMENT ON TABLE loyalty.pos_balance_sync_state IS
    'ADR 0171. One row per matched (binding, points account): the ADR 0026 mapping, the last version the platform pushed, the pull cursor, and the till balance last OBSERVED. Not a balance: loyalty.accounts.balance_minor stays the projection of loyalty.entries.';

-- ---------------------------------------------------------------------------
-- A till movement, whatever became of it
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty.pos_sync_movements (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    account_id uuid NOT NULL,
    run_id uuid,

    external_movement_id varchar(128) NOT NULL,
    kind varchar(8) NOT NULL,
    amount_minor bigint NOT NULL,
    occurred_at_till timestamptz,
    received_at timestamptz NOT NULL,

    status varchar(24) NOT NULL,
    applied_minor bigint NOT NULL DEFAULT 0,
    absorbed_minor bigint NOT NULL DEFAULT 0,

    -- The ledger entry an applied movement became, and the discrepancy a held or partly applied one
    -- opened. Plain ids rather than foreign keys: loyalty.entries is keyed by id alone, and the
    -- entry itself carries the idempotency key that makes this row's uniqueness real.
    entry_id uuid,
    discrepancy_id uuid,

    CONSTRAINT fk_pos_sync_movement_account FOREIGN KEY (tenant_id, account_id)
        REFERENCES loyalty.accounts (tenant_id, id),
    CONSTRAINT fk_pos_sync_movement_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    -- Idempotence is the schema's job and is asserted by a test, not trusted: a replayed or
    -- overlapping page of the till's feed imports each movement once.
    CONSTRAINT uq_pos_sync_movement_external UNIQUE (tenant_id, binding_id, external_movement_id),
    CONSTRAINT ck_pos_sync_movement_kind CHECK (kind IN ('EARN', 'SPEND')),
    CONSTRAINT ck_pos_sync_movement_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_pos_sync_movement_status CHECK (
        status IN ('APPLIED', 'HELD', 'PARTIALLY_APPLIED', 'REJECTED')),
    CONSTRAINT ck_pos_sync_movement_applied CHECK (
        applied_minor >= 0 AND absorbed_minor >= 0 AND applied_minor + absorbed_minor <= amount_minor)
);

CREATE INDEX ix_pos_sync_movement_account ON loyalty.pos_sync_movements (tenant_id, account_id, received_at DESC);
CREATE INDEX ix_pos_sync_movement_day ON loyalty.pos_sync_movements (tenant_id, binding_id, kind, received_at);

COMMENT ON TABLE loyalty.pos_sync_movements IS
    'ADR 0171. Every movement the till reported, whatever became of it: applied as an order-free ADJUSTMENT (POS_SYNC_EARN / POS_SYNC_SPEND, actor system:pos-sync:<bindingId>), held by a cap, partly applied with the rest absorbed by the brand, or rejected. Unique on the till''s own movement id.';

-- ---------------------------------------------------------------------------
-- What a person has to look at
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty.pos_sync_discrepancies (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    account_id uuid,
    run_id uuid,
    external_customer_id varchar(255),

    kind varchar(24) NOT NULL,

    platform_balance_minor bigint,
    till_balance_minor bigint,
    delta_minor bigint,
    -- For CREDIT_HELD and SPEND_EXCEEDS_BALANCE, the movement it came from.
    movement_id uuid,

    status varchar(16) NOT NULL DEFAULT 'OPEN',
    resolution_code varchar(16),
    resolution_reason varchar(500),
    resolved_by varchar(255),
    resolved_at timestamptz,
    approval_id uuid,

    observed_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT fk_pos_sync_discrepancy_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT fk_pos_sync_discrepancy_account FOREIGN KEY (tenant_id, account_id)
        REFERENCES loyalty.accounts (tenant_id, id),
    CONSTRAINT uq_pos_sync_discrepancy_identity UNIQUE (tenant_id, id),
    CONSTRAINT ck_pos_sync_discrepancy_kind CHECK (kind IN (
        'TILL_HIGHER', 'TILL_LOWER', 'TILL_ONLY', 'PLATFORM_ONLY', 'AMBIGUOUS_MATCH',
        'SPEND_EXCEEDS_BALANCE', 'CREDIT_HELD', 'RATIO_MISMATCH', 'NOT_CONSENTED', 'UNVERIFIABLE')),
    CONSTRAINT ck_pos_sync_discrepancy_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_pos_sync_discrepancy_resolution CHECK (
        resolution_code IS NULL OR resolution_code IN ('ACCEPT_TILL', 'ACCEPT_PLATFORM', 'ABSORB', 'SUSPEND')),
    -- Resolved is a state with a reason, a person and a time, stated as an equivalence so the mixed
    -- case is not reachable through three-valued logic.
    CONSTRAINT ck_pos_sync_discrepancy_resolved CHECK (
        (status = 'RESOLVED') = (resolution_code IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_pos_sync_discrepancy_version CHECK (version >= 1)
);

CREATE INDEX ix_pos_sync_discrepancy_open ON loyalty.pos_sync_discrepancies (tenant_id, brand_id, status, observed_at DESC);
CREATE INDEX ix_pos_sync_discrepancy_account ON loyalty.pos_sync_discrepancies (tenant_id, account_id, kind)
    WHERE status = 'OPEN';

COMMENT ON TABLE loyalty.pos_sync_discrepancies IS
    'ADR 0171. A difference between the platform and the till that a person resolves: the two balances and the delta, never silently corrected. Resolutions: ACCEPT_TILL (apply the difference as an adjustment, under approval), ACCEPT_PLATFORM (push it again), ABSORB (the brand carries it), SUSPEND (stop syncing that customer).';

-- No DELETE: these are evidence of what moved, and retention follows the ledger's.
GRANT SELECT, INSERT, UPDATE ON loyalty.pos_sync_runs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON loyalty.pos_balance_sync_state TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON loyalty.pos_sync_movements TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON loyalty.pos_sync_discrepancies TO horecaos_application;
