-- ADR 0169 (Accepted 2026-10-07), gap-map row 1.2c: the customer's recorded agreement to a removal.
--
-- ADR 0039 requires the attestation for an increase and V0029 enforces it in the database on the applied row
-- (ck_amendment_increase_confirmed). ADR 0169 decides a removal needs it too, always: the customer ordered the
-- item, and an unrecorded unilateral removal is the complaint the attestation answers, whoever asked for it.
-- A removal does not raise the total, so the increase rule cannot see it; the amendment now says, on its own
-- row, that it takes stock off the order, and the same guard is stated for that.
--
-- Stated in the database and in the service on purpose, as the increase rule is: a guard that lives in one
-- service is a guard the next call path can walk around.

ALTER TABLE ordering.order_amendments
    ADD COLUMN removes_stock boolean NOT NULL DEFAULT false;

ALTER TABLE ordering.order_amendments
    ADD CONSTRAINT ck_amendment_removal_confirmed CHECK (
        status <> 'APPLIED' OR NOT removes_stock OR confirmation_attested_at IS NOT NULL);

COMMENT ON COLUMN ordering.order_amendments.removes_stock IS
    'ADR 0169. True when applying this amendment takes a line off the order or lowers a quantity, which gives stock back or writes it off. Such an amendment cannot be applied without the customer''s recorded agreement.';

-- ADR 0169, decision 11: the cancellation's stock consequence is carried out by the inventory process after the
-- outcome is written, and records the ledger movement it wrote on the outcome. ordering.order_outcomes is
-- append-only for the application role (V0029: SELECT, INSERT); this one column is the exception, because it is
-- null when the outcome is written and means "the consequence has been carried out" once it is set. A column
-- grant, so nothing else on the row can be edited.
GRANT UPDATE (inventory_movement_id) ON ordering.order_outcomes TO horecaos_application;
