-- ADR 0139: every tenant keeps its own record of each person who works for it.
--
-- One row per (tenant, Keycloak subject). The person is global and the
-- employment is not: this tenant hired them, this tenant knows their spoken
-- languages and their employee number, and a second tenant that hired the same
-- human knows different things and must never read these. So the record is
-- tenant-scoped on purpose, and `iam.principals` (V0057), which is global and
-- PII-free on purpose, is not extended to carry it.
--
-- It answers "who is this?" for the People list, every audit actor, every order
-- attribution, the POS operator mapping and the leaderboard. It is not an
-- authorization record (iam.grants stays that) and not an authentication record
-- (Keycloak stays that).
--
-- Personal fields follow ADR 0029 and nothing invented here: name, phone and
-- employee number are PERSONAL, stored envelope-encrypted through
-- FieldProtection with associated data binding each ciphertext to its tenant,
-- this table, its column and its row. A keyed per-tenant lookup hash beside the
-- phone and the employee number gives exact-match search without a decrypt.
-- Status, the two dates, the languages and the non-personal display_reference
-- stay in clear so a screen can sort, filter and count on them.
--
-- A row is never deleted: audit facts, order attribution and POS mappings refer
-- to it, and the retention sweeper (report-only until legal answers) anonymises
-- it in place. That is why the grant at the foot has no DELETE.

CREATE TABLE iam.staff_members (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- The same string iam.grants.principal_subject carries.
    principal_subject varchar(255) NOT NULL,
    -- 'S-0142': tenant-unique, never reused, allocated from
    -- iam.staff_member_counters. What an event payload, a log line or a
    -- wallboard may carry when a name must not appear.
    display_reference varchar(32) NOT NULL,

    -- ADR 0029 PERSONAL. Nullable on purpose: a subject whose Keycloak account
    -- holds no name is backfilled with null names and renders as its
    -- display_reference until someone fills it in. The edit form requires a
    -- first name before it will save.
    protected_first_name text,
    protected_last_name text,
    protected_phone text,
    phone_lookup_hash varchar(64),
    protected_employee_number text,
    employee_number_hash varchar(64),

    -- A PRIVATE, TENANT-owned image. Two-column reference on purpose: media.assets
    -- is keyed on asset_id alone, and a single-column reference lets one tenant's
    -- row point at another tenant's private object (V0069 repaired exactly this for
    -- a courier's registration certificate). A null photo is not checked.
    photo_asset_id uuid,

    ui_locale varchar(8),
    spoken_languages varchar(8)[] NOT NULL DEFAULT '{}',

    employment_status varchar(16) NOT NULL,
    employed_from date,
    employed_until date,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_staff_member_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_staff_member_subject UNIQUE (tenant_id, principal_subject),
    CONSTRAINT uq_staff_member_reference UNIQUE (tenant_id, display_reference),
    CONSTRAINT uq_staff_member_identity UNIQUE (id, tenant_id),
    CONSTRAINT fk_staff_member_photo FOREIGN KEY (photo_asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id),
    CONSTRAINT ck_staff_member_reference CHECK (display_reference ~ '^S-[0-9]{4,}$'),
    CONSTRAINT ck_staff_member_status CHECK (employment_status IN ('PENDING', 'ACTIVE', 'ON_LEAVE', 'ENDED')),
    CONSTRAINT ck_staff_member_ended_has_date CHECK (employment_status <> 'ENDED' OR employed_until IS NOT NULL),
    CONSTRAINT ck_staff_member_dates CHECK (
        employed_until IS NULL OR employed_from IS NULL OR employed_until >= employed_from),
    CONSTRAINT ck_staff_member_locale CHECK (ui_locale IS NULL OR ui_locale IN ('ru', 'uz', 'en')),
    CONSTRAINT ck_staff_member_languages CHECK (cardinality(spoken_languages) <= 8),
    -- A ciphertext with no lookup hash, or a hash with no ciphertext, is a
    -- half-written value that exact-match search would silently miss.
    CONSTRAINT ck_staff_member_phone_pair CHECK ((protected_phone IS NULL) = (phone_lookup_hash IS NULL)),
    CONSTRAINT ck_staff_member_employee_number_pair CHECK (
        (protected_employee_number IS NULL) = (employee_number_hash IS NULL)),
    CONSTRAINT ck_staff_member_version CHECK (version >= 1)
);

-- NOT unique, on purpose. A contact phone is a way to reach someone, not an
-- identity: the cook may set hers to the kitchen's shared mobile, and a new
-- hire's personal sign-in number may equal a number a colleague has already made
-- their contact phone. A unique index would turn either into a failure of an
-- invitation after its irreversible steps. Sign-in identity is the Keycloak
-- username, unique realm-wide and guarded elsewhere.
CREATE INDEX ix_staff_member_phone
    ON iam.staff_members (tenant_id, phone_lookup_hash)
    WHERE phone_lookup_hash IS NOT NULL;

-- An employee number does identify one person inside one employer.
CREATE UNIQUE INDEX ux_staff_member_employee_number
    ON iam.staff_members (tenant_id, employee_number_hash)
    WHERE employee_number_hash IS NOT NULL;

-- The People list reads a whole tenant and filters by status.
CREATE INDEX ix_staff_member_status ON iam.staff_members (tenant_id, employment_status);

COMMENT ON TABLE iam.staff_members IS
    'ADR 0139. One row per tenant and Keycloak subject: who this person is to this tenant. Not an authorization record (iam.grants) and not an authentication record (Keycloak). Never deleted; the retention sweeper anonymises in place.';
COMMENT ON COLUMN iam.staff_members.display_reference IS
    'ADR 0029. A non-personal handle such as S-0142, tenant-unique and never reused, allocated from iam.staff_member_counters. The only identifier an event payload, a log line or a wallboard may carry for a person.';
COMMENT ON COLUMN iam.staff_members.protected_first_name IS
    'ADR 0029 PERSONAL, envelope-encrypted and bound to this tenant, table, column and row by AAD. Never mirrored back to Keycloak after account creation and invitation acceptance.';
COMMENT ON COLUMN iam.staff_members.protected_last_name IS
    'ADR 0029 PERSONAL, envelope-encrypted and bound to this row by AAD.';
COMMENT ON COLUMN iam.staff_members.protected_phone IS
    'ADR 0029 PERSONAL, envelope-encrypted. The contact phone colleagues call; it starts as the sign-in number and then belongs to the person. Not the Keycloak username.';
COMMENT ON COLUMN iam.staff_members.phone_lookup_hash IS
    'Keyed per-tenant HMAC of the digits of protected_phone, for exact-match search only. Deliberately not unique.';
COMMENT ON COLUMN iam.staff_members.protected_employee_number IS
    'ADR 0029 PERSONAL, envelope-encrypted. The employer-assigned number; set by a manager, never by the person.';
COMMENT ON COLUMN iam.staff_members.employee_number_hash IS
    'Keyed per-tenant HMAC of protected_employee_number: exact-match search, and uniqueness inside one tenant.';
COMMENT ON COLUMN iam.staff_members.photo_asset_id IS
    'ADR 0010 media asset: PRIVATE visibility, TENANT owner scope, in this tenant. fk_staff_member_photo is the backstop; the service resolves the asset in the caller''s own tenant first and answers one not-found for an asset of another tenant and for one that does not exist.';
COMMENT ON COLUMN iam.staff_members.employment_status IS
    'PENDING (invited, not yet accepted), ACTIVE, ON_LEAVE, ENDED. ENDED is reached only through the end-employment act, which also revokes the person''s grants in this tenant.';

GRANT SELECT, INSERT, UPDATE ON iam.staff_members TO horecaos_application;
