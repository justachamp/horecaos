package uz.horecaos.platform.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.workforce.WorkforceKit.Person;
import uz.horecaos.platform.workforce.application.StaffRosterService.CreateEntry;
import uz.horecaos.platform.workforce.application.StaffTodayService.Row;
import uz.horecaos.platform.workforce.application.StaffTodayService.TodayView;
import uz.horecaos.platform.workforce.domain.ReasonCode;
import uz.horecaos.platform.workforce.domain.TodayStatus;

/**
 * «Сегодня» (ADR 0156, Specification): the six derived statuses, the tenant's business day, the guard
 * that a future day never reads as missed, and the one signal that sees only people who take orders.
 *
 * <p>Every fixture carries explicit timestamps: two rows created in the same millisecond tie locally and
 * reorder in CI under load, and a clock that is only ever "now" proves a duration is an instant.
 */
class StaffTodayServiceTests {

    private static final String MANAGER = "today-manager";

    private static TestDatabase.Handle db;

    private WorkforceKit kit;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        kit = WorkforceKit.over(db, Instant.parse("2026-10-08T02:00:00Z")); // 07:00 local, before any shift
        kit.reset();
        kit.grants.allow(MANAGER, Capability.STAFF_SHIFT_MANAGE, Capability.STAFF_SHIFT_READ);
    }

    @Test
    @DisplayName(
            "a published start more than ten minutes ago with no shift is LATE; at nine minutes it is still SCHEDULED")
    void lateAfterTheConfiguredMinutes() {
        Person cook = kit.cook("cook");
        publish(cook, "2026-10-08T03:00:00Z", "2026-10-08T11:00:00Z");

        kit.clock.set(Instant.parse("2026-10-08T03:09:00Z"));
        assertThat(statusOf(cook)).isEqualTo(TodayStatus.SCHEDULED);

        kit.clock.set(Instant.parse("2026-10-08T03:11:00Z"));
        assertThat(statusOf(cook)).isEqualTo(TodayStatus.LATE);

        kit.settings.set("workforce.roster.late_after_minutes", 30);
        assertThat(statusOf(cook)).as("the tenant's own number").isEqualTo(TodayStatus.SCHEDULED);
    }

    @Test
    @DisplayName("once a published entry's end has passed with no shift it is MISSED_TODAY, as a coverage fact")
    void missedAfterTheEnd() {
        Person cook = kit.cook("cook");
        publish(cook, "2026-10-08T03:00:00Z", "2026-10-08T07:00:00Z");

        kit.clock.set(Instant.parse("2026-10-08T07:30:00Z"));

        assertThat(statusOf(cook)).isEqualTo(TodayStatus.MISSED_TODAY);
    }

    @Test
    @DisplayName("an entry on a later day never renders as missed or late today, and yesterday's is not today's list")
    void aFutureDayIsNeverMissed() {
        Person cook = kit.cook("cook");
        Person waiter = kit.cook("waiter");
        publish(cook, "2026-10-09T03:00:00Z", "2026-10-09T11:00:00Z");
        publish(waiter, "2026-10-07T03:00:00Z", "2026-10-07T11:00:00Z");
        kit.clock.set(Instant.parse("2026-10-08T08:00:00Z"));

        TodayView view = kit.today.today(WorkforceKit.branch(), false);

        assertThat(view.rows())
                .as("neither tomorrow's nor yesterday's entry is on today's list")
                .isEmpty();
    }

    @Test
    @DisplayName("today is the tenant's business day: at 01:00 local on the 9th, the 8th's rota is gone")
    void todayIsTheBusinessDay() {
        Person cook = kit.cook("cook");
        publish(cook, "2026-10-08T03:00:00Z", "2026-10-08T11:00:00Z");
        kit.clock.set(Instant.parse("2026-10-08T20:00:00Z")); // 01:00 on the 9th in Tashkent

        TodayView view = kit.today.today(WorkforceKit.branch(), false);

        assertThat(view.businessDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(view.rows()).isEmpty();
    }

    @Test
    @DisplayName(
            "an order-taker with no open shift is WORKING_WITHOUT_SHIFT; a cook who only cooks is never on the list for it")
    void onlyOrderAttributionRaisesWorkingWithoutAShift() {
        Person orderTaker = kit.cook("operator");
        Person onlyCooks = kit.cook("cook");
        kit.orders.operators.add(orderTaker.subject());
        kit.clock.set(Instant.parse("2026-10-08T06:00:00Z"));

        TodayView view = kit.today.today(WorkforceKit.branch(), false);

        assertThat(view.rows())
                .extracting(
                        row -> java.util.Objects.requireNonNull(row.person()).displayReference())
                .containsExactly(orderTaker.reference());
        assertThat(view.rows().getFirst().status()).isEqualTo(TodayStatus.WORKING_WITHOUT_SHIFT);
        assertThat(view.rows())
                .extracting(
                        row -> java.util.Objects.requireNonNull(row.person()).displayReference())
                .as("nothing names a human for kitchen work, so the cook is invisible to this signal")
                .doesNotContain(onlyCooks.reference());
    }

    @Test
    @DisplayName(
            "opening a shift for the order-taker afterwards clears the notice; the signal refused nothing at the time")
    void aManagerFixesItAfterwards() {
        Person orderTaker = kit.cook("operator");
        kit.orders.operators.add(orderTaker.subject());
        kit.clock.set(Instant.parse("2026-10-08T06:00:00Z"));
        assertThat(statusOf(orderTaker)).isEqualTo(TodayStatus.WORKING_WITHOUT_SHIFT);

        kit.shifts.openFor(
                WorkforceKit.branch(),
                orderTaker.memberId(),
                Instant.parse("2026-10-08T03:00:00Z"),
                ReasonCode.FORGOT_CLOCK_IN,
                MANAGER,
                "m");

        assertThat(statusOf(orderTaker)).isEqualTo(TodayStatus.WORKING);
    }

    @Test
    @DisplayName("an open shift is WORKING, and OPEN_TOO_LONG past the maximum open hours; a closed one is CLOSED")
    void workingTooLongAndClosed() {
        Person longOne = kit.cook("long");
        Person shortOne = kit.cook("short");
        // The branch is UTC+5: every instant here stays on the local day of 8 October.
        kit.clock.set(Instant.parse("2026-10-08T01:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, longOne.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T15:10:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, shortOne.subject(), WorkforceKit.LOCATION, "c2");

        assertThat(statusOf(longOne)).as("fourteen hours and ten minutes").isEqualTo(TodayStatus.OPEN_TOO_LONG);
        assertThat(statusOf(shortOne)).isEqualTo(TodayStatus.WORKING);

        kit.clock.advance(Duration.ofHours(3));
        kit.shifts.clockOut(WorkforceKit.TENANT, shortOne.subject(), "c3");
        assertThat(statusOf(shortOne)).isEqualTo(TodayStatus.CLOSED);
    }

    @Test
    @DisplayName("rows come in the spec's order of urgency, and a name appears only when asked for")
    void orderOfUrgencyAndNames() {
        Person late = kit.cook("Aziza Karimova");
        Person scheduled = kit.cook("scheduled");
        Person working = kit.cook("working");
        publish(late, "2026-10-08T03:00:00Z", "2026-10-08T11:00:00Z");
        publish(scheduled, "2026-10-08T08:00:00Z", "2026-10-08T12:00:00Z");
        kit.clock.set(Instant.parse("2026-10-08T03:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, working.subject(), WorkforceKit.LOCATION, "c");
        kit.clock.set(Instant.parse("2026-10-08T03:30:00Z"));

        TodayView anonymous = kit.today.today(WorkforceKit.branch(), false);
        TodayView named = kit.today.today(WorkforceKit.branch(), true);

        assertThat(anonymous.rows())
                .extracting(Row::status)
                .containsExactly(TodayStatus.LATE, TodayStatus.WORKING, TodayStatus.SCHEDULED);
        assertThat(java.util.Objects.requireNonNull(anonymous.rows().getFirst().person())
                        .name())
                .isNull();
        assertThat(java.util.Objects.requireNonNull(named.rows().getFirst().person())
                        .name())
                .isEqualTo("Aziza Karimova");
    }

    @Test
    @DisplayName("a shift left open from yesterday is still on today's screen: the screen exists to flag it")
    void yesterdaysOpenShiftIsStillFlagged() {
        Person cook = kit.cook("cook");
        kit.clock.set(Instant.parse("2026-10-07T12:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c");
        kit.clock.set(Instant.parse("2026-10-08T08:00:00Z"));

        Row row = kit.today.today(WorkforceKit.branch(), false).rows().getFirst();

        assertThat(row.status()).isEqualTo(TodayStatus.OPEN_TOO_LONG);
        assertThat(row.shiftId()).isNotNull();
    }

    // ==================================================================================== helpers

    private TodayStatus statusOf(Person person) {
        List<Row> rows = kit.today.today(WorkforceKit.branch(), false).rows();
        return rows.stream()
                .filter(row -> row.memberId().equals(person.memberId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(person.reference() + " is not on today's list: " + rows))
                .status();
    }

    private void publish(Person person, String start, String end) {
        kit.rota.create(
                WorkforceKit.branch(),
                new CreateEntry(person.memberId(), Instant.parse(start), Instant.parse(end), 0),
                MANAGER,
                "c");
        kit.rotaPublish(Instant.parse(start));
    }
}
