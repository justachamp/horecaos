-- T11 (ADR 0125): fulfillment.shipments (V0054) is indexed by delivery_plan_id,
-- courier_id and the partner's external reference, but never by order_id --
-- every existing caller reaches a shipment through its plan. The new
-- DeliveryCompletionPort (ADR 0125) looks a shipment up by order at delivery
-- completion, and 7.4c's per-order external-delivery-cost report does the same
-- for every row it renders; both would otherwise scan.

CREATE INDEX ix_shipment_order ON fulfillment.shipments (tenant_id, order_id);
