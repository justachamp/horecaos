-- ADR 0040: the marketplace channel and the partner API.
--
-- ADR 0040 was `Proposed` on one question: who issues the fiscal receipt for an
-- order an aggregator collected payment for. ADR 0038 answered it — the
-- restaurant's legal entity is the seller and the legal principal, HorecaOS is an
-- agent and never the issuer — and the answer is what makes this migration
-- writable. A marketplace order is a sale by the restaurant, so it belongs in
-- the same `ordering.orders` table as every other sale by that restaurant, under
-- the same legal entity, with the same fiscal obligation resolved the same way.
-- Had the aggregator been the principal, an aggregator order would have been a
-- different commercial object and this would have been a different schema.
--
-- Four ideas run through what is here.
--
-- First: an inbound aggregator order is an order. It is not a second aggregate
-- that projects into the order list, because every operations screen, filter,
-- report, cancellation, refund and audit query would then need a second
-- implementation, and the two would drift exactly where it matters — money and
-- status. What distinguishes it is three authority flags and a partner
-- attribution, and everything else about it is an ordinary order.
--
-- Second: an externally priced total is an escape hatch, so it exists exactly
-- once and only where it is unavoidable. `ordering.orders.pricing_authority` is
-- the single enforcement point; ADR 0036's `tenant.sales_channels.externally_priced`
-- is a default that seeds it and is never read again. A check constraint refuses
-- `EXTERNAL` on anything but a `MARKETPLACE`-origin order, and a trigger refuses
-- a marketplace order whose binding is not a binding of a `MARKETPLACE`
-- installation. Two switches that both mean "do not price this" diverge the
-- moment a channel is reconfigured, and then a nine-month-old order re-prices
-- differently from the way it was booked.
--
-- Third: idempotency is structural rather than advisory. A retried aggregator
-- push that creates a second order is a restaurant cooking twice, so the
-- duplicate is refused by a unique index on `(binding, external order id)` in
-- the staging table and not by a check the application performs first.
--
-- Fourth: a partner credential is never a value in this database. `partner.api_clients`
-- carries an OAuth client id and an ADR 0028 secret reference, and rotation
-- changes what is behind the reference and never the reference. There is
-- deliberately no column a secret could be written to, because
-- `base64(login:password)` of a real panel user is what the legacy estate mints
-- and this decision exists partly to refuse it.
--
-- One deviation from ADR 0040's written physical model, recorded because reading
-- the ADR and reading this file should not produce two different beliefs:
--
--   * `fulfillment_authority`, not `fulfilment_authority`. This table already
--     carries `fulfillment_mode` and `fulfillment_status_projection`, and the
--     schema is named `fulfillment`. One table with two spellings of one word is
--     a column somebody eventually types wrong in a report.
--   * The inbound staging table is `partner.inbound_orders`, not
--     `ordering.marketplace_inbound_orders`. Nothing in `ordering` reads or
--     writes it; it is the partner module's evidence that a push was received,
--     including the pushes that were refused and therefore never became orders.
--
-- All money is integer minor units with a currency. For UZS a minor unit is a
-- whole som.

CREATE SCHEMA IF NOT EXISTS partner;

-- ------------------------------------------------------ the MARKETPLACE category

-- ADR 0026's category enum has no value for a provider that sends orders in.
-- `MARKETPLACE` is distinct from `DELIVERY` and the distinction is not
-- taxonomy: Yandex Delivery sources a courier for an order HorecaOS owns, Yandex
-- Eda sends an order HorecaOS did not create. Same company, opposite direction, two
-- installations, two sets of credentials, two failure modes.
ALTER TABLE integration.provider_environments
    DROP CONSTRAINT ck_provider_environment_category;
ALTER TABLE integration.provider_environments
    ADD CONSTRAINT ck_provider_environment_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'MARKETPLACE',
                              'NOTIFICATION', 'GEOCODING', 'OTHER'));

ALTER TABLE integration.installations
    DROP CONSTRAINT ck_installation_category;
ALTER TABLE integration.installations
    ADD CONSTRAINT ck_installation_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'MARKETPLACE',
                              'NOTIFICATION', 'GEOCODING', 'OTHER'));

-- --------------------------------------------------- inbound partner credentials

-- ADR 0026 models one direction of a provider relationship: HorecaOS holds a secret
-- and calls out. A marketplace runs both directions, and the inbound half is a
-- machine principal authenticating *to* HorecaOS. That is an identity object with
-- its own lifecycle, so it is a row rather than a field on the installation.
--
-- There is no secret column, and there will not be one. `secret_reference` is an
-- ADR 0028 pointer; the secret itself lives in the secret store, is shown to the
-- partner once at issuance, and rotation replaces what the reference resolves to
-- while the client id and the installation id stay put. A partner integration
-- that has to change its client id to rotate a secret does not rotate its secret.
CREATE TABLE partner.api_clients (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- The ADR 0026 installation this credential belongs to. The installation
    -- decides which aggregator it is and its bindings decide which branches it
    -- may act for, so the credential carries no scope of its own — see
    -- partner.api_client_bindings below for why that is a view and not a copy.
    installation_id uuid NOT NULL,

    -- The OAuth 2.0 client_credentials client id, as issued in Keycloak. Not a
    -- secret: it appears in logs, in the partner's configuration file, and in
    -- support conversations, which is exactly why the secret is somewhere else.
    client_id varchar(255) NOT NULL,
    secret_reference varchar(512),

    status varchar(16) NOT NULL DEFAULT 'PENDING',
    -- When the current secret was last replaced, and when it stops being
    -- accepted. An expiry that is null is a credential nobody will ever be
    -- forced to rotate, which is how a five-year-old partner secret happens.
    secret_rotated_at timestamptz,
    secret_expires_at timestamptz,
    -- Liveness of the credential itself, distinct from liveness of the channel:
    -- a partner whose token has expired stops authenticating before it stops
    -- sending, and the two failures look identical from the order list.
    last_authenticated_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_partner_client_status CHECK (
        status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    -- A PENDING client has no secret yet and an ACTIVE one must have a
    -- reference to resolve. Stated per-status rather than as a bare NOT NULL so
    -- issuance can create the row before the secret exists without the row
    -- claiming to be usable.
    CONSTRAINT ck_partner_client_secret_present CHECK (
        status <> 'ACTIVE' OR secret_reference IS NOT NULL),
    -- Rotation writes both or neither. A rotation time with no expiry cannot
    -- answer "when must this be rotated again", which is the only question the
    -- column exists for.
    CONSTRAINT ck_partner_client_rotation_pair CHECK (
        (secret_rotated_at IS NULL) = (secret_expires_at IS NULL)),
    CONSTRAINT ck_partner_client_version CHECK (version >= 1),
    CONSTRAINT fk_partner_client_installation FOREIGN KEY (tenant_id, installation_id)
        REFERENCES integration.installations (tenant_id, id),
    -- Globally unique, not per tenant: the client id is what an unauthenticated
    -- token request presents, before any tenant is known. A per-tenant unique
    -- key would make resolution ambiguous at exactly the moment there is nothing
    -- to disambiguate with.
    CONSTRAINT uq_partner_client_client_id UNIQUE (client_id),
    CONSTRAINT uq_partner_client_identity UNIQUE (tenant_id, id)
);

-- One live credential per installation. Two would rotate independently, and
-- "revoke the partner's access" would silently leave one of them working.
CREATE UNIQUE INDEX uq_partner_client_active_per_installation
    ON partner.api_clients (tenant_id, installation_id)
    WHERE status IN ('PENDING', 'ACTIVE');

COMMENT ON TABLE partner.api_clients IS
    'ADR 0040 inbound partner credential. OAuth 2.0 client_credentials, one confidential client per ADR 0026 installation. Holds a client id and an ADR 0028 secret reference and never a secret.';
COMMENT ON COLUMN partner.api_clients.secret_reference IS
    'ADR 0028 reference, never a value. Rotation changes what this resolves to and never this string, so a rotation is not an outage.';
COMMENT ON COLUMN partner.api_clients.last_authenticated_at IS
    'The last successful token exchange or authenticated call. Distinct from the channel watermark: a partner can be authenticating perfectly and sending nothing, and the two need different alerts.';

-- ------------------------------------------------------- order authority columns

ALTER TABLE ordering.orders
    -- Where the order was placed. Not derivable from the channel, because a
    -- tenant may register several marketplace channels and a channel may be
    -- reconfigured after orders were taken on it.
    ADD COLUMN origin varchar(16) NOT NULL DEFAULT 'HORECAOS',
    -- Who computed the total. The single enforcement point for the ADR 0018
    -- bypass; ADR 0036's channel flag seeds this and is never consulted again.
    ADD COLUMN pricing_authority varchar(16) NOT NULL DEFAULT 'HORECAOS',
    -- Who owns the courier and the customer promise. PARTNER narrows HorecaOS's
    -- state machine rather than surrendering it: HorecaOS stays the only writer of
    -- orders.status, and partner courier state is a projection stored beside it.
    ADD COLUMN fulfillment_authority varchar(16) NOT NULL DEFAULT 'HORECAOS',
    -- How the row got here. A manually keyed aggregator order is a total a human
    -- typed and the platform cannot verify, which is a materially different
    -- evidential position from a total a partner pushed.
    ADD COLUMN entry_mode varchar(16) NOT NULL DEFAULT 'API',
    ADD COLUMN marketplace_binding_id uuid;

COMMENT ON COLUMN ordering.orders.origin IS
    'ADR 0040. HORECAOS for an order placed on a HorecaOS surface, MARKETPLACE for one an aggregator pushed or an operator keyed in on an aggregator''s behalf.';
COMMENT ON COLUMN ordering.orders.pricing_authority IS
    'ADR 0040. The only enforcement point for the ADR 0018 bypass. EXTERNAL means the totals were supplied and verified arithmetically, never re-derived; such orders are excluded from pricing reconciliation and must be labelled wherever they are aggregated.';
COMMENT ON COLUMN ordering.orders.fulfillment_authority IS
    'ADR 0040. PARTNER means the aggregator dispatches the courier and owns the customer promise. Such an order runs RECEIVED -> CONFIRMED -> PREPARING -> READY -> COMPLETED, skipping FULFILLING, and reaches COMPLETED at proven handover rather than at delivery.';
COMMENT ON COLUMN ordering.orders.entry_mode IS
    'ADR 0040. API for a partner push or a storefront checkout, MANUAL for an operator keying an unintegrated partner''s order, IMPORT for a migration. MANUAL always carries an ADR 0027 audit fact naming the operator.';
COMMENT ON COLUMN ordering.orders.marketplace_binding_id IS
    'ADR 0026 binding of the MARKETPLACE installation this order arrived through. Required on every MARKETPLACE-origin order, because "which aggregator" is not answerable from the channel alone once a tenant runs three.';

-- ADR 0019 made four columns mandatory because every order came from a HorecaOS
-- cart that HorecaOS had priced. A marketplace order has none of them: no cart was
-- ever opened, no quote was ever computed, and the context hash that proves "the
-- price you were shown is the price you paid" has nothing to prove because HorecaOS
-- showed no price. Dropping the NOT NULL and stating the pairing per authority
-- keeps the guarantee exactly where it was true and stops asserting it where it
-- never was.
ALTER TABLE ordering.orders
    ALTER COLUMN pricing_quote_id DROP NOT NULL,
    ALTER COLUMN pricing_context_hash DROP NOT NULL,
    ALTER COLUMN catalog_publication_id DROP NOT NULL,
    ALTER COLUMN cart_id DROP NOT NULL;

ALTER TABLE ordering.orders
    ADD CONSTRAINT ck_order_origin CHECK (origin IN ('HORECAOS', 'MARKETPLACE')),
    ADD CONSTRAINT ck_order_pricing_authority CHECK (pricing_authority IN ('HORECAOS', 'EXTERNAL')),
    ADD CONSTRAINT ck_order_fulfillment_authority CHECK (
        fulfillment_authority IN ('HORECAOS', 'PARTNER')),
    ADD CONSTRAINT ck_order_entry_mode CHECK (entry_mode IN ('API', 'MANUAL', 'IMPORT')),

    -- The boundary this decision exists to draw. Delever's «Свободная скидка» is
    -- a promotion with no input fields, usable on any channel, which means
    -- anyone who can set a discount can set any total. Here the escape hatch is
    -- reachable from exactly one origin and the database says so.
    ADD CONSTRAINT ck_order_external_pricing_is_marketplace CHECK (
        pricing_authority = 'HORECAOS' OR origin = 'MARKETPLACE'),

    -- "Which aggregator" must be answerable from the order row alone. A
    -- marketplace order with no binding is one nobody can settle, reconcile, or
    -- push a status back to.
    ADD CONSTRAINT ck_order_marketplace_has_binding CHECK (
        origin = 'HORECAOS' OR marketplace_binding_id IS NOT NULL),
    ADD CONSTRAINT ck_order_horecaos_has_no_binding CHECK (
        origin = 'MARKETPLACE' OR marketplace_binding_id IS NULL),

    -- The quote and its context hash travel together or not at all, and both are
    -- present exactly when HorecaOS priced the order. Written as equivalences so
    -- neither an externally priced order carrying a quote nor a HorecaOS-priced one
    -- missing its quote can be written.
    ADD CONSTRAINT ck_order_quote_matches_authority CHECK (
        (pricing_authority = 'HORECAOS') = (pricing_quote_id IS NOT NULL)),
    ADD CONSTRAINT ck_order_quote_pair CHECK (
        (pricing_quote_id IS NULL) = (pricing_context_hash IS NULL)),

    -- A cart is a HorecaOS surface artefact. A marketplace order has none, and a
    -- fabricated empty cart to satisfy a foreign key would put a row in every
    -- cart-conversion funnel that no customer ever filled.
    ADD CONSTRAINT ck_order_cart_matches_origin CHECK (
        (origin = 'HORECAOS') = (cart_id IS NOT NULL)),

    -- One-directional on purpose. A HorecaOS order always names the publication its
    -- lines were snapshotted from; a marketplace order may name the publication
    -- its lines were matched against, and that is worth storing when it is known
    -- and honest to leave null when a partner sent something the catalogue does
    -- not carry.
    ADD CONSTRAINT ck_order_horecaos_has_publication CHECK (
        origin <> 'HORECAOS' OR catalog_publication_id IS NOT NULL),

    ADD CONSTRAINT fk_order_marketplace_binding FOREIGN KEY (tenant_id, marketplace_binding_id)
        REFERENCES integration.bindings (tenant_id, id);

CREATE INDEX ix_orders_marketplace ON ordering.orders (tenant_id, marketplace_binding_id, created_at DESC)
    WHERE origin = 'MARKETPLACE';

-- A check constraint cannot join, and the fact that has to be true here lives
-- two tables away: the binding must belong to a MARKETPLACE installation, and if
-- it is bound to a branch it must be this order's branch. Both are the kind of
-- mistake a configuration screen makes once and a reconciliation report inherits
-- for a year, so they are refused where nothing can bypass them.
CREATE OR REPLACE FUNCTION ordering.assert_marketplace_binding() RETURNS trigger AS $$
DECLARE
    v_category varchar(32);
    v_binding_location uuid;
BEGIN
    IF NEW.marketplace_binding_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT i.provider_category, b.location_id
      INTO v_category, v_binding_location
      FROM integration.bindings b
      JOIN integration.installations i
        ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
     WHERE b.tenant_id = NEW.tenant_id
       AND b.id = NEW.marketplace_binding_id;

    IF v_category IS DISTINCT FROM 'MARKETPLACE' THEN
        RAISE EXCEPTION
            'An order may only name a binding of a MARKETPLACE installation (ADR 0040)';
    END IF;

    IF v_binding_location IS NOT NULL AND v_binding_location <> NEW.location_id THEN
        RAISE EXCEPTION
            'A marketplace binding bound to one branch cannot carry another branch''s order (ADR 0040)';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_marketplace_binding
    BEFORE INSERT OR UPDATE OF marketplace_binding_id, location_id ON ordering.orders
    FOR EACH ROW EXECUTE FUNCTION ordering.assert_marketplace_binding();

-- ADR 0039's revision spine has the same premise as the order did: every
-- revision came from a quote. Revision 1 of an externally priced order came from
-- a partner's push instead, and the totals on it are the partner's own.
ALTER TABLE ordering.order_revisions
    ALTER COLUMN pricing_quote_id DROP NOT NULL,
    ALTER COLUMN pricing_context_hash DROP NOT NULL;

ALTER TABLE ordering.order_revisions
    ADD CONSTRAINT ck_order_revision_quote_pair CHECK (
        (pricing_quote_id IS NULL) = (pricing_context_hash IS NULL));

COMMENT ON COLUMN ordering.order_revisions.pricing_quote_id IS
    'ADR 0039, relaxed by ADR 0040. Null exactly on a revision of an externally priced order: there was no quote, and a fabricated one would claim HorecaOS computed a total it received.';

-- ---------------------------------------------------------- unmapped order lines

-- The deliberate tolerance in ADR 0040's rejection table. A line that maps to no
-- HorecaOS catalogue node is normally a menu-sync lag on one item, and refusing the
-- whole order means a customer who has already paid the aggregator gets nothing
-- while the branch never learns why. A flagged line is a problem a person solves
-- in the thirty seconds before the food is cooked.
--
-- `external_mapping_status` is NOT NULL with a default rather than nullable, so
-- the pairing below can be a plain equivalence with no operand that can be NULL.
-- A nullable status would leave `source_variant_id IS NULL AND status IS NULL`
-- evaluating to NULL and therefore passing, which is the three-valued hole this
-- codebase keeps finding in other people's CHECKs.
ALTER TABLE ordering.order_lines
    ALTER COLUMN source_variant_id DROP NOT NULL,
    ADD COLUMN external_mapping_status varchar(16) NOT NULL DEFAULT 'MAPPED',
    -- The partner's own identifier for the item, so the branch can tell the
    -- aggregator which SKU to fix rather than describing the dish over the phone.
    ADD COLUMN external_item_reference varchar(128);

ALTER TABLE ordering.order_lines
    ADD CONSTRAINT ck_order_line_mapping_status CHECK (
        external_mapping_status IN ('MAPPED', 'UNMAPPED')),
    ADD CONSTRAINT ck_order_line_unmapped_has_no_variant CHECK (
        (source_variant_id IS NULL) = (external_mapping_status = 'UNMAPPED'));

COMMENT ON COLUMN ordering.order_lines.external_mapping_status IS
    'ADR 0040. UNMAPPED means a partner sent an item the catalogue does not carry: the line keeps the partner''s own name and amount, carries no variant, and raises a location-visible exception. Every HorecaOS-originated line is MAPPED.';
COMMENT ON COLUMN ordering.order_lines.external_item_reference IS
    'ADR 0040. The partner''s identifier for the item. Present on marketplace lines so an unmapped line names the SKU the aggregator has to fix.';

-- ---------------------------------------------------------- external order money

-- What the partner said, kept verbatim and separately from what HorecaOS booked.
-- The two agree by construction — the ingestion refuses a push whose parts do
-- not sum to its total, and `ordering.orders` carries its own reconciliation
-- check — but they are different claims by different parties and a single set of
-- columns would lose that distinction the first time a partner restates a total.
CREATE TABLE ordering.order_external_pricing (
    order_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    currency char(3) NOT NULL,

    -- What the customer handed the aggregator. This is the figure the partner
    -- will settle against and the figure a customer quotes in a complaint.
    customer_paid_total_minor bigint NOT NULL,
    external_subtotal_minor bigint NOT NULL,
    external_discount_minor bigint NOT NULL DEFAULT 0,
    external_fee_minor bigint NOT NULL DEFAULT 0,
    -- Null means the partner did not state tax, not that tax was zero. An
    -- aggregator that reports gross prices and no tax line is the common case,
    -- and a zero here would be a claim about VAT that nobody made.
    external_tax_minor bigint,

    -- Who paid for the discount. UNKNOWN is a legitimate and frequent answer:
    -- most partner protocols do not say, and guessing MERCHANT would put the
    -- cost of the aggregator's own campaign on the restaurant's P&L.
    discount_funding varchar(16) NOT NULL DEFAULT 'UNKNOWN',

    -- Settlement, not ingestion. The push states what the customer paid; what
    -- the restaurant receives is decided weeks later by an imported statement,
    -- and reconciling the two is ADR 0043's work. The columns exist so that is
    -- not a migration.
    partner_commission_minor bigint,
    partner_payout_minor bigint,
    settlement_reference varchar(255),

    -- Recorded rather than assumed. The ingestion refuses a mismatch, so this is
    -- always true today; it is stored because a later import path that skipped
    -- the check would otherwise be indistinguishable from one that ran it.
    arithmetic_verified boolean NOT NULL,
    -- The partner's own totals object, for the argument that happens when the
    -- partner's portal and this row disagree. No customer contact is in it: the
    -- ingestion strips contact before storing, and the raw payload that does
    -- carry it is encrypted in partner.inbound_orders under ADR 0029.
    raw_totals jsonb NOT NULL DEFAULT '{}'::jsonb,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_external_pricing_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_external_pricing_amounts CHECK (
        customer_paid_total_minor >= 0 AND external_subtotal_minor >= 0
        AND external_discount_minor >= 0 AND external_fee_minor >= 0
        AND (external_tax_minor IS NULL OR external_tax_minor >= 0)),
    -- The arithmetic rule itself, at the database. HorecaOS validates nothing else
    -- about an external total, so this is the whole of what it promises: an
    -- order whose booked total does not equal the sum of its parts is what an
    -- accountant finds three months later and nobody can explain.
    CONSTRAINT ck_external_pricing_reconciles CHECK (
        customer_paid_total_minor = external_subtotal_minor
            + COALESCE(external_tax_minor, 0) + external_fee_minor
            - external_discount_minor),
    CONSTRAINT ck_external_pricing_funding CHECK (
        discount_funding IN ('PARTNER', 'MERCHANT', 'SPLIT', 'UNKNOWN')),
    CONSTRAINT ck_external_pricing_settlement CHECK (
        (partner_commission_minor IS NULL OR partner_commission_minor >= 0)
        AND (partner_payout_minor IS NULL OR partner_payout_minor >= 0)),
    CONSTRAINT ck_external_pricing_raw_totals CHECK (jsonb_typeof(raw_totals) = 'object'),
    CONSTRAINT fk_external_pricing_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_external_pricing_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id)
);

COMMENT ON TABLE ordering.order_external_pricing IS
    'ADR 0040. The partner''s own money claim for an externally priced order, stored verbatim. HorecaOS validates arithmetic and nothing else; these rows are excluded from pricing reconciliation and must be labelled wherever they are aggregated.';
COMMENT ON COLUMN ordering.order_external_pricing.external_tax_minor IS
    'Null means the partner stated no tax, which is not the same as zero tax. A zero here would be a VAT claim nobody made.';
COMMENT ON COLUMN ordering.order_external_pricing.partner_payout_minor IS
    'Written weeks later from an imported settlement statement, never from the order push. Null until then.';

-- ------------------------------------------------------------ external references

-- Customers and couriers quote the aggregator's number, not HorecaOS's. ADR 0026's
-- provider_entity_mappings cannot hold these: its unique keys make it a
-- one-to-one map per binding per entity type, and one order legitimately carries
-- a partner order id, a short display code, a venue-facing number and a delivery
-- claim id at once.
CREATE TABLE ordering.order_external_references (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    -- Null for a reference issued by something that is not a marketplace
    -- binding — a POS export id, for instance. The uniqueness key below treats
    -- null as its own namespace, which is what makes that work.
    binding_id uuid,
    reference_type varchar(32) NOT NULL,
    reference_value varchar(128) NOT NULL,
    -- Uppercased, with whitespace, hyphens and a leading '#' removed. Search
    -- matches on this, because an operator reading `YE-2291-04` off a courier's
    -- phone types `ye 2291 04` and must still find the order sitting four rows
    -- above in the same list.
    reference_value_normalised varchar(128) NOT NULL,
    issued_by varchar(16) NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT ck_external_reference_type CHECK (reference_type IN (
        'PARTNER_ORDER_ID', 'PARTNER_DISPLAY_CODE', 'PARTNER_VENUE_ORDER_NO',
        'DELIVERY_CLAIM_ID', 'POS_ORDER_ID')),
    CONSTRAINT ck_external_reference_issued_by CHECK (issued_by IN ('PARTNER', 'HORECAOS', 'POS')),
    CONSTRAINT ck_external_reference_value CHECK (length(btrim(reference_value)) > 0),
    CONSTRAINT ck_external_reference_version CHECK (version >= 1),
    CONSTRAINT fk_external_reference_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_external_reference_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id)
);

-- Per binding and per type, and deliberately neither global nor per tenant
-- alone. Two aggregators legitimately issue the same short numeric code on one
-- day, and a wider index would reject the second order outright — which is a
-- customer who paid and gets nothing, caused by an index that was trying to be
-- careful. NULLS NOT DISTINCT so a second POS_ORDER_ID with no binding still
-- collides with the first rather than slipping past on a null.
CREATE UNIQUE INDEX uq_external_reference_per_binding
    ON ordering.order_external_references (
        tenant_id, binding_id, reference_type, reference_value_normalised)
    NULLS NOT DISTINCT;

CREATE INDEX ix_external_reference_search
    ON ordering.order_external_references (tenant_id, reference_value_normalised);

COMMENT ON TABLE ordering.order_external_references IS
    'ADR 0040. Every identifier anybody else uses for this order. Search is by the normalised column across the tenant and may return several rows, disambiguated by provider and branch.';
COMMENT ON COLUMN ordering.order_external_references.reference_value_normalised IS
    'Uppercased with whitespace, hyphens and a leading # stripped. The column search reads; reference_value keeps what the partner actually sent.';

-- ------------------------------------------------------------ handover challenges

-- The platform's only handover-verification model, and this decision owns it.
-- ADR 0041's expo station verifies against this table rather than creating a
-- `kitchen.handovers` of its own: handing a bag to an aggregator courier and
-- handing it to a customer at the pass are the same physical act with the same
-- failure — the wrong person leaves with the food — and two tables would mean
-- two hash schemes, two attempt counters and two answers to "was this order
-- proven handed over".
--
-- The failure this prevents is concrete: two aggregator couriers reach a
-- Chilanzar branch a minute apart, a 420,000 som order goes to the wrong one,
-- and nobody can prove which took which bag.
CREATE TABLE ordering.order_handover_challenges (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    -- Null for a pickup or internal-courier handover, which uses the same row
    -- shape and the same attempt counter as an aggregator one.
    binding_id uuid,

    -- NONE is an explicit configured value per binding and never a null, so a
    -- branch cannot skip verification because a field happened to be empty.
    challenge_type varchar(16) NOT NULL,
    issued_by varchar(16) NOT NULL,

    -- A peppered hash, compared in constant time. A handover code in a readable
    -- column is a code anyone with a read replica can use. Null exactly when the
    -- challenge type is NONE.
    expected_value_hash varchar(128),

    attempts integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 5,
    status varchar(16) NOT NULL DEFAULT 'PENDING',

    verified_at timestamptz,
    verified_by varchar(255),
    -- Required on a BYPASSED row. An override with no reason is the support
    -- conversation this table exists to end.
    bypass_reason_code varchar(48),

    expires_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_handover_type CHECK (challenge_type IN ('CODE', 'QR', 'SIGNATURE', 'NONE')),
    CONSTRAINT ck_handover_issued_by CHECK (issued_by IN ('PARTNER', 'HORECAOS')),
    CONSTRAINT ck_handover_status CHECK (
        status IN ('PENDING', 'VERIFIED', 'BYPASSED', 'FAILED', 'EXPIRED')),
    -- A challenge that expects nothing is exactly a challenge of type NONE.
    -- Stated as an equivalence so neither a CODE challenge with no expected
    -- value nor a NONE challenge carrying one can be written.
    CONSTRAINT ck_handover_expected_value CHECK (
        (expected_value_hash IS NULL) = (challenge_type = 'NONE')),
    CONSTRAINT ck_handover_attempts CHECK (
        attempts >= 0 AND max_attempts >= 1 AND attempts <= max_attempts),
    -- Who verified and when travel together, and a settled challenge has both.
    CONSTRAINT ck_handover_verified_pair CHECK (
        (verified_at IS NULL) = (verified_by IS NULL)),
    CONSTRAINT ck_handover_settled CHECK (
        status NOT IN ('VERIFIED', 'BYPASSED') OR verified_at IS NOT NULL),
    -- The reason is mandatory on a bypass and refused on everything else, so a
    -- reason code can never be read as evidence for a status that did not need
    -- one.
    CONSTRAINT ck_handover_bypass_reason CHECK (
        (status = 'BYPASSED') = (bypass_reason_code IS NOT NULL)),
    CONSTRAINT ck_handover_version CHECK (version >= 1),
    CONSTRAINT fk_handover_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_handover_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id)
);

-- One open challenge per order. A second would let one bag be proved handed over
-- twice, to two different people, with two different attempt counters.
CREATE UNIQUE INDEX uq_handover_open_per_order
    ON ordering.order_handover_challenges (tenant_id, order_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_handover_order ON ordering.order_handover_challenges (tenant_id, order_id);

COMMENT ON TABLE ordering.order_handover_challenges IS
    'ADR 0040. The platform''s only handover-verification model. ADR 0041''s expo station verifies against this table; kitchen.handover.complete closes a challenge and marketplace.handover.bypass overrides one.';
COMMENT ON COLUMN ordering.order_handover_challenges.expected_value_hash IS
    'Peppered hash, compared in constant time. The plain value is never stored, never returned, never logged, and never appears in a trace.';
COMMENT ON COLUMN ordering.order_handover_challenges.challenge_type IS
    'NONE is a configured decision per binding, never an empty field. A branch must not be able to skip verification because nobody filled a form in.';

-- ------------------------------------------------------------- inbound staging

-- Every partner push lands here first, and this row is the evidence that a
-- rejected order was received at all. Without it a refused push is
-- indistinguishable from one that never arrived, and the partner's portal shows
-- an order HorecaOS has no record of.
CREATE TABLE partner.inbound_orders (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    -- The partner's stable identifier for the order. Together with the binding
    -- this is the idempotency key, and it is a stronger one than a client-chosen
    -- header: HorecaOS does not control the partner's retry client, but the
    -- partner's own order identifier is stable by construction.
    external_order_id varchar(128) NOT NULL,

    received_at timestamptz NOT NULL DEFAULT now(),
    -- ADR 0029. The push carries a proxied customer phone number, so the body is
    -- envelope-encrypted like any other personal data and never written to a log
    -- or an event.
    raw_payload_encrypted text NOT NULL,
    -- Over the canonical bytes, before encryption. Two pushes of one order that
    -- differ are a partner restating the order, which is a different fact from a
    -- retry and has to be visible as one.
    payload_sha256 char(64) NOT NULL,

    outcome varchar(16) NOT NULL,
    rejection_code varchar(48),
    -- Set when the push became an order. Null on a rejection, and on a duplicate
    -- it points at the order the first push created.
    order_id uuid,

    -- What the partner said about timing. HorecaOS's own promise columns record
    -- NOT_PROMISED for a fulfillment_authority = PARTNER order, because HorecaOS
    -- did not make that promise and a copied one would be indistinguishable from
    -- a promise HorecaOS owns.
    partner_pickup_expected_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_inbound_outcome CHECK (outcome IN ('ACCEPTED', 'REJECTED')),
    -- A rejection names its code and an acceptance names its order. Stated as
    -- two equivalences so neither an accepted push without an order nor a
    -- rejected one without a reason can exist.
    CONSTRAINT ck_inbound_rejection CHECK (
        (outcome = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT ck_inbound_accepted_order CHECK (
        (outcome = 'ACCEPTED') = (order_id IS NOT NULL)),
    CONSTRAINT ck_inbound_payload_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_inbound_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT fk_inbound_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    -- The whole of the duplicate defence. Two concurrent pushes of one order
    -- race here, one loses on this index, and the loser reads back the winner's
    -- order rather than creating a second one. An application-level "does it
    -- exist yet" check would let both pass under concurrency, and the cost of
    -- that is a restaurant cooking the same order twice.
    CONSTRAINT uq_inbound_per_binding UNIQUE (tenant_id, binding_id, external_order_id)
);

CREATE INDEX ix_inbound_orders_recent
    ON partner.inbound_orders (tenant_id, binding_id, received_at DESC);

CREATE INDEX ix_inbound_orders_rejected
    ON partner.inbound_orders (tenant_id, binding_id, received_at DESC)
    WHERE outcome = 'REJECTED';

COMMENT ON TABLE partner.inbound_orders IS
    'ADR 0040. Every partner push, accepted or refused. The unique key on (tenant, binding, external order id) is the idempotency defence: a retry reads back the order the first push created.';
COMMENT ON COLUMN partner.inbound_orders.raw_payload_encrypted IS
    'ADR 0029 envelope-encrypted. The push carries a proxied customer contact, so the body never appears in a log, an event, or an error response.';

-- ---------------------------------------------------------- liveness watermarks

-- A dead marketplace integration produces no errors, because nothing is being
-- called. An expired token or a revoked venue looks exactly like a quiet Tuesday
-- until the manager notices Friday was quiet too. ADR 0006 records failures that
-- happened and has no concept of work that stopped arriving; this is that
-- concept.
CREATE TABLE integration.provider_activity_watermarks (
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    -- Denormalised from the binding so the liveness matrix is one query. The
    -- binding stays the authority; a binding scoped to a brand rather than a
    -- branch writes null here.
    location_id uuid,
    direction varchar(16) NOT NULL,

    last_success_at timestamptz,
    last_success_reference varchar(255),
    last_failure_at timestamptz,
    last_failure_code varchar(48),

    -- Resolved per binding through ADR 0030 and copied here at evaluation,
    -- because a branch taking two Uzum orders a day and one taking two hundred
    -- have completely different silences and one global threshold alerts on the
    -- wrong one of them.
    stale_after_seconds integer NOT NULL,
    -- The trailing median interval actually observed, kept beside the threshold
    -- so the number is set from evidence rather than guessed.
    observed_median_interval_seconds integer,
    alert_state varchar(16) NOT NULL DEFAULT 'HEALTHY',
    alert_raised_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, binding_id, direction),
    CONSTRAINT ck_watermark_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_watermark_alert_state CHECK (
        alert_state IN ('HEALTHY', 'STALE', 'SUSPENDED')),
    -- An alert with no time cannot answer "since when", which is the first
    -- question asked about a channel that went quiet.
    CONSTRAINT ck_watermark_alert_pair CHECK (
        (alert_state = 'HEALTHY') = (alert_raised_at IS NULL)),
    CONSTRAINT ck_watermark_threshold CHECK (stale_after_seconds > 0),
    CONSTRAINT ck_watermark_observed CHECK (
        observed_median_interval_seconds IS NULL OR observed_median_interval_seconds >= 0),
    CONSTRAINT ck_watermark_success_pair CHECK (
        (last_success_at IS NULL) = (last_success_reference IS NULL)),
    CONSTRAINT ck_watermark_failure_pair CHECK (
        (last_failure_at IS NULL) = (last_failure_code IS NULL)),
    CONSTRAINT ck_watermark_version CHECK (version >= 1),
    CONSTRAINT fk_watermark_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_watermark_alerting
    ON integration.provider_activity_watermarks (tenant_id, alert_state, updated_at);

COMMENT ON TABLE integration.provider_activity_watermarks IS
    'ADR 0040. Last successful inbound order and outbound push per binding and direction. Extends ADR 0006, which records failures that happened and cannot see work that stopped arriving.';
COMMENT ON COLUMN integration.provider_activity_watermarks.alert_state IS
    'SUSPENDED is a rollback that somebody decided, not a silence. A suspended binding keeps recording so the channel reads as visibly off rather than mysteriously quiet.';

-- ------------------------------------------------------------------------ grants

GRANT USAGE ON SCHEMA partner TO horecaos_application;

-- No DELETE on the credential registry. Revoking a partner's access is a status
-- change with a time on it; deleting the row loses the evidence that the
-- credential ever existed, which is the record an incident review needs most.
GRANT SELECT, INSERT, UPDATE ON partner.api_clients TO horecaos_application;

-- No UPDATE and no DELETE. An inbound push is what a partner sent at an instant.
-- Its outcome is decided in the same transaction that inserts it, and a row that
-- can be edited afterwards is not evidence of anything.
GRANT SELECT, INSERT ON partner.inbound_orders TO horecaos_application;

-- No DELETE. Settlement writes the payout columns weeks after ingestion, which
-- is why UPDATE is here; nothing legitimately removes the record of what a
-- partner claimed the customer paid.
GRANT SELECT, INSERT, UPDATE ON ordering.order_external_pricing TO horecaos_application;

-- No UPDATE. A reference is a fact somebody else issued: a partner that reissues
-- a code has issued a second reference, and overwriting the first loses the
-- courier's ability to find the order by the number on their phone.
GRANT SELECT, INSERT ON ordering.order_external_references TO horecaos_application;

-- UPDATE, because attempts, status and the verification columns are live state
-- while a challenge is open. No DELETE: a failed handover is the record of an
-- argument about a bag of food.
GRANT SELECT, INSERT, UPDATE ON ordering.order_handover_challenges TO horecaos_application;

GRANT SELECT, INSERT, UPDATE ON integration.provider_activity_watermarks TO horecaos_application;

GRANT EXECUTE ON FUNCTION ordering.assert_marketplace_binding() TO horecaos_application;
