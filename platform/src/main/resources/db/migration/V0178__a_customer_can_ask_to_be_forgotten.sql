-- ADR 0029: the erasure-request mechanism its own Implementation status said
-- did not exist anywhere in this codebase — "no data-subject export,
-- correction, anonymisation, retention, legal-hold or proof operation exists
-- anywhere ... there is no privacy endpoint, service or table". ADR 0044 hit
-- the same gap from the marketing side: CustomerMetricProjectionService.erase
-- and JdbcAudienceStore.eraseMembership exist and are tested, but nothing ever
-- produced the fact they were meant to consume, and nothing ever wrote
-- customer.customer_accounts.status = 'ANONYMIZED' though V0017's own CHECK
-- constraint has accepted that value since the schema was created.
--
-- This migration is the narrow slice: a durable record of a request, and the
-- state it moves through. It does not add export, correction, legal hold, or
-- proof — ADR 0029 names those as separate, larger operations.

-- At most one PENDING request per account, so raising a second one while the
-- first is still outstanding is idempotent rather than a duplicate worklist
-- entry: the service returns the existing row instead of inserting another.
-- COMPLETED and CANCELLED are terminal and may repeat, which is why the
-- uniqueness is on the partial index rather than a plain unique constraint.
CREATE TABLE customer.erasure_requests (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',

    -- Where the request came from: the data subject themselves through the
    -- storefront, or an operator filing one on their behalf (ADR 0025 read
    -- for the capability question, ADR 0015 for why "on behalf of" already has
    -- a shape elsewhere in this module). Separate from the actor type below:
    -- a support agent and a storefront customer are both possible for
    -- OPERATIONS in the sense that an agent can act "as" a customer over the
    -- phone, but STOREFRONT vs OPERATIONS is the fact a later report needs —
    -- "how many people asked for this themselves" is a different question from
    -- "how many did staff file".
    requested_via varchar(16) NOT NULL,
    requested_by_actor_type varchar(16) NOT NULL,
    requested_by_actor_id varchar(255) NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),

    -- Execution is deliberately a separate, manual, capability-gated act from
    -- the request (ADR 0044's own words: "a manual, tested operation" until a
    -- sweep exists to schedule it). These four columns are the only place the
    -- transition that actually anonymises an account and overwrites its
    -- protected fields is recorded.
    completed_at timestamptz,
    completed_by_actor_type varchar(16),
    completed_by_actor_id varchar(255),

    cancelled_at timestamptz,
    cancelled_by_actor_type varchar(16),
    cancelled_by_actor_id varchar(255),

    CONSTRAINT ck_erasure_request_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT ck_erasure_request_via CHECK (
        requested_via IN ('STOREFRONT', 'OPERATIONS')
    ),
    CONSTRAINT ck_erasure_request_actor_type CHECK (
        requested_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')
    ),
    CONSTRAINT ck_erasure_request_completed_actor_type CHECK (
        completed_by_actor_type IS NULL
        OR completed_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')
    ),
    CONSTRAINT ck_erasure_request_cancelled_actor_type CHECK (
        cancelled_by_actor_type IS NULL
        OR cancelled_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')
    ),
    -- A row is COMPLETED exactly when it carries a completion, and never
    -- carries one otherwise. The equivalence, not just one direction, is what
    -- catches a service that flips the status without writing who and when.
    CONSTRAINT ck_erasure_request_completed_fields CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL
            AND completed_by_actor_type IS NOT NULL AND completed_by_actor_id IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL
            AND completed_by_actor_type IS NULL AND completed_by_actor_id IS NULL)
    ),
    CONSTRAINT ck_erasure_request_cancelled_fields CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL
            AND cancelled_by_actor_type IS NOT NULL AND cancelled_by_actor_id IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL
            AND cancelled_by_actor_type IS NULL AND cancelled_by_actor_id IS NULL)
    ),
    CONSTRAINT fk_erasure_request_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

CREATE UNIQUE INDEX ux_erasure_request_pending
    ON customer.erasure_requests (tenant_id, customer_account_id)
    WHERE status = 'PENDING';

-- One account's history, newest first — what a staff detail screen and the
-- storefront's own "erasure pending since ..." read from.
CREATE INDEX ix_erasure_requests_account
    ON customer.erasure_requests (tenant_id, customer_account_id, requested_at DESC);

-- The worklist a future ADR 0044 sweep reads once it exists: every PENDING
-- request, oldest first. Not read by anything in this migration's own wave —
-- execution here is the manual operation ADR 0044 describes — but the shape a
-- sweep needs is exactly this index, so it is built now rather than bolted on
-- as a second migration when that wave lands.
CREATE INDEX ix_erasure_requests_pending
    ON customer.erasure_requests (requested_at)
    WHERE status = 'PENDING';

GRANT SELECT, INSERT, UPDATE ON customer.erasure_requests TO horecaos_application;
