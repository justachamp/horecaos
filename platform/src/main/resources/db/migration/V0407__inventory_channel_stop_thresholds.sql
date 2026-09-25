-- ADR 0017's QUANTITY branch (gap map row 4.4c): a per-channel-type stop
-- threshold. A tenant that keeps selling to zero on its own storefront but
-- wants an aggregator cut off earlier (so a courier is never dispatched for
-- an order the kitchen cannot actually fulfil by the time it prints) names
-- the remaining quantity at which a channel type stops selling, per stock
-- item. No row for a channel type means "no threshold, sell to zero" --
-- today's default for every channel, unless and until an operator adds one.
--
-- Keyed by tenant.sales_channels.system_type (V0020's closed vocabulary)
-- rather than a specific channel id: "stop every aggregator at 3" is the
-- shape gap map row 4.4c actually asks for ("stop the item on MARKETPLACE
-- channels..."), and a tenant with three aggregator channels would otherwise
-- have to author the same threshold three times and keep them in step by
-- hand.

CREATE TABLE inventory.channel_stop_thresholds (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    -- tenant.sales_channels.system_type's own closed set (V0020). Repeated
    -- here rather than referenced, the same "closed vocabulary, no live FK"
    -- choice V0020 documents for the channel registry itself.
    channel_system_type varchar(16) NOT NULL,
    -- The remaining quantity (on_hand - reserved) at or below which this
    -- channel type stops selling the item. Zero is a legitimate threshold
    -- (equivalent to "stop only once truly sold out", i.e. no earlier cutoff
    -- than every other channel already gets).
    stop_at_or_below numeric(14, 3) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_stop_threshold_channel_type CHECK (channel_system_type IN (
        'WEB', 'IOS', 'ANDROID', 'TELEGRAM', 'KIOSK', 'QR_TABLE',
        'CALL_CENTRE', 'AGGREGATOR', 'POS')),
    CONSTRAINT ck_stop_threshold_value CHECK (stop_at_or_below >= 0),
    CONSTRAINT fk_stop_threshold_stock_item FOREIGN KEY (stock_item_id, tenant_id)
        REFERENCES inventory.stock_items (id, tenant_id) ON DELETE CASCADE,
    -- One threshold per (stock item, channel type). Two rows for the same
    -- pair would make the effective threshold depend on read order.
    CONSTRAINT uq_channel_stop_threshold UNIQUE (tenant_id, stock_item_id, channel_system_type)
);

CREATE INDEX ix_channel_stop_thresholds_item ON inventory.channel_stop_thresholds (stock_item_id);

COMMENT ON TABLE inventory.channel_stop_thresholds IS
    'ADR 0017 QUANTITY branch, gap map row 4.4c: a per-(stock item, channel system_type) remaining-quantity cutoff. Consulted by the channel-aware availability read (InventoryService#checkAvailabilityForChannel), never by the reservation/hold path -- a reservation is refused only by true stock exhaustion (on_hand - reserved), never by this projection-only cutoff, so a direct-channel order can still hold and commit stock an aggregator has already stopped selling.';

GRANT SELECT, INSERT, UPDATE, DELETE ON inventory.channel_stop_thresholds TO horecaos_application;
