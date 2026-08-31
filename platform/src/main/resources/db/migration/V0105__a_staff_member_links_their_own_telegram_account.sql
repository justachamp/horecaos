-- ADR 0060 §3: the staff identity link. From an authenticated staff session
-- (app or web board) a staff member generates a one-time code and sends
-- `/link <code>` to the bot in a 1:1 chat; the server binds the Telegram
-- account to the principal that requested the code. Deliberately a different
-- table from integration.telegram_pending_links (V0099): that handshake binds
-- a GROUP chat to a tenant's operations feed and is redeemed by whichever
-- group it is pasted into; this one binds one person's own Telegram account
-- to their own principal, is redeemed only in a 1:1 chat, and never creates
-- an ADR 0026 binding at all — there is no chat subscription here, only an
-- identity fact the bot callback authorizer resolves at tap time.

CREATE TABLE integration.telegram_staff_link_codes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Short and opaque by construction (TelegramLinkCode, reused from ADR
    -- 0058); unique globally for the same reason the group-link code is: the
    -- bot has no tenant context until the code resolves one.
    code varchar(32) NOT NULL,

    -- The Keycloak subject of the staff member who requested the code, and
    -- therefore whose principal the redeeming Telegram account is bound to.
    -- Never anyone else's — a staff member can only link their own account,
    -- which is what makes issuing this code a self-service capability
    -- (Capability.INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE) rather than an
    -- administrative one.
    principal_subject varchar(255) NOT NULL,

    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_link_id uuid,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_telegram_staff_link_code UNIQUE (code),
    -- What the staff link's own FK below references — a tenant-scoped unique
    -- pair, not just id, so that FK cannot point at another tenant's code.
    CONSTRAINT uq_telegram_staff_link_code_identity UNIQUE (tenant_id, id),
    CONSTRAINT ck_telegram_staff_link_code_consumed_pair CHECK (
        (consumed_at IS NULL) = (created_link_id IS NULL)),
    CONSTRAINT fk_telegram_staff_link_code_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id)
);

CREATE INDEX ix_telegram_staff_link_code_open
    ON integration.telegram_staff_link_codes (code) WHERE consumed_at IS NULL;

COMMENT ON TABLE integration.telegram_staff_link_codes IS
    'ADR 0060: server-side pending state for the staff /link <code> identity handshake, 1:1 chats only.';

-- ------------------------------------------------------------------ the link

-- The identity fact BotCallbackAuthorizer and typed commands resolve at tap
-- or command time: this Telegram account acts, in this tenant, as this
-- principal. Deliberately carries no revocation state of its own — v1
-- revocation is check-at-tap (ADR 0060 §4): the link row is a standing fact
-- about who a Telegram account claims to be, and whether that claim still
-- carries any authority is answered fresh, every time, by
-- AuthorizationService.require against the live grant. A grant revoked in
-- iam.grants makes the next tap refused without this table ever being
-- touched, which is exactly what makes the mechanism honest — the link
-- itself is not the authority.
CREATE TABLE integration.telegram_staff_links (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    telegram_user_id bigint NOT NULL,
    principal_subject varchar(255) NOT NULL,

    linked_via_code_id uuid,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 1,

    -- One Telegram account may hold multiple per-tenant links (ADR 0060 §3),
    -- but at most one per (tenant, account, principal) — redeeming the same
    -- code twice, or linking the same principal again, updates nothing new.
    CONSTRAINT uq_telegram_staff_link_identity UNIQUE (tenant_id, telegram_user_id, principal_subject),
    CONSTRAINT fk_telegram_staff_link_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    -- Composite, against the code table's own (tenant_id, id) unique pair
    -- above — a bare id reference would let one tenant's link point at
    -- another tenant's pending code row.
    CONSTRAINT fk_telegram_staff_link_code FOREIGN KEY (tenant_id, linked_via_code_id)
        REFERENCES integration.telegram_staff_link_codes (tenant_id, id),
    CONSTRAINT ck_telegram_staff_link_version CHECK (version >= 1)
);

-- The lookup a DM command does: every tenant this Telegram account is linked
-- into, to decide whether a tenant picker is needed.
CREATE INDEX ix_telegram_staff_link_by_account ON integration.telegram_staff_links (telegram_user_id);

-- The lookup a callback tap and a bound-group command do: is this account
-- linked in exactly this tenant, and to whom.
CREATE INDEX ix_telegram_staff_link_by_tenant_account
    ON integration.telegram_staff_links (tenant_id, telegram_user_id);

COMMENT ON TABLE integration.telegram_staff_links IS
    'ADR 0060: which principal a Telegram account acts as, per tenant. Authority is re-checked live at tap time, never read from here.';

-- ------------------------------------------------------------------------ grants

GRANT SELECT, INSERT, UPDATE, DELETE ON integration.telegram_staff_link_codes TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON integration.telegram_staff_links TO horecaos_application;
