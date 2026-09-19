-- The Telegram Bot API joins the approved-endpoint catalogue (ADR 0026, ADR 0065).
--
-- `POST .../integrations` refuses an installation whose `environmentCode` is not
-- a row of `integration.provider_environments` for its category: "the
-- environment is chosen from an approved catalogue; a tenant never supplies a
-- URL, which closes the request-forgery path at the model". V0099-V0106 built
-- the Telegram installation, binding, channel and tracked-message model, and
-- every test of it seeds its own environment row pointed at a fake bot -- but
-- no migration ever approved the real endpoint. So on a deployed host the
-- catalogue held Clopos, the SMS gateway and three analytics rows, and
-- "Connect provider > Telegram Bot API" could not succeed with ANY code typed
-- into the environment field: pre-production, 2026-09-19, the secret door
-- stored the bot token and the installation call that follows answered 400
-- "Unknown provider environment for this category".
--
-- `telegram-prod` is the code docs/runbooks/connect-click-payme-sandbox.md has
-- told operators to type since that runbook was written; this row is what makes
-- the runbook true. `base_url` has no trailing slash and no `/bot`:
-- TelegramBotApiClient appends `/bot<token>/<method>` itself.
--
-- One row, production only, on purpose. Telegram documents a test environment,
-- but it is a separate account universe (test DCs, separate BotFather, bots
-- addressed as `/bot<token>/test/<method>`), not a second host -- a row for it
-- would need a different URL shape the client does not build, and nobody has
-- asked for it. Pre-production uses a real bot against this same host, exactly
-- as the SMS gateway's V0061 comment describes for its own provider.
--
-- Reference data with no tenant_id: the catalogue is platform-owned and shared
-- by every tenant, like every other row in this table.

INSERT INTO integration.provider_environments
    (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
VALUES
    ('telegram-prod', 'NOTIFICATION', 'TELEGRAM_BOT_API',
     'https://api.telegram.org', true, 'api.telegram.org',
     'The Bot API host. The client appends /bot<token>/<method>, so the URL carries neither. No test-environment sibling: Telegram''s test DCs are a separate account universe addressed by a different path shape, not a second host.')
ON CONFLICT (code) DO NOTHING;
