-- The inbox backlog gauge had no index and scanned the whole table.
--
-- MessagingBacklogMetrics aggregates the pending set of integration.inbox_messages
-- to answer "how far behind is consumption". The two existing indexes do not
-- serve it: ix_inbox_tenant_time leads on tenant_id, which the gauge does not
-- filter on, and ix_inbox_messages_resolved covers the resolution lifecycle
-- rather than the pending set. So the gauge was a sequential scan, and a
-- sequential scan on a timer costs more than the thing it measures -- on a table
-- that, as V0049's sibling findings note, nothing ever deletes from.
--
-- The application half of this is already fixed: the gauge polls on its own
-- slower timer and backs off when the scan gets expensive, loudly rather than
-- silently, because an unrefreshed gauge holds its last value and a stale zero
-- reads exactly like a healthy zero. This index is the half that stops it
-- getting expensive in the first place.
--
-- Partial, because the pending set is the small minority of a table that is
-- overwhelmingly PROCESSED. Indexing every row would cost write throughput on
-- the hot path to speed up a metric.
--
-- Deliberately not CONCURRENTLY: Flyway runs each migration inside a transaction
-- and CREATE INDEX CONCURRENTLY cannot run in one. The pending set is small and
-- the lock is brief; on an installation where it is not, build it by hand
-- outside Flyway first and this statement becomes a no-op.
CREATE INDEX IF NOT EXISTS ix_inbox_messages_pending
    ON integration.inbox_messages (received_at)
    WHERE status IN ('RECEIVED', 'PROCESSING', 'RETRY_PENDING');
