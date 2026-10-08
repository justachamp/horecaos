-- ADR 0161 (row X.35): one mark per physical unit of a marked order line.
--
-- A marked good is one identifier per physical unit, so quantity stops being a number the customer
-- chose and becomes a set of specific objects somebody scans at the pass. ADR 0038 sketched the store as
-- fiscal.fiscal_unit_marks; ADR 0161 puts it in `ordering`, because the guard that holds an order at the
-- pass (OrderStateService) must count the marks in the same transaction as the status change, and a
-- fiscal table read from ordering would close a module cycle (fiscal and payments import ordering.api;
-- ordering imports neither).
--
-- A mark is a row, not a column on order_lines: an array cannot carry one AAD-bound ciphertext per code,
-- cannot enforce "unique while live" per tenant, cannot be released one unit at a time and cannot be
-- audited per unit. A mark is RELEASED, never deleted (no DELETE grant): the same physical unit can be
-- scanned again once a removal, a cancellation or an amendment has stamped released_at and a reason.
--
-- The code itself is an ADR 0029 FINANCIAL envelope value bound to its row (so a ciphertext copied to
-- another row fails to decrypt), and code_lookup_hash is the per-tenant keyed hash the "unique among live
-- marks" index and the duplicate check use. The GTIN in clear is a product identifier, not a secret.
-- Nothing here carries a customer, an address or a note: a customer erasure leaves marks alone.
--
-- No row-level-security policy in v1. ADR 0056 ratifies application-enforced tenant isolation and has
-- rolled the backstop out to `inventory` only; no `ordering` table has a policy, and an unbound path under
-- a policy would count zero live marks, which the guard reads as "every marked order is incomplete". The
-- table is isolated as the rest of `ordering` is (a tenant_id predicate in every statement and composite
-- foreign keys) until ADR 0056's rollout reaches the schema.
CREATE TABLE ordering.order_line_marks (
    id                     uuid PRIMARY KEY,
    tenant_id              uuid NOT NULL,
    order_id               uuid NOT NULL,
    order_line_id          uuid NOT NULL,
    -- 1..N within the line; gaps are allowed after a release.
    sequence               integer NOT NULL,
    marking_scheme         varchar(16) NOT NULL DEFAULT 'DATA_MATRIX',
    gtin                   varchar(14) NOT NULL,
    code_encrypted         text NOT NULL,
    code_lookup_hash       varchar(64) NOT NULL,
    -- Derived by the server from the order's status, never chosen by the client.
    capture_stage          varchar(8)  NOT NULL,
    -- Client-reported and advisory.
    entry_method           varchar(8)  NOT NULL,
    -- False when the classification had no barcode to compare the GTIN with.
    gtin_checked           boolean     NOT NULL,
    captured_by_actor_type varchar(16) NOT NULL,
    captured_by_actor_id   varchar(255) NOT NULL,
    captured_at            timestamptz NOT NULL,
    released_at            timestamptz,
    released_reason        varchar(24),
    CONSTRAINT fk_order_line_mark_line FOREIGN KEY (order_line_id, tenant_id)
        REFERENCES ordering.order_lines (id, tenant_id),
    CONSTRAINT fk_order_line_mark_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT ck_order_line_mark_scheme CHECK (marking_scheme IN ('DATA_MATRIX')),
    CONSTRAINT ck_order_line_mark_stage  CHECK (capture_stage IN ('PICK', 'HANDOVER')),
    CONSTRAINT ck_order_line_mark_method CHECK (entry_method IN ('SCANNER', 'CAMERA', 'KEYED')),
    CONSTRAINT ck_order_line_mark_gtin   CHECK (gtin ~ '^[0-9]{14}$'),
    CONSTRAINT ck_order_line_mark_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_order_line_mark_release CHECK ((released_at IS NULL) = (released_reason IS NULL)),
    CONSTRAINT ck_order_line_mark_release_reason CHECK (
        released_reason IS NULL
        OR released_reason IN ('MISSCAN', 'UNIT_REPLACED', 'UNIT_DAMAGED', 'ORDER_CANCELLED', 'LINE_REPLACED'))
);

-- A code is unique among the LIVE marks of a tenant: the same physical unit cannot be on two orders, or
-- twice on one, until a release frees it.
CREATE UNIQUE INDEX uq_order_line_marks_live_code
    ON ordering.order_line_marks (tenant_id, code_lookup_hash) WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_order_line_marks_live_sequence
    ON ordering.order_line_marks (order_line_id, sequence) WHERE released_at IS NULL;
-- The guard's count and the projection's read.
CREATE INDEX ix_order_line_marks_order
    ON ordering.order_line_marks (tenant_id, order_id) WHERE released_at IS NULL;

-- No DELETE: evidence is released, not removed (V0450 argued the same for the weighed columns).
GRANT SELECT, INSERT ON ordering.order_line_marks TO horecaos_application;
GRANT UPDATE (released_at, released_reason) ON ordering.order_line_marks TO horecaos_application;

COMMENT ON TABLE ordering.order_line_marks IS
    'ADR 0161: one scanned marking code per physical unit of a marked order line. Released, never deleted. code_encrypted is ADR 0029 FINANCIAL.';
COMMENT ON COLUMN ordering.order_line_marks.code_encrypted IS
    'The canonical code, AAD-bound to ordering.order_line_marks.code_encrypted:<id>. Revealed only by payments, for a receipt.';
