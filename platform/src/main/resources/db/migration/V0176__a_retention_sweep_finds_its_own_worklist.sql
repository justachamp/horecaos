-- ADR 0044 retention: the snapshot-membership purge needs its own worklist,
-- not a sequential scan of marketing.audience_snapshots on every tenant's
-- database each time the sweep ticks.
--
-- Same shape as V0112's ix_campaigns_sending: a partial index scoped to
-- exactly the predicate JdbcAudienceStore#snapshotsPastRetention runs --
-- READY and not yet purged -- with completed_at leading so the sweep's own
-- ORDER BY completed_at LIMIT :limit is answered from the index directly,
-- oldest first, rather than sorting every READY snapshot on every pass. The
-- predicate is spelled out to match that query character-for-character, the
-- same discipline V0067's own comment names: PostgreSQL only uses a partial
-- index when it can prove the index predicate follows from the query's, and
-- its prover does not reason about a >= comparison implying anything about
-- an unrelated column, so status and members_purged_at are stated here
-- exactly as MarketingRetentionSweeper's own read states them.
--
-- No new table, and therefore no new GRANT: marketing.audience_snapshots
-- already carries the application role's SELECT, INSERT and UPDATE from
-- V0043.
CREATE INDEX ix_audience_snapshots_retention_due
    ON marketing.audience_snapshots (completed_at)
    WHERE status = 'READY' AND members_purged_at IS NULL;

COMMENT ON INDEX marketing.ix_audience_snapshots_retention_due IS
    'ADR 0044. MarketingRetentionSweeper''s own worklist: snapshots owed a twenty-four-month membership purge, oldest first.';
