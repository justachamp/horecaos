-- ADR 0056, schema one of several: inventory.
--
-- Chosen to go first because it is small (five tables) and self-contained --
-- grep across src/main/java found no module outside
-- uz.horecaos.platform.inventory reading or writing an inventory.* table
-- directly; every other module reaches it through InventoryReservationPort or
-- StockAvailabilityPort -- and because its one genuinely cross-tenant
-- statement, JdbcInventoryStore.expireReservations (a single UPDATE that
-- sweeps every tenant's stale holds), is a clean, representative worked
-- example of the exempt-role path rather than a special case invented for
-- this migration. InventoryService.expireStaleReservations now binds
-- horecaos_platform_bypass before calling it (see that class).
--
-- All five tables carry a NOT NULL tenant_id already (V0019), so the plain
-- tenant_id = <session GUC> predicate applies to every one of them without
-- modification -- none of them is the nullable-tenant_id, platform-or-tenant
-- shape that iam.roles and iam.grants are, which is why those two are not
-- here and are not simply "not yet done".
--
-- The application role's privileges are unchanged: V0019 already granted
-- horecaos_application SELECT, INSERT and UPDATE on every table in this
-- schema (and revoked UPDATE on the append-only movements ledger). This
-- migration changes which ROWS those verbs can reach, not which verbs are
-- granted, so it needs no GRANT block of its own.
--
-- Rollback, if this schema's rollout ever needs reversing before a later one
-- lands, is five DISABLE ROW LEVEL SECURITY statements in a forward
-- migration -- the policies are additive and carry no data, and this
-- migration's own comment says why that is a real property here and not
-- just an assertion: nothing above changed a grant, a column, or a row.

SELECT platform.enable_tenant_row_level_security('inventory.stock_items');
SELECT platform.enable_tenant_row_level_security('inventory.positions');
SELECT platform.enable_tenant_row_level_security('inventory.movements');
SELECT platform.enable_tenant_row_level_security('inventory.reservations');
SELECT platform.enable_tenant_row_level_security('inventory.reservation_lines');
