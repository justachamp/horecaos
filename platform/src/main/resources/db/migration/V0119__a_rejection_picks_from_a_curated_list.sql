-- Order rejection stops being free text (owner's directive, wave 24).
--
-- What is here: a platform-curated, code-owned reference table of reject
-- reasons — not a tenant-authored registry like ordering.order_outcome_reasons
-- (ADR 0039, V0029). The two look similar and are deliberately not the same
-- table:
--
--   order_outcome_reasons carries per-reason CONSEQUENCES a tenant decides
--   once, in advance (stock disposition, liability party, refund posture) —
--   that is the whole argument for letting a tenant author rows there. A
--   rejection's consequence never varies by which reason was picked: every
--   REJECTED order already always writes StockDisposition.RELEASE,
--   LiabilityParty.TENANT and CustomerRefund.FULL under
--   OutcomeSystemCategory.RESTAURANT_REFUSED — see OrderStateService.decide.
--   A reject reason carries nothing but a code, a display position and a
--   label in three languages, which is the shape ordering already has for
--   pure reference data (catalog.mxik_reference, V0028) and for a
--   platform-seeded, no-tenant-authoring table (tenant.onboarding_templates,
--   V0014): seeded once, by this migration, and read-only from the
--   application today.
--
-- Why platform-owned rather than tenant-configurable in v1: the owner's own
-- brief names the eight reasons directly — item unavailable, kitchen
-- overloaded, closing soon, delivery zone unreachable, suspicious/test order,
-- customer unreachable, price/menu error, other — the same closed-set
-- argument OutcomeSystemCategory already makes for cross-tenant reporting:
-- fifty near-duplicate tenant reasons ("Нет в наличии", "Закончилось",
-- "Кончилось") would fragment a rejection-rate report the same way a free
-- tenant cancellation registry would. Nothing here forecloses a tenant
-- addition later: the seam is an additive tenant_id column plus a manage
-- endpoint mirroring OrderOutcomeReasonController's, exactly the shape this
-- table's sibling already has — deliberately not built now because nothing
-- in the pilot needs it and an unused nullable column would be exactly the
-- kind of hypothetical-scale plumbing AGENTS.md tells us not to add.
--
-- The registry stores CODES, not surrogate UUIDs, and carries no version: a
-- platform-seeded row is not renamed out from under a tenant the way an
-- authored order_outcome_reasons row can be (that table's reasonId/
-- reasonVersion/reasonSnapshot machinery exists to survive exactly that
-- rename). ordering.order_outcomes.reason_id keeps pointing only at
-- order_outcome_reasons; a REJECTED outcome still carries no reason_id
-- (unchanged from V0029) — the reject reason code lives where every other
-- decision reason already lives, ordering.order_transitions.reason_code and
-- ordering.order_approval_decisions.reason_code, both free varchar(64)
-- columns that already accept an arbitrary code. No rewrite of history: a
-- row an operator free-typed before this release ("нет курицы") stays
-- exactly as readable as one that names a registry code today.
--
-- What is new for a note: OTHER keeps the information the free text used to
-- carry. ordering.order_outcomes.note_encrypted already exists (V0029, built
-- for cancellation) and was simply never populated for a rejection —
-- OrderStateService.decide always passed null. This migration adds no
-- column for that; it only starts being used.

CREATE TABLE ordering.order_reject_reasons (
    code varchar(48) PRIMARY KEY,
    display_order integer NOT NULL,
    -- True only for OTHER today. A future platform reason that also wants a
    -- mandatory note sets this rather than the application special-casing
    -- one literal code — see RejectReasonQueryService.validateForDecision.
    requires_note boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_order_reject_reason_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_order_reject_reason_display_order CHECK (display_order >= 0)
);

COMMENT ON TABLE ordering.order_reject_reasons IS
    'Platform-curated, code-owned reject reasons (wave 24). Seeded by this migration only; no tenant authors a row here in v1. See this migration''s own header for why this is not ordering.order_outcome_reasons.';
COMMENT ON COLUMN ordering.order_reject_reasons.requires_note IS
    'True only for OTHER: picking it must not lose the information free text used to carry, so a note is mandatory precisely where the code itself says nothing.';

-- What the operator picks from, and what the customer is told, per locale —
-- the same three-locale closed set order_outcome_reason_texts enforces
-- (ADR 0035).
CREATE TABLE ordering.order_reject_reason_texts (
    reason_code varchar(48) NOT NULL REFERENCES ordering.order_reject_reasons (code),
    locale varchar(16) NOT NULL,
    label varchar(120) NOT NULL,

    PRIMARY KEY (reason_code, locale),
    CONSTRAINT ck_order_reject_reason_text_locale CHECK (locale IN ('ru', 'uz-Latn', 'en')),
    CONSTRAINT ck_order_reject_reason_text_present CHECK (length(btrim(label)) > 0)
);

COMMENT ON TABLE ordering.order_reject_reason_texts IS
    'wave 24. One label per reject reason per locale — the same label an operator picks from and a customer is told, since the codes are platform-curated and none of them carries an operator''s own wording to keep separate from a customer''s (contrast order_outcome_reason_texts, which does).';

CREATE INDEX ix_order_reject_reasons_active_order
    ON ordering.order_reject_reasons (display_order)
    WHERE active;

-- --------------------------------------------------------------------- seed
--
-- Eight reasons, the owner's own list plus OTHER. English label given here
-- for readability; ru and uz-Latn follow immediately below each.

INSERT INTO ordering.order_reject_reasons (code, display_order, requires_note, active) VALUES
    ('ITEM_UNAVAILABLE', 1, false, true),
    ('KITCHEN_OVERLOADED', 2, false, true),
    ('CLOSING_SOON', 3, false, true),
    ('DELIVERY_ZONE_UNREACHABLE', 4, false, true),
    ('CUSTOMER_UNREACHABLE', 5, false, true),
    ('SUSPICIOUS_OR_TEST_ORDER', 6, false, true),
    ('PRICE_OR_MENU_ERROR', 7, false, true),
    ('OTHER', 8, true, true);

INSERT INTO ordering.order_reject_reason_texts (reason_code, locale, label) VALUES
    ('ITEM_UNAVAILABLE', 'ru', 'Нет в наличии'),
    ('ITEM_UNAVAILABLE', 'uz-Latn', 'Mavjud emas'),
    ('ITEM_UNAVAILABLE', 'en', 'Item unavailable'),

    ('KITCHEN_OVERLOADED', 'ru', 'Кухня перегружена'),
    ('KITCHEN_OVERLOADED', 'uz-Latn', 'Oshxona band'),
    ('KITCHEN_OVERLOADED', 'en', 'Kitchen overloaded'),

    ('CLOSING_SOON', 'ru', 'Скоро закрываемся'),
    ('CLOSING_SOON', 'uz-Latn', 'Yaqinda yopamiz'),
    ('CLOSING_SOON', 'en', 'Closing soon'),

    ('DELIVERY_ZONE_UNREACHABLE', 'ru', 'Зона доставки недоступна'),
    ('DELIVERY_ZONE_UNREACHABLE', 'uz-Latn', 'Yetkazib berish zonasi mavjud emas'),
    ('DELIVERY_ZONE_UNREACHABLE', 'en', 'Delivery zone unreachable'),

    ('CUSTOMER_UNREACHABLE', 'ru', 'Не дозвонились до клиента'),
    ('CUSTOMER_UNREACHABLE', 'uz-Latn', 'Mijoz bilan bog''lanib bo''lmadi'),
    ('CUSTOMER_UNREACHABLE', 'en', 'Customer unreachable'),

    ('SUSPICIOUS_OR_TEST_ORDER', 'ru', 'Подозрительный или тестовый заказ'),
    ('SUSPICIOUS_OR_TEST_ORDER', 'uz-Latn', 'Shubhali yoki test buyurtma'),
    ('SUSPICIOUS_OR_TEST_ORDER', 'en', 'Suspicious or test order'),

    ('PRICE_OR_MENU_ERROR', 'ru', 'Ошибка цены или меню'),
    ('PRICE_OR_MENU_ERROR', 'uz-Latn', 'Narx yoki menyu xatosi'),
    ('PRICE_OR_MENU_ERROR', 'en', 'Price or menu error'),

    ('OTHER', 'ru', 'Другое'),
    ('OTHER', 'uz-Latn', 'Boshqa'),
    ('OTHER', 'en', 'Other');

-- No UPDATE/DELETE/INSERT grant: read-only reference data today, exactly
-- like catalog.mxik_reference. A future manage endpoint arrives with its own
-- additive migration widening this grant, the same way
-- order_outcome_reasons' own wider grant arrived with its own authoring
-- endpoint (V0029).
GRANT SELECT ON ordering.order_reject_reasons TO horecaos_application;
GRANT SELECT ON ordering.order_reject_reason_texts TO horecaos_application;

-- ------------------------------------------------ bot_action_tokens extension
--
-- ADR 0060 §4's token model, extended for a two-step decision. The Reject
-- button on an order-awaiting-approval notification used to decide
-- REJECTED the instant it was tapped, recording a fixed
-- TELEGRAM_BOT_TAP reason (OrderDecisionPortAdapter). It now mints a token
-- with decision_action = 'REJECT' and reject_reason_code left null — "tap
-- to see reasons" — and tapping it presents a follow-up keyboard built from
-- ordering.order_reject_reasons, each button its own ORDER_DECISION token
-- carrying the same order/brand/location and a chosen reject_reason_code.
-- Reusing bot_action_tokens' own shape rather than a parallel store: this is
-- still exactly one button, once, expiring, indexed by an opaque token,
-- which is everything ck_bot_action_token_shape already describes for
-- ORDER_DECISION — it only needed one more optional column.
ALTER TABLE integration.bot_action_tokens
    ADD COLUMN reject_reason_code varchar(48);

-- Restates ck_bot_action_token_shape in full (V0106), per this repo's own
-- rule for CHECK constraints: a partial ALTER cannot add a column to an
-- existing equality-shaped CHECK, so the whole constraint is dropped and
-- recreated rather than approximated.
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
    );

-- A reject reason code only ever rides on a REJECT decision token — an
-- APPROVE token or a bare "pick a reason" REJECT token both carry null here.
ALTER TABLE integration.bot_action_tokens
    ADD CONSTRAINT ck_bot_action_token_reject_reason CHECK (
        reject_reason_code IS NULL OR decision_action = 'REJECT');

COMMENT ON COLUMN integration.bot_action_tokens.reject_reason_code IS
    'wave 24. Null on the bare Reject button (tap presents the reason picker) and on every APPROVE token; set on each reason-picker button, one of ordering.order_reject_reasons.code.';
