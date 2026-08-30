-- The two scheduled jobs V0070 did not reach.
--
-- V0070 found one of three code paths that do DDL and fixed it. The search that
-- found `reporting.ensure_fact_partition` was not exhaustive, and two jobs that
-- ship enabled by default were left issuing statements the application role can
-- no longer run:
--
--   AuditPartitionManager        CREATE TABLE ... PARTITION OF, then GRANT
--   TrackRetentionSweeper        CREATE TABLE ... PARTITION OF, then GRANT,
--                                and DROP TABLE for the ADR 0029 retention
--
-- Under `horecaos_application` the CREATE is `permission denied for schema`, the
-- DROP is `must be owner of table`, and — the worst of the three — the GRANT
-- raises nothing at all. PostgreSQL answers a GRANT from a role holding no grant
-- option with `WARNING: no privileges were granted` and moves nothing, so even
-- where the CREATE succeeded the partition would be unreadable and unwritable by
-- the very role that made it. A privilege bug that reports itself as a warning on
-- a background thread is a privilege bug nobody finds.
--
-- The consequence was reproduced rather than reasoned about.
-- `TrackRetentionSweeper.sweep()` runs hourly and calls `ensurePartitions()`
-- first. V0041 provisioned `fulfillment.courier_location_tracks` a fortnight past
-- its own run, so the first day the sweeper needs a partition it does not have,
-- it throws — and `expireLivePositions()` and `dropExpiredPartitions()`, the two
-- statements after it, never run again. `dropExpiredPartitions()` is ADR 0029's
-- retention of courier GPS tracks: the movement history of identified
-- self-employed people. It stops. Every subsequent day's observations fall into
-- `courier_location_tracks_default`, which then makes that day's real partition
-- impossible to create, and nothing anywhere fails to a user. A scheduled job
-- logs a stack trace once an hour and the retention window quietly becomes
-- forever.
--
-- The fix is V0070's, not a wider role: a `SECURITY DEFINER` function owned by
-- `horecaos_migrator`, `search_path` pinned to `pg_catalog`, EXECUTE revoked from
-- PUBLIC and granted to `horecaos_application` by name, and — the part that matters
-- most — no identifier slot that a caller's string can reach. Every function
-- below builds the only name it will ever touch from a value the type system has
-- already proven: an `integer` year, a `date`. There is no `p_table text` here,
-- because a `SECURITY DEFINER` function that interpolates a caller's text into an
-- identifier is not a partition helper, it is a privilege-escalation primitive
-- that happens to create partitions.
--
--
-- WHY THESE JOBS STAY ON THE APPLICATION
--
-- The alternative was to move both to deploy time, where `horecaos_migrator`
-- already runs and holds everything outright. It is rejected for both, for the
-- same reason and with different force.
--
-- A partition provisioner has to run on the calendar, and deployments do not
-- happen on a calendar. Provision at deploy time and a quiet month means
-- observations landing in the default partition — which is not merely untidy: a
-- default partition holding a day's rows makes that day's partition impossible to
-- create until somebody finds and moves them, so one missed window costs manual
-- work on a live table. Running daily is deliberate over-provisioning of an
-- operation that costs nothing, which is the trade V0070 made for the fact
-- tables, and it is the right one here too.
--
-- The retention sweep is the harder call, because dropping a partition destroys
-- personal data on purpose, and that is not a verb to hand out casually. But a
-- retention window does not pause between releases either — ADR 0029's thirty
-- days is thirty days whether or not anyone deployed — so retention that only
-- runs at deploy time is retention that silently lengthens with every quiet
-- month, which is precisely the failure this migration exists to end.
--
-- So it stays on the clock, and the grant is narrowed instead of the schedule.
-- The application does not get "drop a partition of this table". It gets "sweep
-- whatever is expired", and every part of the judgement moves into the function:
--
--   * The caller names no table. It cannot, because there is no parameter for
--     one. The function enumerates the children of exactly one parent through
--     `pg_inherits`, so the set of tables it can possibly drop is a property of
--     the schema rather than of the argument.
--   * The caller does not decide what "expired" means. The cutoff is
--     `current_date` — the database's own clock, which an application cannot
--     move — minus a window the function derives itself.
--   * The caller's retention argument can only ever make the window LONGER. It
--     is one term of a `GREATEST` alongside the ADR 0045 platform floor and the
--     longest value any tenant configured under ADR 0030. Passing 0, or -1, or
--     nothing, drops strictly less than passing the truth. There is no value a
--     compromised caller can pass that deletes a track a day early.
--   * A partition is expired only if every timestamp it is able to hold is older
--     than the cutoff, read from the partition's own bound rather than from its
--     name. A table whose name says January and whose bound says today is not
--     expired, and this is the check that says so.
--   * The DEFAULT partition has no upper bound to be older than, so it falls out
--     of that test rather than needing to be excluded by name. It is excluded by
--     name as well.
--
-- What a compromised application can do with this function, at its very worst, is
-- ask for the sweep that a healthy one asks for every hour.


-- ---------------------------------------------------------------------------
-- 1. ADR 0027 — the audit trail's partitions
-- ---------------------------------------------------------------------------
--
-- The parent has a default partition so an audited action can never fail for want
-- of one, but rows landing there are a symptom: they have to be moved before that
-- range can get a partition of its own. Creating next year's well in advance is
-- the cheap way to stop that happening at all.
--
-- The year is an `integer` and the range check is inclusive of nothing outside
-- 2020..2100, so the relation name is four digits of `to_char` and cannot be
-- anything else. The grant the manager used to issue itself is issued here, by
-- the owner, where it actually moves: INSERT and SELECT only, because V0007
-- revokes UPDATE, DELETE and TRUNCATE on the parent and a partition that
-- inherited more than the parent allows would make that REVOKE decorative.
CREATE FUNCTION audit.ensure_event_partition(p_year integer)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_name text;
BEGIN
    IF p_year IS NULL OR p_year < 2020 OR p_year > 2100 THEN
        RAISE EXCEPTION 'audit.ensure_event_partition manages the years 2020..2100, not %', p_year;
    END IF;

    v_name := 'audit_events_' || to_char(p_year, 'FM0000');

    IF EXISTS (SELECT 1 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
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
            IF EXISTS (SELECT 1 FROM pg_class c
                         JOIN pg_namespace n ON n.oid = c.relnamespace
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
    'ADR 0027 partition upkeep. Idempotent and race-tolerant across replicas. SECURITY DEFINER (V0075) because the application role holds no DDL rights and this is the only way it is meant to add a partition; search_path is pinned, EXECUTE is granted by name rather than to PUBLIC, and the only identifier it builds comes from an integer year, never from a caller''s string.';

REVOKE EXECUTE ON FUNCTION audit.ensure_event_partition(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.ensure_event_partition(integer) TO horecaos_application;


-- ---------------------------------------------------------------------------
-- 2. ADR 0045 — the courier track partitions
-- ---------------------------------------------------------------------------
--
-- Daily rather than yearly, and the bounds are written as explicit UTC literals
-- rather than as a cast of a date, for the reason V0041 gives: a cast takes the
-- session's timezone, and a partition whose day starts at 19:00 the previous
-- evening is dropped five hours early. That is a retention rule that quietly
-- deletes evidence.
CREATE FUNCTION fulfillment.ensure_track_partition(p_day date)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_name text;
BEGIN
    IF p_day IS NULL OR p_day < DATE '2020-01-01' OR p_day > DATE '2100-01-01' THEN
        RAISE EXCEPTION 'fulfillment.ensure_track_partition manages 2020-01-01..2100-01-01, not %',
            p_day;
    END IF;

    v_name := 'courier_location_tracks_' || to_char(p_day, 'YYYYMMDD');

    IF EXISTS (SELECT 1 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
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
            IF EXISTS (SELECT 1 FROM pg_class c
                         JOIN pg_namespace n ON n.oid = c.relnamespace
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
    'ADR 0045 partition upkeep. Idempotent and race-tolerant across replicas. SECURITY DEFINER (V0075) for the reason audit.ensure_event_partition is; the only identifier it builds is a to_char of a date, so there is no slot a caller''s string can reach. SELECT and INSERT only — nothing rewrites a track, and deleting one is the sweep''s job and not a row operation.';

REVOKE EXECUTE ON FUNCTION fulfillment.ensure_track_partition(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fulfillment.ensure_track_partition(date) TO horecaos_application;


-- ---------------------------------------------------------------------------
-- 3. ADR 0029 — the retention sweep, which is the one that destroys something
-- ---------------------------------------------------------------------------
--
-- Read the header of this file for why the application keeps a trigger here and
-- not a verb. The mechanics:
--
-- `p_retention_days` is what the application believes the window is — the ADR
-- 0030 resolution the sweeper already computes. It is taken as one term of a
-- GREATEST and never as the answer, so it can only lengthen the window. The other
-- two terms are the ADR 0045 platform floor and the longest value any tenant
-- stored for `telemetry.track_retention_days`; the second is re-derived here
-- rather than trusted from the caller, because a partition holds every tenant's
-- rows and dropping at anything shorter than the longest configured window
-- deletes evidence a tenant is required to hold.
--
-- The floor is 30 and duplicates `TrackRetentionFloor.CONFIGURED_TRACK_RETENTION_DAYS`.
-- Duplicating a constant is a cost paid deliberately: this one is the last line
-- of defence for a destructive operation, and a last line of defence that a
-- property file can lower is not one. Shortening the platform's retention is
-- therefore a migration, which is the right amount of friction for a change that
-- deletes personal data sooner. The two must move together.
--
-- `p_report_only` exists because ADR 0029 asks a retention job's first run in a
-- new environment to say what it would delete before deleting anything. It makes
-- the function do strictly less, so it needs no guard of its own.
--
-- The returned names are bare relation names, not qualified ones: the caller logs
-- them and has nothing it could do with a qualified name that it is allowed to
-- do anyway.
CREATE FUNCTION fulfillment.sweep_expired_track_partitions(
        p_retention_days integer,
        p_report_only boolean DEFAULT false)
RETURNS SETOF text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    -- ADR 0045. See above: lowering this is a migration, on purpose.
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
    -- overflow raised from inside a retention job. A hundred years is far past
    -- ADR 0045's 90-day review ceiling and is a ceiling, not a target.
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
          FROM pg_inherits i
          JOIN pg_class c      ON c.oid = i.inhrelid
          JOIN pg_class p      ON p.oid = i.inhparent
          JOIN pg_namespace pn ON pn.oid = p.relnamespace
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
        -- partition whose name lies about its contents is not expired.
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
    'ADR 0029 retention of ADR 0045 courier tracks. The application may ask for the sweep and may not define it: there is no table parameter, the children come from pg_inherits on one named parent, the cutoff is the database''s own current_date, and the retention argument is one term of a GREATEST with the platform floor and the longest tenant window, so it can only ever lengthen. A partition is dropped only when its own upper bound is older than the cutoff. SECURITY DEFINER (V0075), search_path pinned, EXECUTE granted by name.';

REVOKE EXECUTE ON FUNCTION fulfillment.sweep_expired_track_partitions(integer, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fulfillment.sweep_expired_track_partitions(integer, boolean)
    TO horecaos_application;


-- ---------------------------------------------------------------------------
-- 4. V0070's function, held to the same rule as the two above
-- ---------------------------------------------------------------------------
--
-- `reporting.ensure_fact_partition(text, date)` is the pattern this migration
-- followed, and it is the one function here that still takes a table name as
-- text. Its allowlist is real and it is not a hole. It is left as it is rather
-- than replaced, because a fourth signature change would break
-- ReportingPartitionManager for no privilege gained, and because the allowlist
-- and the `%I` quoting together already close it. This note exists so that the
-- next reader does not conclude from its shape that a `text` parameter feeding an
-- identifier is the house style. It is not. The three functions above are.
