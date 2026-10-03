-- ADR 0039, ADR 0041, ADR 0136: an amendment reaches a ticket that is already open.
--
-- An order amendment never edits a line: it closes it and appends its replacement
-- (ordering.order_lines.revision_to), and ADD_LINES appends lines the order did not have. A kitchen
-- ticket is built once, when the order is confirmed, from the lines the order held then; a line
-- appended afterwards -- a drink added at the table, a combo added to a phone order -- reached no
-- station at all, and a line replaced by a larger one was cooked at its old size.
--
-- The kitchen now listens for the amendment and brings the open ticket in line with the order
-- (KitchenAmendmentListener): items for the lines the ticket does not know, and the items of lines
-- the amendment closed cancelled. Each of those is a fact on the ticket's own timeline -- "why is
-- this burger on the grill, and why was that one struck" has to be answerable from the board, not
-- from a server log -- so the timeline's trigger vocabulary gains the amendment.
--
-- A widened CHECK and nothing else: no column, no table, no grant.

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
        'CAPACITY_CEILING_REACHED',
        -- An ADR 0039 amendment added, replaced or struck lines on an order whose ticket was open:
        -- an item routed to its station for a new line, or cancelled because its line was closed.
        'ORDER_AMENDED'));
