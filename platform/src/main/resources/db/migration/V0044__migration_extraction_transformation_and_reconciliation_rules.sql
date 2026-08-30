-- ADR 0024: the half of the migration that moves data.
--
-- V0024 built the control plane and stopped there: it can say who owns a
-- capability, what a run counted, and what a reconciliation proved, but nothing
-- extracts, transforms or imports. This migration adds the three durable facts
-- the moving half needs and that a restart must not lose.
--
-- First, where extraction got to. ADR 0024 requires runs to be restartable and
-- to checkpoint only after a target commit, and `migration.runs.checkpoint` is
-- the wrong place for it: a run is one execution, while the position in the
-- source outlives every execution and is what the next one resumes from. A
-- cursor on the run would mean a killed backfill's successor either re-reads
-- from the beginning or inherits a checkpoint by guessing which earlier run to
-- read it off.
--
-- Second, which transformation produced a row. `migration.runs` and
-- `migration.entity_mappings` already carry `transformation_version`; what was
-- missing is anything that says what version 3 *is*, so "the mapping changed"
-- was a claim nobody could check. The registry holds a digest over the
-- transformation's declared rules, so a changed mapping is detected by the
-- migrator refusing to start rather than by a reconciliation noticing two
-- semantics in one table months later.
--
-- Third, the rule library. `migration.reconciliation_results` records
-- `rule_code` and `rule_version` and has nowhere to resolve them. ADR 0024 is
-- explicit that a rule loosened after the fact must not make a past approval
-- look stricter than it was, which needs the rule's severity and tolerance to be
-- stored per version and never edited in place.
--
-- The same isolation rule as V0024 applies throughout: nothing derived from a
-- source row lands here. Cursors hold keys and watermarks, the registry holds
-- digests, and the rule library holds thresholds.

-- ------------------------------------------------------- the source's timezone

-- The zone every naive legacy timestamp is in.
--
-- `docs/domains/legacy-profile-findings.md` finding 2, structural: the legacy
-- `BaseModel` gives every table `created` and `updated` typed without a timezone
-- and defaulted to `datetime.now`, which is the naive local time of the
-- application server process. Nothing in the legacy schema records the zone and
-- nothing in its data can establish it; it is read from the production server's
-- `TZ`, `/etc/localtime`, or container spec.
--
-- Nullable, with no default, and that is the entire point. A default of 'UTC'
-- would be indistinguishable from a deployment that had actually been checked,
-- and reading Asia/Tashkent timestamps as UTC shifts every historical order five
-- hours across the business-date boundary that the daily order number depends
-- on — a day's orders renumber into the wrong day, and the reconciliation meant
-- to catch it then compares two equally wrong figures. Extraction refuses to
-- start while this is null.
--
-- Not coupled to `status` by a CHECK either: backfilling a value for whatever
-- rows exist would be inventing the answer in a migration script, which is the
-- same error with a longer paper trail.
ALTER TABLE migration.programs
    ADD COLUMN source_time_zone varchar(64);

ALTER TABLE migration.programs
    ADD CONSTRAINT ck_program_source_time_zone CHECK (
        source_time_zone IS NULL OR source_time_zone ~ '^[A-Za-z][A-Za-z0-9_+/-]{1,63}$');

COMMENT ON COLUMN migration.programs.source_time_zone IS
    'IANA zone the legacy application server ran in, applied to its naive timestamps. Null until read from the production deployment; extraction refuses to run without it, because assuming UTC shifts every order across the business-date boundary.';

-- -------------------------------------------------------------- source cursors

-- Where paged extraction of one entity type in one scope got to.
--
-- One row per (scope, entity type), surviving every run over it. The stable key
-- is the pagination contract: pages are keyset-ordered on a column the source
-- cannot renumber, never on an offset, because the legacy tables are live while
-- this reads them and an offset silently skips rows when a page shifts
-- underneath the reader. The row skipped would be a legacy record nobody then
-- accounts for, which is exactly the claim ADR 0024 exists to make.
--
-- `last_stable_key` advances in the same transaction as the target write it
-- covers. Both live in this database — the control plane and the target are
-- different schemas of one PostgreSQL — so "checkpoint only after a target
-- commit" is available in its strongest form: the same commit. There is no
-- window in which a page is imported and unrecorded, or recorded and not
-- imported.
CREATE TABLE migration.source_cursors (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,

    -- The source column pages are ordered by, as the source spells it
    -- ('id', 'created'). Recorded rather than assumed, because changing it
    -- invalidates every key already stored: 'id' > 4200 and 'created' >
    -- '2026-02-01' are not comparable, and resuming one cursor with the other's
    -- bound would skip or re-read an arbitrary slice.
    stable_key_column varchar(64) NOT NULL,
    -- The exclusive lower bound for the next page, as text for the reason
    -- entity_mappings.legacy_id is text: the legacy estate keys on bigints,
    -- uuids and strings, and coercing them into one type is where a cursor
    -- starts inventing an ordering.
    last_stable_key varchar(255),

    -- The change watermark a catch-up run resumes from, which is a different
    -- question from the backfill's position: the backfill asks "which rows have
    -- I not seen", the catch-up asks "what changed since". A finished backfill
    -- has an exhausted key cursor and a live watermark.
    watermark varchar(512),
    watermark_column varchar(64),

    -- The run whose target commit last advanced this cursor. A cursor that
    -- cannot name the run behind its position is a position nobody can audit,
    -- and a remediation reading it needs to know whether the run that set it
    -- applied the transformation the remediation is replacing.
    advanced_by_run_id uuid NOT NULL,
    transformation_version integer NOT NULL,

    -- Committed, not scanned: these move only with the target write, so they are
    -- the figure a restart can trust and the one a count reconciliation is
    -- compared against. The run counters remain the per-execution tally.
    pages_committed bigint NOT NULL DEFAULT 0,
    rows_committed bigint NOT NULL DEFAULT 0,

    -- Set when a page comes back short: the source has no more rows past the
    -- key. Recorded rather than re-derived, because "the last page was empty"
    -- and "nobody has read this yet" are both an absent next key.
    exhausted boolean NOT NULL DEFAULT false,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_source_cursor_entity_type CHECK (entity_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_source_cursor_stable_key_column CHECK (
        stable_key_column ~ '^[a-z][a-z0-9_]{0,63}$'),
    -- Pair completeness stated as an equality. A watermark with no column names
    -- a value nobody can compare, and a column with no watermark is a catch-up
    -- that would restart from the beginning of time. The OR-of-shapes form reads
    -- identically and leaves the first case legal.
    CONSTRAINT ck_source_cursor_watermark CHECK (
        (watermark IS NULL) = (watermark_column IS NULL)),
    CONSTRAINT ck_source_cursor_watermark_column CHECK (
        watermark_column IS NULL OR watermark_column ~ '^[a-z][a-z0-9_]{0,63}$'),
    CONSTRAINT ck_source_cursor_counts CHECK (pages_committed >= 0 AND rows_committed >= 0),
    CONSTRAINT ck_source_cursor_transformation_version CHECK (transformation_version > 0),
    CONSTRAINT ck_source_cursor_version CHECK (version >= 1),

    CONSTRAINT fk_source_cursor_scope FOREIGN KEY (tenant_id, scope_id)
        REFERENCES migration.scopes (tenant_id, id),
    CONSTRAINT fk_source_cursor_run FOREIGN KEY (tenant_id, advanced_by_run_id)
        REFERENCES migration.runs (tenant_id, id),
    -- One position per entity type per scope. Two would be two migrators paging
    -- the same table against different bounds, each believing it had covered the
    -- gap the other left.
    CONSTRAINT uq_source_cursor UNIQUE (scope_id, entity_type)
);

COMMENT ON TABLE migration.source_cursors IS
    'ADR 0024 restartable extraction position per scope and entity type. Advanced in the same transaction as the target write it covers, so no page is ever imported and unrecorded.';

COMMENT ON COLUMN migration.source_cursors.last_stable_key IS
    'Exclusive lower bound for the next page, in the source key''s own spelling. Text because the legacy estate keys on bigints, uuids and strings.';

COMMENT ON COLUMN migration.source_cursors.exhausted IS
    'The source had no rows past last_stable_key. Distinguishes a finished backfill from one nobody has started, which otherwise look alike.';

-- ------------------------------------------------------- transformation registry

-- What a transformation version means, so that changing a mapping is detectable.
--
-- ADR 0024: "Transformation version is recorded so a changed mapping creates an
-- explicit remediation run rather than silently mixing semantics." Recording the
-- number was V0024's half; this is the other half. The migrator computes a
-- digest over its own declared rules at startup and compares it against the
-- current registered version. Equal, it runs. Different, it refuses, and an
-- operator declares a new version and starts a REMEDIATION run over the rows the
-- old one wrote — which is findable, because entity_mappings carries the version
-- on every row.
--
-- Append-only by grant. A digest edited in place would let a mapping change
-- disguise itself as the mapping that was already approved.
CREATE TABLE migration.transformations (
    id uuid PRIMARY KEY,
    program_id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,
    transformation_version integer NOT NULL,

    -- sha-256 over the transformation's declared rules, in its own canonical
    -- form. Hex like every other digest in this database so a comparison is
    -- string equality and not a format negotiation.
    rule_digest char(64) NOT NULL,
    -- What changed and why, for the operator reading a remediation months later.
    -- Prose about rules, never an example row.
    summary varchar(1000) NOT NULL,

    declared_by varchar(255) NOT NULL,
    -- Null while this is the version migrators must run at. Set when a newer
    -- version supersedes it; the rows it wrote keep pointing here.
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_transformation_entity_type CHECK (entity_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_transformation_version CHECK (transformation_version > 0),
    CONSTRAINT ck_transformation_digest CHECK (rule_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transformation_summary CHECK (length(btrim(summary)) > 0),

    CONSTRAINT fk_transformation_program FOREIGN KEY (program_id)
        REFERENCES migration.programs (id),
    CONSTRAINT uq_transformation_version UNIQUE (program_id, entity_type, transformation_version),
    -- One digest per version, which is what makes the version a name for a
    -- mapping rather than a label somebody reused.
    CONSTRAINT uq_transformation_digest UNIQUE (program_id, entity_type, rule_digest)
);

-- Exactly one live version per entity type per program. Two would leave the
-- migrator's startup check with a choice, and whichever it picked would be the
-- silent semantic mixing the version exists to prevent.
CREATE UNIQUE INDEX ux_transformation_current
    ON migration.transformations (program_id, entity_type)
    WHERE retired_at IS NULL;

COMMENT ON TABLE migration.transformations IS
    'ADR 0024 what each transformation_version means: a digest over the declared mapping rules. A migrator whose digest differs from the current version refuses to run, forcing a remediation rather than mixed semantics.';

-- ------------------------------------------------------ reconciliation rules

-- The rule library, versioned, so a past approval cannot be re-read under a
-- rule that was loosened afterwards.
--
-- Evaluation lives in code — a rule is a pair of queries over two databases —
-- and this table is its durable declaration: which capability and entity family
-- it covers, how bad a difference is, and what tolerance, if any, was approved.
-- `migration.reconciliation_results.rule_code` and `rule_version` resolve here.
--
-- The severity and the tolerance are on the *version*, not the rule. Loosening a
-- rule is declaring a new version and retiring the old one, so results recorded
-- under version 1 stay readable under version 1's severity forever.
CREATE TABLE migration.reconciliation_rules (
    id uuid PRIMARY KEY,
    rule_code varchar(64) NOT NULL,
    rule_version integer NOT NULL,
    -- MigrationCapability, matching migration.scopes.capability. Which gate this
    -- rule stands in front of.
    capability varchar(16) NOT NULL,
    -- The entity family the rule measures, or the empty string for a rule that
    -- spans the capability. Empty string rather than NULL for the same reason
    -- reconciliation_results.dimension_key uses one: NULL does not compare equal
    -- and the unique key below would stop deduplicating.
    entity_type varchar(64) NOT NULL DEFAULT '',

    severity varchar(16) NOT NULL,
    measure_kind varchar(16) NOT NULL,

    tolerance_kind varchar(16) NOT NULL DEFAULT 'ZERO',
    -- Exact integers, in the measure's own unit: rows for COUNT, minor units for
    -- AMOUNT. numeric(38,0) and never a float, because a tolerance that rounds
    -- is a tolerance that accepts a difference nobody agreed to. For UZS a minor
    -- unit is a whole som, so a tolerance of 1 here is one som and not one tiyin.
    tolerance_value numeric(38, 0) NOT NULL DEFAULT 0,

    -- Why this rule exists and what a difference in it would mean, in prose. It
    -- is what the approver reads at three in the morning.
    rationale varchar(1000) NOT NULL,

    declared_by varchar(255) NOT NULL,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_reconciliation_rule_code CHECK (rule_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_reconciliation_rule_version_positive CHECK (rule_version > 0),
    CONSTRAINT ck_reconciliation_rule_capability CHECK (capability IN (
        'TENANCY', 'IDENTITY', 'CUSTOMERS', 'MEDIA', 'CATALOG', 'INVENTORY',
        'PRICING', 'ORDERS', 'PAYMENTS', 'FULFILLMENT', 'NOTIFICATIONS',
        'CONFIGURATION', 'REPORTING')),
    CONSTRAINT ck_reconciliation_rule_entity_type CHECK (
        entity_type = '' OR entity_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_reconciliation_rule_severity CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
    CONSTRAINT ck_reconciliation_rule_measure CHECK (
        measure_kind IN ('COUNT', 'AMOUNT', 'CHECKSUM')),
    CONSTRAINT ck_reconciliation_rule_tolerance_kind CHECK (
        tolerance_kind IN ('ZERO', 'ABSOLUTE')),
    CONSTRAINT ck_reconciliation_rule_tolerance CHECK (
        (tolerance_kind = 'ZERO') = (tolerance_value = 0)),
    -- A checksum has no arithmetic, so it has no tolerance. Two digests are
    -- equal or they are not, and a tolerance against one would be a number
    -- nothing consults.
    CONSTRAINT ck_reconciliation_rule_checksum_tolerance CHECK (
        measure_kind <> 'CHECKSUM' OR tolerance_kind = 'ZERO'),
    -- A rule that blocks cutover admits no difference. ADR 0024's
    -- approved-tolerance path is a decision about a *result* — an operator
    -- accepting a specific difference, with their name on it — and folding it
    -- into the rule would approve every future difference in advance.
    CONSTRAINT ck_reconciliation_rule_critical_is_exact CHECK (
        severity <> 'CRITICAL' OR tolerance_kind = 'ZERO'),
    CONSTRAINT ck_reconciliation_rule_rationale CHECK (length(btrim(rationale)) > 0),

    CONSTRAINT uq_reconciliation_rule_version UNIQUE (rule_code, rule_version)
);

-- One live version per rule code, for the same reason there is one live
-- transformation: a suite that had to choose which version to evaluate under
-- would produce evidence nobody can reproduce.
CREATE UNIQUE INDEX ux_reconciliation_rule_current
    ON migration.reconciliation_rules (rule_code)
    WHERE retired_at IS NULL;

CREATE INDEX ix_reconciliation_rules_capability
    ON migration.reconciliation_rules (capability, entity_type)
    WHERE retired_at IS NULL;

COMMENT ON TABLE migration.reconciliation_rules IS
    'ADR 0024 versioned rule library. Severity and tolerance belong to the version, so a rule loosened later cannot make a past approval look stricter than it was.';

COMMENT ON COLUMN migration.reconciliation_rules.tolerance_value IS
    'Exact integer in the measure''s own unit: rows for COUNT, minor units for AMOUNT. For UZS a minor unit is a whole som.';

-- The four mandatory rules ADR 0024 names, seeded at version 1 so the first run
-- has something to resolve its results against. Declared by the migration rather
-- than by an operator because they are not optional: ADR 0024 lists them under
-- "Mandatory examples", and a program that could start without them would be a
-- program whose first reconciliation had no rules to fail.
INSERT INTO migration.reconciliation_rules (
    id, rule_code, rule_version, capability, entity_type, severity, measure_kind,
    tolerance_kind, tolerance_value, rationale, declared_by)
VALUES
    ('4a1d0a2e-0d3a-4f1e-9a71-9b3a1c5f0001', 'AUTHORITATIVE_ID_COUNT', 1, 'ORDERS', '',
     'CRITICAL', 'COUNT', 'ZERO', 0,
     'Every legacy row in scope has exactly one crosswalk entry. Counts alone are weak evidence, which is why this rule pairs with the checksum below, but a count that disagrees means rows were dropped or duplicated and nothing further is worth measuring until it does not.',
     'V0044'),
    ('4a1d0a2e-0d3a-4f1e-9a71-9b3a1c5f0002', 'AUTHORITATIVE_ID_CHECKSUM', 1, 'ORDERS', '',
     'CRITICAL', 'CHECKSUM', 'ZERO', 0,
     'The ordered digest of legacy identifiers matches the digest of the identifiers the crosswalk holds. This is what makes the count meaningful: a run that dropped one row and duplicated another has the right count and the wrong set, and ADR 0024 rejects counts on their own as reconciliation evidence for exactly that reason.',
     'V0044'),
    ('4a1d0a2e-0d3a-4f1e-9a71-9b3a1c5f0003', 'MONEY_TOTAL_BY_CURRENCY_AND_STATUS', 1, 'PAYMENTS', '',
     'CRITICAL', 'AMOUNT', 'ZERO', 0,
     'Order and payment totals agree per currency and per status, in minor units. Sliced rather than summed: a total that nets a shortfall in completed orders against an excess in cancelled ones reconciles to zero while both figures are wrong, and the legacy writer computes order price, delivery price and packaging separately, so they are compared separately.',
     'V0044'),
    ('4a1d0a2e-0d3a-4f1e-9a71-9b3a1c5f0004', 'CROSS_TENANT_ANCESTRY', 1, 'TENANCY', '',
     'CRITICAL', 'COUNT', 'ZERO', 0,
     'No imported row has an ancestor in another tenant, and none has a null ancestor where the target requires one. Expected is zero and any other number blocks: a legacy order whose branch is null has no brand and no tenant, and ADR 0024 quarantines it rather than assigning it to a convenient parent.',
     'V0044');

-- --------------------------------------------------------------------- grants

GRANT SELECT, INSERT, UPDATE ON migration.source_cursors TO qoida_application;
GRANT SELECT, INSERT ON migration.transformations TO qoida_application;
GRANT SELECT, INSERT ON migration.reconciliation_rules TO qoida_application;

-- Retiring a version is an UPDATE of retired_at, and it is deliberately not
-- granted. Both tables are declarations that results and crosswalk rows point
-- back to; retirement is a decision with an approver, taken through the same
-- door as a cutover, and until that endpoint exists it is a migration rather
-- than something the application can do to its own evidence.
REVOKE UPDATE, DELETE, TRUNCATE ON migration.transformations FROM qoida_application;
REVOKE UPDATE, DELETE, TRUNCATE ON migration.reconciliation_rules FROM qoida_application;

-- No DELETE anywhere here, for V0024's reason. A cursor is the record of how far
-- a source was read, and deleting one turns "we can account for every row" into
-- "we can account for the rows still present".
