-- The two ways yesterday's role switch left the application worse off than it
-- looked: a SECURITY DEFINER function that a temporary table can redirect, and
-- an upsert that no longer has the privilege it always needed.
--
--
-- ===========================================================================
-- 1. THE SHADOWING. pg_temp IS SEARCHED FIRST WHEN IT IS NOT NAMED
-- ===========================================================================
--
-- V0070 and V0075 pinned `search_path = pg_catalog` on all four functions the
-- application may call, and treated that as closing the SECURITY DEFINER hazard.
-- It does not close it. PostgreSQL searches the session's temporary schema
-- BEFORE anything written in `search_path` whenever `pg_temp` is not named in it
-- explicitly — before `pg_catalog` included — for relation names and for type
-- names alike. The manual's "Writing SECURITY DEFINER Functions Safely" says so
-- and gives the remedy: write `pg_temp` yourself, LAST, so that the implicit
-- first position becomes an explicit last one.
--
-- All four functions read the catalogue through unqualified relation names —
-- `pg_class`, `pg_namespace`, `pg_inherits` — so all four were bendable. And
-- `TEMPORARY` on the database is one of the two privileges PostgreSQL grants to
-- PUBLIC by default; no migration in this repository had ever revoked it, so
-- `horecaos_application` held it and `CREATE TEMP TABLE` succeeded.
--
-- That is enough for a foothold on the application's connection — an injection,
-- a deserialization bug, anything that can run one statement of its choosing —
-- to reach the worst verb the platform hands out. Reproduced against a real
-- container before this migration was written, as `horecaos_application` and
-- nothing more:
--
--     CREATE TEMP TABLE pg_class AS
--     SELECT old.oid,                                    -- an EXPIRED partition's oid
--            'courier_location_tracks_'||to_char(current_date,'YYYYMMDD'),  -- TODAY's name
--            old.relpartbound, old.relnamespace          -- ...wearing 2020's bound
--       FROM pg_catalog.pg_class old ... ;
--     -- with pg_inherits and pg_namespace forged to match
--     SELECT fulfillment.sweep_expired_track_partitions(30, false);
--
--     BEFORE: today partition exists = 1
--     DROPPED: courier_location_tracks_20260825
--     AFTER:  today partition exists = 0
--     AFTER:  genuinely expired 2020 partition exists = 1
--
-- The sweep read a forged catalogue, believed today's partition was five years
-- expired, and issued `DROP TABLE fulfillment.courier_location_tracks_20260825`
-- against the real schema — a day of ADR 0045 courier GPS tracks, which is ADR
-- 0029 personal data about identified self-employed people, destroyed on demand
-- by a caller that holds no DDL and owns nothing. Meanwhile the partition that
-- was genuinely expired survived, because the forgery replaced the catalogue
-- rather than adding to it: the sweep also stopped doing its actual job.
--
-- Three independent things are fixed here, because the arrangement that just
-- failed was one declaration being right in four places.
--
--   * `pg_temp` is named LAST in `search_path` on every one of the four.
--   * Every catalogue read is schema-qualified anyway — `pg_catalog.pg_class`
--     and so on — so a function whose declaration is ever written wrongly again
--     still reads the real catalogue.
--   * `TEMPORARY` is revoked from PUBLIC on this database, so the forgery has
--     nowhere to live in the first place.
--
--
-- WHY REVOKING TEMPORARY IS SAFE HERE
--
-- A privilege is not removed because it is unused-looking; it is removed after
-- establishing that nothing uses it. Nothing does:
--
--   * Flyway connects as `horecaos_migrator`, which owns this database. An owner
--     holds every database privilege implicitly and is unaffected by a REVOKE
--     from PUBLIC, so migrations are untouched.
--   * `src/main/java` contains no `CREATE TEMP`, no `TEMPORARY TABLE`, no
--     `ON COMMIT DROP` and no `pg_temp` — checked across the whole tree.
--   * The three scripts in `tools/migration/` that do create a temporary table
--     run against the LEGACY database with a legacy credential (ADR 0024 Phase
--     0 profiling). They never connect here.
--   * `horecaos_reporting_read` is a NOLOGIN group for reading facts through the
--     ADR 0043 query surface, not a warehouse credential staging its own
--     intermediate tables.
--
-- Sorting and hashing that spill to disk are not this privilege — those are
-- temporary FILES, which need no grant — so no query gets slower or fails.
--
-- If a future BI or bulk-load path genuinely needs staging tables, the answer is
-- `GRANT TEMPORARY ON DATABASE ... TO <that role>` by name, in a forward
-- migration, and not a default that every role in the cluster inherits.
--
--
-- ===========================================================================
-- 2. THE REGRESSION. AN UPSERT IS AN UPDATE THE STATEMENT NEVER SPELLS
-- ===========================================================================
--
-- `JdbcTelemetryStore.upsertTrackWindow` writes one minute of a courier's
-- movement with `ON CONFLICT (window_start, tenant_id, courier_id) DO UPDATE`.
-- V0041 granted `SELECT, INSERT` on `fulfillment.courier_location_tracks` and
-- nothing else, which was correct for a table nothing rewrites and wrong for the
-- statement the code actually issues: an `ON CONFLICT ... DO UPDATE` needs the
-- UPDATE privilege on its target whether or not any row ever conflicts.
--
-- Under the owner connection this never showed. Under `horecaos_application` it is
-- `permission denied for table courier_location_tracks` on EVERY call — not on
-- the conflicting ones, on all of them, because the privilege is checked when
-- the statement is planned. Reproduced under the probe role before this was
-- written; telemetry ingest was refused outright.
--
-- UPDATE is granted on the parent and on the parent only. PostgreSQL checks
-- privileges on the relation named in the query, so a write routed through the
-- partitioned parent is checked there; the daily partitions keep the
-- `SELECT, INSERT` that `fulfillment.ensure_track_partition` gives them, and a
-- statement naming a partition directly still cannot rewrite it.
--
-- The systematic scanner added yesterday walks every schema-qualified table in
-- `src/main/java` and asserts the role holds what the code needs, and it missed
-- this: it reads the verb at the head of the statement, and the head of this one
-- says INSERT. `DatabasePrivilegeTests` is extended in the same change to
-- recognise the three privileges a statement does not spell — `ON CONFLICT DO
-- UPDATE`, a row-locking clause (`FOR UPDATE`, and `FOR SHARE` too: both require
-- UPDATE, verified against the engine rather than read off a doc), and
-- `DELETE ... USING`. Of those, exactly one grant was missing across the whole
-- codebase, and it is the one below.


-- ---------------------------------------------------------------------------
-- The grant the telemetry upsert has always needed
-- ---------------------------------------------------------------------------

GRANT UPDATE ON fulfillment.courier_location_tracks TO horecaos_application;


-- ---------------------------------------------------------------------------
-- Nowhere for a forged catalogue to live
-- ---------------------------------------------------------------------------
--
-- The database name differs between deployments and the test container, so it
-- comes from `current_database()` and goes through `%I`. This is the migration
-- role, which owns the database, so the REVOKE is the owner's to issue.

DO $$
BEGIN
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
END;
$$;


-- ---------------------------------------------------------------------------
-- 1. ADR 0027 — audit.ensure_event_partition
-- ---------------------------------------------------------------------------
--
-- Replaced rather than ALTERed because the catalogue reads inside the body are
-- half the fix. Note that `CREATE OR REPLACE` drops any `SET` clause it does not
-- restate, so `SECURITY DEFINER` and the pinned path are respelled in full here;
-- omitting either would silently undo V0075.
--
-- The body is V0075's, with `pg_class` and `pg_namespace` qualified.

CREATE OR REPLACE FUNCTION audit.ensure_event_partition(p_year integer)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_name text;
BEGIN
    IF p_year IS NULL OR p_year < 2020 OR p_year > 2100 THEN
        RAISE EXCEPTION 'audit.ensure_event_partition manages the years 2020..2100, not %', p_year;
    END IF;

    v_name := 'audit_events_' || to_char(p_year, 'FM0000');

    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c
                 JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'audit' AND c.relname = v_name) THEN
        RETURN false;
    END IF;

    -- Every replica runs the job and their daily timers drift towards each other,
    -- so check-then-act is not idempotent where it matters: two replicas see no
    -- partition, both issue the DDL, and the loser fails on a race whose outcome
    -- was correct. A partition manager that raises on a healthy outcome trains an
    -- operator to mute its alerts. The loser is recognised instead: the block
    -- below is a subtransaction, so the failed CREATE rolls back on its own and
    -- the re-check runs against a catalogue snapshot that now includes the
    -- winner's committed table.
    BEGIN
        EXECUTE format(
            'CREATE TABLE audit.%I PARTITION OF audit.audit_events '
            'FOR VALUES FROM (%L) TO (%L)',
            v_name,
            to_char(p_year, 'FM0000') || '-01-01 00:00:00+00',
            to_char(p_year + 1, 'FM0000') || '-01-01 00:00:00+00');
    EXCEPTION
        WHEN duplicate_table OR unique_violation OR invalid_object_definition THEN
            IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c
                         JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'audit' AND c.relname = v_name) THEN
                RETURN false;
            END IF;
            RAISE;
    END;

    EXECUTE format('GRANT INSERT, SELECT ON audit.%I TO horecaos_application', v_name);
    RETURN true;
END;
$$;

COMMENT ON FUNCTION audit.ensure_event_partition(integer) IS
    'ADR 0027 partition upkeep. Idempotent and race-tolerant across replicas. SECURITY DEFINER (V0075) because the application role holds no DDL rights and this is the only way it is meant to add a partition; EXECUTE is granted by name rather than to PUBLIC, and the only identifier it builds comes from an integer year, never from a caller''s string. search_path names pg_temp LAST and every catalogue read is schema-qualified (V0080) — without both, a caller''s temporary table is searched before pg_catalog and IS the catalogue this function reads.';

REVOKE EXECUTE ON FUNCTION audit.ensure_event_partition(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.ensure_event_partition(integer) TO horecaos_application;


-- ---------------------------------------------------------------------------
-- 2. ADR 0045 — fulfillment.ensure_track_partition
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fulfillment.ensure_track_partition(p_day date)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_name text;
BEGIN
    IF p_day IS NULL OR p_day < DATE '2020-01-01' OR p_day > DATE '2100-01-01' THEN
        RAISE EXCEPTION 'fulfillment.ensure_track_partition manages 2020-01-01..2100-01-01, not %',
            p_day;
    END IF;

    v_name := 'courier_location_tracks_' || to_char(p_day, 'YYYYMMDD');

    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c
                 JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'fulfillment' AND c.relname = v_name) THEN
        RETURN false;
    END IF;

    BEGIN
        EXECUTE format(
            'CREATE TABLE fulfillment.%I PARTITION OF fulfillment.courier_location_tracks '
            'FOR VALUES FROM (%L) TO (%L)',
            v_name,
            to_char(p_day, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char(p_day + 1, 'YYYY-MM-DD') || ' 00:00:00+00');
    EXCEPTION
        WHEN duplicate_table OR unique_violation OR invalid_object_definition THEN
            IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c
                         JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'fulfillment' AND c.relname = v_name) THEN
                RETURN false;
            END IF;
            RAISE;
    END;

    EXECUTE format('GRANT SELECT, INSERT ON fulfillment.%I TO horecaos_application', v_name);
    RETURN true;
END;
$$;

COMMENT ON FUNCTION fulfillment.ensure_track_partition(date) IS
    'ADR 0045 partition upkeep. Idempotent and race-tolerant across replicas. SECURITY DEFINER (V0075) for the reason audit.ensure_event_partition is; the only identifier it builds is a to_char of a date, so there is no slot a caller''s string can reach. The partition grant stays SELECT and INSERT: nothing rewrites a track, the upsert''s UPDATE is checked on the partitioned parent (V0080), and deleting one is the sweep''s job and not a row operation. search_path names pg_temp LAST and every catalogue read is schema-qualified (V0080).';

REVOKE EXECUTE ON FUNCTION fulfillment.ensure_track_partition(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fulfillment.ensure_track_partition(date) TO horecaos_application;


-- ---------------------------------------------------------------------------
-- 3. ADR 0029 — fulfillment.sweep_expired_track_partitions
-- ---------------------------------------------------------------------------
--
-- The one that destroys something, and so the one the forgery aimed at. Read
-- V0075's header for why the application keeps a trigger here and not a verb;
-- everything that made it a trigger rather than a verb — no table parameter, the
-- children read from `pg_inherits` on one named parent, the cutoff from the
-- database's own `current_date`, the retention argument as one term of a
-- `GREATEST` — was sound and is unchanged. What was not sound was reading
-- `pg_inherits`, `pg_class` and `pg_namespace` through names a caller could
-- own. Those three are qualified below, and `pg_temp` is named last.

CREATE OR REPLACE FUNCTION fulfillment.sweep_expired_track_partitions(
        p_retention_days integer,
        p_report_only boolean DEFAULT false)
RETURNS SETOF text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    -- ADR 0045. See V0075: lowering this is a migration, on purpose.
    v_platform_floor constant integer := 30;

    v_days     integer;
    v_cutoff   timestamptz;
    v_child    record;
    v_bound    text;
    v_upper    text[];
    v_expired  text[] := ARRAY[]::text[];
    v_name     text;
BEGIN
    -- The tenant maximum is a bigint column and is capped before the cast, so a
    -- fat-fingered configuration value is a long window rather than an integer
    -- overflow raised from inside a retention job.
    v_days := GREATEST(
        COALESCE(p_retention_days, 0),
        v_platform_floor,
        LEAST(COALESCE((SELECT max(cv.integer_value)
                          FROM tenant.configuration_values cv
                         WHERE cv.key_code = 'telemetry.track_retention_days'
                           AND cv.is_explicit_null = false), 0), 36500)::integer);

    -- The database's clock, deliberately. An application's clock is injected, and
    -- a retention window that an injected clock can move is not a control.
    -- Written as an explicit UTC literal for V0041's reason.
    v_cutoff := (to_char(current_date - v_days, 'YYYY-MM-DD') || ' 00:00:00+00')::timestamptz;

    FOR v_child IN
        SELECT c.oid, c.relname, c.relpartbound
          FROM pg_catalog.pg_inherits i
          JOIN pg_catalog.pg_class c      ON c.oid = i.inhrelid
          JOIN pg_catalog.pg_class p      ON p.oid = i.inhparent
          JOIN pg_catalog.pg_namespace pn ON pn.oid = p.relnamespace
         WHERE pn.nspname = 'fulfillment'
           AND p.relname  = 'courier_location_tracks'
           -- Only the daily partitions this platform provisions. The default is
           -- excluded here by name and again below by having no upper bound.
           AND c.relname ~ '^courier_location_tracks_[0-9]{8}$'
         ORDER BY c.relname
    LOOP
        v_bound := pg_get_expr(v_child.relpartbound, v_child.oid);
        v_upper := regexp_match(v_bound, 'TO \(''([^'']+)''\)');

        -- DEFAULT, or a bound shape this function was not written for. Either
        -- way it is not something to drop on a guess.
        CONTINUE WHEN v_upper IS NULL;

        -- Expired means every timestamp the partition is ABLE to hold is older
        -- than the cutoff, read from the bound rather than from the name. A
        -- partition whose name lies about its contents is not expired — and
        -- until V0080 a caller could hand this loop a name and a bound that
        -- belonged to two different tables.
        CONTINUE WHEN v_upper[1]::timestamptz > v_cutoff;

        v_expired := v_expired || v_child.relname;
    END LOOP;

    IF NOT COALESCE(p_report_only, false) THEN
        FOREACH v_name IN ARRAY v_expired LOOP
            EXECUTE format('DROP TABLE fulfillment.%I', v_name);
        END LOOP;
    END IF;

    RETURN QUERY SELECT unnest(v_expired);
END;
$$;

COMMENT ON FUNCTION fulfillment.sweep_expired_track_partitions(integer, boolean) IS
    'ADR 0029 retention of ADR 0045 courier tracks. The application may ask for the sweep and may not define it: there is no table parameter, the children come from pg_inherits on one named parent, the cutoff is the database''s own current_date, and the retention argument is one term of a GREATEST with the platform floor and the longest tenant window, so it can only ever lengthen. A partition is dropped only when its own upper bound is older than the cutoff. SECURITY DEFINER (V0075), EXECUTE granted by name. The parent, the children and their bounds are read through pg_catalog-qualified names with pg_temp named last in search_path (V0080): with either half missing, a temporary table called pg_class carrying today''s relname and an expired bound made this function drop a live day of personal data.';

REVOKE EXECUTE ON FUNCTION fulfillment.sweep_expired_track_partitions(integer, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fulfillment.sweep_expired_track_partitions(integer, boolean)
    TO horecaos_application;


-- ---------------------------------------------------------------------------
-- 4. ADR 0043 — reporting.ensure_fact_partition
-- ---------------------------------------------------------------------------
--
-- V0075 left this one's signature alone and said why: a fourth signature change
-- would break `ReportingPartitionManager` for no privilege gained, and the
-- allowlist plus `%I` already close the identifier slot. That reasoning still
-- holds and the `text` parameter stays. It does not survive the pinning
-- question, though — the existence check inside it read `pg_class` unqualified
-- exactly like the other three, so a forged catalogue could make it believe a
-- month's partition already existed and let a month of facts fall into the
-- default partition, which then blocks that month's real partition until an
-- operator finds and moves the rows.

CREATE OR REPLACE FUNCTION reporting.ensure_fact_partition(p_table text, p_month date)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_start date := date_trunc('month', p_month)::date;
    v_end   date := (date_trunc('month', p_month) + interval '1 month')::date;
    v_name  text := p_table || '_' || to_char(v_start, 'YYYYMM');
BEGIN
    IF p_table NOT IN ('fact_order', 'fact_order_line', 'fact_refund') THEN
        RAISE EXCEPTION 'reporting.ensure_fact_partition does not manage %', p_table;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c
                 JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'reporting' AND c.relname = v_name) THEN
        RETURN;
    END IF;

    EXECUTE format(
        'CREATE TABLE reporting.%I PARTITION OF reporting.%I FOR VALUES FROM (%L) TO (%L)',
        v_name, p_table, v_start, v_end);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.%I TO horecaos_application', v_name);
    EXECUTE format('GRANT SELECT ON reporting.%I TO horecaos_reporting_read', v_name);
END;
$$;

COMMENT ON FUNCTION reporting.ensure_fact_partition(text, date) IS
    'ADR 0043 partition upkeep. Idempotent, and refuses any table it does not own so a typo cannot partition something else. SECURITY DEFINER (V0070) because the application role holds no DDL rights and this function is the only way it is meant to add a partition; EXECUTE is granted by name, never to PUBLIC. search_path names pg_temp LAST and the catalogue read is schema-qualified (V0080).';

REVOKE EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) TO horecaos_application;
