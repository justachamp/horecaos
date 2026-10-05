-- ADR 0140 (Pre-order re-validation) and ADR 0019 (Scheduled/pre-order semantics), row 6.1:
-- what a scheduled order's promotions look like when they are judged again at its checkpoint.
--
-- A pre-order is priced the moment it is taken, at that moment's clock, and then waits hours or
-- days for the kitchen. A "lunch 12:00 to 15:00" promotion that was not in force when the order
-- was taken may be by the time the food is made, and one that was may have closed. ADR 0140 says
-- a scheduled order is re-quoted at the ADR 0019 checkpoint; ADR 0019 says a long wait may
-- reprice but that "any customer-visible price/substitution change requires explicit acceptance
-- or safe cancellation". So a re-quote here is evidence and never a repricing: it prices the
-- order's live basket again at the checkpoint instant, compares it with the quote the customer
-- holds, and records what differs. The order's own totals, lines, ledger rows and redemptions are
-- not touched by anything in this table.
--
-- One CHECKPOINT row per order, enforced, so a sweep that runs twice (two nodes, a restart)
-- records one finding. An OPERATOR row is a re-check somebody asked for and may repeat.
--
-- The promotion changes are ids and signed minor-unit amounts and nothing else: no name, no
-- code word, no customer. PII has no column here (ADR 0029).

CREATE TABLE ordering.order_promotion_requotes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,

    -- CHECKPOINT: the sweep that judges a scheduled order as its promised time approaches.
    -- OPERATOR: a re-check somebody asked for.
    trigger_kind varchar(16) NOT NULL,
    -- The instant the promotions were judged at. Window conditions (time of day, weekday)
    -- read this, in the location's timezone.
    checkpoint_at timestamptz NOT NULL,

    -- The quote behind the order's current revision: what the customer holds.
    based_on_quote_id uuid NOT NULL,
    -- The quote the re-pricing produced. Null when pricing refused (a dish no longer priced).
    requote_quote_id uuid,

    -- UNCHANGED: the same promotions give the same amounts and the total is the same.
    -- CHANGED: they differ; the order keeps the price it was taken at, and a change the customer
    --   would see needs their agreement or a cancellation (ADR 0019), which this table does not
    --   decide.
    -- NOT_PRICEABLE: the basket could not be priced at the checkpoint; refusal_code says why.
    outcome varchar(16) NOT NULL,
    refusal_code varchar(64),

    currency char(3) NOT NULL,
    held_total_minor bigint NOT NULL,
    held_discount_minor bigint NOT NULL,
    requote_total_minor bigint,
    requote_discount_minor bigint,

    -- [{"promotionId": "...", "change": "DROPPED"|"GAINED"|"CHANGED", "heldMinor": n, "requoteMinor": n}]
    -- Signed like the quote's adjustments: a discount is negative, a markup positive.
    promotion_changes jsonb NOT NULL DEFAULT '[]'::jsonb,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_order_promotion_requote_trigger CHECK (trigger_kind IN ('CHECKPOINT', 'OPERATOR')),
    CONSTRAINT ck_order_promotion_requote_outcome CHECK (outcome IN ('UNCHANGED', 'CHANGED', 'NOT_PRICEABLE')),
    CONSTRAINT ck_order_promotion_requote_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_order_promotion_requote_changes CHECK (jsonb_typeof(promotion_changes) = 'array'),
    -- A refusal has no second quote and no second total, and everything else has both.
    CONSTRAINT ck_order_promotion_requote_priced CHECK (
        (outcome = 'NOT_PRICEABLE')
            = (requote_quote_id IS NULL AND requote_total_minor IS NULL AND requote_discount_minor IS NULL
                AND refusal_code IS NOT NULL)
    ),
    CONSTRAINT fk_order_promotion_requote_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_order_promotion_requote_based_on FOREIGN KEY (based_on_quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id),
    CONSTRAINT fk_order_promotion_requote_quote FOREIGN KEY (requote_quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id)
);

COMMENT ON TABLE ordering.order_promotion_requotes IS
    'ADR 0140 / ADR 0019 row 6.1. Evidence of a scheduled order''s promotions judged again at its checkpoint. Never changes the order: a customer-visible price change needs acceptance or cancellation (ADR 0019).';

-- One checkpoint finding per order, whichever node ran the sweep.
CREATE UNIQUE INDEX ux_order_promotion_requote_checkpoint
    ON ordering.order_promotion_requotes (tenant_id, order_id) WHERE trigger_kind = 'CHECKPOINT';

CREATE INDEX ix_order_promotion_requotes_order
    ON ordering.order_promotion_requotes (tenant_id, order_id, created_at DESC);

GRANT SELECT, INSERT ON ordering.order_promotion_requotes TO horecaos_application;
