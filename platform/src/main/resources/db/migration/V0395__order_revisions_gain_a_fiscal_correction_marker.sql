-- ADR 0039's consequence matrix marks a fiscal correction "if a receipt
-- exists" for every financial amendment command whose total can change.
-- Fiscalization is not live (gap map row X.2, ADR 0038 blocked) — no order in
-- this build carries a receipt, so no correction document can be issued today.
-- ADR 0039 §3.11's own rule stands regardless: an amendment must never silently
-- skip the consequence it declares just because the module behind it has not
-- shipped. This column is that record, swept by the fiscalization wave once it
-- exists rather than reconstructed by diffing revisions after the fact.
--
-- Written true for every revision produced by a command ADR 0039's matrix marks
-- "Correction if a receipt exists" / "Correction only if the total changed" —
-- never for CHANGE_FULFILLMENT_TIME or a non-financial command, whose own
-- fiscal column reads "None".

ALTER TABLE ordering.order_revisions
    ADD COLUMN fiscal_correction_required boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN ordering.order_revisions.fiscal_correction_required IS
    'ADR 0039. Set true when this revision''s own command declares a fiscal consequence (a correction document once a receipt exists) that fiscalization cannot carry out yet (ADR 0038 blocked, gap map row X.2). Never blocks the amendment; swept once fiscalization ships.';
