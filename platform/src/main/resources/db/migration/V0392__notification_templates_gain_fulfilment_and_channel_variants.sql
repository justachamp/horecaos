-- Gap-map row 10.9a: an order-status or OTP wording could not be keyed to the
-- order's fulfilment mode or the channel it arrived on, so one wording had to
-- serve delivery, pickup and dine-in across every channel. ADR 0020's own
-- resolution chain already narrows by brand; this widens the same template
-- row (never a new table) with two more optional dimensions, both nullable
-- meaning "any" so every template created before this migration keeps
-- resolving exactly as it did.
--
-- Values mirror two existing closed, code-owned enums rather than inventing a
-- third vocabulary: uz.horecaos.platform.tenancy.api.FulfillmentMode (ADR
-- 0036) and uz.horecaos.platform.tenancy.api.SalesChannelSystemType (ADR
-- 0036). Neither is FK'd to a table — both are compiled-in, closed sets, the
-- same posture ordering.orders.fulfillment_mode and tenant.sales_channels.system_type
-- already take for the columns this migration mirrors.
ALTER TABLE notifications.templates
    ADD COLUMN fulfillment_mode varchar(16),
    ADD COLUMN channel_source varchar(16);

ALTER TABLE notifications.templates
    ADD CONSTRAINT ck_template_fulfillment_mode CHECK (
        fulfillment_mode IS NULL OR fulfillment_mode IN ('DELIVERY', 'PICKUP', 'DINE_IN')
    ),
    ADD CONSTRAINT ck_template_channel_source CHECK (
        channel_source IS NULL OR channel_source IN (
            'WEB', 'IOS', 'ANDROID', 'TELEGRAM', 'KIOSK', 'QR_TABLE',
            'CALL_CENTRE', 'AGGREGATOR', 'POS')
    );

COMMENT ON COLUMN notifications.templates.fulfillment_mode IS
    'ADR 0020/0036, gap-map row 10.9a. Null matches every fulfilment mode — a wildcard, not "delivery only unset".';

COMMENT ON COLUMN notifications.templates.channel_source IS
    'ADR 0020/0036, gap-map row 10.9a. Null matches every channel source. Distinct from the notification `channel` column (SMS/TELEGRAM/…), which is the outbound medium, not the order''s own inbound source.';

-- The two identity indexes below (one WHERE brand_id IS NOT NULL, one WHERE
-- brand_id IS NULL — the only way V0026 had to express "two rows agreeing on
-- a null column are still a collision" before this Postgres version's NULLS
-- NOT DISTINCT existed on this codebase's radar) are replaced by one index
-- that extends the same idea across all three optional dimensions at once,
-- rather than the eight partial indexes (2 brand x 2 fulfilment x 2 channel)
-- the old technique would have needed.
DROP INDEX notifications.ux_template_for_brand;
DROP INDEX notifications.ux_template_tenant_wide;

CREATE UNIQUE INDEX ux_template_variant ON notifications.templates
    (tenant_id, brand_id, template_key, channel, fulfillment_mode, channel_source)
    NULLS NOT DISTINCT;
