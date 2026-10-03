-- ADR 0141 (Accepted 2026-10-01), gap map row 2.5a: two things the marketplace
-- reconciler's per-binding state (V0464) did not yet remember.
--
-- 1. The item mapping is a resolver input, so a change to it asks for an early sweep.
--
--    The reconciler's mapped items ARE integration.provider_entity_mappings rows of
--    entity_type MENU_ITEM (ADR 0040): a new row is a dish the partner has never been
--    told about, a retired or deleted row is a dish the reconciler must stop keeping a
--    row for, and a changed external id means the old partner id is no longer the one to
--    tell. The resync sweep already picks every one of these up within one interval, so
--    this marker is an accelerator and not the guarantee (ADR 0141 Decision 7): a missed
--    marker costs a delay and never prevents a correction.
--
--    It is a trigger rather than a call in each writer for the same reason the sweep is
--    the guarantee: the writers of a MENU_ITEM mapping are the mapping pane, a menu sync
--    ADR 0040 has not built yet, and whatever an aggregator adapter brings. A list of
--    writers to remember is exactly what the ADR argues cannot be the correctness
--    argument. The table and the state it marks are both integration's own, so the
--    trigger crosses no module boundary. It marks in the transaction that wrote the
--    mapping: the marker and the fact commit or roll back together.
--
-- 2. Whether a stale channel has already been reported.
--
--    A binding whose pushes have gone unconfirmed for longer than its bound raises
--    MarketplaceChannelWentStale and an operations alert -- once. stale_alerted_at is the
--    "once": it is set in the same transaction that appends the event, so two replicas
--    ticking the same binding cannot both report it, and it is cleared when the binding
--    has nothing unconfirmed past its bound, so the next staleness is a new report.

ALTER TABLE integration.marketplace_availability_sync_state
    ADD COLUMN stale_alerted_at timestamptz;

COMMENT ON COLUMN integration.marketplace_availability_sync_state.stale_alerted_at IS
    'ADR 0141. When MarketplaceChannelWentStale was raised for this binding and has not since cleared; NULL when the binding is not stale or has not been reported. Set in the same transaction as the event, so the report is made once.';

CREATE FUNCTION integration.request_marketplace_sweep_for_mapping() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    changed record;
BEGIN
    IF TG_OP = 'DELETE' THEN
        changed := OLD;
    ELSE
        changed := NEW;
    END IF;

    IF changed.entity_type <> 'MENU_ITEM' OR changed.binding_id IS NULL THEN
        RETURN NULL;
    END IF;

    -- An update that changed nothing the reconciler reads (a last_seen_at touch, a
    -- version bump on its own) asks for nothing.
    IF TG_OP = 'UPDATE'
       AND OLD.status = NEW.status
       AND OLD.horecaos_entity_id = NEW.horecaos_entity_id
       AND OLD.external_entity_id = NEW.external_entity_id
       AND OLD.binding_id = NEW.binding_id THEN
        RETURN NULL;
    END IF;

    -- Only a binding that still exists can be marked: the sync state references it, and
    -- a mapping removed because its binding is being removed has nothing left to sweep.
    INSERT INTO integration.marketplace_availability_sync_state
        (tenant_id, binding_id, sweep_requested_at, updated_at)
    SELECT changed.tenant_id, changed.binding_id, now(), now()
    WHERE EXISTS (
        SELECT 1 FROM integration.bindings b
        WHERE b.tenant_id = changed.tenant_id AND b.id = changed.binding_id)
    ON CONFLICT (binding_id) DO UPDATE
    SET sweep_requested_at = COALESCE(
            integration.marketplace_availability_sync_state.sweep_requested_at, EXCLUDED.sweep_requested_at),
        updated_at = EXCLUDED.updated_at;

    -- A mapping that moved from one binding to another leaves the old binding to sweep too.
    IF TG_OP = 'UPDATE' AND OLD.binding_id IS DISTINCT FROM NEW.binding_id AND OLD.binding_id IS NOT NULL THEN
        INSERT INTO integration.marketplace_availability_sync_state
            (tenant_id, binding_id, sweep_requested_at, updated_at)
        SELECT OLD.tenant_id, OLD.binding_id, now(), now()
        WHERE EXISTS (
            SELECT 1 FROM integration.bindings b
            WHERE b.tenant_id = OLD.tenant_id AND b.id = OLD.binding_id)
        ON CONFLICT (binding_id) DO UPDATE
        SET sweep_requested_at = COALESCE(
                integration.marketplace_availability_sync_state.sweep_requested_at, EXCLUDED.sweep_requested_at),
            updated_at = EXCLUDED.updated_at;
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_marketplace_mapping_marks_sweep
    AFTER INSERT OR UPDATE OR DELETE ON integration.provider_entity_mappings
    FOR EACH ROW EXECUTE FUNCTION integration.request_marketplace_sweep_for_mapping();

COMMENT ON FUNCTION integration.request_marketplace_sweep_for_mapping() IS
    'ADR 0141. A MENU_ITEM mapping added, retired, deleted or repointed asks its binding for an early reconciler sweep, in the same transaction. An accelerator: the resync sweep is the guarantee.';
