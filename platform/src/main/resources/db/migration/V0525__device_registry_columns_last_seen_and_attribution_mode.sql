-- ADR 0155: what the class-neutral terminals registry shows of a device, and the one choice a manager
-- makes about it.
--
-- iam.device_principals is the ADR 0079 principal every device class shares, so these columns are the
-- registry's, not the kitchen's:
--
--   last_seen_at / last_seen_build   stamped by any authenticated request from a device principal, at most
--                                    once a minute per device per node (DeviceSeenRecorder): the signal is the
--                                    device actually working, not a ping, so no heartbeat endpoint exists.
--   attribution_mode                 how a person's unlock attributes an action taken at the screen.
--                                    DEVICE_ONLY is today's behaviour and the default for every existing and
--                                    every new device, so nothing a running kitchen does changes on the day
--                                    this is built. PERSON_OPTIONAL and PERSON_REQUIRED exist only for a class a
--                                    person unlocks (DevicePrincipalClass#unlockable), which the CHECK states
--                                    and restates per unlockable class.
--   auto_lock_seconds                the device's own idle limit; null means the tenant policy
--                                    (terminal.auto_lock_seconds), 30 to 3,600.
--
-- The tenant-bearing unique key a session or a kiosk row needs to reference a device's own location,
-- uq_device_principal_tenant_id_location (tenant_id, id, location_id), is V0501's; ADR 0154, 0155 and 0162
-- each name a key over those three columns and the first to merge adds it, so this migration finds it
-- present and adds nothing.

ALTER TABLE iam.device_principals
    ADD COLUMN attribution_mode varchar(16) NOT NULL DEFAULT 'DEVICE_ONLY',
    ADD COLUMN auto_lock_seconds integer,
    ADD COLUMN last_seen_at timestamptz,
    ADD COLUMN last_seen_build varchar(64);

ALTER TABLE iam.device_principals
    ADD CONSTRAINT ck_device_principal_attribution_mode
        CHECK (attribution_mode IN ('DEVICE_ONLY', 'PERSON_OPTIONAL', 'PERSON_REQUIRED')),
    -- A class nobody unlocks cannot be asked to attribute to a person. Restated, with the new class in the
    -- list, by the migration of whichever record makes the next class unlockable.
    ADD CONSTRAINT ck_device_principal_attribution_class
        CHECK (attribution_mode = 'DEVICE_ONLY' OR device_class IN ('KITCHEN_KDS')),
    ADD CONSTRAINT ck_device_principal_auto_lock
        CHECK (auto_lock_seconds IS NULL OR auto_lock_seconds BETWEEN 30 AND 3600);

COMMENT ON COLUMN iam.device_principals.last_seen_at IS
    'ADR 0155. The last authenticated request from this device, stamped at most once a minute per node. Not a heartbeat: a healthy idle device and a stuck one are told apart by how long since.';
COMMENT ON COLUMN iam.device_principals.attribution_mode IS
    'ADR 0155. DEVICE_ONLY (a session header is ignored and the device is the actor), PERSON_OPTIONAL (a live session, when present, is the actor) or PERSON_REQUIRED (a mutating request without one is refused).';
