-- Idempotency records identified an operation and a tenant, but neither the
-- resource acted on nor the caller.
--
-- Two consequences followed, and the first is the ordinary one that would have
-- shown up in production without anybody attacking. One customer opening a
-- payment session for order A and then for order B, reusing a key, was answered
-- the second time with order A's cached response -- its checkout link, its
-- amount, its merchant transaction id -- while order B never had an attempt
-- opened at all and sat in PAYMENT_AUTHORIZING forever. The request hash could
-- not tell them apart, because the body on that endpoint is a few optional
-- fields that serialise to {} for nearly every caller.
--
-- The second is that two different customers in one tenant who happened to
-- choose the same key value collided the same way, across accounts. The replay
-- is written before the handler runs, so the ownership check inside
-- PaymentCheckoutService -- which does compare the caller against the order --
-- was never reached.
--
-- The resource half is fixed in the application: scope_key now carries the
-- request's path variables, so two orders are two scopes. The caller half is
-- fixed here and in the lookup, which now matches on principal_subject.
--
-- principal_subject has been written on every row since V0006; it was simply
-- never read back. That is why this can be an index change rather than a
-- backfill.

DROP INDEX IF EXISTS platform.uq_idempotency_scope_key_tenant;
DROP INDEX IF EXISTS platform.uq_idempotency_scope_key_platform;

-- Column order is chosen for the read as well as for the constraint. The lookup
-- matches scope_key, idempotency_key and principal_subject by equality, so they
-- lead; tenant_id is compared with IS NOT DISTINCT FROM, which cannot drive an
-- index scan, and sits last where it is a filter rather than a seek.
CREATE UNIQUE INDEX uq_idempotency_scope_key_tenant
    ON platform.idempotency_records
       (scope_key, idempotency_key, principal_subject, tenant_id)
    WHERE tenant_id IS NOT NULL;

CREATE UNIQUE INDEX uq_idempotency_scope_key_platform
    ON platform.idempotency_records (scope_key, idempotency_key, principal_subject)
    WHERE tenant_id IS NULL;
