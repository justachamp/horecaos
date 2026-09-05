package uz.horecaos.platform.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderDirectory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.reviews.application.ReviewQueryService;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService.Submission;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0071: a customer rating their own completed order, once, read back by
 * themselves and by an authorized operator.
 *
 * <p>Against real PostgreSQL, for the same reason every ownership and
 * uniqueness claim in this platform is: "once per order" is a property of
 * {@code uq_order_review_one_per_order}, and "the caller's own order" is a
 * property of a query with a tenant and an account predicate, neither of
 * which a mock can stand in for. Every encryption assertion reads the raw
 * stored column, not merely the service's own claim about what it wrote —
 * the 2026-08-26 audit's lesson that a guard checking the adjacent quantity
 * can stay green while the one that matters breaks.
 */
class ReviewSubmissionAndReadTests {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID BRAND_A = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-09-05T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReviewStore reviewStore;
    private OrderDirectory orderDirectory;
    private FieldProtection protection;

    private ReviewSubmissionService submission;
    private ReviewQueryService query;

    private UUID locationId;
    private UUID channelId;
    private UUID publicationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for review tests");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE reviews.order_reviews CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        reviewStore = new JdbcReviewStore(jdbc);
        JdbcOrderStore orderStore = new JdbcOrderStore(jdbc);
        orderDirectory = new JdbcOrderDirectory(orderStore);

        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get, CLOCK);
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));
        submission = new ReviewSubmissionService(reviewStore, orderDirectory, protection, CLOCK);
        query = new ReviewQueryService(reviewStore, protection);

        Seed seed = seedTenancy(TENANT_A, BRAND_A);
        locationId = seed.locationId();
        channelId = seed.channelId();
        publicationId = seed.publicationId();
    }

    // ------------------------------------------------------------- submission

    @Test
    @DisplayName("a customer with a COMPLETED order of their own submits a review successfully")
    void completedOrderCanBeReviewed() {
        UUID customer = newCustomer(TENANT_A);
        UUID orderId = order(TENANT_A, BRAND_A, customer, "COMPLETED");

        Submission result = submission.submit(TENANT_A, BRAND_A, orderId, customer, 5, "Great food, fast delivery");

        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.comment()).isEqualTo("Great food, fast delivery");
    }

    @Test
    @DisplayName("a second submission against the same order is refused, and the row count stays one")
    void secondSubmissionIsRefused() {
        UUID customer = newCustomer(TENANT_A);
        UUID orderId = order(TENANT_A, BRAND_A, customer, "COMPLETED");
        submission.submit(TENANT_A, BRAND_A, orderId, customer, 4, null);

        assertThatThrownBy(() -> submission.submit(TENANT_A, BRAND_A, orderId, customer, 1, "actually terrible"))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

        Long count = jdbc.sql("SELECT count(*) FROM reviews.order_reviews WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        assertThat(count).as("the refused second attempt left no second row").isEqualTo(1L);

        Integer rating = jdbc.sql("SELECT rating FROM reviews.order_reviews WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        assertThat(rating)
                .as("the original rating is untouched by the refused overwrite attempt")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("a customer cannot submit a review for another customer's order")
    void cannotReviewSomeoneElsesOrder() {
        UUID owner = newCustomer(TENANT_A);
        UUID stranger = newCustomer(TENANT_A);
        UUID orderId = order(TENANT_A, BRAND_A, owner, "COMPLETED");

        assertThatThrownBy(() -> submission.submit(TENANT_A, BRAND_A, orderId, stranger, 5, "not mine to rate"))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        assertThat(reviewStore.findByOrder(TENANT_A, orderId))
                .as("a refused cross-account attempt leaves no row behind")
                .isEmpty();
    }

    @Test
    @DisplayName("a customer cannot submit a review for their own order before it is COMPLETED")
    void cannotReviewBeforeCompletion() {
        UUID customer = newCustomer(TENANT_A);
        UUID orderId = order(TENANT_A, BRAND_A, customer, "RECEIVED");

        assertThatThrownBy(() -> submission.submit(TENANT_A, BRAND_A, orderId, customer, 5, "too early"))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("a rating outside 1..5 is refused before anything is written")
    void ratingMustBeInRange() {
        UUID customer = newCustomer(TENANT_A);
        UUID orderId = order(TENANT_A, BRAND_A, customer, "COMPLETED");

        assertThatThrownBy(() -> submission.submit(TENANT_A, BRAND_A, orderId, customer, 6, null))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(reviewStore.findByOrder(TENANT_A, orderId)).isEmpty();
    }

    // ------------------------------------------------------------ tenant isolation

    @Test
    @DisplayName("tenant B cannot read tenant A's reviews, and a cross-tenant order id does not resolve at all")
    void tenantIsolationHolds() {
        UUID brandB = UUID.randomUUID();
        seedTenancy(TENANT_B, brandB);
        // Tenant B's own location/channel/publication are seeded above but
        // deliberately unused here — this test never creates an order for
        // tenant B, only proves tenant A's stays unreachable from it.

        UUID customerA = newCustomer(TENANT_A);
        UUID orderA = order(TENANT_A, BRAND_A, customerA, "COMPLETED");
        submission.submit(TENANT_A, BRAND_A, orderA, customerA, 3, "tenant A's own comment");

        // Tenant B's own customer, holding tenant A's real order id, resolves to
        // nothing — OrderDirectory#summary is itself tenant-scoped.
        UUID customerB = newCustomer(TENANT_B);
        assertThatThrownBy(() -> submission.submit(TENANT_B, brandB, orderA, customerB, 5, "cross-tenant"))
                .isInstanceOf(ApiException.class)
                .extracting(t -> ((ApiException) t).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        assertThat(query.list(TENANT_B, brandB, null, null, null, null, null, null, 50))
                .as("tenant B's brand-scoped read never returns tenant A's row")
                .isEmpty();
        assertThat(query.list(TENANT_A, BRAND_A, null, null, null, null, null, null, 50))
                .hasSize(1);
    }

    // ------------------------------------------------------------ encryption

    @Test
    @DisplayName("the comment is never stored as plaintext, and round-trips through decryption correctly")
    void commentIsEnvelopeEncrypted() {
        UUID customer = newCustomer(TENANT_A);
        UUID orderId = order(TENANT_A, BRAND_A, customer, "COMPLETED");
        submission.submit(TENANT_A, BRAND_A, orderId, customer, 2, "the courier was rude to my neighbour");

        String storedRaw = jdbc.sql("SELECT comment_protected FROM reviews.order_reviews WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();

        assertThat(storedRaw)
                .as("the raw column never contains the plaintext comment")
                .doesNotContain("courier")
                .doesNotContain("neighbour");

        List<ReviewQueryService.ReviewView> views =
                query.list(TENANT_A, BRAND_A, null, null, null, null, null, null, 50);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).comment()).isEqualTo("the courier was rude to my neighbour");
    }

    @Test
    @DisplayName("a review with no comment stores a null comment column, not an empty encrypted string")
    void ratingOnlyReviewHasNoComment() {
        UUID customer = newCustomer(TENANT_A);
        UUID orderId = order(TENANT_A, BRAND_A, customer, "COMPLETED");
        submission.submit(TENANT_A, BRAND_A, orderId, customer, 5, null);

        String storedRaw = jdbc.sql("SELECT comment_protected FROM reviews.order_reviews WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .orElse(null);
        assertThat(storedRaw).isNull();
    }

    // ------------------------------------------------------------ operations read

    @Test
    @DisplayName("the operations screen filters by location and rating, and the summary matches the filtered list")
    void operationsFilteringAndSummary() {
        UUID locationTwo = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'SECOND', 'second', 'Second', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", locationTwo)
                .param("tenantId", TENANT_A)
                .param("brandId", BRAND_A)
                .update();

        UUID customerOne = newCustomer(TENANT_A);
        UUID customerTwo = newCustomer(TENANT_A);
        UUID orderOne = order(TENANT_A, BRAND_A, customerOne, "COMPLETED");
        UUID orderTwo = orderAt(TENANT_A, BRAND_A, locationTwo, customerTwo, "COMPLETED");

        submission.submit(TENANT_A, BRAND_A, orderOne, customerOne, 5, "loved it");
        submission.submit(TENANT_A, BRAND_A, orderTwo, customerTwo, 1, "cold food");

        List<ReviewQueryService.ReviewView> atLocationOne =
                query.list(TENANT_A, BRAND_A, locationId, null, null, null, null, null, 50);
        assertThat(atLocationOne)
                .extracting(ReviewQueryService.ReviewView::rating)
                .containsExactly(5);

        List<ReviewQueryService.ReviewView> highRatingOnly =
                query.list(TENANT_A, BRAND_A, null, 4, null, null, null, null, 50);
        assertThat(highRatingOnly)
                .extracting(ReviewQueryService.ReviewView::orderId)
                .containsExactly(orderOne);

        JdbcReviewStore.Summary summary = query.summary(TENANT_A, BRAND_A, null, null, null);
        assertThat(summary.reviewCount()).isEqualTo(2);
        assertThat(summary.averageRating()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("a customer reads their own reviews and nobody else's")
    void myReviewsIsScopedToTheCaller() {
        UUID customerOne = newCustomer(TENANT_A);
        UUID customerTwo = newCustomer(TENANT_A);
        UUID orderOne = order(TENANT_A, BRAND_A, customerOne, "COMPLETED");
        UUID orderTwo = order(TENANT_A, BRAND_A, customerTwo, "COMPLETED");
        submission.submit(TENANT_A, BRAND_A, orderOne, customerOne, 4, null);
        submission.submit(TENANT_A, BRAND_A, orderTwo, customerTwo, 2, null);

        List<Submission> mine = submission.myReviews(TENANT_A, BRAND_A, customerOne, null, 50);
        assertThat(mine).extracting(Submission::orderId).containsExactly(orderOne);
    }

    @Test
    @DisplayName("a customer's own reviews page forward across a real cursor and reach a null nextCursor")
    void myReviewsCursorPagesForward() {
        // A real, advancing clock: the fixture's shared CLOCK is fixed, and three
        // submissions under one fixed instant would tie on submitted_at and fall
        // back to an id-order this test cannot predict. Keyset pagination is
        // exactly the property under test, so the timestamps must actually differ.
        MutableClock advancing = new MutableClock(NOW);
        ReviewSubmissionService orderedSubmission =
                new ReviewSubmissionService(reviewStore, orderDirectory, protection, advancing);

        UUID customer = newCustomer(TENANT_A);
        UUID orderIdOne = order(TENANT_A, BRAND_A, customer, "COMPLETED");
        orderedSubmission.submit(TENANT_A, BRAND_A, orderIdOne, customer, 5, null);
        advancing.advance(Duration.ofSeconds(1));
        UUID orderIdTwo = order(TENANT_A, BRAND_A, customer, "COMPLETED");
        orderedSubmission.submit(TENANT_A, BRAND_A, orderIdTwo, customer, 4, null);
        advancing.advance(Duration.ofSeconds(1));
        UUID orderIdThree = order(TENANT_A, BRAND_A, customer, "COMPLETED");
        orderedSubmission.submit(TENANT_A, BRAND_A, orderIdThree, customer, 3, null);

        List<Submission> firstPage = orderedSubmission.myReviews(TENANT_A, BRAND_A, customer, null, 2);
        assertThat(firstPage).extracting(Submission::orderId).containsExactly(orderIdThree, orderIdTwo);

        List<Submission> secondPage = orderedSubmission.myReviews(
                TENANT_A, BRAND_A, customer, firstPage.get(1).id(), 2);
        assertThat(secondPage).extracting(Submission::orderId).containsExactly(orderIdOne);
    }

    @Test
    @DisplayName("a cursor naming somebody else's review is refused rather than leaking their page position")
    void myReviewsRejectsAnotherCustomersCursor() {
        UUID customerOne = newCustomer(TENANT_A);
        UUID customerTwo = newCustomer(TENANT_A);
        UUID orderOne = order(TENANT_A, BRAND_A, customerOne, "COMPLETED");
        UUID orderTwo = order(TENANT_A, BRAND_A, customerTwo, "COMPLETED");
        Submission ownedByOne = submission.submit(TENANT_A, BRAND_A, orderOne, customerOne, 5, null);
        submission.submit(TENANT_A, BRAND_A, orderTwo, customerTwo, 2, null);

        assertThatThrownBy(() -> submission.myReviews(TENANT_A, BRAND_A, customerTwo, ownedByOne.id(), 50))
                .isInstanceOf(ReviewSubmissionService.UnknownCursorException.class);
    }

    @Test
    @DisplayName("the operations list pages forward the same way, and rejects a cursor from another brand")
    void operationsListCursorPagesForwardAndRejectsForeignCursors() {
        // See myReviewsCursorPagesForward's own comment: tied submitted_at values
        // under the shared fixed CLOCK would make the keyset order depend on
        // random id comparison rather than submission order, so this test needs
        // its own advancing clock too.
        MutableClock advancing = new MutableClock(NOW);
        ReviewSubmissionService orderedSubmission =
                new ReviewSubmissionService(reviewStore, orderDirectory, protection, advancing);

        UUID customerOne = newCustomer(TENANT_A);
        UUID customerTwo = newCustomer(TENANT_A);
        UUID orderOne = order(TENANT_A, BRAND_A, customerOne, "COMPLETED");
        UUID orderTwo = order(TENANT_A, BRAND_A, customerTwo, "COMPLETED");
        orderedSubmission.submit(TENANT_A, BRAND_A, orderOne, customerOne, 5, null);
        advancing.advance(Duration.ofSeconds(1));
        Submission second = orderedSubmission.submit(TENANT_A, BRAND_A, orderTwo, customerTwo, 4, null);

        List<ReviewQueryService.ReviewView> firstPage =
                query.list(TENANT_A, BRAND_A, null, null, null, null, null, null, 1);
        assertThat(firstPage).extracting(ReviewQueryService.ReviewView::orderId).containsExactly(orderTwo);

        List<ReviewQueryService.ReviewView> secondPage = query.list(
                TENANT_A,
                BRAND_A,
                null,
                null,
                null,
                null,
                null,
                firstPage.get(0).id(),
                1);
        assertThat(secondPage)
                .extracting(ReviewQueryService.ReviewView::orderId)
                .containsExactly(orderOne);

        UUID otherBrand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'OTHER', 'ACTIVE', 0)
                """).param("id", otherBrand).param("tenantId", TENANT_A).update();
        assertThatThrownBy(() -> query.list(TENANT_A, otherBrand, null, null, null, null, null, second.id(), 50))
                .isInstanceOf(ReviewQueryService.UnknownCursorException.class);
    }

    // ----------------------------------------------------------------- fixtures

    private record Seed(UUID locationId, UUID channelId, UUID publicationId) {}

    private Seed seedTenancy(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "review-tenant-" + tenantId.toString().substring(0, 8))
                .update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'MAIN', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "main-" + brandId.toString().substring(0, 8))
                .update();

        UUID location = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", location)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        UUID channel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channel).param("tenantId", tenantId).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        UUID publication = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .update();

        return new Seed(location, channel, publication);
    }

    private UUID newCustomer(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 1, 1)
                """).param("id", id).param("tenantId", tenantId).update();
        return id;
    }

    private UUID order(UUID tenantId, UUID brandId, UUID customerAccountId, String status) {
        return orderAt(tenantId, brandId, locationId, customerAccountId, status);
    }

    private UUID orderAt(UUID tenantId, UUID brandId, UUID atLocationId, UUID customerAccountId, String status) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        long totalMinor = 84_000;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        :total, 0, :total, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", atLocationId)
                .param("publicationId", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, customer_account_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :customer, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", atLocationId)
                .param("channelId", channelId)
                .param("customer", customerAccountId)
                .update();

        boolean completedLike = "COMPLETED".equals(status)
                || "CONFIRMED".equals(status)
                || "PREPARING".equals(status)
                || "READY".equals(status)
                || "FULFILLING".equals(status);

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", "R-" + orderId.toString().substring(0, 8));
        order.put("tenantId", tenantId);
        order.put("brandId", brandId);
        order.put("locationId", atLocationId);
        order.put("channelId", channelId);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publicationId);
        order.put("customer", customerAccountId);
        order.put("total", totalMinor);
        order.put("key", "idem-" + orderId);
        order.put("status", status);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    fee_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    :customer, 'DELIVERY', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, CAST(:status AS varchar),
                    'UZS', :total, 0, 0, :total, :quoteId, 'hash', :publicationId, :cartId,
                    :key, 1, %s)
                """.formatted(completedLike ? "now()" : "NULL")).params(order).update();

        return orderId;
    }

    /** A clock a single test can advance between submissions, for the keyset-pagination tests. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
