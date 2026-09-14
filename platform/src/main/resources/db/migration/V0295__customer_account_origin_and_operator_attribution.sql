-- ADR 0015 / ADR 0039, wave P14: which surface created a customer account, and
-- who, when that surface was a staff member rather than the person themself.
--
-- ADR 0015 sketched these two columns when it defined the account shape and
-- never built them; ADR 0039's own status line has carried "ADR 0015's
-- origin/created_by_actor_id columns this ADR sketched for that account
-- remain not built" since the New order screen's customer pane first learned
-- to create an account on a phone-order miss. Two callers write
-- `customer.customer_accounts` today with no principal behind the row —
-- `CustomerIdentityService#createAccountWithoutPrincipal`, shared by the CSV
-- import (`CustomerImportDirectoryService`) and the operator's own
-- "Создать клиента" (`CustomerController#createManually`) — and until now
-- neither the row nor a report could tell the two apart, or tell either apart
-- from an ordinary self-service sign-in. That matters beyond bookkeeping: an
-- operator-created account is deliberately non-contactable for marketing
-- (ADR 0039, "absence of a decision is not consent"), and a marketing export
-- with no `origin` column to filter on has no way to honour that.
--
-- `SELF_SERVICE` is the default so every existing row and every unmodified
-- sign-in write (`CustomerIdentityService#create`) keeps meaning what it
-- always meant, with no backfill required.

ALTER TABLE customer.customer_accounts
    ADD COLUMN origin varchar(16) NOT NULL DEFAULT 'SELF_SERVICE',
    -- Null for SELF_SERVICE and IMPORT, which have no one staff member to
    -- name. Not a foreign key to any staff table: the value is a Keycloak
    -- subject or a service account name, the same loosely-typed identifier
    -- `ordering.orders.created_by_actor_id` (V0029) already carries for the
    -- identical reason — the account this column names may since have been
    -- deactivated, and the historical fact must survive that.
    ADD COLUMN created_by_actor_id varchar(255);

ALTER TABLE customer.customer_accounts
    ADD CONSTRAINT ck_customer_account_origin CHECK (
        origin IN ('SELF_SERVICE', 'OPERATOR', 'IMPORT', 'AGGREGATOR', 'MIGRATION')),
    -- An operator-created account is exactly the one this column exists to
    -- audit; a row claiming that origin with nobody named is the gap this
    -- migration was written to close, reappearing under a different name.
    ADD CONSTRAINT ck_customer_account_operator_actor CHECK (
        origin <> 'OPERATOR' OR created_by_actor_id IS NOT NULL);

COMMENT ON COLUMN customer.customer_accounts.origin IS
    'ADR 0015/0039. SELF_SERVICE for a sign-in-created account, OPERATOR for "Создать клиента" on the New order screen, IMPORT for CustomerCsvImportRowService, AGGREGATOR reserved for a future marketplace-matched account (none is created today, ADR 0040), MIGRATION reserved for the later legacy cutover.';
COMMENT ON COLUMN customer.customer_accounts.created_by_actor_id IS
    'ADR 0039. The staff subject who created this account by hand, set only when origin = OPERATOR. Never a foreign key: the identity may since have left, and the historical fact must outlive it.';

-- No new GRANT: V0017 already grants SELECT, INSERT, UPDATE on this table to
-- horecaos_application, and an added column needs nothing further.
