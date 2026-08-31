package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayAggregate;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.RefundFact;

/**
 * The arithmetic the close writes and the recut checks (ADR 0043).
 *
 * <p>Pure, so the properties that matter can be asserted without a database: the
 * shares of a distribution add up, a refund lands on its own date, and an absent
 * figure is absent rather than zero.
 */
class DayAggregatorTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-0000-7000-8000-00000000a001");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-0000-7000-8000-00000000a002");
    private static final UUID ENTITY_A = UUID.fromString("018f6f4e-0000-7000-8000-00000000a003");
    private static final UUID ENTITY_B = UUID.fromString("018f6f4e-0000-7000-8000-00000000a004");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);

    @Test
    void twoLegalEntitiesProduceTwoRowsAndNeverOne() {
        // ADR 0038: one tenant can trade as two companies on the same evening, so
        // the aggregate has to keep them apart. The moment they share a row, no
        // query downstream can separate them again.
        List<BranchDayAggregate> rows = DayAggregator.branchDay(
                DAY,
                List.of(completed("ord-1", ENTITY_A, 100_000, 0), completed("ord-2", ENTITY_B, 60_000, 0)),
                List.of(),
                1,
                1);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.key().legalEntityId()).containsExactlyInAnyOrder(ENTITY_A, ENTITY_B);
        assertThat(rows).extracting(BranchDayAggregate::grossSom).containsExactlyInAnyOrder(100_000L, 60_000L);
    }

    @Test
    void aRefundLandsOnItsOwnDayAndNotTheOrdersDay() {
        // The refund below reverses an order from three days earlier. It belongs
        // to today's net revenue and must leave that Tuesday's closed report
        // exactly as it was.
        RefundFact refund = new RefundFact(
                TENANT,
                DAY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                DAY.minusDays(3),
                LOCATION,
                ENTITY_A,
                "TELEGRAM",
                "DELIVERY",
                25_000,
                Instant.parse("2026-08-21T14:00:00Z"),
                1,
                1);

        List<BranchDayAggregate> rows =
                DayAggregator.branchDay(DAY, List.of(completed("ord-1", ENTITY_A, 100_000, 0)), List.of(refund), 1, 1);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().grossSom())
                .as("a refund never touches gross revenue")
                .isEqualTo(100_000L);
        assertThat(rows.getFirst().refundedSom()).isEqualTo(25_000L);
    }

    @Test
    void aRefundForABranchWithNoOrdersTodayStillGetsARow() {
        // Otherwise a day whose only movement was a refund reports as a day with
        // no movement at all, and the money silently leaves the books.
        RefundFact refund = new RefundFact(
                TENANT,
                DAY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                DAY.minusDays(9),
                LOCATION,
                ENTITY_A,
                "TELEGRAM",
                "DELIVERY",
                40_000,
                Instant.parse("2026-08-21T14:00:00Z"),
                1,
                1);

        List<BranchDayAggregate> rows = DayAggregator.branchDay(DAY, List.of(), List.of(refund), 1, 1);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().refundedSom()).isEqualTo(40_000L);
        assertThat(rows.getFirst().orderCount()).isZero();
    }

    @Test
    void aDayWithNothingClosedHasNoAverageRatherThanAZeroSecondOne() {
        OrderFact stillOpen = new OrderFact(
                TENANT,
                UUID.randomUUID(),
                DAY,
                1,
                Instant.parse("2026-08-21T14:00:00Z"),
                null,
                UUID.randomUUID(),
                LOCATION,
                ENTITY_A,
                "TELEGRAM",
                "DELIVERY",
                "PREPARING",
                null,
                null,
                null,
                50_000,
                0,
                0,
                0,
                50_000,
                1,
                1,
                30,
                null,
                null,
                null,
                null,
                null,
                1,
                1);

        List<BranchDayAggregate> rows = DayAggregator.branchDay(DAY, List.of(stillOpen), List.of(), 1, 1);

        assertThat(rows.getFirst().avgSecondsTotal())
                .as("zero would read as an order delivered instantly")
                .isNull();
    }

    @Test
    void theSlaSharesSumToTheWhole() {
        // Three orders do not divide evenly into ten thousand basis points.
        // Truncating each share leaves the columns of a distribution chart
        // visibly failing to add up, which costs more trust than the rounding
        // error is worth.
        List<OrderFact> orders =
                List.of(closedAfter("a", 20 * 60), closedAfter("b", 33 * 60), closedAfter("c", 70 * 60));

        var buckets = DayAggregator.slaBuckets(TENANT, DAY, orders);

        assertThat(buckets).hasSize(3);
        assertThat(buckets.stream()
                        .mapToInt(bucket -> bucket.shareBasisPoints())
                        .sum())
                .isEqualTo(10_000);
        assertThat(buckets)
                .extracting(bucket -> bucket.bucketCode())
                .containsExactlyInAnyOrder("UNDER_30", "M30_35", "OVER_60");
    }

    @Test
    void anOrderStillOpenIsNotBucketed() {
        // Counting it in the fastest bucket would be wrong twice: the order is
        // not fast, and it is not finished.
        OrderFact open = new OrderFact(
                TENANT,
                UUID.randomUUID(),
                DAY,
                1,
                Instant.parse("2026-08-21T14:00:00Z"),
                null,
                UUID.randomUUID(),
                LOCATION,
                ENTITY_A,
                "TELEGRAM",
                "DELIVERY",
                "PREPARING",
                null,
                null,
                null,
                50_000,
                0,
                0,
                0,
                50_000,
                1,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                1);

        assertThat(DayAggregator.slaBuckets(TENANT, DAY, List.of(open))).isEmpty();
    }

    @Test
    void latenessCountsOnlyOrdersThatCarriedAPromise() {
        Instant placed = Instant.parse("2026-08-21T14:00:00Z");
        OrderFact promisedAndLate = new OrderFact(
                TENANT,
                UUID.randomUUID(),
                DAY,
                1,
                placed,
                placed.plusSeconds(3600),
                UUID.randomUUID(),
                LOCATION,
                ENTITY_A,
                "TELEGRAM",
                "DELIVERY",
                "COMPLETED",
                null,
                null,
                null,
                50_000,
                0,
                0,
                0,
                50_000,
                1,
                1,
                60,
                1800,
                3600,
                placed.plusSeconds(2400),
                null,
                1200,
                1,
                1);
        OrderFact neverPromised = new OrderFact(
                TENANT,
                UUID.randomUUID(),
                DAY,
                1,
                placed,
                placed.plusSeconds(7200),
                UUID.randomUUID(),
                LOCATION,
                ENTITY_A,
                "TELEGRAM",
                "DELIVERY",
                "COMPLETED",
                null,
                null,
                null,
                50_000,
                0,
                0,
                0,
                50_000,
                1,
                1,
                60,
                1800,
                7200,
                null,
                null,
                null,
                1,
                1);

        var rows = DayAggregator.branchDay(DAY, List.of(promisedAndLate, neverPromised), List.of(), 1, 1);

        assertThat(rows.getFirst().promisedCount())
                .as("an unpromised order cannot be late, and counting it as on time is a " + "different lie")
                .isEqualTo(1);
        assertThat(rows.getFirst().lateCount()).isEqualTo(1);
    }

    private static OrderFact completed(String seed, UUID entityId, long gross, long discount) {
        Instant placed = Instant.parse("2026-08-21T14:00:00Z");
        return new OrderFact(
                TENANT,
                UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)),
                DAY,
                1,
                placed,
                placed.plusSeconds(2400),
                UUID.randomUUID(),
                LOCATION,
                entityId,
                "TELEGRAM",
                "DELIVERY",
                "COMPLETED",
                null,
                null,
                null,
                gross,
                discount,
                0,
                0,
                gross - discount,
                2,
                3,
                60,
                1500,
                2400,
                null,
                null,
                null,
                1,
                1);
    }

    private static OrderFact closedAfter(String seed, int seconds) {
        Instant placed = Instant.parse("2026-08-21T14:00:00Z");
        return new OrderFact(
                TENANT,
                UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)),
                DAY,
                1,
                placed,
                placed.plusSeconds(seconds),
                UUID.randomUUID(),
                LOCATION,
                ENTITY_A,
                "TELEGRAM",
                "DELIVERY",
                "COMPLETED",
                null,
                null,
                null,
                50_000,
                0,
                0,
                0,
                50_000,
                1,
                1,
                60,
                1200,
                seconds,
                null,
                null,
                null,
                1,
                1);
    }
}
