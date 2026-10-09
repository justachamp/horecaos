-- ADR 0170: courier pay for grouped orders.
--
-- A run is the set of in-house shipments sharing a run_key. The pay basis is decided when the OFFER is
-- made, shown to the courier before they accept, and never lowered afterwards: "the quote is the pay".
-- So it is stamped on the assignment attempt at insert, then copied (not re-derived) onto the earning
-- the delivery produces, where the existing calculator is handed a shorter distance and nothing else.
--
--   assignment_attempts.pay_basis             DIRECT (the order's own distance, as today) or RUN_INCREMENT
--   assignment_attempts.run_merge_distance_m  the straight-line metres from this drop-off to the nearest
--                                              drop-off of the courier's un-picked-up shipment; the ONLY
--                                              number the feed leaves behind (no coordinate is stored)
--   assignment_attempts.run_lead_shipment_id  which shipment that nearest one was, so acceptance joins the
--                                              right run without decrypting an address a second time. An
--                                              id, not a place
--   shipments.run_key                         the run; assigned at acceptance, to the new shipment and to
--                                              its lead when the lead had none
--   shipments.run_merge_distance_m            copied from the attempt, on the follower only
--   courier_assignment_earnings.pay_basis / run_key / direct_distance_meters
--                                              distance_meters keeps meaning "the distance paid"; the
--                                              order's own distance sits beside it, so a statement line
--                                              shows both and the reason
--
-- Everything defaults to DIRECT: a manually assigned order, a partner shipment, a tenant on PER_ORDER and
-- an order offered while the bias found nothing are all unchanged. Every row already carries tenant_id and
-- the grants of the three tables cover the new columns; no table is added.

ALTER TABLE fulfillment.assignment_attempts
    ADD COLUMN pay_basis varchar(16) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN run_merge_distance_m integer,
    ADD COLUMN run_lead_shipment_id uuid,
    ADD CONSTRAINT ck_attempt_pay_basis CHECK (pay_basis IN ('DIRECT', 'RUN_INCREMENT')),
    ADD CONSTRAINT ck_attempt_run_stamp CHECK (
        (pay_basis = 'RUN_INCREMENT')
            = (run_merge_distance_m IS NOT NULL AND run_lead_shipment_id IS NOT NULL)),
    ADD CONSTRAINT ck_attempt_run_distance CHECK (run_merge_distance_m IS NULL OR run_merge_distance_m >= 0),
    ADD CONSTRAINT ck_attempt_run_internal CHECK (pay_basis = 'DIRECT' OR source_type = 'INTERNAL');

-- "It only ever improves for the courier after the offer" and "the quote is the pay": once an offer is
-- written its pay stamp is part of what the courier accepted. A trigger rather than a convention, because
-- the code that would lower a stamp is exactly the code a later change forgets is forbidden.
CREATE FUNCTION fulfillment.forbid_attempt_pay_stamp_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.pay_basis IS DISTINCT FROM OLD.pay_basis
       OR NEW.run_merge_distance_m IS DISTINCT FROM OLD.run_merge_distance_m
       OR NEW.run_lead_shipment_id IS DISTINCT FROM OLD.run_lead_shipment_id THEN
        RAISE EXCEPTION 'The pay stamp of assignment attempt % is fixed when the offer is made (ADR 0170)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_attempt_pay_stamp_fixed
    BEFORE UPDATE ON fulfillment.assignment_attempts
    FOR EACH ROW EXECUTE FUNCTION fulfillment.forbid_attempt_pay_stamp_rewrite();

ALTER TABLE fulfillment.shipments
    ADD COLUMN run_key uuid,
    ADD COLUMN run_merge_distance_m integer,
    ADD CONSTRAINT ck_shipment_run_distance CHECK (
        run_merge_distance_m IS NULL OR (run_key IS NOT NULL AND run_merge_distance_m >= 0));

CREATE INDEX ix_shipment_run ON fulfillment.shipments (tenant_id, run_key) WHERE run_key IS NOT NULL;

ALTER TABLE fulfillment.courier_assignment_earnings
    ADD COLUMN pay_basis varchar(16) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN run_key uuid,
    ADD COLUMN direct_distance_meters integer,
    ADD CONSTRAINT ck_earning_pay_basis CHECK (pay_basis IN ('DIRECT', 'RUN_INCREMENT')),
    ADD CONSTRAINT ck_earning_direct_distance CHECK (
        (pay_basis = 'RUN_INCREMENT') = (direct_distance_meters IS NOT NULL)),
    ADD CONSTRAINT ck_earning_run_key CHECK (pay_basis = 'DIRECT' OR run_key IS NOT NULL),
    ADD CONSTRAINT ck_earning_distance_paid CHECK (
        direct_distance_meters IS NULL OR distance_meters <= direct_distance_meters);

COMMENT ON COLUMN fulfillment.assignment_attempts.pay_basis IS
    'ADR 0170. DIRECT pays the order''s own distance; RUN_INCREMENT pays the shorter of that and run_merge_distance_m. Stamped when the offer is made and never lowered (trg_attempt_pay_stamp_fixed).';
COMMENT ON COLUMN fulfillment.assignment_attempts.run_merge_distance_m IS
    'ADR 0170. Straight-line metres to the nearest drop-off of the courier''s un-picked-up shipment from the same branch. The only number the grouping feed leaves behind: no coordinate is stored (ADR 0029).';
COMMENT ON COLUMN fulfillment.assignment_attempts.run_lead_shipment_id IS
    'ADR 0170. The shipment run_merge_distance_m was measured to. A reference with no foreign key: shipments are cancelled and never deleted, and an id is not a place.';
COMMENT ON COLUMN fulfillment.shipments.run_key IS
    'ADR 0170. The run this in-house shipment belongs to: assigned at acceptance, to the new shipment and to its lead when the lead had none. There is no Trip, no route order and no stored coordinates.';
COMMENT ON COLUMN fulfillment.courier_assignment_earnings.direct_distance_meters IS
    'ADR 0170. The order''s own distance, beside distance_meters (the distance paid), so a statement line shows both when the basis is RUN_INCREMENT.';
