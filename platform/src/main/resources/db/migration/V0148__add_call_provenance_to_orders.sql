-- ADR 0064: call-to-order provenance.
--
-- A call that becomes an order is an ordinary operations order; the only
-- addition is a fact about where it came from, the same discipline V0038 used
-- for marketplace_binding_id. No FK to voice.call_events: this column is set
-- by the operations app, after the fact, from information the frontend
-- already holds (the call id shown on the screen-pop card and the order id it
-- just created or opened) rather than by any code-level dependency from
-- ordering onto the voice module — ordering stays a leaf with respect to
-- voice, exactly as it already is with respect to every other channel.
ALTER TABLE ordering.orders ADD COLUMN source_call_id uuid;

COMMENT ON COLUMN ordering.orders.source_call_id IS
    'ADR 0064: the voice.call_events.id this order originated from, set once via '
    'OperationsOrderController''s call-provenance endpoint. Null for every '
    'non-phone order, which is most of them.';
