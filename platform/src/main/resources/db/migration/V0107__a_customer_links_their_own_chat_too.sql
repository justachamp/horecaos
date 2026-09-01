-- ADR 0058 stage 2, the customer half: a customer's own 1:1 Telegram chat is a
-- binding, the same shape this record already gives OPERATIONS and PLATFORM
-- chats — not a dedicated identity table like ADR 0060's staff /link (V0105).
-- Staff linking got its own table because it is an identity fact with no chat
-- subscription behind it (BotCallbackAuthorizer re-checks authority live, at
-- tap time, never from that table). A customer's link is the opposite: it must
-- actually receive fanned-out messages, which is exactly what
-- integration.bindings + integration.telegram_bindings +
-- notifications.recipient_endpoints already do for OPERATIONS and PLATFORM.
-- Widening the existing shape reuses that delivery path outright instead of
-- inventing a second one this record's own reasoning above rejects.

-- --------------------------------------------------- 1. audience CHECK widening

-- V0099 admitted only 'OPERATIONS'; V0103 rewrote both constraints to
-- ('OPERATIONS', 'PLATFORM') for the control-plane digest audience. V0104 and
-- V0106 both warn, in their own comments, that a DROP/ADD recreation of a CHECK
-- constraint replaces the whole value list rather than adding to it — the full
-- current list is restated here, plus 'CUSTOMER'.
ALTER TABLE integration.telegram_bindings
    DROP CONSTRAINT ck_telegram_binding_audience;
ALTER TABLE integration.telegram_bindings
    ADD CONSTRAINT ck_telegram_binding_audience CHECK (audience IN ('OPERATIONS', 'PLATFORM', 'CUSTOMER'));

ALTER TABLE integration.telegram_pending_links
    DROP CONSTRAINT ck_telegram_pending_link_audience;
ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_audience CHECK (audience IN ('OPERATIONS', 'PLATFORM', 'CUSTOMER'));

-- ------------------------------------------------ 2. pending link customer shape

-- A CUSTOMER-audience pending link is minted from the customer's own ADR 0051
-- session (a storefront endpoint, never Telegram), so unlike an OPERATIONS or
-- PLATFORM link it already knows exactly which account will own the resulting
-- binding — the account is not discoverable from the Telegram side of the
-- handshake the way an operator's principal id is carried on the OPERATIONS
-- row. Nullable, and paired with a shape constraint, the same discipline
-- ck_telegram_pending_link_scope already applies to brand/location.
ALTER TABLE integration.telegram_pending_links ADD COLUMN customer_account_id uuid;

-- Composite against customer_accounts' own (id, tenant_id) unique pair
-- (uq_customer_identity, V0017) — a bare id reference would let one tenant's
-- pending link point at another tenant's customer account.
ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT fk_telegram_pending_link_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id);

ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_customer_shape CHECK (
        (audience = 'CUSTOMER') = (customer_account_id IS NOT NULL));

-- requested_by_principal_id was NOT NULL because every row was, until now, an
-- operator's own group-link request. A CUSTOMER row has no Keycloak principal
-- behind it at all — an ADR 0051 customer session names an account, not a
-- realm subject — so the requester field is what customer_account_id is for
-- on that row instead. Loosened to nullable and given the mirror-image shape
-- constraint, rather than overloading the column with a value that means
-- something different depending on which audience is reading it.
ALTER TABLE integration.telegram_pending_links ALTER COLUMN requested_by_principal_id DROP NOT NULL;

ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_requester_shape CHECK (
        (audience <> 'CUSTOMER') = (requested_by_principal_id IS NOT NULL));

-- ------------------------------------------------------ 3. ck_endpoint_owner

-- V0100's own comment on this constraint pre-announced exactly this migration:
-- "ADR 0058's customer 1:1 linking ... will need its own provider_binding_id
-- endpoint that DOES carry a customer_account_id, which is a future DROP/ADD
-- of this same constraint once that stage's trigger exists to write such a
-- row." That trigger is CustomerProviderBindingSyncService.onLinked, added by
-- this build.
--
-- The old two-way equality could express "customer_account_id is null unless
-- this is an operations-shaped destination" because a provider-binding
-- endpoint was, until now, always an operations/platform one. That is no
-- longer true, and a bare "provider_binding_id IS NOT NULL" can no longer
-- imply "and therefore no customer" — so the constraint is rewritten as three
-- destination-shaped clauses (ck_endpoint_destination above already guarantees
-- exactly one of the three fires): a contact point is always the customer's
-- own, an operations reference never is, and a provider binding may be
-- either. Which one a given binding actually is — a CHECK constraint cannot
-- join to integration.telegram_bindings.audience to find out — is an
-- application-level invariant instead: CustomerProviderBindingSyncService
-- writes customer_account_id on a binding-shaped endpoint if and only if the
-- binding's own audience is CUSTOMER, and nothing else ever writes one.
ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_owner;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_owner CHECK (
        (contact_point_id IS NOT NULL AND customer_account_id IS NOT NULL)
        OR (operations_endpoint_reference IS NOT NULL AND customer_account_id IS NULL)
        OR (provider_binding_id IS NOT NULL));
