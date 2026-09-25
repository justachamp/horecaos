-- w6-reporting-facts, batch 11 (7.4c, ADR 0023/0125): the per-order
-- external-delivery-cost report's own closed fact. `readExternalDeliveryCost`
-- read `fulfillment.shipments` left-joined against `delivery_cost_lines` and
-- `partner_delivery_invoice_lines` live, on every `GET
-- .../external-delivery-cost` request -- the same ADR 0023 violation
-- `fact_delivery_fee_resolution` (V0411) exists to fix for 7.4b. One row per
-- PARTNER-sourced, DELIVERED shipment, written at close time by
-- `DayCloseService`, beside `fact_delivery`'s own producer.
--
-- `shipment_id` is the natural key.
--
-- Unlike `fact_delivery_fee_resolution`, this fact's own source keeps
-- changing after the day it describes closes: a partner invoice is imported
-- and matched days or weeks after delivery (`courier.application
-- .PartnerInvoiceService`), and reconciling a line by hand happens later
-- still. What this table stores is `matchStatus`/`providerBilledMinor`/
-- `varianceMinor` exactly as they stood at close time (ADR 0043's hour-after-
-- close cadence) -- a line matched or reconciled afterward is not reflected
-- in an already-closed day's row. That is a real behaviour change from the
-- live read this replaces (which always answered with whatever matching had
-- reached by the moment of the request); it is the trade ADR 0023's
-- read-only-projection invariant asks this report to make, the same
-- "somebody has already acted on the earlier figure" argument
-- `DayCloseService`'s own class doc makes for `fact_order`, not a decision
-- this migration is making on its own.

CREATE TABLE reporting.fact_external_delivery_cost (
    tenant_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    business_date date NOT NULL,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    location_id uuid NOT NULL,
    order_id uuid NOT NULL,
    public_order_number varchar(24) NOT NULL,
    order_total_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    -- Charged to the customer -- ordering.orders.fee_minor as of close time.
    charged_delivery_minor bigint NOT NULL,

    provider_type varchar(32),
    -- What HorecaOS itself accrued the partner would charge (delivery_cost_lines, PARTNER/ACCRUED).
    provider_estimated_minor bigint,
    invoice_line_id uuid,
    -- What the provider actually billed -- partner_delivery_invoice_lines.amount_minor.
    provider_billed_minor bigint,
    -- Null exactly when invoice_line_id is: no invoice line existed for this
    -- shipment as of close time, which the reader treats as UNBILLED, never
    -- PENDING -- see JdbcReportingStore#readExternalDeliveryCost's own doc.
    match_status varchar(24),
    variance_minor bigint,

    delivered_at timestamptz NOT NULL,
    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_external_delivery_cost PRIMARY KEY (tenant_id, shipment_id),
    CONSTRAINT ck_fact_ext_delivery_cost_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_fact_ext_delivery_cost_match_status CHECK (match_status IS NULL OR match_status IN (
        'PENDING', 'MATCHED', 'VARIANCE', 'UNBILLED', 'UNMATCHED_LINE')),
    CONSTRAINT ck_fact_ext_delivery_cost_line CHECK (
        (invoice_line_id IS NULL) = (match_status IS NULL))
);

COMMENT ON TABLE reporting.fact_external_delivery_cost IS
    'ADR 0023/0125, w6-reporting-facts (7.4c). Derived and rebuildable from fulfillment.shipments left-joined against fulfillment.delivery_cost_lines and fulfillment.partner_delivery_invoice_lines -- see DayCloseService. matchStatus/providerBilledMinor/varianceMinor are a snapshot as of business-day close, not a live reconciliation state -- see this migration''s own header.';

-- The report's own location filter.
CREATE INDEX ix_fact_ext_delivery_cost_location ON reporting.fact_external_delivery_cost
    (tenant_id, location_id, business_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_external_delivery_cost TO horecaos_application;
