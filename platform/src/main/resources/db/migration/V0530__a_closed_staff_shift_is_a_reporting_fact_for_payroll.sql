-- ADR 0156 / ADR 0043: reporting.fact_staff_shift -- the hours a payroll conversation needs,
-- built at day close from a `workforce` port and never by a report reading a workforce table
-- (ADR 0023).
--
-- It holds CLOSED shifts only. An OPEN or AWAITING_APPROVAL shift is not a fact until it
-- settles, which is how "a shift past the threshold waits for approval and stays out of the
-- fact until approved" is true by construction rather than by a filter somebody can forget.
--
-- The grain is one row per shift on the tenant's business date of its start (ADR 0043:
-- computed from reporting.business_day_start), partitioned by business_date like the order
-- facts. A shift edited after its day closed is caught by the recut as a divergence and
-- alerts; the stored fact is never silently corrected.
--
-- No name, phone or employee number: `staff_display_reference` is the non-personal handle
-- («S-0142», iam.staff_members.display_reference) that ADR 0029 allows in a fact. Identity
-- meets hours in exactly one place, the gated and audited STAFF_ATTENDANCE export file.

CREATE TABLE reporting.fact_staff_shift (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    shift_id uuid NOT NULL,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    staff_display_reference varchar(32) NOT NULL,

    -- NULL is not zero: no roster entry means no plan and no variance.
    planned_seconds bigint,
    worked_seconds bigint NOT NULL,
    break_seconds integer NOT NULL,
    paid_seconds bigint NOT NULL,
    variance_seconds bigint,
    -- WITHIN_THRESHOLD (settled at close) or APPROVED (waited, and a person looked).
    approval_status varchar(20) NOT NULL,
    had_roster_entry boolean NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at timestamptz NOT NULL,

    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_staff_shift PRIMARY KEY (tenant_id, business_date, shift_id),
    CONSTRAINT ck_fact_staff_shift_approval CHECK (approval_status IN ('WITHIN_THRESHOLD', 'APPROVED')),
    CONSTRAINT ck_fact_staff_shift_seconds CHECK (
        worked_seconds >= 0 AND paid_seconds >= 0 AND break_seconds >= 0 AND paid_seconds <= worked_seconds),
    CONSTRAINT ck_fact_staff_shift_plan CHECK (
        (planned_seconds IS NULL) = (variance_seconds IS NULL) AND had_roster_entry = (planned_seconds IS NOT NULL))
) PARTITION BY RANGE (business_date);

COMMENT ON TABLE reporting.fact_staff_shift IS
    'ADR 0156, ADR 0043. Derived and rebuildable from workforce.staff_shifts through StaffShiftFactSource -- see DayCloseService. CLOSED shifts only: an open or awaiting-approval shift is not a fact until it settles. Holds the non-personal display reference, never a name or an employee number.';

CREATE INDEX ix_fact_staff_shift_branch ON reporting.fact_staff_shift (tenant_id, location_id, business_date);
CREATE INDEX ix_fact_staff_shift_member ON reporting.fact_staff_shift (tenant_id, staff_member_id, business_date);
CREATE INDEX ix_fact_staff_shift_source ON reporting.fact_staff_shift (tenant_id, shift_id);

-- Partition upkeep: widen the one allowlisted function rather than add a second one, exactly
-- as V0304 did for fact_order_tender. This is a CREATE OR REPLACE of that function reproducing
-- V0304's body verbatim with one table name added -- never a second, unpinned function, which
-- would reopen the gap V0080 closed. A later migration that adds a partitioned fact must
-- reproduce THIS list, not V0304's, or it silently drops fact_staff_shift's upkeep.
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
    IF p_table NOT IN ('fact_order', 'fact_order_line', 'fact_refund', 'fact_order_tender', 'fact_staff_shift') THEN
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
    'ADR 0043/0115 partition upkeep. Idempotent, and refuses any table it does not own so a typo cannot partition something else. SECURITY DEFINER (V0070) because the application role holds no DDL rights and this function is the only way it is meant to add a partition; EXECUTE is granted by name, never to PUBLIC. search_path names pg_temp LAST and the catalogue read is schema-qualified (V0080). fact_order_tender added (V0304), fact_staff_shift added (V0530).';

REVOKE EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) TO horecaos_application;

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_staff_shift TO horecaos_application;
GRANT SELECT ON reporting.fact_staff_shift TO horecaos_reporting_read;

-- Seeded after the grants so the partitions inherit them through the function, over the same
-- 2026-01-01 through 2027-12-01 window V0031 and V0304 seed (ReportingPartitionManager keeps
-- three months ahead of the clock in production).
DO $$
DECLARE
    v_month date := date '2026-01-01';
BEGIN
    WHILE v_month < date '2028-01-01' LOOP
        PERFORM reporting.ensure_fact_partition('fact_staff_shift', v_month);
        v_month := (v_month + interval '1 month')::date;
    END LOOP;
END
$$;
