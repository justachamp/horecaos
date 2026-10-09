-- ADR 0026 / ADR 0145: the approved endpoints for the Yandex geocoder and suggest services.
--
-- An adapter names an environment code and never a URL, which is where the server-side
-- request forgery path is closed -- at the model, not in a validator somebody can forget. The
-- consequence, spelled out in V0061 for the SMS gateway, is that an adapter without a row here
-- is an adapter nothing can be configured to reach: YandexGeocoderAdapter resolves both base
-- URLs by these codes and answers GEO_ENDPOINT_NOT_APPROVED without a network round trip when
-- a row is missing.
--
-- Two rows because Yandex serves the two operations from two hosts and a row holds one base
-- URL. 'GEOCODING' is already an accepted value of ck_provider_environment_category (V0013),
-- so no constraint changes; ProviderCategory.GEOCODING has existed since then, and the
-- tenant-facing connect form (ProviderInstallationController.connectFields) joins this table
-- through ConnectFieldCatalog, which declares no GEOCODING provider -- so these rows are
-- invisible to a tenant, which is intended: the geocoding credential is the platform's one
-- (ADR 0145 decision 4), not a tenant installation.
--
-- No table is created, so no GRANT is due: V0035 granted SELECT, and only SELECT, on
-- integration.provider_environments to horecaos_application, which is exactly what the read
-- needs.
--
-- ---------------------------------------------------------- one row each, not two
--
-- There is deliberately NO non-production sibling of either row. Yandex publishes no sandbox
-- host for the Geocoder or Suggest services (the provider document,
-- docs/providers/yandex-maps.md, records that gap), and a staging row with is_production =
-- false pointed at the production host would be an environment whose name says one thing while
-- every call is billed against the licence. Pre-production exercise runs against the fake
-- adapter (horecaos.geo.provider=fake, local profile) and against the recorded fixtures instead.
--
-- If a sandbox is ever documented it arrives as its own migration with its own host in
-- egress_allowlist, and this comment is what says the new row is new information rather than a
-- correction.

INSERT INTO integration.provider_environments
    (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
VALUES
    ('yandex_geocoder_production', 'GEOCODING', 'YANDEX_GEOCODER',
     'https://geocode-maps.yandex.ru/1.x', true, 'geocode-maps.yandex.ru',
     'ADR 0145. Yandex HTTP Geocoder (geocode and reverse geocode). No non-production sibling: no sandbox host is documented, so pre-production runs against the fake adapter rather than a staging row pointed at the billed host.'),
    ('yandex_suggest_production', 'GEOCODING', 'YANDEX_SUGGEST',
     'https://suggest-maps.yandex.ru/v1', true, 'suggest-maps.yandex.ru',
     'ADR 0145. Yandex HTTP Suggest (type-ahead). No non-production sibling for the same reason as the geocoder row.')
ON CONFLICT (code) DO NOTHING;
