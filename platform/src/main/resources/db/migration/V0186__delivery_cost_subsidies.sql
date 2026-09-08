-- ADR 0014's "Customer fee and cost allocation": the row that had nowhere to
-- go. The customer's delivery fee is snapshotted at checkout and never
-- silently increased when a partner costs more than it. This table is where
-- that gap is written instead — DELIVERY_COST_SUBSIDY, an internal cost
-- allocation between the tenant, brand, location or platform, never a
-- discount and never a mutation of the order.
--
-- Deliberately not a fourth ADR 0048 remedy. `payments.order_remedies` is
-- "three remedies and no fourth" by that record's own decision, and all three
-- make a customer whole. Nobody here was shorted anything: the customer paid
-- exactly the fee they agreed to. This is HorecaOS's own books recording who
-- covers the difference between that fee and what winning the booking cost,
-- which is why ADR 0013 places it in "fulfillment/reporting" rather than in
-- the refund/remedy model payments owns.

CREATE TABLE fulfillment.delivery_cost_subsidies (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    delivery_plan_id uuid NOT NULL,
    shipment_id uuid NOT NULL,

    provider_binding_id uuid NOT NULL,
    provider_type varchar(32) NOT NULL,

    -- What the customer paid (never moves) and what winning the booking cost
    -- (the winning quote's price, an ACCRUED estimate in the same sense
    -- fulfillment.delivery_cost_lines already uses that word for a partner's
    -- booked price -- not yet the invoiced or settled figure).
    customer_delivery_fee_minor bigint NOT NULL,
    provider_cost_minor bigint NOT NULL,
    -- The arithmetic is a constraint, not a convention: a subsidy row whose
    -- amount disagrees with its own two inputs is worse than no row.
    subsidy_amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,

    -- Who the ADR 0030 policy resolved as absorbing it. MANUAL_APPROVAL_REQUIRED
    -- is a legitimate value here, not a placeholder for one: the row is written
    -- the instant the gap is known regardless of who ends up paying it.
    bearer varchar(24) NOT NULL,
    policy_id uuid,
    policy_version integer,

    recognised_at timestamptz NOT NULL DEFAULT now(),
    recorded_by varchar(128) NOT NULL,

    CONSTRAINT fk_subsidy_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_subsidy_plan FOREIGN KEY (delivery_plan_id, tenant_id)
        REFERENCES fulfillment.delivery_plans (id, tenant_id),
    CONSTRAINT fk_subsidy_shipment FOREIGN KEY (shipment_id, tenant_id)
        REFERENCES fulfillment.shipments (id, tenant_id),
    CONSTRAINT uq_subsidy_identity UNIQUE (id, tenant_id),
    -- One plan is booked once. A second row for the same shipment would be a
    -- replay of the same booking event, not a second overrun.
    CONSTRAINT uq_subsidy_one_per_shipment UNIQUE (tenant_id, shipment_id),
    CONSTRAINT ck_subsidy_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_subsidy_bearer CHECK (
        bearer IN ('TENANT', 'BRAND', 'LOCATION', 'PLATFORM', 'MANUAL_APPROVAL_REQUIRED')),
    CONSTRAINT ck_subsidy_amounts_not_negative CHECK (
        customer_delivery_fee_minor >= 0 AND provider_cost_minor >= 0),
    CONSTRAINT ck_subsidy_amount_positive CHECK (subsidy_amount_minor > 0),
    CONSTRAINT ck_subsidy_gap_arithmetic CHECK (
        subsidy_amount_minor = provider_cost_minor - customer_delivery_fee_minor),
    CONSTRAINT ck_subsidy_policy_pair CHECK ((policy_id IS NULL) = (policy_version IS NULL))
);

COMMENT ON TABLE fulfillment.delivery_cost_subsidies IS
    'ADR 0014. Append-only. Written once, at booking, when the winning partner quote costs more than the customer''s snapshotted delivery fee. Not a payments remedy -- payments.order_remedies is three remedies and no fourth (ADR 0048) and this never touches the order or the customer.';
COMMENT ON COLUMN fulfillment.delivery_cost_subsidies.provider_cost_minor IS
    'Integer minor units, whole som for UZS (ADR 0038). The winning quote''s price at booking -- an ACCRUED estimate, not yet reconciled against a partner invoice.';
COMMENT ON COLUMN fulfillment.delivery_cost_subsidies.bearer IS
    'Who the ADR 0030 fulfillment.delivery_subsidy policy resolved as absorbing this gap. Provisional platform default until product and finance close the open bearer question carried from ADR 0013 into ADR 0048.';

CREATE INDEX ix_subsidy_location_recognised ON fulfillment.delivery_cost_subsidies
    (tenant_id, location_id, recognised_at);

-- Append-only: no UPDATE, no DELETE. A wrong bearer decision is corrected by a
-- later operator allocation, not by rewriting the recognition of the gap.
GRANT SELECT, INSERT ON fulfillment.delivery_cost_subsidies TO horecaos_application;
