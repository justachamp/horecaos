-- ADR 0036, row 10.5: a web channel's own hostname.
--
-- The storefront (frontend/storefront) resolves its tenant, brand and channel
-- from `/config.json`, read once at bootstrap (`load-config.ts`) -- one build
-- artifact per deployment, re-pointed by editing that file rather than by any
-- call to this platform. That stays exactly as it is; nothing here changes it.
--
-- What has never existed is a *server-side* record of which hostname belongs
-- to which channel -- the fact an edge layer (a reverse proxy, a static-site
-- host, a future config.json generator) would need to decide which build to
-- serve for an incoming Host header, and the fact row 10.5's console screen
-- needs to show an operator "your storefront is reachable at ...". This table
-- is that record: a hostname either typed as a full custom domain or composed
-- from a reserved-checked subdomain slug (ChannelSetupService), pointing at
-- exactly one channel.
--
-- One row per channel, not a whole-set replace like `channel_social_links`
-- (V0385): a channel has at most one address, unlike an open-ended list of
-- social links, so "set" and "clear" are the only two operations and a
-- second write for the same channel corrects the first rather than adding to
-- it.
--
-- hostname is globally unique, deliberately not scoped by tenant_id: it is a
-- real DNS name, and two tenants both claiming "orders.example.uz" is exactly
-- the collision this table exists to prevent, not a tenant-isolation concern
-- to relax.
--
-- verified starts false for anything the operator did not obtain from the
-- platform itself. ChannelSetupService sets it true immediately for a
-- platform-issued subdomain (`{slug}.<base domain>` -- HorecaOS's own DNS
-- already answers for it) and leaves it false for a custom domain until an
-- operator confirms ownership out of band and a capability-gated console
-- action flips it. The automated DNS-TXT challenge-and-poll flow
-- settings.md 10.5's WEB section describes is not built here -- this
-- migration and its service give the flag somewhere to live, not the checker.
CREATE TABLE tenant.channel_hostnames (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    hostname varchar(253) NOT NULL,
    verified boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, channel_id),
    CONSTRAINT uq_channel_hostname UNIQUE (hostname),
    CONSTRAINT fk_channel_hostname_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE,
    -- Lowercase DNS label(s) separated by dots -- the same label shape
    -- tenancy.domain.Slug already enforces for a tenant's own slug, repeated
    -- here because a hostname is one or more such labels joined by '.', not
    -- a single one.
    CONSTRAINT ck_channel_hostname_format CHECK (
        hostname ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$')
);

CREATE INDEX ix_channel_hostnames_channel ON tenant.channel_hostnames (tenant_id, channel_id);

COMMENT ON TABLE tenant.channel_hostnames IS
    'ADR 0036 row 10.5: the hostname a WEB (or any) channel answers on. One row per channel; hostname is globally unique. verified is a stored flag an operator or the platform sets, not proof of DNS ownership -- see this migration''s own header.';

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.channel_hostnames TO horecaos_application;
