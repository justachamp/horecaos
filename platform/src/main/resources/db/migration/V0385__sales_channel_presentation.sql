-- ADR 0036, row 10.4a: a channel's own presentation.
--
-- The registry (V0020) has always carried what a channel *does* -- its price
-- plane, its payment and fulfilment matrices, which branches serve it -- and
-- nothing about what it *looks like*. 10.5's per-type channel setup already
-- names "theme colour" and "social links" as common fields across every
-- system type; this migration gives the registry row itself the three
-- presentation facts an operator can set before 10.5's fuller per-type forms
-- exist: an icon, up to two brand colours, and a set of social links.
--
-- icon follows payments.payment_methods.icon (V0245) exactly: a code-owned,
-- free-text identifier the console renders, tolerating an unknown value by
-- falling back to a generic icon rather than refusing the row.
ALTER TABLE tenant.sales_channels
    ADD COLUMN icon varchar(64),
    ADD COLUMN brand_color_primary varchar(7),
    ADD COLUMN brand_color_secondary varchar(7);

ALTER TABLE tenant.sales_channels
    ADD CONSTRAINT ck_sales_channel_brand_color_primary CHECK (
        brand_color_primary IS NULL OR brand_color_primary ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT ck_sales_channel_brand_color_secondary CHECK (
        brand_color_secondary IS NULL OR brand_color_secondary ~ '^#[0-9a-fA-F]{6}$');

COMMENT ON COLUMN tenant.sales_channels.icon IS
    'A code-owned icon identifier the console renders, e.g. "telegram", "kiosk". Free text, the same convention as payments.payment_methods.icon (V0245): an unknown value falls back to a generic icon rather than refusing the row.';
COMMENT ON COLUMN tenant.sales_channels.brand_color_primary IS
    'A six-digit hex colour (#rrggbb), q-color-input''s own field shape (row X.32). Null means the channel has not set one and the console falls back to its own theme.';
COMMENT ON COLUMN tenant.sales_channels.brand_color_secondary IS
    'The accent to brand_color_primary''s base colour. Same shape and same null meaning.';

-- ----------------------------------------------------------- social links
--
-- Mirrors support.social_links (V0094) in shape -- a checked platform
-- vocabulary so a storefront can choose an icon rather than parse the URL to
-- guess, one live link per platform -- but scoped to a channel rather than a
-- brand, and to https only rather than support's broader http(s)/tel/mailto:
-- a channel's social presence is always a web destination, never a phone
-- number or an inbox.
--
-- Replaced whole-set under the channel's own expected version, the same
-- discipline tenant.channel_payment_methods and tenant.channel_fulfillment_modes
-- already use (SalesChannelService#replacePaymentMethods, #replaceFulfillmentModes):
-- a links list edited from two tabs must not merge into a combination neither
-- operator chose, and the version check is what makes the second tab lose
-- visibly instead of silently.
CREATE TABLE tenant.channel_social_links (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    platform varchar(32) NOT NULL,
    url varchar(500) NOT NULL,
    -- Display order. Written as the position in the replace request's own
    -- map, the same "request order becomes sort_order" reading
    -- replaceLocations already gives an ordered list.
    sort_order integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (channel_id, platform),
    CONSTRAINT ck_channel_social_platform CHECK (
        platform IN ('TELEGRAM', 'INSTAGRAM', 'FACEBOOK', 'YOUTUBE', 'TIKTOK', 'WEBSITE')),
    -- https only. A javascript: or data: URL authored here would be rendered
    -- as a link by every storefront and console screen that reads this table.
    CONSTRAINT ck_channel_social_url CHECK (url ~* '^https://'),
    CONSTRAINT fk_channel_social_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_channel_social_links_channel
    ON tenant.channel_social_links (tenant_id, channel_id, sort_order);

COMMENT ON TABLE tenant.channel_social_links IS
    'ADR 0036 row 10.4a: a channel''s own social links, one live URL per checked platform, https only.';

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.channel_social_links TO horecaos_application;
