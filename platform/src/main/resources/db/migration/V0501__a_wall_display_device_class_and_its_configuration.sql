-- ADR 0151: a wall-display device class, and the server-side configuration of one.
--
-- ADR 0079 gave the device primitive one class, KITCHEN_KDS, and said a VDU "would reuse this
-- primitive with its own role bundle, not reopen this one". A kitchen wall is that tablet with no
-- keyboard and a longer life, and until now it ran on whoever last typed a password on it. This adds
-- the second class and the one table a wall needs: the station it shows, and when it last read.

-- Both CHECKs are restated in full, never an ALTER that only adds a value (the V0145 precedent): a
-- dropped-and-recreated constraint has to carry every value that was already legal or a live row of
-- an existing class fails the rewrite.
ALTER TABLE iam.device_principals DROP CONSTRAINT ck_device_principal_class;
ALTER TABLE iam.device_principals
    ADD CONSTRAINT ck_device_principal_class CHECK (device_class IN ('KITCHEN_KDS', 'KITCHEN_VDU'));

ALTER TABLE iam.device_enrolment_requests DROP CONSTRAINT ck_device_enrolment_class;
ALTER TABLE iam.device_enrolment_requests
    ADD CONSTRAINT ck_device_enrolment_class CHECK (requested_class IN ('KITCHEN_KDS', 'KITCHEN_VDU'));

-- A device's own location, as a key a foreign key can name. Lets the database say what ADR 0151 left
-- to the service or to this: that a display's row sits at the device's own location, so a station of
-- another branch (kitchen.stations is keyed by its location below) can never be a wall's filter
-- through a row whose location is not the device's.
ALTER TABLE iam.device_principals
    ADD CONSTRAINT uq_device_principal_tenant_id_location UNIQUE (tenant_id, id, location_id);

CREATE TABLE kitchen.device_displays (
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    device_id uuid NOT NULL,
    -- The station this wall shows; null means the whole branch. Enforced for a device caller (the
    -- server applies it and ignores the request's), a convenience and not a boundary against the same
    -- branch's staff (ADR 0151, decision 4).
    station_id uuid,
    -- When the wall last read its projection. Written at most once a minute per device, by the read
    -- itself, so a manager can see on Kitchen -> Devices that a wall has gone dark.
    last_read_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_device_displays PRIMARY KEY (tenant_id, device_id),
    CONSTRAINT ck_device_display_version CHECK (version >= 1),
    CONSTRAINT fk_device_display_device FOREIGN KEY (tenant_id, device_id, location_id)
        REFERENCES iam.device_principals (tenant_id, id, location_id),
    -- A station of another location is refused here, by the composite key kitchen.stations already
    -- carries for exactly this (a ticket item's station is bound to its ticket's location the same way).
    CONSTRAINT fk_device_display_station FOREIGN KEY (station_id, tenant_id, location_id)
        REFERENCES kitchen.stations (id, tenant_id, location_id)
);

CREATE INDEX ix_device_display_location ON kitchen.device_displays (tenant_id, location_id);

COMMENT ON TABLE kitchen.device_displays IS
    'ADR 0151. The server-side configuration of one KITCHEN_VDU device: the station it shows (null = the whole branch) and when it last read. One row per wall, created when the wall is approved; a touch KDS has none.';

GRANT SELECT, INSERT, UPDATE ON kitchen.device_displays TO horecaos_application;
