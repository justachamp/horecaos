-- ADR 0183 (D27, D17): the fields settings.md 10.5 lists for a TELEGRAM channel, and the storefront's branch order.
--
-- The tenant's Telegram Mini App lives at the brand's *verified storefront hostname* (ADR 0183 D27, closing ADR 0075's
-- open input), so nothing here stores a URL a tenant typed. What a tenant does choose per Telegram channel is
-- recorded below: whether the menu button opens the Mini App, the short name BotFather gave it (only the holder of
-- the bot's @BotFather account can run /newapp, and Telegram has no API for it, so the platform can only be told),
-- the label on the menu button and the welcome text a customer is greeted with, each in the languages the platform
-- offers, and whether the tenant's ADR 0068 terms page is offered as the public offer.
--
-- The channel's own `version` (tenant.sales_channels) is the optimistic-lock token for a write to either table, like
-- every other facet of Channel setup (V0403, V0404): the write bumps it, so a stale console cannot overwrite a newer one.

CREATE TABLE tenant.telegram_channel_settings (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    -- The brand whose storefront the Mini App opens. A channel is a tenant-level object (V0020); a Mini App is a
    -- storefront, and a storefront deployment serves exactly one brand, so the brand is chosen here and the hostname is
    -- derived from it. Null until a Mini App is configured.
    brand_id uuid,
    mini_app_enabled boolean NOT NULL DEFAULT false,
    -- The BotFather /newapp short name: the last segment of https://t.me/<bot>/<short name>. Telegram's own rule is
    -- letters, digits and underscores; the length bound is wider than Telegram's so a rule change there does not
    -- strand a stored name.
    mini_app_short_name varchar(64),
    -- Offer the tenant's published ADR 0068 terms page (https://<hostname>/terms) in the welcome message.
    public_offer_link boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, channel_id),
    CONSTRAINT fk_telegram_settings_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_telegram_settings_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_telegram_short_name CHECK (
        mini_app_short_name IS NULL OR mini_app_short_name ~ '^[A-Za-z0-9_]{3,64}$'),
    -- A Mini App with no brand has no storefront to open.
    CONSTRAINT ck_telegram_mini_app_has_brand CHECK (NOT mini_app_enabled OR brand_id IS NOT NULL)
);

COMMENT ON TABLE tenant.telegram_channel_settings IS
    'ADR 0183 D27: a TELEGRAM sales channel''s own settings. No URL is stored: the Mini App address is derived from the brand''s verified storefront hostname (tenant.channel_hostnames).';

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.telegram_channel_settings TO horecaos_application;

CREATE TABLE tenant.telegram_channel_texts (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    -- MENU_BUTTON_LABEL: the text on the bot's menu button (Telegram allows 64 characters).
    -- WELCOME: what a customer is answered with on /start.
    kind varchar(24) NOT NULL,
    -- A tag from the language registry (ADR 0149), validated by the service because the registry is data.
    locale varchar(16) NOT NULL,
    body varchar(1000) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, channel_id, kind, locale),
    CONSTRAINT fk_telegram_text_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_telegram_text_kind CHECK (kind IN ('MENU_BUTTON_LABEL', 'WELCOME')),
    CONSTRAINT ck_telegram_text_body CHECK (
        length(btrim(body)) > 0 AND (kind <> 'MENU_BUTTON_LABEL' OR length(body) <= 64))
);

COMMENT ON TABLE tenant.telegram_channel_texts IS
    'ADR 0183 D27: a TELEGRAM channel''s menu button label and welcome text, one row per kind and language. Text the tenant wrote for its customers; never personal data.';

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.telegram_channel_texts TO horecaos_application;

-- ------------------------------------------------------------ storefront branch order

-- ADR 0157 Release 4 (waived for this part by ADR 0183 D17): the order a storefront lists a brand's branches in when no
-- customer point is known, and the tie-break after distance when one is. Null sorts last, so a brand that sets none
-- keeps today's order. Distinct from `sort_order` (V0401), which orders the console's branch list.
ALTER TABLE tenant.locations
    ADD COLUMN storefront_sort_order integer;

ALTER TABLE tenant.locations
    ADD CONSTRAINT ck_locations_storefront_sort_order CHECK (
        storefront_sort_order IS NULL OR storefront_sort_order BETWEEN 0 AND 100000);

COMMENT ON COLUMN tenant.locations.storefront_sort_order IS
    'ADR 0157 Release 4 / ADR 0183 D17: the storefront''s branch order. Null sorts last. The console''s own list is ordered by sort_order (V0401).';
