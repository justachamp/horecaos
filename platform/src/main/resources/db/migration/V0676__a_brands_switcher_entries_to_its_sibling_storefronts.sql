-- ADR 0157 Release 4 (waived for this part by ADR 0183 D17): the brand switcher. A tenant that runs two brands shows each
-- storefront's visitor the other brands' storefronts, as a small strip in the header: the target brand's name and logo,
-- linking to the verified hostname of the website channel that sells it.
--
-- An entry names a *channel*, not a hostname, so a hostname that is released or un-verified stops being linkable on the next
-- read without anyone editing the entry, exactly as the external-link allowlist re-checks its hosts (V0534). Nothing here is
-- shown until an entry exists, so a tenant with one brand is served the document it was served before.
--
-- No person is named here: an entry is a tenant's own decision about its own brands.

CREATE TABLE content.brand_switcher_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- The brand whose storefront shows the switcher.
    brand_id uuid NOT NULL,
    -- The brand it offers a way to.
    target_brand_id uuid NOT NULL,
    -- The WEB channel whose *verified* hostname the entry links to.
    channel_id uuid NOT NULL,
    sort_key integer NOT NULL DEFAULT 0,
    state varchar(8) NOT NULL DEFAULT 'ACTIVE',

    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT uq_brand_switcher_entry_identity UNIQUE (id, tenant_id, brand_id),
    CONSTRAINT uq_brand_switcher_entry_target UNIQUE (tenant_id, brand_id, target_brand_id, channel_id),
    CONSTRAINT fk_brand_switcher_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_brand_switcher_target FOREIGN KEY (tenant_id, target_brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_brand_switcher_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id),
    CONSTRAINT ck_brand_switcher_not_itself CHECK (brand_id <> target_brand_id),
    CONSTRAINT ck_brand_switcher_state CHECK (state IN ('ACTIVE', 'HIDDEN')),
    CONSTRAINT ck_brand_switcher_sort_key CHECK (sort_key BETWEEN 0 AND 100000),
    CONSTRAINT ck_brand_switcher_version CHECK (version >= 1)
);

CREATE INDEX ix_brand_switcher_entries_brand ON content.brand_switcher_entries (tenant_id, brand_id, sort_key, id);

COMMENT ON TABLE content.brand_switcher_entries IS
    'ADR 0157 Release 4 / ADR 0183 D17: a way from one brand''s storefront to a sibling brand''s, linking to a WEB channel''s verified hostname (checked again on every read). Shown only while ACTIVE, the target brand is ACTIVE and the hostname is verified.';

GRANT SELECT, INSERT, UPDATE, DELETE ON content.brand_switcher_entries TO horecaos_application;
