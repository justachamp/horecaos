-- ADR 0042, ADR 0108: courier types stop being create-only, and gain the two
-- attributes IA 3.4 names that had no column anywhere.
--
-- ---------------------------------------------------------------------------
-- Why this is an ALTER on V0040's table rather than a new one
-- ---------------------------------------------------------------------------
--
-- fulfillment.courier_types already carries the vehicle class and the
-- dispatch ceilings ADR 0042 gives it. The gap the operations gap map found
-- was operational, not structural: no UPDATE and no archive path existed at
-- any layer even though `status` already has ARCHIVED, so a mistyped code or
-- a wrong offer TTL was permanent. This migration adds nothing to fix that --
-- the UPDATE grant already exists (V0040), and the fix is Java, in this
-- wave's CourierTypeService and OperationsCourierController.
--
-- ---------------------------------------------------------------------------
-- starting_minute_offset and work_mode
-- ---------------------------------------------------------------------------
--
-- ADR 0108 records the decision in full, including the tension this closes:
-- docs/operations-spec/couriers.md 9 warned against reproducing Delever's
-- "Начальная минута" / "Режим работы" verbatim, because neither is defined
-- anywhere in Delever's own documentation and copying an undefined field
-- imports the ambiguity rather than the capability. The operations gap map
-- (3.4a) asks for the two fields anyway, with its own gloss on the first:
-- "the minute from which a type begins earning". ADR 0108 gives both a
-- narrow, closed definition instead of Delever's open one, so the field
-- exists without the ambiguity the spec was worried about.
--
-- Both columns are captured now and are informational only: the platform
-- owner's checklist scoped V0259-V0261 to "the columns", not to a
-- CourierAccrualService or CourierDispatchGate change, and ADR 0108 records
-- the wiring as an explicit open input rather than pretending a value nobody
-- reads is enforced. That is the same "modelled, not yet built" shape ADR
-- 0042 already uses for REGISTRY_LOOKUP and courier_roster_entries -- named
-- honestly rather than half-wired.
ALTER TABLE fulfillment.courier_types
    ADD COLUMN starting_minute_offset smallint NOT NULL DEFAULT 0,
    ADD COLUMN work_mode varchar(16) NOT NULL DEFAULT 'SHIFT';

ALTER TABLE fulfillment.courier_types
    ADD CONSTRAINT ck_courier_type_starting_minute
        CHECK (starting_minute_offset BETWEEN 0 AND 1440),
    ADD CONSTRAINT ck_courier_type_work_mode
        CHECK (work_mode IN ('SHIFT', 'ON_DEMAND'));

COMMENT ON COLUMN fulfillment.courier_types.starting_minute_offset IS
    'ADR 0108. Minutes after a shift opens before a courier of this type begins earning its PER_SHIFT_FIXED component. Captured and rendered; not yet read by AccrualCalculator or CourierDispatchGate -- see ADR 0108''s open inputs.';
COMMENT ON COLUMN fulfillment.courier_types.work_mode IS
    'ADR 0108. SHIFT (default): the type follows the ordinary shift model this ADR 0042 module already implements. ON_DEMAND: dispatched without an open shift, for a vehicle class the tenant brings in ad hoc (e.g. a hired van for one large catering order). Captured and rendered; CourierDispatchGate still reads only the location''s courier.shift.enforcement policy, not this column -- see ADR 0108''s open inputs.';
