-- ADR 0127 (status note of 2026-09-30): a tenant may end a module it bought
-- itself, and only that one.
--
-- commercial.tenant_modules said who started a module (started_by) and why
-- (start_reason) but not through which door. The two doors are different acts
-- with different authority: HorecaOS staff giving a tenant a module is a sale
-- HorecaOS made (ADR 0087, platform scope), while a tenant clicking "Add" on
-- its own catalogue is the tenant's own purchase (ADR 0127, tenant scope). A
-- tenant may undo the second; it must never be able to undo the first, so the
-- row has to be able to tell them apart. Inferring it from start_reason would
-- make an authorization decision depend on free text a staff member can type.
--
--   PLATFORM      -- given by HorecaOS staff (the default, and every row that
--                    predates the tenant door)
--   SELF_SERVICE  -- bought by the tenant from the operations console
--
-- The column has a default so a rollback of the application to the version that
-- does not write it still inserts valid rows: every such row is PLATFORM, which
-- is the safe side (the tenant cannot end it).
--
-- Backfill: the tenant door has recorded the one fixed, non-PII reason
-- 'Purchased from the operations console' since ADR 0127 (a merchant never types
-- it), so the rows it wrote are exactly the ones carrying that reason. The
-- platform-admin door takes a typed reason, so a staff member who typed this
-- exact sentence would be misfiled as a tenant purchase; that is the one way the
-- inference can be wrong, and it is accepted for the historical rows only. Rows
-- written from now on never rely on it: the application sets the column.
-- Idempotent: re-running changes nothing.
ALTER TABLE commercial.tenant_modules
    ADD COLUMN acquired_via varchar(16) NOT NULL DEFAULT 'PLATFORM';

ALTER TABLE commercial.tenant_modules
    ADD CONSTRAINT ck_tenant_module_acquired_via CHECK (acquired_via IN ('PLATFORM', 'SELF_SERVICE'));

UPDATE commercial.tenant_modules
   SET acquired_via = 'SELF_SERVICE'
 WHERE acquired_via = 'PLATFORM'
   AND start_reason = 'Purchased from the operations console';

COMMENT ON COLUMN commercial.tenant_modules.acquired_via IS
    'ADR 0127. Which door the module came through: PLATFORM (HorecaOS staff gave it) or SELF_SERVICE (the tenant bought it). Only a SELF_SERVICE module can be ended by the tenant.';

-- The table is already granted to horecaos_application (V0201); a new column
-- needs no grant of its own.
