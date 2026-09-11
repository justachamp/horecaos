-- ADR 0095: a statement has at most one unresolved card charge attempt.
--
-- V0214 gave every attempt its own id as its idempotency key, so a retry of
-- the same attempt never collides with a charge for a different amount. It
-- did not stop two different attempts from existing for the same statement
-- at once. `WalletService.beginCardAttempt` takes the tenant's billing lock
-- only for the short transaction that commits the PENDING row, and releases
-- it before the provider is ever asked -- so an attempt left PENDING by an
-- unanswered provider call (a timeout) was invisible to the next settlement
-- pass, which minted a brand-new attempt and a brand-new key for the same
-- remainder; and two settlement passes for the same tenant landing close
-- together could each commit their own PENDING attempt for the same
-- statement before either had called the provider. Both are the same
-- defect: nothing said an unresolved attempt for a statement is the only
-- one that may exist. A provider that honours idempotency keys then saw two
-- unrelated charges for one statement's remainder, and genuinely debited
-- the tenant's card twice.
--
-- This index is what actually closes the race the application-level check
-- in `beginCardAttempt` cannot: that check runs under the tenant's billing
-- lock, which serialises callers for one tenant but is not the same thing
-- as a database constraint holding regardless of what any future code path
-- does. `beginCardAttempt` re-reads for an existing PENDING attempt before
-- inserting a new one and reuses it; this index is what turns a second,
-- racing INSERT into a refusal the application catches and reads back
-- rather than a second row silently committed beside the first.
CREATE UNIQUE INDEX ux_card_charge_attempt_one_pending
    ON commercial.card_charge_attempts (tenant_id, statement_id)
    WHERE outcome = 'PENDING';
