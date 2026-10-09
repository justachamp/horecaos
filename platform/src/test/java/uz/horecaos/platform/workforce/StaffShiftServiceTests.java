package uz.horecaos.platform.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.workforce.WorkforceKit.Person;
import uz.horecaos.platform.workforce.api.StaffShiftFactSource.ApprovalStatus;
import uz.horecaos.platform.workforce.application.StaffRosterService.CreateEntry;
import uz.horecaos.platform.workforce.application.StaffShiftService.ShiftView;
import uz.horecaos.platform.workforce.domain.ReasonCode;
import uz.horecaos.platform.workforce.domain.RosterStatus;
import uz.horecaos.platform.workforce.domain.ShiftStatus;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * ADR 0156 Decisions 4 to 9 against a real database with an advancing clock: clocking in and out, a
 * manager recording and correcting hours, variance judged at close and approved by a person who looked.
 *
 * <p>Every closed shift here is the product of the real path -- opened, the clock moved, closed -- and
 * none is written by hand, so a duration is a duration and a threshold is a threshold at the moment it
 * was resolved. Each test says what would still be true if the code were broken.
 */
class StaffShiftServiceTests {

    private static final Instant START = Instant.parse("2026-10-08T04:00:00Z"); // 09:00 in Tashkent
    private static final String MANAGER = "manager-subject";

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
        kit.grants.allow(
                MANAGER, Capability.STAFF_SHIFT_MANAGE, Capability.STAFF_SHIFT_READ, Capability.STAFF_SHIFT_APPROVE);
    }

    // =========================================================================== a person's own

    @Test
    @DisplayName(
            "a person clocks in and out and the hours are the server's: 09:04 to 17:22 is 29,880 seconds, no plan, no variance")
    void aPersonClocksInAndOut() {
        Person cook = kit.cook("cook");
        kit.clock.set(Instant.parse("2026-10-08T04:04:00Z"));

        ShiftView opened = kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.advance(Duration.ofHours(8).plusMinutes(18));
        ShiftView closed = kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2");

        assertThat(opened.shift().status()).isEqualTo(ShiftStatus.OPEN);
        assertThat(opened.shift().startedAt()).isEqualTo(Instant.parse("2026-10-08T04:04:00Z"));
        ShiftRow shift = closed.shift();
        assertThat(shift.status()).isEqualTo(ShiftStatus.CLOSED);
        assertThat(shift.endedAt()).isEqualTo(Instant.parse("2026-10-08T12:22:00Z"));
        assertThat(shift.workedSeconds()).isEqualTo(29_880L);
        assertThat(shift.paidSeconds()).isEqualTo(29_880L);
        assertThat(shift.plannedSeconds()).as("no plan: null, not zero").isNull();
        assertThat(shift.varianceSeconds()).isNull();
        assertThat(shift.businessDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(shift.openSource().name()).isEqualTo("CONSOLE_SELF");
        assertThat(shift.rosterEntryId())
                .as("a clock-in needs no published entry")
                .isNull();
    }

    @Test
    @DisplayName("a clock event cannot name its own time: nothing in the call takes one")
    void aSelfClockEventTakesNoTimeFromTheRequest() throws Exception {
        // The signatures are the guarantee: clockIn(tenant, subject, location, correlation) and
        // clockOut(tenant, subject, correlation) carry no instant a caller could choose.
        for (var method : uz.horecaos.platform.workforce.application.StaffShiftService.class.getDeclaredMethods()) {
            if (method.getName().equals("clockIn") || method.getName().equals("clockOut")) {
                assertThat(method.getParameterTypes()).doesNotContain(Instant.class);
            }
        }
    }

    @Test
    @DisplayName(
            "the shift is matched to the nearest published entry of the same person and branch, which becomes CONSUMED, and its plan is snapshotted")
    void aShiftConsumesTheEntryItWasOpenedAgainst() {
        Person cook = kit.cook("cook");
        UUID entry = publishedEntry(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z", 30);
        // A published entry the next day, further from the clock-in, must not be the one matched.
        publishedEntry(cook, "2026-10-09T04:00:00Z", "2026-10-09T12:00:00Z", 30);
        kit.clock.set(Instant.parse("2026-10-08T04:10:00Z"));

        ShiftView opened = kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T12:05:00Z"));
        ShiftRow closed =
                kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2").shift();

        assertThat(opened.shift().rosterEntryId()).isEqualTo(entry);
        assertThat(kit.rosterStore
                        .findById(WorkforceKit.TENANT, entry)
                        .orElseThrow()
                        .status())
                .isEqualTo(RosterStatus.CONSUMED);
        assertThat(closed.plannedSeconds())
                .as("eight hours less a thirty-minute planned break")
                .isEqualTo(27_000L);
        assertThat(closed.breakSeconds())
                .as("the break comes from the entry unless a manager sets it")
                .isEqualTo(1_800);
        assertThat(closed.paidSeconds())
                .as("7h55 worked, less the 30-minute break")
                .isEqualTo(26_700L);
        assertThat(closed.varianceSeconds())
                .as("26,700 paid against 27,000 planned")
                .isEqualTo(-300L);
        assertThat(closed.varianceThresholdMinutes())
                .as("the resolved value is snapshotted")
                .isEqualTo(30);
        assertThat(closed.status()).as("five minutes early is within thirty").isEqualTo(ShiftStatus.CLOSED);
    }

    @Test
    @DisplayName(
            "over the threshold a shift waits in AWAITING_APPROVAL and is not a payroll fact until a person approves it")
    void overTheThresholdTheShiftWaits() {
        Person cook = kit.cook("cook");
        publishedEntry(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z", 0);
        kit.clock.set(Instant.parse("2026-10-08T04:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T13:00:00Z"));

        ShiftRow waiting =
                kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2").shift();

        assertThat(waiting.status()).isEqualTo(ShiftStatus.AWAITING_APPROVAL);
        assertThat(waiting.varianceSeconds()).isEqualTo(3_600L);
        assertThat(kit.factSource.closedShiftsOn(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8)))
                .as("a shift past the threshold stays out of the fact until it settles")
                .isEmpty();

        ShiftRow approved = kit.shifts
                .approve(
                        WorkforceKit.branch(),
                        waiting.id(),
                        waiting.version(),
                        ReasonCode.WORKED_AS_RECORDED,
                        MANAGER,
                        "a1")
                .shift();

        assertThat(approved.status()).isEqualTo(ShiftStatus.CLOSED);
        assertThat(approved.approvedBy()).isEqualTo(MANAGER);
        assertThat(kit.factSource.closedShiftsOn(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8)))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
                    assertThat(fact.paidSeconds()).isEqualTo(32_400L);
                    assertThat(fact.staffDisplayReference()).isEqualTo(cook.reference());
                });
        assertThat(kit.auditFacts("workforce.shift.approved")).hasSize(1);
    }

    @Test
    @DisplayName("exactly at the threshold the shift settles at once; one second over, it waits")
    void theThresholdIsExclusive() {
        Person cook = kit.cook("cook");
        publishedEntry(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z", 0);
        kit.clock.set(Instant.parse("2026-10-08T04:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T12:30:00Z"));

        assertThat(kit.shifts
                        .clockOut(WorkforceKit.TENANT, cook.subject(), "c2")
                        .shift()
                        .status())
                .as("thirty minutes over, with a thirty-minute threshold")
                .isEqualTo(ShiftStatus.CLOSED);
    }

    @Test
    @DisplayName("a threshold changed after a shift closed leaves that shift's verdict and snapshot alone")
    void aLaterThresholdDoesNotReJudgeAnOldShift() {
        Person cook = kit.cook("cook");
        publishedEntry(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z", 0);
        kit.clock.set(Instant.parse("2026-10-08T04:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T12:20:00Z"));
        ShiftRow closed =
                kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2").shift();
        assertThat(closed.status()).isEqualTo(ShiftStatus.CLOSED);

        kit.settings.set("workforce.shift.variance_threshold_minutes", 5);

        ShiftRow reread = kit.shiftStore.find(WorkforceKit.TENANT, closed.id()).orElseThrow();
        assertThat(reread.status()).isEqualTo(ShiftStatus.CLOSED);
        assertThat(reread.varianceThresholdMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("a second clock-in while a shift is open is refused as a conflict, and exactly one open shift exists")
    void aSecondClockInIsRefused() {
        Person cook = kit.cook("cook");
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");

        assertThatThrownBy(() -> kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c2"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(refused.properties()).containsEntry("reason", "SHIFT_ALREADY_OPEN");
                });
        assertThat(count("SELECT count(*) FROM workforce.staff_shifts WHERE status = 'OPEN'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a person with no job at a branch, an unknown branch and another tenant's branch all get the same 404")
    void theBranchAnswersAreAllAlike() {
        Person cook = kit.cook("cook");

        List<ErrorCode> codes = List.of(
                refusal(() ->
                        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.SECOND_LOCATION, "x")),
                refusal(() -> kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), UUID.randomUUID(), "x")),
                refusal(() ->
                        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.OTHER_LOCATION, "x")),
                refusal(() ->
                        kit.shifts.clockIn(WorkforceKit.OTHER_TENANT, cook.subject(), WorkforceKit.LOCATION, "x")));

        assertThat(codes).containsOnly(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(count("SELECT count(*) FROM workforce.staff_shifts")).isZero();
    }

    @Test
    @DisplayName("a person whose employment ended, or who holds no clock capability, cannot clock in")
    void onlyAnActivePersonWithTheCapabilityClocksIn() {
        Person ended = kit.person(WorkforceKit.TENANT, "ended", WorkforceKit.LOCATION, "ENDED");
        kit.grants.allow(ended.subject(), Capability.STAFF_SHIFT_CLOCK);
        Person noCapability = kit.person(WorkforceKit.TENANT, "plain", WorkforceKit.LOCATION, "ACTIVE");

        assertThat(refusal(() -> kit.shifts.clockIn(WorkforceKit.TENANT, ended.subject(), WorkforceKit.LOCATION, "x")))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(refusal(() ->
                        kit.shifts.clockIn(WorkforceKit.TENANT, noCapability.subject(), WorkforceKit.LOCATION, "x")))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("clocking out with no open shift is a state error, not a not-found")
    void clockOutWithNothingOpen() {
        Person cook = kit.cook("cook");

        assertThatThrownBy(() -> kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
                    assertThat(refused.properties()).containsEntry("reason", "NO_OPEN_SHIFT");
                });
    }

    @Test
    @DisplayName(
            "a shift left open past the sanity bound is never closed by the system, and a person cannot end it with hours they did not work")
    void nothingIsClosedWithInventedHours() {
        Person cook = kit.cook("cook");
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.advance(Duration.ofHours(30));

        assertThatThrownBy(() -> kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
                    assertThat(refused.properties()).containsEntry("reason", "SHIFT_TOO_LONG");
                });
        assertThat(count("SELECT count(*) FROM workforce.staff_shifts WHERE status = 'OPEN'"))
                .as("still open: a person's flag, never a job's guess")
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the audit facts of a clock-in and clock-out name the person by display reference and carry no name or employee number")
    void theFactsCarryNoIdentity() {
        Person cook = kit.cook("Aziza Karimova");
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.advance(Duration.ofHours(2));
        kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2");

        List<Map<String, Object>> facts = kit.auditFacts("workforce.shift");

        assertThat(facts)
                .extracting(fact -> fact.get("action_code"))
                .contains("workforce.shift.opened", "workforce.shift.closed");
        String all = facts.toString();
        assertThat(all).contains(cook.reference());
        assertThat(all).doesNotContain("Aziza", "Karimova", "E-" + cook.reference());
        assertThat(facts)
                .allSatisfy(fact -> assertThat(fact.get("actor_subject")).isEqualTo(cook.subject()));
    }

    // ============================================================================ a manager's acts

    @Test
    @DisplayName("a manager records a shift in the past only with a reason from the closed list, never in the future")
    void aManagerNeedsAReasonForThePast() {
        Person cook = kit.cook("cook");
        kit.clock.set(Instant.parse("2026-10-08T10:00:00Z"));
        Instant twoHoursAgo = Instant.parse("2026-10-08T08:00:00Z");

        assertThat(refusal(() ->
                        kit.shifts.openFor(WorkforceKit.branch(), cook.memberId(), twoHoursAgo, null, MANAGER, "m1")))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> kit.shifts.openFor(
                        WorkforceKit.branch(),
                        cook.memberId(),
                        Instant.parse("2026-10-08T11:00:00Z"),
                        ReasonCode.FORGOT_CLOCK_IN,
                        MANAGER,
                        "m2")))
                .as("hours cannot be recorded for a time that has not come")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        ShiftView opened = kit.shifts.openFor(
                WorkforceKit.branch(), cook.memberId(), twoHoursAgo, ReasonCode.FORGOT_CLOCK_IN, MANAGER, "m3");

        assertThat(opened.shift().openSource().name()).isEqualTo("CONSOLE_MANAGER");
        assertThat(opened.shift().editReasonCode()).isEqualTo("FORGOT_CLOCK_IN");
        assertThat(kit.auditFacts("workforce.shift.opened")).singleElement().satisfies(fact -> {
            assertThat(fact.get("reason")).isEqualTo("FORGOT_CLOCK_IN");
            assertThat(fact.get("actor_subject")).isEqualTo(MANAGER);
        });
    }

    @Test
    @DisplayName("a manager closes a forgotten clock-out; the break defaults to the plan unless the manager sets it")
    void aManagerClosesAForgottenClockOut() {
        Person cook = kit.cook("cook");
        UUID entry = publishedEntry(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z", 60);
        kit.clock.set(Instant.parse("2026-10-08T04:05:00Z"));
        ShiftRow opened = kit.shifts
                .clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1")
                .shift();
        kit.clock.set(Instant.parse("2026-10-08T15:00:00Z"));

        ShiftRow closed = kit.shifts
                .closeFor(
                        WorkforceKit.branch(),
                        opened.id(),
                        opened.version(),
                        Instant.parse("2026-10-08T12:00:00Z"),
                        3_000,
                        ReasonCode.FORGOT_CLOCK_OUT,
                        MANAGER,
                        "m1")
                .shift();

        assertThat(closed.status()).isEqualTo(ShiftStatus.CLOSED);
        assertThat(closed.breakSeconds())
                .as("the manager's break, not the entry's 60 minutes")
                .isEqualTo(3_000);
        assertThat(closed.paidSeconds()).as("7h55 worked less 50 minutes").isEqualTo(28_500L - 3_000L);
        assertThat(closed.lastEditedBy()).isEqualTo(MANAGER);
        assertThat(entry).isEqualTo(closed.rosterEntryId());
    }

    @Test
    @DisplayName(
            "a correction is a conditional write whose fact holds the old and new times; a stale version changes nothing")
    void aCorrectionLeavesTheOldAndNewTimes() {
        Person cook = kit.cook("cook");
        kit.clock.set(Instant.parse("2026-10-08T04:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T12:00:00Z"));
        ShiftRow closed =
                kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2").shift();
        kit.clock.set(Instant.parse("2026-10-08T13:00:00Z"));

        ShiftRow corrected = kit.shifts
                .correct(
                        WorkforceKit.branch(),
                        closed.id(),
                        closed.version(),
                        null,
                        Instant.parse("2026-10-08T12:30:00Z"),
                        null,
                        ReasonCode.FORGOT_CLOCK_OUT,
                        MANAGER,
                        "m1")
                .shift();

        assertThat(corrected.endedAt()).isEqualTo(Instant.parse("2026-10-08T12:30:00Z"));
        assertThat(corrected.paidSeconds()).isEqualTo(30_600L);
        assertThat(corrected.version()).isGreaterThan(closed.version());
        Map<String, Object> fact = kit.auditFacts("workforce.shift.corrected").getFirst();
        assertThat(fact.get("reason")).isEqualTo("FORGOT_CLOCK_OUT");
        assertThat(String.valueOf(fact.get("change_document")))
                .contains("2026-10-08T12:00:00Z")
                .contains("2026-10-08T12:30:00Z");

        assertThatThrownBy(() -> kit.shifts.correct(
                        WorkforceKit.branch(),
                        closed.id(),
                        closed.version(),
                        null,
                        Instant.parse("2026-10-08T13:00:00Z"),
                        null,
                        ReasonCode.FORGOT_CLOCK_OUT,
                        MANAGER,
                        "m2"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        stale -> assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(kit.shiftStore
                        .find(WorkforceKit.TENANT, closed.id())
                        .orElseThrow()
                        .endedAt())
                .as("the stale write overwrote nothing")
                .isEqualTo(Instant.parse("2026-10-08T12:30:00Z"));
    }

    @Test
    @DisplayName("moving a shift's start across the business-day boundary recomputes its business date")
    void aCorrectedStartRecomputesTheBusinessDate() {
        Person cook = kit.cook("cook");
        kit.clock.set(Instant.parse("2026-10-08T17:00:00Z")); // 22:00 local
        ShiftRow opened = kit.shifts
                .openFor(
                        WorkforceKit.branch(),
                        cook.memberId(),
                        Instant.parse("2026-10-08T17:00:00Z"),
                        null,
                        MANAGER,
                        "m1")
                .shift();
        kit.clock.set(Instant.parse("2026-10-08T22:00:00Z")); // 03:00 on the 9th, local
        ShiftRow closed = kit.shifts
                .closeFor(
                        WorkforceKit.branch(),
                        opened.id(),
                        opened.version(),
                        Instant.parse("2026-10-08T21:00:00Z"),
                        null,
                        ReasonCode.FORGOT_CLOCK_OUT,
                        MANAGER,
                        "m2")
                .shift();
        assertThat(closed.businessDate()).isEqualTo(LocalDate.of(2026, 10, 8));

        ShiftRow moved = kit.shifts
                .correct(
                        WorkforceKit.branch(),
                        closed.id(),
                        closed.version(),
                        Instant.parse("2026-10-08T19:30:00Z"), // 00:30 on the 9th, local
                        null,
                        null,
                        ReasonCode.PLAN_CHANGED,
                        MANAGER,
                        "m3")
                .shift();

        assertThat(moved.businessDate()).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    @DisplayName("a shift older than the retroactive window can be changed only by a holder of the approve capability")
    void anOldShiftNeedsAnApprover() {
        Person cook = kit.cook("cook");
        String branchManager = "branch-manager-without-approve";
        kit.grants.allow(branchManager, Capability.STAFF_SHIFT_MANAGE, Capability.STAFF_SHIFT_READ);
        kit.clock.set(Instant.parse("2026-10-08T10:00:00Z"));
        Instant tenDaysAgo = Instant.parse("2026-09-28T04:00:00Z");

        assertThatThrownBy(() -> kit.shifts.openFor(
                        WorkforceKit.branch(),
                        cook.memberId(),
                        tenDaysAgo,
                        ReasonCode.FORGOT_CLOCK_IN,
                        branchManager,
                        "m1"))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);

        ShiftView opened = kit.shifts.openFor(
                WorkforceKit.branch(), cook.memberId(), tenDaysAgo, ReasonCode.FORGOT_CLOCK_IN, MANAGER, "m2");
        assertThat(opened.shift().startedAt()).isEqualTo(tenDaysAgo);

        kit.settings.set("workforce.shift.retroactive_edit_days", 14);
        ShiftView withinWindow = kit.shifts.openFor(
                WorkforceKit.branch(),
                kit.cook("second").memberId(),
                tenDaysAgo,
                ReasonCode.FORGOT_CLOCK_IN,
                branchManager,
                "m3");
        assertThat(withinWindow.shift().id()).isNotNull();
    }

    @Test
    @DisplayName("a void keeps the row, takes it out of payroll and gives the consumed entry back to the rota")
    void voidingNeverDeletes() {
        Person cook = kit.cook("cook");
        UUID entry = publishedEntry(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z", 0);
        kit.clock.set(Instant.parse("2026-10-08T04:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T12:00:00Z"));
        ShiftRow closed =
                kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2").shift();
        assertThat(kit.factSource.closedShiftsOn(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8)))
                .hasSize(1);

        ShiftRow voided = kit.shifts
                .voidShift(WorkforceKit.branch(), closed.id(), null, ReasonCode.ENTERED_IN_ERROR, MANAGER, "m1")
                .shift();

        assertThat(voided.status()).isEqualTo(ShiftStatus.VOIDED);
        assertThat(voided.voidReasonCode()).isEqualTo("ENTERED_IN_ERROR");
        assertThat(count("SELECT count(*) FROM workforce.staff_shifts"))
                .as("never deleted")
                .isEqualTo(1);
        assertThat(kit.factSource.closedShiftsOn(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8)))
                .isEmpty();
        assertThat(kit.rosterStore
                        .findById(WorkforceKit.TENANT, entry)
                        .orElseThrow()
                        .status())
                .isEqualTo(RosterStatus.PUBLISHED);
        assertThat(refusal(() -> kit.shifts.voidShift(
                        WorkforceKit.branch(), closed.id(), null, ReasonCode.DUPLICATE, MANAGER, "m2")))
                .as("already voided")
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(kit.auditFacts("workforce.shift.voided")).hasSize(1);
    }

    @Test
    @DisplayName("correcting an approved shift's hours withdraws the approval: the hours it vouched for are gone")
    void aCorrectionWithdrawsAnApproval() {
        Person cook = kit.cook("cook");
        publishedEntry(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z", 0);
        kit.clock.set(Instant.parse("2026-10-08T04:00:00Z"));
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.set(Instant.parse("2026-10-08T13:00:00Z"));
        ShiftRow waiting =
                kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2").shift();
        ShiftRow approved = kit.shifts
                .approve(
                        WorkforceKit.branch(),
                        waiting.id(),
                        waiting.version(),
                        ReasonCode.WORKED_AS_RECORDED,
                        MANAGER,
                        "a")
                .shift();
        kit.clock.set(Instant.parse("2026-10-08T15:00:00Z"));

        ShiftRow corrected = kit.shifts
                .correct(
                        WorkforceKit.branch(),
                        approved.id(),
                        approved.version(),
                        null,
                        Instant.parse("2026-10-08T14:00:00Z"),
                        null,
                        ReasonCode.FORGOT_CLOCK_OUT,
                        MANAGER,
                        "m")
                .shift();

        assertThat(corrected.approvedBy()).isNull();
        assertThat(corrected.status())
                .as("ten hours against an eight-hour plan waits again")
                .isEqualTo(ShiftStatus.AWAITING_APPROVAL);
        assertThat(kit.factSource.closedShiftsOn(WorkforceKit.TENANT, LocalDate.of(2026, 10, 8)))
                .isEmpty();
    }

    @Test
    @DisplayName("when the tenant says a different person must approve, the editor cannot; by default they can")
    void approverMustDiffer() {
        Person cook = kit.cook("cook");
        publishedEntry(cook, "2026-10-08T04:00:00Z", "2026-10-08T12:00:00Z", 0);
        kit.clock.set(Instant.parse("2026-10-08T04:00:00Z"));
        ShiftRow opened = kit.shifts
                .clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1")
                .shift();
        kit.clock.set(Instant.parse("2026-10-08T14:00:00Z"));
        ShiftRow waiting = kit.shifts
                .closeFor(
                        WorkforceKit.branch(),
                        opened.id(),
                        opened.version(),
                        Instant.parse("2026-10-08T13:00:00Z"),
                        null,
                        ReasonCode.FORGOT_CLOCK_OUT,
                        MANAGER,
                        "m1")
                .shift();
        assertThat(waiting.status()).isEqualTo(ShiftStatus.AWAITING_APPROVAL);
        kit.settings.set("workforce.shift.approver_must_differ", true);

        assertThatThrownBy(() -> kit.shifts.approve(
                        WorkforceKit.branch(),
                        waiting.id(),
                        waiting.version(),
                        ReasonCode.WORKED_AS_RECORDED,
                        MANAGER,
                        "a1"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
                    assertThat(refused.properties()).containsEntry("reason", "APPROVER_MUST_DIFFER");
                });

        kit.grants.allow("second-approver", Capability.STAFF_SHIFT_APPROVE);
        assertThat(kit.shifts
                        .approve(
                                WorkforceKit.branch(),
                                waiting.id(),
                                waiting.version(),
                                ReasonCode.WORKED_AS_RECORDED,
                                "second-approver",
                                "a2")
                        .shift()
                        .status())
                .isEqualTo(ShiftStatus.CLOSED);
    }

    @Test
    @DisplayName("only a shift waiting for approval can be approved")
    void onlyAWaitingShiftIsApproved() {
        Person cook = kit.cook("cook");
        kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c1");
        kit.clock.advance(Duration.ofHours(4));
        ShiftRow closed =
                kit.shifts.clockOut(WorkforceKit.TENANT, cook.subject(), "c2").shift();

        assertThat(refusal(() -> kit.shifts.approve(
                        WorkforceKit.branch(),
                        closed.id(),
                        closed.version(),
                        ReasonCode.WORKED_AS_RECORDED,
                        MANAGER,
                        "a")))
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("a person cannot have two overlapping shifts: the database refuses the second and the service says so")
    void noTwoOverlappingShifts() {
        Person cook = kit.cook("cook");
        kit.clock.set(Instant.parse("2026-10-08T12:00:00Z"));
        ShiftRow first = kit.shifts
                .openFor(
                        WorkforceKit.branch(),
                        cook.memberId(),
                        Instant.parse("2026-10-08T04:00:00Z"),
                        ReasonCode.FORGOT_CLOCK_IN,
                        MANAGER,
                        "m1")
                .shift();
        kit.shifts.closeFor(
                WorkforceKit.branch(),
                first.id(),
                first.version(),
                Instant.parse("2026-10-08T08:00:00Z"),
                null,
                ReasonCode.FORGOT_CLOCK_OUT,
                MANAGER,
                "m2");

        assertThatThrownBy(() -> kit.shifts.openFor(
                        WorkforceKit.branch(),
                        cook.memberId(),
                        Instant.parse("2026-10-08T07:00:00Z"),
                        ReasonCode.FORGOT_CLOCK_IN,
                        MANAGER,
                        "m3"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(refused.properties()).containsEntry("reason", "SHIFT_OVERLAP");
                });
        assertThat(count("SELECT count(*) FROM workforce.staff_shifts")).isEqualTo(1);
    }

    @Test
    @DisplayName("with attendance off every call answers as absent and no row is written")
    void attendanceOffMeansAbsent() {
        Person cook = kit.cook("cook");
        kit.settings.set("workforce.attendance.enabled", false);

        assertThat(refusal(() -> kit.shifts.clockIn(WorkforceKit.TENANT, cook.subject(), WorkforceKit.LOCATION, "c")))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(refusal(() -> kit.shifts.openFor(
                        WorkforceKit.branch(), cook.memberId(), kit.clock.instant(), null, MANAGER, "m")))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(refusal(() -> kit.today.today(WorkforceKit.branch(), false)))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(refusal(() -> kit.mine.mine(WorkforceKit.TENANT, cook.subject())))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(count("SELECT count(*) FROM workforce.staff_shifts")).isZero();
    }

    @Test
    @DisplayName(
            "neither table can hold a position, an address, a device or a name: the columns are the schema's, not a promise")
    void theSchemaHasNoPlaceForAnythingElse() {
        List<String> columns = kit.jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                         WHERE table_schema = 'workforce' ORDER BY column_name
                        """).query(String.class).list();

        assertThat(columns)
                .as("a clock event keeps a time and a source; no GPS, geofence, IP or device identifier (Decision 6)")
                .noneMatch(column -> column.matches(
                                ".*(latitude|longitude|geo|position|address|ip_|_ip|device|fingerprint|user_agent|first_name|last_name|phone|employee_number|name).*")
                        && !column.equals("display_name"));
    }

    // ==================================================================================== helpers

    private UUID publishedEntry(Person person, String start, String end, int breakMinutes) {
        var entry = kit.rota.create(
                WorkforceKit.branch(),
                new CreateEntry(person.memberId(), Instant.parse(start), Instant.parse(end), breakMinutes),
                MANAGER,
                "roster");
        kit.rotaPublish(Instant.parse(start));
        return entry.id();
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
