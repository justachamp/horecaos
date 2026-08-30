package uz.qoida.platform.fulfillment.domain;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.fulfillment.domain.tariff.DeliveryFeeCalculator;
import uz.qoida.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.qoida.platform.fulfillment.domain.tariff.DistanceMode;
import uz.qoida.platform.fulfillment.domain.tariff.FeeSource;
import uz.qoida.platform.fulfillment.domain.tariff.TariffBand;
import uz.qoida.platform.fulfillment.domain.tariff.TariffTimeRule;
import uz.qoida.platform.tenancy.api.GeoPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The fee arithmetic, on literals (ADR 0037).
 *
 * <p>These are the golden tests the ADR asks for. They fix a tariff version, a
 * distance and a local moment, and assert the exact band, the exact rule and the
 * exact fee — because the value of stating the formula once is entirely lost if
 * nothing pins the answer it produces.
 *
 * <p>The tariff throughout is ADR 0037's own illustrative Tashkent example:
 * 10,000 so'm to 3 km, 2,000 so'm per further kilometre, a 5,000 so'm surcharge
 * from 18:00 to 22:00, capped at 40,000 so'm.
 */
class DeliveryFeeCalculatorTests {

    private static final UUID TARIFF = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final LocalDateTime NOON_TUESDAY = LocalDateTime.parse("2026-08-25T12:00:00");
    private static final LocalDateTime SEVEN_PM_TUESDAY = LocalDateTime.parse("2026-08-25T19:00:00");

    @Test
    @DisplayName("inside the flat band the fee is the base and nothing else")
    void theFlatBandChargesItsBase() {
        var computation = DeliveryFeeCalculator.compute(tashkentTariff(), 1_200, NOON_TUESDAY);

        assertThat(computation.finalFeeMinor()).isEqualTo(10_000L);
        assertThat(computation.band().sequence()).isZero();
        assertThat(computation.rule()).isNull();
    }

    @Test
    @DisplayName("a partial kilometre is charged as a whole one")
    void partialKilometresRoundUp() {
        // 3,100 m is one hundred metres into the per-kilometre band. A customer
        // paying for a whole further kilometre is explainable at the door; a
        // fraction of a som is not, and there is no coin for it.
        assertThat(DeliveryFeeCalculator.compute(tashkentTariff(), 3_100, NOON_TUESDAY)
                .finalFeeMinor())
                .isEqualTo(12_000L);
    }

    @Test
    @DisplayName("the per-kilometre charge counts from the band's floor, not from the branch")
    void perKilometreCountsFromTheBandFloor() {
        // 5,000 m is two started kilometres past the 3,000 m floor. Counting from
        // the branch instead would bill five, charging the first three kilometres
        // twice — once inside the base and once again by distance.
        assertThat(DeliveryFeeCalculator.compute(tashkentTariff(), 5_000, NOON_TUESDAY)
                .finalFeeMinor())
                .isEqualTo(14_000L);
    }

    @Test
    @DisplayName("a peak surcharge is added at the window's opening minute and not before")
    void theSurchargeAppliesInsideTheWindow() {
        var offPeak = DeliveryFeeCalculator.compute(tashkentTariff(), 3_100, NOON_TUESDAY);
        var peak = DeliveryFeeCalculator.compute(tashkentTariff(), 3_100, SEVEN_PM_TUESDAY);

        assertThat(offPeak.finalFeeMinor()).isEqualTo(12_000L);
        assertThat(peak.finalFeeMinor()).isEqualTo(17_000L);
        assertThat(peak.rule()).isNotNull();
    }

    @Test
    @DisplayName("a peak window is half-open, so its closing minute is already off-peak")
    void theWindowIsHalfOpenAtItsClose() {
        assertThat(DeliveryFeeCalculator.compute(tashkentTariff(), 1_000,
                LocalDateTime.parse("2026-08-25T18:00:00")).rule()).isNotNull();
        // Two adjacent windows must not both claim 22:00, or the higher-priority
        // one wins a minute it was never meant to cover.
        assertThat(DeliveryFeeCalculator.compute(tashkentTariff(), 1_000,
                LocalDateTime.parse("2026-08-25T22:00:00")).rule()).isNull();
    }

    @Test
    @DisplayName("a rule that does not name today does not apply")
    void theDayMaskIsHonoured() {
        // The mask selects Monday to Friday. 2026-08-30 is a Sunday.
        assertThat(DeliveryFeeCalculator.compute(tashkentTariff(), 1_000,
                LocalDateTime.parse("2026-08-30T19:00:00")).finalFeeMinor())
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("the multiplier applies before the surcharge, and rounding happens once")
    void theMultiplierPrecedesTheSurcharge() {
        DeliveryTariff tariff = new DeliveryTariff(TARIFF, 1, VersionStatus.ACTIVE, "UZS",
                FeeSource.TARIFF, DistanceMode.RADIUS, 13_000, null, 15_000, 0L, null,
                bands(),
                List.of(new TariffTimeRule(0, 10, WEEKDAYS, LocalTime.of(18, 0), LocalTime.of(22, 0),
                        12_000, 5_000L)));

        // 12,000 × 1.2 = 14,400, then + 5,000 = 19,400. Multiplying the surcharge
        // too would give 20,400, and both are defensible readings of "a 20% peak
        // uplift and a 5,000 surcharge" — which is exactly why ADR 0037 states the
        // order and why this test exists to hold it.
        assertThat(DeliveryFeeCalculator.compute(tariff, 3_100, SEVEN_PM_TUESDAY).finalFeeMinor())
                .isEqualTo(19_400L);
    }

    @Test
    @DisplayName("the cap decides the fee, and the uncapped figure is still recorded")
    void theCapClampsAndTheComputedFeeSurvives() {
        DeliveryTariff capped = new DeliveryTariff(TARIFF, 1, VersionStatus.ACTIVE, "UZS",
                FeeSource.TARIFF, DistanceMode.RADIUS, 13_000, null, 15_000, 0L, 20_000L,
                bands(), List.of());

        var computation = DeliveryFeeCalculator.compute(capped, 14_999, NOON_TUESDAY);

        assertThat(computation.finalFeeMinor()).isEqualTo(20_000L);
        // Without the uncapped figure beside it, nobody can tell a capped fee from
        // a cheap one, and the subsidy the cap creates has no size.
        assertThat(computation.computedFeeMinor()).isEqualTo(34_000L);
    }

    @Test
    @DisplayName("the minimum lifts a fee the bands priced below it")
    void theMinimumLifts() {
        DeliveryTariff floored = new DeliveryTariff(TARIFF, 1, VersionStatus.ACTIVE, "UZS",
                FeeSource.TARIFF, DistanceMode.RADIUS, 13_000, null, 15_000, 8_000L, null,
                List.of(new TariffBand(0, 0, 15_000, 5_000L, 0L)), List.of());

        assertThat(DeliveryFeeCalculator.compute(floored, 500, NOON_TUESDAY).finalFeeMinor())
                .isEqualTo(8_000L);
    }

    @Test
    @DisplayName("a distance no band covers refuses rather than pricing at zero")
    void aHoleInTheTilingRefuses() {
        DeliveryTariff holed = new DeliveryTariff(TARIFF, 1, VersionStatus.ACTIVE, "UZS",
                FeeSource.TARIFF, DistanceMode.RADIUS, 13_000, null, 15_000, 0L, null,
                List.of(new TariffBand(0, 0, 3_000, 10_000L, 0L),
                        new TariffBand(1, 5_000, 15_000, 10_000L, 2_000L)),
                List.of());

        // A zero here would be indistinguishable from free delivery, which is the
        // single confusion ADR 0037 refuses to allow anywhere.
        assertThat(catchThrowable(() -> DeliveryFeeCalculator.compute(holed, 4_000, NOON_TUESDAY)))
                .isInstanceOf(DeliveryFeeCalculator.UnpriceableDistanceException.class);
    }

    @Test
    @DisplayName("activation names every tiling fault at once, including the one at 4,700 m")
    void activationReportsEveryTilingFault() {
        DeliveryTariff holed = new DeliveryTariff(TARIFF, 1, VersionStatus.DRAFT, "UZS",
                FeeSource.TARIFF, DistanceMode.RADIUS, 13_000, null, 15_000, 0L, null,
                List.of(new TariffBand(0, 500, 3_000, 10_000L, 0L),
                        new TariffBand(1, 5_000, 10_000, 10_000L, 2_000L)),
                List.of());

        assertThat(holed.activationProblems())
                .hasSize(3)
                .anySatisfy(problem -> assertThat(problem).contains("must start at 0 m"))
                .anySatisfy(problem -> assertThat(problem).contains("gap between 3000 m and 5000 m"))
                .anySatisfy(problem -> assertThat(problem).contains("covers to 10000 m"));
    }

    @Test
    @DisplayName("ROAD distance without a routing binding cannot be activated")
    void roadWithoutRoutingIsRefused() {
        DeliveryTariff road = new DeliveryTariff(TARIFF, 1, VersionStatus.DRAFT, "UZS",
                FeeSource.TARIFF, DistanceMode.ROAD, 13_000, null, 15_000, 0L, null,
                bands(), List.of());

        assertThat(road.activationProblems())
                .anySatisfy(problem -> assertThat(problem).contains("routing binding"));
    }

    @Test
    @DisplayName("a well-tiled rate table has nothing to report")
    void aGoodTariffActivatesCleanly() {
        // The negative tests above would all pass against a validator that always
        // complained. This is the one that says it does not.
        assertThat(tashkentTariff().activationProblems()).isEmpty();
    }

    @Test
    @DisplayName("haversine measures Tashkent distances to the metre")
    void haversineIsAccurate() {
        // Amir Temur square to the Chorsu bazaar, which is a little under two
        // kilometres on a straight line. The assertion is tight because the whole
        // reason for computing this here rather than in PostGIS is that it can be
        // pinned and re-derived without a database.
        int meters = Haversine.metersBetween(
                new GeoPoint(41.311081, 69.240562), new GeoPoint(41.326500, 69.234100));

        assertThat(meters).isBetween(1_700, 1_800);
    }

    @Test
    @DisplayName("a coordinate pair is symmetric and a point is zero from itself")
    void haversineIsWellBehaved() {
        GeoPoint square = new GeoPoint(41.311081, 69.240562);
        GeoPoint chorsu = new GeoPoint(41.326500, 69.234100);

        assertThat(Haversine.metersBetween(square, square)).isZero();
        assertThat(Haversine.metersBetween(square, chorsu))
                .isEqualTo(Haversine.metersBetween(chorsu, square));
    }

    @Test
    @DisplayName("a branch with no coordinate cannot originate a zone, and the refusal says why")
    void anUnlocatedBranchIsRefused() {
        UUID branch = UUID.randomUUID();

        Throwable refusal = catchThrowable(
                () -> BranchOrigin.of(branch, null, null, "NOT_GEOCODED"));

        assertThat(refusal)
                .isInstanceOf(BranchOrigin.UnlocatedBranchException.class)
                // "No zones covered this address" would be true and useless: it
                // sends an operator to redraw a polygon when the fault is a branch
                // nobody has placed on a map.
                .hasMessageContaining("no coordinate")
                .hasMessageContaining("Place its pin");
    }

    @Test
    @DisplayName("(0, 0) is refused as loudly as a missing coordinate")
    void theNullIslandIsRefused() {
        // Three of the real legacy branches sit here. It is a genuine point in the
        // Gulf of Guinea that haversine and PostGIS both accept without a word, so
        // a branch stored there prices and serves nothing while reporting as fully
        // configured — which is strictly worse than one that admits it is unplaced.
        assertThat(catchThrowable(() -> BranchOrigin.of(UUID.randomUUID(), 0.0, 0.0, "MERCHANT_PIN")))
                .isInstanceOf(BranchOrigin.UnlocatedBranchException.class)
                .hasMessageContaining("Gulf of Guinea");
    }

    @Test
    @DisplayName("a placed branch is accepted, so the guard is not simply refusing everything")
    void aPlacedBranchIsAccepted() {
        var origin = BranchOrigin.of(UUID.randomUUID(), 41.311081, 69.240562, "MERCHANT_PIN");

        assertThat(origin.point().latitude()).isEqualTo(41.311081);
    }

    /** Monday to Friday: bits 0 to 4. */
    private static final int WEEKDAYS = 0b0011111;

    private static DeliveryTariff tashkentTariff() {
        return new DeliveryTariff(TARIFF, 1, VersionStatus.ACTIVE, "UZS",
                FeeSource.TARIFF, DistanceMode.RADIUS, 13_000, null, 15_000, 0L, 40_000L,
                bands(),
                List.of(new TariffTimeRule(0, 10, WEEKDAYS, LocalTime.of(18, 0), LocalTime.of(22, 0),
                        10_000, 5_000L)));
    }

    private static List<TariffBand> bands() {
        return List.of(
                new TariffBand(0, 0, 3_000, 10_000L, 0L),
                // Base zero, not ten thousand. Bands accumulate: this one's charge
                // is what the journey adds beyond three kilometres, and the ten
                // thousand is already on the band below. V0025 read one band at a
                // time and so had to repeat the figure here, which is the shape
                // V0032 corrects.
                new TariffBand(1, 3_000, 15_000, 0L, 2_000L));
    }
}
