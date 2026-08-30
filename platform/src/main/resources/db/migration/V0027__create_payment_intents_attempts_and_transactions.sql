-- ADR 0013: payment intents, attempts, provider transactions, and the partner
-- fiscal seam.
--
-- Everything here exists because Click and Payme are not two instances of one
-- thing. Click is outbound-dominant with an inbound SHOP API callback that is the
-- only surface that credits an order; Payme is an inbound JSON-RPC server whose
-- endpoint is the whole integration. A schema shaped around either one alone gets
-- the other's transaction identity, reversal direction, and reconciliation
-- direction backwards, which is why the tables below name none of their
-- vocabulary and store all of it.
--
-- ---------------------------------------------------------------------------
-- Three tables, three different things
-- ---------------------------------------------------------------------------
--
-- An **intent** is what the order needs: an amount, a currency, a tender, and a
-- decision about whether the restaurant may be asked to confirm before the money
-- arrives. It has no provider vocabulary on it at all, and a cash intent has no
-- provider.
--
-- An **attempt** is one try at one provider through one merchant account. It
-- carries the identifiers that make an uncertain outcome resolvable — the id
-- HorecaOS minted before the mutating call, and the business date the resolver
-- needs as a path segment — and it carries the provider's own state verbatim,
-- beside HorecaOS's, never instead of it.
--
-- A **transaction** is what the provider says happened, appended once and never
-- rewritten.
--
-- Collapsing any two of them loses the sentence the operations console has to be
-- able to say: "we tried Click, the response was lost, we asked again and it had
-- in fact charged". Under one row that sentence is a contradiction; under three
-- it is a state, a resolver, and an appended capture.
--
-- ---------------------------------------------------------------------------
-- Money
-- ---------------------------------------------------------------------------
--
-- Every `*_amount_minor` column here is **whole som**, matching ADR 0018. The
-- providers disagree with that and with each other: Click's SHOP API amount and
-- its payment calls are som, Click's fiscalization Price and VAT are tiyin, and
-- every Payme amount that has ever existed is tiyin. The multiplication by 100
-- therefore happens in exactly one place — `TiyinAmount.of(SomAmount)` in
-- `payments.domain` — and never in SQL. No column below is ever tiyin; if one
-- ever needs to be, it will say so in its name and in its comment, because a
-- factor-of-100 error on this path is a customer charged a hundred times the
-- price.

-- ---------------------------------------------------------------------------
-- Merchant bindings: the legal-entity dimension
-- ---------------------------------------------------------------------------
--
-- The restaurant's legal entity is the seller and the principal; HorecaOS is an
-- agent. That is not a preference, it is what the contracts force: neither
-- provider accepts a fiscal identity as a per-request field. Payme derives the
-- seller from the cashbox — its receipt carries a `merchant.organization`
-- populated from the cashbox with nothing supplied by the request — and Click
-- derives it from `service_id` plus `merchant_user_id`. One HorecaOS account serving
-- many restaurants would name HorecaOS as the seller on every receipt it issued.
--
-- So each legal entity holds its own Click service and its own Payme cashbox, and
-- an ADR 0026 binding that is tenant-scoped and singular is wrong for payments.
-- This table is the missing dimension. It sits in `payments` rather than in
-- `integration` deliberately: it is the payment-specific resolution rule, and
-- ADR 0013 explicitly declines to choose on ADR 0026's behalf whether the
-- registry's own uniqueness should eventually absorb it.
--
-- The legacy corroborates the shape by construction: `fin_agents` is
-- `UNIQUE (payment_method_id, vendor_id)` — one provider agent per vendor.
CREATE TABLE payments.merchant_bindings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- ADR 0038's `tenant.legal_entities` does not exist yet, so there is no
    -- foreign key to declare. Recorded as a plain uuid rather than left out,
    -- because the whole point of this table is that the dimension exists; adding
    -- the constraint when 0038 lands is a one-line forward migration, whereas
    -- discovering the dimension afterwards means re-pointing live payment
    -- configuration.
    legal_entity_id uuid NOT NULL,

    provider_type varchar(16) NOT NULL,

    -- The ADR 0026 installation and binding this resolves to. The adapter reads
    -- the base URL and the environment from the installation; nothing here
    -- duplicates them.
    installation_id uuid NOT NULL,
    binding_id uuid NOT NULL,

    -- Click's `service_id`, Payme's cashbox id, or a Telegram bot cashbox id.
    -- Non-sensitive: it identifies the account without authenticating to it.
    merchant_account_reference varchar(255) NOT NULL,

    -- Click's `merchant_user_id`, and null on providers that have no second
    -- account-identifying field. Also non-sensitive.
    merchant_user_reference varchar(255),

    secret_reference varchar(512) NOT NULL,

    -- Because Payme's Basic credential is per cashbox and Click's `secret_key` is
    -- per service, the credential identifies the account, and one shared callback
    -- URL cannot authenticate a request. The deployment therefore exposes an
    -- endpoint per binding and this is the segment that appears in its path. It
    -- is not a secret and must not be treated as one: it is guessable by design,
    -- and the signature or the Basic credential is what authenticates.
    callback_path_segment varchar(64) NOT NULL,

    -- Declared facts the operations console renders, not runtime exceptions. On
    -- Click a reversal is an outbound call that may be refused; on Payme there is
    -- no outbound refund at all, because the cabinet's refund button calls
    -- HorecaOS's CancelTransaction. An operator about to reject a paid order needs to
    -- be told which of those they are in before they reject it.
    supports_reversal boolean NOT NULL,
    supports_partner_fiscalization boolean NOT NULL,

    status varchar(16) NOT NULL,
    effective_from date NOT NULL,
    effective_until date,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_merchant_binding_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_merchant_binding_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT fk_merchant_binding_installation FOREIGN KEY (tenant_id, installation_id)
        REFERENCES integration.installations (tenant_id, id),
    CONSTRAINT uq_merchant_binding_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT ck_merchant_binding_provider CHECK (
        provider_type IN ('CLICK', 'PAYME', 'TELEGRAM')),
    CONSTRAINT ck_merchant_binding_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_merchant_binding_validity CHECK (
        effective_until IS NULL OR effective_until > effective_from),
    CONSTRAINT ck_merchant_binding_version CHECK (version >= 1),

    -- ADR 0028's reference format is `horecaos:{environment}:{category}:{owner}:{id}`
    -- and the category for a merchant credential is `provider_payment`. The
    -- pattern is here so that a Payme 36-character cashbox key or a Click
    -- `secret_key` pasted into this column is refused by the database rather than
    -- by a code review. A credential in a column is a credential in every backup,
    -- every replica, and every support export of that backup.
    CONSTRAINT ck_merchant_binding_secret_is_a_reference CHECK (
        secret_reference ~ '^horecaos:[^:]+:provider_payment:[^:]+:[^:]+$'),

    CONSTRAINT ck_merchant_binding_callback_segment CHECK (
        callback_path_segment ~ '^[a-z0-9][a-z0-9-]{7,63}$')
);

-- One live merchant account per legal entity per provider. This is the settled
-- topology written as a constraint: a second ACTIVE Click service for one legal
-- entity would make "which service issued this receipt" a question about row
-- order.
CREATE UNIQUE INDEX ux_merchant_binding_live_per_entity
    ON payments.merchant_bindings (tenant_id, legal_entity_id, provider_type)
    WHERE status = 'ACTIVE';

-- And the converse, which is the one that matters: a merchant account belongs to
-- exactly one legal entity, across every tenant on the platform. Without this,
-- the configuration mistake that the whole legal-entity dimension exists to
-- prevent — two restaurants pointed at one Payme cashbox, every receipt naming
-- the wrong seller — is still one INSERT away. It is deliberately not scoped by
-- tenant: a cashbox shared across two tenants is worse, not better.
CREATE UNIQUE INDEX ux_merchant_account_belongs_to_one_entity
    ON payments.merchant_bindings (provider_type, merchant_account_reference)
    WHERE status <> 'RETIRED';

CREATE UNIQUE INDEX ux_merchant_binding_callback_segment
    ON payments.merchant_bindings (callback_path_segment)
    WHERE status <> 'RETIRED';

COMMENT ON TABLE payments.merchant_bindings IS
    'ADR 0013. The payment binding, dimensioned by legal entity because neither provider takes a seller identity as a per-request field. One Click service and one Payme cashbox per legal entity, forced by the contracts rather than chosen.';
COMMENT ON COLUMN payments.merchant_bindings.legal_entity_id IS
    'ADR 0038 legal entity, resolved from the order location on the order business date. No foreign key yet: tenant.legal_entities is unbuilt.';
COMMENT ON COLUMN payments.merchant_bindings.secret_reference IS
    'ADR 0028 reference, never a value. Rotation changes what is behind this string and never this string, so no row here is rewritten when a key rotates.';
COMMENT ON COLUMN payments.merchant_bindings.supports_reversal IS
    'ADR 0013 capability declaration. False on Payme: a refund is initiated in Payme cabinet and arrives inbound as CancelTransaction, which HorecaOS can only veto with -31007.';

-- ---------------------------------------------------------------------------
-- Payment intents
-- ---------------------------------------------------------------------------
--
-- What the order needs, in HorecaOS's own terms. No provider vocabulary appears on
-- this table, and cash is a first-class tender here rather than the absence of
-- one: the legacy `payment_methods` seeds cash enabled, so it is the majority
-- tender in this market and an intent model that treats it as a gap would be
-- modelling most of the traffic as an exception.
CREATE TABLE payments.payment_intents (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- ADR 0046 puts a settlement and an ordered set of tenders between the order
    -- and the intents beneath it. That module is unbuilt, so the column is
    -- carried null and the "one live intent per order" index below steps aside
    -- for any row that has one. Split tender then needs no change to this table.
    tender_id uuid,

    -- The seller. Null until ADR 0038's assignment exists, and null on a cash
    -- intent that is never presented to a provider. It is snapshotted at
    -- creation rather than resolved on read, because a later change of a
    -- location's legal entity must not rewrite which entity sold a past order.
    legal_entity_id uuid,

    tender varchar(16) NOT NULL,
    payment_method_code varchar(32) NOT NULL,
    provider_type varchar(16),

    -- Whole som. See the money note at the top of this file.
    requested_amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,

    status varchar(16) NOT NULL,

    -- Whether the restaurant may be asked to confirm before the money has
    -- arrived. Asked of payments by ordering rather than assumed, because the
    -- answer depends on the tender: a cash order is confirmed and then paid at
    -- handover, and a card order is not.
    --
    -- One column and not two. An extra `required_before_confirmation` boolean was
    -- drafted here and removed: it is this value restated, and two columns for one
    -- fact have no defined winner when they disagree — which they would, the first
    -- time a tender's timing changed and only one of them was updated.
    capture_timing varchar(24) NOT NULL,

    idempotency_key varchar(255) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,

    CONSTRAINT fk_payment_intent_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_payment_intent_order FOREIGN KEY (order_id)
        REFERENCES ordering.orders (id),
    CONSTRAINT uq_payment_intent_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_payment_intent_idempotency UNIQUE (tenant_id, idempotency_key),

    CONSTRAINT ck_payment_intent_tender CHECK (tender IN ('CASH', 'PROVIDER')),
    CONSTRAINT ck_payment_intent_provider CHECK (
        provider_type IS NULL OR provider_type IN ('CLICK', 'PAYME', 'TELEGRAM')),

    -- Pair completeness stated as an equality rather than as a disjunction of two
    -- null tests, so there is no three-valued-logic hole: a cash intent has no
    -- provider and a provider intent has one, and nothing else passes.
    CONSTRAINT ck_payment_intent_cash_has_no_provider CHECK (
        (tender = 'CASH') = (provider_type IS NULL)),

    CONSTRAINT ck_payment_intent_status CHECK (
        status IN ('PENDING', 'AUTHORIZING', 'PAID', 'CANCELLED', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_payment_intent_capture_timing CHECK (
        capture_timing IN ('BEFORE_CONFIRMATION', 'ON_HANDOVER')),
    CONSTRAINT ck_payment_intent_amount CHECK (requested_amount_minor > 0),
    CONSTRAINT ck_payment_intent_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_intent_method_code CHECK (
        payment_method_code ~ '^[A-Z0-9][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_payment_intent_version CHECK (version >= 1),
    CONSTRAINT ck_payment_intent_settled CHECK (
        (settled_at IS NULL) = (status IN ('PENDING', 'AUTHORIZING')))
);

-- One live intent per order, until ADR 0046's tenders arrive. The predicate
-- names `tender_id IS NULL` so that split tender lifts the constraint by
-- populating a column rather than by dropping an index in a later migration.
CREATE UNIQUE INDEX ux_payment_intent_live_per_order
    ON payments.payment_intents (tenant_id, order_id)
    WHERE tender_id IS NULL AND status IN ('PENDING', 'AUTHORIZING', 'PAID');

CREATE INDEX ix_payment_intents_order
    ON payments.payment_intents (tenant_id, order_id);

CREATE INDEX ix_payment_intents_open
    ON payments.payment_intents (tenant_id, location_id, created_at DESC)
    WHERE status IN ('PENDING', 'AUTHORIZING');

COMMENT ON TABLE payments.payment_intents IS
    'ADR 0013. What the order needs paid, in HorecaOS terms. An intent is not an attempt: it survives every attempt made against it.';
COMMENT ON COLUMN payments.payment_intents.requested_amount_minor IS
    'ADR 0018 minor units, which for UZS are whole som. Never tiyin. The multiplication by 100 lives in TiyinAmount.of(SomAmount) and nowhere else.';
COMMENT ON COLUMN payments.payment_intents.tender_id IS
    'ADR 0046 placeholder. Null today; a populated value lifts the one-live-intent-per-order index, which is how split tender arrives without changing this table.';
COMMENT ON COLUMN payments.payment_intents.capture_timing IS
    'BEFORE_CONFIRMATION for a provider tender that must clear before the restaurant is asked; ON_HANDOVER for cash, which is confirmed first and collected at the door.';

-- ---------------------------------------------------------------------------
-- Payment attempts
-- ---------------------------------------------------------------------------
--
-- One try at one provider through one merchant account, and the place where
-- uncertainty is a state rather than an absence.
--
-- Two columns exist purely so that a lost response is resolvable, and both must
-- be written **before** the mutating call rather than after it:
--
--   `merchant_trans_id` is HorecaOS's own id. On Click it is the join key the
--   callback carries and the only thing `status_by_mti` can be asked about;
--   Click's MERCHANT API has no idempotency key on any call, so this is the
--   entire recovery mechanism. On Payme it is the `account.order_id` the checkout
--   link carries, and it is deliberately opaque and non-sequential: sequential
--   integers would let anyone enumerate other customers' orders through
--   `CheckPerformTransaction`, which is unauthenticated from the customer's side.
--
--   `business_date` is snapshotted at initiation because Click's
--   `payment/status_by_mti` carries a trailing `YYYY-MM-DD` whose meaning and
--   timezone Click does not document. A wrong date reads as "no payment found",
--   which is exactly the answer that would make a retry look safe — so on this
--   provider absence of evidence is never evidence of absence, and a "not found"
--   never unblocks a second charge.
CREATE TABLE payments.payment_attempts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    provider_type varchar(16) NOT NULL,
    merchant_binding_id uuid NOT NULL,

    merchant_trans_id varchar(64) NOT NULL,
    business_date date NOT NULL,

    -- Click's `payment_id`, or Payme's 24-character transaction id. Identity runs
    -- in opposite directions on the two providers — Payme mints it and hands it
    -- over, Click hands back an id derived from one HorecaOS minted — so this column
    -- is null until the provider has named something, and the port may not assume
    -- either direction.
    external_payment_id varchar(64),

    -- Click's `click_paydoc_id`, which appears in neither of Click's two
    -- signatures and is evidence only.
    external_document_id varchar(64),

    requested_amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,

    status varchar(16) NOT NULL,

    presentation_kind varchar(24),
    presented_at timestamptz,

    -- The provider's own vocabulary, verbatim, beside HorecaOS's state and never
    -- instead of it. Payme's signed numeric state carries "cancelled" in its sign
    -- and how far the transaction got in its magnitude; Click has no equivalent,
    -- no expiry state, and no provider-side reservation state at all, so adopting
    -- either vocabulary would leave half of the other provider unrepresentable.
    -- Stored as text because these are evidence, not enumerations HorecaOS owns.
    provider_state varchar(32),
    provider_reason varchar(32),
    provider_state_recorded_at timestamptz,

    -- Payme's `params.time`: the moment Payme created the transaction, which is
    -- the only clock the twelve-hour expiry may be measured from. Payme's own Java
    -- template measures it from the merchant's creation time instead, which is
    -- wrong by however far the two clocks and the two events are apart.
    provider_created_at timestamptz,
    expires_at timestamptz,

    failure_code varchar(64),

    -- Uncertainty, as a state with an obligation attached. A create whose response
    -- was lost is neither succeeded nor failed; it is a question with a named
    -- resolver, a first-observed time, and a deadline after which it becomes an
    -- operations exception. The delivery adapters already hold this discipline for
    -- a double booking; here the failure mode is a double charge on a customer's
    -- card, which is the one that ends the relationship.
    uncertain_since timestamptz,
    uncertain_resolver varchar(32),
    uncertain_deadline timestamptz,
    uncertain_resolution_attempts integer NOT NULL DEFAULT 0,
    uncertain_resolved_at timestamptz,

    reversal_reason varchar(128),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,

    CONSTRAINT fk_payment_attempt_intent FOREIGN KEY (tenant_id, intent_id)
        REFERENCES payments.payment_intents (tenant_id, id),
    CONSTRAINT fk_payment_attempt_binding FOREIGN KEY (tenant_id, merchant_binding_id)
        REFERENCES payments.merchant_bindings (tenant_id, id),
    CONSTRAINT uq_payment_attempt_tenant_id UNIQUE (tenant_id, id),

    -- The resolver key. Unique per provider so that `status_by_mti` and a Payme
    -- `account.order_id` lookup both have exactly one row to land on.
    CONSTRAINT uq_payment_attempt_merchant_trans
        UNIQUE (tenant_id, provider_type, merchant_trans_id),

    CONSTRAINT ck_payment_attempt_provider CHECK (
        provider_type IN ('CLICK', 'PAYME', 'TELEGRAM')),
    CONSTRAINT ck_payment_attempt_status CHECK (status IN (
        'INITIATED', 'PRESENTED', 'RESERVED', 'CAPTURED',
        'CANCELLED', 'EXPIRED', 'REVERSED', 'FAILED', 'UNCERTAIN')),
    CONSTRAINT ck_payment_attempt_presentation CHECK (
        presentation_kind IS NULL OR presentation_kind IN (
            'PAYMENT_LINK', 'CARD_FORM', 'QR', 'INVOICE_PUSH', 'TELEGRAM_INVOICE')),
    CONSTRAINT ck_payment_attempt_resolver CHECK (
        uncertain_resolver IS NULL OR uncertain_resolver IN (
            'CLICK_STATUS_BY_MTI', 'PAYME_CHECK_TRANSACTION', 'OPERATIONS_EXCEPTION')),
    CONSTRAINT ck_payment_attempt_amount CHECK (requested_amount_minor > 0),
    CONSTRAINT ck_payment_attempt_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_attempt_version CHECK (version >= 1),
    CONSTRAINT ck_payment_attempt_resolution_attempts CHECK (
        uncertain_resolution_attempts >= 0),

    -- Pair completeness, three times, each stated as an equality between two null
    -- tests. `(a IS NULL AND b IS NULL) OR (a IS NOT NULL AND b IS NOT NULL)`
    -- would be the same claim with a hole in it, because a mixed pair makes both
    -- disjuncts unknown rather than false, and unknown passes a CHECK.
    CONSTRAINT ck_payment_attempt_presentation_pair CHECK (
        (presentation_kind IS NULL) = (presented_at IS NULL)),
    CONSTRAINT ck_payment_attempt_provider_state_pair CHECK (
        (provider_state IS NULL) = (provider_state_recorded_at IS NULL)),
    CONSTRAINT ck_payment_attempt_uncertainty_resolver_pair CHECK (
        (uncertain_since IS NULL) = (uncertain_resolver IS NULL)),
    CONSTRAINT ck_payment_attempt_uncertainty_deadline_pair CHECK (
        (uncertain_since IS NULL) = (uncertain_deadline IS NULL)),

    -- An UNCERTAIN attempt always carries its obligation, and an attempt that has
    -- left UNCERTAIN always records when it was settled. Both directions matter:
    -- the first stops an attempt sitting in UNCERTAIN with nothing chasing it, the
    -- second stops the resolution being invisible after the fact.
    CONSTRAINT ck_payment_attempt_uncertain_has_obligation CHECK (
        status <> 'UNCERTAIN' OR uncertain_since IS NOT NULL),
    CONSTRAINT ck_payment_attempt_uncertain_unresolved CHECK (
        status <> 'UNCERTAIN' OR uncertain_resolved_at IS NULL),
    CONSTRAINT ck_payment_attempt_resolution_implies_uncertainty CHECK (
        uncertain_resolved_at IS NULL OR uncertain_since IS NOT NULL),

    CONSTRAINT ck_payment_attempt_settled CHECK (
        (settled_at IS NULL) = (status IN ('INITIATED', 'PRESENTED', 'RESERVED', 'UNCERTAIN')))
);

-- The provider's own identifier, once it has named one. Partial, because it is
-- null for the whole window in which a Click charge is in flight and that window
-- is precisely when uncertainty happens.
CREATE UNIQUE INDEX ux_payment_attempt_external_payment
    ON payments.payment_attempts (tenant_id, provider_type, external_payment_id)
    WHERE external_payment_id IS NOT NULL;

-- The single-winner rule, and the reason it is an index rather than a service
-- method. At most one attempt per intent may hold money or hold a question about
-- money. A read-then-write in application code is the classic double-charge path,
-- and concurrent CreateTransaction calls for one order are exactly what it fails
-- under; this makes the second one a constraint violation instead.
CREATE UNIQUE INDEX ux_payment_attempt_live_per_intent
    ON payments.payment_attempts (intent_id)
    WHERE status IN ('RESERVED', 'CAPTURED', 'UNCERTAIN');

-- The operations queue. ADR 0013 exposes it as
-- GET /api/v1/operations/payment-attempts?status=UNCERTAIN, and it is ordered by
-- deadline because the ones about to become exceptions are the ones worth looking
-- at first.
CREATE INDEX ix_payment_attempts_uncertain
    ON payments.payment_attempts (tenant_id, uncertain_deadline)
    WHERE status = 'UNCERTAIN';

-- The expiry sweep. Payme's twelve-hour timeout never fires lazily for a checkout
-- the customer abandoned, and the reservation would otherwise hold stock forever.
CREATE INDEX ix_payment_attempts_expiring
    ON payments.payment_attempts (expires_at)
    WHERE status = 'RESERVED' AND expires_at IS NOT NULL;

CREATE INDEX ix_payment_attempts_intent
    ON payments.payment_attempts (tenant_id, intent_id, created_at DESC);

COMMENT ON TABLE payments.payment_attempts IS
    'ADR 0013. One try at one provider through one merchant account. UNCERTAIN is a state with a resolver and a deadline, never a failure and never a reason to retry.';
COMMENT ON COLUMN payments.payment_attempts.merchant_trans_id IS
    'HorecaOS own identifier, minted and committed before any mutating provider call. Click join key and status_by_mti argument; Payme account.order_id. Opaque and non-sequential, because CheckPerformTransaction is unauthenticated from the customer side.';
COMMENT ON COLUMN payments.payment_attempts.business_date IS
    'Snapshotted at initiation for Click status_by_mti trailing path segment, whose meaning and timezone Click does not document. A wrong date reads as no payment found, which is the answer that makes a retry look safe.';
COMMENT ON COLUMN payments.payment_attempts.provider_state IS
    'The provider own state verbatim: Payme signed numeric state as text, Click payment_status. Evidence beside the HorecaOS state and never the source of a transition.';
COMMENT ON COLUMN payments.payment_attempts.provider_created_at IS
    'Payme params.time. The twelve-hour expiry is measured from this and never from HorecaOS own creation time; Payme own Java template gets this wrong.';
COMMENT ON COLUMN payments.payment_attempts.uncertain_resolver IS
    'Which named procedure settles this: Click status_by_mti then payment/status, or Payme CheckTransaction. OPERATIONS_EXCEPTION once the automated path has given up.';

-- No column holds Click's `merchant_prepare_id`, and that is deliberate. Complete
-- carries exactly one prepare id, so it must be a deterministic function of the
-- order rather than a fresh value minted per Prepare call; a per-call id makes a
-- replayed Complete unresolvable. It is therefore computed, not stored.

-- ---------------------------------------------------------------------------
-- Payment transactions
-- ---------------------------------------------------------------------------
--
-- What the provider says happened. Append-only, and enforced as such by the grant
-- block at the end of this file rather than by a comment asking nicely: these rows
-- are the financial record, and a settlement dispute six weeks later is decided by
-- what is in them.
CREATE TABLE payments.payment_transactions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    intent_id uuid NOT NULL,
    attempt_id uuid NOT NULL,

    transaction_type varchar(16) NOT NULL,

    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,

    -- Click's `click_trans_id` or Payme's `params.id`, and a locally minted
    -- `LOCAL:{uuid}` for the one transaction type no provider originates — an
    -- expiry HorecaOS decided on its own, which Click is never told about because
    -- Click has no expiry state at all. NOT NULL so that the uniqueness below
    -- actually deduplicates: a nullable column would let every replay through.
    provider_reference varchar(128) NOT NULL,

    provider_state varchar(32),
    provider_reason varchar(32),

    -- Two clocks, deliberately both kept. `occurred_at` is the provider's, and it
    -- is what a settlement file will be matched against; `recorded_at` is HorecaOS's,
    -- and the gap between them is how a replayed callback is told apart from a
    -- second event.
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    -- ADR 0029. A payment payload is full of personal data, so the body is held
    -- behind a protected reference and never in a column, an event, a log line, a
    -- trace, or a metric.
    protected_request_reference varchar(512),
    protected_response_reference varchar(512),

    CONSTRAINT fk_payment_transaction_intent FOREIGN KEY (tenant_id, intent_id)
        REFERENCES payments.payment_intents (tenant_id, id),
    CONSTRAINT fk_payment_transaction_attempt FOREIGN KEY (tenant_id, attempt_id)
        REFERENCES payments.payment_attempts (tenant_id, id),

    -- A replayed Complete, a repeated PerformTransaction, and a second delivery of
    -- one callback all land on this constraint and insert nothing. Payme sends
    -- every mutating method at least twice by design and requires the second
    -- response to match the first, so this is a contract requirement rather than a
    -- defensive nicety.
    CONSTRAINT uq_payment_transaction_occurrence
        UNIQUE (tenant_id, attempt_id, transaction_type, provider_reference),
    CONSTRAINT uq_payment_transaction_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT ck_payment_transaction_type CHECK (transaction_type IN (
        'RESERVE', 'CAPTURE', 'CANCEL', 'EXPIRE', 'REVERSE', 'REFUND')),
    CONSTRAINT ck_payment_transaction_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_payment_transaction_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_payment_transactions_attempt
    ON payments.payment_transactions (tenant_id, attempt_id, occurred_at);

CREATE INDEX ix_payment_transactions_intent
    ON payments.payment_transactions (tenant_id, intent_id, occurred_at);

COMMENT ON TABLE payments.payment_transactions IS
    'ADR 0013. Append-only record of what a provider says happened. Never updated: the grant block withholds UPDATE and DELETE.';
COMMENT ON COLUMN payments.payment_transactions.amount_minor IS
    'ADR 0018 minor units, whole som. The value as HorecaOS understands it, never the tiyin figure that crossed the wire.';
COMMENT ON COLUMN payments.payment_transactions.provider_reference IS
    'Click click_trans_id, Payme params.id, or LOCAL:{uuid} for a HorecaOS-decided expiry that no provider originated. Not null, because a nullable column would defeat the replay uniqueness.';

-- ---------------------------------------------------------------------------
-- Provider callbacks
-- ---------------------------------------------------------------------------
--
-- The ADR 0005 inbox, in the providers' own shape. It exists because the inbound
-- surface is the one that credits an order on both providers and neither of them
-- authenticates it the way the rest of the platform does: Click's SHOP API has no
-- auth header at all, and the MD5 `sign_string` is the only thing standing between
-- an anonymous form post and a credited order. Every arrival is recorded whether
-- or not its signature verified, because a burst of failures is the signal that
-- someone is probing or that a key rotation was missed.
CREATE TABLE payments.provider_callbacks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provider_type varchar(16) NOT NULL,
    merchant_binding_id uuid NOT NULL,

    callback_kind varchar(32) NOT NULL,

    -- Click `click_trans_id`, Payme `params.id`, or the JSON-RPC request id when
    -- the call carries no transaction of its own.
    provider_reference varchar(128) NOT NULL,

    request_body_hash char(64) NOT NULL,
    signature_valid boolean NOT NULL,

    attempt_id uuid,

    -- What HorecaOS answered, in the provider's own vocabulary: `0`, `-4`, `-9`,
    -- `-31008`, `-32504`. Recorded because both providers surface these in their
    -- support tooling and the argument about what was returned is otherwise
    -- unwinnable.
    response_code varchar(16) NOT NULL,

    received_at timestamptz NOT NULL DEFAULT now(),

    protected_request_reference varchar(512),
    protected_response_reference varchar(512),

    CONSTRAINT fk_provider_callback_binding FOREIGN KEY (tenant_id, merchant_binding_id)
        REFERENCES payments.merchant_bindings (tenant_id, id),
    CONSTRAINT fk_provider_callback_attempt FOREIGN KEY (tenant_id, attempt_id)
        REFERENCES payments.payment_attempts (tenant_id, id),

    -- The body hash is part of the key on purpose. Two callbacks with one
    -- reference and different bodies are a real event worth seeing, not a
    -- duplicate to swallow.
    CONSTRAINT uq_provider_callback_delivery UNIQUE (
        tenant_id, provider_type, callback_kind, provider_reference, request_body_hash),

    CONSTRAINT ck_provider_callback_provider CHECK (
        provider_type IN ('CLICK', 'PAYME', 'TELEGRAM')),
    CONSTRAINT ck_provider_callback_kind CHECK (callback_kind IN (
        'CLICK_PREPARE', 'CLICK_COMPLETE',
        'PAYME_RPC', 'PAYME_SET_FISCAL_DATA',
        'TELEGRAM_PRE_CHECKOUT', 'TELEGRAM_SUCCESSFUL_PAYMENT')),
    CONSTRAINT ck_provider_callback_body_hash CHECK (request_body_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_provider_callbacks_attempt
    ON payments.provider_callbacks (tenant_id, attempt_id, received_at);

-- The security signal. A burst of signature failures on one binding is either a
-- rotation HorecaOS missed or someone probing an endpoint whose only authentication
-- is an MD5 over a secret-prefixed concatenation.
CREATE INDEX ix_provider_callbacks_signature_failures
    ON payments.provider_callbacks (tenant_id, merchant_binding_id, received_at DESC)
    WHERE NOT signature_valid;

COMMENT ON TABLE payments.provider_callbacks IS
    'ADR 0005 inbox in provider shape. Every inbound arrival including the ones whose signature failed, because a burst of failures is the only warning available on an endpoint with no auth header.';

-- ---------------------------------------------------------------------------
-- Fiscal documents
-- ---------------------------------------------------------------------------
--
-- ADR 0038 owns fiscalization and will eventually own a richer `fiscal` schema
-- with per-location legal entities and marking codes behind it. What lives here is
-- the ADR 0013 partner seam: what Click and Payme return, when, and — for the
-- majority of this market's orders — the recorded fact that neither of them can
-- return anything at all. When 0038's schema lands these rows move to it; the
-- column shape below is deliberately the one it specifies so the move is a copy
-- rather than a redesign.
--
-- **No unique index on `order_id`, ever.** ADR 0038's "exactly one fiscal
-- document" is a statement about the obligation being resolved once, not about row
-- count. Payme's PERFORM and its CANCEL are two distinct receipts for one order by
-- the provider's own statement, and a split-tender order settled part on Click and
-- part in cash produces evidence on two paths. An implementer who reads "exactly
-- one" as a constraint writes the cancel data over the perform data and destroys
-- the only record that the sale was ever fiscalized.
CREATE TABLE payments.fiscal_documents (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    legal_entity_id uuid,

    payment_intent_id uuid,
    payment_transaction_id uuid,

    -- Null for a cash order, which has no provider and therefore no receipt.
    provider_type varchar(16),

    document_type varchar(16) NOT NULL,

    -- A correction is a second document linked to the first, never an overwrite of
    -- it.
    corrects_document_id uuid,

    status varchar(16) NOT NULL,

    -- Never null, in any status. A null would mean "unknown", and the entire point
    -- of the cash decision is that this is known and deliberate.
    reason_code varchar(64) NOT NULL,
    reason_note varchar(255) NOT NULL,

    -- Both providers return the same underlying object; Click packs it into one
    -- ofd.soliq.uz URL and Payme returns named fields plus a URL. The adapter
    -- parses Click's URL into these columns and stores both, because a URL points
    -- at a service HorecaOS does not run and an evidence record that is only a dead
    -- link is not evidence.
    external_receipt_id varchar(64),
    fiscal_sign varchar(64),
    terminal_id varchar(64),
    receipt_reference varchar(64),
    registered_at timestamptz,
    receipt_url varchar(512),

    provider_status_code varchar(32),
    provider_message varchar(512),

    -- ADR 0029. The exact `Items` array or `detail` object that was sent, and the
    -- response, behind protected references. The legacy `tax_receipts` table held
    -- payload, request, response and error as four separate columns, and it is the
    -- only thing that makes an incorrect receipt explicable a year later.
    protected_request_reference varchar(512),
    protected_response_reference varchar(512),

    submitted_at timestamptz,
    issued_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_fiscal_document_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_fiscal_document_order FOREIGN KEY (order_id)
        REFERENCES ordering.orders (id),
    CONSTRAINT fk_fiscal_document_intent FOREIGN KEY (tenant_id, payment_intent_id)
        REFERENCES payments.payment_intents (tenant_id, id),
    CONSTRAINT fk_fiscal_document_transaction FOREIGN KEY (tenant_id, payment_transaction_id)
        REFERENCES payments.payment_transactions (tenant_id, id),
    CONSTRAINT fk_fiscal_document_corrects FOREIGN KEY (corrects_document_id)
        REFERENCES payments.fiscal_documents (id),
    CONSTRAINT uq_fiscal_document_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT ck_fiscal_document_provider CHECK (
        provider_type IS NULL OR provider_type IN ('CLICK', 'PAYME', 'TELEGRAM')),
    CONSTRAINT ck_fiscal_document_type CHECK (
        document_type IN ('SALE', 'REFUND', 'CORRECTION')),
    CONSTRAINT ck_fiscal_document_status CHECK (status IN (
        'NOT_APPLICABLE', 'PENDING', 'SUBMITTED', 'ISSUED', 'FAILED')),
    CONSTRAINT ck_fiscal_document_version CHECK (version >= 1),

    -- NOT_APPLICABLE is a decision, not an empty row waiting to be filled. It has
    -- no provider, no submission, and no evidence, and saying so here is what stops
    -- a later code path quietly attaching a receipt to it.
    CONSTRAINT ck_fiscal_document_not_applicable_is_empty CHECK (
        status <> 'NOT_APPLICABLE' OR (
            provider_type IS NULL
            AND fiscal_sign IS NULL
            AND external_receipt_id IS NULL
            AND submitted_at IS NULL
            AND issued_at IS NULL)),

    -- And the converse: an issued document carries the two identifiers the tax
    -- authority recognises. A receipt with neither is not evidence of anything.
    CONSTRAINT ck_fiscal_document_issued_carries_evidence CHECK (
        status <> 'ISSUED' OR (
            fiscal_sign IS NOT NULL
            AND external_receipt_id IS NOT NULL
            AND issued_at IS NOT NULL
            AND provider_type IS NOT NULL)),

    CONSTRAINT ck_fiscal_document_correction_links CHECK (
        document_type = 'SALE' OR corrects_document_id IS NOT NULL),
    CONSTRAINT ck_fiscal_document_no_self_correction CHECK (
        corrects_document_id IS NULL OR corrects_document_id <> id)
);

CREATE INDEX ix_fiscal_documents_order
    ON payments.fiscal_documents (tenant_id, order_id, created_at);

-- The query the cash decision exists to make possible. Decided by the user on
-- 2026-08-22: a cash order receives no fiscal receipt from a payment provider,
-- because neither provider can produce one — Click's submit_items needs a CLICK
-- payment_id that does not exist, and Payme's fiscal data attaches to a Payme
-- receipt that does not exist. Legacy `payment_methods` has cash enabled, so this
-- is the majority tender rather than an edge case.
--
-- If the decision reverses, whether through ADR 0038's TERMINAL responsibility or
-- its OPERATOR path, the affected orders must be findable by a query on this
-- reason code rather than by inspecting orders one at a time. That is what this
-- index is for and it is the whole reason the state is a value rather than a null.
CREATE INDEX ix_fiscal_documents_not_applicable
    ON payments.fiscal_documents (tenant_id, reason_code, created_at)
    WHERE status = 'NOT_APPLICABLE';

-- The failure-after-capture path, reachable on both providers by different
-- mechanisms: Click fiscalizes strictly after capture and its submit_items can
-- fail, Payme fiscalizes from data fixed before payment and reports back
-- asynchronously through SetFiscalData that may simply never arrive.
CREATE INDEX ix_fiscal_documents_awaiting
    ON payments.fiscal_documents (tenant_id, submitted_at)
    WHERE status IN ('PENDING', 'SUBMITTED');

COMMENT ON TABLE payments.fiscal_documents IS
    'ADR 0013 partner fiscal seam, in the shape ADR 0038 specifies. Many-to-one with an order by design: a Payme PERFORM and its CANCEL are two receipts for one order, and a correction links to the sale rather than overwriting it.';
COMMENT ON COLUMN payments.fiscal_documents.status IS
    'NOT_APPLICABLE is a decision with a reason, never a null. A null would mean unknown, and the point of the cash decision is that this is known.';
COMMENT ON COLUMN payments.fiscal_documents.reason_code IS
    'Why this document is in this status. CASH_TENDER_NO_PROVIDER_FISCALIZATION for a cash order; PARTNER_FISCALIZED once a provider has issued.';
COMMENT ON COLUMN payments.fiscal_documents.fiscal_sign IS
    'The identifier the tax authority recognises. Click returns it inside the QR URL as &s=; Payme returns it as a named field. Parsed into a column either way, because a URL is a pointer to a service HorecaOS does not run.';
COMMENT ON COLUMN payments.fiscal_documents.corrects_document_id IS
    'A Payme CANCEL produces a second document of type REFUND linked here. Never an update of the sale row: overwriting it destroys the only record that the sale was fiscalized.';

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
--
-- `payment_transactions` and `provider_callbacks` receive INSERT and SELECT only.
-- They are the financial and evidentiary record, and withholding UPDATE at the
-- grant is a stronger statement than a comment saying they are append-only —
-- a mistaken UPDATE fails at the database rather than in review.
GRANT USAGE ON SCHEMA payments TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.merchant_bindings TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.payment_intents TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.payment_attempts TO horecaos_application;
GRANT SELECT, INSERT ON payments.payment_transactions TO horecaos_application;
GRANT SELECT, INSERT ON payments.provider_callbacks TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.fiscal_documents TO horecaos_application;
