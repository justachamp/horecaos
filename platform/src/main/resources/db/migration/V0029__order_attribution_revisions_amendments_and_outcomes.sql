-- ADR 0039: operator-assisted ordering, order amendment, and terminal outcome
-- accounting — the slice ADR 0039's own rollout section puts first.
--
-- What is here: attribution, the callback flag, change-due, the revision spine,
-- the tenant-managed outcome reason registry, the one-row-per-order outcome, and
-- the amendment aggregate carrying the three commands that move no money.
--
-- What is deliberately absent, and why it is absent rather than half-built:
--
--   ordering.bulk_operations / bulk_operation_items — no bulk action is driven
--   yet. Bulk courier assignment is ADR 0039's first supported action and it
--   needs ADR 0014, which does not exist. A table nobody writes reads as a
--   capability that exists.
--
--   The seven financial amendment commands (ADD_LINES, CHANGE_LINE_QUANTITY,
--   REMOVE_LINES, CHANGE_PAYMENT_METHOD, CHANGE_DELIVERY_ADDRESS,
--   CHANGE_FULFILLMENT_TIME, CHANGE_CONTACT) are named in ck_amendment_command_type
--   because the set is closed and code-owned like ck_order_status, and the
--   application refuses each of them by name. Naming them is not building them:
--   every one needs a consequence carried out in payment, inventory, fiscal and
--   POS, and three of those modules are unbuilt.
--
--   ordering.order_lines gains no UPDATE grant. Closing a line at a revision
--   boundary is what the first line-changing command needs, and the grant
--   arrives with it. Until then the append-only property V0022 argues for is
--   still true of every row in the table.
--
-- Three ideas run through what is here.
--
-- First: an amendment is not an edit. ADR 0019 made an order immutable and this
-- migration does not take that back. Every applied amendment appends a row to
-- ordering.order_revisions carrying a complete recomputed total; nothing rewrites
-- a revision that already exists, and revision 1 is the checkout snapshot for
-- ever.
--
-- Second: a cancelled order, a rejected one and an expired one are three
-- different commercial facts. The legacy system records one status and one
-- free-text cancel_reason for all three, which is why its cancellation funnel
-- cannot be computed. ordering.order_outcomes is one row per order naming the
-- terminal kind, the platform category, the tenant's reason at the version it
-- was picked at, the stock disposition and the party carrying the cost.
--
-- Third: attribution is written once. created_by records who entered the order,
-- accepted_by who moved it to CONFIRMED, and a trigger refuses to overwrite
-- either — a leaderboard a later action can rewrite measures nothing.
--
-- All money is integer minor units with a currency. For UZS a minor unit is a
-- whole som.

-- ------------------------------------------------- attribution and operator fields

ALTER TABLE ordering.orders
    -- The revision the order currently reads at. Every read of lines or money
    -- pins a revision; a join that forgets to double-counts, and the mistake
    -- stays invisible until someone reconciles a total by hand.
    ADD COLUMN current_revision integer NOT NULL DEFAULT 1,

    ADD COLUMN created_by_actor_type varchar(16),
    ADD COLUMN created_by_actor_id varchar(255),
    ADD COLUMN accepted_by_actor_type varchar(16),
    ADD COLUMN accepted_by_actor_id varchar(255),
    ADD COLUMN accepted_at timestamptz,

    -- A customer-service concern, kept out of the commercial state machine
    -- ADR 0019 keeps small. Cleared only by an operator, who is recorded.
    ADD COLUMN callback_requested boolean NOT NULL DEFAULT false,
    ADD COLUMN callback_resolved_at timestamptz,
    ADD COLUMN callback_resolved_by varchar(255),

    -- Сдача. An operational hint, never a payment transaction: no money has
    -- moved, so change owed is recomputed as (tendered - total) on every read
    -- rather than stored. Whole som per ADR 0018.
    ADD COLUMN cash_tendered_expected_minor bigint,

    -- Operator to kitchen. Not encrypted, and the asymmetry is the point: the
    -- customer's own words live in order_lines.note_encrypted and
    -- order_customer_snapshots.delivery_instructions_encrypted under ADR 0029,
    -- and rendering both in one undifferentiated list is how a customer's note
    -- ends up on a screenshot in a group chat.
    ADD COLUMN kitchen_note varchar(1000);

ALTER TABLE ordering.orders
    ADD CONSTRAINT ck_order_current_revision CHECK (current_revision >= 1),
    ADD CONSTRAINT ck_order_created_by_actor CHECK (
        created_by_actor_type IS NULL
        OR created_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER', 'CUSTOMER')),
    ADD CONSTRAINT ck_order_accepted_by_actor CHECK (
        accepted_by_actor_type IS NULL
        OR accepted_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER', 'CUSTOMER')),
    -- Who accepted and when travel together. An accepted_at with nobody's name
    -- on it cannot answer "who confirmed this order", which is the first
    -- question asked after a disputed one.
    ADD CONSTRAINT ck_order_accepted_pair CHECK (
        (accepted_at IS NULL) = (accepted_by_actor_type IS NULL)),
    ADD CONSTRAINT ck_order_callback_resolution_pair CHECK (
        (callback_resolved_at IS NULL) = (callback_resolved_by IS NULL)),
    -- An open callback has no resolution. Requesting one again after it was
    -- resolved is new work, and clears the previous resolution rather than
    -- leaving a row that claims to be both open and settled.
    ADD CONSTRAINT ck_order_callback_open CHECK (
        NOT callback_requested OR callback_resolved_at IS NULL),
    -- Tendered may be less than the total. An amendment that pushes the total
    -- past it raises CASH_TENDERED_INSUFFICIENT, which the operator acknowledges
    -- rather than being blocked by: the customer can hand over more.
    ADD CONSTRAINT ck_order_cash_tendered CHECK (
        cash_tendered_expected_minor IS NULL OR cash_tendered_expected_minor >= 0);

CREATE INDEX ix_orders_callback_open ON ordering.orders (tenant_id, location_id)
    WHERE callback_requested;

CREATE INDEX ix_orders_created_by ON ordering.orders (tenant_id, created_by_actor_id, created_at DESC)
    WHERE created_by_actor_id IS NOT NULL;

COMMENT ON COLUMN ordering.orders.current_revision IS
    'ADR 0039. The revision every read of lines and money must pin. Revision 1 is the ADR 0019 checkout snapshot.';
COMMENT ON COLUMN ordering.orders.created_by_actor_type IS
    'ADR 0039. Who entered the order — an operator, the storefront acting for the customer, or a machine principal. Null only on orders created before this migration. Never overwritten; the trigger below enforces that against a hand-written statement.';
COMMENT ON COLUMN ordering.orders.accepted_by_actor_type IS
    'ADR 0039. Who moved the order to CONFIRMED, which is a different act from entering it and frequently a different person. An auto-confirm records SYSTEM_JOB rather than leaving the cell empty.';
COMMENT ON COLUMN ordering.orders.cash_tendered_expected_minor IS
    'ADR 0039. What the customer says they will hand over, in whole som. An operational hint: no money has moved, so this is never a payment transaction and change owed is derived rather than stored.';
COMMENT ON COLUMN ordering.orders.kitchen_note IS
    'ADR 0039 SET_KITCHEN_NOTE. Operator to kitchen, and therefore not personal data. The current value only; what each amendment set is in order_amendment_commands.payload_json.';

-- Attribution is a fact about the past, not a field.
--
-- The application writes each of these once. This refuses the UPDATE that a
-- later maintainer would reach for when asked to "fix" who took an order, and
-- refuses it where it cannot be bypassed, because the value of the column is
-- entirely that nothing can rewrite it.
CREATE OR REPLACE FUNCTION ordering.reject_attribution_rewrite() RETURNS trigger AS $$
BEGIN
    IF OLD.created_by_actor_type IS NOT NULL
        AND (NEW.created_by_actor_type IS DISTINCT FROM OLD.created_by_actor_type
             OR NEW.created_by_actor_id IS DISTINCT FROM OLD.created_by_actor_id) THEN
        RAISE EXCEPTION
            'Who entered an order is written once and never rewritten (ADR 0039)';
    END IF;
    IF OLD.accepted_at IS NOT NULL
        AND (NEW.accepted_by_actor_type IS DISTINCT FROM OLD.accepted_by_actor_type
             OR NEW.accepted_by_actor_id IS DISTINCT FROM OLD.accepted_by_actor_id
             OR NEW.accepted_at IS DISTINCT FROM OLD.accepted_at) THEN
        RAISE EXCEPTION
            'Who accepted an order is written once and never rewritten (ADR 0039)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_orders_attribution_written_once
    BEFORE UPDATE ON ordering.orders
    FOR EACH ROW EXECUTE FUNCTION ordering.reject_attribution_rewrite();

-- ------------------------------------------------------------------ revisions

-- One row per revision of one order, append-only.
--
-- Revision 1 is the ADR 0019 checkout snapshot and can only be that; every later
-- revision is produced by exactly one applied amendment. The five money figures
-- are copied rather than derived, so a report pinned to revision 1 still
-- reconciles to the original total after ten amendments.
CREATE TABLE ordering.order_revisions (
    order_id uuid NOT NULL,
    revision integer NOT NULL,
    tenant_id uuid NOT NULL,
    source varchar(16) NOT NULL,
    amendment_id uuid,

    -- The quote this revision's totals came from. A non-financial amendment
    -- carries its predecessor's quote forward unchanged: the basket did not
    -- change, so re-accepting a quote would be a second acceptance of one price.
    pricing_quote_id uuid NOT NULL,
    pricing_context_hash varchar(64) NOT NULL,

    currency char(3) NOT NULL,
    subtotal_minor bigint NOT NULL,
    tax_minor bigint NOT NULL,
    discount_minor bigint NOT NULL DEFAULT 0,
    fee_minor bigint NOT NULL DEFAULT 0,
    total_minor bigint NOT NULL,
    -- Signed, against the predecessor. Zero on revision 1 and on every amendment
    -- that changes no money. This is the figure the operator reads to the
    -- customer, which is why it is stored rather than recomputed by subtracting
    -- two rows a report may have filtered differently.
    delta_total_minor bigint NOT NULL DEFAULT 0,

    created_by_actor_type varchar(16),
    created_by_actor_id varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (order_id, revision),
    CONSTRAINT ck_order_revision_number CHECK (revision >= 1),
    CONSTRAINT ck_order_revision_source CHECK (source IN ('CHECKOUT', 'AMENDMENT')),
    -- Revision 1 is the checkout snapshot, and nothing else is. Stated as an
    -- equivalence in both directions so neither a second CHECKOUT revision nor
    -- an AMENDMENT numbered 1 can be written.
    CONSTRAINT ck_order_revision_first CHECK ((revision = 1) = (source = 'CHECKOUT')),
    CONSTRAINT ck_order_revision_amendment CHECK (
        (source = 'AMENDMENT') = (amendment_id IS NOT NULL)),
    CONSTRAINT ck_order_revision_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_order_revision_amounts CHECK (
        subtotal_minor >= 0 AND tax_minor >= 0 AND discount_minor >= 0
        AND fee_minor >= 0 AND total_minor >= 0),
    -- The same reconciliation ordering.orders carries. A revision whose parts do
    -- not sum to its total cannot be fiscalized and cannot be explained.
    CONSTRAINT ck_order_revision_reconciles CHECK (
        total_minor = subtotal_minor + tax_minor + fee_minor - discount_minor),
    CONSTRAINT ck_order_revision_actor CHECK (
        created_by_actor_type IS NULL
        OR created_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER', 'CUSTOMER')),
    CONSTRAINT fk_order_revision_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    -- The key order_lines points at, so a line cannot claim a revision of
    -- another order.
    CONSTRAINT uq_order_revision_identity UNIQUE (order_id, revision, tenant_id)
);

CREATE INDEX ix_order_revisions_order ON ordering.order_revisions (order_id, revision DESC);

COMMENT ON TABLE ordering.order_revisions IS
    'ADR 0039 append-only order revisions. Revision 1 is the ADR 0019 checkout snapshot and is byte-identical for ever; each applied amendment appends one.';

-- Every order that already exists gets its revision 1, from its own stored
-- totals, before anything is allowed to reference a revision. Deriving it rather
-- than defaulting it is what makes the backfilled row reconcile: the five money
-- figures are the ones the order was placed at, so a report pinned to revision 1
-- gives the same answer for an order taken before this migration as for one
-- taken after it.
INSERT INTO ordering.order_revisions (
    order_id, revision, tenant_id, source, pricing_quote_id, pricing_context_hash,
    currency, subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
    delta_total_minor, created_by_actor_type, created_by_actor_id, created_at)
SELECT id, 1, tenant_id, 'CHECKOUT', pricing_quote_id, pricing_context_hash,
       currency, subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
       0, NULL, NULL, created_at
FROM ordering.orders;

-- Lines become revision-scoped.
--
-- revision_from is written from the first release; revision_to is null on every
-- row today because no command that closes a line is built. The read predicate
-- is written to honour it from the start — `revision_from <= :r AND (revision_to
-- IS NULL OR revision_to > :r)` — so the query does not have to change when the
-- first line-changing command lands, only the grant.
ALTER TABLE ordering.order_lines
    ADD COLUMN revision_from integer NOT NULL DEFAULT 1,
    ADD COLUMN revision_to integer;

ALTER TABLE ordering.order_lines
    ADD CONSTRAINT ck_order_line_revision_range CHECK (
        revision_from >= 1 AND (revision_to IS NULL OR revision_to > revision_from)),
    ADD CONSTRAINT fk_order_line_revision FOREIGN KEY (order_id, revision_from, tenant_id)
        REFERENCES ordering.order_revisions (order_id, revision, tenant_id);

CREATE INDEX ix_order_lines_live ON ordering.order_lines (order_id, revision_from)
    WHERE revision_to IS NULL;

COMMENT ON COLUMN ordering.order_lines.revision_to IS
    'ADR 0039. The revision at which this line stopped being part of the order; null while it is still current. Null on every row today: no amendment command that closes a line is built, and ordering.order_lines deliberately holds no UPDATE grant until one is.';

-- uq_order_line_number stays as V0022 wrote it, and survives revisioning
-- unchanged: a line number is allocated once per order and never reused. A
-- future quantity change closes the old line at revision N and appends a new one
-- with the next number rather than editing the amount in place.

-- ------------------------------------------------------- outcome reason registry

-- Tenant-managed, versioned, and carrying a platform-owned category.
--
-- Fifty near-duplicate tenant reasons are inevitable — «Не дозвонились»,
-- «Клиент не отвечает», «Нет ответа» — and cross-tenant reporting cannot rest on
-- free text. system_category is the closed set every report groups by; the
-- tenant's own wording is what the operator picks from.
CREATE TABLE ordering.order_outcome_reasons (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    kind varchar(16) NOT NULL,
    system_category varchar(48) NOT NULL,
    -- What the operator sees in the list. «Не дозвонились» is a working
    -- shorthand and is never shown to a customer; see the texts table.
    internal_name varchar(120) NOT NULL,

    -- Cancellation only. Each answers a question the operator must not be asked
    -- under pressure: which ADR 0017 movement this writes, who carries the cost
    -- in the ADR 0043 reports, and what the default ADR 0013 refund posture is.
    stock_disposition varchar(24),
    liability_party varchar(24),
    customer_refund varchar(16),

    -- Completion only, validated on use. Without it «Самовывоз выполнен» lands
    -- on a delivery order and both the courier SLA report and the
    -- external-logistics settlement quietly lose that order.
    allowed_fulfillment_modes varchar(16)[],

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_outcome_reason_kind CHECK (kind IN ('CANCELLATION', 'COMPLETION')),
    CONSTRAINT ck_outcome_reason_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_outcome_reason_version CHECK (version >= 1),
    CONSTRAINT ck_outcome_reason_internal_name CHECK (length(btrim(internal_name)) > 0),
    -- The category is closed and belongs to the kind. A completion reason
    -- categorised as ITEM_UNAVAILABLE would sit in the cancellation funnel.
    CONSTRAINT ck_outcome_reason_category CHECK (
        CASE kind
            WHEN 'CANCELLATION' THEN system_category IN (
                'CUSTOMER_CANCELLED', 'CUSTOMER_UNREACHABLE', 'CUSTOMER_NO_SHOW',
                'RESTAURANT_REFUSED', 'ITEM_UNAVAILABLE', 'KITCHEN_CAPACITY',
                'DELIVERY_FAILED', 'COURIER_UNAVAILABLE', 'ADDRESS_UNSERVICEABLE',
                'PAYMENT_NOT_RECEIVED', 'DUPLICATE_ORDER', 'TEST_ORDER',
                'SUSPECTED_FRAUD', 'PRICING_ERROR', 'OTHER')
            WHEN 'COMPLETION' THEN system_category IN (
                'DELIVERED_OWN_COURIER', 'DELIVERED_PARTNER_COURIER',
                'COLLECTED_BY_CUSTOMER', 'SERVED_IN_HOUSE', 'OTHER')
        END),
    -- Each stated as an equivalence, so neither a cancellation reason missing
    -- its disposition nor a completion reason carrying one can be stored.
    CONSTRAINT ck_outcome_reason_disposition CHECK (
        (kind = 'CANCELLATION') = (stock_disposition IS NOT NULL)),
    CONSTRAINT ck_outcome_reason_liability CHECK (
        (kind = 'CANCELLATION') = (liability_party IS NOT NULL)),
    CONSTRAINT ck_outcome_reason_refund CHECK (
        (kind = 'CANCELLATION') = (customer_refund IS NOT NULL)),
    CONSTRAINT ck_outcome_reason_modes CHECK (
        (kind = 'COMPLETION') = (allowed_fulfillment_modes IS NOT NULL)),
    CONSTRAINT ck_outcome_reason_disposition_value CHECK (
        stock_disposition IS NULL OR stock_disposition IN (
            'RELEASE', 'RETURN_TO_STOCK', 'WRITE_OFF', 'NO_EFFECT')),
    CONSTRAINT ck_outcome_reason_liability_value CHECK (
        liability_party IS NULL OR liability_party IN (
            'TENANT', 'CUSTOMER', 'COURIER_PARTNER', 'PLATFORM')),
    CONSTRAINT ck_outcome_reason_refund_value CHECK (
        customer_refund IS NULL OR customer_refund IN ('FULL', 'NONE', 'DISCRETIONARY')),
    CONSTRAINT ck_outcome_reason_modes_value CHECK (
        allowed_fulfillment_modes IS NULL
        OR (cardinality(allowed_fulfillment_modes) > 0
            AND allowed_fulfillment_modes <@ ARRAY['DELIVERY', 'PICKUP', 'DINE_IN']::varchar[])),
    CONSTRAINT fk_outcome_reason_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    -- The key order_outcomes points at, so an outcome cannot cite another
    -- tenant's reason.
    CONSTRAINT uq_outcome_reason_identity UNIQUE (id, tenant_id)
);

-- One live reason per name per kind. Archived rows keep their name so last
-- year's outcomes still resolve, but a tenant cannot hold two active reasons an
-- operator would have to tell apart by guessing.
CREATE UNIQUE INDEX ux_outcome_reason_active_name
    ON ordering.order_outcome_reasons (tenant_id, kind, lower(btrim(internal_name)))
    WHERE status = 'ACTIVE';

CREATE INDEX ix_outcome_reasons_tenant
    ON ordering.order_outcome_reasons (tenant_id, kind, status);

-- What the customer is told, which is a different statement from what the
-- operator picked.
--
-- «Не дозвонились» is what the operator needs in the list; the customer gets the
-- softened wording the tenant wrote. Publishing the internal name to a customer
-- is exactly what this split prevents, and it can only prevent it if the two are
-- different columns in different tables rather than one field used for both.
CREATE TABLE ordering.order_outcome_reason_texts (
    reason_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    customer_text varchar(400) NOT NULL,

    PRIMARY KEY (reason_id, locale),
    CONSTRAINT ck_outcome_reason_text_locale CHECK (locale IN ('ru', 'uz-Latn', 'en')),
    CONSTRAINT ck_outcome_reason_text_present CHECK (length(btrim(customer_text)) > 0),
    CONSTRAINT fk_outcome_reason_text_reason FOREIGN KEY (reason_id)
        REFERENCES ordering.order_outcome_reasons (id) ON DELETE CASCADE
);

COMMENT ON TABLE ordering.order_outcome_reasons IS
    'ADR 0039 tenant-managed, versioned cancellation and completion reasons. Every row carries a platform-owned system_category because cross-tenant reporting cannot rest on tenant free text.';
COMMENT ON COLUMN ordering.order_outcome_reasons.stock_disposition IS
    'ADR 0039, closing ADR 0017''s open input on cancellation restock. The disposition belongs to the reason an admin sets once, not to a checkbox an operator picks at 20:30 on a Friday — under pressure they pick whatever closes the dialog fastest and the write-off rate becomes noise.';

-- ------------------------------------------------------------ terminal outcomes

-- One row per order, written in the same transaction as the terminal transition.
--
-- The legacy system records `status = CANCELLED` plus a free-text cancel_reason
-- for a customer who changed their mind, a restaurant that refused, and an
-- approval that lapsed. Those are three commercial facts with three different
-- stock consequences and three different liable parties, and no report can
-- separate them afterwards. Reports read this table, never a status string.
CREATE TABLE ordering.order_outcomes (
    order_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    kind varchar(16) NOT NULL,
    system_category varchar(48) NOT NULL,

    -- The tenant's reason, at the version it was picked at, plus a snapshot of
    -- the whole row. The duplication is deliberate: renaming a reason in the
    -- registry next year must not rewrite last year's funnel.
    reason_id uuid,
    reason_version integer,
    reason_snapshot jsonb,

    actor_type varchar(16) NOT NULL,
    actor_id varchar(255),

    -- What was actually decided, not what the reason would have decided. The two
    -- differ before the inventory hold is committed, where cancellation always
    -- releases and the reason's disposition is ignored.
    stock_disposition varchar(24) NOT NULL,
    liability_party varchar(24),
    customer_refund varchar(16),
    -- Which of the two situations applied, recorded rather than re-derived: "we
    -- had not started" and "we already cooked it" are the difference, and the
    -- confirmation timestamp it is derived from could be reinterpreted later.
    reservation_committed boolean NOT NULL,

    -- Set when the consequence has been carried out. Both are null today: the
    -- ADR 0017 port ordering holds is deliberately three verbs — hold, commit,
    -- release — and writing a return or waste movement needs a fourth that
    -- belongs to inventory, while a refund is ADR 0013's.
    inventory_movement_id uuid,
    refund_id uuid,

    -- The operator's own words. Encrypted because nothing stops an operator
    -- typing a customer's phone number into a free-text box, and ADR 0029 does
    -- not have an exception for text somebody promised would be innocuous.
    note_encrypted text,

    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    -- PAYMENT_FAILED is terminal in the ADR 0019 machine and is named here so a
    -- row for it is recognisable rather than refused, but nothing writes one
    -- today: the payment-first checkout path is a known ADR 0013 gap and no
    -- order reaches that status.
    CONSTRAINT ck_order_outcome_kind CHECK (kind IN (
        'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED', 'PAYMENT_FAILED')),
    CONSTRAINT ck_order_outcome_category CHECK (
        CASE kind
            WHEN 'COMPLETED' THEN system_category IN (
                'DELIVERED_OWN_COURIER', 'DELIVERED_PARTNER_COURIER',
                'COLLECTED_BY_CUSTOMER', 'SERVED_IN_HOUSE', 'OTHER')
            WHEN 'EXPIRED' THEN system_category = 'APPROVAL_DEADLINE_LAPSED'
            WHEN 'PAYMENT_FAILED' THEN system_category = 'PAYMENT_NOT_RECEIVED'
            ELSE system_category IN (
                'CUSTOMER_CANCELLED', 'CUSTOMER_UNREACHABLE', 'CUSTOMER_NO_SHOW',
                'RESTAURANT_REFUSED', 'ITEM_UNAVAILABLE', 'KITCHEN_CAPACITY',
                'DELIVERY_FAILED', 'COURIER_UNAVAILABLE', 'ADDRESS_UNSERVICEABLE',
                'PAYMENT_NOT_RECEIVED', 'DUPLICATE_ORDER', 'TEST_ORDER',
                'SUSPECTED_FRAUD', 'PRICING_ERROR', 'OTHER')
        END),
    -- A cited reason travels with the version and the snapshot it was cited at.
    -- A reason id alone is a pointer at a row somebody may rename.
    CONSTRAINT ck_order_outcome_reason_pair CHECK (
        (reason_id IS NULL) = (reason_version IS NULL)),
    CONSTRAINT ck_order_outcome_reason_snapshot CHECK (
        (reason_id IS NULL) = (reason_snapshot IS NULL)),
    CONSTRAINT ck_order_outcome_actor CHECK (
        actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER', 'CUSTOMER')),
    CONSTRAINT ck_order_outcome_disposition CHECK (
        stock_disposition IN ('RELEASE', 'RETURN_TO_STOCK', 'WRITE_OFF', 'NO_EFFECT')),
    -- A completed order took the stock it was always going to take at
    -- confirmation. Nothing further moves, and nobody is out of pocket.
    CONSTRAINT ck_order_outcome_completed CHECK (
        kind <> 'COMPLETED'
        OR (stock_disposition = 'NO_EFFECT' AND liability_party IS NULL
            AND customer_refund IS NULL)),
    CONSTRAINT ck_order_outcome_liability_value CHECK (
        liability_party IS NULL OR liability_party IN (
            'TENANT', 'CUSTOMER', 'COURIER_PARTNER', 'PLATFORM')),
    CONSTRAINT ck_order_outcome_refund_value CHECK (
        customer_refund IS NULL OR customer_refund IN ('FULL', 'NONE', 'DISCRETIONARY')),
    -- ADR 0017: a cancellation never reopens a committed reservation, so a
    -- disposition that moves stock can only follow one that was committed, and
    -- an uncommitted hold can only be released.
    CONSTRAINT ck_order_outcome_disposition_agrees CHECK (
        reservation_committed OR stock_disposition = 'RELEASE'),
    CONSTRAINT fk_order_outcome_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_order_outcome_reason FOREIGN KEY (reason_id, tenant_id)
        REFERENCES ordering.order_outcome_reasons (id, tenant_id)
);

CREATE INDEX ix_order_outcomes_reporting
    ON ordering.order_outcomes (tenant_id, kind, system_category, occurred_at DESC);

COMMENT ON TABLE ordering.order_outcomes IS
    'ADR 0039. Exactly one terminal outcome per order, written in the same transaction as the transition. A cancelled order, a rejected one and an expired one are three commercial facts; the legacy system records one status for all three.';
COMMENT ON COLUMN ordering.order_outcomes.reason_snapshot IS
    'ADR 0039 deliberate duplication. Renaming a reason in the registry next year must not rewrite last year''s funnel.';
COMMENT ON COLUMN ordering.order_outcomes.reservation_committed IS
    'ADR 0039, closing ADR 0017''s open input. False means the hold had not been committed and the cancellation released it whatever the reason said; true means the disposition on the reason decided.';

-- ----------------------------------------------------------------- amendments

-- An amendment is a closed set of intent-named commands with a declared
-- consequence, not a patch.
--
-- ADR 0039 rejects a generic field-level diff by name: a diff cannot separate
-- "added a dessert" from "corrected the entrance number", yet the first
-- reprices, re-reserves and re-prints while the second does none of those. The
-- consequence has to be decidable from the command, not reconstructed by
-- comparing two documents at exactly the moment it must be certain.
CREATE TABLE ordering.order_amendments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    status varchar(32) NOT NULL,

    base_revision integer NOT NULL,
    applied_revision integer,

    -- Null for every command built today: none of them changes the basket, so
    -- none of them needs a new ADR 0018 quote. A financial command sets this.
    quote_id uuid,
    delta_total_minor bigint NOT NULL DEFAULT 0,

    requires_approval boolean NOT NULL DEFAULT false,
    approval_request_id uuid,

    -- The operator attests the customer agreed, on the call. An increased total
    -- cannot commit without it, because charging more than the customer agreed
    -- to is the failure this exists to prevent.
    confirmation_attested_by varchar(255),
    confirmation_attested_at timestamptz,
    confirmation_channel varchar(24),

    idempotency_key varchar(255) NOT NULL,
    -- ADR 0018's quote TTL. An amendment holding an inventory reservation
    -- against an unpriceable quote is the problem that TTL already solves.
    expires_at timestamptz NOT NULL,
    rejected_reason_code varchar(48),

    created_by_actor_type varchar(16) NOT NULL,
    created_by_actor_id varchar(255),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,

    CONSTRAINT ck_amendment_status CHECK (status IN (
        'DRAFT', 'PRICED', 'AWAITING_CUSTOMER_CONFIRMATION', 'AWAITING_PAYMENT',
        'APPLIED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_amendment_version CHECK (version >= 1),
    CONSTRAINT ck_amendment_base_revision CHECK (base_revision >= 1),
    CONSTRAINT ck_amendment_actor CHECK (
        created_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER', 'CUSTOMER')),
    CONSTRAINT ck_amendment_applied_revision CHECK (
        (status = 'APPLIED') = (applied_revision IS NOT NULL)),
    CONSTRAINT ck_amendment_applied_after_base CHECK (
        applied_revision IS NULL OR applied_revision > base_revision),
    CONSTRAINT ck_amendment_settled CHECK (
        (status IN ('APPLIED', 'REJECTED', 'EXPIRED')) = (settled_at IS NOT NULL)),
    CONSTRAINT ck_amendment_rejected_reason CHECK (
        (status = 'REJECTED') = (rejected_reason_code IS NOT NULL)),
    -- Who attested, when, and through which channel travel together. Two of the
    -- three prove nothing about the third.
    CONSTRAINT ck_amendment_confirmation_pair CHECK (
        (confirmation_attested_at IS NULL) = (confirmation_attested_by IS NULL)),
    CONSTRAINT ck_amendment_confirmation_channel CHECK (
        (confirmation_attested_at IS NULL) = (confirmation_channel IS NULL)),
    CONSTRAINT ck_amendment_confirmation_channel_value CHECK (
        confirmation_channel IS NULL
        OR confirmation_channel IN ('PHONE', 'CHAT', 'IN_PERSON', 'SELF_SERVICE')),
    -- The rule that makes the attestation worth recording. An applied amendment
    -- that raised the total carries the customer's recorded agreement, enforced
    -- here rather than only in the service that writes it.
    CONSTRAINT ck_amendment_increase_confirmed CHECK (
        status <> 'APPLIED' OR delta_total_minor <= 0
        OR confirmation_attested_at IS NOT NULL),
    -- And the same for the ADR 0027 four-eyes decision on a large decrease.
    CONSTRAINT ck_amendment_approval_recorded CHECK (
        NOT (status = 'APPLIED' AND requires_approval) OR approval_request_id IS NOT NULL),
    CONSTRAINT fk_amendment_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_amendment_quote FOREIGN KEY (quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id),
    CONSTRAINT uq_amendment_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_amendment_identity UNIQUE (id, tenant_id)
);

-- One open amendment per order. Two operators on one order is routine, and
-- without this both would build a change against the same base revision and the
-- second would silently lose the first one's work.
CREATE UNIQUE INDEX ux_order_amendment_open
    ON ordering.order_amendments (order_id)
    WHERE status IN ('DRAFT', 'PRICED', 'AWAITING_CUSTOMER_CONFIRMATION', 'AWAITING_PAYMENT');

CREATE INDEX ix_order_amendments_expiring ON ordering.order_amendments (expires_at)
    WHERE status IN ('DRAFT', 'PRICED', 'AWAITING_CUSTOMER_CONFIRMATION', 'AWAITING_PAYMENT');

CREATE INDEX ix_order_amendments_order ON ordering.order_amendments (order_id, created_at DESC);

-- The commands themselves, in the order the operator issued them.
--
-- The type is a closed set for the reason ADR 0039 gives: every entry has a
-- defined consequence in the quote, the inventory hold, the payment, the fiscal
-- receipt and the POS export, and a request fitting none of the ten needs an ADR
-- entry rather than a configuration change.
CREATE TABLE ordering.order_amendment_commands (
    amendment_id uuid NOT NULL,
    sequence integer NOT NULL,
    tenant_id uuid NOT NULL,
    command_type varchar(32) NOT NULL,
    -- What the command carries, whole. Nothing queries into it: it is read whole
    -- when the amendment is applied and kept afterwards as the record of what
    -- this revision changed.
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    rejected_reason_code varchar(48),

    PRIMARY KEY (amendment_id, sequence),
    CONSTRAINT ck_amendment_command_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_amendment_command_type CHECK (command_type IN (
        'ADD_LINES', 'CHANGE_LINE_QUANTITY', 'REMOVE_LINES', 'CHANGE_PAYMENT_METHOD',
        'CHANGE_DELIVERY_ADDRESS', 'CHANGE_FULFILLMENT_TIME', 'CHANGE_CONTACT',
        'SET_KITCHEN_NOTE', 'SET_CALLBACK_REQUESTED', 'SET_CASH_TENDERED')),
    CONSTRAINT fk_amendment_command_amendment FOREIGN KEY (amendment_id, tenant_id)
        REFERENCES ordering.order_amendments (id, tenant_id) ON DELETE CASCADE
);

COMMENT ON TABLE ordering.order_amendments IS
    'ADR 0039. One proposed change to one order, producing a new revision when applied. Never an edit to an existing revision and never a second order.';
COMMENT ON COLUMN ordering.order_amendment_commands.command_type IS
    'ADR 0039 closed command set. All ten are named because the set is code-owned like ck_order_status; only SET_KITCHEN_NOTE, SET_CALLBACK_REQUESTED and SET_CASH_TENDERED are built, and the application refuses the other seven by name.';

-- --------------------------------------------------------------------- grants

GRANT SELECT, INSERT ON ordering.order_revisions TO horecaos_application;
GRANT SELECT, INSERT ON ordering.order_outcomes TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON ordering.order_outcome_reasons TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON ordering.order_outcome_reason_texts TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON ordering.order_amendments TO horecaos_application;
GRANT SELECT, INSERT ON ordering.order_amendment_commands TO horecaos_application;

-- The grants are uneven, and the unevenness carries the same argument V0022 made.
--
-- order_revisions, order_outcomes and order_amendment_commands receive SELECT and
-- INSERT and nothing else. A revision is a financial fact, an outcome is the row
-- the cancellation funnel and the write-off report are computed from, and a
-- command is the record of what an operator asked for. None of the three may be
-- edited after the fact by any application path, and an UPDATE grant is all it
-- takes for a well-meant support fix to become an unrecorded rewrite.
--
-- order_amendments keeps UPDATE because status, version and the confirmation
-- columns are live state while the amendment is open, and no DELETE because a
-- rejected or expired amendment is evidence of what an operator tried.
--
-- The reason registry keeps DELETE for one reason: a reason created by mistake
-- and never cited by an outcome should be removable, and the foreign key from
-- order_outcomes refuses the delete the moment one has been.
--
-- ordering.order_lines receives no new grant. Closing a line at a revision
-- boundary needs UPDATE and no built command does that yet.
