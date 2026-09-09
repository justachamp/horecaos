-- ADR 0041: throughput ceilings finally consume something.
--
-- V0144's own comment named the gap precisely: "release_at = target_ready_at -
-- prep_estimate - station_queue_offset, the scheduler shift the ADR sketches
-- ... this migration still does not build." A restaurant could set
-- kitchen.station_capacity.portions_per_hour and nothing in the platform ever
-- read it for anything but a manager's own eyeball comparison against the
-- board. That is the same shape of defect ADR 0012's stop-list poller and the
-- ADR-status audit closed in the last two waves: configuration nobody's code
-- consumes.
--
-- KitchenTicketService.capacityOffsetSeconds now reads station_capacity at
-- ticket-creation time and pulls release_at earlier when a station's ceiling
-- for the relevant local weekday and time window would otherwise be
-- overrun -- never later, and never past target_ready_at - prep_estimate: ADR
-- 0041's own words are "a ceiling shifts release_at; it never rejects an
-- order... a kitchen that quietly holds a ticket to protect its own
-- throughput number produces a late order nobody was warned about." Nothing
-- customer-facing moves. target_ready_at is still the stored promise's own
-- component, decided once at ticket creation and never recomputed; this
-- migration and the service change behind it only ever move a kitchen-internal
-- instant earlier within the room target_ready_at - prep_estimate already
-- allows.
--
-- Two schema changes make that possible, and neither touches a table this
-- worktree did not already own.
--
-- 1. A ticket_events trigger for the one operational exception this produces:
--    a station's ceiling asked for more lead time than there was left before
--    "now" caught up with the release instant, so the ticket fires without the
--    buffer the ceiling wanted rather than being held past its promise. The
--    same ALTER-and-replace V0087 already used to add ORDER_PROPOSAL to this
--    CHECK constraint after V0030 shipped it -- constraints on an applied
--    migration are extended by a later migration, never edited in place.
--
-- 2. An index the new load query actually needs. Computing "how many portions
--    has this station already been asked for inside this occurrence of its
--    ceiling window" means filtering kitchen.tickets by location and
--    target_ready_at for every non-VOIDED status, including HELD -- a
--    preorder still sitting in the buffer already counts against the window
--    it will fire into. ix_tickets_board (V0030) does not serve this: it
--    intentionally excludes HELD, because the board is "what is live", not
--    "what is committed". This index is the buffer's own committed-load
--    twin.

ALTER TABLE kitchen.ticket_events
    DROP CONSTRAINT ck_ticket_event_trigger;

ALTER TABLE kitchen.ticket_events
    ADD CONSTRAINT ck_ticket_event_trigger CHECK (trigger IN (
        'STATION_ACTION',
        'ORDER_CONFIRMED',
        'RELEASE_SCHEDULED',
        'RELEASE_COMMAND',
        'ITEM_ROLLUP',
        'ROUTING_UNRESOLVED',
        'ORDER_PROPOSAL',
        -- A station's throughput ceiling asked for more lead time than the
        -- ticket had left before "now". The ticket still fires -- a ceiling
        -- never rejects and never holds a ticket past its promise -- but this
        -- is the record that it went out without the buffer the ceiling
        -- wanted, on the ticket the branch actually reads.
        'CAPACITY_CEILING_REACHED'));

CREATE INDEX ix_tickets_capacity_window
    ON kitchen.tickets (tenant_id, location_id, target_ready_at)
    WHERE status <> 'VOIDED';

COMMENT ON TABLE kitchen.station_capacity IS
    'ADR 0041, frontend-information-architecture.md 2.6. A station''s throughput ceiling for one weekday and one local time window. Read by the operations settings screen and, since wave 44, by KitchenTicketService.capacityOffsetSeconds when a ticket is opened: a station over its ceiling pulls the new ticket''s release_at earlier rather than the platform doing nothing with the number a manager set.';
