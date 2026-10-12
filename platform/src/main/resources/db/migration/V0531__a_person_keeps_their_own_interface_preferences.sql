-- ADR 0160: a person's own interface preferences, beside the staff-member record.
--
-- One row per (tenant, Keycloak subject, surface). A surface is a screen family the
-- console remembers something for -- SHELL (the default branch) and ORDER_BOARD (the
-- board's columns and saved views) today -- and the closed set of them is owned by code,
-- not by this table: the check below is a shape check, so a new surface is a release and
-- never a migration.
--
-- Why a table of its own, and neither ADR 0030 nor the member row (the record argues both
-- at length): a `USER` scope would need a place in the resolution chain and in ADR 0025's
-- `covers`, every write would be an audited, versioned configuration change, and a
-- ConfigurationKey is a tenant-visible scalar; the member row's version is contended by a
-- manager's edits and the preference is rewritten far more often than anything else on it.
--
-- What the row holds, and nothing else: the tenant, the subject (the same string
-- iam.grants and iam.staff_members carry), the surface, one protected document, the schema
-- version the document was written under and a row version. No name, phone, address,
-- device or network identifier and no free-text search term, and no queryable column
-- inside the document: nothing queries it, and a filter on a decrypted field would force
-- the protection off.
--
-- ADR 0029 lists "preferences" among PERSONAL data, and one thing in a document is text a
-- person typed -- a saved view's label -- which the platform cannot prove is not about
-- someone. So the whole document is PERSONAL: stored envelope-encrypted through
-- FieldProtection, the associated data binding the ciphertext to the tenant, this table,
-- this column and this row, so a ciphertext copied to another row, column or tenant fails
-- to open instead of showing someone else's layout.
--
-- Unlike iam.staff_members, a row here IS deleted: a person resets a surface at any time,
-- and the retention sweeper's anonymisation (ADR 0139) removes every row of the person in
-- the same transaction as the member's own update. The 8 KB cap per document and the 12
-- surfaces per person are enforced in the service, where the error can say what was wrong.

CREATE TABLE iam.staff_preferences (
    -- Ids.newId(); the record reference the protection's associated data is bound to.
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    principal_subject varchar(255) NOT NULL,
    surface varchar(32) NOT NULL,
    protected_document text NOT NULL,
    document_schema smallint NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- The member row is found by its unique (tenant, subject) pair and never by a supplied
    -- id; a subject with no member row (a device, a support session) can hold no preference.
    CONSTRAINT fk_staff_preference_member FOREIGN KEY (tenant_id, principal_subject)
        REFERENCES iam.staff_members (tenant_id, principal_subject),
    CONSTRAINT uq_staff_preference_surface UNIQUE (tenant_id, principal_subject, surface),
    CONSTRAINT ck_staff_preference_surface CHECK (surface ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_staff_preference_versions CHECK (version >= 1 AND document_schema >= 1)
);

COMMENT ON TABLE iam.staff_preferences IS
    'ADR 0160. One protected document per (tenant, subject, surface): the board''s columns and saved views, the default branch. Written only by the person it belongs to; no audit fact and no event per write; deleted with the person at anonymisation.';
COMMENT ON COLUMN iam.staff_preferences.protected_document IS
    'ADR 0029 PERSONAL, envelope-encrypted and bound to this tenant, table, column and row by AAD: a closed-vocabulary document plus a saved view''s typed label. Never logged, put in an event, a metric label or a trace attribute.';
COMMENT ON COLUMN iam.staff_preferences.document_schema IS
    'The surface schema version the document was written under; the reader upgrades an older one in memory.';

-- DELETE is deliberate (reset, and anonymisation); iam.staff_members itself is never deleted.
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.staff_preferences TO horecaos_application;
