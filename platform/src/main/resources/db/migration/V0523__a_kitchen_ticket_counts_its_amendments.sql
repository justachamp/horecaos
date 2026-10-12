-- ADR 0154, "Routing and triggers": an amendment that reaches a ticket which has already been
-- released prints an amendment ticket, and the print job's idempotency key carries the amendment's
-- own number (source_revision) so a replayed amendment prints once and a second amendment prints again.
--
-- The kitchen's own fact, owned by the kitchen: the ticket counts the amendments that changed what it
-- holds AFTER it was released (KitchenTicketService.syncAmendedLines bumps it in the same transaction
-- as the items it struck or added). An amendment to a ticket still on hold is not counted: nothing was
-- cooked or printed from the old version, and the release prints the order as it then is. Zero is the
-- ticket as it was released, which is every ticket before this column.
ALTER TABLE kitchen.tickets
    ADD COLUMN amendment_revision integer NOT NULL DEFAULT 0;

ALTER TABLE kitchen.tickets
    ADD CONSTRAINT ck_ticket_amendment_revision CHECK (amendment_revision >= 0);

COMMENT ON COLUMN kitchen.tickets.amendment_revision IS
    'ADR 0154. How many order amendments changed this ticket''s items after it was released. A printing amendment ticket is keyed on it.';

-- The reconciling sweeper reads a branch's recently released tickets by release time.
CREATE INDEX ix_tickets_released ON kitchen.tickets (tenant_id, location_id, released_at)
    WHERE released_at IS NOT NULL;
