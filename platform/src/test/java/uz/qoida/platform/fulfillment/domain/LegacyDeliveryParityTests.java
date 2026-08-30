package uz.qoida.platform.fulfillment.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.fulfillment.domain.tariff.DeliveryFeeCalculator;
import uz.qoida.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyDeliveryConfig;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyDiscount;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyPeak;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyStep;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyWindow;
import uz.qoida.platform.fulfillment.domain.tariff.TariffBand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrated fee is the legacy fee (ADR 0037, V0032).
 *
 * <p>The mistake this file exists to stop repeating was a reading mistake. A
 * profiling pass looked at the JSON <em>keys</em> of {@code vendors.delivery},
 * matched them against ADR 0037's model, and concluded the delivery migration was
 * "a field mapping rather than a redesign". The keys did line up. The arithmetic
 * underneath them did not, and no amount of further staring at key names would have
 * shown that.
 *
 * <p>So the assertion here is not that the shapes correspond. {@link LegacyDeliveryOracle}
 * is a line-by-line transcription of the legacy reader,
 * {@code apps/customer/services/cart/calculate_delivery_price.py}, floating-point
 * arithmetic and all. Every test takes a legacy configuration, computes the fee
 * both ways over a sweep of distances and moments, and asserts the two agree to the
 * som. A model that cannot express the legacy fails here rather than in a customer's
 * basket.
 */
class LegacyDeliveryParityTests {

    private static final UUID TARIFF = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String UZS = "UZS";
    private static final UUID ROUTING = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    /** A Wednesday. Nothing in the legacy config is day-sensitive, but a date is needed. */
    private static final LocalDate DAY = LocalDate.parse("2026-08-26");

    // ------------------------------------------------------------ the sweeps

    @Test
    @DisplayName("a stepped tariff with a peak table and a flat discount agrees at every distance")
    void theRealisticBranchAgreesEverywhere() {
        LegacyDeliveryConfig legacy = realisticBranch();
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        assertThat(tariff.activationProblems()).isEmpty();
        assertAgreesEverywhere(legacy, tariff);
    }

    @Test
    @DisplayName("a distance-denominated discount agrees at every distance")
    void theDistanceDiscountAgreesEverywhere() {
        // 7,000 m of free distance. Deliberately clear of the legacy's own
        // off-by-one in that branch, which the test below pins on its own.
        LegacyDeliveryConfig legacy = new LegacyDeliveryConfig(
                3_000, 12_000, 12_000L, 30_000L,
                new LegacyDiscount(7_000L, "distance", 25_000L,
                        List.of(new LegacyWindow(LocalTime.of(11, 0), LocalTime.of(15, 0)))),
                List.of(new LegacyStep(2_000, 1_500L), new LegacyStep(5_000, 2_000L)),
                List.of());

        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        assertThat(tariff.activationProblems()).isEmpty();
        assertAgreesEverywhere(legacy, tariff);
    }

    @Test
    @DisplayName("a branch with no steps at all — a flat fare and a free tail — agrees")
    void theFlatFareBranchAgreesEverywhere() {
        // Fifteen of the legacy rows carry no discount and barely any tariff. The
        // free tail is the interesting part: past the base distance the legacy loop
        // has no steps to walk and charges nothing more.
        LegacyDeliveryConfig legacy = new LegacyDeliveryConfig(
                5_000, 20_000, 15_000L, null, null, List.of(), List.of());

        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        assertThat(tariff.activationProblems()).isEmpty();
        assertAgreesEverywhere(legacy, tariff);
        // Not vacuously: the tail really is free rather than refused, which is what
        // a gap in the tiling would have made it.
        assertThat(feeAt(tariff, 19_000, noon())).isEqualTo(15_000L);
    }

    @Test
    @DisplayName("a peak window that wraps midnight agrees on both sides of it")
    void theWrappingPeakWindowAgreesEverywhere() {
        LegacyDeliveryConfig legacy = new LegacyDeliveryConfig(
                2_000, 10_000, 10_000L, null,
                new LegacyDiscount(3_000L, "amount", null,
                        List.of(new LegacyWindow(LocalTime.of(9, 0), LocalTime.of(11, 0)))),
                List.of(new LegacyStep(8_000, 1_800L)),
                // 21:00 through to 02:00. One row in the legacy, two rules here,
                // because a single row that wraps needs a special case in the
                // evaluator and that special case is what goes missing.
                List.of(new LegacyPeak(LocalTime.of(21, 0), LocalTime.of(2, 0),
                        1_000, 14_000L, List.of(new LegacyStep(9_000, 2_600L)))));

        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        assertThat(tariff.activationProblems()).isEmpty();
        assertAgreesEverywhere(legacy, tariff);
    }

    // --------------------------------------------- what the old model could not do

    @Test
    @DisplayName("a peak table replaces the base one; a surcharge on it computes a different fee")
    void substitutionIsNotASurcharge() {
        LegacyDeliveryConfig legacy = realisticBranch();
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        long offPeak = feeAt(tariff, 6_000, at(13, 0));
        long peak = feeAt(tariff, 6_000, at(19, 0));

        // The oracle is the arbiter, not these literals.
        assertThat(offPeak).isEqualTo(LegacyDeliveryOracle.price(legacy, 6_000, at(13, 0)));
        assertThat(peak).isEqualTo(LegacyDeliveryOracle.price(legacy, 6_000, at(19, 0)));

        // The two tables differ in their base distance, their base fare AND their
        // per-kilometre steps, so the gap between them is not a constant and cannot
        // be a surcharge. V0025's model had only a multiplier and a flat surcharge,
        // which can reproduce this fee at one distance and is wrong at every other
        // — the failure mode that never announces itself.
        long gapAtSix = peak - offPeak;
        long gapAtNine = feeAt(tariff, 9_000, at(19, 0)) - feeAt(tariff, 9_000, at(13, 0));
        assertThat(gapAtSix).isNotEqualTo(gapAtNine);
    }

    @Test
    @DisplayName("a stepped tariff charges each step's own rate, not the last step's over the lot")
    void stepsAreConsumedInOrder() {
        LegacyDeliveryConfig legacy = realisticBranch();
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        // 3,000 m free-in-the-fare, then 2,000 m at 1,500/km, then the rest at
        // 2,000/km: 12,000 + 3,000 + 2,000 = 17,000 at 6,000 m.
        assertThat(feeAt(tariff, 6_000, noon())).isEqualTo(17_000L);
        // Pricing the whole 3,000 m beyond the fare at the last step's rate would
        // give 18,000, which is a plausible wrong answer nobody would query.
        assertThat(feeAt(tariff, 6_000, noon())).isNotEqualTo(18_000L);
    }

    @Test
    @DisplayName("the legacy's half-to-even 500 rounding is reproduced, not approximated")
    void theRoundingRuleIsTheLegacyOne() {
        // A per-kilometre rate that lands the gross exactly on a half-step. Half up
        // would answer 5,500 here and the branch has always charged 5,000.
        LegacyDeliveryConfig legacy = new LegacyDeliveryConfig(
                1_000, 10_000, 4_000L, null, null,
                List.of(new LegacyStep(9_000, 1_000L)), List.of());
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        // 4,000 + 1,250 m at 1,000/km = 5,250, which is 10.5 steps of 500.
        long fee = feeAt(tariff, 2_250, noon());

        assertThat(fee).isEqualTo(5_000L);
        assertThat(fee).isEqualTo(LegacyDeliveryOracle.price(legacy, 2_250, noon()));
        assertAgreesEverywhere(legacy, tariff);
    }

    // ----------------------------------------------- deliberate divergences

    @Test
    @DisplayName("the legacy's distance-discount off-by-one is not carried, and it favours the customer")
    void theDistanceDiscountOffByOneIsCorrected() {
        // The legacy computes the allowance's worth with `if distance > base_distance`
        // where the fee path uses `if distance > 0`, so an allowance between one and
        // two base distances silently loses its per-kilometre part. 3,000 m base and
        // a 4,500 m allowance sits squarely in that hole.
        LegacyDeliveryConfig legacy = new LegacyDeliveryConfig(
                3_000, 12_000, 12_000L, null,
                new LegacyDiscount(4_500L, "distance", null,
                        List.of(new LegacyWindow(LocalTime.of(0, 0), LocalTime.of(23, 59)))),
                List.of(new LegacyStep(9_000, 2_000L)), List.of());
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        long legacyDiscount = LegacyDeliveryOracle.discount(legacy, 9_000, noon());
        long qoidaDiscount = discountAt(tariff, 9_000, noon());

        // The legacy discounts the bare fare. Qoida discounts the fare plus the
        // 1,500 m the allowance actually covers, which is what "the first 4,500
        // metres are free" says on the tin.
        assertThat(legacyDiscount).isEqualTo(12_000L);
        assertThat(qoidaDiscount).isEqualTo(15_000L);
        assertThat(qoidaDiscount).isGreaterThan(legacyDiscount);

        // Stated as an inequality rather than left implicit: this is the one place
        // the corrected model deliberately disagrees with the legacy, it disagrees
        // in the customer's favour, and it is a bug in the legacy rather than a
        // policy anyone chose. Reproducing it would mean carrying an off-by-one in
        // a rate table forever.
    }

    @Test
    @DisplayName("min_order_price is not imported anywhere, because nothing ever read it")
    void theUnreadMinimumIsNotImported() {
        LegacyDeliveryConfig legacy = realisticBranch();
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        // It is on the config, at the top level and inside the discount, and it
        // reaches nothing on the tariff. The zone's min_basket_minor — which ADR
        // 0037 step 7 does enforce — is set by the zone import, and V0032's column
        // comment tells it to leave this alone: applying a refusal to branches that
        // have never been subject to it is not a migration, it is a policy change.
        assertThat(legacy.minOrderPrice()).isNotNull();
        assertThat(legacy.discount().minOrderPrice()).isNotNull();
        assertThat(tariff.minFeeMinor()).isZero();
        assertThat(tariff.maxFeeMinor()).isNull();
    }

    @Test
    @DisplayName("a discount with no time windows imports as no discount, because it never applied")
    void aWindowlessDiscountNeverApplied() {
        LegacyDeliveryConfig legacy = new LegacyDeliveryConfig(
                3_000, 12_000, 12_000L, null,
                new LegacyDiscount(5_000L, "amount", null, null),
                List.of(new LegacyStep(9_000, 2_000L)), List.of());
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        // The legacy reader only ever sets apply_discount inside the window loop, so
        // a discount without times has not reduced a single fee however large its
        // value. Importing it as an always-on discount would hand every customer of
        // this branch 5,000 so'm they never had.
        assertThat(tariff.discounts()).isEmpty();
        assertThat(discountAt(tariff, 6_000, noon())).isZero();
        assertAgreesEverywhere(legacy, tariff);
    }

    @Test
    @DisplayName("the legacy's inclusive reach survives the move to a half-open one")
    void theInclusiveReachIsPreserved() {
        LegacyDeliveryConfig legacy = realisticBranch();
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        // The legacy refuses only when max_distance < distance, so 12,000 m is
        // served. Qoida's reach is half-open to match its bands, so it imports as
        // 12,001 and the same address is still served — and 12,001 m, which the
        // legacy refused, is refused here too.
        assertThat(tariff.maxDistanceMeters()).isEqualTo(12_001);
        assertThat(feeAt(tariff, 12_000, noon()))
                .isEqualTo(LegacyDeliveryOracle.price(legacy, 12_000, noon()));
    }

    @Test
    @DisplayName("a peak window's closing second stays peak, as it was in the legacy")
    void theClosingSecondKeepsItsLegacyAnswer() {
        LegacyDeliveryConfig legacy = realisticBranch();
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        // The legacy comparison is current <= end, so 22:00:00 exactly is still
        // peak. Qoida's windows are half-open, so the import widens the close by a
        // second — otherwise one second a day quietly reverts to the base rate, and
        // the only person who would ever notice is the customer it happens to.
        LocalDateTime lastPeakSecond = LocalDateTime.of(DAY, LocalTime.of(22, 0, 0));
        assertThat(feeAt(tariff, 6_000, lastPeakSecond))
                .isEqualTo(LegacyDeliveryOracle.price(legacy, 6_000, lastPeakSecond));
        assertThat(DeliveryFeeCalculator.compute(tariff, 6_000, lastPeakSecond).rule()).isNotNull();

        LocalDateTime firstOffPeakSecond = LocalDateTime.of(DAY, LocalTime.of(22, 0, 1));
        assertThat(DeliveryFeeCalculator.compute(tariff, 6_000, firstOffPeakSecond).rule()).isNull();
    }

    @Test
    @DisplayName("the imported band sets each tile the whole reach, so no minute is unpriceable")
    void everyBandSetTiles() {
        LegacyDeliveryConfig legacy = realisticBranch();
        DeliveryTariff tariff = LegacyTariffImport.toTariff(TARIFF, legacy, UZS, ROUTING);

        // A peak set with a hole is the 4,700-metre fault confined to four hours a
        // day, which makes it harder to find rather than less serious.
        assertThat(tariff.bandsOf(TariffBand.BASE_SET)).isNotEmpty();
        assertThat(tariff.bandsOf("PEAK_0")).isNotEmpty();
        assertThat(tariff.activationProblems()).isEmpty();
    }

    // ------------------------------------------------------------- machinery

    /** The shape most of the migrating population has: a fare, two steps, one peak, one discount. */
    private static LegacyDeliveryConfig realisticBranch() {
        return new LegacyDeliveryConfig(
                3_000, 12_000, 12_000L, 30_000L,
                new LegacyDiscount(5_000L, "amount", 25_000L,
                        List.of(new LegacyWindow(LocalTime.of(10, 0), LocalTime.of(14, 0)))),
                List.of(new LegacyStep(2_000, 1_500L), new LegacyStep(5_000, 2_000L)),
                List.of(new LegacyPeak(LocalTime.of(18, 0), LocalTime.of(22, 0),
                        2_000, 15_000L,
                        List.of(new LegacyStep(3_000, 2_500L), new LegacyStep(6_000, 3_000L)))));
    }

    /**
     * Every servable distance against every quarter hour of the day.
     *
     * <p>Roughly sixty thousand comparisons. That is the point: a golden test at
     * three distances passes against a model that is wrong everywhere in between,
     * and "wrong everywhere in between" is precisely what a surcharge standing in
     * for a substituted rate table looks like.
     */
    private static void assertAgreesEverywhere(LegacyDeliveryConfig legacy, DeliveryTariff tariff) {
        List<String> disagreements = new ArrayList<>();

        for (int minuteOfDay = 0; minuteOfDay < 24 * 60; minuteOfDay += 15) {
            LocalDateTime moment = LocalDateTime.of(DAY, LocalTime.of(minuteOfDay / 60, minuteOfDay % 60));
            for (int meters = 0; meters <= legacy.maxDistance(); meters += 25) {
                long expectedFee = LegacyDeliveryOracle.price(legacy, meters, moment);
                long expectedDiscount = LegacyDeliveryOracle.discount(legacy, meters, moment);

                var computation = DeliveryFeeCalculator.compute(tariff, meters, moment);
                if (computation.finalFeeMinor() != expectedFee
                        || computation.discountMinor() != expectedDiscount) {
                    disagreements.add("%s at %d m: legacy %d-%d, Qoida %d-%d".formatted(
                            moment.toLocalTime(), meters, expectedFee, expectedDiscount,
                            computation.finalFeeMinor(), computation.discountMinor()));
                }
                if (disagreements.size() > 5) {
                    break;
                }
            }
        }
        assertThat(disagreements).as("fee and discount, legacy versus migrated").isEmpty();
    }

    private static long feeAt(DeliveryTariff tariff, int meters, LocalDateTime moment) {
        return DeliveryFeeCalculator.compute(tariff, meters, moment).finalFeeMinor();
    }

    private static long discountAt(DeliveryTariff tariff, int meters, LocalDateTime moment) {
        return DeliveryFeeCalculator.compute(tariff, meters, moment).discountMinor();
    }

    private static LocalDateTime noon() {
        return at(12, 0);
    }

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DAY, LocalTime.of(hour, minute));
    }
}
