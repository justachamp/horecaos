package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.reporting.application.ReportingFacts.CallHourFact;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceCallEvent;

class DayAggregatorCallHourFactsTests {

    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);

    @Test
    void bucketsByHourInTheLocationsTimezoneNotUtc() {
        // 2026-09-04T22:30:00Z is 2026-09-05T03:30 in Tashkent (UTC+5). If
        // this aggregator bucketed on the UTC hour instead of the boundary's
        // own zone, this event would land in hour 22 of the *previous* UTC
        // date, not hour 3 of DAY — a test using an already-round UTC hour
        // would not catch that mistake.
        Instant offeredAt = Instant.parse("2026-09-04T22:30:00Z");

        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT,
                DAY,
                TASHKENT,
                List.of(new SourceCallEvent(BRAND, LOCATION, "OFFERED", null, null, offeredAt)),
                1,
                1);

        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().hourOfDay()).isEqualTo(3);
        assertThat(facts.getFirst().offeredCount()).isEqualTo(1);
    }

    @Test
    void anUnattributedOperatorGoesInTheUnassignedBucketNotItsOwnNullRow() {
        Instant at = LocalDate.of(2026, 9, 5).atTime(10, 0).atZone(TASHKENT).toInstant();

        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT, DAY, TASHKENT, List.of(new SourceCallEvent(BRAND, LOCATION, "MISSED", null, null, at)), 1, 1);

        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().operatorPrincipalId()).isEqualTo("(unassigned)");
        assertThat(facts.getFirst().missedCount()).isEqualTo(1);
    }

    @Test
    void talkDurationSumsOnlyEndedEventsWithADuration() {
        Instant at = LocalDate.of(2026, 9, 5).atTime(10, 0).atZone(TASHKENT).toInstant();
        String operator = "alice";

        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT,
                DAY,
                TASHKENT,
                List.of(
                        new SourceCallEvent(BRAND, LOCATION, "ENDED", operator, 120, at),
                        new SourceCallEvent(BRAND, LOCATION, "ENDED", operator, 60, at),
                        // An ENDED row with no computed duration (the OFFERED/
                        // ANSWERED row it correlates against was never found)
                        // must not silently count as a zero-second call in a
                        // sum a manager reads as "average handle time".
                        new SourceCallEvent(BRAND, LOCATION, "ENDED", operator, null, at)),
                1,
                1);

        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().talkDurationSeconds()).isEqualTo(180);
    }

    @Test
    void differentOperatorsInTheSameHourGetSeparateRows() {
        Instant at = LocalDate.of(2026, 9, 5).atTime(10, 0).atZone(TASHKENT).toInstant();

        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT,
                DAY,
                TASHKENT,
                List.of(
                        new SourceCallEvent(BRAND, LOCATION, "ANSWERED", "alice", null, at),
                        new SourceCallEvent(BRAND, LOCATION, "ANSWERED", "bob", null, at)),
                1,
                1);

        assertThat(facts).hasSize(2);
        assertThat(facts.stream().map(CallHourFact::operatorPrincipalId)).containsExactlyInAnyOrder("alice", "bob");
        assertThat(facts).allSatisfy(fact -> assertThat(fact.answeredCount()).isEqualTo(1));
    }

    @Test
    void producesNothingForAnEmptyDay() {
        List<CallHourFact> facts = DayAggregator.callHourFacts(TENANT, DAY, TASHKENT, List.of(), 1, 1);

        assertThat(facts).isEmpty();
    }

    // ------------------------------------------------------------ ADR 0175

    private static final Instant TEN =
            LocalDate.of(2026, 9, 5).atTime(10, 0).atZone(TASHKENT).toInstant();

    /** An {@code ANSWERED} row as the close reads it, with its call-level derivations. */
    private static SourceCallEvent answer(
            Instant at,
            boolean first,
            @Nullable Instant offeredAt,
            @Nullable String ownOperator,
            @Nullable String resolvedOperator,
            boolean hasOrder) {
        return new SourceCallEvent(
                BRAND, LOCATION, "ANSWERED", ownOperator, null, at, first, offeredAt, resolvedOperator, hasOrder);
    }

    @Test
    void aFirstAnswerIsOneAnsweredCallOneRingSampleAndTheIntervalInWholeSeconds() {
        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT, DAY, TASHKENT, List.of(answer(TEN.plusSeconds(7), true, TEN, "alice", "alice", false)), 1, 1);

        assertThat(facts).hasSize(1);
        CallHourFact fact = facts.getFirst();
        assertThat(fact.answeredCallCount()).isEqualTo(1);
        assertThat(fact.ringSampleCount()).isEqualTo(1);
        assertThat(fact.ringSecondsTotal()).isEqualTo(7L);
        assertThat(fact.ringSecondsMax()).isEqualTo(7);
        assertThat(fact.convertedCount()).isZero();
    }

    @Test
    void aSecondAnswerOfTheSameCallAddsNoRingSampleAndNoConversionButStillCountsInAnsweredCount() {
        // A transfer: the call is answered twice. The existing answered_count counts both answers;
        // the new columns count the call once, on its first answer.
        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT,
                DAY,
                TASHKENT,
                List.of(
                        answer(TEN.plusSeconds(5), true, TEN, "alice", "alice", true),
                        answer(TEN.plusSeconds(60), false, TEN, "bob", "bob", true)),
                1,
                1);

        CallHourFact alice = fact(facts, "alice");
        CallHourFact bob = fact(facts, "bob");
        assertThat(alice.answeredCount()).isEqualTo(1);
        assertThat(alice.answeredCallCount()).isEqualTo(1);
        assertThat(alice.convertedCount()).isEqualTo(1);
        assertThat(bob.answeredCount()).isEqualTo(1);
        assertThat(bob.answeredCallCount())
                .as("bob only took the transferred leg")
                .isZero();
        assertThat(bob.ringSampleCount()).isZero();
        assertThat(bob.convertedCount()).isZero();
        assertThat(bob.ringSecondsMax()).as("no sample, no maximum").isNull();
    }

    @Test
    void anOfferedFromThePreviousBusinessDayStillPairsAndAnOverCeilingIntervalIsNotASample() {
        // Offered 23:59:50 the night before, answered 00:00:10: twenty seconds, across midnight.
        Instant offeredLastNight = DAY.atStartOfDay(TASHKENT).toInstant().minusSeconds(10);
        Instant answeredToday = DAY.atStartOfDay(TASHKENT).toInstant().plusSeconds(10);
        // A reused provider id: "offered" two hours before the answer is two different calls.
        Instant staleOffer = TEN.minusSeconds(7200);

        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT,
                DAY,
                TASHKENT,
                List.of(
                        answer(answeredToday, true, offeredLastNight, "alice", "alice", false),
                        answer(TEN, true, staleOffer, "bob", "bob", false)),
                1,
                1,
                1800);

        CallHourFact alice = fact(facts, "alice");
        assertThat(alice.ringSampleCount()).isEqualTo(1);
        assertThat(alice.ringSecondsTotal()).isEqualTo(20L);

        CallHourFact bob = fact(facts, "bob");
        assertThat(bob.answeredCallCount()).as("still an answered call").isEqualTo(1);
        assertThat(bob.ringSampleCount()).as("but not a ring sample").isZero();
        assertThat(bob.ringSecondsTotal()).isZero();
    }

    @Test
    void theCeilingItselfIsStillBelievedAndOneSecondOverIsNot() {
        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT,
                DAY,
                TASHKENT,
                List.of(
                        answer(TEN.plusSeconds(30), true, TEN, "at-ceiling", "at-ceiling", false),
                        answer(TEN.plusSeconds(31), true, TEN, "over-ceiling", "over-ceiling", false)),
                1,
                1,
                30);

        assertThat(fact(facts, "at-ceiling").ringSampleCount()).isEqualTo(1);
        assertThat(fact(facts, "over-ceiling").ringSampleCount()).isZero();
    }

    @Test
    void aNegativeIntervalAndACallWithNoOfferedRowAreAnsweredCallsButNotRingSamples() {
        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT,
                DAY,
                TASHKENT,
                List.of(
                        // The "offer" is after the answer: a clock fault or two joined calls.
                        answer(TEN, true, TEN.plusSeconds(3), "negative", "negative", false),
                        answer(TEN, true, null, "lost-offer", "lost-offer", false)),
                1,
                1);

        for (String operator : List.of("negative", "lost-offer")) {
            CallHourFact fact = fact(facts, operator);
            assertThat(fact.answeredCallCount()).isEqualTo(1);
            assertThat(fact.ringSampleCount()).isZero();
            assertThat(fact.ringSecondsTotal()).isZero();
            assertThat(fact.ringSecondsMax()).isNull();
        }
    }

    @Test
    void theResolvedOperatorKeysTheNewValuesWhileTheEventsOwnOperatorKeysTheOldCounts() {
        // The answer carried no operator; the call's ENDED event named alice (a screen-pop claim lands
        // between answer and end). The answered_count stays on the event's own key, "(unassigned)";
        // the call-level values move to alice.
        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT, DAY, TASHKENT, List.of(answer(TEN.plusSeconds(4), true, TEN, null, "alice", true)), 1, 1);

        CallHourFact unassigned = fact(facts, "(unassigned)");
        assertThat(unassigned.answeredCount()).isEqualTo(1);
        assertThat(unassigned.answeredCallCount()).isZero();
        CallHourFact alice = fact(facts, "alice");
        assertThat(alice.answeredCount())
                .as("a key that exists only for the new values holds zero event counts")
                .isZero();
        assertThat(alice.answeredCallCount()).isEqualTo(1);
        assertThat(alice.ringSecondsTotal()).isEqualTo(4L);
        assertThat(alice.convertedCount()).isEqualTo(1);
    }

    @Test
    void aCallNobodyNamedFallsBackToTheUnassignedSentinel() {
        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT, DAY, TASHKENT, List.of(answer(TEN.plusSeconds(2), true, TEN, null, null, false)), 1, 1);

        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().operatorPrincipalId()).isEqualTo("(unassigned)");
        assertThat(facts.getFirst().answeredCallCount()).isEqualTo(1);
    }

    @Test
    void theNewValuesLandInTheHourOfTheAnswerNotTheHourOfTheOffer() {
        Instant offered =
                LocalDate.of(2026, 9, 5).atTime(9, 59, 55).atZone(TASHKENT).toInstant();
        Instant answered =
                LocalDate.of(2026, 9, 5).atTime(10, 0, 5).atZone(TASHKENT).toInstant();

        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT,
                DAY,
                TASHKENT,
                List.of(
                        new SourceCallEvent(BRAND, LOCATION, "OFFERED", null, null, offered),
                        answer(answered, true, offered, "alice", "alice", false)),
                1,
                1);

        assertThat(facts).hasSize(2);
        CallHourFact offeredHour =
                facts.stream().filter(f -> f.hourOfDay() == 9).findFirst().orElseThrow();
        CallHourFact answeredHour =
                facts.stream().filter(f -> f.hourOfDay() == 10).findFirst().orElseThrow();
        assertThat(offeredHour.offeredCount()).isEqualTo(1);
        assertThat(offeredHour.answeredCallCount()).isZero();
        assertThat(answeredHour.answeredCallCount()).isEqualTo(1);
        assertThat(answeredHour.ringSecondsTotal()).isEqualTo(10L);
    }

    @Test
    void everyRowANewCloseWritesCarriesMeasuredValuesAndZerosAreNotNulls() {
        List<CallHourFact> facts = DayAggregator.callHourFacts(
                TENANT, DAY, TASHKENT, List.of(new SourceCallEvent(BRAND, LOCATION, "MISSED", null, null, TEN)), 1, 1);

        CallHourFact fact = facts.getFirst();
        assertThat(fact.answeredCallCount()).as("measured, and zero").isZero();
        assertThat(fact.ringSecondsTotal()).isZero();
        assertThat(fact.ringSampleCount()).isZero();
        assertThat(fact.convertedCount()).isZero();
        assertThat(fact.ringSecondsMax()).isNull();
    }

    @Test
    void aPreMigrationRowIsNotMeasuredAndTheRecordRefusesAHalfMeasuredShape() {
        CallHourFact legacy = new CallHourFact(TENANT, BRAND, LOCATION, DAY, 10, "alice", 1, 1, 1, 1, 0, 0, 90);
        assertThat(legacy.answeredCallCount()).isNull();
        assertThat(legacy.convertedCount()).isNull();

        assertThatThrownBy(() -> new CallHourFact(
                        TENANT, BRAND, LOCATION, DAY, 10, "alice", 1, 1, 1, 1, 0, 0, 90, 1, null, 1, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallHourFact(
                        TENANT, BRAND, LOCATION, DAY, 10, "alice", 1, 1, 1, 1, 0, 0, 90, 1, 5L, 1, null, 0))
                .as("a sample without a maximum")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CallHourFact fact(List<CallHourFact> facts, String operator) {
        return facts.stream()
                .filter(f -> operator.equals(f.operatorPrincipalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + operator + " in " + facts));
    }
}
