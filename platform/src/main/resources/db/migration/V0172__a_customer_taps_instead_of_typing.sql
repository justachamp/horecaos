-- ADR 0075: a customer orders from the chat they are already in.
--
-- The button discipline is ADR 0060 §4's and does not change: callback_data is
-- capped at 64 bytes by Telegram and carries one opaque server-generated token,
-- never anything signed or self-describing. This migration adds a third kind of
-- action record to the same table, for the same reason V0106 put two kinds in
-- one table rather than two -- both are "a button was rendered once, for one
-- purpose, and expires", and a third of the same shape is one CHECK, not a
-- second store.
--
--   CUSTOMER_ACTION  a button on a message sent to a customer's own linked
--                    private chat (ADR 0058 stage 2's binding). pending_command
--                    names the action and pending_argument its one parameter --
--                    a rating value, a payment method code, a saved address id.
--                    order_id is set where the action is about one order.
--
-- Scoped to the Telegram account it was rendered for, exactly as TENANT_SELECT
-- already is: a forwarded message's button must not act on the account of the
-- person who rendered it. The tenant on the row is the tenant the action runs
-- against, and the chat's own ADR 0026 customer binding must resolve inside it
-- or the tap is refused as unlinked -- so a token minted for one tenant cannot
-- be redeemed against another's cart.
--
-- What this deliberately does NOT do is give a customer button any authority.
-- A staff tap runs through BotCallbackAuthorizer and re-earns an ADR 0025
-- capability live, because it acts on somebody else's order. A customer tap
-- carries none and needs none: capabilities are delegated staff authority,
-- there is no grant row per customer, and StorefrontOrderingController already
-- declares no capability for exactly this reason.

-- Restates ck_bot_action_token_kind in full (V0106), per this repo's rule for
-- CHECK constraints: a value list cannot be extended in place, so the whole
-- constraint is dropped and recreated with every value it must accept.
ALTER TABLE integration.bot_action_tokens DROP CONSTRAINT ck_bot_action_token_kind;
ALTER TABLE integration.bot_action_tokens
    ADD CONSTRAINT ck_bot_action_token_kind CHECK (
        kind IN ('ORDER_DECISION', 'TENANT_SELECT', 'CUSTOMER_ACTION'));

-- Restates ck_bot_action_token_shape in full (V0106, amended by V0119), same
-- rule. Each kind names exactly the columns it needs and forbids the rest: a
-- CUSTOMER_ACTION row carrying a decision_action, or an ORDER_DECISION row
-- carrying a pending_command, would be a token nothing that mints one ever
-- intends and nothing that redeems one should have to guard against.
ALTER TABLE integration.bot_action_tokens DROP CONSTRAINT ck_bot_action_token_shape;
ALTER TABLE integration.bot_action_tokens
    ADD CONSTRAINT ck_bot_action_token_shape CHECK (
        (kind = 'ORDER_DECISION'
            AND order_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NOT NULL
            AND decision_action IS NOT NULL
            AND pending_command IS NULL AND pending_argument IS NULL AND telegram_user_id IS NULL)
        OR
        (kind = 'TENANT_SELECT'
            AND order_id IS NULL AND brand_id IS NULL AND location_id IS NULL AND decision_action IS NULL
            AND pending_command IS NOT NULL AND telegram_user_id IS NOT NULL
            AND reject_reason_code IS NULL)
        OR
        (kind = 'CUSTOMER_ACTION'
            AND brand_id IS NOT NULL AND location_id IS NULL AND decision_action IS NULL
            AND reject_reason_code IS NULL
            AND pending_command IS NOT NULL AND telegram_user_id IS NOT NULL)
    );

-- The closed set of customer actions. A closed set rather than free text
-- because the redeeming switch has one branch per value, and a token carrying
-- a command nothing handles is a button that answers nothing when tapped.
-- ADR 0075's own Decision section is explicit that menu composition is not on
-- this list and is not meant to be: choosing a dish means variants, modifier
-- groups with minimums and maximums, and photographs, and rendering that as
-- inline keyboards puts ADR 0016's selection rules in a second enforcer.
ALTER TABLE integration.bot_action_tokens
    ADD CONSTRAINT ck_bot_action_token_customer_command CHECK (
        kind <> 'CUSTOMER_ACTION'
        OR pending_command IN ('STATUS', 'REPEAT', 'CART', 'CHECKOUT', 'RATE'));

-- An order-scoped customer action is about the customer's own order, and the
-- authorizer proves that by reading the order inside the account's own scope.
-- CART and CHECKOUT are about a cart and name no order.
ALTER TABLE integration.bot_action_tokens
    ADD CONSTRAINT ck_bot_action_token_customer_order CHECK (
        kind <> 'CUSTOMER_ACTION'
        OR (pending_command IN ('STATUS', 'REPEAT', 'RATE')) = (order_id IS NOT NULL));

-- One lookup shape: redeem a token for the account it was rendered for. The
-- primary key already covers the token; this covers the sweep that expires
-- them and the "what is outstanding for this chat" read.
CREATE INDEX ix_bot_action_tokens_customer
    ON integration.bot_action_tokens (telegram_user_id, expires_at)
    WHERE kind = 'CUSTOMER_ACTION';
