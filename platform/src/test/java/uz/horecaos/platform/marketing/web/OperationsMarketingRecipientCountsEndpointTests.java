package uz.horecaos.platform.marketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.web.sms.SmsReceiptFixture;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row 7.9b's aggregate: {@code GET .../campaigns/{campaignId}/recipients/counts},
 * added this wave over the store-level {@code recipientCounts} rollup that
 * {@code CampaignFeedbackService} already relied on internally but nothing
 * ever exposed. Capability split mirrors {@link OperationsMarketingResumeEndpointTests}:
 * {@code TENANT_ADMIN} holds {@code campaign.author} (the same capability
 * {@code .../recipients} itself already declares), {@code TENANT_OWNER}
 * does not, which is this class's negative case.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsMarketingRecipientCountsEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000a3");
    private static final UUID AUDIENCE = UUID.fromString("018f9b20-4000-7000-8000-0000000000a4");

    private static final String OWNER = "campaign-counts-owner";
    private static final String ADMINISTRATOR = "campaign-counts-administrator";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE marketing.campaign_recipients, marketing.campaign_batches, "
                        + "marketing.campaigns, marketing.audience_snapshots, marketing.audiences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, notifications.delivery_attempts, "
                        + "notifications.notifications CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenantAndBrands();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void anOwnerWithoutCampaignAuthorCannotReadRecipientCounts() throws Exception {
        UUID campaignId = insertCampaign(BRAND);

        MvcResult refused = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.CAMPAIGN_AUTHOR.code());
    }

    @Test
    void countsGroupByTheSameStatusRecipientsItselfReturnsAndSumToTheTotal() throws Exception {
        UUID campaignId = insertCampaign(BRAND);
        insertRecipient(campaignId, seedCustomer(), 0, "QUEUED");
        insertRecipient(campaignId, seedCustomer(), 1, "QUEUED");
        insertRecipient(campaignId, seedCustomer(), 2, "DEFERRED");
        insertRecipient(campaignId, seedCustomer(), 3, "REFUSED");
        insertRecipient(campaignId, seedCustomer(), 4, "PENDING");

        MvcResult result = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"pending\":1")
                .contains("\"queued\":2")
                .contains("\"deferred\":1")
                .contains("\"refused\":1")
                .contains("\"total\":5");
    }

    /**
     * Row 6.4 (wave 10 w5-reports-exports): the campaign history/statistics
     * view's own "suppressed" count — nameable from {@code refusal_reason}
     * alone, without a terminal-status projection this wave does not add.
     */
    @Test
    void refusedByReasonBreaksDownTheRefusedTotalIncludingSuppressed() throws Exception {
        UUID campaignId = insertCampaign(BRAND);
        insertRecipientRefused(campaignId, seedCustomer(), 0, "SUPPRESSED");
        insertRecipientRefused(campaignId, seedCustomer(), 1, "SUPPRESSED");
        insertRecipientRefused(campaignId, seedCustomer(), 2, "CONSENT_WITHHELD");
        insertRecipient(campaignId, seedCustomer(), 3, "QUEUED");

        MvcResult result = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"refused\":3").contains("\"queued\":1");
        assertThat(body)
                .as("the same REFUSED total, broken down by reason — SUPPRESSED nameable on its own")
                .contains("\"refusedByReason\"")
                .contains("\"SUPPRESSED\":2")
                .contains("\"CONSENT_WITHHELD\":1");
    }

    /**
     * ADR 0146 Decision 5: a recipient shows what a gateway has actually said about
     * its message. "Handed to the operator" is the honest word for an accepted message
     * nothing has reported on, and it moves only when a receipt (or the sweeper) moves
     * the attempt it is read from.
     */
    @Test
    void recipientsShowWhatTheGatewayReportedAndBilled() throws Exception {
        UUID campaignId = insertCampaign(BRAND);
        UUID account = seedCustomer();
        SmsReceiptFixture fixture = new SmsReceiptFixture(jdbc);
        UUID attemptId = fixture.acceptedAttempt(
                TENANT, BRAND, UUID.randomUUID(), "SMSGW_VAS", "vas-901", account, java.time.Instant.now());
        UUID notificationId = jdbc.sql("SELECT notification_id FROM notifications.delivery_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(UUID.class)
                .single();
        jdbc.sql("UPDATE notifications.delivery_attempts SET provider_segments = 2 WHERE id = :id")
                .param("id", attemptId)
                .update();
        jdbc.sql("""
                INSERT INTO marketing.campaign_recipients (
                    campaign_id, tenant_id, customer_account_id, sequence, status,
                    notification_id, created_at, updated_at)
                VALUES (:campaignId, :tenantId, :accountId, 0, 'QUEUED', :notificationId, now(), now())
                """)
                .param("campaignId", campaignId)
                .param("tenantId", TENANT)
                .param("accountId", account)
                .param("notificationId", notificationId)
                .update();

        assertThat(recipientsOf(campaignId))
                .contains("\"deliveryState\":\"HANDED_TO_OPERATOR\"")
                .contains("\"segmentsBilled\":2")
                .contains("\"receiptState\":null");

        jdbc.sql("UPDATE notifications.delivery_attempts SET receipt_state = 'NO_RECEIPT' WHERE id = :id")
                .param("id", attemptId)
                .update();
        assertThat(recipientsOf(campaignId))
                .contains("\"deliveryState\":\"NO_RECEIPT\"")
                .contains("\"receiptState\":\"NO_RECEIPT\"");

        jdbc.sql("UPDATE notifications.delivery_attempts SET receipt_state = NULL, status = 'DELIVERED', "
                        + "acknowledged_at = now() WHERE id = :id")
                .param("id", attemptId)
                .update();
        assertThat(recipientsOf(campaignId)).contains("\"deliveryState\":\"DELIVERED\"");

        jdbc.sql("UPDATE notifications.delivery_attempts SET status = 'FAILED', acknowledged_at = NULL, "
                        + "failure_code = 'RECEIVER_UNREACHABLE' WHERE id = :id")
                .param("id", attemptId)
                .update();
        assertThat(recipientsOf(campaignId)).contains("\"deliveryState\":\"FAILED\"");
    }

    /**
     * ADR 0146 Decision 8: "wired" is a fact about this brand's account, not about the
     * build. A brand with no SMS binding is not wired for SMS in a build that has an SMS
     * adapter, and the console is told why in a stable code.
     */
    @Test
    void channelsAreWiredPerBrandWithAReason() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/channels")
                        .with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var channels = tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(result.getResponse().getContentAsString());
        java.util.Map<String, tools.jackson.databind.JsonNode> byName = new java.util.LinkedHashMap<>();
        channels.forEach(node -> byName.put(node.get("channel").asString(), node));

        assertThat(java.util.Objects.requireNonNull(byName.get("SMS"))
                        .get("isWired")
                        .asBoolean())
                .isFalse();
        assertThat(java.util.Objects.requireNonNull(byName.get("SMS"))
                        .get("notWiredReason")
                        .asString())
                .isEqualTo("NO_PROVIDER_BINDING");
        assertThat(java.util.Objects.requireNonNull(byName.get("EMAIL"))
                        .get("notWiredReason")
                        .asString())
                .as("email has a delivery since ADR 0182, and no relay is configured in this deployment")
                .isEqualTo("EMAIL_NOT_CONFIGURED");
        assertThat(java.util.Objects.requireNonNull(byName.get("EMAIL"))
                        .get("carriesMarginalCost")
                        .asBoolean())
                .as("the platform's relay costs the tenant nothing per message; the daily cap bounds it")
                .isFalse();
        assertThat(java.util.Objects.requireNonNull(byName.get("PUSH"))
                        .get("notWiredReason")
                        .asString())
                .as("push has a delivery since ADR 0182, and no VAPID identity is configured in this deployment")
                .isEqualTo("PUSH_NOT_CONFIGURED");
        for (String silent : List.of("SMS", "EMAIL", "PUSH", "MESSAGING_APP")) {
            var node = java.util.Objects.requireNonNull(byName.get(silent));
            assertThat(node.get("reportsDelivery").asBoolean())
                    .as(silent + " has no enabled receipt source here")
                    .isFalse();
            assertThat(node.get("reportsRead").asBoolean())
                    .as(silent + ": only a push can report a read, by the customer's tap (ADR 0182, D14)")
                    .isEqualTo("PUSH".equals(silent));
        }
        assertThat(java.util.Objects.requireNonNull(byName.get("MESSAGING_APP"))
                        .get("isWired")
                        .asBoolean())
                .as("Telegram's link is the customer's own, so it is wired for every brand as before")
                .isTrue();
    }

    /**
     * ADR 0182: the report's delivery half, derived from the messages' own evidence and not copied
     * anywhere. One message in every state a message can reach, so a mapping that lumped two of them
     * together, or counted a read as only a delivery, could not pass.
     */
    @Test
    void deliveryCountsAreDerivedFromTheEvidenceOfEveryMessage() throws Exception {
        UUID campaignId = insertCampaign(BRAND);
        message(campaignId, "DELIVERED", null, "DELIVERED", null, 1, false, null);
        message(campaignId, "DELIVERED", null, "DELIVERED", null, 2, true, null);
        message(campaignId, "FAILED_TERMINAL", null, "FAILED", null, 1, false, "RECEIVER_UNREACHABLE");
        message(campaignId, "FAILED_TERMINAL", null, "REJECTED", null, null, false, "SMS_PURPOSE_NOT_PERMITTED");
        message(campaignId, "DELIVERED", null, "ACCEPTED", null, 1, false, null);
        message(campaignId, "DELIVERED", null, "ACCEPTED", "NO_RECEIPT", 1, false, null);
        message(campaignId, "CREATED", null, null, null, null, false, null);
        message(campaignId, "RETRY_PENDING", null, "RETRYABLE_FAILURE", null, null, false, "SMTP_UNAVAILABLE");
        message(campaignId, "SUPPRESSED", "TEMPLATE_AWAITING_PROVIDER", null, null, null, false, null);
        message(campaignId, "SUPPRESSED", "CONSENT_WITHHELD", null, null, null, false, null);
        message(campaignId, "EXPIRED", null, null, null, null, false, null);
        // A retry loop that ended: the notification gave up, the last attempt was only a retryable failure.
        message(campaignId, "FAILED_TERMINAL", null, "RETRYABLE_FAILURE", null, null, false, "SMTP_UNAVAILABLE");

        var delivery = deliveryOf(campaignId);

        assertThat(delivery.get("total").asInt()).isEqualTo(12);
        assertThat(delivery.get("delivered").asInt())
                .as("a read message was delivered too")
                .isEqualTo(2);
        assertThat(delivery.get("read").asInt()).isEqualTo(1);
        assertThat(delivery.get("failed").asInt())
                .as("one the gateway failed, one whose retries ran out")
                .isEqualTo(2);
        assertThat(delivery.get("rejected").asInt()).isEqualTo(1);
        assertThat(delivery.get("handedToOperator").asInt()).isEqualTo(1);
        assertThat(delivery.get("noReceipt").asInt()).isEqualTo(1);
        assertThat(delivery.get("pending").asInt())
                .as("never tried, and still being retried")
                .isEqualTo(2);
        assertThat(delivery.get("withheld").asInt()).isEqualTo(3);
        assertThat(delivery.get("withheldByReason")
                        .get("TEMPLATE_AWAITING_PROVIDER")
                        .asInt())
                .isEqualTo(1);
        assertThat(delivery.get("withheldByReason").get("CONSENT_WITHHELD").asInt())
                .isEqualTo(1);
        assertThat(delivery.get("withheldByReason").get("EXPIRED").asInt())
                .as("a message that lapsed before it was sent was withheld by its expiry")
                .isEqualTo(1);
        assertThat(delivery.get("segmentsBilled").asInt())
                .as(
                        "the delivered one (1) and the read one (2), the failed (1), the two accepted (1 each); a rejection billed nothing")
                .isEqualTo(1 + 2 + 1 + 1 + 1);
    }

    @Test
    void aCampaignWithNoMessagesCountsZeroNotMissing() throws Exception {
        UUID campaignId = insertCampaign(BRAND);

        var delivery = deliveryOf(campaignId);

        assertThat(delivery.get("total").asInt()).isZero();
        assertThat(delivery.get("delivered").asInt()).isZero();
        assertThat(delivery.get("withheldByReason").size()).isZero();
        assertThat(delivery.get("reportsDelivery").asBoolean())
                .as("and says that this channel cannot confirm a delivery, which is not the same as zero")
                .isFalse();
        assertThat(delivery.get("reportsRead").asBoolean()).isFalse();
    }

    /** ADR 0182: the list a marketer compares campaigns by is the same evidence, one read for all of them. */
    @Test
    void deliveryCountsCompareCampaigns() throws Exception {
        UUID small = insertCampaign(BRAND);
        UUID large = insertCampaign(BRAND);
        UUID elsewhere = insertCampaign(OTHER_BRAND);
        message(small, "DELIVERED", null, "ACCEPTED", null, 1, false, null);
        message(large, "DELIVERED", null, "ACCEPTED", null, 1, false, null);
        message(large, "DELIVERED", null, "ACCEPTED", null, 1, false, null);
        message(large, "SUPPRESSED", "CONSENT_WITHHELD", null, null, null, false, null);
        message(elsewhere, "DELIVERED", null, "ACCEPTED", null, 1, false, null);

        MvcResult result = mvc.perform(
                        get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/campaigns/delivery-counts")
                                .with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var rows = tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(result.getResponse().getContentAsString());
        java.util.Map<String, tools.jackson.databind.JsonNode> byCampaign = new java.util.LinkedHashMap<>();
        rows.forEach(row -> byCampaign.put(row.get("campaignId").asString(), row.get("delivery")));
        assertThat(byCampaign).containsOnlyKeys(small.toString(), large.toString());
        var smallCounts = java.util.Objects.requireNonNull(byCampaign.get(small.toString()));
        var largeCounts = java.util.Objects.requireNonNull(byCampaign.get(large.toString()));
        assertThat(smallCounts.get("handedToOperator").asInt()).isEqualTo(1);
        assertThat(largeCounts.get("handedToOperator").asInt()).isEqualTo(2);
        assertThat(largeCounts.get("withheld").asInt()).isEqualTo(1);
        assertThat(largeCounts.get("reportsDelivery").asBoolean()).isFalse();
    }

    @Test
    void anotherCampaignsMessagesAreNotCounted() throws Exception {
        UUID campaignId = insertCampaign(BRAND);
        UUID other = insertCampaign(BRAND);
        message(campaignId, "DELIVERED", null, "DELIVERED", null, 1, false, null);
        message(other, "DELIVERED", null, "DELIVERED", null, 1, false, null);
        message(other, "DELIVERED", null, "DELIVERED", null, 1, false, null);

        assertThat(deliveryOf(campaignId).get("delivered").asInt()).isEqualTo(1);
    }

    /** ADR 0182: a recipient row says the same word the count does, and why, including a message never sent. */
    @Test
    void recipientsCarryReadWithheldAndTheReason() throws Exception {
        UUID campaignId = insertCampaign(BRAND);
        UUID read = message(campaignId, "DELIVERED", null, "DELIVERED", null, 1, true, null);
        UUID withheld = message(campaignId, "SUPPRESSED", "TEMPLATE_AWAITING_PROVIDER", null, null, null, false, null);
        UUID failed = message(campaignId, "FAILED_TERMINAL", null, "FAILED", null, 1, false, "RECEIVER_UNREACHABLE");
        linkRecipient(campaignId, read, 0);
        linkRecipient(campaignId, withheld, 1);
        linkRecipient(campaignId, failed, 2);

        var rows = tools.jackson.databind.json.JsonMapper.builder().build().readTree(recipientsOf(campaignId));
        java.util.Map<String, tools.jackson.databind.JsonNode> byNotification = new java.util.LinkedHashMap<>();
        rows.forEach(row -> byNotification.put(row.get("notificationId").asString(), row));

        var readRow = java.util.Objects.requireNonNull(byNotification.get(read.toString()));
        assertThat(readRow.get("deliveryState").asString()).isEqualTo("READ");
        var withheldRow = java.util.Objects.requireNonNull(byNotification.get(withheld.toString()));
        assertThat(withheldRow.get("deliveryState").asString()).isEqualTo("WITHHELD");
        assertThat(withheldRow.get("deliveryReason").asString()).isEqualTo("TEMPLATE_AWAITING_PROVIDER");
        var failedRow = java.util.Objects.requireNonNull(byNotification.get(failed.toString()));
        assertThat(failedRow.get("deliveryState").asString()).isEqualTo("FAILED");
        assertThat(failedRow.get("deliveryReason").asString()).isEqualTo("RECEIVER_UNREACHABLE");
    }

    @Test
    void recipientCountsRefusesACampaignFromAnotherBrand() throws Exception {
        UUID campaignId = insertCampaign(OTHER_BRAND);

        MvcResult notFound = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(notFound.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ fixtures

    private String recipientsOf(UUID campaignId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/campaigns/"
                                + campaignId + "/recipients")
                        .with(tokenFor(ADMINISTRATOR)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private tools.jackson.databind.JsonNode deliveryOf(UUID campaignId) throws Exception {
        MvcResult result = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(ADMINISTRATOR)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(result.getResponse().getContentAsString())
                .get("delivery");
    }

    /**
     * One campaign message in a chosen state: the notification, its latest attempt if it has one, and a
     * {@code READ} status event when asked. Inserted directly, because what is read is the evidence and
     * not how a message came to have it.
     */
    private UUID message(
            UUID campaignId,
            String notificationStatus,
            @Nullable String suppressionReason,
            @Nullable String attemptStatus,
            @Nullable String receiptState,
            @Nullable Integer segments,
            boolean read,
            @Nullable String failureCode) {
        UUID notificationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notifications.notifications (
                    id, tenant_id, brand_id, notification_class, channel, template_key, subject_type,
                    subject_id, recipient_account_id, idempotency_key, status, suppression_reason)
                VALUES (:id, :tenantId, :brandId, 'MARKETING', 'SMS', 'PROMO', 'MarketingCampaign',
                        :campaignId, :account, :key, :status, :reason)
                """)
                .param("id", notificationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("campaignId", campaignId)
                .param("account", seedCustomer())
                .param("key", "counts-fixture:" + notificationId)
                .param("status", notificationStatus)
                .param("reason", suppressionReason)
                .update();
        if (attemptStatus == null) {
            return notificationId;
        }
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notifications.delivery_attempts (
                    id, tenant_id, notification_id, channel, attempt_number, provider_idempotency_key, status,
                    receipt_state, provider_segments, failure_code, requested_at, acknowledged_at)
                VALUES (:id, :tenantId, :notificationId, 'SMS', 1, :key, :status,
                        :receiptState, :segments, :failureCode, now(),
                        CASE WHEN :status = 'DELIVERED' THEN now() END)
                """)
                .param("id", attemptId)
                .param("tenantId", TENANT)
                .param("notificationId", notificationId)
                .param("key", attemptId.toString())
                .param("status", attemptStatus)
                .param("receiptState", receiptState)
                .param("segments", segments)
                .param("failureCode", failureCode)
                .update();
        if (read) {
            jdbc.sql("""
                    INSERT INTO notifications.delivery_status_events (
                        id, tenant_id, attempt_id, provider_event_id, normalized_status, occurred_at)
                    VALUES (:id, :tenantId, :attemptId, :eventId, 'READ', now())
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("attemptId", attemptId)
                    .param("eventId", "read-" + attemptId)
                    .update();
        }
        return notificationId;
    }

    private void linkRecipient(UUID campaignId, UUID notificationId, int sequence) {
        UUID account = jdbc.sql("SELECT recipient_account_id FROM notifications.notifications WHERE id = :id")
                .param("id", notificationId)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                INSERT INTO marketing.campaign_recipients (
                    campaign_id, tenant_id, customer_account_id, sequence, status,
                    notification_id, created_at, updated_at)
                VALUES (:campaignId, :tenantId, :accountId, :sequence, 'QUEUED', :notificationId, now(), now())
                """)
                .param("campaignId", campaignId)
                .param("tenantId", TENANT)
                .param("accountId", account)
                .param("sequence", sequence)
                .param("notificationId", notificationId)
                .update();
    }

    private static String countsPath(UUID brandId, UUID campaignId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + brandId + "/marketing/campaigns/" + campaignId
                + "/recipients/counts";
    }

    private void insertRecipient(UUID campaignId, UUID customerAccountId, int sequence, String status) {
        UUID notificationId = "QUEUED".equals(status) ? UUID.randomUUID() : null;
        String refusalReason = "REFUSED".equals(status) ? "SUPPRESSED" : null;
        OffsetDateTime deferredUntil =
                "DEFERRED".equals(status) ? OffsetDateTime.now(ZoneOffset.UTC).plusHours(1) : null;
        jdbc.sql("""
                INSERT INTO marketing.campaign_recipients (
                    campaign_id, tenant_id, customer_account_id, sequence, status,
                    notification_id, refusal_reason, deferred_until, created_at, updated_at)
                VALUES (:campaignId, :tenantId, :accountId, :sequence, :status,
                    :notificationId, :refusalReason, :deferredUntil, now(), now())
                """)
                .param("campaignId", campaignId)
                .param("tenantId", TENANT)
                .param("accountId", customerAccountId)
                .param("sequence", sequence)
                .param("status", status)
                .param("notificationId", notificationId)
                .param("refusalReason", refusalReason)
                .param("deferredUntil", deferredUntil)
                .update();
    }

    /** Row 6.4: a REFUSED recipient with a chosen reason, unlike {@link #insertRecipient}'s own fixed SUPPRESSED. */
    private void insertRecipientRefused(UUID campaignId, UUID customerAccountId, int sequence, String refusalReason) {
        jdbc.sql("""
                INSERT INTO marketing.campaign_recipients (
                    campaign_id, tenant_id, customer_account_id, sequence, status,
                    refusal_reason, created_at, updated_at)
                VALUES (:campaignId, :tenantId, :accountId, :sequence, 'REFUSED',
                    :refusalReason, now(), now())
                """)
                .param("campaignId", campaignId)
                .param("tenantId", TENANT)
                .param("accountId", customerAccountId)
                .param("sequence", sequence)
                .param("refusalReason", refusalReason)
                .update();
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', 'ru', now())
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private UUID insertCampaign(UUID brandId) {
        UUID id = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.audience_snapshots (
                    id, tenant_id, brand_id, audience_id, definition_version, channel, consent_purpose,
                    status, metric_definition_version, built_by, built_at, completed_at)
                VALUES (:id, :tenantId, :brandId, :audienceId, 1, 'SMS', 'MARKETING_PROMOTIONS',
                    'READY', 1, :builtBy, now(), now())
                """)
                .param("id", snapshotId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("audienceId", AUDIENCE)
                .param("builtBy", UUID.randomUUID())
                .update();
        jdbc.sql("""
                INSERT INTO marketing.campaigns (
                    id, tenant_id, brand_id, name, channel, consent_purpose, status,
                    audience_id, audience_snapshot_id, template_key, recipient_cap, currency, timezone,
                    created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :name, 'SMS', 'MARKETING_PROMOTIONS', 'DRAFT',
                    :audienceId, :snapshotId, 'MARKETING_PROMOTION', 100, 'UZS', 'Asia/Tashkent', :createdBy,
                    now(), now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("name", "Recipient counts fixture " + id)
                .param("audienceId", AUDIENCE)
                .param("snapshotId", snapshotId)
                .param("createdBy", UUID.randomUUID())
                .update();
        return id;
    }

    private void seedTenantAndBrands() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'campaign-counts-endpoint', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Other brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO marketing.audiences (id, tenant_id, brand_id, name, created_by)
                VALUES (:id, :tenantId, :brandId, 'Everybody', :createdBy)
                """)
                .param("id", AUDIENCE)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("createdBy", UUID.randomUUID())
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'recipient counts endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
