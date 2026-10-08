-- ADR 0147 (road distance and routing provider): a routing engine joins the
-- channels as a provider category, and the fee evidence learns which map measured
-- a road distance.
--
-- 1. `ProviderCategory` names POS, PAYMENT, DELIVERY, MARKETPLACE, NOTIFICATION,
--    GEOCODING, VOICE, ANALYTICS and OTHER, and has no ROUTING value, so a self-hosted
--    routing engine has nowhere to be installed against a tenant and a ROAD tariff
--    can only name an installation that is not one. This mirrors V0145's addition of
--    VOICE and V0250's of ANALYTICS exactly: an ordinary ADR 0026 installation, just
--    a category the CHECK constraints have not named yet. Both constraints are
--    restated in full rather than narrowed by an ALTER that only adds a value -- a
--    dropped-and-recreated constraint has to carry every value that was already legal
--    or a live row on an existing category fails the rewrite.
ALTER TABLE integration.provider_environments
    DROP CONSTRAINT ck_provider_environment_category;
ALTER TABLE integration.provider_environments
    ADD CONSTRAINT ck_provider_environment_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'MARKETPLACE',
                              'NOTIFICATION', 'GEOCODING', 'OTHER', 'VOICE', 'ANALYTICS',
                              'ROUTING'));

ALTER TABLE integration.installations
    DROP CONSTRAINT ck_installation_category;
ALTER TABLE integration.installations
    ADD CONSTRAINT ck_installation_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'MARKETPLACE',
                              'NOTIFICATION', 'GEOCODING', 'OTHER', 'VOICE', 'ANALYTICS',
                              'ROUTING'));

-- 2. The approved endpoint. An installation names an environment code and never a
--    URL (ADR 0026), which is where the request-forgery path is closed -- so an
--    adapter without a row here is an adapter nothing can be configured to reach.
--    `osrm_internal` is the platform's own engine on the private network: the compose
--    service `osrm` (deploy/compose.production.yml), no published port, no
--    credential. The URL is therefore the same on every deployment that runs the
--    engine, and an environment without it simply keeps `horecaos.routing.osrm.enabled`
--    off, which makes every ROAD fee say RADIUS_FALLBACK (docs/routes/osrm-road-distance.md).
--
--    No table is created here, so no new GRANT is due: V0035 granted SELECT on
--    integration.provider_environments to horecaos_application, which is what the
--    adapter's join needs.
INSERT INTO integration.provider_environments
    (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
VALUES
    ('osrm_internal', 'ROUTING', 'OSRM', 'http://osrm:5000', true, 'osrm',
     'ADR 0147: the platform''s own OSRM engine, reached over the internal network only. Keyless; the dataset it answers from is the image tag deployed beside it, recorded on every fee it measures.')
ON CONFLICT (code) DO NOTHING;

-- 3. What measured a ROAD distance. `routing_provider` has existed since V0025; the
--    metres alone cannot be reproduced after a monthly dataset refresh, so the
--    dataset version and the engine's free-flow travel time are stored beside them.
--    Both are null for RADIUS and RADIUS_FALLBACK fees, because nothing routed those.
--    Added to an existing table, so V0035's table-level grants already cover them.
ALTER TABLE fulfillment.delivery_fee_resolutions
    ADD COLUMN routing_seconds integer,
    ADD COLUMN routing_dataset_version varchar(32);

-- An equivalence, written without the "(a IS NULL AND b IS NULL) OR (...)" form that
-- leaves a three-valued-logic hole (see V0025's own warning): the two columns exist
-- exactly when a routing engine measured the distance, and a fallback or a radius fee
-- claiming a dataset would be a fee attributed to a map that never saw it.
ALTER TABLE fulfillment.delivery_fee_resolutions
    ADD CONSTRAINT ck_fee_resolution_routing_evidence CHECK (
        (routing_dataset_version IS NOT NULL) = (routing_seconds IS NOT NULL)
        AND (routing_dataset_version IS NOT NULL) = (distance_source IS NOT DISTINCT FROM 'ROAD')
        AND (routing_seconds IS NULL OR routing_seconds >= 0)
    );

COMMENT ON COLUMN fulfillment.delivery_fee_resolutions.routing_dataset_version IS
    'ADR 0147. The routing dataset (its image tag, normally the extract date) that measured distance_meters. Set exactly when distance_source = ROAD; explains why two quotes for one address disagree across a dataset refresh.';
COMMENT ON COLUMN fulfillment.delivery_fee_resolutions.routing_seconds IS
    'ADR 0147. The routing engine''s free-flow travel time for the same route, in seconds. Free-flow means an empty road: it says nothing about rush hour.';
