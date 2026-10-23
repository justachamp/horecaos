-- Courier policy 3.9, ADR 0042: the pickup and the handover are SOFT by default and record
-- GEO_UNVERIFIED instead of refusing; a location can harden them (ADR 0030).
--
-- A soft gate that records nothing is a gate that was switched off. This table is the record:
-- one row per check a pickup (PICKED_UP) or a handover (DELIVERED) evaluated, with what it came to
-- and the mode the location was in when it was taken. The accrual reads it when the order
-- completes (a delivery whose pickup and handover were both measured and passed is verified;
-- one with no row, such as an order an operator closed by hand, is not), which is what makes the
-- GEO_UNVERIFIED_RATE basis of an adjustment rule (ADR 0108) read a fact instead of a constant.
--
-- The courier module owns it, like every fulfillment.courier_* table, and keys it by the shipment
-- instead of adding columns to fulfillment.shipments: the shipment is fulfillment's aggregate, and
-- what the courier policy made of a step is courier's.
--
-- SKIPPED is a check that was not evaluated because the location does not ask for it (the GPS
-- switch is off). It is kept, rather than left as a missing row, so that «not measured» is a
-- statement and not an absence. PHOTO is in the vocabulary because the courier client's contract
-- names it; no policy asks for a photo yet, so no row carries it.
--
-- No coordinate is stored here or anywhere on the path (ADR 0029, ADR 0045): reason_code is the
-- name of a refusal, such as TOO_FAR_FROM_DROPOFF, and nothing about where the courier stood.

CREATE TABLE fulfillment.courier_step_checks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    step varchar(16) NOT NULL,
    kind varchar(16) NOT NULL,
    outcome varchar(16) NOT NULL,
    reason_code varchar(48),
    gate_mode varchar(8) NOT NULL,
    policy_id uuid NOT NULL,
    policy_version integer NOT NULL,
    recorded_at timestamptz NOT NULL,

    CONSTRAINT fk_step_check_shipment FOREIGN KEY (shipment_id, tenant_id)
        REFERENCES fulfillment.shipments (id, tenant_id),
    CONSTRAINT fk_step_check_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT uq_step_check UNIQUE (tenant_id, shipment_id, step, kind),
    CONSTRAINT ck_step_check_step CHECK (step IN ('PICKED_UP', 'DELIVERED')),
    CONSTRAINT ck_step_check_kind CHECK (kind IN ('GEO', 'PHOTO', 'PAYMENT')),
    CONSTRAINT ck_step_check_outcome CHECK (outcome IN ('PASSED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_step_check_mode CHECK (gate_mode IN ('SOFT', 'HARD')),
    CONSTRAINT ck_step_check_reason CHECK (
        (outcome = 'FAILED') = (reason_code IS NOT NULL))
);

CREATE INDEX ix_step_check_shipment ON fulfillment.courier_step_checks (tenant_id, shipment_id);

COMMENT ON TABLE fulfillment.courier_step_checks IS
    'What each check of a pickup and a handover came to, written in the transaction that moved the shipment. A step with no row here was not taken in the courier app (an operator closed the order), so it is unverified by construction.';
COMMENT ON COLUMN fulfillment.courier_step_checks.outcome IS
    'PASSED, FAILED (measured and refused, or could not be measured), or SKIPPED (the location does not ask for this check). A FAILED row exists only in SOFT mode: in HARD mode the step is refused and nothing is written.';
COMMENT ON COLUMN fulfillment.courier_step_checks.reason_code IS
    'The GateRefusal that failed the check: a name, never a coordinate, an accuracy or a distance.';
COMMENT ON COLUMN fulfillment.courier_step_checks.gate_mode IS
    'The courier.compensation pickupHandoverGate in force at the shipment''s location when the step was taken; the policy id and version beside it say which document.';

GRANT SELECT, INSERT ON fulfillment.courier_step_checks TO horecaos_application;
