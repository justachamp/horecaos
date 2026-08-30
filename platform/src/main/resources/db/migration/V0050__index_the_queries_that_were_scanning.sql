-- Indexes for query patterns that were sequentially scanning tables which grow
-- without bound.
--
-- Each of these was found by taking a query the application actually issues and
-- checking whether any existing index leads with the columns it filters on. All
-- of them are invisible on a development database and get continuously worse in
-- production, which is the failure mode worth catching before the pilot rather
-- than during it.

-- The nightly day-close refund read.
--
-- JdbcReportingStore.readSourceRefunds filters
--   tenant_id = ? AND transaction_type = 'REFUND' AND occurred_at >= ? AND < ?
-- and the two existing indexes on this table lead with attempt_id and intent_id,
-- so neither helps. payment_transactions is append-only and unbounded, and the
-- close runs once per tenant per night.
--
-- transaction_type sits in the key rather than in a WHERE clause so the same
-- index also serves CAPTURE and REVERSE windows. The partial alternative
-- ((tenant_id, occurred_at) WHERE transaction_type = 'REFUND') is a smaller
-- object that serves only this one query; the settlement reconciliation reads
-- the other types over the same window, so the shared form wins.
CREATE INDEX ix_payment_transactions_tenant_refund_window
    ON payments.payment_transactions (tenant_id, transaction_type, occurred_at);

-- Menu validate and publish.
--
-- Both tables have a primary key leading with entity_type and nothing on the
-- tenant, so JdbcCatalogStore.translations() and mediaRelations() scan a
-- platform-wide table -- every brand's rows -- to assemble one brand's menu.
-- CatalogPublicationService loads the snapshot twice on a publish, once to
-- validate and once to publish, so a single publish was four full scans of two
-- shared tables.
CREATE INDEX ix_translation_by_brand
    ON catalog.translations (tenant_id, brand_id);

CREATE INDEX ix_media_relation_by_brand
    ON catalog.media_relations (tenant_id, brand_id);

-- The nightly marketing recomputation.
--
-- JdbcCustomerMetricStore groups ordering.orders by (tenant_id, brand_id), and
-- ix_orders_customer leads with (tenant_id, customer_account_id), so the
-- aggregation scanned the order table. Partial because a row with no customer
-- account contributes to no customer metric, and orders is the largest table
-- here -- indexing the anonymous rows would cost write throughput on the hot
-- path to speed up a nightly job.
CREATE INDEX ix_orders_brand_customer
    ON ordering.orders (tenant_id, brand_id, customer_account_id)
    WHERE customer_account_id IS NOT NULL;

-- The four-eyes aggregate on loyalty adjustments.
--
-- LoyaltyAdjustmentService now sums one actor's recent adjustments, which is the
-- half of the split-adjustment manoeuvre that a per-account total cannot see:
-- one operator spreading small credits across many accounts. Partial, because
-- adjustments are a small minority of a ledger that is mostly accrual and
-- redemption.
CREATE INDEX ix_loyalty_entries_actor_recent
    ON loyalty.entries (tenant_id, actor, recorded_at DESC)
    WHERE entry_type = 'ADJUSTMENT';
