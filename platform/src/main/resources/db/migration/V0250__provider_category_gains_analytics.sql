-- ADR 0106 (wave P35): analytics joins the channels as a provider category.
--
-- `ProviderCategory` names POS, PAYMENT, DELIVERY, MARKETPLACE, NOTIFICATION,
-- GEOCODING, VOICE and OTHER, and has no ANALYTICS value — so a GTM container
-- id, a GA4 measurement id or a Search Console verification token has nowhere
-- to be stored against a tenant, and the storefront's only counter is a
-- commented-out, hard-coded, platform-wide Yandex Metrika id. This mirrors
-- V0145's own addition of VOICE exactly: an ordinary ADR 0026 installation,
-- just a category the CHECK constraints have not named yet, so both
-- constraints are restated in full rather than narrowed by an ALTER that only
-- adds a value — the dropped-and-recreated constraint has to carry every value
-- that was already legal or a live row on an existing category fails the
-- rewrite.
ALTER TABLE integration.provider_environments
    DROP CONSTRAINT ck_provider_environment_category;
ALTER TABLE integration.provider_environments
    ADD CONSTRAINT ck_provider_environment_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'MARKETPLACE',
                              'NOTIFICATION', 'GEOCODING', 'OTHER', 'VOICE', 'ANALYTICS'));

ALTER TABLE integration.installations
    DROP CONSTRAINT ck_installation_category;
ALTER TABLE integration.installations
    ADD CONSTRAINT ck_installation_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'MARKETPLACE',
                              'NOTIFICATION', 'GEOCODING', 'OTHER', 'VOICE', 'ANALYTICS'));

-- Three approved environments, one per analytics provider type. Necessary only
-- to satisfy the existing approved-environment model (ADR 0026): the platform
-- never calls out to any of these itself, so `egress_allowlist` is empty and
-- `base_url` names where the identifier ultimately concerns rather than an
-- address this backend ever dials. The actual "call" is the customer's own
-- browser loading a script the storefront serves, driven by the non-secret
-- fields this wave's ConnectFieldCatalog declares.
INSERT INTO integration.provider_environments
    (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
VALUES
    ('google-tag-manager-production', 'ANALYTICS', 'GOOGLE_TAG_MANAGER',
     'https://www.googletagmanager.com', true, '',
     'ADR 0106: no server-side egress. The storefront loads gtm.js client-side; base_url is the identifier''s own home, not an address this backend dials.'),
    ('google-analytics-4-production', 'ANALYTICS', 'GOOGLE_ANALYTICS_4',
     'https://analytics.google.com', true, '',
     'ADR 0106: no server-side egress. gtag.js runs in the customer''s browser against the tenant''s own GA4 property.'),
    ('google-search-console-production', 'ANALYTICS', 'GOOGLE_SEARCH_CONSOLE',
     'https://search.google.com/search-console', true, '',
     'ADR 0106: verification is a meta tag or DNS record the storefront/tenant sets, not a server-side call.')
ON CONFLICT (code) DO NOTHING;
