-- A draft brand or location made by mistake can now be deleted from the
-- control plane (TenantControlPlaneService#deleteBrand, #deleteLocation), and
-- the application role has never held DELETE on either table: every other
-- write to them is an INSERT or an UPDATE. Without this the delete succeeds in
-- a test that connects as the owner and is refused as horecaos_app in
-- production -- which is exactly what DatabasePrivilegeTests caught.
--
-- DELETE is safe to grant here because the database itself decides what may
-- go. Every foreign key into tenant.brands and tenant.locations is NO ACTION,
-- so a row anything still refers to cannot be deleted, and nothing is ever
-- removed along with one. The service adds the rest: only a DRAFT, only with
-- no locations and no staff access scoped to it.

GRANT DELETE ON tenant.brands TO horecaos_application;
GRANT DELETE ON tenant.locations TO horecaos_application;
