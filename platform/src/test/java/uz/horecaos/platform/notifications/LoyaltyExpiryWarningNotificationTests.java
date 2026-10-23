package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.loyalty.api.LoyaltyExpiryWarningPort.ExpiringLot;
import uz.horecaos.platform.notifications.api.CustomerAccountAlertPort;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.application.CustomerAccountAlertFanoutService;
import uz.horecaos.platform.notifications.application.CustomerTelegramChannelRouter;
import uz.horecaos.platform.notifications.application.LoyaltyExpiryWarningNotifier;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The loyalty pre-expiry warning, from the adapter to the pipeline (row 6.3, ADR 0046, ADR 0020, ADR 0181),
 * against a real PostgreSQL.
 *
 * <p>The message is about a customer's points and not an order, so these tests are the proof that an
 * account-addressed intent -- no order anywhere -- goes through the same eligibility as every other customer
 * message: it is created with the recipient already named, becomes READY under an active template, and is
 * suppressed, with a reason, under none. Nothing here sends: dispatch is {@code NotificationDeliveryTests}'
 * concern, and a fixture that stopped at eligibility proves exactly what this adapter changes.
 */
class LoyaltyExpiryWarningNotificationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID LOYALTY_ACCOUNT = UUID.randomUUID();
    private static final String ZONE = "Asia/Tashkent";

    // Asia/Tashkent has no DST: local time is always UTC+05:00.
    private static final Instant NOW = Instant.parse("2026-10-14T07:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private MutableClock clock;
    private JdbcNotificationStore notifications;
    private NotificationTemplateService templates;
    private NotificationEligibilityService eligibility;
    private LoyaltyExpiryWarningNotifier notifier;
    private CustomerAccountAlertFanoutService alerts;
    private final Map<UUID, UUID> brandOf = new LinkedHashMap<>();
    private final Map<UUID, UUID> locationOf = new LinkedHashMap<>();

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for notification delivery tests");
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
        truncate();

        clock = new MutableClock(NOW);
        objectMapper = JsonMapper.builder().build();

        notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        templates = new NotificationTemplateService(
                templateStore, objectMapper, clock, AuditTrail.discarding(), AuditTrail.actor("template-author"));

        eligibility = new NotificationEligibilityService(
                notifications,
                templates,
                new StubConsentDirectory(),
                new StubRecipientContactDirectory(),
                new NoOrders(),
                new FakeTransport(),
                new AlwaysSendingCampaignFeedback(),
                objectMapper,
                clock,
                "ru");

        CustomerTelegramChannelRouter channelRouter =
                new CustomerTelegramChannelRouter(notifications, new AlwaysEntitledService());
        alerts = new CustomerAccountAlertFanoutService(
                notifications, channelRouter, templates, new FakeTransport(), objectMapper, "SMS");
        notifier = new LoyaltyExpiryWarningNotifier(alerts, ZONE, Duration.ofDays(1));

        seedTenant(TENANT);
    }

    private List<ExpiringLot> lots(long first, long second) {
        // 11 days and 13 days out: the earliest decides the date, the days, and the order they are listed in.
        return List.of(
                new ExpiringLot(UUID.randomUUID(), NOW.plus(Duration.ofDays(11)).plusSeconds(3_600), first),
                new ExpiringLot(UUID.randomUUID(), NOW.plus(Duration.ofDays(13)), second));
    }

    private List<NotificationRow> messages() {
        return notifications.forSubject(TENANT, LoyaltyExpiryWarningNotifier.SUBJECT_TYPE, LOYALTY_ACCOUNT);
    }

    @Test
    @DisplayName("two due lots become one account-addressed intent with typed variables, and no order is involved")
    void oneIntentForTheCustomerWhateverTheLotCount() throws Exception {
        UUID customer = seedCustomer(TENANT);
        activateLoyaltyTemplate(TENANT);

        assertThat(notifier.lotsExpiring(
                        TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW))
                .as("handed over")
                .isTrue();
        notifier.lotsExpiring(TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW);

        assertThat(messages()).hasSize(1);
        NotificationRow row = messages().getFirst();
        assertThat(row.templateKey()).isEqualTo("LOYALTY_POINTS_EXPIRING");
        assertThat(row.notificationClass()).isEqualTo(NotificationClass.TRANSACTIONAL_REQUIRED.name());
        assertThat(row.channel()).isEqualTo(NotificationChannel.SMS.name());
        assertThat(row.recipientAccountId())
                .as("the recipient is named at creation: there is no order to resolve it from")
                .isEqualTo(customer);
        assertThat(row.subjectId()).isEqualTo(LOYALTY_ACCOUNT);
        Map<?, ?> variables = objectMapper.readValue(row.variablesJson(), Map.class);
        assertThat(variables)
                .as("exactly the three typed values; no name, no contact, no order variable")
                .isEqualTo(Map.of(
                        "loyaltyPoints", "5000",
                        // 11 days and an hour after 12:00 Tashkent on 14 October is 13:00 on 25 October.
                        "loyaltyExpiresOn", "25.10.2026",
                        "loyaltyDaysRemaining", "11"));
    }

    @Test
    @DisplayName("the same sweep retried the same day creates no second message; the next day creates one")
    void atMostOneMessagePerCustomerPerDay() {
        UUID customer = seedCustomer(TENANT);
        activateLoyaltyTemplate(TENANT);

        notifier.lotsExpiring(TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW);
        notifier.lotsExpiring(
                TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW.plusSeconds(600));
        assertThat(messages())
                .as("a retry after a crash between the hand-over and the mark")
                .hasSize(1);

        notifier.lotsExpiring(
                TENANT,
                brandId(TENANT),
                customer,
                LOYALTY_ACCOUNT,
                "UZS",
                lots(1_000, 500),
                NOW.plus(Duration.ofDays(1)));
        assertThat(messages()).as("tomorrow is another day").hasSize(2);
    }

    @Test
    @DisplayName("an intent under an active template becomes READY for the named customer without any order")
    void readyUnderAnActiveTemplate() {
        UUID customer = seedCustomer(TENANT);
        activateLoyaltyTemplate(TENANT);
        notifier.lotsExpiring(TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW);
        UUID id = messages().getFirst().id();

        assertThat(eligibility.evaluate(claim(id))).isTrue();

        NotificationRow ready = notifications.find(TENANT, id).orElseThrow();
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.recipientAccountId()).isEqualTo(customer);
        assertThat(ready.variablesJson()).contains("loyaltyPoints").doesNotContain("amount", "orderNumber");
    }

    @Test
    @DisplayName("until a tenant activates wording the adapter creates nothing and hands nothing over")
    void builtDarkUntilAWordingIsActive() {
        UUID customer = seedCustomer(TENANT);

        boolean handedOver = notifier.lotsExpiring(
                TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW);

        assertThat(handedOver)
                .as("false keeps the lots due: their one warning is not spent on a message nobody can send")
                .isFalse();
        assertThat(messages())
                .as("no intent exists to be suppressed NO_ACTIVE_TEMPLATE and to hold the day's key")
                .isEmpty();
    }

    @Test
    @DisplayName("the day's warning is still sent when the wording is activated after an earlier dark sweep")
    void activatingTheWordingTheSameDayStillSendsTheWarning() {
        UUID customer = seedCustomer(TENANT);
        assertThat(notifier.lotsExpiring(
                        TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW))
                .isFalse();

        activateLoyaltyTemplate(TENANT);
        clock.set(NOW.plusSeconds(3_600));
        assertThat(notifier.lotsExpiring(
                        TENANT,
                        brandId(TENANT),
                        customer,
                        LOYALTY_ACCOUNT,
                        "UZS",
                        lots(3_000, 2_000),
                        NOW.plusSeconds(3_600)))
                .isTrue();

        assertThat(messages()).hasSize(1);
        assertThat(eligibility.evaluate(claim(messages().getFirst().id()))).isTrue();
    }

    @Test
    @DisplayName(
            "an intent created in the race window before a wording was withdrawn is still suppressed, with its reason")
    void aWordingWithdrawnAfterTheQuestionIsStillSuppressed() {
        UUID customer = seedCustomer(TENANT);
        alerts.notifyAccount(new CustomerAccountAlertPort.AccountAlert(
                TENANT,
                brandId(TENANT),
                customer,
                LoyaltyExpiryWarningNotifier.TEMPLATE_KEY,
                LoyaltyExpiryWarningNotifier.SUBJECT_TYPE,
                LOYALTY_ACCOUNT,
                "race:" + LOYALTY_ACCOUNT,
                Map.of("loyaltyPoints", "5000"),
                NOW,
                Duration.ofDays(1)));
        UUID id = messages().getFirst().id();

        assertThat(eligibility.evaluate(claim(id))).isFalse();

        NotificationRow suppressed = notifications.find(TENANT, id).orElseThrow();
        assertThat(suppressed.status()).isEqualTo("SUPPRESSED");
        assertThat(suppressed.suppressionReason()).isEqualTo("NO_ACTIVE_TEMPLATE");
    }

    @Test
    @DisplayName("an SMS wording the gateway has not approved is not deliverable: nothing is created until it is")
    void anSmsWordingAwaitsTheGateway() {
        UUID customer = seedCustomer(TENANT);
        activateLoyaltyTemplate(TENANT);
        setProviderReview(TENANT, "PENDING");

        assertThat(notifier.lotsExpiring(
                        TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW))
                .as("awaiting the provider is dark for the same reason as no wording")
                .isFalse();
        assertThat(messages()).isEmpty();

        setProviderReview(TENANT, "APPROVED");
        assertThat(notifier.lotsExpiring(
                        TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW))
                .isTrue();
        assertThat(messages()).hasSize(1);
    }

    @Test
    @DisplayName("a wording for another channel than the customer is reached by is not a wording for the customer")
    void aWordingForTheOtherChannelIsNotDeliverable() {
        UUID customer = seedCustomer(TENANT);
        activateLoyaltyTemplate(TENANT, NotificationChannel.TELEGRAM);

        assertThat(notifier.lotsExpiring(
                        TENANT, brandId(TENANT), customer, LOYALTY_ACCOUNT, "UZS", lots(3_000, 2_000), NOW))
                .as("the customer has no Telegram link, so is reached by SMS, for which there is no wording")
                .isFalse();
        assertThat(messages()).isEmpty();
    }

    private void setProviderReview(UUID tenantId, String state) {
        jdbc.sql("""
                UPDATE notifications.template_versions
                   SET provider_review = :state, provider_review_updated_by = 'moderation-desk',
                       provider_review_updated_at = now()
                 WHERE tenant_id = :tenantId
                """).param("state", state).param("tenantId", tenantId).update();
    }

    // --------------------------------------------------------------- helpers

    private NotificationRow claim(UUID notificationId) {
        return rowNamed(claimAllDue(), notificationId);
    }

    private List<NotificationRow> claimAllDue() {
        return notifications.claimDue(
                clock.instant(), clock.instant().plus(Duration.ofMinutes(2)), 50, UUID.randomUUID());
    }

    private NotificationRow rowNamed(List<NotificationRow> rows, UUID notificationId) {
        return rows.stream()
                .filter(row -> row.id().equals(notificationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Notification " + notificationId + " was not due to claim"));
    }

    /** Authors and activates the wording for the loyalty message, the way a tenant would before relying on it. */
    private void activateLoyaltyTemplate(UUID tenantId) {
        activateLoyaltyTemplate(tenantId, NotificationChannel.SMS);
    }

    private void activateLoyaltyTemplate(UUID tenantId, NotificationChannel channel) {
        UUID templateId = templates.createTemplate(
                tenantId,
                brandId(tenantId),
                LoyaltyExpiryWarningNotifier.TEMPLATE_KEY,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                channel,
                null);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required()
                .forEach(locale -> wordings.put(
                        locale,
                        new Wording(
                                null,
                                "{{loyaltyPoints}} points start to lapse on {{loyaltyExpiresOn}}"
                                        + " ({{loyaltyDaysRemaining}} days)")));
        int versionNumber = templates.addVersion(
                tenantId,
                brandId(tenantId),
                templateId,
                wordings,
                Map.of(
                        "loyaltyPoints", "string",
                        "loyaltyExpiresOn", "string",
                        "loyaltyDaysRemaining", "string"));
        // The migrated endpoints include the VAS gateway, which moderates wordings (ADR 0091): the version starts
        // PENDING and sends nothing until its approval is recorded.
        setProviderReview(tenantId, "APPROVED");
        templates.activate(tenantId, brandId(tenantId), templateId, versionNumber, "copy-approver");
    }

    /**
     * A customer account, and a real {@code customer.contact_points} row for
     * it — {@code notifications.recipient_endpoints.contact_point_id} carries
     * {@code fk_endpoint_contact_point}, so {@link StubRecipientContactDirectory}
     * answering with a made-up id fails that constraint the moment eligibility
     * calls {@code ensureEndpoint}. The row's own id doubles as the account
     * id: two different tables' primary keys, so reusing one UUID for both
     * costs nothing and saves a second map the stub would otherwise need.
     * {@code encrypted_value} is a literal string rather than a real AEAD
     * ciphertext — nothing in this suite ever calls {@code resolveValue}
     * against this row; that always comes from {@link StubRecipientContactDirectory}
     * instead.
     */
    private UUID seedCustomer(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status)
                VALUES (:id, :tenantId, 'ACTIVE')
                """).param("id", id).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO customer.contact_points (
                    id, tenant_id, customer_account_id, type, normalized_hash,
                    encrypted_value, verification_status, is_primary)
                VALUES (:id, :tenantId, :id, 'PHONE', :hash, 'not-a-real-ciphertext', 'UNVERIFIED', true)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("hash", "hash:" + id)
                .update();
        return id;
    }

    /**
     * A tenant, and its own brand and location — never {@code TENANT}'s and
     * a second tenant's sharing one brand/location id, which {@code
     * tenant.brands}/{@code tenant.locations} would refuse as a duplicate
     * primary key on the second insert, and which {@link #quietHoursIsTenantScoped}
     * needs to be genuinely two different tenants' own rows rather than one
     * pair both happen to point at.
     */
    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", "pilot-" + tenantId).update();

        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', :slug, 'Pilot brand', 'ACTIVE')
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "pilot-brand-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :tenantId, :brandId, 'PILOT', :slug, 'Pilot location',
                    'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("slug", "pilot-location-" + tenantId)
                .update();

        brandOf.put(tenantId, brandId);
        locationOf.put(tenantId, locationId);
    }

    /** {@link #seedTenant} always populates both maps together; a lookup miss is a fixture bug. */
    private UUID brandId(UUID tenantId) {
        return Objects.requireNonNull(brandOf.get(tenantId), () -> "No brand seeded for tenant " + tenantId);
    }

    private UUID locationId(UUID tenantId) {
        return Objects.requireNonNull(locationOf.get(tenantId), () -> "No location seeded for tenant " + tenantId);
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, "
                        + "notifications.delivery_attempts, notifications.notifications, "
                        + "notifications.recipient_endpoints, notifications.template_versions, "
                        + "notifications.templates, notifications.notification_preferences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    // ---------------------------------------------------------------- fakes

    /** The loyalty message has no order; asking for one must find nothing, as it would in production. */
    private static final class NoOrders implements OrderDirectory {

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.empty();
        }
    }

    /** Grants an ADR 0015 decision in memory; nothing else this build needs from consent. */
    private static final class StubConsentDirectory implements ConsentDirectory {

        private final Map<String, ConsentState> decisions = new LinkedHashMap<>();

        void grant(UUID tenantId, UUID accountId, String purpose, String channel) {
            decisions.put(key(tenantId, accountId, purpose, channel), new ConsentState(true, "v1", Instant.now()));
        }

        @Override
        public Optional<ConsentState> consentFor(
                UUID tenantId, UUID accountId, @Nullable UUID brandId, String purpose, @Nullable String channel) {
            return Optional.ofNullable(decisions.get(key(tenantId, accountId, purpose, channel)));
        }

        private static String key(UUID tenantId, UUID accountId, String purpose, @Nullable String channel) {
            return tenantId + ":" + accountId + ":" + purpose + ":" + channel;
        }
    }

    /** One fixed phone contact per customer; no plaintext round-trip is under test here. */
    private static final class StubRecipientContactDirectory implements RecipientContactDirectory {

        @Override
        public Optional<ContactEndpoint> primaryContact(UUID tenantId, UUID accountId, ContactMethod method) {
            // UNVERIFIED, not VERIFIED: the schema's own ck_endpoint_verified_at
            // requires a VERIFIED endpoint to carry a real last_verified_at, which
            // this stub has no opinion about and JdbcNotificationStore.ensureEndpoint
            // does not set from this call alone. Verification status plays no part
            // in what NotificationEligibilityService.evaluate decides.
            return Optional.of(new ContactEndpoint(accountId, method, "hash:" + accountId, "UNVERIFIED"));
        }

        @Override
        public Optional<String> resolveValue(UUID tenantId, UUID contactPointId, String purpose) {
            return Optional.of("+998900000000");
        }

        @Override
        public Optional<String> preferredLocale(UUID tenantId, UUID accountId) {
            return Optional.empty();
        }
    }

    /** {@code supports} is all eligibility ever asks; dispatch is out of scope for this suite. */
    private static final class FakeTransport implements NotificationTransport {

        @Override
        public DispatchOutcome dispatch(NotificationDispatch dispatch) {
            throw new UnsupportedOperationException("Not exercised: this suite stops at eligibility");
        }

        @Override
        public DispatchOutcome reconcile(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String channel, String providerIdempotencyKey) {
            throw new UnsupportedOperationException("Not exercised: this suite stops at eligibility");
        }

        @Override
        public boolean supports(String channel) {
            return NotificationChannel.SMS.name().equals(channel);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
