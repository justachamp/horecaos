-- ---------------------------------------------------------------------------
-- V0032. Corrects the ADR 0037 rate-table model against the legacy delivery
-- configuration it claimed to be a field mapping for.
-- ---------------------------------------------------------------------------
--
-- Why this migration exists, since it would have been shorter to edit V0025.
--
-- V0025 was built on a profiling pass that read the JSON *keys* of the legacy
-- `vendors.delivery` column and concluded that ADR 0037's RADIUS-plus-TARIFF
-- model matched it exactly — "a field mapping rather than a redesign". A later
-- audit read the writer, apps/dashboard/schemas/company.py, and then the reader,
-- apps/customer/services/cart/calculate_delivery_price.py, and the shape is not
-- the same. A field's meaning is what the reader does with it, and nothing about
-- a key name carries that.
--
-- V0025 is committed and pushed. Rewriting it would leave a schema that looks as
-- though it was always right, and the thing most worth recording here is that it
-- was not: the model was derived from key names, the key names agreed, and the
-- arithmetic underneath them did not. Four separate corrections follow, each
-- naming what the legacy actually does.
--
-- 1. `prices_per_km` is a stepped tariff — a list of per-kilometre bands, each
--    charging only its own width — and the reader accumulates across all of them.
--    V0025 read the single band containing the distance, which forced every
--    band's base to be authored as a cumulative figure. That is lossy (a step's
--    contribution, width * price / 1000, need not be a whole som and a cumulative
--    base has nowhere to keep the fraction) and it is unsafe to edit (changing one
--    step's rate leaves every later base stating a total that no longer adds up).
--    Bands now accumulate and `base_minor` is local to its band.
--
-- 2. `peak_hours` *replaces* the base band wholesale: the reader substitutes
--    `distance`, `distance_price` and `prices_per_km` together. V0025 modelled a
--    multiplier and a surcharge on the base rate. Adding a surcharge to a base
--    rate computes a different number from swapping the rate out, and the
--    difference is money. A time rule may now name a band set.
--
-- 3. `discount` is a REQUIRED field of VendorDeliveryConfig, not an optional
--    extra — the fifteen branch rows lacking one were not written by that schema
--    at all. It carries a value, a mode that is `amount` or `distance`, and its
--    own time windows, independent of the peak windows. ADR 0037 had a threshold
--    waiver (which asks about the basket) and a promotion benefit (which arrives
--    from ADR 0018); neither asks about the clock or the distance, so neither
--    could express any of the discounts every migrated branch carries.
--
-- 4. The legacy rounds both the fee and the discount to the nearest 500 so'm,
--    with Python's round — which is half to even. Qoida assumed whole minor units
--    and half up. On a 500 so'm step with round rates, a great many fees land
--    exactly on a half-step, so this is not a rounding nicety: 1,250 becomes
--    1,000 there and 1,500 here.
--
-- Two things the legacy declares and never enforces are deliberately NOT given a
-- home here. `min_order_price`, at the top level and inside `discount`, is read
-- by no code anywhere in the legacy backend. Migrating it into
-- service_zone_versions.min_basket_minor — which ADR 0037 step 7 *does* enforce —
-- would introduce a refusal the legacy system never made, on branches that never
-- agreed to it. See the column comment below. And `vendors.visibility_distance`
-- is a second, independent radius governing whether a branch is offered at all;
-- it is serviceability search, not this tariff's reach, and folding the two into
-- one column is how a branch quietly stops being listed.

-- ---------------------------------------------------------------------------
-- Rate-table version: accrual, and the rounding step
-- ---------------------------------------------------------------------------

ALTER TABLE fulfillment.delivery_tariff_versions
    ADD COLUMN distance_accrual varchar(24) NOT NULL DEFAULT 'STARTED_KILOMETRE',
    ADD COLUMN fee_rounding_step_minor bigint,
    ADD COLUMN fee_rounding_mode varchar(16);

ALTER TABLE fulfillment.delivery_tariff_versions
    ADD CONSTRAINT ck_tariff_version_accrual CHECK (
        distance_accrual IN ('STARTED_KILOMETRE', 'PRORATED_METRE')),
    -- A step and a mode are one decision. Stated as an equivalence: the
    -- "(a IS NULL AND b IS NULL) OR (...)" form leaves a three-valued-logic hole,
    -- and that exact bug shipped here once.
    ADD CONSTRAINT ck_tariff_version_rounding_pairing CHECK (
        (fee_rounding_step_minor IS NULL) = (fee_rounding_mode IS NULL)),
    ADD CONSTRAINT ck_tariff_version_rounding_step CHECK (
        fee_rounding_step_minor IS NULL OR fee_rounding_step_minor > 0),
    ADD CONSTRAINT ck_tariff_version_rounding_mode CHECK (
        fee_rounding_mode IS NULL OR fee_rounding_mode IN ('HALF_UP', 'HALF_EVEN'));

COMMENT ON COLUMN fulfillment.delivery_tariff_versions.distance_accrual IS
    'ADR 0037, corrected in V0032. STARTED_KILOMETRE charges every begun kilometre whole, counted per band from that band''s floor; PRORATED_METRE charges metres * per_km / 1000, which is what the legacy dashboard does. At 3,100 m on a 2,000 so''m rate the two differ by 1,800 so''m, so this is a property of the rate table and not of the codebase.';
COMMENT ON COLUMN fulfillment.delivery_tariff_versions.fee_rounding_step_minor IS
    'ADR 0037, added in V0032. The multiple every fee and every tariff discount lands on, or null for whole minor units. Imported legacy branches are 500: the legacy rounds to the nearest 500 so''m and a migrated branch that did not would charge a different price on its first order.';
COMMENT ON COLUMN fulfillment.delivery_tariff_versions.fee_rounding_mode IS
    'ADR 0037, added in V0032. HALF_EVEN exists solely to reproduce Python''s round, which is what every legacy fee was rounded with; on a 500 step it sends 1,250 to 1,000 where HALF_UP sends it to 1,500. Author new tariffs HALF_UP.';
COMMENT ON COLUMN fulfillment.delivery_tariff_versions.max_distance_meters IS
    'ADR 0037, restated in V0032. Half-open: the tariff prices [0, max_distance_meters) and refuses at and past it. Half-open to match the bands — an inclusive reach over half-open bands leaves exactly one unpriceable metre at the boundary, which is the fault the tiling rule exists to forbid. A legacy branch whose max_distance was inclusive imports as that value plus one.';

-- ---------------------------------------------------------------------------
-- Bands belong to a named set, and each band's base is its own
-- ---------------------------------------------------------------------------
--
-- BASE is the table in force when no time rule substitutes another. A rule naming
-- a set puts that whole set in force for its window, which is what a legacy
-- peak_hours entry does.

ALTER TABLE fulfillment.delivery_tariff_bands
    ADD COLUMN band_set varchar(32) NOT NULL DEFAULT 'BASE';

ALTER TABLE fulfillment.delivery_tariff_bands
    ADD CONSTRAINT ck_tariff_band_set CHECK (band_set ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$');

-- Tiling is per set: a peak set with a hole is the same 4,700-metre fault as a
-- base set with a hole, confined to a four-hour window, which makes it harder to
-- find rather than less serious. Both halves of the rule move with the set — the
-- overlap half here, the gap half at activation.
ALTER TABLE fulfillment.delivery_tariff_bands
    DROP CONSTRAINT ex_tariff_band_no_overlap;

ALTER TABLE fulfillment.delivery_tariff_bands
    ADD CONSTRAINT ex_tariff_band_no_overlap EXCLUDE USING gist (
        tariff_id WITH =, tariff_version WITH =, band_set WITH =,
        int4range(from_meters, to_meters, '[)') WITH &&
    );

-- The primary key admitted one sequence per version; a version now holds a base
-- table and one table per peak window, and their sequences number independently.
ALTER TABLE fulfillment.delivery_tariff_bands
    DROP CONSTRAINT delivery_tariff_bands_pkey;

ALTER TABLE fulfillment.delivery_tariff_bands
    ADD CONSTRAINT pk_tariff_band PRIMARY KEY (tariff_id, tariff_version, band_set, sequence);

COMMENT ON COLUMN fulfillment.delivery_tariff_bands.band_set IS
    'ADR 0037, added in V0032. BASE unless a time rule substitutes this set for its window. Each set is a complete rate table tiling [0, max_distance_meters) on its own.';
COMMENT ON COLUMN fulfillment.delivery_tariff_bands.base_minor IS
    'ADR 0037, corrected in V0032. The flat charge for ENTERING this band, not the cumulative charge for reaching it. V0025 read only the containing band, which forced cumulative bases; those cannot hold the fraction a stepped tariff produces and they silently stop adding up when an earlier band is edited.';

-- ---------------------------------------------------------------------------
-- A time rule may replace the table, not only surcharge it
-- ---------------------------------------------------------------------------

ALTER TABLE fulfillment.delivery_tariff_time_rules
    ADD COLUMN band_set varchar(32);

ALTER TABLE fulfillment.delivery_tariff_time_rules
    ADD CONSTRAINT ck_time_rule_band_set CHECK (
        band_set IS NULL
        OR (band_set ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$' AND band_set <> 'BASE'));

COMMENT ON COLUMN fulfillment.delivery_tariff_time_rules.band_set IS
    'ADR 0037, added in V0032. The band set this rule puts in force, or null to leave BASE standing. Legacy peak_hours substitutes the whole table, which a multiplier and a surcharge cannot express: adding a surcharge to a base rate computes a different number from swapping the rate out. Substitution and surcharge compose — a rule may do either or both.';

-- ---------------------------------------------------------------------------
-- The rate table's own standing discount
-- ---------------------------------------------------------------------------
--
-- Distinct from ADR 0037's stage 8 waiver and stage 9 benefit, and it has to be:
-- both of those ask about the basket, and this one asks about the clock and the
-- distance. It resolves in fulfillment with the fee, before PricingEngine runs,
-- because the engine is not allowed to read either.

CREATE TABLE fulfillment.delivery_tariff_discounts (
    tenant_id uuid NOT NULL,
    tariff_id uuid NOT NULL,
    tariff_version integer NOT NULL,
    sequence integer NOT NULL,
    priority integer NOT NULL DEFAULT 0,

    -- AMOUNT subtracts a sum. DISTANCE_ALLOWANCE means "the first N metres are
    -- free": the discount is what the band table in force would have charged for
    -- that distance, so an allowance keeps its value during a peak window.
    discount_kind varchar(24) NOT NULL,
    amount_minor bigint,
    allowance_meters integer,

    -- The discount's own windows, independent of peak_hours. A branch may discount
    -- at lunchtime and surcharge at dinner, and the legacy config says so with two
    -- unrelated lists.
    day_of_week_mask smallint NOT NULL,
    local_from_time time NOT NULL,
    local_to_time time NOT NULL,

    PRIMARY KEY (tariff_id, tariff_version, sequence),
    CONSTRAINT ck_tariff_discount_kind CHECK (
        discount_kind IN ('AMOUNT', 'DISTANCE_ALLOWANCE')),
    -- Two equivalences rather than one null check on whichever column the kind
    -- selects. A value set for the wrong kind is a number nothing reads, which is
    -- how a discount silently becomes zero.
    CONSTRAINT ck_tariff_discount_amount_agrees CHECK (
        (discount_kind = 'AMOUNT') = (amount_minor IS NOT NULL)),
    CONSTRAINT ck_tariff_discount_allowance_agrees CHECK (
        (discount_kind = 'DISTANCE_ALLOWANCE') = (allowance_meters IS NOT NULL)),
    CONSTRAINT ck_tariff_discount_amounts CHECK (
        (amount_minor IS NULL OR amount_minor >= 0)
        AND (allowance_meters IS NULL OR allowance_meters >= 0)),
    CONSTRAINT ck_tariff_discount_mask CHECK (day_of_week_mask BETWEEN 1 AND 127),
    -- No wrapping window, for TariffTimeRule's reason: a single row that wraps
    -- needs the evaluator to special-case from > to, and the special case is
    -- silently absent in every implementation that forgets it.
    CONSTRAINT ck_tariff_discount_window CHECK (local_to_time > local_from_time),
    CONSTRAINT fk_tariff_discount_version FOREIGN KEY (tenant_id, tariff_id, tariff_version)
        REFERENCES fulfillment.delivery_tariff_versions (tenant_id, tariff_id, version)
        ON DELETE CASCADE
);

COMMENT ON TABLE fulfillment.delivery_tariff_discounts IS
    'ADR 0037, added in V0032. The legacy VendorDeliveryConfig.discount, which is a required field carrying its own time windows. Capped at the fee when it resolves, so the delivery line can never pay the customer.';

-- ---------------------------------------------------------------------------
-- min_order_price is declared and never enforced. It stays that way.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN fulfillment.service_zone_versions.min_basket_minor IS
    'ADR 0037 step 7, enforced: below it, checkout is refused with BASKET_BELOW_DELIVERY_MINIMUM. Leave it NULL for a branch imported from legacy. The legacy min_order_price — both the top-level one and the one inside discount — is read by no code anywhere in that backend, so migrating it here would apply a refusal to branches that have never been subject to it, on the strength of a number nobody has checked since it was typed.';

-- ---------------------------------------------------------------------------
-- The resolution has to record the discount it applied
-- ---------------------------------------------------------------------------

ALTER TABLE fulfillment.delivery_fee_resolutions
    ADD COLUMN tariff_discount_minor bigint,
    ADD COLUMN discount_sequence integer;

ALTER TABLE fulfillment.delivery_fee_resolutions
    -- A discount larger than the fee it reduces is the negative-fee bug, and the
    -- calculator caps it. The constraint says so as well, because the cap is worth
    -- more than the code that applies it: this row is the evidence a payout
    -- dispute is settled from.
    ADD CONSTRAINT ck_fee_resolution_discount_within_fee CHECK (
        tariff_discount_minor IS NULL
        OR (final_fee_minor IS NOT NULL
            AND tariff_discount_minor >= 0
            AND tariff_discount_minor <= final_fee_minor)),
    ADD CONSTRAINT ck_fee_resolution_discount_pairing CHECK (
        (discount_sequence IS NULL) OR (tariff_discount_minor IS NOT NULL));

COMMENT ON COLUMN fulfillment.delivery_fee_resolutions.tariff_discount_minor IS
    'ADR 0037, added in V0032. The rate table''s own standing discount, already capped at final_fee_minor. Recorded beside the fee rather than subtracted into it: a fee stored net cannot be told apart from a cheaper tariff, and six weeks later that is the whole question.';

-- ADR 0018's stage vocabulary gains the tariff discount. It is not the stage 8
-- waiver: the waiver asks whether the basket cleared a threshold, and this asks
-- what time it is and how far away the customer lives. Naming them the same would
-- make the two indistinguishable in every report that groups by adjustment type.
ALTER TABLE pricing.quote_adjustments
    DROP CONSTRAINT ck_adjustment_type;

ALTER TABLE pricing.quote_adjustments
    ADD CONSTRAINT ck_adjustment_type CHECK (
        adjustment_type IN ('BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT',
                            'FEE', 'TAX', 'ROUNDING',
                            'DELIVERY_FEE_WAIVER',
                            'DELIVERY_FEE_BENEFIT',
                            -- ADR 0037 stage 6, resolved with the fee in
                            -- fulfillment because it is a function of distance and
                            -- the local clock, neither of which PricingEngine may
                            -- read.
                            'DELIVERY_TARIFF_DISCOUNT')
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.delivery_tariff_discounts TO qoida_application;
