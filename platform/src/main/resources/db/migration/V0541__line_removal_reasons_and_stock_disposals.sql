-- ADR 0169 (Accepted 2026-10-07), gap-map row 1.2c: decreasing or removing a confirmed order line.
--
-- Four things, all additive to what the order and the ledger already hold:
--
--   1. The outcome-reason registry (V0029) gains a third kind, LINE_REMOVAL. A removal names a reason the tenant
--      authored; the reason carries the stock disposition and the liable party, so the operator never picks a
--      disposition (ADR 0039 refused that checkbox by name for cancellation, and the argument is the same).
--   2. ordering.order_line_removals: the append-only evidence of what an amendment took off the order.
--   3. inventory.stock_disposals and its lines: the record of each `dispose` call, so a replay with the same
--      idempotency key returns the first answer and moves nothing. The ledger's own unique key
--      (tenant, stock item, idempotency key) cannot hold the part of a disposal that writes no movement -- the
--      release of a unit still on a HELD hold -- and a replay of that part would release twice.
--   4. inventory.reservation_lines may reach zero. A disposal reduces a held line by the units it releases, and
--      a line whose every unit was taken off the order is a line of zero, which ck_reservation_line_quantity
--      (quantity > 0) refused; the readers that act on a line now skip a zero one.

-- ------------------------------------------------------------------------------------ 1. the third kind

ALTER TABLE ordering.order_outcome_reasons
    DROP CONSTRAINT ck_outcome_reason_kind,
    DROP CONSTRAINT ck_outcome_reason_category,
    DROP CONSTRAINT ck_outcome_reason_disposition,
    DROP CONSTRAINT ck_outcome_reason_liability,
    DROP CONSTRAINT ck_outcome_reason_refund;

ALTER TABLE ordering.order_outcome_reasons
    ADD CONSTRAINT ck_outcome_reason_kind CHECK (kind IN ('CANCELLATION', 'COMPLETION', 'LINE_REMOVAL')),
    -- The category is closed and belongs to the kind. ITEM_UNAVAILABLE and OTHER are honest for a removal as
    -- they are for a cancellation; the other four are the removal's own.
    ADD CONSTRAINT ck_outcome_reason_category CHECK (
        CASE kind
            WHEN 'CANCELLATION' THEN system_category IN (
                'CUSTOMER_CANCELLED', 'CUSTOMER_UNREACHABLE', 'CUSTOMER_NO_SHOW',
                'RESTAURANT_REFUSED', 'ITEM_UNAVAILABLE', 'KITCHEN_CAPACITY',
                'DELIVERY_FAILED', 'COURIER_UNAVAILABLE', 'ADDRESS_UNSERVICEABLE',
                'PAYMENT_NOT_RECEIVED', 'DUPLICATE_ORDER', 'TEST_ORDER',
                'SUSPECTED_FRAUD', 'PRICING_ERROR', 'OTHER')
            WHEN 'COMPLETION' THEN system_category IN (
                'DELIVERED_OWN_COURIER', 'DELIVERED_PARTNER_COURIER',
                'COLLECTED_BY_CUSTOMER', 'SERVED_IN_HOUSE', 'OTHER')
            WHEN 'LINE_REMOVAL' THEN system_category IN (
                'CUSTOMER_CHANGED_MIND', 'ITEM_UNAVAILABLE', 'KITCHEN_ERROR',
                'ORDER_ENTRY_ERROR', 'QUALITY_ISSUE', 'OTHER')
        END),
    -- Each stated as an equivalence, as before: a completion carries no consequence, the other two kinds carry
    -- a stock disposition and a liable party, and only a cancellation has a refund posture.
    ADD CONSTRAINT ck_outcome_reason_disposition CHECK (
        (kind <> 'COMPLETION') = (stock_disposition IS NOT NULL)),
    ADD CONSTRAINT ck_outcome_reason_liability CHECK (
        (kind <> 'COMPLETION') = (liability_party IS NOT NULL)),
    ADD CONSTRAINT ck_outcome_reason_refund CHECK (
        (kind = 'CANCELLATION') = (customer_refund IS NOT NULL)),
    -- A removal never releases (RELEASE is the answer only before commitment, and a removal is asked about by
    -- the reservation itself), and a unit that is "not available" was never there to put back.
    ADD CONSTRAINT ck_outcome_reason_removal_disposition CHECK (
        kind <> 'LINE_REMOVAL' OR stock_disposition IN ('RETURN_TO_STOCK', 'WRITE_OFF', 'NO_EFFECT')),
    ADD CONSTRAINT ck_outcome_reason_removal_unavailable CHECK (
        kind <> 'LINE_REMOVAL' OR system_category <> 'ITEM_UNAVAILABLE' OR stock_disposition <> 'RETURN_TO_STOCK');

-- The starter set (ADR 0169, open input on the starter reasons): three reasons for every tenant that exists,
-- editable and archivable by it. A tenant created later gets the same three when it is activated
-- (OrderOutcomeReasonService#seedStarterRemovalReasons). Customer wording in the three platform languages.
WITH starter(internal_name, system_category, stock_disposition, liability_party, display_order, ru, uz, en) AS (
    VALUES
        ('Customer changed their mind', 'CUSTOMER_CHANGED_MIND', 'RETURN_TO_STOCK', 'CUSTOMER', 0,
         'По вашей просьбе блюдо убрано из заказа.',
         'Sizning iltimosingizga ko‘ra taom buyurtmadan olib tashlandi.',
         'At your request, the dish was removed from your order.'),
        ('Kitchen error', 'KITCHEN_ERROR', 'WRITE_OFF', 'TENANT', 1,
         'Блюдо убрано из заказа: на кухне произошла ошибка.',
         'Taom buyurtmadan olib tashlandi: oshxonada xatolik yuz berdi.',
         'The dish was removed from your order because of a kitchen error.'),
        ('Order entry error', 'ORDER_ENTRY_ERROR', 'RETURN_TO_STOCK', 'TENANT', 2,
         'Блюдо убрано из заказа: оно было добавлено по ошибке.',
         'Taom buyurtmadan olib tashlandi: u xato bilan qoʻshilgan edi.',
         'The dish was removed from your order because it was added by mistake.')
),
seeded AS (
    INSERT INTO ordering.order_outcome_reasons (
        id, tenant_id, kind, system_category, internal_name, stock_disposition, liability_party, status, display_order)
    SELECT gen_random_uuid(), t.id, 'LINE_REMOVAL', s.system_category, s.internal_name, s.stock_disposition,
           s.liability_party, 'ACTIVE', s.display_order
      FROM tenant.tenants t CROSS JOIN starter s
    RETURNING id, tenant_id, internal_name
)
INSERT INTO ordering.order_outcome_reason_texts (reason_id, locale, customer_text)
SELECT seeded.id, texts.locale, texts.customer_text
  FROM seeded
  JOIN starter s ON s.internal_name = seeded.internal_name
  CROSS JOIN LATERAL (VALUES ('ru', s.ru), ('uz-Latn', s.uz), ('en', s.en)) AS texts(locale, customer_text);

-- --------------------------------------------------------------------------------- 2. the removal evidence

-- Append-only, one row per affected line per amendment. The reason is snapshotted so renaming it next year does
-- not rewrite this year's report; the counts say what the ledger did; the movement ids point at it.
CREATE TABLE ordering.order_line_removals (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,
    amendment_id uuid NOT NULL,
    revision integer NOT NULL,
    order_line_id uuid NOT NULL,
    variant_id uuid NOT NULL,
    quantity_before numeric(10, 3) NOT NULL,
    -- Zero for a removal; the lowered quantity for a decrease.
    quantity_after numeric(10, 3) NOT NULL,
    -- Whole units the order no longer needs: the difference of the two ceilings, as an increase counts them.
    stock_units integer NOT NULL,
    released_units integer NOT NULL DEFAULT 0,
    returned_units integer NOT NULL DEFAULT 0,
    written_off_units integer NOT NULL DEFAULT 0,
    no_effect_units integer NOT NULL DEFAULT 0,
    reason_id uuid NOT NULL,
    reason_version integer NOT NULL,
    reason_category varchar(32) NOT NULL,
    reason_snapshot_json jsonb NOT NULL,
    disposition_declared varchar(24) NOT NULL,
    disposition_effective varchar(24) NOT NULL,
    -- What the kitchen's own record said about the line when the removal applied.
    kitchen_state varchar(12) NOT NULL,
    liability_party varchar(24) NOT NULL,
    inventory_movement_ids uuid[] NOT NULL DEFAULT '{}',
    approval_request_id uuid,
    actor_type varchar(16) NOT NULL,
    actor_id varchar(255),
    occurred_at timestamptz NOT NULL,

    CONSTRAINT ck_line_removal_quantities CHECK (
        quantity_before > 0 AND quantity_after >= 0 AND quantity_after < quantity_before),
    CONSTRAINT ck_line_removal_units CHECK (
        stock_units >= 0 AND released_units >= 0 AND returned_units >= 0
        AND written_off_units >= 0 AND no_effect_units >= 0
        AND released_units + returned_units + written_off_units + no_effect_units = stock_units),
    CONSTRAINT ck_line_removal_kitchen_state CHECK (
        kitchen_state IN ('NOT_STARTED', 'STARTED', 'MADE', 'UNKNOWN')),
    CONSTRAINT ck_line_removal_disposition CHECK (
        disposition_declared IN ('RETURN_TO_STOCK', 'WRITE_OFF', 'NO_EFFECT')
        AND disposition_effective IN ('RETURN_TO_STOCK', 'WRITE_OFF', 'NO_EFFECT')),
    CONSTRAINT uq_line_removal_line UNIQUE (amendment_id, order_line_id),
    CONSTRAINT fk_line_removal_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_line_removal_line FOREIGN KEY (order_line_id, tenant_id)
        REFERENCES ordering.order_lines (id, tenant_id),
    CONSTRAINT fk_line_removal_reason FOREIGN KEY (reason_id, tenant_id)
        REFERENCES ordering.order_outcome_reasons (id, tenant_id)
);

CREATE INDEX ix_line_removals_order ON ordering.order_line_removals (tenant_id, order_id, occurred_at);
CREATE INDEX ix_line_removals_report ON ordering.order_line_removals (tenant_id, location_id, occurred_at);

COMMENT ON TABLE ordering.order_line_removals IS
    'ADR 0169. What an applied amendment took off a confirmed order: one row per line per amendment, append-only. The reason is snapshotted; the unit counts say what inventory did (released, returned, written off, no effect), and the movement ids point at the ledger.';

-- ------------------------------------------------------------------------------ 3. the disposal record

CREATE TABLE inventory.stock_disposals (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,
    -- The caller's key. A replay with it returns this disposal and moves nothing.
    idempotency_key varchar(255) NOT NULL,
    declared varchar(24) NOT NULL,
    reason_code varchar(64) NOT NULL,
    actor_type varchar(16) NOT NULL,
    actor_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_stock_disposal_declared CHECK (declared IN ('RETURN_TO_STOCK', 'WRITE_OFF', 'NO_EFFECT')),
    CONSTRAINT uq_stock_disposal_key UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_stock_disposal_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_stock_disposals_order ON inventory.stock_disposals (tenant_id, order_id);

CREATE TABLE inventory.stock_disposal_lines (
    disposal_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    variant_id uuid NOT NULL,
    units integer NOT NULL,
    released_units integer NOT NULL DEFAULT 0,
    returned_units integer NOT NULL DEFAULT 0,
    written_off_units integer NOT NULL DEFAULT 0,
    no_effect_units integer NOT NULL DEFAULT 0,
    movement_ids uuid[] NOT NULL DEFAULT '{}',

    PRIMARY KEY (disposal_id, stock_item_id),
    CONSTRAINT ck_stock_disposal_line_units CHECK (
        units > 0 AND released_units >= 0 AND returned_units >= 0 AND written_off_units >= 0
        AND no_effect_units >= 0
        AND released_units + returned_units + written_off_units + no_effect_units = units),
    CONSTRAINT fk_stock_disposal_line_disposal FOREIGN KEY (disposal_id, tenant_id)
        REFERENCES inventory.stock_disposals (id, tenant_id),
    CONSTRAINT fk_stock_disposal_line_item FOREIGN KEY (stock_item_id, tenant_id)
        REFERENCES inventory.stock_items (id, tenant_id)
);

COMMENT ON TABLE inventory.stock_disposals IS
    'ADR 0169. One call of the dispose verb, keyed by the caller''s idempotency key so a replay returns it and moves nothing. The units a disposal returned, wrote off or took without effect are also what keeps a later disposal for the same order from counting them twice.';

SELECT platform.enable_tenant_row_level_security('inventory.stock_disposals');
SELECT platform.enable_tenant_row_level_security('inventory.stock_disposal_lines');

-- ------------------------------------------------------------------------ 4. a held line may reach zero

ALTER TABLE inventory.reservation_lines DROP CONSTRAINT ck_reservation_line_quantity;
ALTER TABLE inventory.reservation_lines
    ADD CONSTRAINT ck_reservation_line_quantity CHECK (quantity >= 0);

-- ------------------------------------------------------------------------------------------------ grants
--
-- The removal evidence and the disposal record are written once and read; nothing in them is edited.
GRANT SELECT, INSERT ON ordering.order_line_removals TO horecaos_application;
GRANT SELECT, INSERT ON inventory.stock_disposals TO horecaos_application;
GRANT SELECT, INSERT ON inventory.stock_disposal_lines TO horecaos_application;
