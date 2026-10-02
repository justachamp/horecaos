-- ADR 0137 (Accepted 2026-10-01): catchweight pricing and its reconciliation at
-- handover.
--
-- A catchweight variant's price is per weight quantum, and its quoted total is
-- provisional: the customer is shown a number computed against the menu's nominal
-- weight, and the amount actually charged is corrected once the real weight is
-- captured at pick/handover -- the same "captured late, reconciled before the
-- receipt is final" shape ADR 0038 accepted for marking codes.
--
-- Three things are needed, and they are all here:
--
--   1. pricing.quote_lines remembers that a line was priced by weight, at what
--      quantum, against what nominal weight, at what price per quantum, and (once
--      reconciled) at what captured weight -- so a quote is explainable and an
--      order can snapshot it.
--
--   2. ordering.order_lines carries the same facts as a snapshot, plus
--      actual_weight_grams, the one fact the reconciliation writes. The price per
--      quantum is snapshotted so that a menu repriced between checkout and
--      weighing cannot change what the customer agreed to per 100 g.
--
--   3. A reconciliation is an order revision. ordering.order_revisions already holds
--      the complete total of every point in an order's life; a weight captured at
--      handover that moves the total appends a revision (source CATCHWEIGHT)
--      carrying the new total and the signed delta, so "what was this order worth
--      before and after it was weighed" has an answer. No amendment row stands
--      behind it, because it is not an amendment: nobody proposed it, the customer
--      is not asked to confirm it (the checkout total of a catchweight item is
--      provisional by its own definition), and it needs no second signature.
--
-- actual_weight_grams is the weighed total of the WHOLE line (all its units
-- together), not of one unit: a line of three pieces is weighed once.

-- ----------------------------------------------------------------- quote lines

ALTER TABLE pricing.quote_lines
    ADD COLUMN catchweight_quantum_grams integer,
    ADD COLUMN catchweight_nominal_grams integer,
    ADD COLUMN catchweight_price_per_quantum_minor bigint,
    ADD COLUMN actual_weight_grams integer;

ALTER TABLE pricing.quote_lines
    -- The three catchweight facts travel together or not at all, and a captured
    -- weight needs them to mean anything.
    ADD CONSTRAINT ck_quote_line_catchweight_agrees CHECK (
        (catchweight_quantum_grams IS NULL) = (catchweight_nominal_grams IS NULL)
        AND (catchweight_quantum_grams IS NULL) = (catchweight_price_per_quantum_minor IS NULL)
        AND (actual_weight_grams IS NULL OR catchweight_quantum_grams IS NOT NULL)),
    ADD CONSTRAINT ck_quote_line_catchweight_positive CHECK (
        (catchweight_quantum_grams IS NULL OR catchweight_quantum_grams > 0)
        AND (catchweight_nominal_grams IS NULL OR catchweight_nominal_grams > 0)
        AND (catchweight_price_per_quantum_minor IS NULL OR catchweight_price_per_quantum_minor >= 0)
        AND (actual_weight_grams IS NULL OR actual_weight_grams > 0));

-- ----------------------------------------------------------------- order lines

ALTER TABLE ordering.order_lines
    ADD COLUMN catchweight_quantum_grams integer,
    ADD COLUMN catchweight_nominal_grams integer,
    ADD COLUMN catchweight_price_per_quantum_minor bigint,
    ADD COLUMN actual_weight_grams integer;

ALTER TABLE ordering.order_lines
    ADD CONSTRAINT ck_order_line_catchweight_agrees CHECK (
        (catchweight_quantum_grams IS NULL) = (catchweight_nominal_grams IS NULL)
        AND (catchweight_quantum_grams IS NULL) = (catchweight_price_per_quantum_minor IS NULL)
        AND (actual_weight_grams IS NULL OR catchweight_quantum_grams IS NOT NULL)),
    ADD CONSTRAINT ck_order_line_catchweight_positive CHECK (
        (catchweight_quantum_grams IS NULL OR catchweight_quantum_grams > 0)
        AND (catchweight_nominal_grams IS NULL OR catchweight_nominal_grams > 0)
        AND (catchweight_price_per_quantum_minor IS NULL OR catchweight_price_per_quantum_minor >= 0)
        AND (actual_weight_grams IS NULL OR actual_weight_grams > 0));

-- Orders still-to-be-weighed, found by the completion blocker and the kitchen's
-- "weigh these" list without scanning every line of every order.
CREATE INDEX ix_order_lines_unweighed ON ordering.order_lines (tenant_id, order_id)
    WHERE catchweight_quantum_grams IS NOT NULL
      AND actual_weight_grams IS NULL
      AND revision_to IS NULL;

-- The reconciliation corrects the line's amounts from a re-price that includes the
-- captured weight, so it needs UPDATE on exactly those columns and no others.
--
-- V0022 argued the other way for this table -- "an UPDATE grant is all it takes for
-- a well-meant support fix to become an unrecorded rewrite of financial history" --
-- and V0394 opened one column (revision_to) for the same reason this opens five: a
-- decision (ADR 0137: the line's final_amount_minor "is corrected against" the
-- weight) needs a write the table could not otherwise take. The grant is column
-- scoped, so a name, a quantity, a unit price or a note still cannot be touched.
-- The write is not unrecorded: every reconciliation appends an order revision with
-- the complete before/after total and records an audit fact naming each line's
-- amounts before and after.
GRANT UPDATE (actual_weight_grams, base_amount_minor, final_amount_minor, tax_amount_minor)
    ON ordering.order_lines TO horecaos_application;

COMMENT ON COLUMN ordering.order_lines.actual_weight_grams IS
    'ADR 0137. The weighed total of the whole line in grams, captured at pick/handover; null while the line''s amounts are still provisional against catchweight_nominal_grams. Written once by the catchweight reconciliation, which also corrects base/final/tax_amount_minor and appends an order revision.';
COMMENT ON COLUMN ordering.order_lines.catchweight_price_per_quantum_minor IS
    'ADR 0137. The price row''s amount at checkout: minor units per catchweight_quantum_grams. Snapshotted so a repricing of the menu between checkout and weighing cannot change what the customer agreed to per quantum.';

-- ----------------------------------------------------------------- revisions

-- A reconciliation appends a revision without an amendment standing behind it.
-- The two CHECKs that pinned the pair (source = 'AMENDMENT') <=> (amendment_id is
-- not null) keep their meaning for the sources they already named; the new source
-- is the third case, with no amendment.
ALTER TABLE ordering.order_revisions
    DROP CONSTRAINT ck_order_revision_source;
ALTER TABLE ordering.order_revisions
    ADD CONSTRAINT ck_order_revision_source CHECK (source IN ('CHECKOUT', 'AMENDMENT', 'CATCHWEIGHT'));

COMMENT ON TABLE ordering.order_revisions IS
    'ADR 0039 append-only order revisions. Revision 1 is the ADR 0019 checkout snapshot and is byte-identical for ever; each applied amendment appends one, and (ADR 0137) so does a catchweight reconciliation, with source CATCHWEIGHT and no amendment.';
