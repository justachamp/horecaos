-- 10.10d (operations-gap-map): a chain cannot tag its branches today — no
-- table, no endpoint, no screen. "24/7", "has parking", "airport" are the
-- examples settings.md names; this is the tenant-owned registry those tags
-- come from. Delever ships the page empty, per the same doc.
--
-- One registry per tenant, not a platform-shared list: a tag's wording and
-- relevance is the chain's own, unlike order_outcome_reasons' system category
-- which cross-tenant reporting groups by. Archived rather than deleted, same
-- reasoning V0029 recorded for outcome reasons: a tag already assigned to a
-- branch must keep resolving, and archiving frees the code for reuse.
CREATE TABLE tenant.branch_tags (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Short, stable, chosen by the author. Never shown to a customer.
    code varchar(32) NOT NULL,
    display_name varchar(80) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_branch_tags_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT uq_branch_tags_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_branch_tags_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_branch_tags_code CHECK (code ~ '^[a-z0-9][a-z0-9_-]{0,31}$'),
    CONSTRAINT ck_branch_tags_display_name CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_branch_tags_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX ix_branch_tags_tenant_active ON tenant.branch_tags (tenant_id) WHERE status = 'ACTIVE';

COMMENT ON TABLE tenant.branch_tags IS
    '10.10d. A chain''s own registry of branch tags (24/7, has parking, airport). Archived, never deleted, so an already-tagged branch keeps resolving.';

GRANT SELECT, INSERT, UPDATE ON tenant.branch_tags TO horecaos_application;
