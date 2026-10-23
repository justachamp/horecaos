-- ADR 0166 Decision 8 and ADR 0039: «an amendment that changed a filed total opens a request automatically,
-- unapproved until a person acts». ordering.order_revisions.fiscal_correction_required has been written on
-- every financial amendment since V0395 and read by nothing; this is the other half.
--
-- WHICH REVISION A REQUEST COVERS. A request opened because an order was amended records the order revision
-- that caused it (source_revision). A revision is covered once: the reader skips a document that already has a
-- request, of any status, for that revision or a later one, so a request the tenant's agent declined is not
-- reopened for the amendment it already answered, while a later amendment is a new fact and asks again. A
-- request a person raised carries no source revision. Revision 1 is the checkout snapshot and never asks.
--
-- NO NEW GRANT. The column is on a table the application role already holds SELECT, INSERT and UPDATE on
-- (V0551), and the index is on another module's table, read by the same role.

ALTER TABLE fiscal.fiscal_correction_requests
    ADD COLUMN source_revision integer;

ALTER TABLE fiscal.fiscal_correction_requests
    ADD CONSTRAINT ck_fiscal_correction_source_revision CHECK (
        source_revision IS NULL OR source_revision >= 2);

-- One request per document per revision, at the database as well as in the reader.
CREATE UNIQUE INDEX uq_fiscal_correction_per_revision
    ON fiscal.fiscal_correction_requests (tenant_id, document_id, source_revision)
    WHERE source_revision IS NOT NULL;

COMMENT ON COLUMN fiscal.fiscal_correction_requests.source_revision IS
    'ADR 0166 Decision 8. The order revision whose amendment opened this request on its own; NULL for a request a person raised. A document with a request for this revision or a later one is not asked about again.';

-- The reader polls flagged revisions inside a lookback window; the flag is false on most rows.
CREATE INDEX ix_order_revisions_fiscal_correction
    ON ordering.order_revisions (created_at)
    WHERE fiscal_correction_required;

COMMENT ON COLUMN ordering.order_revisions.fiscal_correction_required IS
    'ADR 0039, ADR 0166 Decision 8. Set true when this revision''s own command declares a fiscal consequence (a correction document once a receipt exists). Read since V0686 by the fiscal module''s amendment reader, which opens a correction request for a receipt that had already left before the revision; it never blocks the amendment.';
