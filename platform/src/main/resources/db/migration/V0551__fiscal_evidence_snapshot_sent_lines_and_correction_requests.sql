-- ADR 0166: the fiscal agent per settlement path, and the fiscal evidence surface.
--
-- The evidence already exists (V0027, moved by V0039: the receipt URL, the fiscal sign,
-- the receipt number, the terminal id, the two ADR 0029 references to the request and
-- the response) and nothing could show it honestly. Four things stood between the rows
-- and a screen an operator can hand a tax inspector, and this migration is the schema half
-- of all four.
--
-- 1. WHICH INN ISSUED IT. A document names a legal entity, and a join made a year later
--    answers for today: an entity is re-registered, a branch is handed over. The INN and
--    the legal name are therefore snapshotted onto the document when it is OPENED, by a
--    trigger rather than by whoever remembers: the cash decision row is written through
--    the payments compatibility view at checkout, the partner obligation by the fiscal
--    opener, and a third writer next year would otherwise make the one that forgot a
--    document with no taxpayer on it. Existing rows are backfilled from the entity as it
--    stands now and marked BACKFILL, because that is "resolved from the entity, not from
--    the filing" and the surface says so. A row whose entity cannot be read stays NULL.
--
-- 2. WHAT WAS FILED. Nothing wrote protected_request_reference, and a ciphertext of an
--    Items array does not fit the varchar(512) it was declared as. It becomes text, which
--    needs the compatibility view dropped and rebuilt (PostgreSQL will not retype a column
--    a view reads). The same rebuild appends the four columns payments writes when it
--    submits: the placeholder flag, the tender split as sent, the merchant account used.
--
-- 3. WHAT A DOCUMENT CAN BECOME AFTER IT IS ISSUED. A correction or a void is a request
--    HorecaOS records and the tenant's own fiscal agent issues (Decision 8): four statuses
--    after ISSUED, and a request table. HorecaOS never edits or deletes a fiscal document
--    and never composes a correction receipt.
--
-- 4. THE PLACEHOLDER. lines_synthetic is set when the bridge files its stand-in line, so
--    the evidence says it was a stand-in rather than a sale.
--
-- The per-unit mark store is NOT here: ADR 0161 puts it in `ordering`, and ADR 0166
-- builds no fiscal.fiscal_unit_marks.

-- ---------------------------------------------------------------------------
-- 1. Statuses after ISSUED
-- ---------------------------------------------------------------------------

ALTER TABLE fiscal.fiscal_documents
    DROP CONSTRAINT ck_fiscal_document_status;

ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_status CHECK (status IN (
        'NOT_APPLICABLE', 'PENDING', 'SUBMITTED', 'ISSUED', 'FAILED', 'BLOCKED',
        'CORRECTION_REQUESTED', 'VOID_REQUESTED', 'CORRECTED', 'VOIDED'));

-- A document that was issued and is now being corrected, or was corrected, or was voided
-- still IS the evidence of what was filed. The four statuses must not be a way to lose the
-- identifiers: ck_fiscal_document_issued_carries_evidence only speaks of ISSUED.
ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_after_issue_keeps_evidence CHECK (
        status NOT IN ('CORRECTION_REQUESTED', 'VOID_REQUESTED', 'CORRECTED', 'VOIDED')
        OR (fiscal_sign IS NOT NULL
            AND external_receipt_id IS NOT NULL
            AND issued_at IS NOT NULL
            AND provider_type IS NOT NULL));

-- ---------------------------------------------------------------------------
-- 2. The snapshot, the placeholder flag, the split as sent, the account used
-- ---------------------------------------------------------------------------

ALTER TABLE fiscal.fiscal_documents
    ADD COLUMN seller_tin varchar(9),
    ADD COLUMN seller_name varchar(200),
    -- OPENING: taken by the trigger below when the row was inserted. BACKFILL: taken from the
    -- entity as it stood when this migration ran, which is not the same claim.
    ADD COLUMN seller_snapshot_source varchar(16),
    ADD COLUMN lines_synthetic boolean NOT NULL DEFAULT false,
    -- ADR 0029: how the provider was told the payment was split (Click's received_card,
    -- received_cash, received_ecash), as sent. A protected reference, not a column of amounts.
    ADD COLUMN sent_tender_split_reference text,
    -- The merchant account the submission went through. NULL until something is submitted, and
    -- for a document that has no merchant account to name (a cash decision).
    ADD COLUMN merchant_binding_id uuid;

ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT ck_fiscal_document_seller_tin CHECK (
        seller_tin IS NULL OR seller_tin ~ '^[0-9]{9}$'),
    -- Both or neither, and the source with them. A TIN with no name, or a snapshot with no
    -- statement of where it came from, is half an answer to "who issued this".
    ADD CONSTRAINT ck_fiscal_document_seller_pair CHECK (
        (seller_tin IS NULL) = (seller_name IS NULL)
        AND (seller_tin IS NULL) = (seller_snapshot_source IS NULL)),
    ADD CONSTRAINT ck_fiscal_document_seller_source CHECK (
        seller_snapshot_source IS NULL OR seller_snapshot_source IN ('OPENING', 'BACKFILL')),
    ADD CONSTRAINT fk_fiscal_document_merchant_binding FOREIGN KEY (tenant_id, merchant_binding_id)
        REFERENCES payments.merchant_bindings (tenant_id, id);

COMMENT ON COLUMN fiscal.fiscal_documents.seller_tin IS
    'ADR 0166 Decision 3. The INN of the legal entity this document named when it was opened. A snapshot, never a join: an entity is re-registered and a branch changes hands, and a receipt from last year must not show this year''s taxpayer. A business identifier, not evidence.';
COMMENT ON COLUMN fiscal.fiscal_documents.seller_snapshot_source IS
    'OPENING: taken when the document was inserted. BACKFILL: resolved from the legal entity as it stood when V0551 ran -- not from the filing -- and the evidence surface says so.';
COMMENT ON COLUMN fiscal.fiscal_documents.lines_synthetic IS
    'ADR 0166 Decision 6. True when the receipt lines were the bridge''s one-line placeholder (ИКПУ 00000000000000000) and not lines built from the sale. Only ever true in a sandbox.';
COMMENT ON COLUMN fiscal.fiscal_documents.sent_tender_split_reference IS
    'ADR 0029 protected reference to how the payment was split in what was sent to the provider (Click received_card / received_cash / received_ecash).';

-- Snapshot at insert, whoever the writer is.
CREATE FUNCTION fiscal.snapshot_seller() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.seller_tin IS NULL AND NEW.legal_entity_id IS NOT NULL THEN
        SELECT e.tin, e.legal_name
          INTO NEW.seller_tin, NEW.seller_name
          FROM tenant.legal_entities e
         WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.legal_entity_id;
        IF NEW.seller_tin IS NOT NULL THEN
            NEW.seller_snapshot_source := 'OPENING';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_fiscal_document_snapshot_seller
    BEFORE INSERT ON fiscal.fiscal_documents
    FOR EACH ROW EXECUTE FUNCTION fiscal.snapshot_seller();

-- And once taken, it does not move. A snapshot that an UPDATE can rewrite is a join with
-- extra steps. A row with no snapshot may be given one (the backfill below is exactly that);
-- a row that has one keeps it.
CREATE FUNCTION fiscal.keep_seller_snapshot() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.seller_tin IS NOT NULL AND (
            NEW.seller_tin IS DISTINCT FROM OLD.seller_tin
            OR NEW.seller_name IS DISTINCT FROM OLD.seller_name
            OR NEW.seller_snapshot_source IS DISTINCT FROM OLD.seller_snapshot_source) THEN
        RAISE EXCEPTION 'The seller snapshot of fiscal document % is evidence and is not rewritten', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_fiscal_document_keep_seller_snapshot
    BEFORE UPDATE ON fiscal.fiscal_documents
    FOR EACH ROW EXECUTE FUNCTION fiscal.keep_seller_snapshot();

-- Rows opened before this migration: the entity as it stands now, marked as such.
UPDATE fiscal.fiscal_documents d
   SET seller_tin = e.tin,
       seller_name = e.legal_name,
       seller_snapshot_source = 'BACKFILL'
  FROM tenant.legal_entities e
 WHERE d.seller_tin IS NULL
   AND d.legal_entity_id IS NOT NULL
   AND e.tenant_id = d.tenant_id
   AND e.id = d.legal_entity_id;

-- ---------------------------------------------------------------------------
-- 3. The sent request does not fit a varchar(512); the view has to be rebuilt to say so
-- ---------------------------------------------------------------------------
--
-- The compatibility view stays restricted to what payments writes or reads. The four
-- appended columns are the ones its submission path writes (the placeholder flag, the split
-- as sent, the merchant account used); the seller snapshot is the trigger's and the fiscal
-- module's, and stays out of it.
DROP VIEW payments.fiscal_documents;

ALTER TABLE fiscal.fiscal_documents
    ALTER COLUMN protected_request_reference TYPE text,
    -- CORRECTION_REQUESTED is twenty characters and the column was varchar(16): the four statuses
    -- after ISSUED need the width as much as the CHECK that now names them.
    ALTER COLUMN status TYPE varchar(24);

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
       updated_at,
       lines_synthetic,
       sent_tender_split_reference,
       merchant_binding_id
FROM fiscal.fiscal_documents;

COMMENT ON VIEW payments.fiscal_documents IS
    'ADR 0038. A compatibility view over fiscal.fiscal_documents, which V0027 anticipated by name. Auto-updatable, and restricted to the columns payments reads or writes so it cannot begin depending on a lifecycle column it does not maintain. V0551 widened protected_request_reference to text and appended the three columns its submission path writes (ADR 0166).';

GRANT SELECT, INSERT, UPDATE ON payments.fiscal_documents TO horecaos_application;

-- ---------------------------------------------------------------------------
-- 4. Correction and void requests
-- ---------------------------------------------------------------------------
--
-- HorecaOS records the request and the tenant's own agent issues the correction on the path
-- the sale used; the operator attaches the agent's new reference, which creates a linked
-- CORRECTION or REFUND document and moves the original to CORRECTED or VOIDED. Never an
-- edit of the original, never a deletion, and no HorecaOS-side deadline: the request is aged
-- on a worklist (the open input on the legal deadline is not HorecaOS's to decide).
CREATE TABLE fiscal.fiscal_correction_requests (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    kind varchar(12) NOT NULL,

    reason_code varchar(32) NOT NULL,
    reason_note varchar(255) NOT NULL,

    status varchar(12) NOT NULL DEFAULT 'REQUESTED',

    requested_by varchar(255) NOT NULL,
    -- The ADR 0027 approval this request was raised under: the second person.
    approval_id uuid,
    requested_at timestamptz NOT NULL,

    attached_document_id uuid,
    attached_by varchar(255),
    attached_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_fiscal_correction_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_fiscal_correction_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES fiscal.fiscal_documents (tenant_id, id),
    CONSTRAINT fk_fiscal_correction_attached FOREIGN KEY (tenant_id, attached_document_id)
        REFERENCES fiscal.fiscal_documents (tenant_id, id),
    CONSTRAINT uq_fiscal_correction_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT ck_fiscal_correction_kind CHECK (kind IN ('CORRECTION', 'VOID')),
    CONSTRAINT ck_fiscal_correction_status CHECK (status IN ('REQUESTED', 'ATTACHED', 'DECLINED', 'CANCELLED')),
    CONSTRAINT ck_fiscal_correction_reason CHECK (
        reason_code ~ '^[A-Z][A-Z0-9_]{1,31}$' AND length(btrim(reason_note)) > 0),

    -- An attachment is all three of its facts or none of them, and only an ATTACHED request has
    -- them: a request that says it was attached with no document is a claim nothing backs.
    CONSTRAINT ck_fiscal_correction_attachment CHECK (
        (status = 'ATTACHED') = (attached_document_id IS NOT NULL)
        AND (attached_document_id IS NULL) = (attached_by IS NULL)
        AND (attached_document_id IS NULL) = (attached_at IS NULL)),
    CONSTRAINT ck_fiscal_correction_no_self_attachment CHECK (
        attached_document_id IS NULL OR attached_document_id <> document_id),
    CONSTRAINT ck_fiscal_correction_version CHECK (version >= 1)
);

-- One open request per document. Two people asking for the same correction is one correction.
CREATE UNIQUE INDEX uq_fiscal_correction_one_open_per_document
    ON fiscal.fiscal_correction_requests (tenant_id, document_id)
    WHERE status = 'REQUESTED';

-- The ageing worklist: what has been waiting longest for the agent.
CREATE INDEX ix_fiscal_correction_open
    ON fiscal.fiscal_correction_requests (tenant_id, requested_at)
    WHERE status = 'REQUESTED';

COMMENT ON TABLE fiscal.fiscal_correction_requests IS
    'ADR 0166 Decision 8. A request HorecaOS records and the tenant''s own fiscal agent issues. HorecaOS never edits or deletes a fiscal document and never composes a correction receipt.';

-- No DELETE, on this or on fiscal.fiscal_documents (V0039: a fiscal document is evidence).
GRANT SELECT, INSERT, UPDATE ON fiscal.fiscal_correction_requests TO horecaos_application;

-- ---------------------------------------------------------------------------
-- 5. The second signature on a correction request
-- ---------------------------------------------------------------------------
--
-- ADR 0166: "the correction request is an ADR 0027 approval action; the approver is a second
-- person." A correction asks the tenant to file a legal document that changes what the tax
-- authority holds, so the action fails closed (REQUIRE_CONFIGURED_POLICY, as the wallet
-- actions do) and the platform-scope policy is seeded here: a deployment that deleted it
-- should stop, not let one person do it on their own word.
INSERT INTO audit.approval_policies (
    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
    valid_from, version, approved_by)
VALUES
    ('0192d1a0-0000-7000-8000-000000000551', NULL, 'fiscal.correction.request', 'PLATFORM',
     '{"description": "Every request to correct or void an issued fiscal receipt"}'::jsonb,
     'fiscal.document.resolve', '2026-10-08T00:00:00Z', 1, 'migration V0551');
