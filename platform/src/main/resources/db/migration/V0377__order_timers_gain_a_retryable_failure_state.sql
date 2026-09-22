-- H9: a fired approval-deadline timer had no retry path.
--
-- JdbcOrderStore.claimDueTimers atomically marks a due timer 'FIRED' -- a
-- terminal state per this table's own CHECK constraint, which had no
-- retryable state at all. OrderProcessWorker.fireDueTimers then applies the
-- timer in a *separate* transaction, on purpose (claiming and applying are
-- deliberately split so two workers never fire one timer twice) -- but any
-- failure in that second transaction, or the node dying between the two, left
-- the row FIRED forever: the timer is never reclaimed, no other sweep flags
-- an AWAITING_APPROVAL order whose deadline already passed, and the tenant's
-- configured auto-confirm/auto-reject policy silently never applies.
--
-- Mirrors the retry shape ordering.order_process_states already uses
-- (OrderInventoryProcess's FAILED_RETRYABLE / MANUAL_ACTION_REQUIRED, V0022):
-- attempt_count and next_retry_at, and two new terminal-ish statuses. FIRED
-- keeps its old meaning -- a claim in flight, or (until the worker records
-- otherwise) a successfully applied timer.

ALTER TABLE ordering.order_timers
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at timestamptz;

ALTER TABLE ordering.order_timers
    DROP CONSTRAINT ck_order_timer_status;

ALTER TABLE ordering.order_timers
    ADD CONSTRAINT ck_order_timer_status
        CHECK (status IN ('PENDING', 'FIRED', 'CANCELLED', 'FAILED_RETRYABLE', 'MANUAL_ACTION_REQUIRED'));

-- Mirrors ix_order_timers_due (V0022): the claim query's second source of
-- rows, a failed timer whose backoff has elapsed.
CREATE INDEX ix_order_timers_retry ON ordering.order_timers (next_retry_at)
    WHERE status = 'FAILED_RETRYABLE';

-- No new GRANT: this widens an existing table and constraint
-- ordering.order_timers already grants SELECT, INSERT, UPDATE to
-- horecaos_application (V0022).
