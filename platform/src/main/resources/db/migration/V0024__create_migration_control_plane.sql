-- ADR 0024: the migration control plane.
--
-- This schema is the answer to one question asked continuously for the length of
-- the migration: for this tenant, this capability, this branch — right now — who
-- is allowed to write? Every other table here exists to make that answer
-- defensible: how the crosswalk was built, what the runs that built it counted,
-- what could not be mapped, what reconciliation proved, and who signed the
-- transfer of ownership.
--
-- Three properties are enforced here rather than in a service.
--
-- First, single writer. Two rows claiming the same (tenant, capability, brand,
-- location) at the same specificity would mean two authorities, which is the one
-- outcome ADR 0024 exists to prevent, and it must be unrepresentable rather than
-- merely rejected by whichever service happened to be asked.
--
-- Second, evidence that cannot be edited afterwards. Cutover decisions are
-- append-only, reconciliation figures reconcile arithmetically, and a finished
-- run freezes.
--
-- Third, no source data leaks into the control plane. ADR 0029 does not stop
-- applying because a legacy row is broken: quarantine records a reference and a
-- reason code, never the row that failed.
--
-- The control plane is deliberately isolated from every business schema. It is
-- read by the ownership port on hot paths and written by operators and
-- migrators; mixing it into `tenant` or `ordering` would make a migration table
-- something a business module could join to and then depend on.

CREATE SCHEMA IF NOT EXISTS migration;

COMMENT ON SCHEMA migration IS
    'ADR 0024 migration control plane: capability ownership, runs, crosswalks, quarantine, reconciliation, and cutover evidence';

-- ------------------------------------------------------------------- programs

-- One migration of one source environment into one target environment.
--
-- A program is not tenant-scoped. It is the platform-level unit that carries the
-- policy version every scope under it was planned against, and the same tenant
-- appears under a rehearsal program and the production one.
CREATE TABLE migration.programs (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PLANNING',
    -- Free-form environment identifiers rather than a closed set: the source is
    -- somebody else's system and its environments are named by whoever runs it.
    source_environment varchar(64) NOT NULL,
    target_environment varchar(64) NOT NULL,
    -- The version of the approved mapping and quarantine policy in
    -- docs/domains/legacy-mapping.md that this program executes. Recorded on the
    -- program rather than resolved at read time, because a policy revision must
    -- not retroactively change what an already-running program was approved to do.
    policy_version integer NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- Not one of the pinned domain enums: ADR 0024 names no program status, so
    -- this set is chosen here. It is deliberately four values and does not carry
    -- PAUSED — pausing is a scope-level fact (ScopeState.PAUSED) because a
    -- program-wide pause that did not change the scopes would leave every
    -- ownership answer unchanged while claiming the migration had stopped.
    CONSTRAINT ck_program_status CHECK (
        status IN ('PLANNING', 'ACTIVE', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_program_policy_version CHECK (policy_version > 0),
    -- A program that has not started has no start time, and one that is running
    -- or finished is answerable for when it began.
    --
    -- Not an equality against PLANNING, which is the obvious form and is wrong:
    -- ABANDONED is reachable from PLANNING, and a plan called off before it began
    -- genuinely never started. An equality forces such a row to carry a start
    -- time it did not have, so either the write invents one — recording a
    -- migration that never ran — or the insert is rejected and calling off a plan
    -- returns a 500. Stated instead as the two implications that are actually
    -- true, leaving ABANDONED free to have a start or not depending on whether
    -- the program had one.
    CONSTRAINT ck_program_started CHECK (
        (status <> 'PLANNING' OR started_at IS NULL)
        AND (status NOT IN ('ACTIVE', 'COMPLETED') OR started_at IS NOT NULL)),
    CONSTRAINT ck_program_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_program_window CHECK (
        completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at),
    CONSTRAINT ck_program_version CHECK (version >= 1),
    CONSTRAINT uq_program_name UNIQUE (name)
);

COMMENT ON TABLE migration.programs IS
    'ADR 0024 one migration of one source environment into one target environment, at one approved policy version';

-- --------------------------------------------------------------------- scopes

-- The unit of ownership, and the table the rest of the platform actually reads.
--
-- A scope is keyed by tenant and capability, narrowed optionally by brand and
-- then by location. MigrationOwnershipPort resolves most-specific-first —
-- location, then brand, then tenant — so the three levels are meant to coexist:
-- a tenant may hand ORDERS to the target everywhere except at one branch that is
-- still draining, and that is two rows, not a conflict.
--
-- What must never coexist is two rows at the *same* specificity, which is two
-- writers wearing one answer. The partial unique indexes below make that
-- unrepresentable; see the note above them for why the obvious constraint does
-- not work.
CREATE TABLE migration.scopes (
    id uuid PRIMARY KEY,
    program_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    -- Null narrows nothing: a null brand means the scope covers the whole
    -- tenant, and a null location the whole brand.
    brand_id uuid,
    location_id uuid,
    capability varchar(16) NOT NULL,

    -- Which system owns the data on each side, as an identifier an operator can
    -- recognise in a runbook ('DELEVER', 'HORECAOS_ORDERING'). Free-form within a
    -- code shape rather than a closed set, because the legacy side is named by
    -- the estate being retired and not by this platform.
    source_owner varchar(32) NOT NULL,
    target_owner varchar(32) NOT NULL,

    write_mode varchar(32) NOT NULL DEFAULT 'LEGACY_ONLY',
    read_mode varchar(16) NOT NULL DEFAULT 'LEGACY',

    -- Named `state` and not `status` as ADR 0024's sketch spells it, because the
    -- pinned domain type is ScopeState and CapabilityOwnership.state. The
    -- neighbouring tables keep `status` for the same reason: their domain types
    -- are RunStatus, MappingStatus and ReconciliationStatus. One vocabulary, two
    -- words for it, is how a store ends up mapping the wrong column.
    state varchar(24) NOT NULL DEFAULT 'DISCOVERY',
    -- When the scope entered its current state. The rollback window and the soak
    -- period are both "has it been in this state long enough", and a scope that
    -- only knows updated_at cannot answer that after any unrelated edit.
    state_entered_at timestamptz NOT NULL DEFAULT now(),

    -- The gate evidence carried forward between transitions: final watermarks,
    -- the reconciliation run ids that cleared, the canary observation window.
    -- Aggregate figures and references only — see the quarantine note below for
    -- why nothing derived from a source row belongs in the control plane.
    checkpoint jsonb NOT NULL DEFAULT '{}'::jsonb,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- MigrationCapability. Code-owned and closed: behaviour keys on the
    -- capability, and one an operator invented would be a fence nobody
    -- implemented, which fails open.
    CONSTRAINT ck_scope_capability CHECK (capability IN (
        'TENANCY', 'IDENTITY', 'CUSTOMERS', 'MEDIA', 'CATALOG', 'INVENTORY',
        'PRICING', 'ORDERS', 'PAYMENTS', 'FULFILLMENT', 'NOTIFICATIONS',
        'CONFIGURATION', 'REPORTING')),
    -- ScopeState, WriteMode and ReadMode exactly as the domain declares them. A
    -- value the code can write and the schema refuses stops a cutover mid-window;
    -- a value the schema accepts and the code cannot read is an ownership answer
    -- nobody can interpret.
    CONSTRAINT ck_scope_state CHECK (state IN (
        'DISCOVERY', 'MAPPING_APPROVED', 'BACKFILLING', 'CATCHING_UP',
        'SHADOW_READING', 'CANARY', 'CUTOVER_READY', 'TARGET_OWNED',
        'ROLLBACK_WINDOW', 'LEGACY_READ_ONLY', 'RETIRED', 'PAUSED',
        'BLOCKED_RECONCILIATION', 'ROLLING_BACK')),
    CONSTRAINT ck_scope_write_mode CHECK (write_mode IN (
        'LEGACY_ONLY', 'LEGACY_WITH_TARGET_SHADOW', 'TARGET_ONLY')),
    CONSTRAINT ck_scope_read_mode CHECK (read_mode IN (
        'LEGACY', 'SHADOW_COMPARE', 'CANARY_TARGET', 'TARGET')),
    -- The one mode pairing the ADR settles outright. Reading the target as the
    -- authority while legacy still owns the writes serves data that is missing
    -- every write since the last catch-up, and does so silently. The lagging
    -- cases have their own read modes (SHADOW_COMPARE, CANARY_TARGET), so plain
    -- TARGET means the target is the source of truth, which only holds once it
    -- is also the writer.
    --
    -- The rest of the state-to-mode matrix lives in OwnershipModes and not here.
    -- Restating it in a CHECK would create a second copy that drifts from the
    -- domain on the first state the two disagree about.
    CONSTRAINT ck_scope_target_reads_need_target_writes CHECK (
        read_mode <> 'TARGET' OR write_mode = 'TARGET_ONLY'),
    -- A location always belongs to a brand, so narrowing to a location without
    -- naming its brand would leave the composite foreign key below switched off
    -- and admit another brand's branch.
    CONSTRAINT ck_scope_narrowing CHECK (location_id IS NULL OR brand_id IS NOT NULL),
    CONSTRAINT ck_scope_owners CHECK (
        source_owner ~ '^[A-Z0-9][A-Z0-9_]{0,31}$'
        AND target_owner ~ '^[A-Z0-9][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_scope_checkpoint CHECK (jsonb_typeof(checkpoint) = 'object'),
    CONSTRAINT ck_scope_version CHECK (version >= 1),

    CONSTRAINT fk_scope_program FOREIGN KEY (program_id)
        REFERENCES migration.programs (id),
    CONSTRAINT fk_scope_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    -- Matched on (tenant_id, brand_id) and (tenant_id, brand_id, location_id) so
    -- a scope cannot narrow to another tenant's brand or another brand's branch.
    -- Narrowing on an id alone would insert cleanly and then fence the wrong
    -- people's writes.
    CONSTRAINT fk_scope_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_scope_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- The key every child table points at, so a run, mapping or decision cannot
    -- be attached to another tenant's scope.
    CONSTRAINT uq_scope_identity UNIQUE (tenant_id, id)
);

COMMENT ON COLUMN migration.scopes.checkpoint IS
    'Gate evidence carried between transitions: final watermarks, the reconciliation runs that cleared, the observed canary window. Aggregate figures and references only, never source data.';

-- Overlap prevention, and why it is three indexes rather than one constraint.
--
-- The obvious UNIQUE (tenant_id, capability, brand_id, location_id) does not
-- work: NULL is not equal to NULL in a unique index, so two identical
-- tenant-wide ORDERS scopes both insert and the resolver then picks whichever
-- the planner returned first — two writers, chosen at random, exactly the
-- outcome this ADR exists to prevent.
--
-- The alternative of a single index over COALESCE(brand_id, <sentinel uuid>) was
-- rejected: it makes a real uuid equal to "no brand" if the sentinel is ever
-- generated, and it hides the fact that these are three distinct claims with a
-- precedence order between them.
--
-- Three partial indexes state that order explicitly, and they are also the three
-- probes MigrationOwnershipPort.ownershipOf makes — location, then brand, then
-- tenant — so each resolution step is a unique index lookup rather than a scan.
--
-- Note what the keys deliberately omit: program_id. Two programs each holding a
-- tenant-wide ORDERS scope would be two writers however the programs are
-- described, so the claim is on the capability and not on the paperwork.
--
-- Note also that no index is filtered on state. A RETIRED scope keeps its claim,
-- because a resolver forced to choose between a retired row and a live row at the
-- same specificity is choosing between two writers. Re-migrating a capability
-- reuses the row it already has.
CREATE UNIQUE INDEX ux_scope_claim_tenant_wide
    ON migration.scopes (tenant_id, capability)
    WHERE brand_id IS NULL AND location_id IS NULL;

CREATE UNIQUE INDEX ux_scope_claim_brand_wide
    ON migration.scopes (tenant_id, capability, brand_id)
    WHERE brand_id IS NOT NULL AND location_id IS NULL;

CREATE UNIQUE INDEX ux_scope_claim_location
    ON migration.scopes (tenant_id, capability, location_id)
    WHERE location_id IS NOT NULL;

CREATE INDEX ix_scopes_program ON migration.scopes (program_id, state);

COMMENT ON TABLE migration.scopes IS
    'ADR 0024 unit of capability ownership. Most specific claim wins: location, then brand, then tenant. Overlap at one specificity is made unrepresentable by the ux_scope_claim_* indexes.';

-- ----------------------------------------------------------------------- runs

-- One execution of one migrator against one scope.
--
-- Runs are restartable, which is the whole shape of this table: the watermarks
-- and the checkpoint are what a killed worker reads back to know where it got
-- to, and the counters only ever move forward so that resuming cannot rewind
-- the arithmetic a reconciliation will later be compared against.
CREATE TABLE migration.runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    run_type varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'RUNNING',

    -- Opaque to this schema and meaningful only to the migrator that wrote it:
    -- a primary key, a change sequence, or a timestamp, depending on the source.
    -- Typed as text rather than as a timestamp precisely because a source whose
    -- watermark is a key would otherwise be forced to lie about it.
    source_watermark varchar(512),
    target_watermark varchar(512),

    -- Where inside the current page the worker was, so a kill loses at most the
    -- page in flight. Distinct from scopes.checkpoint, which is gate evidence
    -- about the scope and outlives every run.
    checkpoint jsonb NOT NULL DEFAULT '{}'::jsonb,

    -- The version of the transformation code this run applied. ADR 0024 requires
    -- a changed mapping to produce an explicit remediation run rather than
    -- silently mixing semantics, and that is only checkable if every row the run
    -- wrote can be traced back to the transformation that wrote it.
    transformation_version integer NOT NULL,

    scanned_count bigint NOT NULL DEFAULT 0,
    created_count bigint NOT NULL DEFAULT 0,
    updated_count bigint NOT NULL DEFAULT 0,
    skipped_count bigint NOT NULL DEFAULT 0,
    quarantined_count bigint NOT NULL DEFAULT 0,
    -- Set when the run completes. Hex sha-256 like every other digest in this
    -- database, so the reconciliation suite compares like with like.
    checksum char(64),

    started_by varchar(255) NOT NULL,
    -- ADR 0031. A retried "start backfill" must join the run it already started;
    -- two backfills over one scope double every counter and the reconciliation
    -- that follows would be arithmetic about a run that never happened.
    idempotency_key varchar(255) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,

    CONSTRAINT ck_run_type CHECK (run_type IN (
        'BACKFILL', 'CATCH_UP', 'REMEDIATION', 'RECONCILIATION')),
    CONSTRAINT ck_run_status CHECK (status IN (
        'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    -- A finished run with no finish time cannot be aged out, and a running one
    -- with a finish time is a worker that stopped without saying so. The equality
    -- refuses both, where an OR of the two shapes would leave the mixed cases
    -- silently legal.
    CONSTRAINT ck_run_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT ck_run_window CHECK (finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT ck_run_counts CHECK (
        scanned_count >= 0 AND created_count >= 0 AND updated_count >= 0
        AND skipped_count >= 0 AND quarantined_count >= 0),
    CONSTRAINT ck_run_checksum CHECK (checksum IS NULL OR checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_run_transformation_version CHECK (transformation_version > 0),
    CONSTRAINT ck_run_checkpoint CHECK (jsonb_typeof(checkpoint) = 'object'),
    CONSTRAINT ck_run_version CHECK (version >= 1),

    CONSTRAINT fk_run_scope FOREIGN KEY (tenant_id, scope_id)
        REFERENCES migration.scopes (tenant_id, id),
    CONSTRAINT uq_run_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_run_identity UNIQUE (tenant_id, id),
    -- Reconciliation results carry the scope they belong to so the cutover gate
    -- can read them without a join; this key is what stops that copy from
    -- disagreeing with the run it came from.
    CONSTRAINT uq_run_scope_identity UNIQUE (tenant_id, id, scope_id)
);

-- One live run of each type per scope. Two concurrent backfills over one scope
-- would page the same source twice and race on the crosswalk; a reconciliation
-- alongside a catch-up is fine and deliberately still allowed.
CREATE UNIQUE INDEX ux_run_active_per_scope
    ON migration.runs (scope_id, run_type)
    WHERE status = 'RUNNING';

CREATE INDEX ix_runs_scope ON migration.runs (tenant_id, scope_id, started_at DESC);

-- There is no constraint asserting that the dispositions sum to the scan, and
-- that is deliberate. Whether a quarantined row also counted as scanned, and
-- whether the migrator advances the scan before or after processing a page, are
-- decisions belonging to the migrator; a CHECK stating one of them would abort
-- a legitimate mid-page update of the other. The relation is a reconciliation
-- rule instead, where it can be evaluated over a finished run and disagreed
-- with in evidence rather than by a failing INSERT at three in the morning.

-- Counters are monotone and a finished run is frozen.
--
-- Both rules are temporal and neither can be stated as a CHECK, which sees one
-- row version at a time. They are enforced here because the failure they prevent
-- is silent: a resumed worker that reset its counters to zero and started adding
-- again would produce a run whose totals look plausible, reconcile against
-- nothing, and understate exactly the rows it re-imported.
CREATE OR REPLACE FUNCTION migration.reject_run_regression() RETURNS trigger AS $$
BEGIN
    IF OLD.finished_at IS NOT NULL THEN
        RAISE EXCEPTION
            'Run % finished at % and is evidence, not state (ADR 0024): open a remediation run',
            OLD.id, OLD.finished_at;
    END IF;
    IF NEW.scanned_count < OLD.scanned_count
        OR NEW.created_count < OLD.created_count
        OR NEW.updated_count < OLD.updated_count
        OR NEW.skipped_count < OLD.skipped_count
        OR NEW.quarantined_count < OLD.quarantined_count THEN
        RAISE EXCEPTION
            'Run counters only advance (ADR 0024): a resumed run may not rewind them';
    END IF;
    IF NEW.transformation_version <> OLD.transformation_version THEN
        RAISE EXCEPTION
            'A run cannot change transformation version (ADR 0024): a changed mapping is a new remediation run';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_runs_no_regression
    BEFORE UPDATE ON migration.runs
    FOR EACH ROW EXECUTE FUNCTION migration.reject_run_regression();

COMMENT ON TABLE migration.runs IS
    'ADR 0024 one restartable execution of one migrator over one scope. Counters advance only; a finished run is frozen by trg_runs_no_regression.';

-- ------------------------------------------------------------- entity mappings

-- The crosswalk: which legacy identity became which target identity.
--
-- This is the table that makes "no legacy row was forgotten" provable, which is
-- why a row that could not be imported still gets an entry here with
-- QUARANTINED rather than being absent. Absence would be indistinguishable from
-- never having been seen.
CREATE TABLE migration.entity_mappings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,
    -- The legacy key as the source spells it. Text rather than uuid because the
    -- legacy estate keys on integers, strings and composites, and coercing those
    -- into a uuid is the point at which a crosswalk starts inventing identities.
    legacy_id varchar(255) NOT NULL,
    target_id uuid,

    -- The source row version this mapping was built from, in whatever the source
    -- calls a version: a revision number, a row timestamp, an ETag. Compared as
    -- an opaque token, never ordered by this schema.
    source_version varchar(64),
    -- The version of the target aggregate as it stood after the upsert, so a
    -- later edit made by a human on the target side is distinguishable from one
    -- the migrator made.
    target_version bigint,
    -- Stamped from the run. Two rows of one entity type carrying different
    -- transformation versions is precisely the "silently mixed semantics" ADR
    -- 0024 forbids, and it is only visible because the number is on the row.
    transformation_version integer NOT NULL,

    mapping_status varchar(16) NOT NULL,
    -- The surviving mapping when two legacy identities were reviewed and merged
    -- (ADR 0024 forbids automatic merges; this records an approved one). The
    -- superseded row keeps its target_id so a stale legacy reference still
    -- resolves to something rather than to nothing.
    superseded_by_mapping_id uuid,
    -- The run that last wrote this row, so a mapping changed by a remediation
    -- can be traced to the remediation that changed it.
    run_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_entity_mapping_status CHECK (
        mapping_status IN ('MAPPED', 'QUARANTINED', 'SUPERSEDED')),
    CONSTRAINT ck_entity_mapping_entity_type CHECK (entity_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_entity_mapping_legacy_id CHECK (length(btrim(legacy_id)) > 0),
    -- A quarantined mapping has no target and a non-quarantined one has exactly
    -- one. Stated as an equality so neither a quarantined row pointing at a
    -- target nor a mapped row pointing at nothing can exist; the OR-of-shapes
    -- form would leave the second case legal and it reads identically.
    CONSTRAINT ck_entity_mapping_target CHECK (
        (mapping_status = 'QUARANTINED') = (target_id IS NULL)),
    CONSTRAINT ck_entity_mapping_supersession CHECK (
        (mapping_status = 'SUPERSEDED') = (superseded_by_mapping_id IS NOT NULL)),
    CONSTRAINT ck_entity_mapping_not_self_superseded CHECK (
        superseded_by_mapping_id IS NULL OR superseded_by_mapping_id <> id),
    CONSTRAINT ck_entity_mapping_transformation_version CHECK (transformation_version > 0),

    CONSTRAINT fk_entity_mapping_scope FOREIGN KEY (tenant_id, scope_id)
        REFERENCES migration.scopes (tenant_id, id),
    CONSTRAINT fk_entity_mapping_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES migration.runs (tenant_id, id),
    CONSTRAINT fk_entity_mapping_supersession FOREIGN KEY (superseded_by_mapping_id)
        REFERENCES migration.entity_mappings (id),
    -- The upsert key, and the reason a re-run is idempotent: the second import of
    -- a legacy row finds its own mapping instead of creating a second target
    -- entity. Without it a restarted backfill duplicates every row of the page it
    -- was killed inside.
    CONSTRAINT uq_entity_mapping_key UNIQUE (scope_id, entity_type, legacy_id)
);

-- The reverse direction, which reconciliation and rollback both need: given a
-- target row, which legacy identity produced it. Two legacy ids may point at one
-- target after an approved merge, so this is an index and not a constraint.
CREATE INDEX ix_entity_mappings_target
    ON migration.entity_mappings (scope_id, entity_type, target_id)
    WHERE target_id IS NOT NULL;

CREATE INDEX ix_entity_mappings_unmapped
    ON migration.entity_mappings (tenant_id, scope_id, entity_type)
    WHERE mapping_status = 'QUARANTINED';

COMMENT ON COLUMN migration.entity_mappings.mapping_status IS
    'MAPPED, QUARANTINED or SUPERSEDED. A quarantined mapping is recorded rather than omitted so "seen and not migrated" is distinguishable from "never seen".';

COMMENT ON TABLE migration.entity_mappings IS
    'ADR 0024 legacy-to-target crosswalk, unique on (scope, entity type, legacy id). Carries the source and transformation versions so a changed mapping produces a remediation run.';

-- ----------------------------------------------------------- quarantine items

-- A legacy row that could not be migrated, recorded without being copied.
--
-- There is no payload column here and that is the design, not an omission. ADR
-- 0029 classifies source rows as personal, financial or operational data, and a
-- broken row is not less personal than a valid one — a "just for debugging" jsonb
-- of the source record would be the largest unclassified copy of production
-- personal data on the platform, held indefinitely because nobody prunes a
-- quarantine table.
--
-- What is stored instead: the legacy identity (a reference, resolvable by anyone
-- with legitimate access to the source), a reason code from the approved
-- quarantine vocabulary, and a reference to sanitized diagnostic evidence held in
-- the protected evidence store. The reason code is pattern-constrained rather
-- than free text specifically so that it cannot become the field a diagnosing
-- engineer pastes the failing row into.
CREATE TABLE migration.quarantine_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,
    legacy_id varchar(255) NOT NULL,
    reason_code varchar(64) NOT NULL,
    -- A pointer into the protected evidence store, in the shape ADR 0027 already
    -- uses for audit.audit_events.evidence_reference. Null while the diagnosis is
    -- only a reason code.
    sanitized_evidence_reference varchar(512),
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    -- How the item was settled: re-imported after a source fix, mapped by hand
    -- under review, or accepted as not migratable. A code and not a status value,
    -- so the blocking question stays a single predicate on status.
    resolution_code varchar(64),
    resolved_by varchar(255),
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- Two values, not four. A quarantine item either still blocks or it does not,
    -- and the flavour of the settlement lives in resolution_code. Splitting
    -- "resolved" from "dismissed" at the status level would give the cutover gate
    -- two predicates to keep in step and eventually one of them would be missed.
    CONSTRAINT ck_quarantine_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_quarantine_reason_code CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_quarantine_resolution_code CHECK (
        resolution_code IS NULL OR resolution_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_quarantine_entity_type CHECK (entity_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_quarantine_legacy_id CHECK (length(btrim(legacy_id)) > 0),
    -- A resolved item names who settled it, when, and how. Written as an equality
    -- against the status so a resolution cannot be recorded without an owner and
    -- an open item cannot carry a stale one.
    CONSTRAINT ck_quarantine_resolution CHECK (
        (status = 'RESOLVED') = (resolution_code IS NOT NULL)),
    CONSTRAINT ck_quarantine_resolver CHECK (
        (resolution_code IS NULL) = (resolved_by IS NULL)
        AND (resolved_by IS NULL) = (resolved_at IS NULL)),

    CONSTRAINT fk_quarantine_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES migration.runs (tenant_id, id),
    -- One quarantine item per legacy identity per run. A retried page must not
    -- file the same broken row twice, or the open count that gates cutover
    -- reports a backlog that does not exist.
    CONSTRAINT uq_quarantine_item UNIQUE (run_id, entity_type, legacy_id)
);

CREATE INDEX ix_quarantine_open
    ON migration.quarantine_items (tenant_id, entity_type, created_at)
    WHERE status = 'OPEN';

CREATE INDEX ix_quarantine_run ON migration.quarantine_items (run_id);

COMMENT ON TABLE migration.quarantine_items IS
    'ADR 0024/0029 a legacy row that could not be migrated, held as a reference, a reason code and sanitized evidence. There is deliberately no payload column.';

COMMENT ON COLUMN migration.quarantine_items.sanitized_evidence_reference IS
    'Pointer into the protected evidence store, in the shape ADR 0027 uses for audit evidence. The evidence itself never lands in this schema.';

-- ------------------------------------------------------ reconciliation results

-- One rule, evaluated once, against one dimension.
--
-- ADR 0024 is explicit that a dashboard summary is not approval evidence, so a
-- result stores the rule and its version, both sides of the comparison, and the
-- reference to the sampled discrepancies — enough to re-derive the finding
-- months later without re-running anything.
--
-- The comparison is typed rather than stringly. Counts and money are exact
-- integers (money in minor units with its currency, never a float), checksums are
-- hex digests, and the three cannot be confused for one another because the kind
-- decides which columns must be present. Storing "expected" as free text would
-- make "0" and "0.00" and "zero" all valid and none comparable.
CREATE TABLE migration.reconciliation_results (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    -- Copied from the run so the cutover gate can ask "does this scope have an
    -- open critical difference" with one indexed predicate and no join. The
    -- composite foreign key below is what keeps the copy honest: it references
    -- (tenant_id, id, scope_id) on runs, so a result cannot name a scope its own
    -- run does not belong to.
    scope_id uuid NOT NULL,

    rule_code varchar(64) NOT NULL,
    -- ADR 0024 requires the rule version alongside the result. A rule that was
    -- loosened after the fact must not be able to make a past approval look
    -- stricter than it was.
    rule_version integer NOT NULL,
    -- Which slice the rule was evaluated over: a currency, a provider, a status.
    -- Empty string means the rule has no dimension — a sentinel rather than NULL
    -- for the same reason the scope claim indexes are partial, because NULL would
    -- not compare equal and the unique key below would stop deduplicating.
    dimension_key varchar(128) NOT NULL DEFAULT '',

    severity varchar(16) NOT NULL,
    measure_kind varchar(16) NOT NULL,
    -- Exact integers. For AMOUNT these are minor units of `currency`; for COUNT
    -- they are row counts. numeric(38,0) rather than bigint because a
    -- platform-wide money total in a minor unit currency has more headroom than
    -- anyone wants to think about during a cutover window, and rather than
    -- double or numeric with a scale because money that rounds is money that
    -- reconciles by accident.
    expected_value numeric(38, 0),
    actual_value numeric(38, 0),
    difference_value numeric(38, 0),
    currency char(3),
    expected_checksum char(64),
    actual_checksum char(64),

    -- Pointer to the sampled discrepancies in the protected evidence store, under
    -- the same rule as quarantine: a reference, never the rows.
    sample_reference varchar(512),

    status varchar(16) NOT NULL DEFAULT 'OPEN',
    approved_by varchar(255),
    approved_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_reconciliation_severity CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
    CONSTRAINT ck_reconciliation_status CHECK (status IN ('OPEN', 'APPROVED', 'RESOLVED')),
    CONSTRAINT ck_reconciliation_rule_code CHECK (rule_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_reconciliation_rule_version CHECK (rule_version > 0),
    CONSTRAINT ck_reconciliation_measure_kind CHECK (
        measure_kind IN ('COUNT', 'AMOUNT', 'CHECKSUM')),
    -- Each kind names exactly which columns it uses and which must be absent.
    -- The three branches are exhaustive over measure_kind, so there is no fourth
    -- shape in which a money difference could be recorded without its currency.
    CONSTRAINT ck_reconciliation_measure CHECK (
        (measure_kind = 'COUNT'
            AND expected_value IS NOT NULL AND actual_value IS NOT NULL
            AND currency IS NULL
            AND expected_checksum IS NULL AND actual_checksum IS NULL)
        OR (measure_kind = 'AMOUNT'
            AND expected_value IS NOT NULL AND actual_value IS NOT NULL
            AND currency IS NOT NULL
            AND expected_checksum IS NULL AND actual_checksum IS NULL)
        OR (measure_kind = 'CHECKSUM'
            AND expected_value IS NULL AND actual_value IS NULL
            AND currency IS NULL
            AND expected_checksum IS NOT NULL AND actual_checksum IS NOT NULL)),
    -- The stored difference cannot disagree with the two sides it came from.
    -- IS NOT DISTINCT FROM rather than `=` so the checksum case, where all three
    -- are null, satisfies it instead of evaluating to unknown and passing for the
    -- wrong reason.
    CONSTRAINT ck_reconciliation_difference CHECK (
        difference_value IS NOT DISTINCT FROM (actual_value - expected_value)),
    CONSTRAINT ck_reconciliation_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_reconciliation_checksums CHECK (
        (expected_checksum IS NULL OR expected_checksum ~ '^[0-9a-f]{64}$')
        AND (actual_checksum IS NULL OR actual_checksum ~ '^[0-9a-f]{64}$')),
    -- An approved difference names its approver and when. A difference that was
    -- fixed rather than accepted has no approver, and the two must stay
    -- distinguishable: "we corrected it" and "we agreed to live with it" are
    -- different answers to an auditor.
    CONSTRAINT ck_reconciliation_approval CHECK (
        (status = 'APPROVED') = (approved_by IS NOT NULL)),
    CONSTRAINT ck_reconciliation_approval_time CHECK (
        (approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_reconciliation_resolution CHECK (
        (status = 'RESOLVED') = (resolved_at IS NOT NULL)),

    CONSTRAINT fk_reconciliation_run FOREIGN KEY (tenant_id, run_id, scope_id)
        REFERENCES migration.runs (tenant_id, id, scope_id),
    -- One result per rule per dimension per run. A rule retried inside one run
    -- would otherwise report the same difference twice and the gate would count a
    -- single discrepancy as two.
    CONSTRAINT uq_reconciliation_result UNIQUE (run_id, rule_code, dimension_key)
);

-- The gate query, and the reason scope_id is denormalized onto this table. Every
-- transition attempt asks whether this scope has an unresolved critical
-- difference, so it must be one index probe and not a scan joined to runs.
--
-- APPROVED clears the gate as surely as RESOLVED does: ADR 0024 defines both
-- zero-tolerance and approved-tolerance rules, and an accepted difference that
-- still blocked would leave operators with no way forward except editing the
-- evidence.
CREATE INDEX ix_reconciliation_blocking
    ON migration.reconciliation_results (tenant_id, scope_id)
    WHERE severity = 'CRITICAL' AND status = 'OPEN';

CREATE INDEX ix_reconciliation_run ON migration.reconciliation_results (run_id, severity);

COMMENT ON TABLE migration.reconciliation_results IS
    'ADR 0024 one versioned rule evaluated over one dimension, with both sides of the comparison. An open CRITICAL row blocks every transition of its scope.';

COMMENT ON COLUMN migration.reconciliation_results.dimension_key IS
    'The slice the rule was evaluated over — a currency, a provider, an order status. Empty string, never NULL, so the uniqueness of (run, rule, dimension) still holds for undimensioned rules.';

-- ------------------------------------------------------------ cutover decisions

-- Who transferred ownership, on what evidence, and whether anyone agreed.
--
-- Append-only: the application role below gets INSERT and SELECT and nothing
-- else. A cutover decision is the record that a human accepted responsibility for
-- moving a capability's writer, and a record that can be edited afterwards is
-- worth nothing at the review where it matters.
--
-- ADR 0024's sketch spells the transition as from_mode/to_mode. It is recorded
-- here as from_state/to_state instead, because the modes are derived from the
-- state (OwnershipModes) and recording only the consequence would make two
-- decisions arriving at TARGET_ONLY from CANARY and from ROLLING_BACK
-- indistinguishable — and those are opposite decisions.
CREATE TABLE migration.cutover_decisions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    from_state varchar(24) NOT NULL,
    to_state varchar(24) NOT NULL,
    -- The scope version this decision was taken against, per ADR 0031. It is
    -- both the optimistic-concurrency token and part of the evidence: it says
    -- which revision of the scope the approver was actually looking at.
    scope_version integer NOT NULL,

    decision varchar(16) NOT NULL,
    reason varchar(1000) NOT NULL,
    -- The figures the decision rested on: watermarks, counts, checksums, the
    -- reconciliation run ids that cleared, the observed soak window. Aggregate
    -- evidence and references only — this is not a place to snapshot rows.
    evidence_snapshot jsonb NOT NULL,

    requested_by varchar(255) NOT NULL,
    -- The person who decided, whichever way they decided. Named decided_by and
    -- not approved_by as the sketch spells it, matching audit.approval_requests,
    -- because the refuser of a cutover is as much a fact as the approver.
    decided_by varchar(255),
    -- The ADR 0027 maker-checker request this decision discharges, where the
    -- transition required one. Null for transitions that policy does not gate.
    approval_request_id uuid,
    -- ADR 0031. A retried "approve cutover" must not apply twice; the second
    -- application would move a scope that had already moved on.
    idempotency_key varchar(255) NOT NULL,
    requested_at timestamptz NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_cutover_from_state CHECK (from_state IN (
        'DISCOVERY', 'MAPPING_APPROVED', 'BACKFILLING', 'CATCHING_UP',
        'SHADOW_READING', 'CANARY', 'CUTOVER_READY', 'TARGET_OWNED',
        'ROLLBACK_WINDOW', 'LEGACY_READ_ONLY', 'RETIRED', 'PAUSED',
        'BLOCKED_RECONCILIATION', 'ROLLING_BACK')),
    CONSTRAINT ck_cutover_to_state CHECK (to_state IN (
        'DISCOVERY', 'MAPPING_APPROVED', 'BACKFILLING', 'CATCHING_UP',
        'SHADOW_READING', 'CANARY', 'CUTOVER_READY', 'TARGET_OWNED',
        'ROLLBACK_WINDOW', 'LEGACY_READ_ONLY', 'RETIRED', 'PAUSED',
        'BLOCKED_RECONCILIATION', 'ROLLING_BACK')),
    -- A transition to the state you are already in is not a transition; it is a
    -- duplicate command that should have been refused upstream, and recording it
    -- hides that.
    CONSTRAINT ck_cutover_moves CHECK (from_state <> to_state),
    -- A decision nobody ever made and a decision somebody refused are different
    -- facts. WITHDRAWN and EXPIRED carry no decider by construction; APPROVED and
    -- REFUSED must name one, so "never approved" can never be read as a refusal
    -- or the reverse.
    CONSTRAINT ck_cutover_decision CHECK (
        decision IN ('APPROVED', 'REFUSED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT ck_cutover_decider CHECK (
        (decision IN ('APPROVED', 'REFUSED')) = (decided_by IS NOT NULL)),
    -- Four eyes, as ADR 0027 requires it of every approval, enforced where a
    -- service cannot be bypassed. The person who asked to move a capability's
    -- writer may not be the person who agreed.
    CONSTRAINT ck_cutover_four_eyes CHECK (
        decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_cutover_evidence CHECK (jsonb_typeof(evidence_snapshot) = 'object'),
    CONSTRAINT ck_cutover_scope_version CHECK (scope_version >= 1),
    CONSTRAINT ck_cutover_window CHECK (decided_at >= requested_at),

    CONSTRAINT fk_cutover_scope FOREIGN KEY (tenant_id, scope_id)
        REFERENCES migration.scopes (tenant_id, id),
    CONSTRAINT fk_cutover_approval_request FOREIGN KEY (approval_request_id)
        REFERENCES audit.approval_requests (id),
    CONSTRAINT uq_cutover_idempotency UNIQUE (tenant_id, idempotency_key)
);

-- At most one approved decision per scope version. Two approvals racing on one
-- version would both believe they moved the scope, and one of them would be
-- describing a transition that never happened while its evidence snapshot said
-- otherwise. Refusals are deliberately outside the index: a refusal does not
-- advance the version, and a scope may be refused several times before it is
-- approved once.
CREATE UNIQUE INDEX ux_cutover_approved_per_version
    ON migration.cutover_decisions (scope_id, scope_version)
    WHERE decision = 'APPROVED';

CREATE INDEX ix_cutover_decisions_scope
    ON migration.cutover_decisions (tenant_id, scope_id, decided_at DESC);

COMMENT ON TABLE migration.cutover_decisions IS
    'ADR 0024 append-only ownership-transfer evidence. A decision that was never made carries no decider; a refusal names the person who refused.';

COMMENT ON COLUMN migration.cutover_decisions.evidence_snapshot IS
    'The aggregate figures the decision rested on: watermarks, counts, checksums, cleared reconciliation runs, observed soak window. References and totals only, never source rows.';

-- Transitions a person did not decide — a failing gate forcing
-- BLOCKED_RECONCILIATION, a supervisor pausing a runaway catch-up — are not
-- written here. They are audited through ADR 0027 in the transaction that makes
-- them, because a decision table holding rows nobody decided would make the
-- approver column meaningless on exactly the rows a reviewer reads first.

-- --------------------------------------------------------------------- grants

GRANT USAGE ON SCHEMA migration TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON migration.programs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON migration.scopes TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON migration.runs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON migration.entity_mappings TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON migration.quarantine_items TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON migration.reconciliation_results TO horecaos_application;
GRANT SELECT, INSERT ON migration.cutover_decisions TO horecaos_application;

REVOKE UPDATE, DELETE, TRUNCATE ON migration.cutover_decisions FROM horecaos_application;

-- No table here grants DELETE, and the omission is the point.
--
-- A scope is the record of who owned a capability and when, so it outlives the
-- migration; a run and its counters are what a reconciliation was compared
-- against; a crosswalk row is how a target entity is explained years later; a
-- quarantine item is the proof that a legacy row was seen and consciously not
-- migrated. Deleting any of them turns "we can account for every row" into "we
-- can account for the rows still present", which is the claim ADR 0024 exists to
-- make unnecessary.
