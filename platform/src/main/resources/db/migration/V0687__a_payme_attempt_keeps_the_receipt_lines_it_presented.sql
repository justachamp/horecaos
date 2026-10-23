-- ADR 0166 Decision 9 and ADR 0038 stage 4: Payme takes the fiscal lines WITH THE CHECKOUT, fixed before the
-- customer pays. Click is the mirror image (it fiscalizes after capture, from a payment id that does not exist
-- earlier), and a document that carries the lines is opened only when the order completes, long after the
-- customer saw the checkout. So at presentation the lines have nowhere to live but the attempt.
--
-- WHAT IS KEPT. The receipt lines the checkout carried, exactly as sent, behind an ADR 0029 FINANCIAL
-- protected reference (the same shape as fiscal.fiscal_documents.protected_request_reference): a price, a
-- classifier and a quantity of something a named customer bought. «Stored as sent» (Decision 6) is why: what
-- the evidence shows must be what the customer's checkout carried, not what the catalogue says by the time the
-- document is filed. A marking code is never in it (Payme has no field for one, and a marked basket is not
-- presented on Payme).
--
-- WHO READS IT. The presentation itself (the POST form's detail field), CheckPerformTransaction (the detail
-- result member), and the filing, which records the same lines as the document's sent request.
--
-- NO NEW GRANT. A column on a table the application role already holds SELECT, INSERT and UPDATE on.

ALTER TABLE payments.payment_attempts
    ADD COLUMN presented_receipt_reference text;

COMMENT ON COLUMN payments.payment_attempts.presented_receipt_reference IS
    'ADR 0166 Decision 9, ADR 0029 FINANCIAL. The receipt lines a Payme checkout carried (the detail object), as sent, behind a protected reference. Written when the attempt is first presented and never rewritten: a re-presentation carries the same lines. NULL on a Click attempt and on a Payme cashbox that does not fiscalise.';
