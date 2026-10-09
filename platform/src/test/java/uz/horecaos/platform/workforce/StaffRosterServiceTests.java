package uz.horecaos.platform.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.workforce.WorkforceKit.Person;
import uz.horecaos.platform.workforce.application.RosterMissedSweeper;
import uz.horecaos.platform.workforce.application.StaffRosterService.CreateEntry;
import uz.horecaos.platform.workforce.application.StaffRosterService.EntryView;
import uz.horecaos.platform.workforce.domain.ReasonCode;
import uz.horecaos.platform.workforce.domain.RosterStatus;

/**
 * ADR 0156 Decision 3: the rota. A rota and not an offer -- there is no accepted or declined -- and a
 * plan and never a source of paid time. Every test runs the real services over the real constraints.
 */
class StaffRosterServiceTests {

    private static final Instant START = Instant.parse("2026-10-05T03:00:00Z"); // 08:00 Monday, Tashkent
    private static final String MANAGER = "roster-manager";

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
        kit = WorkforceKit.over(db, START);
        kit.reset();
        kit.grants.allow(MANAGER, Capability.STAFF_SHIFT_MANAGE, Capability.STAFF_SHIFT_READ);
    }

    @Test
    @DisplayName(
            "a manager drafts an entry for an active person with a job here; the fact names the person by display reference only")
    void aDraftIsAnAuditedPlan() {
        Person cook = kit.cook("Aziza Karimova");

        EntryView draft = draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 30);

        assertThat(draft.status()).isEqualTo(RosterStatus.DRAFT);
        assertThat(draft.plannedBreakMinutes()).isEqualTo(30);
        var facts = kit.auditFacts("workforce.roster.drafted");
        assertThat(facts).hasSize(1);
        assertThat(facts.toString()).contains(cook.reference()).doesNotContain("Aziza", "Karimova");
    }

    @Test
    @DisplayName(
            "a person of another tenant, one whose employment ended and one with no job at this branch are all 'no such member'")
    void onlyAnEligiblePersonCanBeRostered() {
        Person ended = kit.person(WorkforceKit.TENANT, "ended", WorkforceKit.LOCATION, "ENDED");
        Person elsewhere = kit.person(WorkforceKit.TENANT, "elsewhere", WorkforceKit.SECOND_LOCATION, "ACTIVE");
        Person foreign = kit.person(WorkforceKit.OTHER_TENANT, "foreign", WorkforceKit.OTHER_LOCATION, "ACTIVE");

        List<ErrorCode> codes = List.of(
                refusal(() -> draft(ended, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 0)),
                refusal(() -> draft(elsewhere, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 0)),
                refusal(() -> draft(foreign, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 0)),
                refusal(() -> kit.rota.create(
                        WorkforceKit.branch(),
                        new CreateEntry(
                                UUID.randomUUID(),
                                Instant.parse("2026-10-06T03:00:00Z"),
                                Instant.parse("2026-10-06T11:00:00Z"),
                                0),
                        MANAGER,
                        "c")));

        assertThat(codes).containsOnly(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(count("SELECT count(*) FROM workforce.staff_roster_entries")).isZero();
    }

    @Test
    @DisplayName(
            "a person cannot hold two live entries that overlap, at any branch; adjacent entries and a cancelled one are fine")
    void noOverlappingLiveEntries() {
        Person cook = kit.cook("cook");
        kit.grants.job(cook.subject(), WorkforceKit.SECOND_LOCATION);
        draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T07:00:00Z", 0);

        assertThatThrownBy(() -> kit.rota.create(
                        WorkforceKit.secondBranch(),
                        new CreateEntry(
                                cook.memberId(),
                                Instant.parse("2026-10-06T06:00:00Z"),
                                Instant.parse("2026-10-06T10:00:00Z"),
                                0),
                        MANAGER,
                        "c"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(refused.properties()).containsEntry("reason", "ROSTER_OVERLAP");
                });

        EntryView adjacent = draft(cook, "2026-10-06T07:00:00Z", "2026-10-06T11:00:00Z", 0);
        kit.rota.cancel(
                WorkforceKit.branch(), adjacent.id(), adjacent.version(), ReasonCode.SCHEDULE_CHANGED, MANAGER, "c");
        assertThat(draft(cook, "2026-10-06T08:00:00Z", "2026-10-06T10:00:00Z", 0)
                        .status())
                .isEqualTo(RosterStatus.DRAFT);
    }

    @Test
    @DisplayName("a window must end after it starts, last at most sixteen hours, and hold its break")
    void theWindowRules() {
        Person cook = kit.cook("cook");

        assertThat(refusal(() -> draft(cook, "2026-10-06T11:00:00Z", "2026-10-06T03:00:00Z", 0)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T19:00:01Z", 0)))
                .as("sixteen hours and a second: a mistyped date, not a working day")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T04:00:00Z", 90)))
                .as("a break longer than the window")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T19:00:00Z", 0)
                        .status())
                .as("exactly sixteen hours is allowed")
                .isEqualTo(RosterStatus.DRAFT);
    }

    @Test
    @DisplayName("an update is a conditional write on the entry's version, and the person cannot change")
    void anUpdateIsConditional() {
        Person cook = kit.cook("cook");
        Person other = kit.cook("other");
        EntryView draft = draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 0);

        EntryView moved = kit.rota.update(
                WorkforceKit.branch(),
                draft.id(),
                draft.version(),
                new CreateEntry(
                        cook.memberId(),
                        Instant.parse("2026-10-06T04:00:00Z"),
                        Instant.parse("2026-10-06T12:00:00Z"),
                        30),
                MANAGER,
                "u1");

        assertThat(moved.plannedStart()).isEqualTo(Instant.parse("2026-10-06T04:00:00Z"));
        assertThat(moved.version()).isGreaterThan(draft.version());
        assertThatThrownBy(() -> kit.rota.update(
                        WorkforceKit.branch(),
                        draft.id(),
                        draft.version(),
                        new CreateEntry(
                                cook.memberId(),
                                Instant.parse("2026-10-06T05:00:00Z"),
                                Instant.parse("2026-10-06T13:00:00Z"),
                                0),
                        MANAGER,
                        "u2"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        stale -> assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(refusal(() -> kit.rota.update(
                        WorkforceKit.branch(),
                        draft.id(),
                        moved.version(),
                        new CreateEntry(
                                other.memberId(),
                                Instant.parse("2026-10-06T04:00:00Z"),
                                Instant.parse("2026-10-06T12:00:00Z"),
                                0),
                        MANAGER,
                        "u3")))
                .as("an entry's person is not editable: cancel and draft another")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(kit.rotaWeek(LocalDate.of(2026, 10, 6)).getFirst().plannedStart())
                .as("the stale write overwrote nothing")
                .isEqualTo(Instant.parse("2026-10-06T04:00:00Z"));
    }

    @Test
    @DisplayName("publishing a range publishes only the drafts that start in it, and names the people by reference")
    void publishingIsByRange() {
        Person cook = kit.cook("Aziza Karimova");
        Person waiter = kit.cook("Bobur Aliyev");
        draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 0);
        draft(waiter, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 0);
        EntryView later = draft(cook, "2026-10-08T03:00:00Z", "2026-10-08T11:00:00Z", 0);

        var result = kit.rota.publish(
                WorkforceKit.branch(), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7), MANAGER, "p1");

        assertThat(result.published()).isEqualTo(2);
        assertThat(result.people()).containsExactly(cook.reference(), waiter.reference());
        assertThat(count("SELECT count(*) FROM workforce.staff_roster_entries WHERE status = 'PUBLISHED'"))
                .isEqualTo(2);
        assertThat(kit.rosterStore
                        .findById(WorkforceKit.TENANT, later.id())
                        .orElseThrow()
                        .status())
                .as("outside the range: still a draft")
                .isEqualTo(RosterStatus.DRAFT);
        assertThat(kit.auditFacts("workforce.roster.published").toString())
                .contains(cook.reference())
                .doesNotContain("Aziza", "Bobur");
        assertThat(kit.rota
                        .publish(
                                WorkforceKit.branch(),
                                LocalDate.of(2026, 10, 6),
                                LocalDate.of(2026, 10, 7),
                                MANAGER,
                                "p2")
                        .published())
                .as("publishing again finds nothing to publish")
                .isZero();
    }

    @Test
    @DisplayName("a published entry can be cancelled with a reason; an entry that was worked cannot")
    void cancellation() {
        Person cook = kit.cook("cook");
        kit.clock.set(Instant.parse("2026-10-06T03:05:00Z"));
        EntryView entry = draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 0);
        kit.rotaPublish(Instant.parse("2026-10-06T03:00:00Z"));
        EntryView published = kit.rotaWeek(LocalDate.of(2026, 10, 6)).getFirst();
        assertThat(published.status()).isEqualTo(RosterStatus.PUBLISHED);

        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c");

        assertThat(refusal(() -> kit.rota.cancel(
                        WorkforceKit.branch(),
                        entry.id(),
                        published.version() + 1,
                        ReasonCode.PERSON_UNAVAILABLE,
                        MANAGER,
                        "x")))
                .as("consumed by the clock-in: version moved on and the state forbids it")
                .isIn(ErrorCode.UNPROCESSABLE_STATE, ErrorCode.STALE_VERSION);

        Person waiter = kit.cook("waiter");
        EntryView other = draft(waiter, "2026-10-07T03:00:00Z", "2026-10-07T11:00:00Z", 0);
        kit.rota.cancel(
                WorkforceKit.branch(), other.id(), other.version(), ReasonCode.PERSON_UNAVAILABLE, MANAGER, "c2");
        assertThat(kit.rosterStore
                        .findById(WorkforceKit.TENANT, other.id())
                        .orElseThrow()
                        .status())
                .isEqualTo(RosterStatus.CANCELLED);
        assertThat(
                        count(
                                "SELECT count(*) FROM workforce.staff_roster_entries WHERE cancel_reason_code = 'PERSON_UNAVAILABLE'"))
                .isEqualTo(1);
        assertThat(kit.auditFacts("workforce.roster.cancelled")).hasSize(1);
    }

    @Test
    @DisplayName(
            "copying a week creates drafts shifted by whole days, skipping a person no longer active and an overlap, and counts both")
    void copyingAWeek() {
        Person cook = kit.cook("cook");
        Person leaver = kit.cook("leaver");
        draft(cook, "2026-10-05T03:00:00Z", "2026-10-05T11:00:00Z", 30);
        draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 30);
        draft(leaver, "2026-10-05T03:00:00Z", "2026-10-05T11:00:00Z", 0);
        // The target week already holds one of cook's days.
        draft(cook, "2026-10-13T03:00:00Z", "2026-10-13T11:00:00Z", 0);
        kit.jdbc
                .sql(
                        "UPDATE iam.staff_members SET employment_status = 'ENDED', employed_until = current_date WHERE id = :id")
                .param("id", leaver.memberId())
                .update();
        kit.roster.people.put(
                leaver.memberId(),
                new Person(
                        leaver.tenantId(),
                        leaver.memberId(),
                        leaver.subject(),
                        leaver.reference(),
                        leaver.name(),
                        "ENDED"));

        var result = kit.rota.copyWeek(
                WorkforceKit.branch(), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12), MANAGER, "copy");

        assertThat(result.created()).as("cook's Monday, a week on").isEqualTo(1);
        assertThat(result.skipped())
                .as("the leaver, and cook's Tuesday which the target already holds")
                .isEqualTo(2);
        List<EntryView> target = kit.rotaWeek(LocalDate.of(2026, 10, 14));
        assertThat(target).hasSize(2);
        assertThat(target)
                .filteredOn(entry -> entry.plannedStart().equals(Instant.parse("2026-10-12T03:00:00Z")))
                .singleElement()
                .satisfies(copy -> {
                    assertThat(copy.status()).isEqualTo(RosterStatus.DRAFT);
                    assertThat(copy.plannedBreakMinutes()).isEqualTo(30);
                });
    }

    @Test
    @DisplayName("the week view hides cancelled entries, carries the branch's zone, and shows a name only when asked")
    void theWeekView() {
        Person cook = kit.cook("Aziza Karimova");
        EntryView kept = draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T11:00:00Z", 0);
        EntryView dropped = draft(cook, "2026-10-07T03:00:00Z", "2026-10-07T11:00:00Z", 0);
        kit.rota.cancel(
                WorkforceKit.branch(), dropped.id(), dropped.version(), ReasonCode.SCHEDULE_CHANGED, MANAGER, "c");

        var anonymous =
                kit.rota.week(WorkforceKit.branch(), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), false);
        var named = kit.rota.week(WorkforceKit.branch(), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), true);

        assertThat(anonymous.timezone()).isEqualTo("Asia/Tashkent");
        assertThat(anonymous.entries()).extracting(EntryView::id).containsExactly(kept.id());
        assertThat(java.util.Objects.requireNonNull(
                                anonymous.entries().getFirst().person())
                        .name())
                .isNull();
        assertThat(java.util.Objects.requireNonNull(
                                anonymous.entries().getFirst().person())
                        .displayReference())
                .isEqualTo(cook.reference());
        assertThat(java.util.Objects.requireNonNull(named.entries().getFirst().person())
                        .name())
                .isEqualTo("Aziza Karimova");
    }

    @Test
    @DisplayName("a branch of another tenant, or an unknown one, cannot be read through this tenant")
    void aForeignBranchIsAbsent() {
        assertThat(refusal(() -> kit.rota.week(
                        new uz.horecaos.platform.workforce.application.Branch(
                                WorkforceKit.TENANT, WorkforceKit.OTHER_BRAND, WorkforceKit.OTHER_LOCATION),
                        LocalDate.of(2026, 10, 5),
                        LocalDate.of(2026, 10, 11),
                        false)))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName(
            "the sweeper marks a published entry missed after its end with no shift, and leaves consumed, future and draft entries alone")
    void theSweeperMarksOnlyWhatEndedUncovered() {
        Person cook = kit.cook("cook");
        Person waiter = kit.cook("waiter");
        Person dishwasher = kit.cook("dishwasher");
        kit.clock.set(Instant.parse("2026-10-06T03:05:00Z"));
        EntryView worked = draft(cook, "2026-10-06T03:00:00Z", "2026-10-06T07:00:00Z", 0);
        EntryView missed = draft(waiter, "2026-10-06T03:00:00Z", "2026-10-06T07:00:00Z", 0);
        EntryView future = draft(dishwasher, "2026-10-06T08:00:00Z", "2026-10-06T11:00:00Z", 0);
        kit.rotaPublish(Instant.parse("2026-10-06T03:00:00Z"));
        EntryView stillDraft = draft(dishwasher, "2026-10-07T03:00:00Z", "2026-10-07T07:00:00Z", 0);
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c");
        kit.clock.set(Instant.parse("2026-10-06T08:00:00Z"));
        RosterMissedSweeper sweeper = new RosterMissedSweeper(
                kit.rosterStore,
                kit.shiftStore,
                kit.clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                100);

        int marked = sweeper.runOnce();

        assertThat(marked).isEqualTo(1);
        assertThat(status(missed)).isEqualTo(RosterStatus.MISSED);
        assertThat(status(worked)).as("has a shift against it").isEqualTo(RosterStatus.CONSUMED);
        assertThat(status(future)).isEqualTo(RosterStatus.PUBLISHED);
        assertThat(status(stillDraft)).isEqualTo(RosterStatus.DRAFT);
        assertThat(sweeper.runOnce()).as("idempotent").isZero();
    }

    // ==================================================================================== helpers

    private EntryView draft(Person person, String start, String end, int breakMinutes) {
        return kit.rota.create(
                WorkforceKit.branch(),
                new CreateEntry(person.memberId(), Instant.parse(start), Instant.parse(end), breakMinutes),
                MANAGER,
                "c");
    }

    private RosterStatus status(EntryView entry) {
        return kit.rosterStore
                .findById(WorkforceKit.TENANT, entry.id())
                .orElseThrow()
                .status();
    }

    private int count(String sql) {
        return kit.jdbc.sql(sql).query(Integer.class).single();
    }

    private static ErrorCode refusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException refused) {
            return refused.errorCode();
        }
        throw new AssertionError("the call was not refused");
    }
}
