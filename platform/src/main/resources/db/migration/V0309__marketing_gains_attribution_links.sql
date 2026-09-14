-- T18, operations §6.6a: ADR 0044's own "Attribution and referrals" section
-- names this table verbatim and its implementation checklist has carried it
-- as "Not built" since the ADR was accepted. This migration builds exactly
-- the shape the ADR already specified -- it is not a new decision.
--
-- What this migration does NOT do, on purpose, matching the same checklist
-- entry's own scope: it does not add `acquisition_channel`/
-- `acquisition_link_id` to a customer account, and it does not add an
-- attribution column to an order. Both are named in the ADR's prose as the
-- first-touch/last-touch halves of the mechanism, and both are other
-- modules' tables -- the same reason the ADR's own checklist gives for why
-- date of birth was not added to the ADR 0015 account from this migration's
-- sibling. What exists here is the half marketing owns outright: minting a
-- trackable link and counting the clicks it actually receives. Recording
-- which link brought a given account or order is follow-on integration work
-- for whichever surface actually serves a `?ref=` redirect or a Telegram
-- `start` deep link -- the storefront and the bot, neither of which this
-- wave's brief names.

CREATE TABLE marketing.attribution_links (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    label varchar(120) NOT NULL,
    -- Opaque and short by design: a Telegram `start` deep link payload is
    -- limited to 64 URL-safe characters (ADR 0044's own note), which is why
    -- this is a random short token and never an encoded struct.
    token varchar(64) NOT NULL,
    owner_note varchar(500),

    channel varchar(24) NOT NULL,
    destination_type varchar(24) NOT NULL,
    destination_id uuid,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    valid_from timestamptz NOT NULL,
    valid_until timestamptz,

    click_count integer NOT NULL DEFAULT 0,

    created_by uuid NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_attribution_link_identity UNIQUE (id, tenant_id),
    CONSTRAINT uq_attribution_link_token UNIQUE (tenant_id, token),
    CONSTRAINT fk_attribution_link_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    -- A CAMPAIGN destination names a real campaign of the same brand, so a
    -- report joining the two can never point at another brand's send.
    CONSTRAINT fk_attribution_link_campaign FOREIGN KEY (destination_id, tenant_id)
        REFERENCES marketing.campaigns (id, tenant_id),

    CONSTRAINT ck_attribution_link_channel CHECK (
        channel IN ('WEB', 'TELEGRAM_BOT', 'TELEGRAM_MINI_APP', 'MOBILE_APP')),
    CONSTRAINT ck_attribution_link_destination_type CHECK (
        destination_type IN ('CAMPAIGN', 'STOREFRONT_HOME', 'INFLUENCER')),
    CONSTRAINT ck_attribution_link_destination_pair CHECK (
        (destination_type = 'CAMPAIGN') = (destination_id IS NOT NULL)),
    CONSTRAINT ck_attribution_link_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_attribution_link_token_shape CHECK (token ~ '^[A-Za-z0-9_-]{4,64}$'),
    CONSTRAINT ck_attribution_link_label CHECK (length(btrim(label)) > 0),
    CONSTRAINT ck_attribution_link_window CHECK (
        valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_attribution_link_click_count CHECK (click_count >= 0)
);

CREATE INDEX ix_attribution_link_brand
    ON marketing.attribution_links (tenant_id, brand_id, created_at DESC);

COMMENT ON TABLE marketing.attribution_links IS
    'ADR 0044 "Attribution and referrals". A trackable website ?ref= link or Telegram startapp deep link a marketer mints for a campaign or an influencer. Renders as https://{tenant-domain}/?ref={token} (WEB) or a Telegram start deep link (TELEGRAM_BOT/TELEGRAM_MINI_APP) -- both computed from the token, neither stored, so changing a tenant''s domain never invalidates an issued link.';
COMMENT ON COLUMN marketing.attribution_links.click_count IS
    'Incremented by whichever surface actually serves the link -- not yet any surface in this build. A count and not a full event log: recording which account or order a link brought needs the account/order-side columns ADR 0044 also names and this migration deliberately does not add.';

GRANT SELECT, INSERT, UPDATE ON marketing.attribution_links TO horecaos_application;
