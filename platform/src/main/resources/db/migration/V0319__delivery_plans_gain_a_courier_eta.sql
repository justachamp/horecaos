-- ADR 0014: a courier ETA, persisted (gap map row 2.1a).
--
-- Nothing in fulfilment kept the provider's own ETA anywhere. Yandex and Noor
-- both answer /check-price and /orders/eval with an `eta` figure and the
-- adapter boundary (YandexDeliveryAdapter, NoorDeliveryAdapter) turned it into
-- `etaMinutes` on a quote result that was then discarded: CamelShipmentBookingPort
-- never overrode ShipmentBookingPort.quote(), so DeliverySourcingService always
-- saw QUOTE_NOT_WIRED and the figure never survived the adapter call.
--
-- One column, on the plan rather than the shipment: it is captured once, at
-- the moment a partner's quote wins the plan (DeliverySourcingService.execute,
-- the same instant a DELIVERY_COST_SUBSIDY may be recognised), and an
-- in-house-fleet plan or a partner that returns no ETA simply never sets it.
-- Absolute rather than a raw minute count -- `now + etaMinutes` at capture
-- time -- for the same reason target_ready_at and estimated_ready_at already
-- on this table are absolute: a relative figure goes stale the moment it is
-- read, and every other "when will this happen" column here already answers
-- in an instant, not a duration.
ALTER TABLE fulfillment.delivery_plans
    ADD COLUMN courier_eta_at timestamptz;

COMMENT ON COLUMN fulfillment.delivery_plans.courier_eta_at IS
    'ADR 0014. The winning partner quote''s own delivery ETA, captured once at booking as now() + quote.deliveryEtaSeconds. Null for a plan an in-house courier carries, and null for a partner that answered no ETA -- never fabricated.';
