-- ADR 0166, Decision 2: the evidence of an incorrect receipt is what was SENT, and a request that left
-- for the provider is kept for as long as the provider may hold it.
--
-- V0551 kept the sent request on the document itself (protected_request_reference and its siblings), and
-- every submission wrote over the one before: a resubmission "replaces the sent request with the one that
-- actually went". That is true of a request the provider refused. It is false of a request whose reply
-- never came: Click may have accepted it, the OFD may be slow, and the read-back that precedes a
-- resubmission finding nothing yet proves only that nothing has been issued yet. The second request then
-- destroyed the only record of the first, with the lines re-projected from whatever the order and the
-- marks looked like by then, so the evidence could show a receipt that was not the one on file.
--
-- fiscal.fiscal_sent_requests is the append-only history: one row per request that left, numbered from 1,
-- holding the same ADR 0029 protected references the document's columns hold (the ciphertext is bound to
-- the document's record, not to the table it sits in). The document's own columns keep meaning the latest
-- request, so everything that already reads them is unchanged. The application role may INSERT and SELECT
-- and nothing else: a request is evidence, and a row of it is never rewritten or removed.
CREATE TABLE fiscal.fiscal_sent_requests (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    attempt integer NOT NULL,
    sent_at timestamptz NOT NULL,
    protected_request_reference text NOT NULL,
    sent_tender_split_reference text NOT NULL,
    lines_synthetic boolean NOT NULL,
    merchant_binding_id uuid,

    CONSTRAINT fk_fiscal_sent_request_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    -- Cascades only so a fixture that clears documents can clear its history; the application role holds no
    -- DELETE on either table, so in service a document and its requests are never removed.
    CONSTRAINT fk_fiscal_sent_request_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES fiscal.fiscal_documents (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_fiscal_sent_request_merchant_binding FOREIGN KEY (tenant_id, merchant_binding_id)
        REFERENCES payments.merchant_bindings (tenant_id, id),
    CONSTRAINT uq_fiscal_sent_request_attempt UNIQUE (tenant_id, document_id, attempt),
    CONSTRAINT ck_fiscal_sent_request_attempt CHECK (attempt >= 1)
);

COMMENT ON TABLE fiscal.fiscal_sent_requests IS
    'ADR 0166 Decision 2. Append-only: every request that left for a provider, as sent, behind the ADR 0029 protected references. The document''s own protected_request_reference is the latest of these. A resubmission adds a row and never replaces one: a reply that never came proves nothing about what arrived.';

-- Backfill: the request each document holds today becomes its attempt 1.
INSERT INTO fiscal.fiscal_sent_requests (
    id, tenant_id, document_id, attempt, sent_at, protected_request_reference,
    sent_tender_split_reference, lines_synthetic, merchant_binding_id)
SELECT uuidv7(), d.tenant_id, d.id, 1, COALESCE(d.submitted_at, d.updated_at, d.created_at),
       d.protected_request_reference, COALESCE(d.sent_tender_split_reference, ''),
       d.lines_synthetic, d.merchant_binding_id
  FROM fiscal.fiscal_documents d
 WHERE d.protected_request_reference IS NOT NULL;

-- The same shape payments already writes the document through (V0039, V0551): a view restricted to what
-- payments writes, so it cannot begin depending on a column it does not maintain.
CREATE VIEW payments.fiscal_sent_requests AS
SELECT id,
       tenant_id,
       document_id,
       attempt,
       sent_at,
       protected_request_reference,
       sent_tender_split_reference,
       lines_synthetic,
       merchant_binding_id
FROM fiscal.fiscal_sent_requests;

-- No UPDATE and no DELETE, on the table or on the view.
GRANT SELECT, INSERT ON fiscal.fiscal_sent_requests TO horecaos_application;
GRANT SELECT, INSERT ON payments.fiscal_sent_requests TO horecaos_application;
