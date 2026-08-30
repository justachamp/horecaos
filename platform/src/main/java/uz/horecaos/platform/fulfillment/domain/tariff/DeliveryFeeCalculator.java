package uz.horecaos.platform.fulfillment.domain.tariff;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The fee arithmetic, and nothing else (ADR 0037).
 *
 * <p>A pure function of a tariff, a distance and a local moment. Kept apart from
 * the resolver so the formula can be tested on literals and read without
 * following three collaborators, for the same reason ADR 0018 keeps
 * {@code PricingEngine} away from the lookups.
 *
 * <p>The formula, as V0032 corrects it:
 *
 * <pre>
 * rule     = the highest-priority matching time rule, or none
 * set      = rule.bandSet, or BASE            (a rule may replace the table outright)
 * gross    = sum over every band of `set` the journey enters, of
 *              band.base + charge(metres covered inside that band, band.perKm)
 *            where charge is per started kilometre or pro rata by metre,
 *            according to the tariff's accrual
 * fee      = round(gross * rule.multiplier / 10000) + rule.surcharge
 * fee      = roundToStep(fee, tariff.step, tariff.roundingRule)
 * fee      = clamp(fee, tariff.min, tariff.max)
 * discount = the highest-priority matching discount, capped at fee
 * </pre>
 *
 * <p>Three details are spelled out because two implementations that disagree on
 * any of them produce two defensible fees for one address. The multiplier applies
 * <em>before</em> the surcharge — a peak surcharge is a flat addition, not
 * something the multiplier compounds. The rounding step is applied <em>after</em>
 * the rule and <em>before</em> the clamp, so a cap is exactly the cap rather than
 * a number the step could push past it. And the discount is computed from the
 * gross band charge and rounded on its own, then capped: rounding the net instead
 * would make the fee and the discount shown to the customer fail to add up to the
 * total, which is the one arithmetic a receipt has to survive.
 *
 * <p>Intermediate amounts are carried in thousandths of a minor unit, because
 * pro-rated metres divide by 1,000 and a tariff that rounds each band separately
 * charges a different total from one that rounds once. There is no double
 * anywhere: binary floating point cannot represent most decimal amounts exactly,
 * so a fee computed twice can differ in the last som, and a quote's whole promise
 * is that it is reproducible.
 */
public final class DeliveryFeeCalculator {

    private static final int BASIS_POINTS = 10_000;
    private static final int METERS_PER_KILOMETER = 1_000;

    /** Thousandths of a minor unit. Metres divide by this and so nothing else has to. */
    private static final long MILLI = 1_000L;

    private DeliveryFeeCalculator() {
    }

    /**
     * @throws UnpriceableDistanceException when the band set in force has no band
     *                                      containing the distance, which activation
     *                                      validation is supposed to have made
     *                                      impossible
     */
    public static Computation compute(DeliveryTariff tariff, int distanceMeters,
            LocalDateTime localMoment) {

        DayOfWeek day = localMoment.getDayOfWeek();
        LocalTime time = localMoment.toLocalTime();

        Optional<TariffTimeRule> rule = matchingRule(tariff, day, time);
        String set = rule.map(TariffTimeRule::effectiveBandSet).orElse(TariffBand.BASE_SET);
        List<TariffBand> bands = tariff.bandsOf(set);

        Accrued accrued = accrue(bands, distanceMeters, tariff.distanceAccrual(), true);
        if (accrued.band() == null) {
            throw new UnpriceableDistanceException(tariff, set, distanceMeters);
        }

        long multiplied = rule
                .map(matched -> roundHalfUp(accrued.milliMinor(), matched.multiplierBasisPoints()))
                .orElse(accrued.milliMinor());
        long withSurchargeMilli = Math.addExact(multiplied,
                Math.multiplyExact(rule.map(TariffTimeRule::surchargeMinor).orElse(0L), MILLI));

        long stepped = settle(tariff, withSurchargeMilli);

        long clamped = Math.max(stepped, tariff.minFeeMinor());
        if (tariff.maxFeeMinor() != null) {
            clamped = Math.min(clamped, tariff.maxFeeMinor());
        }

        final long feeMinor = clamped;
        Optional<TariffDiscount> discount = matchingDiscount(tariff, day, time);
        long discountMinor = discount
                .map(matched -> discountFor(tariff, matched, bands, feeMinor))
                .orElse(0L);

        return new Computation(accrued.band(), rule.orElse(null), discount.orElse(null),
                stepped, feeMinor, discountMinor);
    }

    /**
     * Clamps an externally supplied amount by the same bounds a computed fee gets
     * (ADR 0037 step 6).
     *
     * <p>{@code PROVIDER_QUOTE} above the cap charges the cap. The balance is a
     * subsidy candidate under ADR 0014 and never a higher customer fee, which is
     * the entire point of offering the mode with a cap rather than raw.
     */
    public static long clamp(DeliveryTariff tariff, long feeMinor) {
        long clamped = Math.max(feeMinor, tariff.minFeeMinor());
        return tariff.maxFeeMinor() == null ? clamped : Math.min(clamped, tariff.maxFeeMinor());
    }

    /**
     * Walks the bands the journey actually enters, accumulating each one's own
     * entry charge and the stretch of it covered.
     *
     * <p>V0025 read the single containing band, which meant every band's base had
     * to be authored as a cumulative figure. That could not express a stepped
     * tariff whose steps contribute fractional amounts, and it made editing one
     * step silently wrong for every later band. Traversal states the same tariffs
     * with local numbers and states more of them.
     *
     * @param strict when true, a distance past the set's coverage yields a null
     *               band and the caller refuses. Discounts pass false: a legacy
     *               allowance longer than the step list charges nothing for the
     *               tail rather than failing, which is what the legacy reader does
     *               when its loop runs out of steps
     */
    private static Accrued accrue(List<TariffBand> bands, int distanceMeters,
            DistanceAccrual accrual, boolean strict) {

        long milli = 0L;
        TariffBand containing = null;

        for (TariffBand band : bands) {
            if (band.fromMeters() > distanceMeters) {
                break;
            }
            milli = Math.addExact(milli, Math.multiplyExact(band.baseMinor(), MILLI));

            int covered = Math.min(distanceMeters, band.toMeters()) - band.fromMeters();
            milli = Math.addExact(milli, chargeMilli(covered, band.perKmMinor(), accrual));

            if (band.contains(distanceMeters)) {
                containing = band;
                break;
            }
        }

        if (containing == null && !strict && !bands.isEmpty()) {
            containing = bands.getLast();
        }
        return new Accrued(milli, containing);
    }

    /** What {@code covered} metres cost inside one band, in thousandths of a minor unit. */
    private static long chargeMilli(int covered, long perKmMinor, DistanceAccrual accrual) {
        if (covered <= 0 || perKmMinor == 0) {
            return 0L;
        }
        return accrual == DistanceAccrual.PRORATED_METRE
                // metres * perKm / 1000 kept exact by leaving the division to the
                // milli scale everything else is already in.
                ? Math.multiplyExact((long) covered, perKmMinor)
                : Math.multiplyExact(
                        ceilDivide(covered, METERS_PER_KILOMETER),
                        Math.multiplyExact(perKmMinor, MILLI));
    }

    /**
     * The discount, rounded on its own and then capped at the fee.
     *
     * <p>The cap is not decoration. A distance allowance longer than the journey,
     * or a flat amount larger than a short trip's fee, would otherwise pay the
     * customer to order — and two independent reductions that can each exceed the
     * fee sum below zero, which is the failure ADR 0037 already refuses at stage 9.
     */
    private static long discountFor(DeliveryTariff tariff, TariffDiscount discount,
            List<TariffBand> bands, long feeMinor) {

        long rawMilli = switch (discount.kind()) {
            case AMOUNT -> Math.multiplyExact(discount.amountMinor(), MILLI);
            // The band charge for the allowance under the table currently in force,
            // which is what "the first N metres are free" has to mean if it is to
            // stay true during a peak window.
            case DISTANCE_ALLOWANCE ->
                    accrue(bands, discount.allowanceMeters(), tariff.distanceAccrual(), false)
                            .milliMinor();
        };
        return Math.min(settle(tariff, rawMilli), feeMinor);
    }

    /**
     * Lands a milli-scaled amount on the tariff's rounding step.
     *
     * <p>With no step the amount simply becomes whole minor units, half up. For UZS
     * a minor unit is a whole som, so this is already the coarsest a fee can
     * legitimately be — and an imported branch takes it coarser still, to the 500
     * so'm its dashboard has always rounded to.
     */
    private static long settle(DeliveryTariff tariff, long milliMinor) {
        Long step = tariff.feeRoundingStepMinor();
        if (step == null) {
            return divideHalfUp(milliMinor, MILLI);
        }
        long divisor = Math.multiplyExact(step, MILLI);
        long units = tariff.feeRoundingRule() == RoundingRule.HALF_EVEN
                ? divideHalfEven(milliMinor, divisor)
                : divideHalfUp(milliMinor, divisor);
        return Math.multiplyExact(units, step);
    }

    /**
     * The rule that governs.
     *
     * <p>Highest priority, then the lowest sequence. The second key exists for the
     * reason every tiebreak in this codebase exists: two equally prioritised
     * windows overlapping at 19:00 must not surcharge differently depending on
     * which row came back first.
     */
    private static Optional<TariffTimeRule> matchingRule(DeliveryTariff tariff, DayOfWeek day,
            LocalTime localTime) {

        return tariff.timeRules().stream()
                .filter(rule -> rule.matches(day, localTime))
                .min(Comparator.comparingInt(TariffTimeRule::priority).reversed()
                        .thenComparingInt(TariffTimeRule::sequence));
    }

    /** Same total order as the time rules, and for the same reason. */
    private static Optional<TariffDiscount> matchingDiscount(DeliveryTariff tariff, DayOfWeek day,
            LocalTime localTime) {

        return tariff.discounts().stream()
                .filter(discount -> discount.matches(day, localTime))
                .min(Comparator.comparingInt(TariffDiscount::priority).reversed()
                        .thenComparingInt(TariffDiscount::sequence));
    }

    /** Integer ceiling division. Both operands are non-negative here by construction. */
    private static long ceilDivide(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    private static long roundHalfUp(long amount, int basisPoints) {
        return divideHalfUp(Math.multiplyExact(amount, basisPoints), BASIS_POINTS);
    }

    /** Half up, in integers. Non-negative numerators only, which is all this class produces. */
    private static long divideHalfUp(long numerator, long denominator) {
        return (numerator + denominator / 2) / denominator;
    }

    /**
     * Half to even, in integers.
     *
     * <p>Here for exactly one reason: it is what Python's {@code round} does, and
     * therefore what every fee the legacy dashboard ever quoted was rounded with.
     * A migrated branch that used half-up instead would charge 1,500 where it used
     * to charge 1,000, on every fee that lands on a half-step — which is a great
     * many of them, since the step is 500 and the rates are round numbers.
     */
    private static long divideHalfEven(long numerator, long denominator) {
        long quotient = numerator / denominator;
        long remainder = numerator - quotient * denominator;
        long twice = remainder * 2;
        if (twice > denominator || (twice == denominator && (quotient & 1L) == 1L)) {
            quotient++;
        }
        return quotient;
    }

    private record Accrued(long milliMinor, TariffBand band) { }

    /**
     * @param band             the band the journey ended in, which is what the
     *                         evidence row names
     * @param computedFeeMinor the fee before the tariff's clamp, kept so evidence
     *                         can show that a cap was what decided the amount
     * @param finalFeeMinor    what the fee line says, before the discount and
     *                         before the stage 8 waiver and stage 9 benefit
     * @param discountMinor    the rate table's own standing reduction, already
     *                         capped at the fee. Reported beside the fee rather
     *                         than subtracted into it, because a fee shown net
     *                         cannot be told apart from a cheaper tariff
     */
    public record Computation(TariffBand band, TariffTimeRule rule, TariffDiscount discount,
            long computedFeeMinor, long finalFeeMinor, long discountMinor) {

        /** What the customer actually pays for delivery under this tariff alone. */
        public long netFeeMinor() {
            return finalFeeMinor - discountMinor;
        }
    }

    /**
     * Thrown when the band tiling has a hole after all.
     *
     * <p>Activation validation exists to make this unreachable. It is still an
     * exception rather than a zero, because a fee of zero produced by a broken
     * rate table is indistinguishable from free delivery, and that is precisely
     * the confusion ADR 0037 refuses to allow anywhere.
     */
    public static final class UnpriceableDistanceException extends RuntimeException {
        public UnpriceableDistanceException(DeliveryTariff tariff, String bandSet,
                int distanceMeters) {
            super("Tariff %s version %d band set '%s' has no band covering %d m; it does not tile"
                    .formatted(tariff.tariffId(), tariff.version(), bandSet, distanceMeters));
        }
    }
}
