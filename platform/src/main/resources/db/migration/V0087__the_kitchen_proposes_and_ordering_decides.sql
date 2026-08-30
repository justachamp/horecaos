-- ADR 0041 rollout step 2, and ADR 0019's half of it: the kitchen proposes an
-- order transition and ordering decides whether it happens.
--
-- Three things are needed and none of them existed.
--
-- 1. A trigger value that says a cook's screen moved this order. ADR 0041 names
--    the operations order list as the *fallback* control for the same two
--    transitions, so recording both as OPERATIONS_ACTION would make "which
--    orders did the kitchen actually drive" unanswerable from the history --
--    which is precisely the question a rollback of the pilot has to answer.
--
-- 2. A ledger of proposals, keyed by the kitchen's idempotency key. Status alone
--    is not an idempotency mechanism here: an offline client replaying a queued
--    PREPARING advance against an order that has since reached READY would be
--    told REFUSED, and a refusal that is really a replay is a false alarm on a
--    board. The ledger lets a replay be answered with what happened the first
--    time.
--
-- 3. A ticket-event trigger for the proposal itself, so a refusal is visible on
--    the ticket timeline the branch actually reads rather than only in a log
--    line on a server nobody at the branch can see.

-- ------------------------------------------------------- 1. the history trigger

ALTER TABLE ordering.order_state_history
    DROP CONSTRAINT ck_order_history_trigger;

ALTER TABLE ordering.order_state_history
    ADD CONSTRAINT ck_order_history_trigger CHECK (trigger IN (
        'CHECKOUT', 'APPROVAL_DECISION', 'APPROVAL_TIMEOUT', 'PAYMENT_RESULT',
        'OPERATIONS_ACTION', 'CUSTOMER_ACTION', 'SYSTEM',
        -- ADR 0041: a kitchen ticket's roll-up proposed this, through the same
        -- ADR 0019 command path an operator uses and with no more authority.
        'KITCHEN_PROGRESS'));

-- ------------------------------------------------------------ 2. the ledger

CREATE TABLE ordering.order_progress_proposals (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,

    -- Stable across retries of one kitchen fact. The kitchen builds it from the
    -- ticket and the transition, never from the request, so twelve replayed
    -- advances carry two distinct keys rather than twelve.
    idempotency_key varchar(255) NOT NULL,
    proposed_status varchar(24) NOT NULL,

    -- What the order was when the proposal was weighed, recorded because
    -- "REFUSED" without it does not say what it was refused against.
    from_status varchar(24),

    -- NULL only inside the proposing transaction: the row is claimed before the
    -- transition is attempted, so the key is held for the duration, and settled
    -- before the same transaction commits. A committed row always has an
    -- outcome, and ck_order_progress_proposal_settled is what makes that true
    -- rather than merely intended.
    outcome varchar(16),
    settled_at timestamptz,

    reason_code varchar(64),
    actor_type varchar(16) NOT NULL,
    actor_id varchar(255),
    correlation_id varchar(128),
    proposed_at timestamptz NOT NULL,

    -- The three transitions ADR 0041 entitles a kitchen to propose. NOT_WIRED is
    -- absent on purpose: it is what the port answers when nothing implements it,
    -- and nothing that answers it can write a row here.
    CONSTRAINT ck_order_progress_proposal_status CHECK (
        proposed_status IN ('PREPARING', 'READY', 'COMPLETED')),
    CONSTRAINT ck_order_progress_proposal_outcome CHECK (
        outcome IS NULL OR outcome IN ('APPLIED', 'ALREADY_THERE', 'REFUSED')),
    CONSTRAINT ck_order_progress_proposal_settled CHECK (
        (outcome IS NULL) = (settled_at IS NULL)),
    CONSTRAINT ck_order_progress_proposal_actor CHECK (
        actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER', 'CUSTOMER')),
    CONSTRAINT fk_order_progress_proposal_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    -- The tenant is in the key because the key is a caller-supplied string, and
    -- one tenant's ticket id must not be able to answer another tenant's
    -- proposal with a stored outcome.
    CONSTRAINT uq_order_progress_proposal_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX ix_order_progress_proposals_order
    ON ordering.order_progress_proposals (tenant_id, order_id, proposed_at);

COMMENT ON TABLE ordering.order_progress_proposals IS
    'ADR 0041 / ADR 0019. Every transition the kitchen proposed and what ordering did with it. Carries no dish, customer, address or note: a proposal is a ticket id, an order id and a status.';

GRANT SELECT, INSERT, UPDATE ON ordering.order_progress_proposals TO horecaos_application;

-- ------------------------------------------------- 3. the ticket-event trigger

ALTER TABLE kitchen.ticket_events
    DROP CONSTRAINT ck_ticket_event_trigger;

ALTER TABLE kitchen.ticket_events
    ADD CONSTRAINT ck_ticket_event_trigger CHECK (trigger IN (
        'STATION_ACTION',
        'ORDER_CONFIRMED',
        'RELEASE_SCHEDULED',
        'RELEASE_COMMAND',
        'ITEM_ROLLUP',
        'ROUTING_UNRESOLVED',
        -- The ticket told the order what it had done, and what the order said
        -- back. The ticket does not move for this: from_status and to_status are
        -- the same, because the food is where the food is.
        'ORDER_PROPOSAL'));
