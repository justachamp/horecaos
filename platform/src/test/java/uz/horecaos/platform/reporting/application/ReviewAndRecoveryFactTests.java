package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.MetricDefinition;
import uz.horecaos.platform.reporting.domain.MetricRegistry;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.reporting.web.ReviewReportController;
import uz.horecaos.platform.reviews.ReviewsWiring;
import uz.horecaos.platform.reviews.ReviewsWiring.MutableClock;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.AttachCommand;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.Scope;
import uz.horecaos.platform.reviews.application.RecoveryCaseService.TransitionCommand;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService.Submission;
import uz.horecaos.platform.reviews.domain.CaseOutcome;
import uz.horecaos.platform.reviews.domain.CaseState;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.domain.CompensationKind;
import uz.horecaos.platform.reviews.domain.ModerationReason;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.CaseRow;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0159 / ADR 0043 (rows 5.4 and 5.5): {@code reporting.fact_review}, {@code fact_review_tag} and
 * {@code fact_recovery_case}, the producers inside {@link DayCloseService#close}, the recut over them, the
 * report endpoint, and the one property the record names: the public page's average and the metric are the
 * same number.
 *
 * <p>The reviews side is the real one -- reviews and cases are made through {@code ReviewSubmissionService}
 * and {@code RecoveryCaseService} and handed to reporting by the real {@code JdbcReviewFactSource} -- so
 * what is proven is what reporting does with what the port truly returns, not with a stand-in. The clock
 * is the test's and every case lifetime advances it.
 */
class ReviewAndRecoveryFactTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-d000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-d000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-d000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-d000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-d000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f10-d000-7000-8000-0000000000c2");

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);
    /** 15:00 in Tashkent on {@link #DAY}. */
    private static final Instant START = Instant.parse("2026-10-08T10:00:00Z");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OrderBoardFixtures fixtures;
    private MutableClock clock;
    private ReviewsWiring wiring;
    private JdbcReportingStore store;
    private BusinessDayService businessDays;
    private DayCloseService close;
    private ReportQueryService queries;
    private int orders;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        fixtures = new OrderBoardFixtures(jdbc);
        jdbc.sql("TRUNCATE TABLE reporting.aggregate_divergences, reporting.close_runs")
                .update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.fact_order_line, reporting.fact_order_tender,
                    reporting.fact_refund, reporting.agg_branch_day, reporting.agg_sla_bucket_day,
                    reporting.business_day_policies, reporting.metric_definitions, reporting.fact_call_hour,
                    reporting.fact_promotion_redemption, reporting.fact_review, reporting.fact_review_tag,
                    reporting.fact_recovery_case
                """).update();
        jdbc.sql("TRUNCATE TABLE reviews.order_reviews CASCADE").update();
        jdbc.sql("TRUNCATE TABLE reviews.feedback_tags CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE iam.staff_members CASCADE").update();
        fixtures.clean();
        fixtures.tenant(TENANT, "review-facts", BRAND, LOCATION);
        fixtures.tenant(OTHER_TENANT, "review-facts-other", OTHER_BRAND, OTHER_LOCATION);
        orders = 0;

        clock = MutableClock.at(START);
        wiring = ReviewsWiring.withFlagsOn(jdbc, clock)
                .with("feedback.public_visibility", "RATING_ONLY")
                .with("feedback.public_minimum_reviews", 1);
        store = new JdbcReportingStore(jdbc);
        businessDays = new BusinessDayService(store);
        close = new DayCloseService(
                store,
                businessDays,
                new SubjectPseudonym(wiring.protection),
                clock,
                null,
                wiring.factSource,
                wiring.factSource);
        queries = new ReportQueryService(store, businessDays, clock);
        new MetricDefinitionSynchronizer(store).synchronizeAll();
    }

    // ============================================================ the review facts

    @Test
    @DisplayName("day close writes one fact per review and one per tag, with the customer as a pseudonym and"
            + " no wording of any kind")
    void closeWritesOneFactPerReviewAndPerTag() {
        Map<String, UUID> tags = starterPack();
        Submission cold = rate(
                1,
                "The soup was cold and the box was wet",
                List.of(tags.get("food_cold"), tags.get("delivery_late")),
                false);
        Submission tasty = rate(5, null, List.of(tags.get("food_tasty")), false);
        rate(3, null, List.of(), false);

        close.close(TENANT, DAY);

        List<Map<String, Object>> reviews =
                jdbc.sql("""
                SELECT review_id, business_date, brand_id, location_id, order_id, customer_subject_hash,
                       rating, has_comment, tag_count, metric_calculation_version
                  FROM reporting.fact_review WHERE tenant_id = :t ORDER BY rating
                """).param("t", TENANT).query().listOfRows();
        assertThat(reviews).hasSize(3);
        assertThat(reviews).extracting(r -> r.get("rating")).containsExactly(1, 3, 5);
        Map<String, Object> coldRow = reviews.get(0);
        assertThat(coldRow.get("review_id")).isEqualTo(cold.id());
        assertThat(coldRow.get("has_comment")).isEqualTo(true);
        assertThat(coldRow.get("tag_count")).isEqualTo(2);
        assertThat(String.valueOf(coldRow.get("business_date"))).isEqualTo(DAY.toString());
        assertThat(coldRow.get("brand_id")).isEqualTo(BRAND);
        assertThat(coldRow.get("location_id")).isEqualTo(LOCATION);
        assertThat(coldRow.get("metric_calculation_version")).isEqualTo(MetricRegistry.CALCULATION_VERSION);
        assertThat(reviews.get(2).get("has_comment")).as("no words, no comment").isEqualTo(false);
        assertThat(reviews.get(2).get("review_id")).isEqualTo(tasty.id());
        assertThat(reviews)
                .as("the customer is the keyed ADR 0029 pseudonym, never the account id")
                .allSatisfy(row -> {
                    assertThat(String.valueOf(row.get("customer_subject_hash")))
                            .doesNotContain(
                                    OrderBoardFixtures.customerOf(TENANT).toString());
                    assertThat(row.get("customer_subject_hash")).isNotNull();
                });
        assertThat(reviews.get(0).get("customer_subject_hash"))
                .as("the same function reporting applies to every other fact")
                .isEqualTo(new SubjectPseudonym(wiring.protection).of(TENANT, OrderBoardFixtures.customerOf(TENANT)));

        List<Map<String, Object>> tagRows =
                jdbc.sql("""
                SELECT review_id, category, sentiment, business_date, brand_id, location_id
                  FROM reporting.fact_review_tag WHERE tenant_id = :t ORDER BY category
                """).param("t", TENANT).query().listOfRows();
        assertThat(tagRows).hasSize(3);
        assertThat(tagRows)
                .extracting(row -> row.get("category") + "/" + row.get("sentiment") + "/" + row.get("review_id"))
                .containsExactly(
                        "DELIVERY_TIME/NEGATIVE/" + cold.id(),
                        "FOOD/NEGATIVE/" + cold.id(),
                        "FOOD/POSITIVE/" + tasty.id());
    }

    @Test
    @DisplayName("closing the same day again replaces the rows instead of doubling them")
    void closingTheSameDayTwiceIsIdempotent() {
        Map<String, UUID> tags = starterPack();
        rate(2, "slow", List.of(tags.get("delivery_late")), false);
        rate(4, null, List.of(), false);

        close.close(TENANT, DAY);
        close.close(TENANT, DAY);

        assertThat(count("reporting.fact_review")).isEqualTo(2L);
        assertThat(count("reporting.fact_review_tag")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a boundary change that moves a review across the seam moves its fact, and the close does not abort")
    void aBoundaryChangeMovesTheFactInsteadOfViolatingItsKey() {
        rate(4, null, List.of(), false);
        close.close(TENANT, DAY);
        assertThat(factDates()).containsExactly(DAY);

        // The business day now begins at 16:00. The review, written at 15:00, belongs to the day before.
        businessDays.setBoundary(TENANT, new BusinessDayBoundary(TASHKENT, LocalTime.of(16, 0), 2), DAY, null);

        assertThatCode(() -> close.close(TENANT, DAY.minusDays(1)))
                .as("the same review id inserted again under the previous day used to violate its primary key")
                .doesNotThrowAnyException();
        assertThat(factDates()).as("one row, on the day it now belongs to").containsExactly(DAY.minusDays(1));
    }

    @Test
    @DisplayName("every review counts, whatever became of its comment: shared or not, approved, hidden or erased")
    void everyReviewCountsWhateverBecameOfItsComment() {
        UUID consenting = rate(5, "Lovely plov", List.of(), true).id();
        UUID hidden = rate(1, "Awful, ask for Bobur Rakhimov", List.of(), true).id();
        UUID unshared = rate(2, "Not for the page", List.of(), false).id();
        UUID erased = rate(4, "Call me on 998901234567", List.of(), false).id();

        wiring.moderation.decide(
                TENANT, BRAND, consenting, CommentState.PUBLISHED, null, versionOf(consenting), "manager");
        wiring.moderation.decide(
                TENANT,
                BRAND,
                hidden,
                CommentState.HIDDEN,
                ModerationReason.NAMES_AN_INDIVIDUAL,
                versionOf(hidden),
                "manager");
        jdbc.sql("UPDATE reviews.order_reviews SET comment_protected = NULL WHERE id = :id")
                .param("id", erased)
                .update();
        assertThat(unshared).isNotNull();

        close.close(TENANT, DAY);

        assertThat(count("reporting.fact_review"))
                .as("the average that leaves out the unwelcome ones is not an average")
                .isEqualTo(4L);
        assertThat(jdbc.sql("SELECT sum(rating) FROM reporting.fact_review")
                        .query(Long.class)
                        .single())
                .isEqualTo(12L);
        assertThat(jdbc.sql("SELECT has_comment FROM reporting.fact_review WHERE rating = 4")
                        .query(Boolean.class)
                        .single())
                .as("an erased comment is a review without words, still a review")
                .isFalse();
    }

    @Test
    @DisplayName("no fact carries a comment, a tag's wording, a name, or the customer's account id")
    void noFactCarriesWords() {
        Map<String, UUID> tags = starterPack();
        rate(1, "Cold. Rude waiter named Jasur Tashmatov. Call 998901234567", List.of(tags.get("service_rude")), true);
        close.close(TENANT, DAY);

        String everything = new StringBuilder()
                .append(jdbc.sql("SELECT t::text FROM reporting.fact_review t")
                        .query(String.class)
                        .list())
                .append(jdbc.sql("SELECT t::text FROM reporting.fact_review_tag t")
                        .query(String.class)
                        .list())
                .toString();
        assertThat(everything)
                .doesNotContain("Cold")
                .doesNotContain("Jasur")
                .doesNotContain("998901234567")
                .doesNotContain(OrderBoardFixtures.customerOf(TENANT).toString());
        assertThat(jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                         WHERE table_schema = 'reporting'
                           AND table_name IN ('fact_review', 'fact_review_tag', 'fact_recovery_case')
                           AND (column_name ILIKE '%comment%' OR column_name ILIKE '%note%'
                                OR column_name ILIKE '%label%' OR column_name ILIKE '%courier%'
                                OR column_name ILIKE '%name%')
                        """).query(String.class).list())
                .as("the only column that could hold words says whether words exist")
                .containsExactly("has_comment");
    }

    // ============================================================ the live number is the metric

    @Test
    @DisplayName("the public page's average and the reviews.average_rating metric are the same number, half-up,"
            + " and hiding a comment moves neither")
    void theLiveAverageIsTheMetric() {
        UUID first = rate(2, "meh", List.of(), true).id();
        rate(2, null, List.of(), false);
        rate(2, null, List.of(), false);
        rate(3, null, List.of(), false);
        // 9 over 4 is 2.25: half-up gives 2.3 and banker's rounding would give 2.2, so this is the pair
        // of figures on which two honest-looking implementations disagree.

        close.close(TENANT, DAY);

        var live = wiring.publicReviews.summary(TENANT, BRAND);
        BigDecimal metric = reportRow().average();
        assertThat(live.ready()).isTrue();
        assertThat(live.average()).isEqualByComparingTo(new BigDecimal("2.3"));
        assertThat(metric).as("the report's figure for the same reviews").isEqualByComparingTo(live.average());
        assertThat(reportRow().reviews()).isEqualTo(live.count());
        assertThat(reportRow().distribution()).isEqualTo(live.distribution());

        wiring.moderation.decide(TENANT, BRAND, first, CommentState.PUBLISHED, null, versionOf(first), "manager");
        wiring.moderation.decide(
                TENANT,
                BRAND,
                first,
                CommentState.HIDDEN,
                ModerationReason.SPAM_OR_UNRELATED,
                versionOf(first),
                "manager");
        close.close(TENANT, DAY);
        wiring.cache.evictNow(TENANT, BRAND);

        assertThat(wiring.publicReviews.summary(TENANT, BRAND).average())
                .as("a rating is not words: hiding a comment leaves the live average where it was")
                .isEqualByComparingTo(new BigDecimal("2.3"));
        assertThat(reportRow().average()).isEqualByComparingTo(new BigDecimal("2.3"));
    }

    @Test
    @DisplayName("the tag counts are by category and sentiment, per location, with no wording")
    void theTagCountsAreByCategoryAndSentiment() {
        Map<String, UUID> tags = starterPack();
        rate(1, null, List.of(tags.get("food_cold"), tags.get("delivery_late")), false);
        rate(2, null, List.of(tags.get("food_cold")), false);
        rate(5, null, List.of(tags.get("food_tasty")), false);

        close.close(TENANT, DAY);

        var report = Objects.requireNonNull(new ReviewReportController(queries)
                .summary(TENANT, DAY, DAY, null)
                .getBody());
        Map<String, Long> byDimension = new HashMap<>();
        report.tags().forEach(tag -> byDimension.merge(tag.category() + "/" + tag.sentiment(), tag.tags(), Long::sum));
        assertThat(byDimension)
                .containsEntry("FOOD/NEGATIVE", 2L)
                .containsEntry("FOOD/POSITIVE", 1L)
                .containsEntry("DELIVERY_TIME/NEGATIVE", 1L);
        assertThat(byDimension).hasSize(3);
    }

    @Test
    @DisplayName("one tenant's reviews never reach another tenant's report")
    void tenantsAreIsolated() {
        rate(1, null, List.of(), false);
        close.close(TENANT, DAY);
        close.close(OTHER_TENANT, DAY);

        assertThat(Objects.requireNonNull(new ReviewReportController(queries)
                                .summary(OTHER_TENANT, DAY, DAY, null)
                                .getBody())
                        .rows())
                .isEmpty();
        assertThat(Objects.requireNonNull(new ReviewReportController(queries)
                                .summary(TENANT, DAY, DAY, null)
                                .getBody())
                        .rows())
                .hasSize(1);
    }

    // ============================================================ the case fact

    @Test
    @DisplayName("a case opened on one day and closed on the next is one row, rewritten and not re-filed,"
            + " counted opened on the first day and closed on the second")
    void aCaseIsOpenedOnOneDayAndClosedOnAnother() {
        UUID alice = staff("alice", "S-0001", "Aziza Karimova");
        Submission review = rate(1, "cold", List.of(), false);
        CaseRow opened = caseOf(review);
        close.close(TENANT, DAY);

        assertThat(caseFacts()).singleElement().satisfies(row -> {
            assertThat(String.valueOf(row.get("opened_business_date"))).isEqualTo(DAY.toString());
            assertThat(row.get("closed_business_date")).isNull();
            assertThat(row.get("state")).isEqualTo("NEW");
        });

        Scope here = Scope.location(TENANT, BRAND, LOCATION);
        clock.advance(Duration.ofHours(20));
        CaseRow started = wiring.recovery.transition(
                here, opened.id(), new TransitionCommand(CaseState.IN_PROGRESS, null, null, opened.version()), "alice");
        UUID remedy = UUID.randomUUID();
        wiring.remedies.register(TENANT, remedy, opened.orderId(), 45_000, "UZS");
        wiring.recovery.attach(here, opened.id(), new AttachCommand(CompensationKind.ORDER_REMEDY, remedy), "alice");
        clock.advance(Duration.ofHours(1));
        wiring.recovery.transition(
                here,
                opened.id(),
                new TransitionCommand(CaseState.RESOLVED, null, CaseOutcome.REMEDY_GRANTED, started.version()),
                "alice");
        assertThat(alice).isNotNull();

        LocalDate next = DAY.plusDays(1);
        close.close(TENANT, next);

        assertThat(caseFacts()).as("one row, rewritten").singleElement().satisfies(row -> {
            assertThat(String.valueOf(row.get("opened_business_date"))).isEqualTo(DAY.toString());
            assertThat(String.valueOf(row.get("closed_business_date"))).isEqualTo(next.toString());
            assertThat(row.get("state")).isEqualTo("RESOLVED");
            assertThat(row.get("outcome_code")).isEqualTo("REMEDY_GRANTED");
            assertThat(row.get("seconds_to_first_response")).isEqualTo(20L * 3600);
            assertThat(row.get("seconds_to_close")).isEqualTo(21L * 3600);
            assertThat(row.get("compensation_count")).isEqualTo(1);
            assertThat(row.get("remedy_money_minor")).isEqualTo(45_000L);
            assertThat(row.get("currency")).isEqualTo("UZS");
            assertThat(row.get("rating")).isEqualTo(1);
        });

        assertThat(close.recut(TENANT, DAY).divergences())
                .as("the opening day still counts the case as opened, and the later close is not a divergence")
                .isEmpty();
        assertThat(close.recut(TENANT, next).divergences()).isEmpty();

        var report = Objects.requireNonNull(new ReviewReportController(queries)
                .recovery(TENANT, DAY, next, null)
                .getBody());
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.opened()).isEqualTo(1);
            assertThat(row.closed()).isEqualTo(1);
            assertThat(row.firstResponseMeanSeconds()).isEqualTo(72_000L);
            assertThat(row.firstResponseP90Seconds()).isEqualTo(72_000L);
            assertThat(row.closeMeanSeconds()).isEqualTo(75_600L);
            assertThat(row.compensations()).isEqualTo(1);
            assertThat(row.breachRateBasisPoints())
                    .as("answered late, but the sweeper never marked it: the mark, not a read-time calculation")
                    .isZero();
        });
        assertThat(report.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.outcomeCode()).isEqualTo("REMEDY_GRANTED");
            assertThat(outcome.cases()).isEqualTo(1);
        });
        assertThat(report.remedies()).singleElement().satisfies(remedyRow -> {
            assertThat(remedyRow.currency()).isEqualTo("UZS");
            assertThat(remedyRow.amountMinor()).isEqualTo(45_000L);
        });

        assertThat(Objects.requireNonNull(new ReviewReportController(queries)
                                .recovery(TENANT, DAY, DAY, null)
                                .getBody())
                        .rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.opened()).isEqualTo(1);
                    assertThat(row.closed()).as("not closed on its opening day").isZero();
                });
    }

    @Test
    @DisplayName("the breach flags are the sweeper's mark: a case closed before the sweeper reached it is not breached")
    void theBreachFlagIsTheSweepersMark() {
        UUID alice = staff("alice", "S-0001", "Aziza Karimova");
        CaseRow slow = caseOf(rate(1, "slow", List.of(), false));
        CaseRow quick = caseOf(rate(2, "slow too", List.of(), false));
        Scope here = Scope.location(TENANT, BRAND, LOCATION);

        clock.advance(Duration.ofMinutes(241));
        // The first case passes its first-response clock and is marked; the second is answered at the
        // same instant but never reached by the sweeper before it is closed.
        CaseRow answered = wiring.recovery.transition(
                here, quick.id(), new TransitionCommand(CaseState.IN_PROGRESS, null, null, quick.version()), "alice");
        wiring.recovery.transition(
                here,
                quick.id(),
                new TransitionCommand(
                        CaseState.CLOSED_UNRESOLVED, null, CaseOutcome.CUSTOMER_UNREACHABLE, answered.version()),
                "alice");
        assertThat(wiring.recovery.markBreaches(100))
                .as("only the case nobody answered is marked")
                .isEqualTo(1);
        assertThat(alice).isNotNull();
        assertThat(slow.id()).isNotNull();

        close.close(TENANT, DAY);

        var report = Objects.requireNonNull(new ReviewReportController(queries)
                .recovery(TENANT, DAY, DAY, null)
                .getBody());
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.opened()).isEqualTo(2);
            assertThat(row.breached()).isEqualTo(1);
            assertThat(row.breachRateBasisPoints()).isEqualTo(5_000L);
        });
    }

    @Test
    @DisplayName("a recut reports a review that arrived after the day closed instead of rewriting the stored day")
    void aRecutReportsALateReview() {
        rate(4, null, List.of(), false);
        close.close(TENANT, DAY);
        assertThat(close.recut(TENANT, DAY).divergences()).isEmpty();

        clock.advance(Duration.ofMinutes(30));
        rate(1, null, List.of(), false);

        var divergences = close.recut(TENANT, DAY).divergences();
        assertThat(divergences)
                .extracting(d -> d.metricName() + ":" + d.storedValue() + "->" + d.recutValue())
                .contains("reviews.count:1->2", "reviews.rating_sum:4->5");
        assertThat(count("reporting.fact_review"))
                .as("nothing stored was changed: the recut compares and never writes")
                .isEqualTo(1L);
    }

    // ============================================================ the registry

    @Test
    @DisplayName("the nine metrics are registered, provisional, with no courier dimension, and the remedy money is"
            + " declared unbuilt rather than zero")
    void theMetricsAreRegistered() {
        List<String> built = List.of(
                "reviews.count.v1",
                "reviews.average_rating.v1",
                "reviews.tagged.v1",
                "recovery.cases_opened.v1",
                "recovery.cases_closed.v1",
                "recovery.first_response_seconds.v1",
                "recovery.close_seconds.v1",
                "recovery.sla_breach_rate.v1");
        for (String code : built) {
            MetricDefinition metric = MetricRegistry.require(code);
            assertThat(metric.sourceAvailable()).as(code).isTrue();
            assertThat(metric.effectiveFrom()).as(code).isEqualTo(DAY);
            assertThat(metric.grain().name()).as("no courier dimension").doesNotContain("COURIER");
        }
        assertThat(MetricRegistry.require("reviews.average_rating.v1").unit().name())
                .isEqualTo("STARS");

        MetricDefinition remedy = MetricRegistry.require("recovery.remedy_money.v1");
        assertThat(remedy.sourceAvailable()).isFalse();
        assertThat(remedy.effectiveFrom()).isNull();
        assertThat(remedy.openQuestion()).contains("ADR 0038");

        assertThat(jdbc.sql("SELECT count(*) FROM reporting.metric_definitions WHERE metric_id LIKE 'reviews.%' "
                                + "OR metric_id LIKE 'recovery.%'")
                        .query(Long.class)
                        .single())
                .as("all nine were accepted by the table's own CHECK constraints, STARS included")
                .isEqualTo(9L);
    }

    // ===================================================================== fixtures

    private Map<String, UUID> starterPack() {
        Map<String, UUID> ids = new HashMap<>();
        wiring.tags
                .importStarterPack(TENANT, "owner")
                .forEach(tag -> ids.put(tag.row().code(), tag.row().id()));
        return ids;
    }

    private Submission rate(int rating, @Nullable String comment, List<UUID> tagIds, boolean consent) {
        UUID orderId = fixtures.insertOrder(OrderBoardFixtures.order("RV-" + (++orders))
                .at(TENANT, BRAND, LOCATION)
                .createdAt(Instant.parse("2026-10-01T10:00:00Z"))
                .status("COMPLETED"));
        return wiring.submission.submit(
                TENANT, BRAND, orderId, OrderBoardFixtures.customerOf(TENANT), rating, comment, tagIds, consent);
    }

    private CaseRow caseOf(Submission review) {
        return wiring.caseStore.findByReview(TENANT, review.id()).orElseThrow();
    }

    private int versionOf(UUID reviewId) {
        return jdbc.sql("SELECT version FROM reviews.review_publications WHERE review_id = :id")
                .param("id", reviewId)
                .query(Integer.class)
                .single();
    }

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

    private ReviewReportController.ReviewSummaryRowResponse reportRow() {
        var body = Objects.requireNonNull(new ReviewReportController(queries)
                .summary(TENANT, DAY, DAY, null)
                .getBody());
        assertThat(body.rows()).hasSize(1);
        return body.rows().getFirst();
    }

    private List<Map<String, Object>> caseFacts() {
        return jdbc.sql("""
                SELECT opened_business_date, closed_business_date, state, outcome_code, seconds_to_first_response,
                       seconds_to_close, compensation_count, remedy_money_minor, currency, rating
                  FROM reporting.fact_recovery_case WHERE tenant_id = :t
                """).param("t", TENANT).query().listOfRows();
    }

    private List<LocalDate> factDates() {
        List<LocalDate> dates = new ArrayList<>();
        jdbc.sql("SELECT business_date FROM reporting.fact_review WHERE tenant_id = :t ORDER BY business_date")
                .param("t", TENANT)
                .query(LocalDate.class)
                .list()
                .forEach(dates::add);
        return dates;
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }
}
