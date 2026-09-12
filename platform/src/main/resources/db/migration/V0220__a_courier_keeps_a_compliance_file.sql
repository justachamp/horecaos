-- IA 3.3, ADR 0042 over ADR 0029: the compliance file a self-employment
-- engagement assumes somebody is keeping.
--
-- Until now `fulfillment.couriers` held an id, a type, a Keycloak subject, a
-- display reference, one envelope-encrypted name and a status. That is enough
-- to dispatch somebody and not enough to answer an inspector: which document
-- was sighted, whose licence class matches the vehicle, which plate left the
-- branch, and who to telephone when the plate does not come back.
--
-- Every field below carries the same protection the name already has -- ADR
-- 0029 envelope encryption, bound to this row by AAD, revealed only under a
-- declared purpose that is audited (courier.pii.reveal). Two do not, and the
-- asymmetry is the same one `registration_valid_until` already states:
--
--   vehicle_fuel_type  an attribute of a vehicle, not of a person, and the
--                      question it exists for -- "which of my riders can take
--                      the far orders on one tank" -- is a GROUP BY that an
--                      encrypted column cannot answer.
--   photo_media_id     a reference into media.assets, which has its own
--                      visibility, its own retention and its own access
--                      policy. Encrypting a pointer protects nothing the
--                      pointed-at object is not already protecting, and would
--                      make the foreign key unenforceable.
--
-- No column here is nullable-with-a-default: an absent field means the file is
-- incomplete, which is a fact a manager needs to see rather than one the
-- schema should invent an empty string for.

ALTER TABLE fulfillment.couriers
    ADD COLUMN protected_passport text,
    ADD COLUMN protected_pinfl text,
    ADD COLUMN protected_driving_licence text,
    ADD COLUMN protected_vehicle_registration text,
    ADD COLUMN protected_vehicle_plate text,
    ADD COLUMN protected_address text,
    ADD COLUMN protected_emergency_contact text,
    ADD COLUMN protected_referral text,
    ADD COLUMN protected_notes text,
    ADD COLUMN vehicle_fuel_type varchar(16),
    ADD COLUMN photo_media_id uuid,
    ADD COLUMN compliance_updated_at timestamptz,
    ADD COLUMN compliance_updated_by varchar(128);

ALTER TABLE fulfillment.couriers
    -- Through the tenant-scoped unique (V0058), not through the primary key:
    -- a photograph is a named person's likeness, and a courier row pointing at
    -- another tenant's asset is the cross-tenant reference V0069 wrote the same
    -- rule for on the attestation evidence.
    ADD CONSTRAINT fk_courier_photo FOREIGN KEY (photo_media_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id),
    ADD CONSTRAINT ck_courier_fuel_type CHECK (
        vehicle_fuel_type IS NULL OR vehicle_fuel_type IN (
            'PETROL', 'DIESEL', 'GAS', 'ELECTRIC', 'HYBRID', 'NONE')),
    -- Stated as an equality rather than a disjunction, for the reason
    -- ck_engagement_verification_pair gives: "who last touched the file" with
    -- no instant, or an instant with nobody, is a provenance nobody can be
    -- asked about, and the mixed case is reachable through the three-valued
    -- hole an OR leaves open.
    ADD CONSTRAINT ck_courier_compliance_provenance CHECK (
        (compliance_updated_at IS NULL) = (compliance_updated_by IS NULL));

COMMENT ON COLUMN fulfillment.couriers.protected_passport IS
    'ADR 0029 PERSONAL_SENSITIVE. Passport series and number, envelope-encrypted and therefore not queryable. Revealed only under courier.pii.reveal with a declared purpose, audited.';
COMMENT ON COLUMN fulfillment.couriers.protected_pinfl IS
    'ADR 0029 PERSONAL_SENSITIVE. The fourteen-digit ПИНФЛ. Never on a list read: a roster that carried it would put a national identifier on every dispatcher''s screen to no purpose.';
COMMENT ON COLUMN fulfillment.couriers.protected_driving_licence IS
    'ADR 0029 PERSONAL_SENSITIVE. Licence number and class. The class is held inside the envelope with the number rather than beside it, because "which class" identifies the document as surely as the number does once the fleet is small.';
COMMENT ON COLUMN fulfillment.couriers.protected_vehicle_registration IS
    'ADR 0029 PERSONAL_SENSITIVE. The vehicle registration certificate number.';
COMMENT ON COLUMN fulfillment.couriers.protected_vehicle_plate IS
    'ADR 0029 PERSONAL. A plate is a public marking on a vehicle and a durable identifier of the person who rides it; it is held under the envelope for the second reason, not the first.';
COMMENT ON COLUMN fulfillment.couriers.protected_address IS
    'ADR 0029 PERSONAL. Where the courier lives -- a residential address, classified exactly as a customer''s delivery address is.';
COMMENT ON COLUMN fulfillment.couriers.protected_emergency_contact IS
    'ADR 0029 PERSONAL. A name and a telephone number belonging to a third party who never agreed to be in this system, which is why it is under the envelope and behind a purposeful reveal rather than on the roster.';
COMMENT ON COLUMN fulfillment.couriers.protected_referral IS
    'ADR 0029 PERSONAL. Who brought this courier in -- usually another courier, named.';
COMMENT ON COLUMN fulfillment.couriers.protected_notes IS
    'ADR 0029 PERSONAL. Free text a manager wrote about a person. Free text is the field that ends up holding a diagnosis, so it is protected as though it always does.';
COMMENT ON COLUMN fulfillment.couriers.vehicle_fuel_type IS
    'Held in clear, deliberately: an attribute of the vehicle rather than of the person, and the planning question it answers is an aggregate an encrypted column cannot serve.';
COMMENT ON COLUMN fulfillment.couriers.photo_media_id IS
    'A reference into media.assets, whose own visibility and retention govern the image. A pointer gains nothing from encryption and loses its foreign key.';
COMMENT ON COLUMN fulfillment.couriers.compliance_updated_by IS
    'The IAM subject that last recorded the file. Not a name -- ADR 0029 keeps a person out of a provenance column exactly as it keeps one out of an audit changed map.';

-- "Whose file is incomplete" is the manager's worklist on IA 3.3, and it is a
-- question about presence rather than about content, so it is answerable
-- without revealing anything.
CREATE INDEX ix_couriers_compliance_incomplete
    ON fulfillment.couriers (tenant_id)
    WHERE protected_passport IS NULL OR protected_pinfl IS NULL;
