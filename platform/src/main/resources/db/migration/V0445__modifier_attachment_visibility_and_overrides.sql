-- ADR 0136, modifier depth: the join rows between a modifier group and the
-- product or variant that offers it learn to say more than "attached".
--
-- Until now product_modifier_groups/variant_modifier_groups were
-- (product_id|variant_id, modifier_group_id, sort_order) and nothing else, so
-- every attachment used the shared group's own is_required/min/max wholesale,
-- there was no way to attach a group a customer never sees, and
-- variant_modifier_groups had no writer at all. The columns below are the three
-- targeted changes the record decides; none touches modifier_groups itself, so
-- a second product attaching the same group is completely unaffected by the
-- first product's override (catalog.md: "editing one here would silently change
-- another product, and that is the mistake to design out").
--
--   visibility                 VISIBLE | HIDDEN_AUTO_SELECT. A hidden group is
--                              selected server-side at quote time and never
--                              rendered to the customer: a delivery box that
--                              always reaches the receipt on a DELIVERY order.
--   applicable_fulfillment_modes
--                              Which fulfilment modes a HIDDEN_AUTO_SELECT group
--                              applies to, on location_offerings' own
--                              vocabulary. Null means every mode. Refused on a
--                              VISIBLE group: nothing reads it there, and a
--                              column that silently does nothing is what the
--                              record refuses to fake with a free-text flag.
--   *_override                 Null means "use the group's own value" -- the
--                              fallback the gap-map row names. A set value is
--                              this one attachment being stricter or looser.
--   version                    ADR 0031's expected version for the editor's
--                              If-Match; an attachment is an aggregate of its
--                              own as soon as it carries state.
--
-- Cross-table consistency (the effective range still satisfies min <= max, a
-- hidden group has exactly one active option) cannot be a CHECK. The authoring
-- service refuses what it can see at write time and CatalogValidator re-checks
-- at publication, because the shared group can move under an override later.

ALTER TABLE catalog.product_modifier_groups
    ADD COLUMN visibility varchar(16) NOT NULL DEFAULT 'VISIBLE',
    ADD COLUMN applicable_fulfillment_modes text[],
    ADD COLUMN required_override boolean,
    ADD COLUMN minimum_selections_override integer,
    ADD COLUMN maximum_selections_override integer,
    ADD COLUMN version integer NOT NULL DEFAULT 1;

ALTER TABLE catalog.variant_modifier_groups
    ADD COLUMN visibility varchar(16) NOT NULL DEFAULT 'VISIBLE',
    ADD COLUMN applicable_fulfillment_modes text[],
    ADD COLUMN required_override boolean,
    ADD COLUMN minimum_selections_override integer,
    ADD COLUMN maximum_selections_override integer,
    ADD COLUMN version integer NOT NULL DEFAULT 1;

ALTER TABLE catalog.product_modifier_groups
    ADD CONSTRAINT ck_pmg_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN_AUTO_SELECT')),
    ADD CONSTRAINT ck_pmg_modes CHECK (
        applicable_fulfillment_modes IS NULL
        OR (
            visibility = 'HIDDEN_AUTO_SELECT'
            AND cardinality(applicable_fulfillment_modes) >= 1
            AND applicable_fulfillment_modes <@ ARRAY['DELIVERY', 'PICKUP', 'DINE_IN']::text[]
        )
    ),
    ADD CONSTRAINT ck_pmg_override_range CHECK (
        (minimum_selections_override IS NULL OR minimum_selections_override >= 0)
        AND (maximum_selections_override IS NULL OR maximum_selections_override >= 1)
        AND (
            minimum_selections_override IS NULL
            OR maximum_selections_override IS NULL
            OR minimum_selections_override <= maximum_selections_override
        )
    ),
    -- A required group needs at least one selection, as ck_modifier_group_required
    -- says of the group itself.
    ADD CONSTRAINT ck_pmg_override_required CHECK (
        required_override IS DISTINCT FROM true
        OR minimum_selections_override IS NULL
        OR minimum_selections_override >= 1
    ),
    -- A hidden group is always exactly one auto-selected option, so a selection
    -- range on it would be a number nothing reads.
    ADD CONSTRAINT ck_pmg_hidden_has_no_range CHECK (
        visibility = 'VISIBLE'
        OR (minimum_selections_override IS NULL AND maximum_selections_override IS NULL)
    );

ALTER TABLE catalog.variant_modifier_groups
    ADD CONSTRAINT ck_vmg_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN_AUTO_SELECT')),
    ADD CONSTRAINT ck_vmg_modes CHECK (
        applicable_fulfillment_modes IS NULL
        OR (
            visibility = 'HIDDEN_AUTO_SELECT'
            AND cardinality(applicable_fulfillment_modes) >= 1
            AND applicable_fulfillment_modes <@ ARRAY['DELIVERY', 'PICKUP', 'DINE_IN']::text[]
        )
    ),
    ADD CONSTRAINT ck_vmg_override_range CHECK (
        (minimum_selections_override IS NULL OR minimum_selections_override >= 0)
        AND (maximum_selections_override IS NULL OR maximum_selections_override >= 1)
        AND (
            minimum_selections_override IS NULL
            OR maximum_selections_override IS NULL
            OR minimum_selections_override <= maximum_selections_override
        )
    ),
    ADD CONSTRAINT ck_vmg_override_required CHECK (
        required_override IS DISTINCT FROM true
        OR minimum_selections_override IS NULL
        OR minimum_selections_override >= 1
    ),
    ADD CONSTRAINT ck_vmg_hidden_has_no_range CHECK (
        visibility = 'VISIBLE'
        OR (minimum_selections_override IS NULL AND maximum_selections_override IS NULL)
    );

COMMENT ON COLUMN catalog.product_modifier_groups.visibility IS
    'ADR 0136. VISIBLE is the customer-facing choice every attachment was before this column; HIDDEN_AUTO_SELECT is applied server-side at quote time for the fulfilment modes in applicable_fulfillment_modes and is never published as a customer choice.';
COMMENT ON COLUMN catalog.variant_modifier_groups.visibility IS
    'ADR 0136. See catalog.product_modifier_groups.visibility. A variant-level attachment of the same group wins over the product-level one.';
COMMENT ON COLUMN catalog.product_modifier_groups.applicable_fulfillment_modes IS
    'ADR 0136. Null = every mode; else a non-empty subset of DELIVERY, PICKUP, DINE_IN. Only a HIDDEN_AUTO_SELECT attachment may carry it.';
COMMENT ON COLUMN catalog.variant_modifier_groups.applicable_fulfillment_modes IS
    'ADR 0136. See catalog.product_modifier_groups.applicable_fulfillment_modes.';
COMMENT ON COLUMN catalog.product_modifier_groups.required_override IS
    'ADR 0136. Null falls back to modifier_groups.is_required. Scoped to this one product and group; the shared group is never edited from here.';
COMMENT ON COLUMN catalog.variant_modifier_groups.required_override IS
    'ADR 0136. See catalog.product_modifier_groups.required_override.';

-- No GRANT: both tables were granted in V0016, and a table-level privilege
-- covers columns added later. The application role already reads and writes
-- these rows.
