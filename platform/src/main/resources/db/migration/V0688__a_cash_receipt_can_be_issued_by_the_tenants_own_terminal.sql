-- ADR 0166 «Providers» and ADR 0038 stage 5: a cash sale at a location with a fiscal-capable terminal is a TERMINAL
-- receipt, issued by the tenant's own equipment and recorded here.
--
-- WHAT STOOD IN THE WAY. Two checks said an issued document names the provider that issued it: ISSUED needs a
-- provider_type (V0027), and so does every status after ISSUED (V0551). That was true while only Click and Payme
-- issued. A terminal receipt has no provider, and the honest alternative to relaxing the checks (a made-up provider
-- type) would put a false fact on a tax document. So the document names the terminal that issued it instead, and
-- the checks become «a provider or a terminal».
--
-- WHICH TERMINAL. fiscal_terminal_id is the registry's id (fiscal.fiscal_terminals), set when the terminal's receipt
-- is recorded and before the document moves to ISSUED. The receipt's own terminal_id (the fiscal module's number
-- printed on it) stays what it was: the OFD's identifier, evidence. This column is HorecaOS's own foreign key.
--
-- THE COMPATIBILITY VIEW gains the column, appended (CREATE OR REPLACE VIEW may add trailing columns), because
-- payments writes the evidence through it.
--
-- NO NEW GRANT. A column on a table the application role already holds SELECT, INSERT and UPDATE on, and a
-- constraint on the terminal registry, which only fiscal writes.

ALTER TABLE fiscal.fiscal_terminals
    ADD CONSTRAINT uq_fiscal_terminal_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE fiscal.fiscal_documents
    ADD COLUMN fiscal_terminal_id uuid;

ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT fk_fiscal_document_terminal FOREIGN KEY (tenant_id, fiscal_terminal_id)
        REFERENCES fiscal.fiscal_terminals (tenant_id, id);

-- An issued document is issued by something: a provider through a merchant account, or the tenant's terminal.
ALTER TABLE fiscal.fiscal_documents
    DROP CONSTRAINT ck_fiscal_document_issued_carries_evidence;

ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_issued_carries_evidence CHECK (
        status <> 'ISSUED' OR (
            fiscal_sign IS NOT NULL
            AND external_receipt_id IS NOT NULL
            AND issued_at IS NOT NULL
            AND (provider_type IS NOT NULL OR fiscal_terminal_id IS NOT NULL)));

ALTER TABLE fiscal.fiscal_documents
    DROP CONSTRAINT ck_fiscal_document_after_issue_keeps_evidence;

ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_after_issue_keeps_evidence CHECK (
        status NOT IN ('CORRECTION_REQUESTED', 'VOID_REQUESTED', 'CORRECTED', 'VOIDED')
        OR (fiscal_sign IS NOT NULL
            AND external_receipt_id IS NOT NULL
            AND issued_at IS NOT NULL
            AND (provider_type IS NOT NULL OR fiscal_terminal_id IS NOT NULL)));

COMMENT ON COLUMN fiscal.fiscal_documents.fiscal_terminal_id IS
    'ADR 0166 «Providers». The registered terminal (fiscal.fiscal_terminals) that issued this document, for a TERMINAL receipt: a cash sale at a location with fiscal equipment of its own. NULL for a provider receipt, which names its merchant account instead.';

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
       seller_tin,
       fiscal_terminal_id
FROM fiscal.fiscal_documents;

COMMENT ON VIEW payments.fiscal_documents IS
    'ADR 0038. A compatibility view over fiscal.fiscal_documents, which V0027 anticipated by name. Auto-updatable, and restricted to the columns payments reads or writes so it cannot begin depending on a lifecycle column it does not maintain. V0551 widened protected_request_reference to text and appended the three columns its submission path writes (ADR 0166); V0640 appended seller_tin, which payments READS and never writes; V0688 appended fiscal_terminal_id, which payments WRITES when the tenant''s own terminal issues a cash receipt.';
