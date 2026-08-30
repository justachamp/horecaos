-- ADR 0038: the fiscal document lifecycle, and the sweeper that turns silence
-- into work.
--
-- V0027 built the ADR 0013 partner seam as `payments.fiscal_documents` and said,
-- in its own comment, what was to become of it: "ADR 0038 owns fiscalization and
-- will eventually own a richer `fiscal` schema ... When 0038's schema lands these
-- rows move to it; the column shape below is deliberately the one it specifies so
-- the move is a copy rather than a redesign." This is that move, and the promise
-- held: not one column is redesigned below, and no row is rewritten.
--
-- V0028 built the other half of ADR 0038 — the classification table, the
-- delivery-fee node, the MXIK reference, the per-entity binding dimension on
-- `payments.merchant_bindings`. What neither migration built is the part of the
-- ADR that is about time rather than about shape: a document that was submitted
-- and never answered.
--
-- ---------------------------------------------------------------------------
-- Why the table moves, and why a view stays behind
-- ---------------------------------------------------------------------------
--
-- The schema name is the ownership statement. A reader looking for the fiscal
-- lifecycle should find it under `fiscal`, because that is where ADR 0038 puts
-- it and because the obligation is the order's rather than the payment's: a cash
-- order has a receipt obligation and no payment transaction to hang it on, which
-- is the exact argument the ADR uses to move the module.
--
-- But `payments` is a built module with live code against this table — the Payme
-- `SetFiscalData` handler, the intent service that records the cash decision, the
-- order payment read — and rewriting six of its files from this migration's side
-- would be a large change to a module this decision does not own. So the table
-- moves and an auto-updatable view keeps its old name. The view is not a
-- permanent shim: it exists so the move is a schema change rather than a
-- cross-module refactor, and it is deliberately restricted to V0027's exact
-- column list, so nothing on the payments side can start depending on a lifecycle
-- column it does not maintain. Retiring it is a rename in six SQL strings, on
-- whatever day the payments owner has the file open.
--
-- ---------------------------------------------------------------------------
-- What is deliberately not added
-- ---------------------------------------------------------------------------
--
-- The ADR sketches `fiscal_document_lines`, `fiscal_unit_marks`,
-- `fiscal_terminals`, `provider_receipt_type` and a stored `responsibility`.
-- None of them is created here, and the reason is the one V0031 wrote down: a
-- table whose producer does not exist is not a head start, it is an empty table
-- that reads to the next author as though the projection is broken.
--
--   * Lines are not stored as rows on purpose. V0027 argued it and the argument
--     still holds: what is worth keeping is the exact `Items` array or `detail`
--     object that was *sent*, behind an ADR 0029 protected reference, because a
--     reconstruction is not evidence. A lines table earns its place when
--     TERMINAL issuance builds a receipt Qoida composes rather than relays.
--   * Unit marks and terminals are the ADR's rollout stages 5 and 6 and have no
--     writer in this build.
--   * `provider_receipt_type` would duplicate `document_type`: Payme's PERFORM
--     lands on the SALE row and its CANCEL on the linked REFUND row, which is
--     already how the callback handler routes them. Two columns encoding one
--     fact is one UPDATE away from disagreeing.
--   * `responsibility` is derivable — a document with no provider is the
--     restaurant's own equipment's to discharge — and a stored copy would have no
--     maintainer on the rows the payments module inserts.

CREATE SCHEMA fiscal;

COMMENT ON SCHEMA fiscal IS
    'ADR 0038. The order''s fiscal obligation: whether a receipt exists, who owes it, and what is blocking it. Distinct from payments because a cash order has an obligation and no payment transaction to attach it to.';

-- ---------------------------------------------------------------------------
-- 1. The move
-- ---------------------------------------------------------------------------
--
-- Indexes, constraints and the self-referencing foreign key travel with the
-- table. Nothing is recreated, so nothing can be recreated slightly differently.
ALTER TABLE payments.fiscal_documents SET SCHEMA fiscal;

COMMENT ON TABLE fiscal.fiscal_documents IS
    'ADR 0038. One row per settlement leg of an order''s fiscal obligation, never one per order: a Payme PERFORM and its CANCEL are two receipts by the provider''s own statement, and an ADR 0046 split tender settles on two paths. Moved here from payments by V0039, unchanged.';

-- ---------------------------------------------------------------------------
-- 2. BLOCKED
-- ---------------------------------------------------------------------------
--
-- The state this migration exists for. Payme's `SetFiscalData` is inbound and
-- optional to implement, and `receipts.set_fiscal_data` runs the other way — it
-- is for a merchant who fiscalized on their own equipment — so there is no
-- merchant-initiated retry on the reporting path at all. A callback that never
-- arrives therefore leaves "not yet reported" and "no receipt" as the same row in
-- the same status, and the tenant learns the difference from an inspector.
--
-- BLOCKED is not an error status. It is the statement that this document needs a
-- person, carried in the same never-null `reason_code` every other status uses.
-- A second `blocked_reason_code` column was considered and rejected: V0027
-- deliberately made one reason column mandatory in every status precisely so
-- that "why is this document like this" has exactly one answer, and a second
-- nullable reason beside it is how the two come to disagree.
ALTER TABLE fiscal.fiscal_documents
    DROP CONSTRAINT ck_fiscal_document_status;

ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_status CHECK (status IN (
        'NOT_APPLICABLE', 'PENDING', 'SUBMITTED', 'ISSUED', 'FAILED', 'BLOCKED'));

ALTER TABLE fiscal.fiscal_documents
    -- When the block was applied, so a worklist can be ordered by how long
    -- somebody has been waiting rather than by when the order was placed. It is
    -- not cleared when the block clears: a document that was blocked for two
    -- hours on a Friday and resolved itself is a fact about this provider worth
    -- being able to count, and clearing the column would erase the evidence that
    -- the deadline is set wrongly.
    ADD COLUMN blocked_at timestamptz,

    -- When the provider's report stops being late and starts being missing.
    -- Nullable, and read through COALESCE by the sweeper, because the rows the
    -- payments module inserts through the compatibility view know nothing about
    -- deadlines. A NOT NULL DEFAULT would be worse than a null here: it would
    -- silently give a cash document a deadline it can never meet.
    ADD COLUMN reporting_deadline_at timestamptz,

    -- ADR 0046. Null for a single-tender order, and the third column of the
    -- uniqueness rule below.
    ADD COLUMN tender_id uuid,

    -- How many times a person has asked for this document again. Manual retry
    -- reuses the document rather than creating a second one — two sale receipts
    -- for one payment is a discrepancy with the tax authority that cannot be
    -- deleted, only corrected — so the count lives on the row it retries.
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;

-- A blocked document always says when it was blocked. Stated as an implication
-- rather than as an equivalence on purpose: the converse is false by design,
-- because `blocked_at` survives the block being cleared.
ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_blocked_at CHECK (
        status <> 'BLOCKED' OR blocked_at IS NOT NULL);

ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_attempts CHECK (attempt_count >= 0);

-- And the converse of V0027's not-applicable rule, extended: a document that
-- records a decision rather than a gap is never blocked. Cash is NOT_APPLICABLE
-- with a reason, and the receipt it still owes comes from the restaurant's own
-- fiscal-capable equipment under the ADR's TERMINAL responsibility — not from a
-- sweeper chasing a provider that was never asked.
ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_not_applicable_is_not_blocked CHECK (
        status <> 'NOT_APPLICABLE' OR blocked_at IS NULL);

COMMENT ON COLUMN fiscal.fiscal_documents.status IS
    'NOT_APPLICABLE is a decision with a reason, never a null. BLOCKED is work with a reason, never an error. A null would mean unknown, and the point of both is that they are known.';
COMMENT ON COLUMN fiscal.fiscal_documents.blocked_at IS
    'When the block was applied. Kept after the block clears, because how long a provider left a document unanswered is the evidence that decides whether the deadline is set correctly.';
COMMENT ON COLUMN fiscal.fiscal_documents.reporting_deadline_at IS
    'ADR 0038. When silence becomes a missing receipt. Null on a row inserted by the payment seam, which does not know the policy; the sweeper then derives it from submitted_at and the fiscal.reporting_deadline policy.';
COMMENT ON COLUMN fiscal.fiscal_documents.tender_id IS
    'ADR 0046 split tender. Null for a single-tender order, and NULLS NOT DISTINCT in the uniqueness rule so that null still means one leg rather than unlimited legs.';
COMMENT ON COLUMN fiscal.fiscal_documents.attempt_count IS
    'How many times a person has asked for this document again. Retry reuses the document: two sale receipts for one payment can only be corrected, never deleted.';

-- ---------------------------------------------------------------------------
-- 3. Several documents per order, and exactly one sale per leg
-- ---------------------------------------------------------------------------
--
-- There is still no unique index on `order_id`, and there never will be. What is
-- enforced instead is the statement ADR 0038 actually makes: one SALE document
-- per settled tender. A REFUND or a CORRECTION is an additional row linked by
-- `corrects_document_id`, which is where Payme's CANCEL payload lands.
--
-- NULLS NOT DISTINCT is the whole of why this index is correct. A single-tender
-- order carries `tender_id IS NULL`, and under the default NULLS DISTINCT every
-- such row would be unique against every other — the constraint would hold for
-- split-tender orders, which are the rare case, and silently permit unlimited
-- duplicate sale documents on the common one. That is the wrong way round.
CREATE UNIQUE INDEX uq_fiscal_document_sale_per_tender
    ON fiscal.fiscal_documents (tenant_id, order_id, tender_id) NULLS NOT DISTINCT
    WHERE document_type = 'SALE';

COMMENT ON INDEX fiscal.uq_fiscal_document_sale_per_tender IS
    'ADR 0038. One SALE per settled tender, and deliberately not one document per order: reading "exactly one fiscal document" as a constraint on order_id writes a cancellation payload over a sale''s evidence.';

-- ---------------------------------------------------------------------------
-- 4. What the sweeper reads
-- ---------------------------------------------------------------------------
--
-- No tenant predicate, and that is not an oversight. Every tenant-owned read in
-- this platform carries one because it is made on behalf of somebody who belongs
-- to a tenant; this sweep is a platform job that belongs to no tenant and must
-- not be able to miss one because nobody thought to enumerate it. The tenant
-- appears in the row, in the policy resolution, and in every read the operator
-- console makes over the result.
CREATE INDEX ix_fiscal_documents_reporting_due
    ON fiscal.fiscal_documents (submitted_at)
    WHERE status = 'SUBMITTED';

-- The worklist, ordered by how long it has been waiting. Blocked work that is
-- only reachable by a full scan is blocked work nobody looks at, which is the
-- state this whole design exists to leave behind.
CREATE INDEX ix_fiscal_documents_blocked
    ON fiscal.fiscal_documents (tenant_id, blocked_at)
    WHERE status = 'BLOCKED';

-- ---------------------------------------------------------------------------
-- 5. The compatibility view
-- ---------------------------------------------------------------------------
--
-- Auto-updatable: one base relation, no DISTINCT, no grouping, no set operation,
-- and every select-list entry a plain column reference. PostgreSQL therefore
-- rewrites INSERT, UPDATE and DELETE straight through, and a base column absent
-- from the view takes its default — which is why `attempt_count` could be
-- NOT NULL DEFAULT 0 and the lifecycle columns had to be nullable.
--
-- The column list is V0027's exactly. Withholding the four new columns is the
-- point of writing them out rather than using `SELECT *`: the payments module
-- must not be able to read a deadline it does not set or write a block it does
-- not sweep, and `SELECT *` would hand it both the day this file was written.
CREATE VIEW payments.fiscal_documents AS
SELECT id,
       tenant_id,
       order_id,
       legal_entity_id,
       payment_intent_id,
       payment_transaction_id,
       provider_type,
       document_type,
       corrects_document_id,
       status,
       reason_code,
       reason_note,
       external_receipt_id,
       fiscal_sign,
       terminal_id,
       receipt_reference,
       registered_at,
       receipt_url,
       provider_status_code,
       provider_message,
       protected_request_reference,
       protected_response_reference,
       submitted_at,
       issued_at,
       version,
       created_at,
       updated_at
FROM fiscal.fiscal_documents;

COMMENT ON VIEW payments.fiscal_documents IS
    'ADR 0038. A compatibility view over fiscal.fiscal_documents, which V0027 anticipated by name. Auto-updatable, and restricted to V0027''s columns so the payments module cannot begin depending on a lifecycle column it does not maintain. Retiring it is a rename in the payments SQL.';

-- ---------------------------------------------------------------------------
-- 6. Grants
-- ---------------------------------------------------------------------------
--
-- No DELETE, on either the table or the view. A fiscal document is evidence,
-- including the ones that record that there will never be a receipt; the only
-- legitimate way for one to stop being true is a linked correction, which is an
-- INSERT. The one existing caller that deletes is a test fixture running as the
-- owner, which is the correct place for that power to live.
GRANT USAGE ON SCHEMA fiscal TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON fiscal.fiscal_documents TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON payments.fiscal_documents TO qoida_application;
