package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
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
}
