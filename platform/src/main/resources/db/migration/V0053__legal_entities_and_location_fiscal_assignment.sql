-- ADR 0038 rollout stage 1: tenant.legal_entities and the effective-dated
-- assignment of a location to one. Owned by ADR 0038; the fiscal obligation
-- opener and the payments merchant binding both resolve through it.
--
-- btree_gist is already installed by V0025 and V0034. The statement is repeated
-- so this file states everything it depends on.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- A company inside a tenant that sells in its own name.
--
-- ADR 0002 models Tenant -> Brand -> Location with no company anywhere, which is
-- a model of one taxpayer per tenant. This market is not that: the competitor
-- holds the fiscalization INN at branch granularity because one operator
-- routinely splits branches across companies. Brand cannot carry it either -- a
-- company and a trade name are orthogonal.
CREATE TABLE tenant.legal_entities (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Stable and never reused. A fiscal document and a merchant binding both
    -- point at this row and must still resolve a year later, so an entity is
    -- archived rather than deleted.
    code varchar(32) NOT NULL,

    legal_name varchar(200) NOT NULL,
    short_name varchar(100),

    -- Uzbek INN / STIR: nine digits. Not a PINFL, which is fourteen and
    -- identifies a person. No checksum: the check-digit rule is not published in
    -- a citable form, and a guessed algorithm rejects valid numbers, which has no
    -- workaround, while a wrong INN is visible on the first receipt.
    tin varchar(9) NOT NULL,

    vat_registered boolean NOT NULL DEFAULT false,
    vat_certificate_reference varchar(64),

    -- ADR 0018 profile used when this entity sells. Nullable: null means
    -- resolution falls through to brand and tenant, not that no tax applies.
    --
    -- NO FOREIGN KEY, and that is a gap rather than a decision:
    -- pricing.tax_profiles has no UNIQUE (tenant_id, id), so the composite key
    -- that would keep this inside the tenant cannot be declared. Adding that
    -- unique constraint to pricing.tax_profiles makes this a one-line follow-up.
    tax_profile_id uuid,

    registered_address varchar(400),
    contact_phone varchar(32),

    status varchar(24) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_legal_entity_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT uq_legal_entity_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_legal_entity_code UNIQUE (tenant_id, code),

    -- One row per taxpayer. Two rows for one INN make every per-entity merchant
    -- binding ambiguous, and "which service issued this receipt" becomes a
    -- question about row order.
    CONSTRAINT uq_legal_entity_tin UNIQUE (tenant_id, tin),

    CONSTRAINT ck_legal_entity_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_legal_entity_tin CHECK (tin ~ '^[0-9]{9}$'),
    CONSTRAINT ck_legal_entity_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),

    -- An entity that deregisters and keeps its certificate reads to a later
    -- auditor as though it were still registered, and every receipt it issues
    -- charges VAT it does not owe.
    CONSTRAINT ck_legal_entity_vat_certificate CHECK (
        vat_registered OR vat_certificate_reference IS NULL),
    CONSTRAINT ck_legal_entity_version CHECK (version >= 1)
);

COMMENT ON TABLE tenant.legal_entities IS
    'ADR 0038. The company that sells, as distinct from the brand it sells under. One tenant contains several taxpayers, which is what this market looks like.';
COMMENT ON COLUMN tenant.legal_entities.tin IS
    'Uzbek INN/STIR, nine digits. The number printed on the customer''s fiscal receipt as the seller.';

-- Which company a branch sold under, and between which dates.
--
-- The tax identity hangs here rather than on tenant.locations. A column on the
-- location would hold one value -- today's -- and a receipt issued before a
-- re-registration would resolve to the company that took over afterwards.
CREATE TABLE tenant.location_fiscal_assignments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,

    -- Half-open [effective_from, effective_until). The end date is the first day
    -- the assignment no longer applies, so a handover on 1 September is one row
    -- ending on the first and another starting on it, with no day belonging to
    -- both and none belonging to neither. An inclusive end invites the
    -- off-by-one where the handover day is claimed by two taxpayers at once.
    effective_from date NOT NULL,
    effective_until date,

    -- ADR 0027 evidence. Which company sells at a branch is a decision somebody
    -- signed, and an assignment nobody approved is one nobody can defend.
    approved_by varchar(200) NOT NULL,
    approval_reference varchar(200),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_location_fiscal_assignment_location
        FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_location_fiscal_assignment_entity
        FOREIGN KEY (tenant_id, legal_entity_id)
        REFERENCES tenant.legal_entities (tenant_id, id),
    CONSTRAINT uq_location_fiscal_assignment_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_location_fiscal_assignment_range CHECK (
        effective_until IS NULL OR effective_until > effective_from),
    CONSTRAINT ck_location_fiscal_assignment_version CHECK (version >= 1),

    -- The constraint the whole table exists for. Two assignments covering one day
    -- for one location mean two INNs are simultaneously correct and the resolver
    -- picks by row order -- one branch issuing receipts under two taxpayers in an
    -- evening, decided by a tiebreak nobody chose. A Java pre-check settles every
    -- race except the first write for a location, which is exactly the case that
    -- matters, so the rule lives here.
    CONSTRAINT ex_location_fiscal_assignment_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        location_id WITH =,
        daterange(effective_from, effective_until, '[)') WITH &&)
);

COMMENT ON TABLE tenant.location_fiscal_assignments IS
    'ADR 0038. Which legal entity a location sold under, effective-dated. Resolution is by location and business date and is snapshotted onto the order: a re-registration must not rewrite what a delivered order''s receipt said.';

CREATE INDEX ix_location_fiscal_assignment_resolution
    ON tenant.location_fiscal_assignments (tenant_id, location_id, effective_from DESC);

GRANT SELECT, INSERT, UPDATE ON tenant.legal_entities TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON tenant.location_fiscal_assignments TO qoida_application;

-- The two forward foreign keys ADR 0038's checklist calls "a one-line forward
-- migration". Run them only after every existing legal_entity_id has a row
-- above; both columns are unconstrained uuids today.
ALTER TABLE payments.merchant_bindings
    ADD CONSTRAINT fk_merchant_binding_legal_entity
    FOREIGN KEY (tenant_id, legal_entity_id)
    REFERENCES tenant.legal_entities (tenant_id, id);

ALTER TABLE fiscal.fiscal_documents
    ADD CONSTRAINT fk_fiscal_document_legal_entity
    FOREIGN KEY (tenant_id, legal_entity_id)
    REFERENCES tenant.legal_entities (tenant_id, id);

-- What the obligation opener scans every minute: completed orders with no sale
-- document. Without this it is a sequential scan of ordering.orders on every
-- pass, bounded only by the lookback window.
CREATE INDEX ix_orders_completed_for_fiscalization
    ON ordering.orders (closed_at)
    WHERE status = 'COMPLETED';
