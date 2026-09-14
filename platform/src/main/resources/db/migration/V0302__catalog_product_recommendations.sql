-- Row 4.2h (P47): cross-sell / recommended products, new to the gap map and
-- unbuilt at every layer before this migration -- no catalog table, no read
-- model, no frontend reference anywhere (`grep -ari recommend
-- frontend/operations/src` returned nothing).
--
-- Directional by construction: source_product_id names the product being
-- edited, target_variant_id names the sellable unit offered alongside it.
-- "Chips with this burger" is not "this burger with chips" -- a symmetric
-- link table would make attaching one direction silently attach the other,
-- which is not what an operator asking the console to suggest a side asked
-- for. Nothing here is a combo: a combo groups items into one priceable,
-- orderable unit (row 4.2a, deferred behind an ADR 0016 amendment) and this
-- table changes no price and creates no orderable line -- it is a pointer a
-- storefront or aggregator projection may later render as "add-on"
-- suggestions, nothing more.
--
-- Keyed by product on the source side (the screen this is authored from) and
-- by variant on the target side (the sellable, priceable, stoppable unit --
-- exactly the grain catalog.location_offerings and inventory.positions both
-- use, which is what IA 4.2's own filter -- active + in-menu + not-stopped --
-- needs to join against at read time).
CREATE TABLE catalog.product_recommendations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    source_product_id uuid NOT NULL,
    target_variant_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_product_recommendation_source FOREIGN KEY (source_product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT fk_product_recommendation_target FOREIGN KEY (target_variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    -- One row per (source, target) pair -- attaching an already-attached
    -- target is a reorder (see JdbcCatalogStore#upsertRecommendation's
    -- ON CONFLICT), never a second row.
    CONSTRAINT uq_product_recommendation UNIQUE (source_product_id, target_variant_id)
);

CREATE INDEX ix_product_recommendations_source
    ON catalog.product_recommendations (tenant_id, source_product_id, sort_order);

COMMENT ON TABLE catalog.product_recommendations IS
    'Row 4.2h: directional cross-sell, product -> variant. Never symmetric, never a combo. The active + in-menu + not-stopped filter is resolved by the read, not by pruning this table.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.product_recommendations TO horecaos_application;
