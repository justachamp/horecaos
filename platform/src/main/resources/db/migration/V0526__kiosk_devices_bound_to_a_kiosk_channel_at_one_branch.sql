-- ADR 0162: a kiosk is a device principal (V0524's class) bound to one KIOSK sales channel at the one branch
-- the principal is enrolled at, and the database says so.
--
-- The V0046 lesson applies twice: a foreign key references a unique constraint on exactly its own columns,
-- and every one of these carries tenant_id (a reference between two tenant-scoped tables that leaves it out
-- fails TenantScopedReferenceCatalogTests, whose allowlist is empty and stays so). The target tables' own keys
-- do not qualify: the channel table's tenant-bearing unique is (tenant_id, id), which cannot carry the type,
-- and the channel-location table's primary key is (channel_id, location_id), which carries no tenant. So
-- this adds uq_sales_channel_type to the channel table and uq_sales_channel_location_identity to the
-- channel-location table; the third key, the device's own location, is V0501's
-- uq_device_principal_tenant_id_location (tenant_id, id, location_id).

ALTER TABLE tenant.sales_channels
    ADD CONSTRAINT uq_sales_channel_type UNIQUE (tenant_id, id, system_type);

ALTER TABLE tenant.sales_channel_locations
    ADD CONSTRAINT uq_sales_channel_location_identity UNIQUE (tenant_id, channel_id, location_id);

CREATE TABLE tenant.kiosk_devices (
    tenant_id uuid NOT NULL,
    device_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    -- Always 'KIOSK'. It is a column so that a foreign key can carry it into the channel table's
    -- (tenant_id, id, system_type) key: a kiosk cannot be bound to a channel of another type, and the
    -- database is what refuses it, not the service.
    channel_type varchar(16) NOT NULL DEFAULT 'KIOSK',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_kiosk_devices PRIMARY KEY (device_id),
    CONSTRAINT ck_kiosk_device_channel_type CHECK (channel_type = 'KIOSK'),
    CONSTRAINT ck_kiosk_device_version CHECK (version >= 1),
    CONSTRAINT fk_kiosk_device_branch FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- The kiosk's location is its principal's own location.
    CONSTRAINT fk_kiosk_device_principal FOREIGN KEY (tenant_id, device_id, location_id)
        REFERENCES iam.device_principals (tenant_id, id, location_id),
    CONSTRAINT fk_kiosk_device_channel FOREIGN KEY (tenant_id, channel_id, channel_type)
        REFERENCES tenant.sales_channels (tenant_id, id, system_type),
    -- The channel sells at the kiosk's location: a binding row exists. (That the binding is ACTIVE is the
    -- service's check, repeated at every rebind.)
    CONSTRAINT fk_kiosk_device_channel_location FOREIGN KEY (tenant_id, channel_id, location_id)
        REFERENCES tenant.sales_channel_locations (tenant_id, channel_id, location_id)
);

CREATE INDEX ix_kiosk_device_location ON tenant.kiosk_devices (tenant_id, location_id);
CREATE INDEX ix_kiosk_device_channel ON tenant.kiosk_devices (tenant_id, channel_id);

COMMENT ON TABLE tenant.kiosk_devices IS
    'ADR 0162. One row per paired kiosk: its device principal, its branch, and the one KIOSK sales channel it sells through. Inserted in the approval transaction beside the principal; never deleted (a revoked kiosk is history).';

-- No delete: a revoked kiosk is history. Granted to the role the application connects as (V0035).
GRANT SELECT, INSERT, UPDATE ON tenant.kiosk_devices TO horecaos_application;
