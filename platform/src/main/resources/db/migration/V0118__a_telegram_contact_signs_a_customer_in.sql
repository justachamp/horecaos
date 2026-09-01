-- ADR 0063: share-contact sign-in rides the wave-7 pending-link machinery
-- (V0099, widened by V0107) rather than inventing a parallel one. A fourth
-- audience, 'AUTH': a code minted by the storefront before any session or
-- account exists at all -- unlike CUSTOMER, which is minted from an already-
-- authenticated customer session and already knows the account it will
-- link, an AUTH code knows only a tenant and a brand, and the account is
-- discovered later, in the chat, from a Telegram-attested phone number.

-- --------------------------------------------------- 1. audience CHECK widening
-- integration.telegram_bindings.ck_telegram_binding_audience is untouched:
-- AUTH never produces its own binding audience. The binding an AUTH
-- redemption creates is audience CUSTOMER, the same call
-- TelegramCustomerLinkService#link already makes for the /start <code> path,
-- so that CHECK's current list ('OPERATIONS', 'PLATFORM', 'CUSTOMER', V0107)
-- already covers every value this migration will ever write there.
--
-- integration.telegram_pending_links.ck_telegram_pending_link_audience does
-- need AUTH -- this is the code table. V0107's own comment on this
-- constraint says it plainly: a DROP/ADD recreation of a CHECK replaces the
-- whole value list rather than adding to it. The full current list
-- ('OPERATIONS', 'PLATFORM', 'CUSTOMER') is restated here, plus 'AUTH'.
ALTER TABLE integration.telegram_pending_links
    DROP CONSTRAINT ck_telegram_pending_link_audience;
ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_audience CHECK (audience IN ('OPERATIONS', 'PLATFORM', 'CUSTOMER', 'AUTH'));

-- ---------------------------------------------- 2. customer_account_id shape
-- V0107's ck_telegram_pending_link_customer_shape requires customer_account_id
-- exactly when audience = 'CUSTOMER'. That still holds for AUTH: an AUTH row
-- is minted knowing no account (customer_account_id stays NULL for it, same
-- as OPERATIONS/PLATFORM), so the existing two-way equality needs no change
-- -- restated here only in this comment, not in SQL, because nothing about
-- it actually changes.

-- ------------------------------------------------------- 3. requester shape
-- V0107's ck_telegram_pending_link_requester_shape requires
-- requested_by_principal_id exactly when audience <> 'CUSTOMER'. An AUTH row
-- has no Keycloak principal behind it either -- the storefront call that
-- mints it is unauthenticated by design, the same as
-- StorefrontCustomerIdentityController's own three identity endpoints --
-- so AUTH joins CUSTOMER in the nullable side.
ALTER TABLE integration.telegram_pending_links
    DROP CONSTRAINT ck_telegram_pending_link_requester_shape;
ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_requester_shape CHECK (
        (audience NOT IN ('CUSTOMER', 'AUTH')) = (requested_by_principal_id IS NOT NULL));

-- ------------------------------------------------- 4. the AUTH-only columns
--
-- Deliberately not a session token, encrypted or otherwise: DataClass.SECRET
-- on FieldProtection says plainly that a credential is handled by ADR 0028
-- and never stored in a business table, and a session bearer is exactly that
-- credential -- CustomerSessionService already stores nothing but its hash,
-- once, for the same reason. So nothing recoverable is ever written here.
-- What crosses from the webhook handler (which redeems the code) to the
-- storefront poll (which mints the session) is only the resolved account,
-- which is not a secret:
--
--   awaiting_contact_chat_id  -- set the moment "/start auth_<code>" sends
--                                the request_contact keyboard, so the contact
--                                message that follows it (which carries no
--                                payload of its own -- Telegram's
--                                request_contact button has no room for one)
--                                can be correlated back to this row by the
--                                chat it arrived in.
--   auth_account_id           -- the account TelegramContactSignIn resolved
--                                or created, set together with consumed_at
--                                and created_binding_id.
--   auth_account_created      -- whether that sign-in brought the account
--                                into existence, mirroring
--                                CustomerSessionService.Established#created.
--   auth_session_claimed_at   -- the single-claim guard: the storefront poll
--                                mints the ADR 0051 session itself, exactly
--                                once, the first time it observes a redeemed
--                                AUTH row -- see
--                                StorefrontTelegramSignInController. A losing
--                                concurrent poll (two tabs, a retried
--                                request) sees this already set and answers
--                                as if the code had simply expired, rather
--                                than minting a second session for one
--                                redemption.
ALTER TABLE integration.telegram_pending_links ADD COLUMN awaiting_contact_chat_id bigint;
ALTER TABLE integration.telegram_pending_links ADD COLUMN auth_account_id uuid;
ALTER TABLE integration.telegram_pending_links ADD COLUMN auth_account_created boolean;
ALTER TABLE integration.telegram_pending_links ADD COLUMN auth_session_claimed_at timestamptz;

ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_auth_shape CHECK (
        audience = 'AUTH' OR (
            awaiting_contact_chat_id IS NULL
            AND auth_account_id IS NULL
            AND auth_account_created IS NULL
            AND auth_session_claimed_at IS NULL));

-- auth_account_created travels with auth_account_id (both set together, at
-- redemption, by the same UPDATE consume() already makes) and
-- auth_session_claimed_at can only follow a redemption that resolved one.
ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_auth_account_pair CHECK (
        (auth_account_id IS NULL) = (auth_account_created IS NULL));
ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_auth_claim_shape CHECK (
        auth_session_claimed_at IS NULL OR auth_account_id IS NOT NULL);

-- Composite against customer_accounts' own (id, tenant_id) unique pair, the
-- same reference V0107 adds for customer_account_id: a bare id reference
-- would let one tenant's AUTH row resolve to another tenant's account.
ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT fk_telegram_pending_link_auth_account FOREIGN KEY (auth_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id);

-- The lookup the contact-message handler actually does: "is some chat
-- waiting on a contact share right now". Partial, and scoped to unconsumed
-- rows, for the same reason ix_telegram_pending_link_open already is.
CREATE INDEX ix_telegram_pending_link_awaiting_contact
    ON integration.telegram_pending_links (awaiting_contact_chat_id)
    WHERE awaiting_contact_chat_id IS NOT NULL AND consumed_at IS NULL;

-- ------------------------------------------------------ 5. contact provenance
--
-- ADR 0063: a phone Telegram itself attested (the request_contact button
-- returns the account owner's own verified number) is recorded as a
-- verified contact point sourced 'TELEGRAM_CONTACT', distinguishable from
-- one an SMS one-time code proved ('CUSTOMER_VERIFICATION'). Nullable and
-- newly added rather than backfilled: no existing row claims either
-- provenance today, and an unset source on an old VERIFIED row is an honest
-- "recorded before this column existed", not a third value pretending to be
-- silence.
ALTER TABLE customer.contact_points ADD COLUMN source varchar(32);
ALTER TABLE customer.contact_points
    ADD CONSTRAINT ck_contact_source CHECK (
        source IS NULL OR source IN ('CUSTOMER_VERIFICATION', 'TELEGRAM_CONTACT'));

-- Table-level GRANTs on both tables already cover every column added here
-- (V0017, V0099); no new grant needed.
