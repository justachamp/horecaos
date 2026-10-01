-- ADR 0141, gap map row 2.5a: the per-binding half of the marketplace reconciler.
--
-- V0463 holds what each ITEM is told. This holds what each BINDING has been through:
-- when its next full recompute is due, whether a change has asked for one at once, and
-- the two facts a resumption needs to remember.
--
-- The resync sweep is the GUARANTEE, the markers an accelerator (ADR 0141 Decision 7).
-- Every active MARKETPLACE binding is recomputed in full, through the one resolver,
-- at least every resync interval -- jittered per binding so forty venues do not tick
-- together -- and at once when a binding becomes active, an adapter starts declaring
-- marketplace.availability.push, or a suspended reconciler is resumed. A marker is a
-- change that asks for an early sweep (sweep_requested_at); a missing marker delays a
-- correction and never prevents it, which is why the sweep, not the list of markers,
-- is the correctness argument.
--
-- reconcile_was_enabled and was_stale exist because the partner portal may have been
-- edited by hand while the reconciler could not talk to it (the stop-list banner tells
-- the operator to do exactly that). When the reconciler is resumed, or recovers from a
-- stale outbound watermark, every confirmed_available of the binding is nulled first,
-- so resumption resends everything once rather than trusting a belief that went
-- unchecked.

CREATE TABLE integration.marketplace_availability_sync_state (
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,

    next_sweep_at timestamptz,
    last_sweep_at timestamptz,
    last_sweep_item_count integer,
    -- A marker: some input a resolver reads changed, recompute at the next tick instead
    -- of waiting for next_sweep_at. Cleared by the sweep that honours it.
    sweep_requested_at timestamptz,

    -- Whether the reconcile switch was on when this binding was last ticked. A tick that
    -- finds it on after a tick that found it off is a resumption.
    reconcile_was_enabled boolean NOT NULL DEFAULT true,
    -- Whether the binding's outbound watermark was stale when last ticked.
    was_stale boolean NOT NULL DEFAULT false,

    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (binding_id),
    CONSTRAINT fk_marketplace_sync_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_marketplace_sync_count CHECK (last_sweep_item_count IS NULL OR last_sweep_item_count >= 0)
);

CREATE INDEX ix_marketplace_sync_due
    ON integration.marketplace_availability_sync_state (next_sweep_at);

COMMENT ON TABLE integration.marketplace_availability_sync_state IS
    'ADR 0141. Per MARKETPLACE binding: when the next full recompute (the resync sweep, the correctness guarantee) is due, whether a marker asked for an early one, and whether the reconciler was suspended or stale at the last tick (so a resumption resends everything once).';

GRANT SELECT, INSERT, UPDATE, DELETE ON integration.marketplace_availability_sync_state TO horecaos_application;
