-- ADR 0113 (wave P10), amending ADR 0039: two more non-financial amendment
-- commands, discovered necessary while wiring the console's first amendment
-- client.
--
-- orders.md §11 named the gap in one sentence: "Adding two commands is a
-- one-line ADR amendment" -- true of AmendmentCommandType and of ADR 0039's
-- own text, and not true of this constraint. ck_amendment_command_type
-- (V0029) is a closed value list, and this repo's rule for one is the rule
-- V0172 already states: it cannot be extended in place, so the whole
-- constraint is dropped and recreated with every value it must accept. Wave
-- P10's own migration budget was zero -- the closed set of ten commands was
-- assumed cheap to widen in Java alone -- and
-- courierAndInternalNotesApplyAndTouchNoOrderField proved that assumption
-- wrong the first time either command tried to insert a row: the CHECK
-- refused SET_COURIER_NOTE and SET_INTERNAL_NOTE by name, the same way it is
-- built to refuse ADD_LINES. AmendmentCommandType#built() promises "the
-- application can actually carry this command out today"; a command that
-- carries `built() == true` in Java and a 500 in Postgres breaks that
-- promise, which is why this migration exists despite the wave's own "no
-- migration" allocation -- see the wave's own ADR (0113) for the full record
-- of the discovery.
--
-- Both new commands are the identical shape SET_KITCHEN_NOTE already has --
-- free text, no reprice, no reservation, no payment, no fiscal consequence,
-- no POS consequence -- and neither gets a column on ordering.orders the way
-- kitchen_note has one: that would be a second migration this wave still does
-- not spend, and OrderAmendmentService#patchOf folds neither into the order's
-- own fields. The only copy of either note is the payload_json already on
-- this table; OrderAmendmentService#noteOf and the new
-- AmendmentHistoryEntryResponse are how the console reads it back.
ALTER TABLE ordering.order_amendment_commands DROP CONSTRAINT ck_amendment_command_type;
ALTER TABLE ordering.order_amendment_commands
    ADD CONSTRAINT ck_amendment_command_type CHECK (command_type IN (
        'ADD_LINES', 'CHANGE_LINE_QUANTITY', 'REMOVE_LINES', 'CHANGE_PAYMENT_METHOD',
        'CHANGE_DELIVERY_ADDRESS', 'CHANGE_FULFILLMENT_TIME', 'CHANGE_CONTACT',
        'SET_KITCHEN_NOTE', 'SET_CALLBACK_REQUESTED', 'SET_CASH_TENDERED',
        'SET_COURIER_NOTE', 'SET_INTERNAL_NOTE'));
