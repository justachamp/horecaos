-- ADR 0027: archival of closed audit partitions to protected storage.
--
-- Everything else the record named was already built: the two retention keys
-- (V0005's registry, ADR 0030), the partitioned store (V0007), the manager that
-- keeps partitions two years ahead (V0075). Nothing read the keys and nothing
-- moved a closed partition anywhere. This migration is the bookkeeping table an
-- application-level archiver (AuditPartitionArchiver) needs, and the one
-- privileged operation it must not be trusted to perform on its own say-so:
-- dropping a partition out of the live table.
--
-- That drop is guarded the way V0075/V0080 guard the retention sweeps this
-- platform already runs: a SECURITY DEFINER function owned by the migration
-- role, search_path pinned with pg_temp named last, every catalogue read
-- schema-qualified, EXECUTE granted to horecaos_application and to nobody else.
-- What is new here, and stronger than the fulfillment sweep's own guard, is that
-- the function does not take the caller's word for anything. It re-reads
-- audit.partition_archives itself and refuses unless THIS table already says
-- VERIFIED for that year -- a status only the application can set, but doing so
-- requires a separate row-write bug, not merely calling the wrong function with
-- the wrong argument. "Never drop live evidence before the archive is durable
-- and verified" is therefore a database-enforced precondition on the one
-- statement in the whole feature that destroys something, not a promise the
-- Java call order keeps by convention.

CREATE TABLE audit.partition_archives (
    -- One row per calendar-year audit_events partition. The year is the natural
    -- key: audit.ensure_event_partition (V0007/V0075/V0080) already names a
    -- partition audit_events_<year> and nothing else, so there is exactly one
    -- partition per year to track.
    year integer PRIMARY KEY,
    partition_name text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING',
    -- Where it landed. Null until the first successful, verified upload; the
    -- bucket is recorded rather than assumed from configuration, because
    -- configuration can move and this row is the historical record of where
    -- THIS partition's evidence actually is.
    bucket text,
    object_key text,
    sha256_base64 text,
    size_bytes bigint,
    row_count bigint,
    -- The retention lock this partition's archive object was written under --
    -- the longer of the two ADR 0030 retention keys in force when it was
    -- archived, measured from the partition's own upper bound rather than from
    -- upload time, so a late archival run never shortens what an on-time one
    -- would have promised.
    retain_until timestamptz,
    verified_at timestamptz,
    dropped_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_partition_archive_status CHECK (status IN ('PENDING', 'VERIFIED', 'DROPPED')),
    CONSTRAINT ck_partition_archive_name CHECK (partition_name ~ '^audit_events_[0-9]{4}$'),
    -- A row cannot claim to be verified without the evidence a verification
    -- actually produces, and cannot claim to be dropped without having been
    -- verified first -- both belt-and-braces alongside the function below,
    -- which is the real enforcement point.
    CONSTRAINT ck_partition_archive_verified_has_evidence CHECK (
        status = 'PENDING'
        OR (bucket IS NOT NULL AND object_key IS NOT NULL AND sha256_base64 IS NOT NULL
            AND retain_until IS NOT NULL AND verified_at IS NOT NULL)
    )
);

COMMENT ON TABLE audit.partition_archives IS
    'ADR 0027 archival bookkeeping: one row per calendar-year audit_events partition, tracking its trip to protected storage. This is process metadata, not evidence -- audit.audit_events is the evidence -- so unlike that table this one may be updated by the application as the state machine advances (PENDING -> VERIFIED -> DROPPED). Only audit.drop_archived_partition may move a row to DROPPED, and only after re-reading VERIFIED for itself rather than trusting an argument.';

-- Process metadata, not evidence: ordinary INSERT/SELECT/UPDATE, the same shape
-- media's derivative and verification job tables already use. There is
-- deliberately no DELETE grant -- a row here is never removed, only advanced --
-- and no way for the application to reach DROPPED except through the function
-- below, which does not trust this table's own status column blindly either: it
-- is the one write path that matters, and it is guarded there.
GRANT SELECT, INSERT, UPDATE ON audit.partition_archives TO horecaos_application;


-- ---------------------------------------------------------------------------
-- audit.drop_archived_partition -- the one statement that destroys something
-- ---------------------------------------------------------------------------
--
-- Modelled on fulfillment.sweep_expired_track_partitions (V0075, pinned by
-- V0080), with one guard that function does not need because nothing it drops
-- was ever meant to be archived first. This one is: a year is dropped only when
-- audit.partition_archives already says VERIFIED for it, read fresh inside this
-- function rather than passed as an argument the caller could get wrong. There
-- is no p_verified boolean parameter here, on purpose, for the same reason
-- audit.ensure_event_partition has no p_table text: a caller cannot pass its way
-- past a check that does not accept anything from it.
--
-- The partition's own upper bound is re-checked too, from pg_catalog rather
-- than from the year argument's arithmetic, so a partition whose bound does not
-- match its name is refused the same way the fulfillment sweep refuses one.

CREATE FUNCTION audit.drop_archived_partition(p_year integer)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_name    text;
    v_status  text;
    v_oid     oid;
    v_bound   pg_node_tree;
    v_upper   text[];
BEGIN
    IF p_year IS NULL OR p_year < 2020 OR p_year > 2100 THEN
        RAISE EXCEPTION 'audit.drop_archived_partition manages the years 2020..2100, not %', p_year;
    END IF;

    v_name := 'audit_events_' || to_char(p_year, 'FM0000');

    SELECT status INTO v_status FROM audit.partition_archives WHERE year = p_year;

    IF v_status = 'DROPPED' THEN
        -- Already done. A retry after a crash between the DROP below and the
        -- caller learning about it must be a no-op, not an error the caller has
        -- to special-case.
        RETURN false;
    END IF;

    IF v_status IS DISTINCT FROM 'VERIFIED' THEN
        RAISE EXCEPTION
            'audit.drop_archived_partition: % has no VERIFIED archive (partition_archives says %)',
            v_name, coalesce(v_status, 'no row at all');
    END IF;

    SELECT c.oid, c.relpartbound
      INTO v_oid, v_bound
      FROM pg_catalog.pg_inherits i
      JOIN pg_catalog.pg_class c      ON c.oid = i.inhrelid
      JOIN pg_catalog.pg_class p      ON p.oid = i.inhparent
      JOIN pg_catalog.pg_namespace pn ON pn.oid = p.relnamespace
     WHERE pn.nspname = 'audit' AND p.relname = 'audit_events' AND c.relname = v_name;

    IF v_oid IS NULL THEN
        RAISE EXCEPTION 'audit.drop_archived_partition: % does not exist', v_name;
    END IF;

    v_upper := regexp_match(pg_get_expr(v_bound, v_oid), 'TO \(''([^'']+)''\)');
    IF v_upper IS NULL THEN
        RAISE EXCEPTION 'audit.drop_archived_partition: % has no readable upper bound', v_name;
    END IF;
    IF v_upper[1]::timestamptz > now() THEN
        RAISE EXCEPTION 'audit.drop_archived_partition: % is not closed yet (bound %)', v_name, v_upper[1];
    END IF;

    EXECUTE format('DROP TABLE audit.%I', v_name);

    UPDATE audit.partition_archives
       SET status = 'DROPPED', dropped_at = now(), updated_at = now()
     WHERE year = p_year;

    RETURN true;
END;
$$;

COMMENT ON FUNCTION audit.drop_archived_partition(integer) IS
    'ADR 0027 archival. Drops a year of audit.audit_events out of the live table once, and only once, audit.partition_archives already says VERIFIED for that year -- read fresh here, not trusted from an argument, because there is no argument that names it. SECURITY DEFINER, owned by the migration role, search_path pins pg_catalog then pg_temp last and every catalogue read is schema-qualified, the same discipline V0075/V0080 use for the retention sweeps this platform already runs. EXECUTE is granted to horecaos_application by name and to nobody else.';

REVOKE EXECUTE ON FUNCTION audit.drop_archived_partition(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.drop_archived_partition(integer) TO horecaos_application;
