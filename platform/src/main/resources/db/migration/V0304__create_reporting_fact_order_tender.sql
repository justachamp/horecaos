-- ADR 0043 / ADR 0115: reporting.fact_order_tender — the tender grain V0031's
-- header line 9 deliberately left out ("fact_order_tender ... have no
-- migration here. A fact table whose producer does not exist is not a head
-- start").
--
-- ---------------------------------------------------------------------------
-- This is a projection, not a capture
-- ---------------------------------------------------------------------------
--
-- The data already exists one layer down. V0042 (ADR 0046) gives every order
-- an ordered settlement of one or more tenders in payments.tenders, populated
-- in production by CheckoutSettlementPlanner; V0048 adds refunded_minor so a
-- tender remembers how much of itself has already been given back. Nothing in
-- `reporting` was allowed to read either table until now — ADR 0023 forbids a
-- report reading a module schema — so the close job is the one thing that may,
-- exactly as V0031's own header says of `ordering` and `payments`: "The close
-- job is the one thing that reads ordering and payments, and it writes only
-- here."
--
-- ---------------------------------------------------------------------------
-- The grain, per ADR 0043's "Payment is a grain, not a column" section
-- ---------------------------------------------------------------------------
--
-- One row per tender, on the ORDER's own business date (never a separate
-- refund date the way fact_refund uses one): order_id, business_date,
-- location_id, legal_entity_id, tender_sequence, payment_method_code,
-- settles_from_balance, amount_som, tender_status. amount_som is the NET
-- amount currently tendered — amount_minor less refunded_minor — so a
-- partially refunded tender nets out in place and a fully refunded one
-- (tender_status REVERSED, which payments.tenders only reaches when the whole
-- tender has been refunded) reads zero rather than needing a second row. This
-- mirrors the choice ADR 0043 already explains for fact_refund's own grain,
-- just landing on the opposite shape for the opposite reason: a refund is
-- filed on ITS OWN date because two partial refunds can land on two different
-- days, while a tender is filed once, on the order's date, because "what is
-- currently in the till for this order, by method" is the figure a cash
-- reconciliation needs — not a ledger of every movement against it (that
-- ledger is payments.tenders itself, one layer down, which this fact never
-- re-exposes).
--
-- Facts capture everything the close job sees, the same discipline fact_order
-- follows for terminal_status: a PLANNED or FAILED tender is stored with its
-- own status and its full (unrefunded, since nothing settled) amount, and it
-- is the metric layer's inclusion rule — SETTLED_OR_REVERSED_TENDERS,
-- reporting.domain.MetricRegistry's payment_mix.amount.v1 — that decides which
-- rows actually collected cash. Storing only "the ones that count" would make
-- a later inclusion-rule change a migration instead of a code review.
--
-- ---------------------------------------------------------------------------
-- Money
-- ---------------------------------------------------------------------------
--
-- Whole som, matching every other fact table V0031 created. amount_som is
-- never negative: a fully refunded tender is zero, not a negative row that
-- would need netting against something else to read correctly.

CREATE TABLE reporting.fact_order_tender (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    order_id uuid NOT NULL,

    -- payments.tenders.sequence, 1-based, unique within the order's own
    -- settlement (uq_tender_sequence, V0042). The reservation order a split
    -- settlement was planned in — balance first, external money last — never
    -- a display order.
    tender_sequence integer NOT NULL,

    boundary_version integer NOT NULL,

    location_id uuid NOT NULL,
    -- ADR 0038, copied from the same order fact this tender belongs to
    -- (fact_order.legal_entity_id) rather than re-resolved from payments.
    -- Null means no fiscal identity was recorded, exactly as fact_order
    -- documents, and this figure is money: MetricDefinition refuses a
    -- UZS_SOM metric whose grain does not name the legal entity, so the
    -- registry's own constructor is what stops a combined-entity total
    -- reaching a tenant that trades as two companies on the same evening.
    legal_entity_id uuid,

    -- payments.payment_methods.code (ADR 0038's tenant registry), snapshotted
    -- rather than joined, matching every other snapshotted code in this
    -- schema (fact_order.channel_code, fact_order_line.product_name_snapshot):
    -- a method renamed next quarter must not rewrite this quarter's chart.
    payment_method_code varchar(32) NOT NULL,
    settles_from_balance boolean NOT NULL,
    tender_status varchar(16) NOT NULL,

    -- Net of any refund already recorded against this tender
    -- (payments.tenders.amount_minor - refunded_minor, V0048). Never a
    -- second row for the refunded portion — see the grain note above.
    amount_som bigint NOT NULL,

    metric_calculation_version integer NOT NULL,
    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_order_tender PRIMARY KEY (tenant_id, business_date, order_id, tender_sequence),
    CONSTRAINT ck_fact_order_tender_amount CHECK (amount_som >= 0),
    CONSTRAINT ck_fact_order_tender_sequence CHECK (tender_sequence >= 1),
    CONSTRAINT ck_fact_order_tender_payment_method_code CHECK (
        payment_method_code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    -- The same six payments.tenders.status carries (ck_tender_status, V0042).
    -- Repeated rather than referenced, on the same footing as
    -- fact_order.terminal_status: a chart drawn this quarter must not change
    -- meaning if the settlement vocabulary ever does.
    CONSTRAINT ck_fact_order_tender_status CHECK (tender_status IN (
        'PLANNED', 'RESERVED', 'SETTLED', 'RELEASED', 'REVERSED', 'FAILED'))
) PARTITION BY RANGE (business_date);

COMMENT ON TABLE reporting.fact_order_tender IS
    'ADR 0043/0115. One row per tender, on the ORDER''s business date — never a separate refund date, unlike fact_refund. Derived and rebuildable: drop it and recut rather than repair it.';
COMMENT ON COLUMN reporting.fact_order_tender.amount_som IS
    'Net of any refund already recorded against this tender (payments.tenders.refunded_minor, V0048). A fully refunded tender reads zero (tender_status REVERSED) rather than needing a second row.';
COMMENT ON COLUMN reporting.fact_order_tender.tender_status IS
    'Every status payments.tenders can hold, not only SETTLED/REVERSED — a PLANNED or FAILED tender never collected money and payment_mix.amount.v1''s inclusion rule (SETTLED_OR_REVERSED_TENDERS) excludes it, the same "facts capture everything, metrics decide inclusion" split fact_order.terminal_status already follows.';
COMMENT ON COLUMN reporting.fact_order_tender.legal_entity_id IS
    'ADR 0038, copied from the order''s own fact rather than re-resolved. This is a money column, so MetricDefinition refuses to register a UZS_SOM metric at a grain that does not name it (ADR 0038).';

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
--
-- Every reporting query carries the tenant predicate, matching V0031's own
-- indexes on the sibling fact tables.
CREATE INDEX ix_fact_order_tender_source
    ON reporting.fact_order_tender (tenant_id, order_id);
CREATE INDEX ix_fact_order_tender_mix
    ON reporting.fact_order_tender (tenant_id, business_date, location_id, legal_entity_id, payment_method_code);

-- ---------------------------------------------------------------------------
-- Partition upkeep: widen the one allowlisted function rather than add a
-- second one
-- ---------------------------------------------------------------------------
--
-- reporting.ensure_fact_partition (V0031, hardened SECURITY DEFINER by V0070
-- and pinned against the pg_temp forgery by V0080) is the only DDL path the
-- application role has for a fact partition, and its allowlist is the whole
-- defence against a caller pointing it at an arbitrary table. This is a
-- CREATE OR REPLACE of that same function, reproducing V0080's body verbatim
-- with one table name added — never a second, unpinned function, which would
-- reopen exactly the gap V0080 closed.
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
    IF p_table NOT IN ('fact_order', 'fact_order_line', 'fact_refund', 'fact_order_tender') THEN
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
    'ADR 0043/0115 partition upkeep. Idempotent, and refuses any table it does not own so a typo cannot partition something else. SECURITY DEFINER (V0070) because the application role holds no DDL rights and this function is the only way it is meant to add a partition; EXECUTE is granted by name, never to PUBLIC. search_path names pg_temp LAST and the catalogue read is schema-qualified (V0080). fact_order_tender added (V0304).';

REVOKE EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) TO horecaos_application;

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
--
-- Explicit rather than left to the ALTER DEFAULT PRIVILEGES V0031 already set
-- for horecaos_reporting_read — nine migrations forgot their own GRANT block
-- before (AGENTS.md), and a grant that is only implied is the next one to be
-- forgotten when this table's shape changes.
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_order_tender TO horecaos_application;
GRANT SELECT ON reporting.fact_order_tender TO horecaos_reporting_read;

-- Seeded after the grants so the partitions inherit them through the
-- function, matching V0031's own seed block and the same 2026-01-01 through
-- 2027-12-01 window (ReportingPartitionManager keeps three months ahead of
-- the clock in production; this only needs to cover what fixtures and the
-- pilot's own history already assume).
DO $$
DECLARE
    v_month date := date '2026-01-01';
BEGIN
    WHILE v_month < date '2028-01-01' LOOP
        PERFORM reporting.ensure_fact_partition('fact_order_tender', v_month);
        v_month := (v_month + interval '1 month')::date;
    END LOOP;
END
$$;
