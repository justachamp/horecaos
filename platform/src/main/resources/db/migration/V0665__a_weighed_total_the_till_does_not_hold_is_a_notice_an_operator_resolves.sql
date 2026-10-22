-- ADR 0137, ADR 0039 (batch 21, row 4.2c): when the weight captured at the pass moves the total of an order
-- the till already holds, the platform says so instead of leaving the till silently at the nominal weight.
--
-- A catchweight line is checked out at the menu's nominal weight and the scale corrects it before handover
-- (CatchweightReconciliationService). If the order was exported to the till before that, the till still holds
-- the nominal total and the platform now holds the weighed one. Whether the till can be corrected is a fact
-- about the vendor (Clopos: PUT /orders/{id} accepts only status IGNORE, OrderProduct.count is an integer,
-- docs/providers/clopos-api.md 6.1 and Q10), so today it is usually a person at the till.
--
-- One OPEN notice per order at a time. A second weighing before anyone has acted updates the same notice
-- (the till still holds the total it held when the notice opened; only the revised figure and revision move),
-- so the banner states the one difference that matters and the alert goes out once. A notice ends by being
-- TOLD (the platform sent the revised amount to a till whose vendor accepts one) or ACKNOWLEDGED (a person
-- says the till now agrees). It is never deleted: no DELETE is granted.
--
-- Only money and codes. No customer, no line name, no free text: a person's remark about what they did at the
-- till is not collected, because a remark is where a phone number ends up.
--
-- No row-level-security policy, for the reason V0557 gives: no `ordering` table has one yet (ADR 0056).

CREATE TABLE ordering.order_till_revision_notices (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL,
    brand_id             uuid NOT NULL,
    location_id          uuid NOT NULL,
    order_id             uuid NOT NULL,
    -- What moved the total. Only a captured weight can, today: an amendment is refused while the till holds
    -- the order (OrderAmendmentService#requirePosExportSettled).
    source               varchar(16) NOT NULL,
    -- The order revision whose total the till does not hold.
    revision             integer NOT NULL,
    currency             char(3) NOT NULL,
    -- What the till was last told: the order's total when this notice opened.
    till_total_minor     bigint NOT NULL,
    revised_total_minor  bigint NOT NULL,
    status               varchar(16) NOT NULL DEFAULT 'OPEN',
    opened_at            timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    resolved_at          timestamptz,
    resolved_by          varchar(255),
    attempt_count        integer NOT NULL DEFAULT 0,
    last_attempt_at      timestamptz,
    last_failure_code    varchar(64),
    CONSTRAINT fk_till_revision_notice_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT ck_till_revision_notice_source CHECK (source IN ('CATCHWEIGHT')),
    CONSTRAINT ck_till_revision_notice_status CHECK (status IN ('OPEN', 'TOLD', 'ACKNOWLEDGED')),
    CONSTRAINT ck_till_revision_notice_revision CHECK (revision >= 2),
    CONSTRAINT ck_till_revision_notice_differs CHECK (till_total_minor <> revised_total_minor),
    CONSTRAINT ck_till_revision_notice_resolution CHECK (
        (status = 'OPEN') = (resolved_at IS NULL AND resolved_by IS NULL)),
    CONSTRAINT ck_till_revision_notice_attempts CHECK (attempt_count >= 0)
);

-- At most one open notice per order: a second weighing updates it rather than opening another.
CREATE UNIQUE INDEX uq_till_revision_notice_open
    ON ordering.order_till_revision_notices (tenant_id, order_id) WHERE status = 'OPEN';
CREATE INDEX ix_till_revision_notice_order
    ON ordering.order_till_revision_notices (tenant_id, order_id, opened_at DESC);
-- The location's open work, for a banner or a count.
CREATE INDEX ix_till_revision_notice_location_open
    ON ordering.order_till_revision_notices (tenant_id, location_id) WHERE status = 'OPEN';

-- No DELETE: a notice is closed, not removed.
GRANT SELECT, INSERT, UPDATE ON ordering.order_till_revision_notices TO horecaos_application;

COMMENT ON TABLE ordering.order_till_revision_notices IS
    'ADR 0137, row 4.2c. An order the till already holds whose total changed afterwards (a captured weight). OPEN until the platform has told the till (TOLD) or a person says the till agrees (ACKNOWLEDGED). Money and codes only.';
COMMENT ON COLUMN ordering.order_till_revision_notices.till_total_minor IS
    'What the till holds: the order total when the notice opened. Not updated by a second weighing.';

-- ---------------------------------------------------------------------------
-- The capability that would let the platform tell the till, and the ceilings
-- ---------------------------------------------------------------------------
--
-- ORDER_REVISION: tell a vendor the order it holds is now worth a different total. Every vendor starts
-- unassessed, and integration.pos_capability_is_supported() refuses an unassessed capability on a binding,
-- so no real vendor is claimed. Clopos is RECORDED, with the reason it cannot: PUT /orders/{id} accepts only
-- status IGNORE, and a line count is an integer. iiko and R-Keeper stay absent (nothing in this repository
-- says what either offers). The fake supports it so the path is proved against something.

INSERT INTO integration.pos_provider_capabilities
    (provider_type, capability_code, support_level, rationale, evidence_source, assessed_on)
VALUES
    ('clopos', 'ORDER_REVISION', 'UNSUPPORTED',
     'PUT /orders/{id} accepts a body of {"status": "IGNORE"} and nothing else, and only while the order is PENDING; OrderProduct.count is typed integer, so a weighed quantity has no representation either (Q10, still unanswered). There is no documented way to change an exported order''s total. A person corrects the till.',
     'clopos /api-reference/v2/orders/update-order; docs/providers/clopos-api.md section 6.1 and Q10', DATE '2026-10-14'),
    ('fake-pos', 'ORDER_REVISION', 'SUPPORTED',
     'The controlled fake accepts a revised total for an order it holds, deduplicating on the idempotency key, with its failure modes injectable. It exists so telling a till the weighed amount is proved against more than the absence Clopos presents.',
     'ADR 0137 controlled fake provider', DATE '2026-10-14');

-- ---------------------------------------------------------------------------
-- A chat may hear that a weighed total needs the till changed
-- ---------------------------------------------------------------------------
--
-- Restates the full current list, as V0544 did, rather than adding a clause: a bare DROP/ADD silently drops
-- every value the previous migration knew about if the list is not carried forward from the latest one.
ALTER TABLE integration.telegram_binding_events DROP CONSTRAINT ck_telegram_binding_event_class;
ALTER TABLE integration.telegram_binding_events
    ADD CONSTRAINT ck_telegram_binding_event_class CHECK (
        event_class IN (
            'ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_APPROVAL_DEADLINE_WARNING',
            'ORDER_AWAITING_APPROVAL',
            'DIGEST_15M', 'DIGEST_HALF_DAY', 'DIGEST_DAY_CLOSE',
            'PLATFORM_DIGEST_HALF_DAY', 'PLATFORM_DIGEST_DAY_CLOSE',
            'PAYMENT_ATTEMPT_FAILED', 'PAYMENT_ATTEMPT_NEEDS_OPERATOR',
            'FISCAL_DOCUMENT_BLOCKED',
            'ITEM_86D',
            'DEAD_LETTER_RECORDED', 'POS_EXPORT_AWAITING_OPERATOR',
            'CAMPAIGN_BLOCK_RATE_PAUSED',
            'MARKETPLACE_CHANNEL_STALE',
            'PRINTER_OFFLINE', 'PRINT_JOB_DEAD',
            'TERMINAL_CREDENTIAL_LOCKED', 'TERMINAL_DEVICE_UNSEEN',
            'RECOVERY_CASE_OPENED', 'RECOVERY_CASE_SLA_BREACHED',
            'TILL_REVISION_NEEDED'));
