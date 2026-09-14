-- Wave P16 / gap map row 2.5c: a windowed digest for stop-list changes.
--
-- `InventoryOperationsAlertTrigger` used to call `OperationsAlertPort.fanOut`
-- directly, once per `ItemAvailabilityChanged`, and only on the
-- available->false edge. A branch that 86's fifteen items in one rush push
-- got fifteen separate Telegram messages, and nobody was ever told when an
-- item came back to sale (`available` -> true is the direction Delever's own
-- «Вышли со стопа» half of the grouped message names, and this build never
-- raised it at all). This table is the queue that makes coalescing possible:
-- every availability change, either direction, is appended here in the same
-- transaction as the toggle it describes (still inside
-- `InventoryOperationsAlertTrigger`'s existing `BEFORE_COMMIT` listener), and
-- a periodic sweep (`InventoryStopDigestSweeper`) claims whatever is pending
-- per location, groups it into "went on stop" / "came back" lists, and raises
-- exactly one alert through the same `OperationsAlertPort` the single-item
-- trigger already used.
--
-- Consumed rows are marked rather than deleted (`consumed_at`), matching this
-- codebase's append-only ledger convention elsewhere (`inventory.movements`
-- itself never allows UPDATE from the application role) — a digest run is
-- itself an auditable fact: which entries a given alert actually named.
CREATE TABLE notifications.inventory_stop_digest_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    variant_id uuid NOT NULL,

    -- The direction of this one toggle: false = went on stop, true = came
    -- back. Mirrors `ItemAvailabilityChanged.available` exactly.
    available boolean NOT NULL,
    reason_code varchar(64),
    occurred_at timestamptz NOT NULL,

    -- The `ItemAvailabilityChanged` event id this entry was raised from, so a
    -- digest run's own audit trail can point back to the originating fact.
    trigger_event_id uuid,

    -- Null while pending; set by the sweeper that folded this entry into a
    -- digest alert, in the same transaction as that alert's own fan-out.
    consumed_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_inventory_stop_digest_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id)
);

-- The sweeper's own read: every unconsumed entry for one location, oldest
-- first, so the digest lists items in the order they actually changed.
CREATE INDEX ix_inventory_stop_digest_pending
    ON notifications.inventory_stop_digest_entries (tenant_id, location_id, occurred_at)
    WHERE consumed_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON notifications.inventory_stop_digest_entries TO horecaos_application;
