-- ADR 0166 Decision 3 / Decision 9: the INN a receipt is filed under is the one its document was
-- opened under, never today's resolution of the order's location.
--
-- V0551 snapshotted the issuing INN onto the document and kept it out of the payments
-- compatibility view on the reasoning that payments neither reads nor writes it. That stopped
-- being true the moment the receipt line builder needed a seller: the bridge resolved the
-- location's assignment a second time, so a branch that had changed hands since the sale filed
-- the receipt under a zero TIN (or, for an entity re-registered under a new INN, under the new
-- one) while the evidence surface went on showing the snapshot. The surface and the filing then
-- named two taxpayers for one sale.
--
-- Appending the column is all it takes: CREATE OR REPLACE VIEW accepts new columns at the end,
-- the grants and the auto-updatable shape survive, and the trigger that takes the snapshot is
-- the only writer of it. payments reads it; payments never writes it (V0551's keep-trigger would
-- refuse a rewrite of a snapshot that exists anyway).
CREATE OR REPLACE VIEW payments.fiscal_documents AS
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
       updated_at,
       lines_synthetic,
       sent_tender_split_reference,
       merchant_binding_id,
       seller_tin
FROM fiscal.fiscal_documents;

COMMENT ON VIEW payments.fiscal_documents IS
    'ADR 0038. A compatibility view over fiscal.fiscal_documents, which V0027 anticipated by name. Auto-updatable, and restricted to the columns payments reads or writes so it cannot begin depending on a lifecycle column it does not maintain. V0551 widened protected_request_reference to text and appended the three columns its submission path writes (ADR 0166); V0640 appended seller_tin, which payments READS (the INN a receipt is filed under is the snapshot taken when the document was opened) and never writes.';
