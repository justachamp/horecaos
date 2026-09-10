-- ADR 0083: the control plane's global lookup finds an identifier across
-- tenants. Every existing index on these columns leads with tenant_id,
-- which is right for a tenant's own screens and useless to a question that
-- does not know the tenant yet; each of these lets that question use an
-- index instead of reading the whole table.
CREATE INDEX ix_order_public_number_lookup ON ordering.orders (public_order_number);
CREATE INDEX ix_external_reference_value_lookup ON ordering.order_external_references (reference_value_normalised);
CREATE INDEX ix_pos_export_external_order_lookup ON integration.pos_order_exports (external_order_id)
    WHERE external_order_id IS NOT NULL;
CREATE INDEX ix_payment_transaction_provider_reference_lookup ON payments.payment_transactions (provider_reference);
CREATE INDEX ix_courier_display_reference_lookup ON fulfillment.couriers (display_reference);
