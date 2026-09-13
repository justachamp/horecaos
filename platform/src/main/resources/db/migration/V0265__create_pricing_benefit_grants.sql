-- Operations §6.2a: a one-off code bound to exactly one named customer (the
-- late-order apology), which no code path could mint before this migration —
-- `pricing.benefit_grants` has been named in ADR 0018 and ADR 0044 since they
-- were written and never created. V0043's own comment on the marketing side
-- explains why it could not be built there: "a table whose producer does not
-- exist is not a head start... The coded grant additionally cannot be built
-- here at all — `pricing` has no `benefit_grants` table in this database yet,
-- and inventing one from the marketing side would be this module minting the
-- benefit that ADR 0044 is explicit it may only reference." This migration
-- builds it where it belongs: inside `pricing`, beside the coupon machinery
-- ADR 0072 already shipped.
--
-- ---------------------------------------------------------------------------
-- Shared code word vs. per-recipient grant
-- ---------------------------------------------------------------------------
--
-- ADR 0044's own table names the split this schema follows:
--
--   Shared code word (OSH2026)   -> pricing.coupon_codes    one row, many redeemers
--   Unique per-recipient code    -> pricing.benefit_grants   one row per recipient
--
-- A shared code is what 6.2's screen authors: one row, a total and a
-- per-customer limit, spent by whoever types it. A benefit grant is the other
-- shape entirely — bound to one `customer_account_id` at mint time, single-use,
-- and never presented as a word anyone else could type in and have it work.
-- Forcing the apology through `coupon_codes` would need a "coupon" only one
-- person could ever redeem, i.e. a per-customer limit of 1 on a total limit of
-- 1 — legal but indistinguishable at the schema level from a shared code
-- nobody else happened to redeem yet, so a support agent reading the row could
-- not tell "this can only ever go to Aziz" from "this happens to still be
-- unclaimed". A separate table makes the binding a fact of the row, not an
-- inference from two counters.
--
-- ---------------------------------------------------------------------------
-- What this migration does not build
-- ---------------------------------------------------------------------------
--
-- ADR 0044 additionally specifies `code_encrypted` — an ADR 0029 envelope
-- ciphertext beside the lookup hash, so an operator can decrypt a code a
-- customer reads out over the phone without granting `customer.pii.reveal`.
-- That reveal flow has no caller yet (service recovery, 5.4, is not built —
-- see the operations gap map's own entry), so this migration follows
-- `pricing.coupon_codes`'s own, already-proven shape instead: `code_hash`
-- only, SHA-256 over the normalized code, never stored or returned again
-- after the mint response. Adding `code_encrypted` is a column any later
-- migration can append once a caller actually needs to read a code back to a
-- customer; a nullable column with no reader is exactly the "empty table"
-- V0043 already declined to leave behind.
--
-- `pricing.benefit_usages` (ADR 0018's reservation-ledger sibling to
-- `coupon_redemptions`) is also not built here: a benefit grant is single-use
-- by construction (`consumed_count`-style reservation only matters once a
-- grant can be spent more than once), so redemption is a direct status
-- transition on this table, mirroring how `coupon_codes.consumed_count`
-- itself is the reservation ledger for the common case.

CREATE TABLE pricing.benefit_grants (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- The whole point: bound to one account at mint time, never a bearer
    -- secret like a shared coupon word. No FK to customer.customer_accounts —
    -- pricing does not reach across a module boundary for it, the same choice
    -- coupon_redemptions.customer_account_id already made two tables up.
    customer_account_id uuid NOT NULL,

    -- ADR 0072's closed discount-shape set (PromoCodeAuthoringService.DiscountShape),
    -- reused rather than re-invented: a benefit grant discounts an order or
    -- waives delivery the same three ways a promo code does.
    benefit_type varchar(24) NOT NULL,
    value bigint NOT NULL,
    maximum_discount_minor bigint,
    currency char(3),
    min_basket_minor bigint NOT NULL DEFAULT 0,

    -- Hashed, never stored in the clear — pricing.coupon_codes's own pattern.
    -- The plaintext is returned exactly once, in the mint response.
    code_hash char(64) NOT NULL,
    code_hint varchar(8) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    -- Who asked for this grant to exist. OPERATOR_MANUAL is the only source
    -- with a caller in this wave; RECOVERY_CASE/CAMPAIGN/TRIGGER are named now
    -- so 5.4's service-recovery board and 6.5's automations (both of which
    -- this table is the named prerequisite for) extend this column rather
    -- than widen it later.
    source_type varchar(24) NOT NULL,
    source_id uuid,

    valid_from timestamptz NOT NULL,
    expires_at timestamptz,

    -- Set together, at redemption, by the one conditional UPDATE that claims
    -- this grant — mirroring coupon_redemptions' own redeemed_at/order_id pair.
    redeemed_at timestamptz,
    order_id uuid,
    quote_id uuid,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_benefit_grant_type CHECK (
        benefit_type IN ('PERCENTAGE_OFF_ORDER', 'FIXED_AMOUNT_OFF_ORDER', 'FREE_DELIVERY')
    ),
    CONSTRAINT ck_benefit_grant_status CHECK (status IN ('ACTIVE', 'REDEEMED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_benefit_grant_source CHECK (
        source_type IN ('OPERATOR_MANUAL', 'RECOVERY_CASE', 'CAMPAIGN', 'TRIGGER')
    ),
    -- FREE_DELIVERY carries no currency of its own — nothing to charge in one —
    -- exactly like ADR 0072's coupon action attributes for the same shape.
    CONSTRAINT ck_benefit_grant_currency CHECK (currency IS NOT NULL OR benefit_type = 'FREE_DELIVERY'),
    CONSTRAINT ck_benefit_grant_min_basket CHECK (min_basket_minor >= 0),
    CONSTRAINT ck_benefit_grant_max_discount CHECK (maximum_discount_minor IS NULL OR maximum_discount_minor > 0),
    CONSTRAINT ck_benefit_grant_window CHECK (expires_at IS NULL OR expires_at > valid_from),
    CONSTRAINT ck_benefit_grant_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_benefit_grant_redeemed CHECK (
        (status <> 'REDEEMED') OR (redeemed_at IS NOT NULL AND order_id IS NOT NULL)
    ),
    CONSTRAINT uq_benefit_grant_identity UNIQUE (id, tenant_id)
);

-- One live grant per code per tenant — the same per-tenant scoping
-- ux_coupon_code gives a shared code word, for the same reason: two tenants
-- may each mint an identically-spelled apology code without one failing to
-- mint. Every grant is coded (this table exists only for the coded case), so
-- the index is unconditional rather than a partial one filtering out NULLs.
CREATE UNIQUE INDEX ux_benefit_grant_code
    ON pricing.benefit_grants (tenant_id, code_hash);

-- Drives "every code minted for this customer" — a support screen's own
-- lookup, and the query the redemption check runs to confirm the presented
-- code belongs to the account presenting it.
CREATE INDEX ix_benefit_grants_customer
    ON pricing.benefit_grants (tenant_id, customer_account_id);

-- Drives the future expiry sweep (ADR 0018's existing coupon expiry job is
-- named as the model this absorbs into) — every grant still ACTIVE past its
-- window, oldest first.
CREATE INDEX ix_benefit_grants_expiring
    ON pricing.benefit_grants (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

GRANT SELECT, INSERT, UPDATE ON pricing.benefit_grants TO horecaos_application;
