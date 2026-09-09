-- ADR 0039: bulk actions over an operator's own order selection.
--
-- ordering.bulk_operations names one operator request under one idempotency
-- key; ordering.bulk_operation_items is the per-order outcome ledger V0029's
-- own comment deferred ("a table nobody writes reads as a capability that
-- exists"). This is not bulk courier assignment — that stays deferred behind
-- the fulfillment-domain work ADR 0039 named, and is not built here.
--
-- Two decisions the table shape carries, both taken from the ADR's own
-- "Bulk actions" section and its rejected all-or-nothing alternative:
--
-- First, N independent commands, never one transaction. Each item is applied
-- and recorded by its own call into the existing single-order services
-- (OrderStateService.advance, OrderOutcomeService.cancel) — the same
-- compare-and-set and reason-registry rules a single-order request meets. A
-- lock convoy during the peak that produced a bulk cancellation, and one
-- already-cancelled order failing the other hundred and ninety-nine, is
-- exactly the failure an all-or-nothing transaction was rejected for.
--
-- Second, the per-item idempotency key ADR 0039 derives as
-- {bulkKey}:{orderId} is realized as (bulk_operation_id, order_id) rather than
-- a literal string: bulk_operation_id is already keyed one-to-one with the
-- caller's Idempotency-Key through uq_bulk_operation_idempotency, so the pair
-- is that derivation without reformatting it. A re-run under the same bulk
-- key finds every prior item row and changes nothing, applied or failed alike
-- — matching the exit criterion the ADR states in its own testing section.

CREATE TABLE ordering.bulk_operations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- Closed set, like ordering.order_amendments' own command_type: which
    -- actions may ever be applied a hundred at a time is a decision this
    -- migration and OrderBulkActionService make together, not a string an
    -- operator's client is trusted to invent.
    action_type varchar(16) NOT NULL,

    requested_count integer NOT NULL,
    idempotency_key varchar(255) NOT NULL,

    created_by_actor_type varchar(16) NOT NULL,
    created_by_actor_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,

    CONSTRAINT ck_bulk_operation_action_type CHECK (action_type IN ('ADVANCE', 'CANCEL')),
    CONSTRAINT ck_bulk_operation_requested_count CHECK (requested_count BETWEEN 1 AND 200),
    CONSTRAINT ck_bulk_operation_actor CHECK (
        created_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER', 'CUSTOMER')),
    CONSTRAINT fk_bulk_operation_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- The whole of this wave's replay safety: a caller who resubmits the same
    -- Idempotency-Key for this tenant is resubmitting the same conceptual
    -- request, whatever the body says this time.
    CONSTRAINT uq_bulk_operation_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_bulk_operation_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_bulk_operations_location ON ordering.bulk_operations (tenant_id, location_id, created_at DESC);

COMMENT ON TABLE ordering.bulk_operations IS
    'ADR 0039. One operator bulk request, named once by its Idempotency-Key. Items are applied and recorded independently in ordering.bulk_operation_items — this row is the request, not the outcome.';
COMMENT ON COLUMN ordering.bulk_operations.action_type IS
    'ADR 0039: which actions may be bulk-applied at all is closed and code-owned. ADVANCE moves a live order between non-terminal kitchen states; CANCEL goes through the same tenant reason registry a single cancellation uses. Neither AMEND nor COMPLETE nor REJECT is a bulk action this release — see OrderBulkActionService for why.';

-- The per-item ledger. Never collapses to one fact: a bulk of two hundred with
-- three failures must still name which three, both for the operator's own
-- retry and for whoever audits the shift afterwards.
CREATE TABLE ordering.bulk_operation_items (
    bulk_operation_id uuid NOT NULL,
    order_id uuid NOT NULL,
    tenant_id uuid NOT NULL,

    item_status varchar(16) NOT NULL DEFAULT 'PENDING',
    item_problem_code varchar(64),
    resulting_order_version integer,
    decided_at timestamptz,

    PRIMARY KEY (bulk_operation_id, order_id),
    CONSTRAINT ck_bulk_item_status CHECK (item_status IN ('PENDING', 'APPLIED', 'FAILED')),
    CONSTRAINT ck_bulk_item_decided CHECK (
        (item_status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT ck_bulk_item_problem CHECK (
        (item_status = 'FAILED') = (item_problem_code IS NOT NULL)),
    CONSTRAINT ck_bulk_item_applied_version CHECK (
        (item_status = 'APPLIED') = (resulting_order_version IS NOT NULL)),
    CONSTRAINT fk_bulk_item_operation FOREIGN KEY (bulk_operation_id, tenant_id)
        REFERENCES ordering.bulk_operations (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_bulk_item_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id)
);

CREATE INDEX ix_bulk_operation_items_order ON ordering.bulk_operation_items (tenant_id, order_id);

COMMENT ON TABLE ordering.bulk_operation_items IS
    'ADR 0039. One row per order named in a bulk request, applied or failed independently. A bulk action never collapses into one fact that loses which orders were touched (ADR 0027).';
COMMENT ON COLUMN ordering.bulk_operation_items.item_problem_code IS
    'A stable code (STALE_VERSION, ILLEGAL_TRANSITION, ORDER_NOT_FOUND_AT_LOCATION, REASON_NOT_FOUND, VALIDATION_FAILED, UNEXPECTED_FAILURE) — never operator or exception free text, so a dead-letter-style summary of a failed item carries no more than any other ADR 0032 payload does.';

-- --------------------------------------------------------------------- grants

GRANT SELECT, INSERT, UPDATE ON ordering.bulk_operations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON ordering.bulk_operation_items TO horecaos_application;

-- Both keep UPDATE and neither gets DELETE, the same shape ordering.order_amendments
-- has and for the same reason: bulk_operations.completed_at is written once the
-- loop finishes, bulk_operation_items moves from PENDING to a settled status as
-- each item is decided, and a bulk request — successful, partial, or failed — is
-- evidence of what an operator tried and is never removed.
