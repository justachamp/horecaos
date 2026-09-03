-- finance.md's scope note (§0) defers 8.3-8.6 to wave 2; this wave builds them
-- as real reads over ADR 0042's already-built courier module (shifts, cash
-- handovers, settlement periods, delivery cost lines, partner invoices), not
-- over any new reporting fact table.
--
-- Three new fleet-wide worklist reads land this wave:
--   JdbcCourierShiftStore.listHandovers   -- Finance 8.3 cash reconciliation
--   JdbcCourierLedgerStore.listPeriods    -- Finance 8.5 courier payouts
--   JdbcDeliveryCostStore.listInvoices    -- Finance 8.4 delivery cost reconciliation
--
-- All three query by (tenant_id, status) with no other selective predicate,
-- which none of V0040's indexes on these tables covers:
--   courier_cash_handovers has only uq_handover_shift (shift_id) and
--     uq_handover_identity (id, tenant_id) -- neither leads with tenant_id.
--   courier_settlement_periods has only uq_period_start (tenant_id,
--     courier_id, period_start) -- built for "one courier's history"
--     (JdbcCourierLedgerStore.periodsOf), not "every courier's periods".
--   partner_delivery_invoices has uq_partner_invoice_ref (tenant_id,
--     provider_code, provider_invoice_ref), whose leading column is usable but
--     whose second column is not what these worklists filter or sort by.
--
-- Cheap now (a pilot tenant's fleet is a handful of couriers and periods), and
-- worth having before the "worst variance first" / "largest amount payable
-- first" sort in each read has to do a sequential scan as a tenant's history
-- grows.

CREATE INDEX ix_cash_handover_tenant_status
    ON fulfillment.courier_cash_handovers (tenant_id, status, expected_minor DESC);

CREATE INDEX ix_settlement_period_tenant_status
    ON fulfillment.courier_settlement_periods (tenant_id, status, amount_payable_minor DESC);

CREATE INDEX ix_partner_invoice_tenant_status
    ON fulfillment.partner_delivery_invoices (tenant_id, status, total_minor DESC);
