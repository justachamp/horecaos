-- ADR 0095, ADR 0026: the approved endpoints of the card provider HorecaOS bills its tenants through.
--
-- ADR 0026 closes the request-forgery path at the model: nothing is called at a URL somebody typed, and an
-- installation names a row of this catalogue. HorecaOS's own card merchant account is
-- commercial.platform_card_installations, whose environment must be a PAYMENT row approved for the
-- installation's provider type, so the adapter that speaks to Click (ClickCardProviderAdapter, provider type
-- CLICK_CARD) has nothing to name until these rows exist. That was developer work the connect-card-merchant-
-- account runbook listed, and it is done here.
--
-- One host, two rows, and the reason is a fact about the documentation rather than a convenience. Click's
-- merchant API documentation (read 2026-10-14, docs/providers/click-card-token.md) names a single host,
-- api.click.uz, and publishes no separate sandbox host: a test is a test service on that host. The two rows
-- therefore differ only in is_production, which is what lets the control plane say which kind of account an
-- installation is and lets a sandbox walk be declared apart from the live account. If Click names a sandbox
-- host when the account is opened, a later migration moves the sandbox row's base_url; nothing else changes.
--
-- The base URL is Click's /v2/merchant root, as the payment adapter already uses it, so the card-token paths
-- (/card_token/request, /card_token/verify, /card_token/payment, /card_token/:service_id/:card_token) and the
-- status paths (/payment/status..., /payment/status_by_mti...) append to it.
--
-- No installation is seeded. An installation is HorecaOS's declaration that it holds an account, and it
-- needs a secret reference to a credential that does not exist yet (ck_platform_card_installation_secret);
-- seeding one would be seeding a claim. The runbook says exactly which request declares it.
INSERT INTO integration.provider_environments
    (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
VALUES
    ('click_card_production', 'PAYMENT', 'CLICK_CARD',
     'https://api.click.uz/v2/merchant', true, 'api.click.uz',
     'ADR 0095. Click merchant API, card-token calls, for HorecaOS''s own billing account (a platform installation, never a tenant''s).'),
    ('click_card_sandbox', 'PAYMENT', 'CLICK_CARD',
     'https://api.click.uz/v2/merchant', false, 'api.click.uz',
     'ADR 0095. Click publishes no separate sandbox host in its merchant API documentation; a sandbox walk uses a test service id on this host. Replace the base_url by a migration if Click names a sandbox host.')
ON CONFLICT (code) DO NOTHING;
