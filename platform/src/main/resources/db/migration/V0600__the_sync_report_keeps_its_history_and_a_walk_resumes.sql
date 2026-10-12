-- ADR 0171 (row 6.3b), review corrections to the sync tables V0555 created. Nothing here edits V0555; it adds to
-- what V0555 made.
--
-- Three things the reconciliation report needed in order to be true:
--
--   1. A report is history. `pos_sync_discrepancies.run_id` was rewritten by every pass that saw the same open
--      difference again, so an old run's report lost the rows its own counts had counted. Each run's observations
--      now live in `pos_sync_run_findings`, one row per (run, discrepancy) with the numbers THAT run saw; the
--      discrepancy keeps `run_id` as the run that opened it and is never re-pointed.
--
--   2. "Absorb" is durable. A person who absorbs a difference that has no movement behind it (a till customer with
--      no platform account, an ambiguous match, a customer the till no longer knows, a balance that could not be
--      read, a difference of a stated size) has said the brand carries it. `carried` marks that resolution as one a
--      later pass must not reopen while the same condition holds; the pass that sees the condition end clears it.
--
--   3. A walk of the till's customers resumes. The customer list is paged and one pass reads a bounded number of
--      pages; the cursor was not kept, so every pass started at page one and the customers beyond the bound were
--      never matched or reported. `pos_sync_match_progress` keeps where each binding's walk stopped.

-- ---------------------------------------------------------------------------
-- 2. Absorb that stays absorbed
-- ---------------------------------------------------------------------------
ALTER TABLE loyalty.pos_sync_discrepancies
    ADD COLUMN carried boolean NOT NULL DEFAULT false;

-- A carried row is a resolved ABSORB and nothing else: the flag cannot outlive or contradict the resolution.
ALTER TABLE loyalty.pos_sync_discrepancies
    ADD CONSTRAINT ck_pos_sync_discrepancy_carried CHECK (
        NOT carried OR (status = 'RESOLVED' AND resolution_code = 'ABSORB' AND movement_id IS NULL));

CREATE INDEX ix_pos_sync_discrepancy_carried ON loyalty.pos_sync_discrepancies (tenant_id, binding_id, kind)
    WHERE carried;

COMMENT ON COLUMN loyalty.pos_sync_discrepancies.carried IS
    'ADR 0171. True on a difference a person ABSORBED and that still holds: a later pass that sees the same condition records it as a finding of its run and does not reopen it. Cleared by the pass that sees the condition end, or when the difference changes size.';

-- ---------------------------------------------------------------------------
-- 1. A report that is still true tomorrow
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty.pos_sync_run_findings (
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    discrepancy_id uuid NOT NULL,

    -- What THIS run saw. The discrepancy row holds the latest numbers; a run's report holds its own.
    platform_balance_minor bigint,
    till_balance_minor bigint,
    delta_minor bigint,
    observed_at timestamptz NOT NULL,

    CONSTRAINT pk_pos_sync_run_finding PRIMARY KEY (tenant_id, run_id, discrepancy_id),
    CONSTRAINT fk_pos_sync_finding_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES loyalty.pos_sync_runs (tenant_id, id),
    CONSTRAINT fk_pos_sync_finding_discrepancy FOREIGN KEY (tenant_id, discrepancy_id)
        REFERENCES loyalty.pos_sync_discrepancies (tenant_id, id)
);

CREATE INDEX ix_pos_sync_finding_discrepancy ON loyalty.pos_sync_run_findings (tenant_id, discrepancy_id);

COMMENT ON TABLE loyalty.pos_sync_run_findings IS
    'ADR 0171. The reconciliation report of one run: every difference the run observed, with the numbers it saw, so a run''s counts and its listing agree for good. Append-only evidence; the application role may not UPDATE or DELETE it.';

-- What the existing rows say about the run that last saw them is the best history there is.
INSERT INTO loyalty.pos_sync_run_findings
    (tenant_id, run_id, discrepancy_id, platform_balance_minor, till_balance_minor, delta_minor, observed_at)
SELECT d.tenant_id, d.run_id, d.id, d.platform_balance_minor, d.till_balance_minor, d.delta_minor, d.observed_at
  FROM loyalty.pos_sync_discrepancies d
 WHERE d.run_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM loyalty.pos_sync_runs r WHERE r.tenant_id = d.tenant_id AND r.id = d.run_id)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Where a binding's walk of the till's customers stopped
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty.pos_sync_match_progress (
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,

    -- The vendor's opaque cursor for the next page of customers; null when the last walk reached the end, so the
    -- next pass starts again at the top and finds the customers who joined the till since. Never a phone or a name.
    customer_cursor varchar(1024),
    updated_at timestamptz NOT NULL,

    CONSTRAINT pk_pos_sync_match_progress PRIMARY KEY (tenant_id, binding_id),
    CONSTRAINT fk_pos_sync_match_progress_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id)
);

COMMENT ON TABLE loyalty.pos_sync_match_progress IS
    'ADR 0171. One row per binding: the cursor at which the next matching pass resumes reading the till''s customers, because one pass reads a bounded number of pages and a till with more customers than that is walked over several.';

-- No DELETE: a finished walk is a null cursor, not a missing row.
GRANT SELECT, INSERT ON loyalty.pos_sync_run_findings TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON loyalty.pos_sync_match_progress TO horecaos_application;
