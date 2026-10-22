-- ADR 0141 "Rollout and rollback", Phase 3: the reconciler for one aggregator at one branch in a dry run
-- that computes desired-versus-confirmed without calling the partner, compared against that partner's
-- portal for a week, and only then live (batch 21, ADR 0180 Decision 3).
--
-- A dry-run binding still sweeps (that touches only our database) and so keeps `desired_available` current;
-- what it does not do is claim a row, call the adapter, or believe anything about the partner. Instead it
-- RECORDS what it would have sent: for every row whose desired value differs from what the partner is
-- known to hold, the value and the sequence it would have pushed and the instant it decided to. Those three
-- columns are the whole of the dry run's output, so the operator compares a list of decisions, one per dish,
-- against the partner's portal. confirmed_available is never touched in a dry run, so nothing is ever
-- believed in sync because a rehearsal "sent" it.
--
-- `was_dry_run` on the sync state is what makes leaving a dry run a resumption: the first live tick after
-- it withdraws every belief about the partner and resends everything once, exactly as a suspended
-- reconciler's resumption does, because the partner portal may have been edited by hand during the week.
--
-- No personal data, no dish name and no partner response lives in any of it: a boolean, a sequence number
-- and an instant per row.

ALTER TABLE integration.marketplace_item_availability
    ADD COLUMN would_push_available boolean,
    ADD COLUMN would_push_seq bigint,
    ADD COLUMN would_push_at timestamptz;

ALTER TABLE integration.marketplace_item_availability
    ADD CONSTRAINT ck_marketplace_item_would_push CHECK (
        (would_push_available IS NULL) = (would_push_seq IS NULL)
        AND (would_push_seq IS NULL) = (would_push_at IS NULL));

ALTER TABLE integration.marketplace_availability_sync_state
    ADD COLUMN was_dry_run boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN integration.marketplace_item_availability.would_push_available IS
    'ADR 0141 Phase 3. The value the reconciler WOULD have pushed for this row while the binding was in dry run (marketplace.availability.dry_run), with would_push_seq and would_push_at. Nothing was sent.';
COMMENT ON COLUMN integration.marketplace_availability_sync_state.was_dry_run IS
    'ADR 0141 Phase 3. Whether the previous pass ran in dry run: leaving it withdraws every confirmation and resends everything once, like a resumption.';
