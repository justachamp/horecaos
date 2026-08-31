-- ADR 0058 (rollout stage 1: operations groups): the Telegram provider kind.
--
-- ADR 0026 already has an installation/binding model; this migration is the
-- Telegram-specific extension of it, not a parallel one. Two things Telegram
-- needs that no existing category does:
--
-- First, a second secret. ADR 0026 observes that Telegram is two-directional
-- like MARKETPLACE: outbound Bot API calls plus inbound webhook deliveries that
-- must be authenticated. MARKETPLACE's inbound leg is an OAuth 2.0 client
-- (partner.api_clients) because a partner authenticates itself with its own
-- identity. Telegram's inbound leg is simpler and different in kind: Bot API
-- webhooks authenticate with one shared secret token the platform itself
-- chooses and hands to Telegram via setWebhook, verified on every inbound call
-- against the header `X-Telegram-Bot-Api-Secret-Token`. That is a second ADR
-- 0028 reference sitting beside the existing one, not a second credential
-- object with its own lifecycle, so it is a column rather than a table.
--
-- Second, fan-out. A payment or POS binding answers "which one account handles
-- this?" (ADR 0026's primary-binding model). An operations Telegram binding
-- answers a different question: "which chats want to hear about this?" — and
-- the honest answer is usually several. So a Telegram binding is not resolved
-- through binding_capabilities' primary-per-scope index at send time; it is
-- looked up directly, once its id is already known (recorded on the
-- notification's own recipient_endpoint_id — see V0100). binding_capabilities
-- still gets one row per Telegram binding (SEND_TELEGRAM_MESSAGE, is_primary
-- irrelevant) purely so ADR 0026's "every subscription is inspectable" holds
-- and reconciliation tooling that walks binding_capabilities does not need a
-- Telegram-shaped exception.

ALTER TABLE integration.installations
    ADD COLUMN webhook_secret_reference varchar(512);

COMMENT ON COLUMN integration.installations.webhook_secret_reference IS
    'ADR 0028 reference to the Bot API webhook secret_token. Null for every '
    'one-directional installation; set only for a two-directional provider '
    'category whose inbound leg authenticates with a shared secret rather '
    'than an OAuth client (contrast MARKETPLACE''s partner.api_clients).';

-- ------------------------------------------------------------- the binding

-- One row per bound chat (or forum topic within a chat). tenant_id is
-- duplicated from integration.bindings rather than joined for every read,
-- the same choice binding_capabilities already made, and for the same reason:
-- every query here is tenant-scoped and a join is not the sanctioned way to
-- discover that.
CREATE TABLE integration.telegram_bindings (
    binding_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Telegram chat ids exceed int4 range for supergroups (they are large
    -- negative numbers), hence bigint rather than integer.
    chat_id bigint NOT NULL,
    -- The forum topic's message_thread_id. Null means a flat group — the
    -- degraded mode ADR 0058 says must never be a blocker.
    topic_id integer,

    -- Only OPERATIONS is admitted this migration. ADR 0058's other two
    -- audiences (storefront 1:1, control-plane) are explicitly out of this
    -- rollout stage; adding their values is a DROP/ADD on this CHECK when
    -- their stage starts, following the pattern V0038 set for
    -- ck_installation_category. Shipping a value nothing can create yet is
    -- exactly what AGENTS.md's "Partial means partial" rule warns against.
    audience varchar(24) NOT NULL DEFAULT 'OPERATIONS',

    -- The Telegram user id (not a HorecaOS principal) who ran /link in the
    -- group. Traceability for "who added this bot here", not an identity the
    -- platform authenticates.
    linked_by_telegram_user_id bigint,

    -- Retirement taxonomy (ADR 0058): 403 (blocked/kicked) and the 400-class
    -- topic-deleted/thread-not-found answers both retire a binding, because a
    -- phantom chat or topic must not queue forever. Modelled as a state on
    -- this row rather than dropping to integration.bindings.status = SUSPENDED
    -- alone, so the taxonomy survives for an operator reading "why did this
    -- group stop receiving messages" without a log dive. bindings.status is
    -- still set to SUSPENDED in the same transaction — this table explains,
    -- the ADR 0026 table remains the one every generic reconciliation tool
    -- already reads.
    retired_at timestamptz,
    retired_reason varchar(32),

    -- migrate_to_chat_id history (ADR 0058): a group upgraded to a supergroup
    -- keeps receiving without operator help by rewriting chat_id in place;
    -- the previous id is kept for one hop so a duplicate migrate_to_chat_id
    -- payload racing the rewrite is recognisable rather than silently
    -- rewriting a binding that already moved.
    migrated_from_chat_id bigint,
    last_migrated_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_telegram_binding_identity UNIQUE (tenant_id, binding_id),
    CONSTRAINT fk_telegram_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT ck_telegram_binding_audience CHECK (audience = 'OPERATIONS'),
    CONSTRAINT ck_telegram_binding_retirement_pair CHECK (
        (retired_at IS NULL) = (retired_reason IS NULL)),
    CONSTRAINT ck_telegram_binding_retired_reason CHECK (
        retired_reason IS NULL OR retired_reason IN (
            'BOT_BLOCKED', 'BOT_KICKED', 'TOPIC_DELETED', 'THREAD_NOT_FOUND', 'MANUAL')),
    CONSTRAINT ck_telegram_binding_migration_pair CHECK (
        (migrated_from_chat_id IS NULL) = (last_migrated_at IS NULL)),
    CONSTRAINT ck_telegram_binding_version CHECK (version >= 1)
);

-- At most one binding per (chat, topic). COALESCE folds "no topic" onto one
-- sentinel so two flat-group bindings for the same chat collide the same way
-- two bindings on the same topic do — a plain UNIQUE over a nullable column
-- would let them duplicate, because NULL never compares equal to itself.
CREATE UNIQUE INDEX ux_telegram_binding_chat
    ON integration.telegram_bindings (tenant_id, chat_id, COALESCE(topic_id, -1))
    WHERE retired_at IS NULL;

CREATE INDEX ix_telegram_binding_chat_lookup ON integration.telegram_bindings (tenant_id, chat_id);

COMMENT ON TABLE integration.telegram_bindings IS
    'ADR 0058 stage 1: the ADR 0026 binding, extended with what a Telegram chat needs that no other category does.';

-- --------------------------------------------------------- event subscriptions

-- Which event classes a bound chat receives. Modelled exactly on
-- integration.binding_capabilities (binding_id, tenant_id, code, enabled):
-- the same shape, a different vocabulary. Kept as its own table rather than an
-- array column so a trigger's "who wants ORDER_CONFIRMED at this scope" is an
-- indexed equality lookup, not an array-contains scan.
CREATE TABLE integration.telegram_binding_events (
    binding_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    event_class varchar(64) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (binding_id, event_class),
    CONSTRAINT fk_telegram_binding_event_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.telegram_bindings (tenant_id, binding_id) ON DELETE CASCADE,
    -- Stage 1's trigger coverage only (ordering's confirm/reject, plus the new
    -- approval-deadline warning). Every other event class ADR 0058 names —
    -- payments, fulfillment, fiscal, inventory, integration, onboarding, bands
    -- — is out of scope this slice and named there as separate build items;
    -- admitting their values here before a trigger can ever write them is the
    -- same mistake V0038's comment on ck_installation_category warns against.
    CONSTRAINT ck_telegram_binding_event_class CHECK (
        event_class IN ('ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_APPROVAL_DEADLINE_WARNING'))
);

CREATE INDEX ix_telegram_binding_event_lookup
    ON integration.telegram_binding_events (tenant_id, event_class) WHERE enabled;

COMMENT ON TABLE integration.telegram_binding_events IS
    'ADR 0058: which event classes a bound Telegram chat is subscribed to.';

-- --------------------------------------------------------------- pending links

-- The server side of the `/link <code>` handshake (ADR 0058, ADR 0044's
-- deep-link payload constraint). An authenticated operations endpoint issues a
-- short opaque code here; the bot resolves it back to a tenant and a scope
-- when it arrives as a command inside a group, and never any other way — the
-- code is the only thing that ties an anonymous Telegram update to a tenant.
CREATE TABLE integration.telegram_pending_links (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Short and opaque by construction (see TelegramLinkCode in Java); unique
    -- globally rather than per tenant because the bot has no tenant context
    -- until the code resolves one.
    code varchar(32) NOT NULL,

    audience varchar(24) NOT NULL DEFAULT 'OPERATIONS',
    brand_id uuid,
    location_id uuid,

    -- The Keycloak subject of the operator who requested the code (ADR 0027's
    -- ActorRef.subject is a string throughout the platform, never assumed to
    -- parse as a UUID), for the audit trail an operator reads back later: "who
    -- invited this bot to this group".
    requested_by_principal_id varchar(255) NOT NULL,

    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_binding_id uuid,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_telegram_pending_link_code UNIQUE (code),
    CONSTRAINT ck_telegram_pending_link_audience CHECK (audience = 'OPERATIONS'),
    CONSTRAINT ck_telegram_pending_link_scope CHECK (location_id IS NULL OR brand_id IS NOT NULL),
    CONSTRAINT ck_telegram_pending_link_consumed_pair CHECK (
        (consumed_at IS NULL) = (created_binding_id IS NULL)),
    CONSTRAINT fk_telegram_pending_link_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_telegram_pending_link_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_telegram_pending_link_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id)
);

-- The lookup the bot actually does: "does this code exist, unconsumed,
-- unexpired". Partial so a consumed code (which stays forever, for audit)
-- never has to be scanned by it.
CREATE INDEX ix_telegram_pending_link_open
    ON integration.telegram_pending_links (code) WHERE consumed_at IS NULL;

COMMENT ON TABLE integration.telegram_pending_links IS
    'ADR 0058: server-side pending state for the /link <code> group-linking handshake.';

-- ------------------------------------------------------------------ chat locks

-- The durable multi-replica claim ADR 0058 names as "the OutboxRelay lease
-- pattern", applied per chat instead of per outbox row: ADR 0033's in-process
-- limiter cannot coordinate two JVMs, so mutual exclusion on a chat's outbound
-- send has to be a database row every replica contends for, not a local lock.
-- One row is created lazily per chat on first send and then reused; there is
-- no FK to telegram_bindings because a chat can outlive any one binding row
-- across a migrate_to_chat_id rewrite, and the lock's job is to serialise
-- Bot API calls to a chat id, not to track binding identity.
CREATE TABLE integration.telegram_chat_locks (
    tenant_id uuid NOT NULL,
    chat_id bigint NOT NULL,
    lease_owner uuid,
    lease_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, chat_id),
    CONSTRAINT ck_telegram_chat_lock_pair CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL))
);

COMMENT ON TABLE integration.telegram_chat_locks IS
    'ADR 0058: per-chat send lease so at most one outbound Bot API call is in flight per chat across every replica.';

-- ------------------------------------------------------------------------ grants

-- integration already has USAGE granted (V0035); only the new tables need one.
GRANT SELECT, INSERT, UPDATE, DELETE ON integration.telegram_bindings TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON integration.telegram_binding_events TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON integration.telegram_pending_links TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON integration.telegram_chat_locks TO horecaos_application;
