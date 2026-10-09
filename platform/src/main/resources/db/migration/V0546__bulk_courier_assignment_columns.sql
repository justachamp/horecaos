-- ADR 0172: bulk courier assignment is the third member of ADR 0039's closed
-- BulkActionType. V0193's table admits ADVANCE and CANCEL; this widens the CHECK
-- (drop and re-add, V0193 is applied) and adds the columns an assignment needs.
--
-- A bulk assignment names ONE courier for the whole request and one reason code,
-- so both belong on ordering.bulk_operations. What it did to each order belongs
-- on the item: the plan and the shipment it produced (references, no foreign
-- key, because fulfillment rows are cancelled and never deleted and ordering
-- must not take a cycle through fulfillment's tables) and a non-fatal note such
-- as OVERRODE_DISPATCH_RULE or OUTSIDE_DISTANCE_BAND. A refusal's own detail
-- (the dispatch gate's named reasons) goes in the same column.
--
-- ordering stores no courier name, no address and no phone: a courier id, a
-- plan id, a shipment id and codes.

ALTER TABLE ordering.bulk_operations
    ADD COLUMN courier_id uuid,
    ADD COLUMN reason_code varchar(48);

ALTER TABLE ordering.bulk_operations DROP CONSTRAINT ck_bulk_operation_action_type;
ALTER TABLE ordering.bulk_operations
    ADD CONSTRAINT ck_bulk_operation_action_type
        CHECK (action_type IN ('ADVANCE', 'CANCEL', 'ASSIGN_COURIER'));

-- The courier is the action's whole target: exactly an ASSIGN_COURIER names one.
ALTER TABLE ordering.bulk_operations
    ADD CONSTRAINT ck_bulk_operation_courier
        CHECK ((action_type = 'ASSIGN_COURIER') = (courier_id IS NOT NULL)),
    ADD CONSTRAINT ck_bulk_operation_assign_reason
        CHECK (action_type <> 'ASSIGN_COURIER' OR reason_code IS NOT NULL),
    ADD CONSTRAINT fk_bulk_operation_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id);

ALTER TABLE ordering.bulk_operation_items
    ADD COLUMN plan_id uuid,
    ADD COLUMN shipment_id uuid,
    ADD COLUMN item_note varchar(160);

COMMENT ON COLUMN ordering.bulk_operations.courier_id IS
    'ADR 0172. The one courier an ASSIGN_COURIER request names; null for ADVANCE and CANCEL.';
COMMENT ON COLUMN ordering.bulk_operations.reason_code IS
    'ADR 0172. The assignment''s reason code ([A-Z0-9_]{1,48}), a code and never prose.';
COMMENT ON COLUMN ordering.bulk_operation_items.plan_id IS
    'ADR 0172. The delivery plan an applied assignment landed on. A reference with no foreign key: fulfillment rows are cancelled, never deleted.';
COMMENT ON COLUMN ordering.bulk_operation_items.shipment_id IS
    'ADR 0172. The shipment an applied assignment created. A reference with no foreign key.';
COMMENT ON COLUMN ordering.bulk_operation_items.item_note IS
    'ADR 0172. A non-fatal annotation on an applied item (OVERRODE_DISPATCH_RULE, OUTSIDE_DISTANCE_BAND, NO_OPEN_SHIFT) or the named refusals of a COURIER_NOT_ELIGIBLE one. Codes only, comma separated; never free text.';

-- The existing grants (SELECT, INSERT, UPDATE on both tables, V0193) cover the
-- new columns; nothing here adds a table.
