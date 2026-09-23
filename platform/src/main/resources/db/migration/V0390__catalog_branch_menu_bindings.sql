-- Row 4.4a, part two: binding a named menu (V0389) to a branch.
--
-- The constraint this migration has to decide honestly -- nothing prior
-- names it -- is "how many menus may be live for one branch at once". The
-- answer taken here, matching catalog.channel_offering_exclusions' (V0020)
-- own two-partial-index shape for exactly the same "brand-wide vs. this
-- branch only" nullable-scope problem:
--
--   * channel_id NULL binds a menu as the branch's default, across every
--     sales channel the tenant runs there.
--   * channel_id NOT NULL binds a menu for one specific channel at that
--     branch only (e.g. a shorter delivery-only assortment on the aggregator
--     channel while the hall keeps the full default menu).
--
-- At most one binding per (branch, channel-scope) -- one default and, on top
-- of it, at most one override per channel -- is enforced by the database via
-- two partial unique indexes, not by application discipline: a race that
-- produced two live bindings for the same branch and channel would be
-- silently ambiguous about which menu a customer sees, exactly the failure
-- catalog.publications' own ux_publication_active guards against for
-- publications themselves.
--
-- What "publishes from the menu" means downstream (StorefrontCatalogQuery):
-- resolving a branch's offering map first tries a channel-specific binding,
-- then the branch's default binding, and only when neither exists falls back
-- to catalog.location_offerings exactly as today -- so a tenant that has
-- never bound a menu anywhere sees zero behaviour change, proven by the
-- unmodified StorefrontCatalogQueryTests staying green. The publication
-- itself (which products/categories/modifiers exist, and their content) is
-- untouched; only the per-branch "does this branch sell this variant, at
-- what default availability" resolution gains a second source.

CREATE TABLE catalog.branch_menu_bindings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- NULL binds the menu as this branch's default across every channel;
    -- naming a channel overrides the default for that channel alone. See
    -- this migration's own header for why both may coexist.
    channel_id uuid,

    menu_id uuid NOT NULL,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_branch_menu_binding_version CHECK (version >= 1),
    -- Unlike catalog.location_offerings (V0016), which predates the
    -- (tenant_id, id) uniqueness V0020 added to tenant.locations for exactly
    -- this purpose and was never backfilled, this table gets the real
    -- referential guarantee from day one: a binding cannot name a branch
    -- outside its own brand.
    CONSTRAINT fk_branch_menu_binding_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_branch_menu_binding_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id),
    CONSTRAINT fk_branch_menu_binding_menu FOREIGN KEY (menu_id, tenant_id, brand_id)
        REFERENCES catalog.menus (id, tenant_id, brand_id)
);

-- Two partial indexes rather than one, for the same reason
-- catalog.channel_offering_exclusions carries two: a nullable column in a
-- unique constraint does not deduplicate, so without these a branch's
-- default binding could be inserted twice, or a channel override could be,
-- and an "unbind" would remove only one of them while the other kept
-- publishing from a menu nobody could see any more.
CREATE UNIQUE INDEX ux_branch_menu_binding_channel
    ON catalog.branch_menu_bindings (location_id, channel_id)
    WHERE channel_id IS NOT NULL;

CREATE UNIQUE INDEX ux_branch_menu_binding_default
    ON catalog.branch_menu_bindings (location_id)
    WHERE channel_id IS NULL;

CREATE INDEX ix_branch_menu_bindings_menu
    ON catalog.branch_menu_bindings (tenant_id, brand_id, menu_id);

COMMENT ON TABLE catalog.branch_menu_bindings IS
    'Row 4.4a. Which named menu (catalog.menus) a branch publishes from -- at most one default (channel_id NULL) and at most one override per channel, per branch. A branch with no row here keeps ADR 0016''s unmodified location_offerings behaviour.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.branch_menu_bindings TO horecaos_application;
