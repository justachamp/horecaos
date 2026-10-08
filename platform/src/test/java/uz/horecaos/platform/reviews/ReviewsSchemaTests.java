package uz.horecaos.platform.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument;
import uz.horecaos.platform.support.TestDatabase;

/**
 * What the database itself refuses (ADR 0159, V0543-V0545), asserted under the role the application
 * connects as and with the migrations' own SQL.
 *
 * <p>The application context in the other suites connects as the owner and a superuser, so a missing
 * grant or a trigger that does not fire would not show there. These probes log in as a role that holds
 * only {@code horecaos_application} -- the one thing that observes what the deployed application can do
 * -- and each states what would still be true if the guard were absent.
 */
class ReviewsSchemaTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-9000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-9000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-9000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-9000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-9000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f10-9000-7000-8000-0000000000c2");

    private static final String PROBE = "reviews_schema_probe";
    private static final String PROBE_PASSWORD = "reviews-schema-probe-password";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private static JdbcClient owner;
    private static JdbcClient application;

    private OrderBoardFixtures fixtures;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the reviews schema probes");
        db = TestDatabase.migrated();
        DataSource asOwner = db.dataSource();
        owner = JdbcClient.create(asOwner);

        owner.sql("DROP ROLE IF EXISTS " + PROBE).update();
        owner.sql("CREATE ROLE " + PROBE + " LOGIN PASSWORD '" + PROBE_PASSWORD + "'")
                .update();
        owner.sql("ALTER ROLE " + PROBE + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        owner.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + PROBE).update();
        application = JdbcClient.create(db.dataSourceAs(PROBE, PROBE_PASSWORD));
    }

    @AfterAll
    static void stopDatabase() {
        if (db == null) {
            return;
        }
        db.close();
        try {
            TestDatabase.onCluster("DROP ROLE IF EXISTS " + PROBE);
        } catch (RuntimeException leftover) {
            System.err.println("ReviewsSchemaTests: " + PROBE + " outlived the suite (" + leftover.getMessage() + ")");
        }
    }

    @BeforeEach
    void seed() {
        fixtures = new OrderBoardFixtures(owner);
        owner.sql("TRUNCATE TABLE reviews.order_reviews CASCADE").update();
        owner.sql("TRUNCATE TABLE reviews.feedback_tags CASCADE").update();
        owner.sql("TRUNCATE TABLE notifications.templates CASCADE").update();
        owner.sql("DELETE FROM tenant.policy_current").update();
        owner.sql("DELETE FROM tenant.policies").update();
        owner.sql("TRUNCATE TABLE customer.consent_types CASCADE").update();
        fixtures.clean();
        fixtures.tenant(TENANT, "reviews-schema", BRAND, LOCATION);
        fixtures.tenant(OTHER_TENANT, "reviews-schema-other", OTHER_BRAND, OTHER_LOCATION);
    }

    // ===================================================================== order_reviews

    @Test
    @DisplayName("the application may null a comment, and may change nothing else about a review")
    void onlyTheCommentMayBeErased() {
        UUID reviewId = review("R-1", 4, "ciphertext-of-a-comment");

        application
                .sql("UPDATE reviews.order_reviews SET comment_protected = NULL WHERE id = :id")
                .param("id", reviewId)
                .update();
        assertThat(owner.sql("SELECT comment_protected FROM reviews.order_reviews WHERE id = :id")
                        .param("id", reviewId)
                        .query(String.class)
                        .optional())
                .as("an erasure nulls the comment")
                .isEmpty();

        assertThatThrownBy(() -> application
                        .sql("UPDATE reviews.order_reviews SET rating = 5 WHERE id = :id")
                        .param("id", reviewId)
                        .update())
                .as("the column grant stops the rating being changed by the role the application holds")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("UPDATE reviews.order_reviews SET brand_id = brand_id WHERE id = :id")
                        .param("id", reviewId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM reviews.order_reviews WHERE id = :id")
                        .param("id", reviewId)
                        .update())
                .as("and a review is never deleted")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName(
            "even the owner cannot change a rating, or put a comment back: the trigger refuses every change but a null comment")
    void theTriggerRefusesWhatTheGrantWouldAllow() {
        UUID reviewId = review("R-2", 2, "ciphertext");

        assertThatThrownBy(() -> owner.sql("UPDATE reviews.order_reviews SET rating = 5 WHERE id = :id")
                        .param("id", reviewId)
                        .update())
                .hasMessageContaining("A review is immutable");
        assertThatThrownBy(() -> owner.sql(
                                "UPDATE reviews.order_reviews SET comment_protected = 'other words' WHERE id = :id")
                        .param("id", reviewId)
                        .update())
                .as("a comment can go to null and never to other words")
                .hasMessageContaining("A review is immutable");
        assertThatThrownBy(() -> owner.sql(
                                "UPDATE reviews.order_reviews SET comment_protected = NULL, rating = 5 WHERE id = :id")
                        .param("id", reviewId)
                        .update())
                .as("nulling the comment cannot carry another change in with it")
                .hasMessageContaining("A review is immutable");

        assertThat(owner.sql("SELECT rating FROM reviews.order_reviews WHERE id = :id")
                        .param("id", reviewId)
                        .query(Integer.class)
                        .single())
                .as("the rating is as it was submitted")
                .isEqualTo(2);

        owner.sql("UPDATE reviews.order_reviews SET comment_protected = NULL WHERE id = :id")
                .param("id", reviewId)
                .update();
        owner.sql("UPDATE reviews.order_reviews SET comment_protected = NULL WHERE id = :id")
                .param("id", reviewId)
                .update();
    }

    // ================================================================= the tag library

    @Test
    @DisplayName(
            "a tag's code, category and sentiment are frozen once a review carries it; its order and wording are not")
    void aUsedTagIsFrozen() {
        UUID tag = tag(TENANT, "food_cold", "FOOD", "NEGATIVE");
        UUID unused = tag(TENANT, "food_hot", "FOOD", "POSITIVE");
        UUID reviewId = review("R-3", 1, null);
        owner.sql("INSERT INTO reviews.order_review_tags (tenant_id, review_id, tag_id) VALUES (:t, :r, :tag)")
                .param("t", TENANT)
                .param("r", reviewId)
                .param("tag", tag)
                .update();

        assertThatThrownBy(() -> owner.sql("UPDATE reviews.feedback_tags SET category = 'SERVICE' WHERE id = :id")
                        .param("id", tag)
                        .update())
                .hasMessageContaining("cannot change its code, category or sentiment");
        assertThatThrownBy(() -> owner.sql("UPDATE reviews.feedback_tags SET sentiment = 'POSITIVE' WHERE id = :id")
                        .param("id", tag)
                        .update())
                .hasMessageContaining("cannot change its code, category or sentiment");
        assertThatThrownBy(() -> owner.sql("UPDATE reviews.feedback_tags SET code = 'food_warm' WHERE id = :id")
                        .param("id", tag)
                        .update())
                .hasMessageContaining("cannot change its code, category or sentiment");

        owner.sql("UPDATE reviews.feedback_tags SET sort_order = 5, status = 'RETIRED' WHERE id = :id")
                .param("id", tag)
                .update();
        owner.sql("UPDATE reviews.feedback_tags SET category = 'OTHER', code = 'food_lukewarm' WHERE id = :id")
                .param("id", unused)
                .update();
    }

    @Test
    @DisplayName("a tag code is unique per tenant, not across tenants, and cannot be a malformed one")
    void tagCodesAreUniquePerTenant() {
        tag(TENANT, "food_tasty", "FOOD", "POSITIVE");
        tag(OTHER_TENANT, "food_tasty", "FOOD", "POSITIVE");

        assertThatThrownBy(() -> tag(TENANT, "food_tasty", "FOOD", "POSITIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_feedback_tag_code");
        assertThatThrownBy(() -> tag(TENANT, "Food Tasty", "FOOD", "POSITIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_feedback_tag_code");
        assertThatThrownBy(() -> tag(TENANT, "food_ok", "COURIER_RATING", "POSITIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_feedback_tag_category");
    }

    @Test
    @DisplayName("a review cannot carry another tenant's tag, and the application cannot delete a tag or a carried tag")
    void tagsAreTenantScopedAndNeverDeleted() {
        UUID foreign = tag(OTHER_TENANT, "food_tasty", "FOOD", "POSITIVE");
        UUID reviewId = review("R-4", 5, null);

        assertThatThrownBy(() -> owner.sql(
                                "INSERT INTO reviews.order_review_tags (tenant_id, review_id, tag_id) VALUES (:t, :r, :tag)")
                        .param("t", TENANT)
                        .param("r", reviewId)
                        .param("tag", foreign)
                        .update())
                .as("the foreign key includes the tenant, so the database refuses it even if a service forgot to")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_order_review_tag_tag");

        assertThatThrownBy(() -> application
                        .sql("DELETE FROM reviews.feedback_tags WHERE id = :id")
                        .param("id", foreign)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() ->
                        application.sql("DELETE FROM reviews.order_review_tags").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("UPDATE reviews.order_review_tags SET tag_id = tag_id")
                        .update())
                .as("a review's tags never change")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    // ============================================================= the publication overlay

    @Test
    @DisplayName(
            "the consent snapshot is never flipped: the application may move the state and cannot touch public_consent")
    void theConsentSnapshotIsImmutable() {
        UUID reviewId = review("R-5", 4, "words");
        publication(reviewId, true, "PENDING");

        application
                .sql("UPDATE reviews.review_publications SET comment_state = 'PUBLISHED', version = version + 1 "
                        + "WHERE review_id = :id")
                .param("id", reviewId)
                .update();
        assertThatThrownBy(() -> application
                        .sql("UPDATE reviews.review_publications SET public_consent = false WHERE review_id = :id")
                        .param("id", reviewId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM reviews.review_publications")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName(
            "a comment cannot be anything but NOT_SHARED without the author's consent, and a withdrawal implies HIDDEN")
    void publicationInvariants() {
        UUID noConsent = review("R-6", 4, "words");
        UUID withConsent = review("R-7", 4, "words");

        assertThatThrownBy(() -> publication(noConsent, false, "PUBLISHED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_review_publication_consent");
        publication(withConsent, true, "PENDING");
        assertThatThrownBy(() -> owner.sql(
                                "UPDATE reviews.review_publications SET withdrawal_decision_id = gen_random_uuid() "
                                        + "WHERE review_id = :id")
                        .param("id", withConsent)
                        .update())
                .as("a withdrawal decision on a comment that is not hidden")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_review_publication_withdrawal");
    }

    // ====================================================================== cases

    @Test
    @DisplayName(
            "a case is about one review, its state and outcome agree, and it is closed exactly when it is terminal")
    void caseInvariants() {
        UUID reviewId = review("R-8", 1, null);
        UUID caseId = recoveryCase(reviewId, "NEW");

        assertThatThrownBy(() -> recoveryCase(reviewId, "NEW"))
                .as("one case per review, whatever source asks")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_recovery_case_review");
        assertThatThrownBy(() -> owner.sql(
                                "UPDATE reviews.recovery_cases SET outcome_code = 'REMEDY_GRANTED' WHERE id = :id")
                        .param("id", caseId)
                        .update())
                .as("an open case has no outcome")
                .hasMessageContaining("ck_recovery_case_outcome");
        assertThatThrownBy(() -> owner.sql("UPDATE reviews.recovery_cases SET state = 'RESOLVED', closed_at = now(), "
                                + "outcome_code = 'CUSTOMER_UNREACHABLE' WHERE id = :id")
                        .param("id", caseId)
                        .update())
                .as("a resolved case cannot carry an unresolved case's outcome")
                .hasMessageContaining("ck_recovery_case_outcome");
        assertThatThrownBy(() -> owner.sql("UPDATE reviews.recovery_cases SET state = 'RESOLVED', "
                                + "outcome_code = 'OFFLINE_GESTURE' WHERE id = :id")
                        .param("id", caseId)
                        .update())
                .as("a terminal state without a closing time")
                .hasMessageContaining("ck_recovery_case_closed");
        assertThatThrownBy(() -> owner.sql("UPDATE reviews.recovery_cases SET state = 'IN_PROGRESS' WHERE id = :id")
                        .param("id", caseId)
                        .update())
                .as("work in progress has an owner")
                .hasMessageContaining("ck_recovery_case_owner");

        owner.sql("UPDATE reviews.recovery_cases SET state = 'CLOSED_UNRESOLVED', outcome_code = 'NOT_ACTIONABLE', "
                        + "closed_at = now() WHERE id = :id")
                .param("id", caseId)
                .update();
    }

    @Test
    @DisplayName("a compensation belongs to at most one case, and a grant carries no money")
    void compensationInvariants() {
        UUID firstCase = recoveryCase(review("R-9", 1, null), "NEW");
        UUID secondCase = recoveryCase(review("R-10", 1, null), "NEW");
        UUID remedy = UUID.randomUUID();

        compensation(firstCase, "ORDER_REMEDY", remedy, 50_000L, "UZS");
        assertThatThrownBy(() -> compensation(secondCase, "ORDER_REMEDY", remedy, 50_000L, "UZS"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_recovery_case_compensation_once");
        assertThatThrownBy(() -> compensation(firstCase, "BENEFIT_GRANT", UUID.randomUUID(), 1_000L, "UZS"))
                .as("a benefit grant is a count, not money")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_recovery_case_compensation_grant");
        assertThatThrownBy(() -> compensation(firstCase, "ORDER_REMEDY", UUID.randomUUID(), 1_000L, null))
                .as("an amount needs its currency")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_recovery_case_compensation_money");
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM reviews.recovery_case_compensations")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM reviews.recovery_case_events")
                        .update())
                .as("the timeline is append-only")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("the application may null a note and change nothing else on a timeline entry")
    void onlyTheNoteMayBeErasedFromTheTimeline() {
        UUID caseId = recoveryCase(review("R-11", 1, null), "NEW");
        UUID entry = UUID.randomUUID();
        owner.sql("""
                INSERT INTO reviews.recovery_case_events (id, tenant_id, case_id, kind, actor_type, note_protected, occurred_at)
                VALUES (:id, :t, :c, 'NOTE', 'STAFF', 'ciphertext', now())
                """).param("id", entry).param("t", TENANT).param("c", caseId).update();

        application
                .sql("UPDATE reviews.recovery_case_events SET note_protected = NULL WHERE id = :id")
                .param("id", entry)
                .update();
        assertThatThrownBy(() -> application
                        .sql("UPDATE reviews.recovery_case_events SET kind = 'RESOLVED' WHERE id = :id")
                        .param("id", entry)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    // ============================================================== the migrations' own SQL

    @Test
    @DisplayName(
            "V0543 gives review.public_display to every tenant that already has a consent registry, and only to those")
    void theConsentPurposeReachesExistingRegistries() throws IOException {
        owner.sql("""
                INSERT INTO customer.consent_types (id, tenant_id, code, label_ru, label_uz, label_en,
                    channel_specific, policy_version, active, created_by, updated_by)
                VALUES (gen_random_uuid(), :t, 'TERMS_OF_SERVICE', 'ru', 'uz', 'en', false, '1', true, 'test', 'test')
                """).param("t", TENANT).update();

        owner.sql(statementOf(
                        "V0543__service_recovery_feedback_tags_publication_and_cases.sql",
                        "INSERT INTO customer.consent_types"))
                .update();

        assertThat(owner.sql("SELECT code FROM customer.consent_types WHERE tenant_id = :t ORDER BY code")
                        .param("t", TENANT)
                        .query(String.class)
                        .list())
                .containsExactly("TERMS_OF_SERVICE", "review.public_display");
        assertThat(owner.sql("SELECT count(*) FROM customer.consent_types WHERE tenant_id = :t")
                        .param("t", OTHER_TENANT)
                        .query(Long.class)
                        .single())
                .as("a tenant with no registry gets the defaults when it first reads one, not a half-registry here")
                .isZero();

        owner.sql(statementOf(
                        "V0543__service_recovery_feedback_tags_publication_and_cases.sql",
                        "INSERT INTO customer.consent_types"))
                .update();
        assertThat(owner.sql("SELECT count(*) FROM customer.consent_types WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("running it twice changes nothing")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("V0545 keeps an SMS prompt immediate for a tenant that already had an active ORDER_COMPLETED template")
    void thePreservationStepKeepsTodaysPrompt() throws IOException {
        template(TENANT, "ORDER_COMPLETED", "SMS", "ACTIVE");
        template(TENANT, "ORDER_COMPLETED", "TELEGRAM", "ACTIVE");
        template(TENANT, "ORDER_CONFIRMED", "EMAIL", "ACTIVE");
        template(OTHER_TENANT, "ORDER_COMPLETED", "SMS", "DRAFT");

        String sql = resource("V0545__a_tenant_with_an_order_completed_template_keeps_its_prompt_as_it_is.sql");
        owner.sql(sql).update();

        assertThat(owner.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'reviews.feedback_prompt'")
                        .query(Long.class)
                        .single())
                .as("one tenant had something to preserve; the other's template was only a draft")
                .isEqualTo(1L);
        String document = owner.sql(
                        "SELECT document::text FROM tenant.policies WHERE key_code = 'reviews.feedback_prompt' "
                                + "AND tenant_id = :t")
                .param("t", TENANT)
                .query(String.class)
                .single();
        FeedbackPromptDocument preserved =
                JsonMapper.builder().build().readValue(document, FeedbackPromptDocument.class);

        assertThat(preserved.sendWindow())
                .as("no send window: the prompt goes at any hour, as it did")
                .isNull();
        for (String channel : new String[] {"SMS", "TELEGRAM"}) {
            FeedbackPromptDocument.PromptRow row = preserved.rowFor("DELIVERY", channel);
            assertThat(row.enabled()).as(channel).isTrue();
            assertThat(row.delayMinutes()).as(channel + " is not delayed").isZero();
            assertThat(row.giveUpAfterHours()).isEqualTo(6);
        }
        assertThat(preserved.rowFor("DELIVERY", "EMAIL").enabled())
                .as("a channel the template did not reach stays off")
                .isFalse();
        assertThat(preserved.violations()).isEmpty();

        Instant completed = Instant.parse("2026-10-07T22:00:00Z");
        assertThat(preserved.scheduledAt(
                        preserved.rowFor("DELIVERY", "SMS"), completed, java.time.ZoneId.of("Asia/Tashkent")))
                .as("03:00 local: the tenant's SMS still goes at once")
                .isEqualTo(completed);

        assertThat(owner.sql("SELECT count(*) FROM tenant.policy_current WHERE key_code = 'reviews.feedback_prompt' "
                                + "AND scope_type = 'TENANT' AND tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);

        owner.sql(sql).update();
        assertThat(owner.sql("SELECT count(*) FROM tenant.policies WHERE key_code = 'reviews.feedback_prompt'")
                        .query(Long.class)
                        .single())
                .as("a second run writes nothing: a tenant that already has a version is left alone")
                .isEqualTo(1L);
    }

    // ----------------------------------------------------------------- fixtures

    private UUID review(String number, int rating, @Nullable String commentProtected) {
        UUID orderId = fixtures.insertOrder(
                OrderBoardFixtures.order(number).at(TENANT, BRAND, LOCATION).status("COMPLETED"));
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO reviews.order_reviews (id, tenant_id, brand_id, location_id, order_id,
                    customer_account_id, rating, comment_protected, submitted_at)
                VALUES (:id, :t, :b, :l, :o, :c, :rating, :comment, :at)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("o", orderId)
                .param("c", OrderBoardFixtures.customerOf(TENANT))
                .param("rating", rating)
                .param("comment", commentProtected)
                .param("at", OffsetDateTime.ofInstant(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID tag(UUID tenant, String code, String category, String sentiment) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO reviews.feedback_tags (id, tenant_id, code, category, sentiment, created_by_subject, updated_by_subject)
                VALUES (:id, :t, :code, :category, :sentiment, 'test', 'test')
                """)
                .param("id", id)
                .param("t", tenant)
                .param("code", code)
                .param("category", category)
                .param("sentiment", sentiment)
                .update();
        return id;
    }

    private void publication(UUID reviewId, boolean consent, String state) {
        owner.sql("""
                INSERT INTO reviews.review_publications (tenant_id, review_id, brand_id, location_id, public_consent, comment_state)
                VALUES (:t, :r, :b, :l, :consent, :state)
                """)
                .param("t", TENANT)
                .param("r", reviewId)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("consent", consent)
                .param("state", state)
                .update();
    }

    private UUID recoveryCase(UUID reviewId, String state) {
        UUID id = UUID.randomUUID();
        UUID orderId = owner.sql("SELECT order_id FROM reviews.order_reviews WHERE id = :id")
                .param("id", reviewId)
                .query(UUID.class)
                .single();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        owner.sql("""
                INSERT INTO reviews.recovery_cases (id, tenant_id, brand_id, location_id, order_id, review_id,
                    customer_account_id, source, state, policy_version, first_response_due_at, resolution_due_at,
                    created_at, updated_at)
                VALUES (:id, :t, :b, :l, :o, :r, :c, 'AUTO', :state, 0, :frd, :rd, :now, :now)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("o", orderId)
                .param("r", reviewId)
                .param("c", OrderBoardFixtures.customerOf(TENANT))
                .param("state", state)
                .param("frd", now.plusHours(4))
                .param("rd", now.plusHours(72))
                .param("now", now)
                .update();
        return id;
    }

    private void compensation(UUID caseId, String kind, UUID refId, Long amount, @Nullable String currency) {
        UUID orderId = owner.sql("SELECT order_id FROM reviews.recovery_cases WHERE id = :id")
                .param("id", caseId)
                .query(UUID.class)
                .single();
        owner.sql("""
                INSERT INTO reviews.recovery_case_compensations (tenant_id, case_id, kind, ref_id, order_id, attached_at,
                    amount_minor, currency)
                VALUES (:t, :c, :kind, :ref, :o, now(), :amount, :currency)
                """)
                .param("t", TENANT)
                .param("c", caseId)
                .param("kind", kind)
                .param("ref", refId)
                .param("o", orderId)
                .param("amount", amount)
                .param("currency", currency)
                .update();
    }

    private void template(UUID tenant, String key, String channel, String status) {
        owner.sql("""
                INSERT INTO notifications.templates (id, tenant_id, template_key, notification_class, channel, status, active_version)
                VALUES (gen_random_uuid(), :t, :key, 'TRANSACTIONAL_REQUIRED', :channel, :status, :version)
                """)
                .param("t", tenant)
                .param("key", key)
                .param("channel", channel)
                .param("status", status)
                .param("version", "ACTIVE".equals(status) ? 1 : null)
                .update();
    }

    private static String resource(String migration) throws IOException {
        try (InputStream stream =
                ReviewsSchemaTests.class.getClassLoader().getResourceAsStream("db/migration/" + migration)) {
            assertThat(stream).as(migration).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The one statement of a migration that begins with {@code prefix}, through its terminating semicolon. */
    private static String statementOf(String migration, String prefix) throws IOException {
        String text = resource(migration);
        int start = text.indexOf(prefix);
        assertThat(start).as("%s in %s", prefix, migration).isGreaterThanOrEqualTo(0);
        int end = text.indexOf(";\n", start);
        return text.substring(start, end);
    }
}
