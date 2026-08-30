-- ADR 0016 follow-up. Two referential gaps found by review of V0016.
--
-- Both were places where a comment claimed an invariant the schema only half
-- enforced, which is worse than not claiming it: a reader stops checking.

-- 1. location_offerings named a location but had no foreign key to one.
--
-- V0016's comment said the composite key "enforces ADR 0016's rule that a
-- location may only offer a variant from its own brand". It enforced half of
-- that: the variant had to belong to the row's brand, but location_id could be
-- any UUID at all — including a location belonging to a different brand, or one
-- that does not exist. tenant.locations is keyed by (tenant_id, brand_id, id),
-- so referencing that triple closes it properly.
ALTER TABLE catalog.location_offerings
    ADD CONSTRAINT fk_offering_location
    FOREIGN KEY (tenant_id, brand_id, location_id)
    REFERENCES tenant.locations (tenant_id, brand_id, id);

-- 2. A category's parent could live in a different catalog.
--
-- fk_category_parent matched on (id, tenant_id, brand_id), which lets a category
-- in the seasonal menu be the parent of one in the main menu. The validator
-- walks ancestry within a single catalog, so such a parent reads as missing and
-- blocks publication with a confusing message — after the bad edit was already
-- accepted. Catching it at write time is both earlier and clearer.
ALTER TABLE catalog.categories
    ADD CONSTRAINT uq_category_within_catalog UNIQUE (id, tenant_id, brand_id, catalog_id);

ALTER TABLE catalog.categories
    DROP CONSTRAINT fk_category_parent;

ALTER TABLE catalog.categories
    ADD CONSTRAINT fk_category_parent
    FOREIGN KEY (parent_category_id, tenant_id, brand_id, catalog_id)
    REFERENCES catalog.categories (id, tenant_id, brand_id, catalog_id);
