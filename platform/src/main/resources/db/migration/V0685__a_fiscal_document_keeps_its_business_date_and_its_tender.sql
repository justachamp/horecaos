-- ADR 0038 checklist ("Snapshot the order's business_date and tender_id onto the fiscal document")
-- and ADR 0166 Decision 2 (which tender the document covers).
--
-- Two facts about a fiscal document were inferred every time they were asked for.
--
-- 1. WHICH TENDER. fiscal.fiscal_documents.tender_id has existed since V0039 and nothing wrote it: every
--    row carried NULL, which V0039 documents as correct for an order with one money tender. The
--    evidence read therefore worked out «which tender» by looking at the order's settlement, which is
--    right for one money tender per order and wrong the day a second one exists (a cash leg beside a
--    card leg, each owing its own receipt). The obligation opener now writes it, from the tender whose
--    payment intent the document names. The uniqueness rule from V0039 (one SALE per order and tender,
--    NULLS NOT DISTINCT) is unchanged and now means what it says.
--
-- 2. WHICH DAY. The seller of a receipt is resolved from the branch's assignment on the order's business
--    date, and Click's recovery lookup (status_by_mti) needs the payment's date. The sweeper derived the
--    date from the order's closing instant and the branch's timezone every time it was needed, and a
--    timezone changed after the sale, or a closing instant corrected, would move it. The date the seller
--    was resolved on is now kept on the document: the local date, in the branch's calendar, on which the
--    order closed.
--
-- Rows opened before this migration get the date backfilled by the same rule (the order's closing
-- instant in the branch's timezone, falling back to the platform's business timezone as the opener does)
-- and their tender where exactly one tender names the document's payment intent. A row with no closing
-- instant, or with no unambiguous tender, stays NULL: absence is the honest answer, and the reader says
-- so.
--
-- A document written through the payments compatibility view (the cash decision recorded at checkout, the
-- partner obligation opened by payments) carries neither yet: the order has not closed when those rows are
-- written, so there is no business date to snapshot, and the view deliberately withholds the column.

ALTER TABLE fiscal.fiscal_documents
    ADD COLUMN business_date date;

COMMENT ON COLUMN fiscal.fiscal_documents.business_date IS
    'ADR 0038. The local date, in the branch''s calendar, on which the order closed: the date the seller was resolved on. A snapshot, never recomputed. NULL on a document written before the order closed, and on a row whose order never recorded a closing instant.';
COMMENT ON COLUMN fiscal.fiscal_documents.tender_id IS
    'ADR 0046, ADR 0166 Decision 2. The money tender this document covers, written when the obligation is opened. NULL where no unambiguous tender could be named (a row from before it was written, and the cash decision recorded at checkout). NULLS NOT DISTINCT in the uniqueness rule, so NULL still means one leg.';

-- The evidence read asks «the document of this tender».
CREATE INDEX ix_fiscal_documents_tender
    ON fiscal.fiscal_documents (tenant_id, tender_id)
    WHERE tender_id IS NOT NULL;

-- Backfill the date: the order's closing instant in the branch's calendar.
UPDATE fiscal.fiscal_documents d
   SET business_date = (o.closed_at AT TIME ZONE COALESCE(l.timezone, 'Asia/Tashkent'))::date
  FROM ordering.orders o
  LEFT JOIN tenant.locations l
         ON l.tenant_id = o.tenant_id AND l.id = o.location_id
 WHERE d.business_date IS NULL
   AND o.tenant_id = d.tenant_id
   AND o.id = d.order_id
   AND o.closed_at IS NOT NULL;

-- Backfill the tender where exactly one tender names the document's intent and no other SALE of the
-- order already carries it. Changing a NULL key to a tender cannot collide: the order has one SALE.
UPDATE fiscal.fiscal_documents d
   SET tender_id = t.id
  FROM payments.tenders t
 WHERE d.tender_id IS NULL
   AND d.payment_intent_id IS NOT NULL
   AND d.document_type = 'SALE'
   AND t.tenant_id = d.tenant_id
   AND t.payment_intent_id = d.payment_intent_id
   AND NOT t.settles_from_balance
   AND (SELECT count(*) FROM payments.tenders x
         WHERE x.tenant_id = d.tenant_id AND x.payment_intent_id = d.payment_intent_id) = 1;
