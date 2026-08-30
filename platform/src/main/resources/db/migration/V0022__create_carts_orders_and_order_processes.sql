-- ADR 0019: cart, checkout, and order orchestration — the blocking subset named
-- in docs/minimum-viable-cutover.md ("cart, checkout, order snapshots, state
-- machine, approval, payment and inventory process managers").
--
-- POS export, delivery sourcing, scheduled orders, and amendment of a confirmed
-- order are deliberately absent. Their tables arrive with the decisions that own
-- them (ADR 0011/0012, 0014, 0039), because a table nobody writes is a schema
-- everybody has to reason about.
--
-- Two ideas run through everything below.
--
-- First: a cart is mutable and cheap, an order is immutable and expensive. Every
-- name, price, tax figure and modifier on an order is a *copy* taken at checkout.
-- A menu republish, a price-book edit, or a dish being renamed six months later
-- must not be able to rewrite what a customer agreed to pay. That is why the
-- order tables carry `_snapshot` columns instead of foreign keys to catalog rows.
--
-- Second: every outcome that two actors can race for is settled by a predicate
-- inside an UPDATE or by a unique index — never by reading a row and then writing
-- it. Two checkouts on one cart, two operators approving one order, and a
-- reservation expiring mid-checkout are all resolved here in the database rather
-- than by whichever application thread happened to be scheduled first.
--
-- All money is integer minor units with a currency, per the cutover rules.

-- ---------------------------------------------------------------------- carts

-- One cart belongs to one tenant, one brand, one location, and one channel.
--
-- Moving location does not move the cart: catalog, availability, tax, fee and
-- promise all change with the location, so carrying lines across would show a
-- customer prices that do not apply where they are now ordering. CartService
-- abandons the old cart and builds a new one; there is deliberately no
-- UPDATE path that changes location_id, and the trigger below enforces that even
-- against a hand-written statement.
CREATE TABLE ordering.carts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- ADR 0036: a registered channel, not free text. The channel decides which
    -- menu is priced, which price plane applies, which fulfilment modes are
    -- offered, and whether a guest may order at all.
    channel_id uuid NOT NULL,

    -- Exactly one owner. A cart owned by nobody cannot be found again by the
    -- person who filled it, and a cart owned by both an account and a guest
    -- reference is two carts wearing one row.
    customer_account_id uuid,
    -- ADR 0029: a keyed hash of the guest's device/session reference, never the
    -- reference itself. A cart is not worth storing a raw identifier for.
    guest_reference_hash varchar(64),

    fulfillment_mode varchar(16) NOT NULL,
    currency char(3) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',

    -- Set when the cart is priced. Both are cleared by any line edit: a cart
    -- whose contents changed is not the cart that was quoted, and leaving a stale
    -- quote id attached is how a customer checks out at yesterday's price.
    pricing_quote_id uuid,
    pricing_context_hash varchar(64),
    catalog_publication_id uuid,

    version integer NOT NULL DEFAULT 1,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    converted_order_id uuid,

    CONSTRAINT ck_cart_status CHECK (
        status IN ('ACTIVE', 'CHECKOUT_IN_PROGRESS', 'CONVERTED', 'EXPIRED', 'ABANDONED')
    ),
    CONSTRAINT ck_cart_fulfillment_mode CHECK (
        fulfillment_mode IN ('DELIVERY', 'PICKUP', 'DINE_IN')
    ),
    CONSTRAINT ck_cart_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_cart_owner CHECK (
        (customer_account_id IS NOT NULL) <> (guest_reference_hash IS NOT NULL)
    ),
    -- A quote reference without the hash it was taken at proves nothing: the
    -- hash is what checkout compares against, so the pair travels together.
    CONSTRAINT ck_cart_quote_pair CHECK (
        (pricing_quote_id IS NULL) = (pricing_context_hash IS NULL)
    ),
    CONSTRAINT ck_cart_converted CHECK (
        (status <> 'CONVERTED') OR (converted_order_id IS NOT NULL)
    ),
    CONSTRAINT ck_cart_version CHECK (version >= 1),
    CONSTRAINT fk_cart_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- Matched on (tenant_id, id) so a channel id belonging to another tenant
    -- cannot be attached to this cart. Matching on id alone would insert cleanly.
    CONSTRAINT fk_cart_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id),
    CONSTRAINT fk_cart_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_cart_quote FOREIGN KEY (pricing_quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id),
    CONSTRAINT fk_cart_publication FOREIGN KEY (catalog_publication_id)
        REFERENCES catalog.publications (id),
    -- The key cart_lines points at, so a line cannot be attached to another
    -- tenant's cart.
    CONSTRAINT uq_cart_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_carts_expiry ON ordering.carts (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_carts_customer ON ordering.carts (tenant_id, customer_account_id)
    WHERE customer_account_id IS NOT NULL;

-- A cart's location, brand, tenant and channel are its identity, not its state.
-- The application always rebuilds rather than moves, but a rebuild that was
-- implemented as an UPDATE by a later maintainer would silently reprice nothing
-- and show stale prices, so the rule is enforced where it cannot be bypassed.
CREATE OR REPLACE FUNCTION ordering.reject_cart_rebinding() RETURNS trigger AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id
        OR NEW.brand_id <> OLD.brand_id
        OR NEW.location_id <> OLD.location_id
        OR NEW.channel_id <> OLD.channel_id THEN
        RAISE EXCEPTION
            'A cart cannot be moved between locations or channels (ADR 0019): rebuild and reprice';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_carts_no_rebinding
    BEFORE UPDATE ON ordering.carts
    FOR EACH ROW EXECUTE FUNCTION ordering.reject_cart_rebinding();

CREATE TABLE ordering.cart_lines (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    cart_id uuid NOT NULL,
    -- Stable within the cart and reused as the ADR 0018 quote line id, so a
    -- re-quote can be compared line by line rather than by position.
    line_key varchar(64) NOT NULL,
    variant_id uuid NOT NULL,
    quantity integer NOT NULL,

    -- The customer's modifier choices as chosen, in a stable order. A jsonb
    -- document rather than a child table because nothing queries into it: it is
    -- read whole when pricing and copied whole onto the order.
    selected_modifier_snapshot jsonb NOT NULL DEFAULT '[]'::jsonb,

    -- ADR 0029: "no onions, ring the top bell" is free text a customer wrote
    -- about themselves. Encrypted at rest like every other personal field, and
    -- never placed in an event.
    customer_note_encrypted text,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_cart_line_quantity CHECK (quantity > 0 AND quantity <= 999),
    CONSTRAINT fk_cart_line_cart FOREIGN KEY (cart_id, tenant_id)
        REFERENCES ordering.carts (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_line_variant FOREIGN KEY (variant_id)
        REFERENCES catalog.variants (id),
    -- One row per line key. Two rows sharing a key would price twice and the
    -- context hash would depend on which one the query planner returned first.
    CONSTRAINT uq_cart_line_key UNIQUE (cart_id, line_key)
);

CREATE INDEX ix_cart_lines_cart ON ordering.cart_lines (cart_id);

-- ADR 0019's cart model also names an `ordering.cart_fulfillment` table holding
-- the delivery address, the requested time, and the contact reference. It is not
-- created here, and its absence is deliberate rather than an oversight.
--
-- Nothing in this release captures a delivery address: the storefront collects a
-- location and a basket, and ADR 0014 delivery sourcing is outside the first
-- cutover slice. A table nobody writes is schema everybody has to reason about,
-- and worse, it reads as a capability that exists. It arrives with the address
-- capture it exists to hold, in the same migration.
--
-- The practical consequence is that a DELIVERY cart can be opened and priced but
-- carries no address, so the first slice takes pickup and dine-in orders. That is
-- the honest state of the platform and it is visible here rather than discovered
-- at a counter.

-- ------------------------------------------------------------------- checkout

-- The transactional idempotency record from step 1 of ADR 0019's checkout.
--
-- This is not the same thing as the HTTP-level record in web.idempotency_records,
-- and both are needed. The HTTP one replays a response to a repeated request; this
-- one is the *transaction's* guard, so that two concurrent checkouts — arriving on
-- two nodes, or from a non-HTTP caller — serialize on a unique index rather than
-- both proceeding to create an order.
--
-- ADR 0019 rejected hashing the request to derive the key: two legitimately
-- different carts can normalize to the same hash. The fingerprint below is the
-- reverse check — it detects one client reusing a key for a different request.
CREATE TABLE ordering.checkout_attempts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    cart_id uuid NOT NULL,
    quote_id uuid NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'IN_PROGRESS',
    -- Set on a completed attempt, whichever way it completed. A business
    -- rejection is a settled outcome: retrying must return the same rejection
    -- rather than starting again against a cart that has since changed.
    order_id uuid,
    outcome_code varchar(48),
    outcome_detail text,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,

    CONSTRAINT ck_checkout_attempt_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_checkout_attempt_completed CHECK (
        (status <> 'COMPLETED') OR (completed_at IS NOT NULL AND outcome_code IS NOT NULL)
    ),
    -- The whole point of the table. A second concurrent checkout with the same
    -- key blocks on this index until the first commits, then reads its result.
    CONSTRAINT uq_checkout_attempt_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX ix_checkout_attempts_cart ON ordering.checkout_attempts (cart_id);

-- --------------------------------------------------------------------- orders

CREATE TABLE ordering.orders (
    id uuid PRIMARY KEY,

    -- What the customer and the kitchen say out loud. Scoped per location per
    -- day by the counter table below, because "order fourteen" is what a
    -- restaurant shouts and a platform-wide monotonic number would both be
    -- unusable at the counter and leak total platform volume to anyone holding
    -- two receipts.
    public_order_number varchar(24) NOT NULL,

    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    -- Snapshotted alongside the id: channels archive rather than delete, but a
    -- rename would still change what every historical report says this order
    -- arrived through.
    channel_code_snapshot varchar(32) NOT NULL,

    customer_account_id uuid,
    guest_reference_hash varchar(64),

    fulfillment_mode varchar(16) NOT NULL,

    -- ADR 0002 and ADR 0030: which acceptance rule applied, and the exact policy
    -- version it came from. Re-resolving the policy later would let a policy
    -- change alter what an already-placed order was permitted to do.
    acceptance_mode_snapshot varchar(24) NOT NULL,
    acceptance_policy_id uuid,
    acceptance_policy_version integer NOT NULL DEFAULT 0,
    approval_channel_snapshot varchar(24) NOT NULL,
    approval_timeout_action_snapshot varchar(16),
    approval_deadline_at timestamptz,

    status varchar(24) NOT NULL,

    -- Projections, not authorities. The payment and fulfilment aggregates own
    -- their own lifecycles; these columns exist so an operations list can be
    -- rendered without joining four modules, and they are always written from
    -- those aggregates' events rather than decided here.
    payment_status_projection varchar(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    fulfillment_status_projection varchar(24) NOT NULL DEFAULT 'PENDING',

    currency char(3) NOT NULL,
    subtotal_minor bigint NOT NULL,
    tax_minor bigint NOT NULL,
    discount_minor bigint NOT NULL DEFAULT 0,
    fee_minor bigint NOT NULL DEFAULT 0,
    total_minor bigint NOT NULL,

    pricing_quote_id uuid NOT NULL,
    -- The hash the quote was accepted at. Stored so "the price you were shown is
    -- the price you paid" is provable from the order alone, without re-reading a
    -- quote that will eventually be pruned.
    pricing_context_hash varchar(64) NOT NULL,
    catalog_publication_id uuid NOT NULL,
    cart_id uuid NOT NULL,

    idempotency_key varchar(255) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    closed_at timestamptz,

    -- The canonical statuses from docs/domains/state-machines.md. Code-owned:
    -- OrderStateMachine holds the same set and the transitions between them, and
    -- a test asserts the two agree. ADR 0036's omission list is explicit that a
    -- tenant may not reorder or extend this, which is why it is a CHECK and not
    -- a lookup table anyone could INSERT into.
    CONSTRAINT ck_order_status CHECK (status IN (
        'RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL', 'PAYMENT_FAILED',
        'CONFIRMED', 'REJECTED', 'EXPIRED', 'PREPARING', 'READY', 'FULFILLING',
        'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_order_payment_projection CHECK (payment_status_projection IN (
        'NOT_REQUIRED', 'PENDING', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'VOIDED', 'REFUNDED')),
    CONSTRAINT ck_order_fulfillment_projection CHECK (fulfillment_status_projection IN (
        'PENDING', 'IN_PREPARATION', 'READY', 'DISPATCHED', 'DELIVERED', 'COLLECTED', 'CANCELLED')),
    CONSTRAINT ck_order_fulfillment_mode CHECK (
        fulfillment_mode IN ('DELIVERY', 'PICKUP', 'DINE_IN')),
    CONSTRAINT ck_order_acceptance_mode CHECK (
        acceptance_mode_snapshot IN ('AUTO_CONFIRM', 'RESTAURANT_APPROVAL')),
    CONSTRAINT ck_order_approval_channel CHECK (
        approval_channel_snapshot IN ('NONE', 'QOIDA_OPERATIONS', 'POS', 'EITHER')),
    CONSTRAINT ck_order_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_order_amounts CHECK (
        subtotal_minor >= 0 AND tax_minor >= 0 AND discount_minor >= 0
        AND fee_minor >= 0 AND total_minor >= 0),
    -- Tax is inside the total (ADR 0018 prices are VAT-inclusive), so the net
    -- and the tax must reconstitute it. An order whose parts do not sum to its
    -- total cannot be fiscalized and cannot be explained to a customer.
    CONSTRAINT ck_order_total_reconciles CHECK (
        total_minor = subtotal_minor + tax_minor + fee_minor - discount_minor),
    CONSTRAINT ck_order_owner CHECK (
        (customer_account_id IS NOT NULL) <> (guest_reference_hash IS NOT NULL)),
    -- Confirmation is a fact with a time. A CONFIRMED order with no
    -- confirmed_at cannot answer "how long did approval take", which is the
    -- first question every restaurant asks.
    CONSTRAINT ck_order_confirmed_at CHECK (
        (status NOT IN ('CONFIRMED', 'PREPARING', 'READY', 'FULFILLING', 'COMPLETED'))
        OR confirmed_at IS NOT NULL),
    -- Restaurant approval needs a deadline; auto-confirm must not have one, or a
    -- timeout job would eventually fire against an order nobody was asked about.
    CONSTRAINT ck_order_approval_deadline CHECK (
        (acceptance_mode_snapshot = 'RESTAURANT_APPROVAL') = (approval_deadline_at IS NOT NULL)),
    CONSTRAINT ck_order_version CHECK (version >= 1),

    CONSTRAINT fk_order_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_order_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id),
    CONSTRAINT fk_order_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_order_quote FOREIGN KEY (pricing_quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id),
    CONSTRAINT fk_order_publication FOREIGN KEY (catalog_publication_id)
        REFERENCES catalog.publications (id),
    CONSTRAINT fk_order_cart FOREIGN KEY (cart_id, tenant_id)
        REFERENCES ordering.carts (id, tenant_id),

    -- One order per accepted quote. This is the last line of defence behind the
    -- conditional quote acceptance: even if two checkouts somehow both believed
    -- they had accepted the quote, only one order can exist for it. Without this
    -- a customer could be charged twice for one basket.
    CONSTRAINT uq_order_per_quote UNIQUE (tenant_id, pricing_quote_id),
    -- One order per cart, for the same reason at the other end of the funnel.
    CONSTRAINT uq_order_per_cart UNIQUE (tenant_id, cart_id),
    CONSTRAINT uq_order_number UNIQUE (tenant_id, location_id, public_order_number),
    CONSTRAINT uq_order_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_order_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_orders_location_open ON ordering.orders (tenant_id, location_id, created_at DESC);
CREATE INDEX ix_orders_awaiting_approval ON ordering.orders (approval_deadline_at)
    WHERE status = 'AWAITING_APPROVAL';
CREATE INDEX ix_orders_customer ON ordering.orders (tenant_id, customer_account_id, created_at DESC)
    WHERE customer_account_id IS NOT NULL;

-- The daily per-location counter behind public_order_number.
--
-- One atomic INSERT ... ON CONFLICT DO UPDATE ... RETURNING allocates a number,
-- so two simultaneous checkouts at one branch cannot receive the same one. The
-- contention is per branch per day, which is the throughput of a single kitchen.
--
-- business_date is the location's *local* date, computed by the application from
-- tenant.locations.timezone. A UTC date would roll the counter over at 05:00
-- local in Tashkent, mid-evening-service in a westward zone.
CREATE TABLE ordering.order_number_counters (
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    business_date date NOT NULL,
    last_value integer NOT NULL DEFAULT 0,

    PRIMARY KEY (tenant_id, location_id, business_date),
    CONSTRAINT ck_order_counter_value CHECK (last_value >= 0),
    CONSTRAINT fk_order_counter_location FOREIGN KEY (tenant_id, location_id)
        REFERENCES tenant.locations (tenant_id, id)
);

-- ---------------------------------------------------- immutable order content

-- Names and amounts as they were. `source_*` columns record which catalog row
-- this came from so the two can be reconciled, but nothing joins to them to
-- render an order: a deleted or renamed product must not change a receipt.
CREATE TABLE ordering.order_lines (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    line_number integer NOT NULL,

    source_product_id uuid,
    source_variant_id uuid NOT NULL,

    product_name_snapshot varchar(255) NOT NULL,
    variant_name_snapshot varchar(255),
    sku_snapshot varchar(64),

    quantity integer NOT NULL,
    unit_amount_minor bigint NOT NULL,
    base_amount_minor bigint NOT NULL,
    final_amount_minor bigint NOT NULL,
    tax_amount_minor bigint NOT NULL,
    -- ADR 0029 again: the note is the customer's own words.
    note_encrypted text,

    CONSTRAINT ck_order_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_line_amounts CHECK (
        unit_amount_minor >= 0 AND base_amount_minor >= 0
        AND final_amount_minor >= 0 AND tax_amount_minor >= 0),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT uq_order_line_number UNIQUE (order_id, line_number),
    CONSTRAINT uq_order_line_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_order_lines_order ON ordering.order_lines (order_id);

CREATE TABLE ordering.order_line_modifiers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_line_id uuid NOT NULL,
    source_group_id uuid,
    source_option_id uuid NOT NULL,
    group_name_snapshot varchar(255),
    option_name_snapshot varchar(255) NOT NULL,
    quantity integer NOT NULL DEFAULT 1,
    unit_amount_minor bigint NOT NULL,
    final_amount_minor bigint NOT NULL,

    CONSTRAINT ck_order_modifier_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_modifier_amounts CHECK (
        unit_amount_minor >= 0 AND final_amount_minor >= 0),
    CONSTRAINT fk_order_modifier_line FOREIGN KEY (order_line_id, tenant_id)
        REFERENCES ordering.order_lines (id, tenant_id)
);

CREATE INDEX ix_order_line_modifiers_line ON ordering.order_line_modifiers (order_line_id);

-- Every step that made up the total, copied from the accepted quote. Without
-- this, a customer asking "why is this 47,000 som" six months later has no
-- answer once the quote has been pruned, and neither does an auditor.
CREATE TABLE ordering.order_adjustments (
    order_id uuid NOT NULL,
    sequence integer NOT NULL,
    tenant_id uuid NOT NULL,
    order_line_id uuid,
    adjustment_type varchar(32) NOT NULL,
    source_type varchar(32) NOT NULL,
    source_id uuid,
    source_version integer,
    description_code varchar(64) NOT NULL,
    amount_minor bigint NOT NULL,

    PRIMARY KEY (order_id, sequence),
    CONSTRAINT ck_order_adjustment_type CHECK (adjustment_type IN (
        'BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT', 'FEE', 'TAX', 'ROUNDING')),
    CONSTRAINT fk_order_adjustment_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id)
);

-- The customer as they were at checkout. Encrypted per ADR 0029 and bound to the
-- order row by the AAD, so a ciphertext copied to another order fails to decrypt
-- rather than revealing the wrong person's address.
--
-- Copied rather than referenced because a customer who later edits their address
-- has not changed where last Tuesday's order went.
CREATE TABLE ordering.order_customer_snapshots (
    order_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    display_name_encrypted text,
    contact_encrypted text,
    address_encrypted text,
    delivery_instructions_encrypted text,
    -- Consent-safe facts: whether the customer may be contacted about this
    -- order, without copying the consent record itself.
    transactional_contact_allowed boolean NOT NULL DEFAULT true,
    -- Set when an ADR 0029 retention job blanks the encrypted columns. The row
    -- stays so the order still knows it once had a customer.
    anonymized_at timestamptz,

    CONSTRAINT fk_order_customer_snapshot_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id)
);

-- Append-only. Every transition, with what caused it and who.
--
-- This is the answer to "why is this order stuck", which ADR 0019 names as the
-- most common operational question in food delivery. An order row alone holds
-- only the current status and can never answer it.
CREATE TABLE ordering.order_state_history (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    sequence_number integer NOT NULL,
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    trigger varchar(32) NOT NULL,
    reason_code varchar(64),
    actor_type varchar(16) NOT NULL,
    actor_id varchar(255),
    correlation_id varchar(128),
    occurred_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_order_history_actor CHECK (
        actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER', 'CUSTOMER')),
    CONSTRAINT ck_order_history_trigger CHECK (trigger IN (
        'CHECKOUT', 'APPROVAL_DECISION', 'APPROVAL_TIMEOUT', 'PAYMENT_RESULT',
        'OPERATIONS_ACTION', 'CUSTOMER_ACTION', 'SYSTEM')),
    -- A transition to the status you were already in is not a transition. It is
    -- either a duplicate command that should have been rejected upstream, or a
    -- lost update, and recording it hides both.
    CONSTRAINT ck_order_history_moves CHECK (from_status IS NULL OR from_status <> to_status),
    CONSTRAINT fk_order_history_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    -- The sequence is allocated from the order's version, so a gap or a
    -- duplicate here means a lost update rather than a missing log line.
    CONSTRAINT uq_order_history_sequence UNIQUE (order_id, sequence_number)
);

CREATE INDEX ix_order_history_order ON ordering.order_state_history (order_id, sequence_number);

-- ------------------------------------------------------ restaurant approval

-- Every approve/reject command that arrived, whether or not it won.
--
-- ADR 0019: "the first valid command wins under compare-and-set", and a late
-- duplicate returns the settled outcome without re-running side effects. Storing
-- only the winner would make an operator's rejected click invisible, and the
-- question "who tried to reject this and when" unanswerable.
CREATE TABLE ordering.approval_decisions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    -- Client-supplied and stable across retries of one human decision, so the
    -- same click arriving twice is one decision rather than two.
    decision_id varchar(64) NOT NULL,
    action varchar(16) NOT NULL,
    decision_channel varchar(24) NOT NULL,
    actor_type varchar(16) NOT NULL,
    actor_id varchar(255),
    reason_code varchar(64),
    -- True on exactly one decision per order: the one that moved the state.
    effective boolean NOT NULL DEFAULT false,
    issued_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_approval_action CHECK (action IN ('APPROVE', 'REJECT')),
    CONSTRAINT ck_approval_channel CHECK (
        decision_channel IN ('QOIDA_OPERATIONS', 'POS', 'SYSTEM_TIMEOUT')),
    CONSTRAINT ck_approval_actor CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER')),
    CONSTRAINT fk_approval_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT uq_approval_decision_id UNIQUE (tenant_id, order_id, decision_id)
);

-- The race, settled in the database. Two operators clicking approve and reject
-- at the same instant both insert a row; only one can mark itself effective, and
-- the loser reads the winner's outcome instead of applying its own.
CREATE UNIQUE INDEX ux_approval_effective_per_order
    ON ordering.approval_decisions (order_id)
    WHERE effective;

-- A durable timeout, per ADR 0019: "Timeouts use a durable PostgreSQL job."
--
-- A scheduler in memory loses every pending deadline on a restart, and the
-- orders sit awaiting an approval nobody will ever be asked for.
CREATE TABLE ordering.order_timers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    timer_type varchar(32) NOT NULL,
    due_at timestamptz NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,

    CONSTRAINT ck_order_timer_type CHECK (timer_type IN ('APPROVAL_DEADLINE')),
    CONSTRAINT ck_order_timer_status CHECK (status IN ('PENDING', 'FIRED', 'CANCELLED')),
    CONSTRAINT fk_order_timer_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id)
);

-- One live timer of each kind per order. A second pending approval deadline
-- would fire twice and auto-reject an order that had already been confirmed.
CREATE UNIQUE INDEX ux_order_timer_pending
    ON ordering.order_timers (order_id, timer_type)
    WHERE status = 'PENDING';

CREATE INDEX ix_order_timers_due ON ordering.order_timers (due_at) WHERE status = 'PENDING';

-- ----------------------------------------------------------- process managers

-- One row per order per concern, not one saga per order.
--
-- ADR 0019's reasoning: a stuck POS export must not block payment, notification
-- and fulfilment for that order. Per-concern rows also make "why is this order
-- stuck" a single query rather than an interpretation of one opaque saga blob.
--
-- Only ORDER_INVENTORY is driven in this release. The others are named in the
-- CHECK so their rows are recognisable the moment their ADRs land, and refused
-- until then rather than being written by something that does not exist.
CREATE TABLE ordering.order_process_states (
    order_id uuid NOT NULL,
    process_name varchar(32) NOT NULL,
    tenant_id uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'WAITING',
    -- What the process must do next and what it has already done. Rebuilding a
    -- process from history must not repeat a provider effect, so the checkpoint
    -- records the effect, not merely the intent.
    checkpoint jsonb NOT NULL DEFAULT '{}'::jsonb,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error text,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (order_id, process_name),
    CONSTRAINT ck_order_process_name CHECK (process_name IN (
        'ORDER_PAYMENT', 'RESTAURANT_APPROVAL', 'ORDER_INVENTORY',
        'POS_ORDER_EXPORT', 'ORDER_FULFILLMENT', 'ORDER_NOTIFICATION')),
    CONSTRAINT ck_order_process_status CHECK (status IN (
        'WAITING', 'COMPLETED', 'FAILED_RETRYABLE', 'MANUAL_ACTION_REQUIRED', 'COMPENSATED')),
    -- A retryable failure with no scheduled retry is a process that has silently
    -- stopped, which looks identical to one that is merely slow.
    CONSTRAINT ck_order_process_retry CHECK (
        status <> 'FAILED_RETRYABLE' OR next_attempt_at IS NOT NULL),
    CONSTRAINT fk_order_process_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id)
);

CREATE INDEX ix_order_processes_runnable
    ON ordering.order_process_states (process_name, next_attempt_at)
    WHERE status IN ('WAITING', 'FAILED_RETRYABLE');

CREATE INDEX ix_order_processes_stuck
    ON ordering.order_process_states (tenant_id, status)
    WHERE status IN ('FAILED_RETRYABLE', 'MANUAL_ACTION_REQUIRED');

-- ------------------------------------------------- ADR 0036 capacity accounting

-- ADR 0036's V0020 left a note saying tenant.location_capacity_holds should be
-- dropped when ADR 0019 creates ordering.orders, and the concurrent-order count
-- should become a count of open orders.
--
-- It is not dropped, and the note is wrong about which direction the dependency
-- would run. tenancy is depended on by catalog, pricing, inventory and ordering;
-- making ServiceabilityService count rows in ordering.orders would invert that
-- and put a business module underneath the module every other one reads. The
-- claim table stays, and ordering owns its lifecycle instead: the hold id is now
-- the order id, claimed inside the checkout transaction after the order row
-- exists, and released when the order reaches a terminal state.
--
-- That keeps the single-authority property the note was protecting — there is
-- still exactly one counted set, and it is still claimed and released by the
-- transaction that creates and closes the order.
COMMENT ON COLUMN tenant.location_capacity_holds.id IS
    'The order id (ADR 0019). Claimed inside the checkout transaction once ordering.orders exists, released when the order closes. A primary key rather than a generated one, so a retried checkout re-claims its own slot instead of consuming a second.';

-- --------------------------------------------------------------------- grants

GRANT USAGE ON SCHEMA ordering TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON ordering.carts TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON ordering.cart_lines TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON ordering.checkout_attempts TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON ordering.orders TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON ordering.order_number_counters TO qoida_application;
GRANT SELECT, INSERT ON ordering.order_lines TO qoida_application;
GRANT SELECT, INSERT ON ordering.order_line_modifiers TO qoida_application;
GRANT SELECT, INSERT ON ordering.order_adjustments TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON ordering.order_customer_snapshots TO qoida_application;
GRANT SELECT, INSERT ON ordering.order_state_history TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON ordering.approval_decisions TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON ordering.order_timers TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON ordering.order_process_states TO qoida_application;

-- The grants above are deliberately uneven, and the unevenness is the point.
--
-- order_lines, order_line_modifiers, order_adjustments and order_state_history
-- receive SELECT and INSERT and nothing else: an order's commercial content and
-- its transition log are historical facts, and no application code path may edit
-- an amount, a name, or a recorded transition after the fact. A correction is a
-- new order (ADR 0019), and an UPDATE grant is all it takes for a well-meant
-- support fix to become an unrecorded rewrite of financial history.
--
-- ordering.orders keeps UPDATE because status, version and the two projections
-- are live state, and no DELETE because an order never stops having happened.
--
-- order_customer_snapshots keeps UPDATE for one reason only: ADR 0029
-- crypto-shredding must be able to blank the encrypted columns without deleting
-- the order. Every column it may touch is nullable for exactly that reason.

COMMENT ON TABLE ordering.carts IS
    'ADR 0019 mutable server-side cart, bound to one tenant, brand, location and channel; rebuilt rather than moved.';
COMMENT ON TABLE ordering.checkout_attempts IS
    'ADR 0019 transactional checkout idempotency record; distinct from the HTTP-level web.idempotency_records.';
COMMENT ON TABLE ordering.orders IS
    'ADR 0019 immutable commercial order with its snapshotted totals, acceptance policy and pricing context.';
COMMENT ON TABLE ordering.order_state_history IS
    'ADR 0019 append-only transition log; the answer to "why is this order stuck".';
COMMENT ON TABLE ordering.approval_decisions IS
    'ADR 0002/0019 approve and reject commands, including the ones that lost the race; exactly one is effective.';
COMMENT ON TABLE ordering.order_process_states IS
    'ADR 0019 one durable process state per order per concern. Only ORDER_INVENTORY is driven in this release.';
