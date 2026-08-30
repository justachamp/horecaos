-- ADR 0043: reporting, analytics, and the metric layer — the day-grain slice.
--
-- What this migration builds is the part the operations console needs and can
-- actually source today: a metric registry, a business-day boundary, one order
-- fact and one line fact, two aggregates, and the close/recut bookkeeping that
-- turns "the number moved" into an alert instead of a silent overwrite.
--
-- What it deliberately does not build is listed at the bottom of the ADR's
-- implementation checklist. `fact_order_tender`, `fact_delivery`,
-- `fact_promotion_redemption`, `fact_behaviour`, the classification and forecast
-- runs, and `report_exports` have no migration here. A fact table whose producer
-- does not exist is not a head start: it is an empty table that reads to the next
-- author as though the projection is broken.
--
-- ---------------------------------------------------------------------------
-- Money
-- ---------------------------------------------------------------------------
--
-- Every amount here is whole som, matching ADR 0018 and the note at the top of
-- V0027. UZS has no sub-unit in practice: a minor unit is a som. Nothing in the
-- reporting path divides an amount by a hundred, and nothing formats one — a
-- formatter that asks ISO 4217 for the decimal places is how a customer is shown
-- a price a hundred times too small, and that bug has already been caught here
-- once.
--
-- ---------------------------------------------------------------------------
-- Why these are copies
-- ---------------------------------------------------------------------------
--
-- ADR 0023 forbids reporting from reading a module schema on the read path, and
-- ADR 0043 makes that a grant rather than a convention: `qoida_reporting_read`,
-- created at the end of this file, holds SELECT on `reporting` and nothing else.
-- The close job is the one thing that reads `ordering` and `payments`, and it
-- writes only here.
--
-- The cost is a second copy of order data that will drift. The reconciliation
-- check is permanent work producing no feature, and ADR 0043 says so plainly.

-- ---------------------------------------------------------------------------
-- The business day
-- ---------------------------------------------------------------------------
--
-- A restaurant that closes at 02:00 and sees those orders on the next date
-- concludes the report is broken. Uzbekistan is UTC+5 with no daylight saving,
-- which removes one whole class of boundary bug, but the boundary still has to be
-- stored rather than assumed by a hundred queries.
--
-- `boundary_version` is the part that matters and the part that is easy to leave
-- out. Moving the boundary rewrites the meaning of every `business_date` already
-- computed, so the version is stamped onto every fact at write time and the query
-- API refuses a range spanning two regimes until the recut has caught up. Without
-- it, changing the boundary produces a report that silently mixes two definitions
-- of "Tuesday" and no column anywhere records that it happened.
CREATE TABLE reporting.business_day_policies (
    tenant_id uuid PRIMARY KEY,

    -- Local wall-clock time at which the business day begins. Delever defaults to
    -- 09:00; Qoida defaults to midnight, because midnight is what a merchant
    -- assumes until they say otherwise, and a default that silently shifts a
    -- third of the evening into tomorrow is a support ticket on day one.
    business_day_start time NOT NULL DEFAULT '00:00',

    -- Copied from the tenant rather than joined, for the same reason the order
    -- snapshots its channel code: a tenant that moves timezone has not moved last
    -- year's Tuesdays.
    timezone varchar(64) NOT NULL,

    boundary_version integer NOT NULL DEFAULT 1,
    boundary_effective_from date NOT NULL,

    -- How far the recut has got after a boundary change. Null means no boundary
    -- change is outstanding. While it is set, a query whose range starts before
    -- it and ends after it is refused rather than answered from two regimes.
    recut_completed_through date,

    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_business_day_policy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_business_day_policy_version CHECK (boundary_version >= 1),
    CONSTRAINT ck_business_day_policy_timezone CHECK (length(btrim(timezone)) > 0)
);

COMMENT ON TABLE reporting.business_day_policies IS
    'ADR 0043. The tenant-scoped business-day boundary every fact is dated against. Changing it requires an ADR 0027 approval and a full recut.';
COMMENT ON COLUMN reporting.business_day_policies.boundary_version IS
    'Stamped onto every fact. A range spanning two versions is refused by the query API rather than answered by silently mixing two definitions of the same day.';
COMMENT ON COLUMN reporting.business_day_policies.recut_completed_through IS
    'The last business date recut under the current boundary version. Null when no boundary change is outstanding.';

-- ---------------------------------------------------------------------------
-- The metric registry
-- ---------------------------------------------------------------------------
--
-- The definitions themselves are code (`reporting.domain.MetricRegistry`), not
-- rows: a definition that can be edited with an UPDATE is a definition two
-- surfaces can disagree about, which is the failure this whole ADR exists to
-- prevent. This table is the registry's mirror and, more usefully, its signature
-- ledger — finance signs a version here, and the API says "provisional" until
-- they have.
--
-- The mirror is asserted at startup rather than written over: if a stored row
-- disagrees with code for the same (metric_id, version), the application refuses
-- to start and names the drift. ADR 0043 is explicit that a definition change is
-- a new version, so the disagreement is always a mistake and never a migration.
CREATE TABLE reporting.metric_definitions (
    metric_id varchar(64) NOT NULL,
    version integer NOT NULL,

    grain varchar(48) NOT NULL,

    -- The table the number comes from, named so a reader can go and look. ADR
    -- 0043's first requirement is provenance per number, not per report.
    source_fact varchar(255) NOT NULL,

    -- Whether that source exists today. The operations prototype renders an
    -- unavailable metric as unbuilt rather than as zero, which is the difference
    -- between "we do not know" and "it is nothing".
    source_available boolean NOT NULL,

    aggregation varchar(24) NOT NULL,
    inclusion_rule_code varchar(48) NOT NULL,
    currency_rule varchar(24) NOT NULL,
    rounding_rule varchar(128) NOT NULL,
    unit varchar(24) NOT NULL,

    -- A digest over the code-side definition. The startup check compares this
    -- rather than a dozen columns, so a drifted row reports one difference and
    -- not twelve.
    definition_digest char(64) NOT NULL,

    effective_from date,

    signed_by varchar(255),
    signed_at timestamptz,

    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_metric_definition PRIMARY KEY (metric_id, version),
    CONSTRAINT ck_metric_definition_version CHECK (version >= 1),
    -- Stated as an equivalence and not as "both null or both set", which in SQL
    -- leaves the mixed case unconstrained through the three-valued-logic hole.
    CONSTRAINT ck_metric_definition_signature CHECK ((signed_by IS NULL) = (signed_at IS NULL)),
    CONSTRAINT ck_metric_definition_aggregation CHECK (aggregation IN (
        'SUM', 'COUNT', 'COUNT_DISTINCT', 'RATIO', 'MEDIAN', 'DISTRIBUTION')),
    CONSTRAINT ck_metric_definition_currency_rule CHECK (currency_rule IN ('UZS_SOM', 'NONE')),
    CONSTRAINT ck_metric_definition_unit CHECK (unit IN (
        'MONEY_SOM', 'COUNT', 'SECONDS', 'MINUTES', 'BASIS_POINTS'))
);

COMMENT ON TABLE reporting.metric_definitions IS
    'ADR 0043. Mirror of the code-owned metric registry plus the finance signature. Definitions are never edited here: a definition change is a new version.';
COMMENT ON COLUMN reporting.metric_definitions.signed_by IS
    'Finance sign-off. Null means the definition ships provisional and every surface using it must say so.';
COMMENT ON COLUMN reporting.metric_definitions.source_available IS
    'False where the metric is defined but its source fact is not built yet. Such a metric is reported as unbuilt, never as zero.';
COMMENT ON COLUMN reporting.metric_definitions.currency_rule IS
    'UZS_SOM means whole som. There is no sub-unit: dividing by a hundred anywhere in this path shows a customer the wrong price.';

-- ---------------------------------------------------------------------------
-- fact_order
-- ---------------------------------------------------------------------------
--
-- One row per order, dated on its business date, carrying everything the
-- day-grain reports need so that no report joins back to `ordering`.
--
-- Partitioned monthly by `business_date`, following the pattern already used for
-- `audit.audit_events`, with one difference argued below at the partition block.
CREATE TABLE reporting.fact_order (
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    business_date date NOT NULL,
    boundary_version integer NOT NULL,

    -- The instant beside the date, as ADR 0043 requires. A date alone cannot be
    -- re-derived under a different boundary, which would make the recut
    -- impossible rather than merely expensive.
    occurred_at timestamptz NOT NULL,
    closed_at timestamptz,

    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- ADR 0038. Snapshotted from the payment intent that priced the order and
    -- never re-resolved, so it matches what the receipt said. Null where no
    -- fiscal identity was recorded — which is not the same as belonging to the
    -- tenant's only entity, and the money reports group it as its own bucket
    -- rather than folding it into one.
    legal_entity_id uuid,

    channel_code varchar(32) NOT NULL,
    fulfilment_type varchar(16) NOT NULL,
    terminal_status varchar(24) NOT NULL,

    cancellation_reason_code varchar(64),

    -- ADR 0039's order_outcomes, copied when that module exists. Null today on
    -- every row, and null is not NO_EFFECT: a reservation released before
    -- production and four cooked dishes binned at the pass are the same number
    -- here until these columns are filled, which is exactly what the cancellation
    -- panel has to stop claiming.
    stock_disposition varchar(24),
    liability_party varchar(24),

    -- ADR 0029: the keyed hash, never an account id that joins to a person and
    -- never a phone number. Reporting has no PERSONAL field at all.
    customer_subject_hash varchar(64),
    is_first_order boolean,

    gross_revenue_som bigint NOT NULL,
    discount_som bigint NOT NULL,
    delivery_fee_som bigint NOT NULL,
    tax_som bigint NOT NULL,
    net_revenue_som bigint NOT NULL,

    -- ADR 0040 supplies this. Null is not zero: an aggregator order with an
    -- unknown commission read as a zero-commission order overstates margin on
    -- every channel comparison, and revenue.net.v1 deliberately does not
    -- subtract it. A revenue.net_of_commission.v2 arrives with the fact.
    aggregator_commission_som bigint,

    line_count integer NOT NULL,
    item_count integer NOT NULL,

    seconds_to_confirm integer,
    seconds_to_ready integer,
    seconds_total integer,

    -- ADR 0036's promise, copied so lateness is answerable from the fact alone.
    -- The promise was stamped at checkout and never recomputed, so a later edit
    -- to a branch's preparation bands cannot move a promise already made.
    promised_at timestamptz,
    promise_travel_minutes integer,

    -- Signed seconds past the promise: positive is late, negative is early. Null
    -- exactly when there is no promise or the order never closed, which is a
    -- third state and not a zero. ADR 0036 keeps lateness derived and unstored on
    -- the order; here it is a closed order's settled fact, which is the different
    -- question that ADR says belongs to reporting.
    seconds_late integer,

    metric_calculation_version integer NOT NULL,
    source_order_version integer NOT NULL,
    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_order PRIMARY KEY (tenant_id, business_date, order_id),
    CONSTRAINT ck_fact_order_amounts CHECK (
        gross_revenue_som >= 0 AND discount_som >= 0 AND delivery_fee_som >= 0
        AND tax_som >= 0 AND net_revenue_som >= 0
        AND (aggregator_commission_som IS NULL OR aggregator_commission_som >= 0)),
    CONSTRAINT ck_fact_order_counts CHECK (line_count >= 0 AND item_count >= 0),
    -- Stored, never recomputed at query time. Two places computing net revenue is
    -- precisely how a dashboard tile and a finance CSV come to disagree.
    CONSTRAINT ck_fact_order_net CHECK (net_revenue_som = gross_revenue_som - discount_som),
    -- Lateness is known exactly when there was a promise and the order closed.
    CONSTRAINT ck_fact_order_lateness_pairing CHECK (
        (promised_at IS NOT NULL AND closed_at IS NOT NULL) = (seconds_late IS NOT NULL)),
    CONSTRAINT ck_fact_order_fulfilment CHECK (
        fulfilment_type IN ('DELIVERY', 'PICKUP', 'DINE_IN')),
    -- The same twelve as ordering.ck_order_status. Repeated rather than
    -- referenced because a report drawn last quarter must not change meaning when
    -- the ordering vocabulary does; if it ever does change, this constraint fails
    -- the close job loudly instead of accepting a status no chart knows about.
    CONSTRAINT ck_fact_order_terminal_status CHECK (terminal_status IN (
        'RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL', 'PAYMENT_FAILED',
        'CONFIRMED', 'REJECTED', 'EXPIRED', 'PREPARING', 'READY', 'FULFILLING',
        'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_fact_order_stock_disposition CHECK (stock_disposition IS NULL
        OR stock_disposition IN ('RELEASE', 'RETURN_TO_STOCK', 'WRITE_OFF', 'NO_EFFECT')),
    CONSTRAINT ck_fact_order_liability_party CHECK (liability_party IS NULL
        OR liability_party IN ('TENANT', 'CUSTOMER', 'COURIER_PARTNER', 'PLATFORM'))
) PARTITION BY RANGE (business_date);

COMMENT ON TABLE reporting.fact_order IS
    'ADR 0043. One row per order on its business date. Derived and rebuildable: drop it and recut rather than repair it.';
COMMENT ON COLUMN reporting.fact_order.legal_entity_id IS
    'ADR 0038, snapshotted from the payment intent that priced the order. Never re-resolved. Null means no fiscal identity was recorded, which is its own group and never folded into another entity.';
COMMENT ON COLUMN reporting.fact_order.gross_revenue_som IS
    'Order value BEFORE discount, including the delivery fee and tax. Not the amount the customer paid: that is net_revenue_som. ADR 0043 defines revenue.net.v1 as gross minus discount minus refunds, which only holds if gross is the pre-discount figure.';
COMMENT ON COLUMN reporting.fact_order.net_revenue_som IS
    'Gross minus discount, on the order date. Refunds are deliberately not subtracted here: they live in reporting.fact_refund on their own business date, so a closed report does not change when a refund lands today.';
COMMENT ON COLUMN reporting.fact_order.seconds_late IS
    'Signed seconds past the promise. Null means no promise was made or the order never closed, which is a third state and not zero.';
COMMENT ON COLUMN reporting.fact_order.promise_travel_minutes IS
    'ADR 0037 road estimate, null on every delivery order taken before that model existed. Null means travel was not modelled, not that it was zero, so delivery lateness computed from this promise is understated and the surface has to say so.';
COMMENT ON COLUMN reporting.fact_order.aggregator_commission_som IS
    'ADR 0040. Null is not zero. Reading it as zero overstates margin on every aggregator channel.';
COMMENT ON COLUMN reporting.fact_order.customer_subject_hash IS
    'ADR 0029 keyed hash of the customer account id. Never an account id, never a phone number: no PERSONAL field exists anywhere in the reporting schema.';

-- ---------------------------------------------------------------------------
-- fact_order_line
-- ---------------------------------------------------------------------------
CREATE TABLE reporting.fact_order_line (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    order_id uuid NOT NULL,
    line_id uuid NOT NULL,

    location_id uuid NOT NULL,
    variant_id uuid,
    category_id uuid,

    -- Snapshotted, so renaming a dish does not rewrite last year's sales report.
    product_name_snapshot varchar(255) NOT NULL,

    quantity integer NOT NULL,
    gross_som bigint NOT NULL,
    discount_som bigint NOT NULL,
    net_som bigint NOT NULL,

    CONSTRAINT pk_fact_order_line PRIMARY KEY (tenant_id, business_date, line_id),
    CONSTRAINT ck_fact_order_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_fact_order_line_amounts CHECK (
        gross_som >= 0 AND discount_som >= 0 AND net_som >= 0),
    CONSTRAINT ck_fact_order_line_net CHECK (net_som = gross_som - discount_som)
) PARTITION BY RANGE (business_date);

COMMENT ON TABLE reporting.fact_order_line IS
    'ADR 0043. One row per order line, for product sales cuts. Revenue metrics sum fact_order and never this table: summing both is how revenue doubles.';

-- ---------------------------------------------------------------------------
-- fact_refund
-- ---------------------------------------------------------------------------
--
-- A refund is a grain, not a column, for the same reason ADR 0043 gives for
-- payment: the ADR sketched `refunded_som` and `refunded_on_business_date` on
-- `fact_order`, and that pair has no answer for an order refunded partially on
-- Tuesday and again on Friday. It either attributes both to one date or holds one
-- total against the wrong day, and both are wrong in a way nothing detects.
--
-- One row per refund transaction, filed under the refund's own business date, is
-- what makes "refunds attributed to the refund's business date" literally true.
-- The order's own date is carried along so a support question — which day's
-- revenue did this reverse — is answerable without a join back to fact_order,
-- which may live in a different partition.
CREATE TABLE reporting.fact_refund (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    refund_id uuid NOT NULL,

    order_id uuid NOT NULL,
    -- Deliberately not a foreign key. fact_order is partitioned by its own
    -- business_date, which is usually not this row's, so the reference would have
    -- to name a partition and would break the moment a boundary recut moved the
    -- order to a different day.
    order_business_date date NOT NULL,

    location_id uuid NOT NULL,
    legal_entity_id uuid,

    -- Copied from the order being refunded rather than left off. Without them a
    -- refund cannot be attributed into agg_branch_day at all, which is keyed by
    -- channel and fulfilment type, and the day's refunds would silently land in
    -- no branch row.
    channel_code varchar(32) NOT NULL,
    fulfilment_type varchar(16) NOT NULL,

    refunded_som bigint NOT NULL,
    occurred_at timestamptz NOT NULL,

    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,
    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_refund PRIMARY KEY (tenant_id, business_date, refund_id),
    CONSTRAINT ck_fact_refund_amount CHECK (refunded_som > 0),
    CONSTRAINT ck_fact_refund_fulfilment CHECK (
        fulfilment_type IN ('DELIVERY', 'PICKUP', 'DINE_IN'))
) PARTITION BY RANGE (business_date);

COMMENT ON TABLE reporting.fact_refund IS
    'ADR 0043. One row per refund on the refund''s own business date. A refund is a grain and not a column: a pair of columns on fact_order cannot express two partial refunds on two dates.';
COMMENT ON COLUMN reporting.fact_refund.order_business_date IS
    'The business date of the order being refunded, carried so "which day did this reverse" is answerable without crossing a partition.';

-- ---------------------------------------------------------------------------
-- Partitions
-- ---------------------------------------------------------------------------
--
-- Monthly, and deliberately with no DEFAULT partition, which is where this
-- departs from `audit.audit_events`.
--
-- Audit has a default because an audited action must never fail for want of a
-- partition: losing the evidence is worse than any alternative. A fact is the
-- opposite case. It is derived and rebuildable, so a failed INSERT costs a rerun
-- and nothing else, whereas a default partition quietly absorbs rows for a month
-- nobody provisioned and then blocks the creation of that month's partition until
-- someone finds and moves them. A loud failure in a batch job beats a silent
-- bucket that makes the fix harder the longer it goes unnoticed.
--
-- ReportingPartitionManager keeps months ahead of the clock; this seeds the range
-- the pilot will live in.
CREATE OR REPLACE FUNCTION reporting.ensure_fact_partition(p_table text, p_month date)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_start date := date_trunc('month', p_month)::date;
    v_end   date := (date_trunc('month', p_month) + interval '1 month')::date;
    v_name  text := p_table || '_' || to_char(v_start, 'YYYYMM');
BEGIN
    IF p_table NOT IN ('fact_order', 'fact_order_line', 'fact_refund') THEN
        RAISE EXCEPTION 'reporting.ensure_fact_partition does not manage %', p_table;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'reporting' AND c.relname = v_name) THEN
        RETURN;
    END IF;

    EXECUTE format(
        'CREATE TABLE reporting.%I PARTITION OF reporting.%I FOR VALUES FROM (%L) TO (%L)',
        v_name, p_table, v_start, v_end);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.%I TO qoida_application', v_name);
    EXECUTE format('GRANT SELECT ON reporting.%I TO qoida_reporting_read', v_name);
END;
$$;

COMMENT ON FUNCTION reporting.ensure_fact_partition(text, date) IS
    'ADR 0043 partition upkeep. Idempotent, and refuses any table it does not own so a typo cannot partition something else.';

-- ---------------------------------------------------------------------------
-- Aggregates
-- ---------------------------------------------------------------------------
--
-- `agg_branch_day` carries `legal_entity_id` in its key, which the ADR's sketch
-- did not. Without it the aggregate can produce a tenant-wide revenue total for a
-- multi-entity tenant — a number that reconciles to neither tax filing and that
-- somebody would carry into a return. With it, the entity split is the cheap
-- query and the combined total is the one that has to be asked for deliberately,
-- which is the right way round for a figure nobody should want.
CREATE TABLE reporting.agg_branch_day (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    location_id uuid NOT NULL,
    legal_entity_id uuid,
    channel_code varchar(32) NOT NULL,
    fulfilment_type varchar(16) NOT NULL,

    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    order_count integer NOT NULL,
    cancelled_count integer NOT NULL,
    gross_som bigint NOT NULL,
    discount_som bigint NOT NULL,
    net_som bigint NOT NULL,

    -- Refunds attributed to THIS date, from orders that may belong to any other
    -- date. This is what makes revenue.net.v1 move on the refund's day and leave
    -- the order's day alone.
    refunded_som bigint NOT NULL,

    -- Nullable on purpose. A day on which no order closed has no average, and a
    -- zero there reads as "instant", which is the more damaging wrong answer.
    avg_seconds_total integer,

    promised_count integer NOT NULL,
    late_count integer NOT NULL,

    distinct_customers integer NOT NULL,
    new_customers integer NOT NULL,

    built_at timestamptz NOT NULL DEFAULT now(),

    -- A unique constraint and not a primary key, because the identifying tuple
    -- includes a nullable column and a primary key cannot. The null is load
    -- bearing: it is the group of orders with no recorded fiscal identity, which
    -- must stay its own bucket rather than being folded into an entity. NULLS NOT
    -- DISTINCT is what makes that bucket one row instead of many, since
    -- PostgreSQL otherwise treats every null as distinct from every other.
    CONSTRAINT uq_agg_branch_day UNIQUE NULLS NOT DISTINCT (
        tenant_id, business_date, location_id, legal_entity_id, channel_code, fulfilment_type),
    CONSTRAINT ck_agg_branch_day_counts CHECK (
        order_count >= 0 AND cancelled_count >= 0 AND promised_count >= 0
        AND late_count >= 0 AND late_count <= promised_count
        AND distinct_customers >= 0 AND new_customers >= 0
        AND new_customers <= distinct_customers),
    CONSTRAINT ck_agg_branch_day_amounts CHECK (
        gross_som >= 0 AND discount_som >= 0 AND net_som >= 0 AND refunded_som >= 0),
    CONSTRAINT ck_agg_branch_day_fulfilment CHECK (
        fulfilment_type IN ('DELIVERY', 'PICKUP', 'DINE_IN'))
);

COMMENT ON TABLE reporting.agg_branch_day IS
    'ADR 0043 day-grain aggregate behind the console overview. Keyed by legal entity so an entity split is the cheap query and a combined multi-entity total is not.';
COMMENT ON COLUMN reporting.agg_branch_day.refunded_som IS
    'Refunds attributed to this business date, from orders placed on any date. Never the refunds of the orders counted in this row.';
COMMENT ON COLUMN reporting.agg_branch_day.avg_seconds_total IS
    'Null when no order closed on this day. Zero would read as an instant order, which is the more damaging wrong answer.';

-- SLA buckets are platform-fixed and versioned, never tenant-configurable. A
-- tenant-editable bucket rewrites the meaning of every chart already drawn,
-- including last quarter's, and nothing records that it happened. Raw
-- `seconds_total` stays on the fact so a v2 set can re-cut history rather than
-- reinterpret it.
CREATE TABLE reporting.agg_sla_bucket_day (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    scope_kind varchar(16) NOT NULL,
    scope_id uuid NOT NULL,
    bucket_set_version integer NOT NULL,
    bucket_code varchar(16) NOT NULL,

    order_count integer NOT NULL,
    -- Basis points rather than a percentage, because a share stored as a rounded
    -- percentage does not sum back to the whole and the chart's columns then
    -- visibly fail to add up.
    share_basis_points integer NOT NULL,

    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_agg_sla_bucket_day PRIMARY KEY (
        tenant_id, business_date, scope_kind, scope_id, bucket_set_version, bucket_code),
    CONSTRAINT ck_agg_sla_scope_kind CHECK (scope_kind IN ('LOCATION', 'COURIER')),
    CONSTRAINT ck_agg_sla_counts CHECK (order_count >= 0),
    CONSTRAINT ck_agg_sla_share CHECK (share_basis_points BETWEEN 0 AND 10000)
);

COMMENT ON TABLE reporting.agg_sla_bucket_day IS
    'ADR 0043 sla_bucket_set.v1: six half-open intervals over elapsed order seconds. Exhaustive and non-overlapping, so the shares sum. COURIER scope awaits ADR 0042.';

-- ---------------------------------------------------------------------------
-- Close runs and divergence
-- ---------------------------------------------------------------------------
--
-- The close builds a day after it ends; the recut rebuilds it after the settle
-- window so a late refund does not leave Tuesday wrong forever.
--
-- The recut does not overwrite. It compares, and writes a divergence row when the
-- re-derived total disagrees with the stored one. A projection that quietly
-- corrects itself hides the bug that caused the drift, and — the operational
-- half of the same argument — somebody has already acted on the earlier figure.
-- Silently replacing it means the manager who ordered stock against Tuesday's
-- revenue is never told the number they used has gone.
CREATE TABLE reporting.close_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    run_kind varchar(16) NOT NULL,
    status varchar(16) NOT NULL,

    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    orders_written integer NOT NULL DEFAULT 0,
    lines_written integer NOT NULL DEFAULT 0,
    divergences_found integer NOT NULL DEFAULT 0,

    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    failure_reason varchar(512),

    CONSTRAINT ck_close_run_kind CHECK (run_kind IN ('CLOSE', 'RECUT')),
    CONSTRAINT ck_close_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    -- A run that is no longer running has finished, one way or the other, and a
    -- finished run has a completion instant. Stated as an equivalence.
    CONSTRAINT ck_close_run_completion CHECK ((status <> 'RUNNING') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_close_run_failure CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_close_run_counts CHECK (
        orders_written >= 0 AND lines_written >= 0 AND divergences_found >= 0)
);

CREATE INDEX ix_close_runs_tenant_date
    ON reporting.close_runs (tenant_id, business_date, started_at DESC);

COMMENT ON TABLE reporting.close_runs IS
    'ADR 0043. Every close and recut, with what it wrote. The freshness a report declares is read from here: a report that cannot state its freshness is not shipped (ADR 0023).';

CREATE TABLE reporting.aggregate_divergences (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    business_date date NOT NULL,

    metric_id varchar(64) NOT NULL,
    metric_version integer NOT NULL,

    -- Which slice disagreed, as the dimension values that identify it. Text
    -- rather than a foreign key because the slice is a tuple, and because this
    -- row has to stay readable after the dimension row it names is gone.
    dimension_key varchar(512) NOT NULL,

    stored_value bigint NOT NULL,
    recut_value bigint NOT NULL,
    difference bigint NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'OPEN',
    detected_at timestamptz NOT NULL DEFAULT now(),
    acknowledged_by varchar(255),
    acknowledged_at timestamptz,
    resolution_note varchar(512),

    CONSTRAINT fk_aggregate_divergence_run FOREIGN KEY (run_id)
        REFERENCES reporting.close_runs (id),
    CONSTRAINT ck_aggregate_divergence_status CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_aggregate_divergence_difference CHECK (difference = recut_value - stored_value),
    -- A divergence with no difference is not a divergence, and recording one
    -- would train people to ignore the whole table.
    CONSTRAINT ck_aggregate_divergence_real CHECK (difference <> 0),
    CONSTRAINT ck_aggregate_divergence_acknowledgement CHECK (
        (acknowledged_by IS NULL) = (acknowledged_at IS NULL)),
    CONSTRAINT ck_aggregate_divergence_open CHECK (
        (status = 'OPEN') = (acknowledged_by IS NULL))
);

CREATE INDEX ix_aggregate_divergences_open
    ON reporting.aggregate_divergences (tenant_id, status, detected_at DESC);

COMMENT ON TABLE reporting.aggregate_divergences IS
    'ADR 0043. A recut that disagrees with a stored total alerts here and never overwrites: somebody may already have acted on the earlier figure.';
COMMENT ON COLUMN reporting.aggregate_divergences.dimension_key IS
    'The slice that disagreed, as dimension values. Deliberately denormalised text so the row stays readable after the branch or channel it names is archived.';

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
--
-- Every reporting query carries the tenant predicate, so every index leads with
-- tenant_id.
CREATE INDEX ix_fact_order_location_day
    ON reporting.fact_order (tenant_id, location_id, business_date);
CREATE INDEX ix_fact_order_entity_day
    ON reporting.fact_order (tenant_id, legal_entity_id, business_date);
CREATE INDEX ix_fact_order_channel_day
    ON reporting.fact_order (tenant_id, channel_code, business_date);
CREATE INDEX ix_fact_order_source
    ON reporting.fact_order (tenant_id, order_id);

CREATE INDEX ix_fact_refund_order
    ON reporting.fact_refund (tenant_id, order_id);
CREATE INDEX ix_fact_refund_location_day
    ON reporting.fact_refund (tenant_id, location_id, business_date);

CREATE INDEX ix_fact_order_line_order
    ON reporting.fact_order_line (tenant_id, order_id);
CREATE INDEX ix_fact_order_line_variant_day
    ON reporting.fact_order_line (tenant_id, variant_id, business_date);

CREATE INDEX ix_agg_branch_day_range
    ON reporting.agg_branch_day (tenant_id, business_date, location_id);

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
--
-- `qoida_reporting_read` is ADR 0023's rule expressed as a grant rather than a
-- convention: SELECT on `reporting` and nothing else, no INSERT anywhere, and no
-- USAGE on `ordering`, `payments`, `customers`, or any other module schema. A
-- reporting query that reaches a module table fails at the database instead of
-- being caught in review, or not caught.
--
-- NOLOGIN. It is granted to whichever login role the analytics connection pool
-- uses; creating a password here would put a credential in a migration file.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qoida_reporting_read') THEN
        CREATE ROLE qoida_reporting_read NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA reporting TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.business_day_policies TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.metric_definitions TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_order TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_order_line TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_refund TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.agg_branch_day TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.agg_sla_bucket_day TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.close_runs TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.aggregate_divergences TO qoida_application;
GRANT EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) TO qoida_application;

GRANT USAGE ON SCHEMA reporting TO qoida_reporting_read;
GRANT SELECT ON ALL TABLES IN SCHEMA reporting TO qoida_reporting_read;
ALTER DEFAULT PRIVILEGES IN SCHEMA reporting GRANT SELECT ON TABLES TO qoida_reporting_read;

-- Seeded after the grants so the partitions inherit them through the function.
DO $$
DECLARE
    v_month date := date '2026-01-01';
BEGIN
    WHILE v_month < date '2028-01-01' LOOP
        PERFORM reporting.ensure_fact_partition('fact_order', v_month);
        PERFORM reporting.ensure_fact_partition('fact_order_line', v_month);
        PERFORM reporting.ensure_fact_partition('fact_refund', v_month);
        v_month := (v_month + interval '1 month')::date;
    END LOOP;
END
$$;
