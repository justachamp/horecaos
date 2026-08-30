-- A tender remembered what it settled and not what it had already given back.
--
-- OrderSettlementService.refund computed each tender's refundable amount as
-- min(outstanding, tender.amount_minor) -- the tender's ORIGINAL amount, on every
-- call. Nothing recorded prior refunds: settled_minor on the settlement row was
-- never decremented, and a tender only left SETTLED when a single call happened
-- to consume it exactly, so a partial refund left it fully refundable again. The
-- guard that a refund "cannot exceed what the tenders settled" balanced only
-- within one invocation.
--
-- So a 100 000 som tender refunded 60 000 and then 60 000 again returned 120 000,
-- both calls reporting success. The same arithmetic drove the points reversal,
-- which means a 12 000 som points tender could be reversed 24 000 -- the
-- points-to-cash conversion at par that the tender ordering exists to prevent.
--
-- Nothing calls the settlement service in this build, so no row has been
-- refunded and the backfill is a constant zero. That is the only reason this is
-- a default rather than a reconstruction from the transaction ledger, and it is
-- why fixing it now is much cheaper than fixing it after the refund endpoint
-- exists.

ALTER TABLE payments.tenders
    ADD COLUMN refunded_minor bigint NOT NULL DEFAULT 0;

-- The invariant the code was asserting in a comment. A database that permits
-- refunded_minor > amount_minor permits the bug above, whatever the service does
-- next; the constraint is what makes a future writer's mistake a failed
-- statement rather than money leaving the tenant.
ALTER TABLE payments.tenders
    ADD CONSTRAINT ck_tender_refunded_within_amount
        CHECK (refunded_minor >= 0 AND refunded_minor <= amount_minor);
