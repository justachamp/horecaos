package uz.horecaos.platform.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.pricing.api.BenefitGrantDirectory.GrantSummary;
import uz.horecaos.platform.reviews.ReviewsWiring.MutableClock;
import uz.horecaos.platform.reviews.api.RecoveryCaseOpened;
import uz.horecaos.platform.reviews.api.RecoveryCaseSlaBreached;
import uz.horecaos.platform.reviews.api.RecoveryCaseStateChanged;
import uz.horecaos.platform.reviews.api.RecoveryFactSource.CaseFact;
import uz.horecaos.platform.reviews.api.ReviewsConfigurationKeys;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.AttachCommand;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.CaseDetail;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.Scope;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.TransitionCommand;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService.Submission;
import uz.horecaos.platform.reviews.domain.CaseClocks;
import uz.horecaos.platform.reviews.domain.CaseEventKind;
import uz.horecaos.platform.reviews.domain.CaseOutcome;
import uz.horecaos.platform.reviews.domain.CaseState;
import uz.horecaos.platform.reviews.domain.CompensationKind;
import uz.horecaos.platform.reviews.domain.RecoveryPolicyDocument;
import uz.horecaos.platform.reviews.domain.SlaState;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.CaseRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.Listed;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The service-recovery worklist (ADR 0159, Decisions 8-10) against a real database: the case a low review
 * opens, the four-state machine edge by edge, the clocks and the sweeper that marks them, the compensation
 * a case links to, and what a case never lets out.
 *
 * <p>The clock is the test's, and every assertion about a duration advances it: a lifetime asserted at one
 * instant is asserted against an instant (CLAUDE.md). The fixtures do what production does -- a review is
 * submitted through {@code ReviewSubmissionService}, which opens the case in the same call -- and never
 * plant a case row by hand.
 */
class RecoveryCaseServiceTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-a000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-a000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-a000-7000-8000-0000000000c1");
    private static final UUID LOCATION_2 = UUID.fromString("018f9f10-a000-7000-8000-0000000000c2");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-a000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-a000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f10-a000-7000-8000-0000000000c3");

    private static final Instant START = Instant.parse("2026-10-08T10:00:00Z");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OrderBoardFixtures fixtures;
    private MutableClock clock;
    private ReviewsWiring wiring;
    private int orders;
    private UUID alice;
    private UUID bob;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the recovery tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void seed() {
        jdbc = JdbcClient.create(db.dataSource());
        fixtures = new OrderBoardFixtures(jdbc);
        jdbc.sql("TRUNCATE TABLE reviews.order_reviews CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE iam.staff_members CASCADE").update();
        fixtures.clean();
        fixtures.tenant(TENANT, "recovery-service", BRAND, LOCATION);
        fixtures.location(TENANT, BRAND, LOCATION_2, "L2", "Second branch");
        fixtures.tenant(OTHER_TENANT, "recovery-other", OTHER_BRAND, OTHER_LOCATION);
        orders = 0;

        clock = MutableClock.at(START);
        wiring = ReviewsWiring.withFlagsOn(jdbc, clock);
        alice = staff("alice", "S-0001", "Aziza Karimova");
        bob = staff("bob", "S-0002", "Bobur Rakhimov");
    }

    // ===================================================================== intake

    @Test
    @DisplayName(
            "a one-star review opens a NEW case in the review's own call, with its clocks and its first timeline line")
    void aLowReviewOpensACase() {
        Submission review = rate(1, LOCATION, "cold and late");

        CaseRow opened = caseOf(review);

        assertThat(opened.state()).isEqualTo(CaseState.NEW);
        assertThat(opened.source()).isEqualTo("AUTO");
        assertThat(opened.locationId()).isEqualTo(LOCATION);
        assertThat(opened.ownerStaffMemberId()).isNull();
        assertThat(opened.firstResponseDueAt()).isEqualTo(START.plus(Duration.ofMinutes(240)));
        assertThat(opened.resolutionDueAt()).isEqualTo(START.plus(Duration.ofMinutes(4320)));
        assertThat(opened.policyVersion())
                .as("nothing authored: the built-in policy, version 0")
                .isZero();
        assertThat(wiring.caseStore.events(TENANT, opened.id()))
                .extracting(e -> e.kind() + ":" + e.actorType() + ":" + e.detailCode())
                .containsExactly("OPENED:SYSTEM:AUTO");

        RecoveryCaseOpened event = wiring.events.of(RecoveryCaseOpened.class).getFirst();
        assertThat(event.caseId()).isEqualTo(opened.id());
        assertThat(event.rating()).isEqualTo(1);
        assertThat(event.source()).isEqualTo("AUTO");
        assertThat(jdbc.sql("SELECT actor_type || ':' || actor_subject FROM audit.audit_events "
                                + "WHERE action_code = 'reviews.case.open'")
                        .query(String.class)
                        .single())
                .as("the intake acts as a system job, never as the customer")
                .isEqualTo("SYSTEM_JOB:reviews.recovery-intake");
    }

    @Test
    @DisplayName(
            "the threshold is inclusive: two stars opens a case, three does not; and with the flag off nothing opens")
    void theThresholdAndTheFlag() {
        assertThat(wiring.caseStore.findByReview(TENANT, rate(2, LOCATION, null).id()))
                .as("2 stars is at the built-in threshold")
                .isPresent();
        assertThat(wiring.caseStore.findByReview(TENANT, rate(3, LOCATION, null).id()))
                .as("3 stars is above it")
                .isEmpty();
        assertThat(wiring.caseStore.findByReview(TENANT, rate(5, LOCATION, null).id()))
                .isEmpty();

        wiring.with(ReviewsConfigurationKeys.FEATURE_SERVICE_RECOVERY_CODE, false);
        Submission whileOff = rate(1, LOCATION, "terrible");
        assertThat(wiring.caseStore.findByReview(TENANT, whileOff.id()))
                .as("the flag is the rollback: new cases stop opening, the review itself is still kept")
                .isEmpty();
        assertThat(wiring.reviewStore.findById(TENANT, whileOff.id())).isPresent();
    }

    @Test
    @DisplayName(
            "the threshold and the clocks come from the brand's policy, and a case keeps the version it opened under")
    void aCaseKeepsItsPolicyVersion() {
        wiring.policies.publish(
                ReviewsConfigurationKeys.RECOVERY_POLICY,
                ResourceScope.brand(TENANT, BRAND),
                new RecoveryPolicyDocument(3, 30, 120, false, false));

        Submission threeStars = rate(3, LOCATION, null);
        CaseRow opened = caseOf(threeStars);
        assertThat(opened.policyVersion()).isEqualTo(1);
        assertThat(opened.firstResponseDueAt()).isEqualTo(START.plus(Duration.ofMinutes(30)));
        assertThat(opened.resolutionDueAt()).isEqualTo(START.plus(Duration.ofMinutes(120)));

        wiring.policies.publish(
                ReviewsConfigurationKeys.RECOVERY_POLICY,
                ResourceScope.brand(TENANT, BRAND),
                new RecoveryPolicyDocument(2, 600, 6000, false, false));
        CaseRow reread = wiring.caseStore.find(TENANT, opened.id()).orElseThrow();
        assertThat(reread.firstResponseDueAt())
                .as("a later edit never makes an old case late, or early")
                .isEqualTo(START.plus(Duration.ofMinutes(30)));
        assertThat(wiring.caseStore.findByReview(TENANT, rate(3, LOCATION, null).id()))
                .as("and the new policy governs the next review")
                .isEmpty();
    }

    @Test
    @DisplayName("a case can be opened by hand from any review of the branch, once, and not from another branch's")
    void manualOpening() {
        Submission fiveStars = rate(5, LOCATION, "lovely");
        Scope here = Scope.location(TENANT, BRAND, LOCATION);

        CaseRow manual = wiring.recovery.openManually(here, fiveStars.id(), "alice");

        assertThat(manual.source()).isEqualTo("MANUAL");
        assertThat(manual.openedByStaffMemberId())
                .as("opened by the staff member who asked")
                .isEqualTo(alice);
        assertThatThrownBy(() -> wiring.recovery.openManually(here, fiveStars.id(), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThatThrownBy(() -> wiring.recovery.openManually(
                        Scope.location(TENANT, BRAND, LOCATION_2),
                        rate(1, LOCATION, null).id(),
                        "alice"))
                .as("a review of another branch is not found from this one")
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        wiring.with(ReviewsConfigurationKeys.FEATURE_SERVICE_RECOVERY_CODE, false);
        assertThatThrownBy(() -> wiring.recovery.openManually(
                        here, rate(5, LOCATION, null).id(), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    // =================================================================== the machine

    @Test
    @DisplayName("every edge of the machine, allowed and refused, with the right refusal for each")
    void everyEdgeOfTheMachine() {
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        List<CaseOutcome> outcomes = new ArrayList<>();
        outcomes.add(null);
        outcomes.addAll(List.of(
                CaseOutcome.REMEDY_GRANTED,
                CaseOutcome.OFFLINE_GESTURE,
                CaseOutcome.CUSTOMER_DECLINED,
                CaseOutcome.NOT_ACTIONABLE));
        int checked = 0;

        for (CaseState from : CaseState.values()) {
            for (CaseState to : CaseState.values()) {
                for (CaseOutcome outcome : outcomes) {
                    CaseRow current = caseIn(from);
                    UUID newOwner = to == CaseState.IN_PROGRESS && from == CaseState.IN_PROGRESS ? bob : null;
                    TransitionCommand command = new TransitionCommand(to, newOwner, outcome, current.version());
                    String label = "%s -> %s with %s".formatted(from, to, outcome);

                    @Nullable ErrorCode expectedRefusal = refusalFor(from, to, outcome);
                    if (expectedRefusal == null) {
                        CaseRow moved = wiring.recovery.transition(here, current.id(), command, "alice");
                        assertThat(moved.state()).as(label).isEqualTo(to);
                        if (to.terminal()) {
                            assertThat(moved.outcome()).as(label).isEqualTo(outcome);
                            assertThat(moved.closedAt()).as(label).isEqualTo(clock.instant());
                        } else {
                            assertThat(moved.outcome()).as(label).isNull();
                            assertThat(moved.closedAt()).as(label).isNull();
                        }
                    } else {
                        assertThatThrownBy(() -> wiring.recovery.transition(here, current.id(), command, "alice"))
                                .as(label)
                                .isInstanceOf(ApiException.class)
                                .extracting(e -> ((ApiException) e).errorCode())
                                .isEqualTo(expectedRefusal);
                        CaseRow unchanged =
                                wiring.caseStore.find(TENANT, current.id()).orElseThrow();
                        assertThat(unchanged.state())
                                .as(label + " leaves the case where it was")
                                .isEqualTo(from);
                        assertThat(unchanged.version()).as(label).isEqualTo(current.version());
                    }
                    checked++;
                }
            }
        }
        assertThat(checked).as("the table was actually walked").isEqualTo(4 * 4 * 5);
    }

    /** The table the record states, written out independently of {@code CaseState}. */
    private static @Nullable ErrorCode refusalFor(CaseState from, CaseState to, @Nullable CaseOutcome outcome) {
        if (from == CaseState.RESOLVED || from == CaseState.CLOSED_UNRESOLVED) {
            return ErrorCode.UNPROCESSABLE_STATE;
        }
        if (from == CaseState.NEW) {
            return switch (to) {
                case IN_PROGRESS -> null;
                case CLOSED_UNRESOLVED -> outcome == CaseOutcome.NOT_ACTIONABLE ? null : ErrorCode.VALIDATION_FAILED;
                case NEW, RESOLVED -> ErrorCode.UNPROCESSABLE_STATE;
            };
        }
        return switch (to) {
            case NEW, IN_PROGRESS -> null;
            case RESOLVED -> {
                if (outcome == null
                        || outcome == CaseOutcome.CUSTOMER_DECLINED
                        || outcome == CaseOutcome.NOT_ACTIONABLE) {
                    yield ErrorCode.VALIDATION_FAILED;
                }
                yield outcome == CaseOutcome.REMEDY_GRANTED ? ErrorCode.UNPROCESSABLE_STATE : null;
            }
            case CLOSED_UNRESOLVED -> {
                if (outcome == null
                        || outcome == CaseOutcome.REMEDY_GRANTED
                        || outcome == CaseOutcome.OFFLINE_GESTURE) {
                    yield ErrorCode.VALIDATION_FAILED;
                }
                yield null;
            }
        };
    }

    @Test
    @DisplayName("starting work makes the caller the owner and the first response; releasing keeps the first response")
    void startingAndReleasing() {
        CaseRow opened = caseOf(rate(1, LOCATION, null));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        clock.advance(Duration.ofMinutes(12));

        CaseRow started = wiring.recovery.transition(
                here, opened.id(), new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()), "alice");

        assertThat(started.ownerStaffMemberId()).isEqualTo(alice);
        assertThat(started.firstResponseAt()).isEqualTo(START.plus(Duration.ofMinutes(12)));
        RecoveryCaseStateChanged changed =
                wiring.events.of(RecoveryCaseStateChanged.class).getLast();
        assertThat(changed.ownerReference())
                .as("the non-personal reference, never the name")
                .isEqualTo("S-0001");
        assertThat(changed.fromState()).isEqualTo("NEW");
        assertThat(changed.toState()).isEqualTo("IN_PROGRESS");

        clock.advance(Duration.ofMinutes(30));
        CaseRow released = wiring.recovery.transition(
                here, opened.id(), new TransitionCommand(CaseState.NEW, null, null, started.version()), "alice");
        assertThat(released.state()).isEqualTo(CaseState.NEW);
        assertThat(released.ownerStaffMemberId()).as("released: nobody owns it").isNull();
        assertThat(released.firstResponseAt())
                .as("the first response was given; releasing the case does not un-give it")
                .isEqualTo(START.plus(Duration.ofMinutes(12)));

        clock.advance(Duration.ofMinutes(5));
        CaseRow restarted = wiring.recovery.transition(
                here,
                opened.id(),
                new TransitionCommand(CaseState.IN_PROGRESS, bob, null, released.version()),
                "alice");
        assertThat(restarted.ownerStaffMemberId())
                .as("a named owner, not the caller")
                .isEqualTo(bob);
        assertThat(restarted.firstResponseAt()).isEqualTo(START.plus(Duration.ofMinutes(12)));

        assertThat(wiring.caseStore.events(TENANT, opened.id()))
                .extracting(e -> e.kind() + ":" + e.detailCode())
                .containsExactly("OPENED:AUTO", "STARTED:S-0001", "RELEASED:S-0001", "STARTED:S-0002");
    }

    @Test
    @DisplayName("a caller with no staff record must name the owner; an owner who does not work here is refused")
    void theOwnerMustBeSomeoneWhoWorksHere() {
        CaseRow opened = caseOf(rate(1, LOCATION, null));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);

        assertThatThrownBy(() -> wiring.recovery.transition(
                        here,
                        opened.id(),
                        new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()),
                        "a-support-session"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Name the owner");
        assertThatThrownBy(() -> wiring.recovery.transition(
                        here,
                        opened.id(),
                        new TransitionCommand(CaseState.IN_PROGRESS, UUID.randomUUID(), null, opened.version()),
                        "alice"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        UUID departed = staff("departed", "S-0009", "Someone Gone");
        wiring.staffCards.register(departed, "departed", "S-0009", "Someone Gone", "ENDED");
        assertThatThrownBy(() -> wiring.recovery.transition(
                        here,
                        opened.id(),
                        new TransitionCommand(CaseState.IN_PROGRESS, departed, null, opened.version()),
                        "alice"))
                .as("an ended employee cannot be handed a case")
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("dismissing from NEW answers nobody: the first response stays null and the case is closed")
    void dismissalIsNotAResponse() {
        CaseRow opened = caseOf(rate(1, LOCATION, null));
        clock.advance(Duration.ofMinutes(5));

        CaseRow dismissed = wiring.recovery.transition(
                Scope.location(TENANT, BRAND, LOCATION),
                opened.id(),
                new TransitionCommand(CaseState.CLOSED_UNRESOLVED, null, CaseOutcome.NOT_ACTIONABLE, opened.version()),
                "alice");

        assertThat(dismissed.state()).isEqualTo(CaseState.CLOSED_UNRESOLVED);
        assertThat(dismissed.firstResponseAt()).isNull();
        assertThat(dismissed.ownerStaffMemberId()).isNull();
        assertThat(dismissed.closedAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("a lost race is STALE_VERSION and changes nothing")
    void aLostRaceIsStale() {
        CaseRow opened = caseOf(rate(1, LOCATION, null));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        wiring.recovery.transition(
                here, opened.id(), new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()), "alice");

        assertThatThrownBy(() -> wiring.recovery.transition(
                        here,
                        opened.id(),
                        new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()),
                        "bob"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.STALE_VERSION);
        assertThat(wiring.caseStore.find(TENANT, opened.id()).orElseThrow().ownerStaffMemberId())
                .as("the second caller did not become the owner")
                .isEqualTo(alice);
    }

    // ================================================================= the clocks

    @Test
    @DisplayName("a breach is marked once, on an open case, and a case closed before the worker comes is never marked")
    void theSweeperMarksOnce() {
        CaseRow untouched = caseOf(rate(1, LOCATION, null));
        CaseRow dismissed = caseOf(rate(1, LOCATION, null));
        CaseRow answered = caseOf(rate(1, LOCATION, null));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);

        clock.advance(Duration.ofMinutes(10));
        wiring.recovery.transition(
                here,
                dismissed.id(),
                new TransitionCommand(
                        CaseState.CLOSED_UNRESOLVED, null, CaseOutcome.NOT_ACTIONABLE, dismissed.version()),
                "alice");
        wiring.recovery.transition(
                here,
                answered.id(),
                new TransitionCommand(CaseState.IN_PROGRESS, null, null, answered.version()),
                "alice");

        assertThat(wiring.recovery.markBreaches(100)).as("nothing is due yet").isZero();

        clock.advance(Duration.ofMinutes(241));
        assertThat(wiring.recovery.markBreaches(100))
                .as("only the untouched case has passed a clock it has not been answered on")
                .isEqualTo(1);
        assertThat(wiring.events.of(RecoveryCaseSlaBreached.class))
                .extracting(e -> e.caseId() + ":" + e.kind())
                .containsExactly(untouched.id() + ":FIRST_RESPONSE");
        assertThat(wiring.recovery.markBreaches(100))
                .as("a second pass marks nothing: once")
                .isZero();

        clock.advance(Duration.ofDays(4));
        assertThat(wiring.recovery.markBreaches(100))
                .as("the resolution clock of the two open cases; the dismissed case is closed and never marked")
                .isEqualTo(2);
        assertThat(wiring.events.of(RecoveryCaseSlaBreached.class))
                .extracting(e -> e.caseId() + ":" + e.kind())
                .containsExactlyInAnyOrder(
                        untouched.id() + ":FIRST_RESPONSE",
                        untouched.id() + ":RESOLUTION",
                        answered.id() + ":RESOLUTION");

        CaseRow reread = wiring.caseStore.find(TENANT, dismissed.id()).orElseThrow();
        assertThat(reread.firstResponseBreachedAt()).isNull();
        assertThat(reread.resolutionBreachedAt()).isNull();
        assertThat(wiring.caseStore.events(TENANT, untouched.id()))
                .extracting(e -> e.kind())
                .contains(CaseEventKind.FIRST_RESPONSE_BREACHED, CaseEventKind.RESOLUTION_BREACHED);
    }

    @Test
    @DisplayName("two workers racing for the same overdue cases mark each clock exactly once between them")
    void twoWorkersMarkExactlyOnce() throws Exception {
        for (int i = 0; i < 12; i++) {
            caseOf(rate(1, LOCATION, null));
        }
        clock.advance(Duration.ofDays(5));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> workers = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                workers.add(() -> wiring.recovery.markBreaches(5));
            }
            int marked = 0;
            for (Future<Integer> result : pool.invokeAll(workers)) {
                marked += result.get();
            }
            marked += wiring.recovery.markBreaches(100);

            assertThat(marked)
                    .as("twelve cases, two clocks each, however the workers interleaved")
                    .isEqualTo(24);
        } finally {
            pool.shutdownNow();
        }
        assertThat(wiring.events.of(RecoveryCaseSlaBreached.class)).hasSize(24);
        assertThat(wiring.events.of(RecoveryCaseSlaBreached.class).stream()
                        .map(e -> e.caseId() + e.kind())
                        .distinct())
                .as("no clock published twice")
                .hasSize(24);
    }

    @Test
    @DisplayName(
            "the SQL the list uses and the Java the drawer uses agree about where a case stands, for every shape of case")
    void theListAndTheDrawerAgreeAboutTheClocks() {
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        List<UUID> caseIds = new ArrayList<>();

        caseIds.add(caseOf(rate(1, LOCATION, null)).id());
        CaseRow started = caseOf(rate(1, LOCATION, null));
        clock.advance(Duration.ofMinutes(20));
        wiring.recovery.transition(
                here,
                started.id(),
                new TransitionCommand(CaseState.IN_PROGRESS, null, null, started.version()),
                "alice");
        caseIds.add(started.id());

        CaseRow resolvedInTime = caseOf(rate(1, LOCATION, null));
        clock.advance(Duration.ofMinutes(3));
        CaseRow resolvedInTimeStarted = wiring.recovery.transition(
                here,
                resolvedInTime.id(),
                new TransitionCommand(CaseState.IN_PROGRESS, null, null, resolvedInTime.version()),
                "alice");
        clock.advance(Duration.ofHours(2));
        wiring.recovery.transition(
                here,
                resolvedInTime.id(),
                new TransitionCommand(
                        CaseState.RESOLVED, null, CaseOutcome.OFFLINE_GESTURE, resolvedInTimeStarted.version()),
                "alice");
        caseIds.add(resolvedInTime.id());

        CaseRow resolvedLate = caseOf(rate(1, LOCATION, null));
        CaseRow resolvedLateStarted = wiring.recovery.transition(
                here,
                resolvedLate.id(),
                new TransitionCommand(CaseState.IN_PROGRESS, null, null, resolvedLate.version()),
                "alice");
        clock.advance(Duration.ofDays(4));
        wiring.recovery.transition(
                here,
                resolvedLate.id(),
                new TransitionCommand(
                        CaseState.RESOLVED, null, CaseOutcome.EXPLAINED_NO_REMEDY, resolvedLateStarted.version()),
                "alice");
        caseIds.add(resolvedLate.id());

        CaseRow dismissed = caseOf(rate(1, LOCATION, null));
        wiring.recovery.transition(
                here,
                dismissed.id(),
                new TransitionCommand(
                        CaseState.CLOSED_UNRESOLVED, null, CaseOutcome.NOT_ACTIONABLE, dismissed.version()),
                "alice");
        caseIds.add(dismissed.id());

        Set<SlaState> seen = EnumSet.noneOf(SlaState.class);
        Instant end = clock.instant().plus(Duration.ofDays(10));
        for (Instant now = START; now.isBefore(end); now = now.plusSeconds(13 * 60 + 7)) {
            for (UUID caseId : caseIds) {
                Listed listed = wiring.caseStore.findListed(TENANT, caseId, now).orElseThrow();
                CaseRow row = listed.row();
                CaseClocks clocks = new CaseClocks(
                        row.createdAt(),
                        row.firstResponseDueAt(),
                        row.resolutionDueAt(),
                        row.firstResponseAt(),
                        row.closedAt());
                assertThat(listed.slaState())
                        .as("case %s as of %s", caseId, now)
                        .isEqualTo(clocks.overall(now));
                seen.add(listed.slaState());
            }
        }
        assertThat(seen)
                .as("the probe walked every state, or agreeing about one of them proves little")
                .containsExactlyInAnyOrder(SlaState.values());
    }

    // ============================================================= the worklist reads

    @Test
    @DisplayName("the list is filtered by state, owner and clocks, ordered for work, scoped to the branch, and counted")
    void theWorklist() {
        Scope brand = Scope.brand(TENANT, BRAND);
        Scope second = Scope.location(TENANT, BRAND, LOCATION_2);
        CaseRow first = caseOf(rate(1, LOCATION, null));
        clock.advance(Duration.ofMinutes(1));
        CaseRow secondCase = caseOf(rate(2, LOCATION_2, null));
        clock.advance(Duration.ofMinutes(1));
        CaseRow third = caseOf(rate(1, LOCATION, null));
        wiring.recovery.transition(
                Scope.location(TENANT, BRAND, LOCATION),
                third.id(),
                new TransitionCommand(CaseState.IN_PROGRESS, null, null, third.version()),
                "alice");

        assertThat(wiring.recovery.list(brand, null, null, null, null, 50).items())
                .extracting(s -> s.row().id())
                .as("open cases most urgent first: the earliest resolution due time")
                .containsExactly(first.id(), secondCase.id(), third.id());
        assertThat(wiring.recovery
                        .list(brand, CaseState.IN_PROGRESS, null, null, null, 50)
                        .items())
                .extracting(s -> s.row().id())
                .containsExactly(third.id());
        assertThat(wiring.recovery.list(brand, null, alice, null, null, 50).items())
                .extracting(s -> s.row().id())
                .containsExactly(third.id());
        assertThat(wiring.recovery.list(second, null, null, null, null, 50).items())
                .extracting(s -> s.row().id())
                .as("a branch sees its own cases and no other's")
                .containsExactly(secondCase.id());
        assertThat(wiring.recovery
                        .list(brand, null, null, SlaState.BREACHED, null, 50)
                        .items())
                .isEmpty();

        assertThat(wiring.recovery.list(brand, null, null, null, null, 2).nextCursor())
                .as("a full page carries the cursor")
                .isEqualTo(secondCase.id().toString());
        assertThat(wiring.recovery
                        .list(brand, null, null, null, secondCase.id(), 2)
                        .items())
                .extracting(s -> s.row().id())
                .containsExactly(third.id());
        assertThatThrownBy(() -> wiring.recovery.list(second, null, null, null, first.id(), 50))
                .as("a cursor from another branch is not a cursor here")
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        clock.advance(Duration.ofMinutes(250));
        var counts = wiring.recovery.counts(brand);
        assertThat(counts.byState().get(CaseState.NEW)).isEqualTo(2L);
        assertThat(counts.byState().get(CaseState.IN_PROGRESS)).isEqualTo(1L);
        assertThat(counts.bySla().get(SlaState.BREACHED))
                .as("the two untouched cases are past their first response; the answered one is not")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("a case in another branch, or another tenant, is not found by any operation")
    void aCaseOutsideTheScopeIsNotFound() {
        CaseRow opened = caseOf(rate(1, LOCATION, null));
        Scope elsewhere = Scope.location(TENANT, BRAND, LOCATION_2);
        Scope otherTenant = Scope.location(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION);

        for (Scope scope : List.of(elsewhere, otherTenant)) {
            assertThatThrownBy(() -> wiring.recovery.detail(scope, opened.id(), true))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
            assertThatThrownBy(() -> wiring.recovery.addNote(scope, opened.id(), "a note", "alice"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
            assertThatThrownBy(() -> wiring.recovery.transition(
                            scope,
                            opened.id(),
                            new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()),
                            "alice"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }
        assertThat(wiring.caseStore.find(TENANT, opened.id()).orElseThrow().state())
                .as("and the refused attempts changed nothing")
                .isEqualTo(CaseState.NEW);
    }

    // ===================================================================== the drawer

    @Test
    @DisplayName("the drawer shows the comment only to a caller who may read reviews, and marks it withheld otherwise")
    void theDrawerWithholdsTheComment() {
        CaseRow opened = caseOf(rate(1, LOCATION, "the soup arrived cold and the courier was rude"));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);

        CaseDetail withRights = wiring.recovery.detail(here, opened.id(), true);
        assertThat(withRights.hasComment()).isTrue();
        assertThat(withRights.comment()).isEqualTo("the soup arrived cold and the courier was rude");
        assertThat(withRights.commentWithheld()).isFalse();

        CaseDetail withoutRights = wiring.recovery.detail(here, opened.id(), false);
        assertThat(withoutRights.hasComment()).as("there is a comment").isTrue();
        assertThat(withoutRights.comment())
                .as("but this caller does not read it")
                .isNull();
        assertThat(withoutRights.commentWithheld()).isTrue();
        assertThat(withoutRights.summary().rating())
                .as("the case can still be worked")
                .isEqualTo(1);

        CaseDetail noComment =
                wiring.recovery.detail(here, caseOf(rate(1, LOCATION, null)).id(), false);
        assertThat(noComment.hasComment()).isFalse();
        assertThat(noComment.commentWithheld()).as("nothing to withhold").isFalse();
    }

    @Test
    @DisplayName(
            "a note is encrypted at rest, shown in the drawer, and carried by no event, audit fact or return value")
    void aNoteNeverLeavesTheDrawer() throws Exception {
        CaseRow opened = caseOf(rate(1, LOCATION, null));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        String note = "Spoke to the customer on 998901234567; she wants a call back after six";

        UUID entry = wiring.recovery.addNote(here, opened.id(), note, "alice");
        wiring.recovery.transition(
                here, opened.id(), new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()), "alice");

        String stored = jdbc.sql("SELECT note_protected FROM reviews.recovery_case_events WHERE id = :id")
                .param("id", entry)
                .query(String.class)
                .single();
        assertThat(stored)
                .as("ciphertext at rest")
                .doesNotContain("998901234567")
                .doesNotContain("call back");

        CaseDetail detail = wiring.recovery.detail(here, opened.id(), false);
        assertThat(detail.timeline())
                .filteredOn(line -> line.kind() == CaseEventKind.NOTE)
                .singleElement()
                .satisfies(line -> assertThat(line.note()).isEqualTo(note));

        JsonMapper json = JsonMapper.builder().build();
        String everythingPublished = json.writeValueAsString(
                wiring.events.all().stream().map(Object::toString).toList());
        String payloads = wiring.events.all().stream()
                .map(event -> event instanceof uz.horecaos.platform.reviews.api.ReviewsEvent reviews
                        ? json.writeValueAsString(reviews.payload())
                        : "")
                .reduce("", String::concat);
        assertThat(everythingPublished + payloads)
                .as("no event, in any rendering, carries the note")
                .doesNotContain("998901234567")
                .doesNotContain("call back");
        assertThat(jdbc.sql(
                                "SELECT string_agg(change_document::text || coalesce(reason, ''), ' ') FROM audit.audit_events")
                        .query(String.class)
                        .optional()
                        .orElse(""))
                .as("and no audit fact does")
                .doesNotContain("998901234567")
                .doesNotContain("call back");

        assertThatThrownBy(() -> wiring.recovery.addNote(here, opened.id(), "   ", "alice"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> wiring.recovery.addNote(here, opened.id(), "x".repeat(2001), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ============================================================== compensation

    @Test
    @DisplayName("a case links a remedy of its own order, copies the amount, and then REMEDY_GRANTED can close it")
    void attachingARemedy() {
        Submission review = rate(1, LOCATION, null);
        CaseRow opened = caseOf(review);
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        UUID remedy = UUID.randomUUID();
        wiring.remedies.register(TENANT, remedy, opened.orderId(), 45_000, "UZS");
        CaseRow started = wiring.recovery.transition(
                here, opened.id(), new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()), "alice");

        assertThatThrownBy(() -> wiring.recovery.transition(
                        here,
                        opened.id(),
                        new TransitionCommand(CaseState.RESOLVED, null, CaseOutcome.REMEDY_GRANTED, started.version()),
                        "alice"))
                .as("a remedy is a fact, not the operator's word: nothing is attached yet")
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);

        var link = wiring.recovery.attach(
                here, opened.id(), new AttachCommand(CompensationKind.ORDER_REMEDY, remedy), "alice");
        assertThat(link.amountMinor()).isEqualTo(45_000L);
        assertThat(link.currency()).isEqualTo("UZS");
        assertThat(link.orderId()).isEqualTo(opened.orderId());

        CaseRow resolved = wiring.recovery.transition(
                here,
                opened.id(),
                new TransitionCommand(CaseState.RESOLVED, null, CaseOutcome.REMEDY_GRANTED, started.version()),
                "alice");
        assertThat(resolved.state()).isEqualTo(CaseState.RESOLVED);

        assertThat(wiring.recovery.detail(here, opened.id(), false).compensations())
                .singleElement()
                .satisfies(line -> assertThat(java.util.Objects.requireNonNull(line.attachedBy())
                                .label())
                        .isEqualTo("Aziza Karimova"));
        assertThatThrownBy(() -> wiring.recovery.attach(
                        here,
                        opened.id(),
                        new AttachCommand(CompensationKind.ORDER_REMEDY, UUID.randomUUID()),
                        "alice"))
                .as("a closed case is final")
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("a remedy of another order or another tenant, and a second link to the same remedy, are refused")
    void remedyChecks() {
        CaseRow first = caseOf(rate(1, LOCATION, null));
        CaseRow second = caseOf(rate(1, LOCATION, null));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        UUID ofAnotherOrder = UUID.randomUUID();
        UUID ofAnotherTenant = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        wiring.remedies.register(TENANT, ofAnotherOrder, second.orderId(), 10_000, "UZS");
        wiring.remedies.register(OTHER_TENANT, ofAnotherTenant, first.orderId(), 10_000, "UZS");
        wiring.remedies.register(TENANT, mine, first.orderId(), 10_000, "UZS");

        for (UUID refused : List.of(ofAnotherOrder, ofAnotherTenant, UUID.randomUUID())) {
            assertThatThrownBy(() -> wiring.recovery.attach(
                            here, first.id(), new AttachCommand(CompensationKind.ORDER_REMEDY, refused), "alice"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }
        wiring.recovery.attach(here, first.id(), new AttachCommand(CompensationKind.ORDER_REMEDY, mine), "alice");
        assertThatThrownBy(() -> wiring.recovery.attach(
                        here, first.id(), new AttachCommand(CompensationKind.ORDER_REMEDY, mine), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName(
            "an apology code links only when it is this customer's, at this brand, and not revoked; it carries no money")
    void attachingAGrant() {
        CaseRow opened = caseOf(rate(1, LOCATION, null));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        UUID customer = OrderBoardFixtures.customerOf(TENANT);
        UUID good = UUID.randomUUID();
        UUID forAnotherCustomer = UUID.randomUUID();
        UUID forAnotherBrand = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        wiring.grants.register(TENANT, new GrantSummary(good, BRAND, customer, "ACTIVE", "RECOVERY_CASE", opened.id()));
        wiring.grants.register(
                TENANT,
                new GrantSummary(forAnotherCustomer, BRAND, UUID.randomUUID(), "ACTIVE", "OPERATOR_MANUAL", null));
        wiring.grants.register(
                TENANT, new GrantSummary(forAnotherBrand, OTHER_BRAND, customer, "ACTIVE", "OPERATOR_MANUAL", null));
        wiring.grants.register(TENANT, new GrantSummary(revoked, BRAND, customer, "REVOKED", "OPERATOR_MANUAL", null));

        for (UUID refused : List.of(forAnotherCustomer, forAnotherBrand, revoked, UUID.randomUUID())) {
            assertThatThrownBy(() -> wiring.recovery.attach(
                            here, opened.id(), new AttachCommand(CompensationKind.BENEFIT_GRANT, refused), "alice"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }
        var link = wiring.recovery.attach(
                here, opened.id(), new AttachCommand(CompensationKind.BENEFIT_GRANT, good), "alice");
        assertThat(link.amountMinor()).isNull();
        assertThat(link.currency()).isNull();
        assertThat(link.orderId()).isEqualTo(opened.orderId());
        assertThat(wiring.caseStore.hasCompensation(TENANT, opened.id())).isTrue();
    }

    // ================================================================== the facts

    @Test
    @DisplayName(
            "the day-close source reports a case on the day it opened and the day it closed, with the worker's marks and the copied money")
    void theFactSourceCountsACaseOnItsOwnDates() {
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        Submission review = rate(1, LOCATION, "cold");
        CaseRow opened = caseOf(review);
        UUID remedy = UUID.randomUUID();
        wiring.remedies.register(TENANT, remedy, opened.orderId(), 30_000, "UZS");

        Instant dayOneStart = Instant.parse("2026-10-08T00:00:00Z");
        Instant dayTwoStart = Instant.parse("2026-10-09T00:00:00Z");
        Instant dayThreeStart = Instant.parse("2026-10-10T00:00:00Z");
        assertThat(wiring.factSource.touchedBetween(TENANT, dayOneStart, dayTwoStart))
                .extracting(CaseFact::caseId)
                .containsExactly(opened.id());

        clock.set(Instant.parse("2026-10-09T09:00:00Z"));
        wiring.recovery.markBreaches(10);
        CaseRow started = wiring.recovery.transition(
                here, opened.id(), new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()), "alice");
        wiring.recovery.attach(here, opened.id(), new AttachCommand(CompensationKind.ORDER_REMEDY, remedy), "alice");
        clock.advance(Duration.ofHours(1));
        wiring.recovery.transition(
                here,
                opened.id(),
                new TransitionCommand(CaseState.RESOLVED, null, CaseOutcome.REMEDY_GRANTED, started.version()),
                "alice");

        assertThat(wiring.factSource.touchedBetween(TENANT, dayTwoStart, dayThreeStart))
                .as("day two: the day it closed")
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.caseId()).isEqualTo(opened.id());
                    assertThat(fact.state()).isEqualTo("RESOLVED");
                    assertThat(fact.outcomeCode()).isEqualTo("REMEDY_GRANTED");
                    assertThat(fact.rating()).isEqualTo(1);
                    assertThat(fact.openedAt()).isEqualTo(START);
                    assertThat(fact.closedAt()).isEqualTo(Instant.parse("2026-10-09T10:00:00Z"));
                    assertThat(fact.firstResponseBreached())
                            .as("the worker marked it before anyone answered")
                            .isTrue();
                    assertThat(fact.resolutionBreached()).isFalse();
                    assertThat(fact.compensationCount()).isEqualTo(1);
                    assertThat(fact.remedyMoneyMinor()).isEqualTo(30_000L);
                    assertThat(fact.remedyCurrency()).isEqualTo("UZS");
                });
        assertThat(wiring.factSource.touchedBetween(TENANT, dayOneStart, dayTwoStart))
                .as("and still the day it opened")
                .extracting(CaseFact::caseId)
                .containsExactly(opened.id());
        assertThat(wiring.factSource.touchedBetween(TENANT, dayThreeStart, dayThreeStart.plus(Duration.ofDays(1))))
                .isEmpty();
        assertThat(wiring.factSource.touchedBetween(OTHER_TENANT, dayOneStart, dayThreeStart))
                .as("another tenant's window sees none of it")
                .isEmpty();
    }

    // ==================================================================== fixtures

    /** Submits a review the way the storefront does, on a fresh completed order of the named branch. */
    private Submission rate(int rating, UUID location, @Nullable String comment) {
        String seed = "RC-" + (++orders);
        UUID orderId = fixtures.insertOrder(
                OrderBoardFixtures.order(seed).at(TENANT, BRAND, location).status("COMPLETED"));
        return wiring.submission.submit(TENANT, BRAND, orderId, OrderBoardFixtures.customerOf(TENANT), rating, comment);
    }

    private CaseRow caseOf(Submission review) {
        return wiring.caseStore.findByReview(TENANT, review.id()).orElseThrow();
    }

    /** A case in {@code state}, reached the way an operator reaches it. */
    private CaseRow caseIn(CaseState state) {
        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        CaseRow current = caseOf(rate(1, LOCATION, null));
        if (state == CaseState.NEW) {
            return current;
        }
        current = wiring.recovery.transition(
                here,
                current.id(),
                new TransitionCommand(CaseState.IN_PROGRESS, null, null, current.version()),
                "alice");
        return switch (state) {
            case IN_PROGRESS -> current;
            case RESOLVED ->
                wiring.recovery.transition(
                        here,
                        current.id(),
                        new TransitionCommand(CaseState.RESOLVED, null, CaseOutcome.OFFLINE_GESTURE, current.version()),
                        "alice");
            case CLOSED_UNRESOLVED ->
                wiring.recovery.transition(
                        here,
                        current.id(),
                        new TransitionCommand(
                                CaseState.CLOSED_UNRESOLVED, null, CaseOutcome.CUSTOMER_DECLINED, current.version()),
                        "alice");
            case NEW -> current;
        };
    }

    /** A real staff member row (the case's foreign key needs one) and the card the directory answers with. */
    private UUID staff(String subject, String reference, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.staff_members (id, tenant_id, principal_subject, display_reference, employment_status)
                VALUES (:id, :t, :subject, :reference, 'ACTIVE')
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("subject", subject)
                .param("reference", reference)
                .update();
        wiring.staffCards.register(id, subject, reference, name, "ACTIVE");
        wiring.staffDirectory.member(TENANT, subject, id);
        return id;
    }
}
