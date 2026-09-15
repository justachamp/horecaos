-- T13 (7.6a/7.6b): customer analytics — cohort/retention and the RFM cross-tab
-- both group reporting.fact_order by customer_subject_hash, joined back to
-- itself for the cohort read. Neither existing index leads with that column
-- (ix_fact_order_location_day and ix_fact_order_channel_day lead with
-- location/channel; ix_fact_order_source is (tenant_id, order_id)), so both
-- reads would otherwise scan every partition in range.
--
-- Created on the partitioned parent, not each partition: PostgreSQL creates
-- a matching index on every existing partition automatically, and on every
-- partition created afterward through reporting.ensure_fact_partition (the
-- same behaviour the four V0031 indexes on this table already rely on).
CREATE INDEX ix_fact_order_customer
    ON reporting.fact_order (tenant_id, customer_subject_hash, business_date);

COMMENT ON INDEX reporting.ix_fact_order_customer IS
    'T13 (7.6a/7.6b): supports the cohort self-join and the RFM/customer-kpi GROUP BY customer_subject_hash. Partial rather than full only in effect — most rows in range carry a hash, so a WHERE customer_subject_hash IS NOT NULL predicate index would buy little.';
