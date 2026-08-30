-- Tenant boundaries the application assumed and the schema did not enforce.
--
-- Each of these was found by asking, of a query that filters on tenant_id,
-- whether anything would object if it stopped. In every case below the answer
-- was no: the row is reachable by an identifier alone, and the identifier is an
-- opaque UUID that travels through support tickets, exports and logs. Code has
-- been fixed to pass the tenant; this migration is the half that keeps the next
-- writer honest.

-- 1. Two tenants could not both publish a menu for "their" brand on the same
--    channel.
--
-- The partial unique index was keyed on (brand_id, channel), and catalog.publications
-- has no foreign key to tenant.brands -- every foreign key in V0016 is composite
-- within the catalog schema, so brand_id is whatever the writer supplied. So the
-- first tenant to publish against a given brand+channel took the slot globally,
-- and the second was refused a publication of their own menu. Widening the index
-- makes the constraint say what it always meant: one active publication per
-- brand per channel, within a tenant.
DROP INDEX IF EXISTS catalog.ux_publication_active;

CREATE UNIQUE INDEX ux_publication_active
    ON catalog.publications (tenant_id, brand_id, channel)
    WHERE status = 'PUBLISHED';

-- 2. A cart line could reference another tenant's variant.
--
-- fk_cart_line_variant referenced catalog.variants (id) alone, while the sibling
-- fk_cart_line_cart is correctly composite.
--
-- ordering.cart_lines has no brand_id, so the tenant is as far as this can go.
-- That still closes the crossing that matters: a variant belonging to a
-- different tenant.
--
-- The referenced pair needs its own unique constraint. V0016 declares
-- uq_variant_identity on (id, tenant_id, brand_id), and a foreign key must match
-- a unique constraint on exactly its own columns -- a three-column unique does
-- not satisfy a two-column reference. Since id is already the primary key this
-- adds no real uniqueness; it exists so the foreign key below has something to
-- point at.
ALTER TABLE catalog.variants
    ADD CONSTRAINT uq_variant_tenant_identity UNIQUE (id, tenant_id);

ALTER TABLE ordering.cart_lines
    DROP CONSTRAINT IF EXISTS fk_cart_line_variant;

ALTER TABLE ordering.cart_lines
    ADD CONSTRAINT fk_cart_line_variant FOREIGN KEY (variant_id, tenant_id)
        REFERENCES catalog.variants (id, tenant_id);

-- 3. A timetable's rules and exceptions could be rewritten from any tenant.
--
-- tenant.service_schedule_rules and tenant.service_schedule_exceptions are keyed
-- on schedule_id alone and carry no tenant_id, and their foreign keys are
-- single-column -- unlike tenant.location_service_bindings in the same migration,
-- which gets the composite form right. The write path now checks ownership before
-- touching either table (ServiceScheduleService.requireOwned), and these indexes
-- support that check plus the reads that resolve serviceability.
--
-- The columns are deliberately NOT added here. Backfilling tenant_id onto both
-- child tables means rewriting every row and widening two primary keys, and the
-- ownership check above already closes the hole. Doing it properly is a separate
-- change with its own rehearsal, not a rider on a security fix.
CREATE INDEX IF NOT EXISTS ix_service_schedules_tenant_brand
    ON tenant.service_schedules (tenant_id, brand_id, id);
