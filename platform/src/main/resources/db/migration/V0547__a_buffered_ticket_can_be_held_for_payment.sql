-- ADR 0168: a paid-only hold in the kitchen buffer.
--
-- A ticket in the buffer is held for time (SCHEDULED) and for a person (MANUAL_HOLD). This adds a third
-- reason it may wait, money: the order's payment became required after the order was confirmed (ADR
-- 0039's CHANGE_PAYMENT_METHOD is the one path) and has not arrived. The hold is a reason beside the
-- release mode, not a fourth mode: a ticket keeps its mode and its release_at, so clearing the hold
-- returns it to its own schedule, and ck_ticket_scheduled_has_instant keeps meaning what it meant.
--
--   hold_reason                NULL | 'PAYMENT'
--   hold_since                 the instant it was placed; a pair with the reason
--   hold_policy_id/version     the kitchen.paid_hold document it was placed under (ADR 0030): a durable
--                              decision pins the policy it used
--   hold_blocked_notified_at   the one PAYMENT_HOLD_BLOCKED_RELEASE per hold: release_at arrived unpaid
--   hold_overdue_notified_at   the one PAYMENT_HOLD_OVERDUE per hold: the policy's overdueAfterMinutes
--                              passed after that instant
--
-- A fired ticket carries no hold (food already started is never recalled), and a VOIDED one keeps
-- whatever it had so a cancelled payment-held pre-order still tells how it ended.

ALTER TABLE kitchen.tickets
    ADD COLUMN hold_reason varchar(16),
    ADD COLUMN hold_since timestamptz,
    ADD COLUMN hold_policy_id uuid,
    ADD COLUMN hold_policy_version integer,
    ADD COLUMN hold_blocked_notified_at timestamptz,
    ADD COLUMN hold_overdue_notified_at timestamptz;

ALTER TABLE kitchen.tickets
    ADD CONSTRAINT ck_ticket_hold_reason CHECK (hold_reason IS NULL OR hold_reason IN ('PAYMENT')),
    ADD CONSTRAINT ck_ticket_hold_pair CHECK ((hold_reason IS NULL) = (hold_since IS NULL)),
    ADD CONSTRAINT ck_ticket_hold_policy_pair CHECK ((hold_policy_id IS NULL) = (hold_policy_version IS NULL)),
    ADD CONSTRAINT ck_ticket_hold_only_in_buffer CHECK (hold_reason IS NULL OR status IN ('HELD', 'VOIDED')),
    ADD CONSTRAINT ck_ticket_hold_notices CHECK (
        hold_reason IS NOT NULL
        OR (hold_blocked_notified_at IS NULL AND hold_overdue_notified_at IS NULL));

-- The sweep's read: every ticket currently held for payment, oldest first.
CREATE INDEX ix_tickets_payment_hold ON kitchen.tickets (tenant_id, hold_since) WHERE hold_reason IS NOT NULL;

ALTER TABLE kitchen.ticket_events DROP CONSTRAINT ck_ticket_event_trigger;

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
        'ORDER_AMENDED',
        -- ADR 0168: the ticket was put on hold for money that is required and has not arrived ...
        'PAYMENT_HOLD_PLACED',
        -- ... the hold ended: the money arrived, the policy was turned off, or the order went terminal ...
        'PAYMENT_HOLD_CLEARED',
        -- ... release_at arrived with the money still absent (once per hold) ...
        'PAYMENT_HOLD_BLOCKED_RELEASE',
        -- ... and it stayed absent for the policy's overdueAfterMinutes (once per hold) ...
        'PAYMENT_HOLD_OVERDUE',
        -- ... or a named person fired it anyway, with a coded reason.
        'PAYMENT_HOLD_OVERRIDDEN',
        -- A buffered ticket voided with its order, held for payment or not.
        'ORDER_TERMINAL'));

COMMENT ON COLUMN kitchen.tickets.hold_reason IS
    'ADR 0168. Why a buffered ticket will not fire on its schedule: PAYMENT, money required and absent. Null for every ticket in the ordinary buffer.';
COMMENT ON COLUMN kitchen.tickets.hold_policy_id IS
    'ADR 0030. The kitchen.paid_hold policy document the hold was placed under, with its version. Null when no hold has ever been placed.';
