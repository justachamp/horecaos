-- The four references V0077 deferred, and the reason it deferred them is gone.
--
-- V0077 named the tenant in fourteen foreign keys and left nine in
-- tools/checks/known_tenant_blind_references.tsv. Five of those nine point at a
-- table whose tenant_id is nullable by design — a platform-owned row underneath
-- a tenant-owned one — and a composite key cannot express that; they need a
-- resolution rule in the referencing service and they stay where they are.
--
-- The other four were deferred for a scheduling reason and not a technical one:
-- ordering, payments and loyalty were under concurrent change, and a constraint
-- added to a table somebody else is migrating is a merge conflict that surfaces
-- at deploy time rather than at review time. That work has landed.
--
-- ONE CORRECTION TO THE ALLOWLIST, because it mattered to the design. The entry
-- for loyalty.lots said "source_entry_id is nullable, so the repair is
-- copy-then-clear". It is `source_entry_id uuid NOT NULL` (V0042 line 509), and
-- payments.payment_intents.order_id is NOT NULL too (V0027 line 196). So
-- copy-then-clear is available for two of these four and not for the other two,
-- which is why they are treated differently below rather than uniformly.

-- ---------------------------------------------------------------------------
-- 1. The two whose pointer is nullable: quarantine, then clear
-- ---------------------------------------------------------------------------
--
-- A cart or an order naming another tenant's publication is a row whose price
-- and availability came from a catalogue its tenant cannot see. The pointer is
-- nullable — a cart may legitimately have no publication — so the row survives
-- with its provenance recorded, which is V0077's POINTER_CLEARED disposition.

INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT cart.tenant_id, 'ordering.carts', 'fk_cart_publication', cart.id::text,
       'catalog_publication_id', 'catalog.publications', cart.catalog_publication_id,
       'POINTER_CLEARED', 'PUBLICATION_IN_ANOTHER_TENANT'
FROM ordering.carts AS cart
WHERE cart.catalog_publication_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM catalog.publications AS publication
      WHERE publication.id = cart.catalog_publication_id
        AND publication.tenant_id = cart.tenant_id);

UPDATE ordering.carts AS cart
   SET catalog_publication_id = NULL
 WHERE cart.catalog_publication_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM catalog.publications AS publication
       WHERE publication.id = cart.catalog_publication_id
         AND publication.tenant_id = cart.tenant_id);

INSERT INTO tenant.cross_tenant_reference_quarantine (
    tenant_id, source_table, constraint_name, source_row_key,
    referencing_column, referenced_table, referenced_id,
    disposition, quarantine_reason)
SELECT "order".tenant_id, 'ordering.orders', 'fk_order_publication', "order".id::text,
       'catalog_publication_id', 'catalog.publications', "order".catalog_publication_id,
       'POINTER_CLEARED', 'PUBLICATION_IN_ANOTHER_TENANT'
FROM ordering.orders AS "order"
WHERE "order".catalog_publication_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM catalog.publications AS publication
      WHERE publication.id = "order".catalog_publication_id
        AND publication.tenant_id = "order".tenant_id);

UPDATE ordering.orders AS "order"
   SET catalog_publication_id = NULL
 WHERE "order".catalog_publication_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM catalog.publications AS publication
       WHERE publication.id = "order".catalog_publication_id
         AND publication.tenant_id = "order".tenant_id);

-- ---------------------------------------------------------------------------
-- 2. The two that carry money: refuse rather than repair
-- ---------------------------------------------------------------------------
--
-- payment_intents.order_id and loyalty.lots.source_entry_id are both NOT NULL,
-- so neither can be cleared, and the only mechanical repair left is to delete
-- the row. Both rows are money — an intent is what a provider was asked to
-- collect, and a lot is points a customer holds under ADR 0021 — and a migration
-- that silently destroys a money record to satisfy a constraint has done
-- something far worse than the constraint was protecting against.
--
-- So this refuses the deployment instead, the way V0060 refuses rather than
-- re-partitioning customers who already exist. On any database where the hole
-- was never exercised it is a no-op, which is every database today: the local
-- estate reports zero for both. If it ever fires, the answer is a person
-- deciding what that intent or that lot was supposed to point at, which is not
-- a decision DDL gets to make at three in the morning.
DO $$
DECLARE
    stray_intents bigint;
    stray_lots bigint;
BEGIN
    SELECT count(*) INTO stray_intents
      FROM payments.payment_intents AS intent
      JOIN ordering.orders AS "order" ON "order".id = intent.order_id
     WHERE "order".tenant_id <> intent.tenant_id;

    SELECT count(*) INTO stray_lots
      FROM loyalty.lots AS lot
      JOIN loyalty.entries AS entry ON entry.id = lot.source_entry_id
     WHERE entry.tenant_id <> lot.tenant_id;

    IF stray_intents > 0 OR stray_lots > 0 THEN
        RAISE EXCEPTION
            'V0084: % payment intent(s) and % loyalty lot(s) reference another '
            'tenant''s row. Both columns are NOT NULL and both rows are money, so '
            'this migration will not clear them and will not delete them. Decide '
            'what each was meant to point at, correct it, and redeploy.',
            stray_intents, stray_lots;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 3. The unique key loyalty.entries did not have
-- ---------------------------------------------------------------------------
--
-- A foreign key must reference a unique constraint on exactly its own columns.
-- catalog.publications got uq_publication_identity in V0077 and ordering.orders
-- has had uq_order_identity since V0022; loyalty.entries has only its primary
-- key on id and the idempotency key, so the composite reference below has
-- nothing to point at until this exists.
ALTER TABLE loyalty.entries
    ADD CONSTRAINT uq_loyalty_entry_identity UNIQUE (id, tenant_id);

-- ---------------------------------------------------------------------------
-- 4. Name the tenant
-- ---------------------------------------------------------------------------

ALTER TABLE ordering.carts
    DROP CONSTRAINT fk_cart_publication,
    ADD CONSTRAINT fk_cart_publication
        FOREIGN KEY (catalog_publication_id, tenant_id)
        REFERENCES catalog.publications (id, tenant_id);

ALTER TABLE ordering.orders
    DROP CONSTRAINT fk_order_publication,
    ADD CONSTRAINT fk_order_publication
        FOREIGN KEY (catalog_publication_id, tenant_id)
        REFERENCES catalog.publications (id, tenant_id);

ALTER TABLE payments.payment_intents
    DROP CONSTRAINT fk_payment_intent_order,
    ADD CONSTRAINT fk_payment_intent_order
        FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id);

ALTER TABLE loyalty.lots
    DROP CONSTRAINT fk_loyalty_lot_source,
    ADD CONSTRAINT fk_loyalty_lot_source
        FOREIGN KEY (source_entry_id, tenant_id)
        REFERENCES loyalty.entries (id, tenant_id);

COMMENT ON CONSTRAINT fk_payment_intent_order ON payments.payment_intents IS
    'Composite since V0084: an intent settles against a total, and an intent '
    'naming another tenant''s order settles against a total that tenant cannot see.';

COMMENT ON CONSTRAINT fk_loyalty_lot_source ON loyalty.lots IS
    'Composite since V0084: a lot is points a customer holds, and the entry that '
    'created it has to belong to the same tenant as the lot it created.';
