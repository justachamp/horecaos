-- ADR 0060 §4: callback_data is capped at 64 bytes by Telegram and must never
-- carry anything signed or self-describing — the review's own words are
-- "nothing signed travels in the button". Every inline button this bot ever
-- sends carries one opaque, short, server-generated token; this table is the
-- server-side action record the token indexes. Two kinds share it because
-- both are exactly the same shape — "a button was rendered once, for one
-- purpose, and expires" — and a generic table is one migration and one store
-- instead of two:
--
--   ORDER_DECISION  an Approve/Reject button on an order-awaiting-approval
--                    notification. order_id/brand_id/location_id/
--                    decision_action are all set; the token string itself
--                    doubles as OrderStateService.DecisionCommand.decisionId,
--                    so the same physical button tapped twice is the same
--                    decision retried (idempotent), and two different
--                    buttons (different chats, different renders) racing the
--                    same order are two different decisions the compare-
--                    and-set in ordering settles, exactly as the web board's
--                    own decisionId scoping already works.
--
--   TENANT_SELECT    a tenant-picker button answering an ambiguous DM typed
--                     command from an account linked into more than one
--                     tenant with the needed grant (ADR 0060 §3). Scoped to
--                     the one Telegram account it was rendered for, since a
--                     forged tap from someone else must not be able to steer
--                     which tenant a resumed command runs against.
CREATE TABLE integration.bot_action_tokens (
    token varchar(32) PRIMARY KEY,
    tenant_id uuid NOT NULL,
    kind varchar(24) NOT NULL,

    order_id uuid,
    brand_id uuid,
    location_id uuid,
    decision_action varchar(16),

    pending_command varchar(16),
    pending_argument varchar(256),
    telegram_user_id bigint,

    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_bot_action_token_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT ck_bot_action_token_kind CHECK (kind IN ('ORDER_DECISION', 'TENANT_SELECT')),
    CONSTRAINT ck_bot_action_token_decision_action CHECK (
        decision_action IS NULL OR decision_action IN ('APPROVE', 'REJECT')),
    -- Exactly the fields its own kind needs, and no others: an ORDER_DECISION
    -- row with a pending_command, or a TENANT_SELECT row with an order_id,
    -- would be a token nothing that mints it ever intends and nothing that
    -- redeems it should have to guard against.
    CONSTRAINT ck_bot_action_token_shape CHECK (
        (kind = 'ORDER_DECISION'
            AND order_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NOT NULL
            AND decision_action IS NOT NULL
            AND pending_command IS NULL AND pending_argument IS NULL AND telegram_user_id IS NULL)
        OR
        (kind = 'TENANT_SELECT'
            AND order_id IS NULL AND brand_id IS NULL AND location_id IS NULL AND decision_action IS NULL
            AND pending_command IS NOT NULL AND telegram_user_id IS NOT NULL)
    )
);

-- The re-render lookup: TelegramChannelAdapter mints an ORDER_DECISION pair
-- once per (order, chat) and reuses it on a harmless resend rather than
-- minting a fresh pair — and therefore a fresh decisionId — every time.
CREATE INDEX ix_bot_action_token_order
    ON integration.bot_action_tokens (tenant_id, order_id, decision_action) WHERE order_id IS NOT NULL;

-- Expired tokens are never deleted (they are evidence of what a button once
-- pointed at), but the redemption path always filters on this.
CREATE INDEX ix_bot_action_token_expiry ON integration.bot_action_tokens (expires_at);

COMMENT ON TABLE integration.bot_action_tokens IS
    'ADR 0060 section 4: the server-side record an opaque callback_data token indexes. Never carries signed authority.';

GRANT SELECT, INSERT, UPDATE, DELETE ON integration.bot_action_tokens TO horecaos_application;

-- ------------------------------------------------------------ new event class

-- ADR 0060 §2 puts Approve/Reject buttons on the order-awaiting-approval
-- notification, which stage 1 (ADR 0058, V0099) never fanned out to Telegram
-- at all — only ORDER_CONFIRMED, ORDER_REJECTED and the deadline warning
-- were admitted. Same append-only DROP/ADD pattern V0038 and V0099 both use.
ALTER TABLE integration.telegram_binding_events DROP CONSTRAINT ck_telegram_binding_event_class;
ALTER TABLE integration.telegram_binding_events
    ADD CONSTRAINT ck_telegram_binding_event_class CHECK (
        event_class IN (
            'ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_APPROVAL_DEADLINE_WARNING', 'ORDER_AWAITING_APPROVAL'));
