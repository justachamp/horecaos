-- One index, for the one question dispatch asks on every sourcing tick.
--
-- ADR 0042's dispatch half now has an implementation: `InternalFleetAdapter`
-- answers `fulfillment.api.InternalFleetPort` from the courier module's own
-- rows, and one of the five things a `FleetCandidate` carries is
-- `deliveriesThisShift` -- the fairness tiebreaker ADR 0014's ranking applies
-- after load and distance, so that the courier who has already done four runs
-- this evening is not handed the fifth ahead of the one who has done none.
--
-- That figure is a COUNT over `courier_assignment_earnings` keyed on
-- `shift_id`, and V0040 indexed that table by settlement period and by
-- shipment but never by shift. The count therefore runs as a sequential scan
-- over every earning the tenant has ever accrued, once per candidate, on a
-- path that runs every few seconds per delivery plan. That is the shape of
-- query that is fine in a test fixture holding nine rows and is a production
-- incident at nine hundred thousand.
--
-- Partial on `shift_id IS NOT NULL` because it legitimately is: V0040 makes the
-- column nullable, an accrual recorded outside a shift (ADR 0042 permits it
-- where `courier.shift.enforcement` is OFF or ADVISORY) carries none, and those
-- rows can never satisfy this predicate.
--
-- No GRANT below. This adds no table and no column; `qoida_application`
-- already holds SELECT on `fulfillment.courier_assignment_earnings` from V0040
-- line 1016, and an index is not a grantable object.

CREATE INDEX ix_earning_shift
    ON fulfillment.courier_assignment_earnings (tenant_id, shift_id)
    WHERE shift_id IS NOT NULL;

COMMENT ON INDEX fulfillment.ix_earning_shift IS
    'ADR 0042 dispatch. "How many deliveries has this courier already done on this shift" -- the fairness tiebreaker in ADR 0014''s candidate ranking, asked once per candidate per sourcing tick.';
