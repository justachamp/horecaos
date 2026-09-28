-- Gap map row 1.3: a New Order operator overriding the resolver's proposed
-- branch names why, from a curated list, never free text.
--
-- Shaped exactly like ordering.order_reject_reasons (V0119) and for the same
-- reason: an override's consequence never varies by which reason was picked
-- (it never changes stock disposition, liability or a refund posture the way
-- a cancellation reason does), so this is platform-curated reference data,
-- not a tenant-authored registry like ordering.order_outcome_reasons. See
-- V0119's own header for the fuller argument; it applies here unchanged.
--
-- The override itself is audited through ChangeDocuments on the order the
-- placement created (ordering.order.branch_overridden, ADR 0027) — this
-- table only supplies the closed set of codes that audit fact's reasonCode
-- may name.

CREATE TABLE ordering.branch_override_reasons (
    code varchar(48) PRIMARY KEY,
    display_order integer NOT NULL,
    -- True only for OTHER today — see RejectReasonQueryService's own doc for
    -- why this is a column on the reason and not a hardcoded special case.
    requires_note boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_branch_override_reason_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_branch_override_reason_display_order CHECK (display_order >= 0)
);

COMMENT ON TABLE ordering.branch_override_reasons IS
    'Platform-curated, code-owned reasons for overriding the New Order screen''s proposed branch (gap map row 1.3). Seeded by this migration only; no tenant authors a row here in v1.';

CREATE TABLE ordering.branch_override_reason_texts (
    reason_code varchar(48) NOT NULL REFERENCES ordering.branch_override_reasons (code),
    locale varchar(16) NOT NULL,
    label varchar(120) NOT NULL,

    PRIMARY KEY (reason_code, locale),
    CONSTRAINT ck_branch_override_reason_text_locale CHECK (locale IN ('ru', 'uz-Latn', 'en')),
    CONSTRAINT ck_branch_override_reason_text_present CHECK (length(btrim(label)) > 0)
);

CREATE INDEX ix_branch_override_reasons_active_order
    ON ordering.branch_override_reasons (display_order)
    WHERE active;

-- --------------------------------------------------------------------- seed

INSERT INTO ordering.branch_override_reasons (code, display_order, requires_note, active) VALUES
    ('CUSTOMER_REQUESTED_BRANCH', 1, false, true),
    ('PROPOSED_BRANCH_TOO_BUSY', 2, false, true),
    ('PROPOSED_BRANCH_CLOSED_OR_UNAVAILABLE', 3, false, true),
    ('LOCAL_KNOWLEDGE_BETTER_MATCH', 4, false, true),
    ('OTHER', 5, true, true);

INSERT INTO ordering.branch_override_reason_texts (reason_code, locale, label) VALUES
    ('CUSTOMER_REQUESTED_BRANCH', 'ru', 'Клиент попросил этот филиал'),
    ('CUSTOMER_REQUESTED_BRANCH', 'uz-Latn', 'Mijoz shu filialni so''radi'),
    ('CUSTOMER_REQUESTED_BRANCH', 'en', 'Customer asked for this branch'),

    ('PROPOSED_BRANCH_TOO_BUSY', 'ru', 'Предложенный филиал перегружен'),
    ('PROPOSED_BRANCH_TOO_BUSY', 'uz-Latn', 'Taklif qilingan filial band'),
    ('PROPOSED_BRANCH_TOO_BUSY', 'en', 'Proposed branch is too busy'),

    ('PROPOSED_BRANCH_CLOSED_OR_UNAVAILABLE', 'ru', 'Предложенный филиал закрыт или недоступен'),
    ('PROPOSED_BRANCH_CLOSED_OR_UNAVAILABLE', 'uz-Latn', 'Taklif qilingan filial yopiq yoki mavjud emas'),
    ('PROPOSED_BRANCH_CLOSED_OR_UNAVAILABLE', 'en', 'Proposed branch is closed or unavailable'),

    ('LOCAL_KNOWLEDGE_BETTER_MATCH', 'ru', 'По местному опыту подходит другой филиал'),
    ('LOCAL_KNOWLEDGE_BETTER_MATCH', 'uz-Latn', 'Mahalliy tajribaga ko''ra boshqa filial mos'),
    ('LOCAL_KNOWLEDGE_BETTER_MATCH', 'en', 'Local knowledge points to a different branch'),

    ('OTHER', 'ru', 'Другое'),
    ('OTHER', 'uz-Latn', 'Boshqa'),
    ('OTHER', 'en', 'Other');

-- No UPDATE/DELETE/INSERT grant: read-only reference data today, exactly like
-- ordering.order_reject_reasons (V0119).
GRANT SELECT ON ordering.branch_override_reasons TO horecaos_application;
GRANT SELECT ON ordering.branch_override_reason_texts TO horecaos_application;
