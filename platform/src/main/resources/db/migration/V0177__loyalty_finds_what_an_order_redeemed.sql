-- ADR 0046, ADR 0067: closing the gap both records name -- nothing calls
-- LoyaltyAccrualService.accrue or ReferralQualificationService.onOrderOutcome
-- from a real order-completion fact. The new OrderCompletionAccrualTrigger
-- (loyalty) reads how much of a just-completed order was discharged from
-- points, net of which is what "the money the customer paid" means for
-- accrual, by summing loyalty.reservations for that order.
--
-- No index existed for that lookup: loyalty.reservations carries
-- fk_loyalty_reservation_order (order_id, tenant_id) but PostgreSQL never
-- creates an index for a foreign key's referencing side on its own, and the
-- table's own unique constraints are keyed on id and on tender_id, neither of
-- which a query filtered on order_id can use. This is that index, and it
-- carries status so JdbcLoyaltyStore#settledRedemptionMinor's
-- "status = 'SETTLED'" predicate is answered from the index rather than by
-- fetching every reservation an order ever held (a released hold, a stale
-- one the sweep is about to return) to throw most of them away.
--
-- No new table, and therefore no new GRANT: loyalty.reservations already
-- carries the application role's SELECT, INSERT and UPDATE from V0042.
CREATE INDEX ix_loyalty_reservation_order
    ON loyalty.reservations (tenant_id, order_id, status);

COMMENT ON INDEX loyalty.ix_loyalty_reservation_order IS
    'ADR 0046, ADR 0067. What one order redeemed from points and kept redeemed, read by the order-completion accrual trigger.';
