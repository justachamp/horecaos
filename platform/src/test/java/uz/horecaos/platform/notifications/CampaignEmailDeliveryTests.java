package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;
import uz.horecaos.platform.customers.application.RecipientContactService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.camel.notification.CamelNotificationTransport;
import uz.horecaos.platform.integration.camel.notification.NotificationGateway;
import uz.horecaos.platform.integration.camel.notification.NotificationProcessor;
import uz.horecaos.platform.integration.camel.notification.NotificationRouteBuilder;
import uz.horecaos.platform.integration.camel.notification.email.SmtpEmailChannelAdapter;
import uz.horecaos.platform.integration.provider.JdbcProviderActivityRecorder;
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.application.DeliverabilityFeedbackService;
import uz.horecaos.platform.marketing.application.MarketingSuppressionService;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.application.CampaignBlockRateMonitor;
import uz.horecaos.platform.notifications.application.CampaignEmailDeliveryService;
import uz.horecaos.platform.notifications.application.CampaignEmailEnvelopes;
import uz.horecaos.platform.notifications.application.CampaignMessageRouter;
import uz.horecaos.platform.notifications.application.EmailDailyCap;
import uz.horecaos.platform.notifications.application.EmailUnsubscribeService;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.application.OperationsAlertFanoutService;
import uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcUnsubscribeTokenStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0182, D13, over the real dispatch path: a campaign email is an ordinary ADR 0020 intent that
 * leaves through the platform's own mail relay in the brand's name, carries a way out that really
 * withdraws the customer's consent, bounces into suppression only when the relay refuses the
 * address, and is held by the tenant's daily cap rather than dropped.
 *
 * <p>Real PostgreSQL, real envelope encryption of the address, the real worker, eligibility,
 * dispatch, gateway and Camel route, a recording relay at the end. The one thing faked is the
 * relay itself, because what is under test is what the platform does with each of its answers; the
 * wire format has its own test against a real SMTP server ({@code SmtpPlatformMailerTests}).
 */
class CampaignEmailDeliveryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String ADDRESS = "guest@example.uz";
    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");
    private static final String ORIGIN = "https://api.horecaos.test";

    private static TestDatabase.Handle db;

    private RecordingRelay relay;
    private MutableClock clock;
    private CamelContext camel;
    private JdbcClient jdbc;
    private JdbcNotificationStore notifications;
    private JdbcEngagementStore engagement;
    private NotificationWorker worker;
    private CampaignMessageRouter router;
    private ConsentService consentService;
    private EmailUnsubscribeService unsubscribes;
    private NotificationTemplateService templates;
    private UUID accountId = UUID.randomUUID();

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        relay = new RecordingRelay();
        clock = new MutableClock(NOW);
        build(1000);
    }

    /** Wires the real stack around the relay; called again by a test that needs a different cap. */
    private void build(int dailyCap) throws Exception {
        if (camel != null) {
            camel.stop();
        }
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        ObjectMapper objectMapper = JsonMapper.builder().build();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get, clock);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        CustomerProfileService profiles = new CustomerProfileService(
                customerStore, protection, objectMapper, clock, new JdbcAuditRecorder(jdbc, objectMapper));
        consentService = new ConsentService(customerStore, clock);
        RecipientContactDirectory contacts = new RecipientContactService(customerStore, protection);

        notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        templates = new NotificationTemplateService(
                templateStore, objectMapper, clock, AuditTrail.discarding(), AuditTrail.actor("template-author"));

        seedTenantAndCustomer(profiles);

        JdbcProviderInstallationLookup installations =
                new JdbcProviderInstallationLookup(jdbc, clock, new JdbcProviderEnvironmentLookup(jdbc));
        NotificationGateway providerGateway =
                new NotificationGateway(List.of(new SmtpEmailChannelAdapter(relay)), installations, secrets);

        camel = new DefaultCamelContext();
        camel.addRoutes(
                new NotificationRouteBuilder(new NotificationProcessor(providerGateway, new SimpleMeterRegistry())));
        camel.start();
        CamelNotificationTransport transport = new CamelNotificationTransport(
                camel.createProducerTemplate(), providerGateway, new JdbcProviderActivityRecorder(jdbc), clock);

        OperationsAlertFanoutService operationsAlerts = new OperationsAlertFanoutService(
                new NoTelegramBindings(),
                notifications,
                new TelegramOperationsEntitlementGate(new AlwaysEntitledService()),
                objectMapper,
                clock);
        CampaignBlockRateMonitor blockRate =
                new CampaignBlockRateMonitor(new AlwaysSendingCampaignFeedback(), operationsAlerts, meters);
        OrderDirectory noOrders = (tenantId, orderId) -> Optional.empty();
        NotificationEligibilityService eligibility = new NotificationEligibilityService(
                notifications,
                templates,
                consentService,
                contacts,
                noOrders,
                transport,
                new AlwaysSendingCampaignFeedback(),
                new EmailDailyCap(notifications, clock, dailyCap),
                objectMapper,
                clock,
                "ru");

        engagement = new JdbcEngagementStore(jdbc);
        DeliverabilityFeedbackService deliverability = new DeliverabilityFeedbackService(
                new MarketingSuppressionService(engagement, new JdbcAuditRecorder(jdbc, objectMapper), clock),
                engagement,
                clock);
        unsubscribes = new EmailUnsubscribeService(
                new JdbcUnsubscribeTokenStore(jdbc),
                consentService,
                consentService,
                (tenantId, brandId) -> Optional.of("Pilot brand"),
                clock,
                Duration.ofDays(400));
        CampaignEmailEnvelopes envelopes = new CampaignEmailEnvelopes(
                unsubscribes, (tenantId, brandId) -> Optional.of("Pilot brand"), templateStore, ORIGIN + "/");
        NotificationDispatchService dispatch = new NotificationDispatchService(
                notifications,
                templateStore,
                contacts,
                transport,
                blockRate,
                deliverability,
                meters,
                envelopes,
                objectMapper,
                clock,
                8,
                Duration.ofSeconds(30));

        worker = new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));
        router = new CampaignMessageRouter(
                List.of(new CampaignEmailDeliveryService(
                        notifications, templates, objectMapper, clock, Duration.ofDays(1))),
                transport,
                notifications,
                new JdbcDeliveryReceiptStore(jdbc));
        activateEmailTemplate();
    }

    @AfterEach
    void tearDown() {
        if (camel != null) {
            camel.stop();
        }
    }

    @Test
    @DisplayName("a campaign email leaves in the brand's name, carries a way out, and is only «handed to the operator»")
    void aCampaignEmailLeavesWithAWayOut() {
        grantEmailConsent();

        UUID notificationId = enqueue("m1");
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        assertThat(relay.sent).hasSize(1);
        OutgoingMail mail = relay.sent.getFirst();
        assertThat(mail.to()).isEqualTo(ADDRESS);
        assertThat(mail.senderName())
                .as("the brand's display name is the sender's name, over the platform's one address")
                .isEqualTo("Pilot brand");
        assertThat(mail.subject()).isEqualTo("Pilot brand: aksiya");
        assertThat(mail.unsubscribeUrl()).startsWith(ORIGIN + "/providers/mail/unsubscribe/");
        assertThat(mail.text()).contains("Chegirma 20%").contains(mail.unsubscribeUrl());

        var attempt = notifications.attempts(TENANT, notificationId).getFirst();
        assertThat(attempt.providerType()).isEqualTo("PLATFORM_SMTP");
        assertThat(attempt.providerBindingId())
                .as("the platform's own relay has no tenant binding")
                .isNull();
        assertThat(attempt.status())
                .as("a relay that took the message has not said it arrived")
                .isEqualTo("ACCEPTED");
        assertThat(router.deliveryEvidence(TENANT, List.of(notificationId)).get(notificationId))
                .satisfies(evidence -> assertThat(evidence.state()).isEqualTo("HANDED_TO_OPERATOR"));

        assertThat(everythingIn("notifications.notifications")
                        + everythingIn("notifications.delivery_attempts")
                        + everythingIn("notifications.delivery_status_events")
                        + everythingIn("notifications.recipient_endpoints"))
                .as("ADR 0029: the address is on the wire and nowhere on the platform's own rows")
                .doesNotContain(ADDRESS);
        assertThat(everythingIn("notifications.unsubscribe_tokens"))
                .as("only the token's hash is kept")
                .doesNotContain(tokenOf(mail));
    }

    @Test
    @DisplayName("the way out withdraws exactly this consent, and the next campaign refuses the customer")
    void unsubscribingWithdrawsConsent() {
        grantEmailConsent();
        grantConsent("SMS");
        enqueue("m1");
        worker.drain();
        String token = tokenOf(relay.sent.getFirst());

        assertThat(unsubscribes.preview(token))
                .get()
                .satisfies(preview -> assertThat(preview.brandName()).isEqualTo("Pilot brand"));
        assertThat(consentGranted("EMAIL")).isTrue();

        assertThat(unsubscribes.unsubscribe(token)).isPresent();

        assertThat(consentGranted("EMAIL"))
                .as("an unsubscribe is a withdrawal of consent on this channel")
                .isFalse();
        assertThat(consentGranted("SMS"))
                .as("consent to SMS is a different decision, and a link in an email does not touch it")
                .isTrue();

        UUID second = enqueue("m2");
        worker.drain();
        assertThat(statusOf(second)).isEqualTo("SUPPRESSED");
        assertThat(suppressionReasonOf(second)).isEqualTo("CONSENT_WITHHELD");
        assertThat(relay.sent).as("the second campaign sent nothing").hasSize(1);

        assertThat(unsubscribes.unsubscribe(token))
                .as("clicking again is the same act, not an error")
                .isPresent();
        assertThat(withdrawals())
                .as("and it records no second withdrawal of what was already withdrawn")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a token that nothing minted, and one that has expired, are one answer")
    void unknownAndExpiredTokensAreOneAnswer() {
        grantEmailConsent();
        enqueue("m1");
        worker.drain();
        String token = tokenOf(relay.sent.getFirst());

        assertThat(unsubscribes.unsubscribe("A".repeat(32))).isEmpty();
        assertThat(unsubscribes.preview("A".repeat(32))).isEmpty();

        clock.advance(Duration.ofDays(401));
        assertThat(unsubscribes.unsubscribe(token))
                .as("a year is the token's life")
                .isEmpty();
        assertThat(consentGranted("EMAIL")).isTrue();
    }

    @Test
    @DisplayName("a relay that refuses the address for good suppresses the customer, once")
    void aRefusedAddressSuppresses() {
        grantEmailConsent();
        relay.outcome = new MailOutcome.Rejected("ADDRESS_REJECTED");

        UUID notificationId = enqueue("m1");
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("FAILED_TERMINAL");
        assertThat(engagement.hasActiveSuppression(TENANT, BRAND, accountId, "EMAIL", NOW))
                .as("ADR 0044: a hard bounce is a deliverability fact, and it outranks consent")
                .isTrue();
        assertThat(router.deliveryEvidence(TENANT, List.of(notificationId)).get(notificationId))
                .satisfies(evidence -> assertThat(evidence.state()).isEqualTo("REJECTED"));
    }

    @Test
    @DisplayName("a refusal of the message, and a relay that is down, suppress nobody")
    void otherFailuresDoNotSuppress() {
        grantEmailConsent();
        relay.outcome = new MailOutcome.Rejected("MESSAGE_INVALID");
        UUID refused = enqueue("m1");
        worker.drain();
        assertThat(statusOf(refused)).isEqualTo("FAILED_TERMINAL");

        relay.outcome = new MailOutcome.Failed("SMTP_UNAVAILABLE");
        UUID unreachable = enqueue("m2");
        worker.drain();
        assertThat(statusOf(unreachable)).isEqualTo("RETRY_PENDING");

        assertThat(engagement.hasActiveSuppression(TENANT, BRAND, accountId, "EMAIL", NOW))
                .as("neither says anything about the customer's address")
                .isFalse();
    }

    @Test
    @DisplayName("a connection lost after the message was on its way is reconciled, never sent again")
    void aLostAnswerIsNeverRepeated() {
        grantEmailConsent();
        relay.outcome = new MailOutcome.Failed(MailOutcome.SMTP_NO_ANSWER);

        UUID notificationId = enqueue("m1");
        worker.drain();
        assertThat(statusOf(notificationId)).isEqualTo("UNCERTAIN");

        relay.outcome = new MailOutcome.Sent();
        for (int round = 0; round < 3; round++) {
            makeDue(notificationId);
            worker.drain();
        }

        assertThat(relay.sent)
                .as("SMTP has no idempotency key and no way to ask, so a second send would be a duplicate")
                .hasSize(1);
        assertThat(statusOf(notificationId)).isNotEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("the tenant's daily cap holds the excess until the next day instead of dropping it")
    void theDailyCapHoldsTheExcess() throws Exception {
        build(2);
        grantEmailConsent();
        UUID first = enqueue("m1");
        UUID second = enqueue("m2");
        UUID third = enqueue("m3");

        worker.drain();

        assertThat(relay.sent).as("two of three fit under the cap").hasSize(2);
        assertThat(List.of(statusOf(first), statusOf(second), statusOf(third)))
                .containsExactlyInAnyOrder("DELIVERED", "DELIVERED", "CREATED");
        UUID held = List.of(first, second, third).stream()
                .filter(id -> "CREATED".equals(statusOf(id)))
                .findFirst()
                .orElseThrow();
        assertThat(notifications.find(TENANT, held).orElseThrow().nextAttemptAt())
                .as("held until 00:00 UTC of the next day")
                .isEqualTo(Instant.parse("2026-08-23T00:00:00Z"));

        clock.advance(Duration.ofHours(16));
        worker.drain();

        assertThat(relay.sent)
                .as("the next day it is sent, and counted against that day")
                .hasSize(3);
        assertThat(statusOf(held)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("an email of any class but marketing is not sent, and cannot be authored")
    void onlyMarketingEmailIsWired() {
        grantEmailConsent();
        UUID transactional = UUID.randomUUID();
        notifications.createIntent(new NewNotification(
                transactional,
                TENANT,
                BRAND,
                null,
                NotificationClass.TRANSACTIONAL_OPTIONAL.name(),
                NotificationChannel.EMAIL.name(),
                "PROMO_EMAIL",
                "MarketingCampaign",
                UUID.randomUUID(),
                accountId,
                null,
                "transactional-1",
                "{}",
                NOW,
                NOW.plusSeconds(3600),
                NOW,
                null));

        worker.drain();

        assertThat(statusOf(transactional)).isEqualTo("SUPPRESSED");
        assertThat(suppressionReasonOf(transactional)).isEqualTo("CHANNEL_NOT_AVAILABLE");
        assertThat(relay.sent).isEmpty();

        assertThatThrownBy(() -> templates.createTemplate(
                        TENANT,
                        BRAND,
                        "RECEIPT_EMAIL",
                        NotificationClass.TRANSACTIONAL_REQUIRED,
                        NotificationChannel.EMAIL,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MARKETING");
    }

    @Test
    @DisplayName("an email wording needs a subject in every language")
    void anEmailWordingNeedsASubject() {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, "NO_SUBJECT", NotificationClass.MARKETING, NotificationChannel.EMAIL, "PROMOTIONS");
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, "Chegirma")));

        assertThatThrownBy(() -> templates.addVersion(TENANT, BRAND, templateId, wordings, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    @Test
    @DisplayName("the console is told why email cannot send, per brand, and what it can report")
    void wiringAndReporting() {
        assertThat(router.wiring(TENANT, BRAND, "EMAIL", CampaignMessagePort.PURPOSE_MARKETING)
                        .isWired())
                .isTrue();

        relay.configured = false;
        var notConfigured = router.wiring(TENANT, BRAND, "EMAIL", CampaignMessagePort.PURPOSE_MARKETING);
        assertThat(notConfigured.isWired()).isFalse();
        assertThat(notConfigured.reason()).isEqualTo("EMAIL_NOT_CONFIGURED");

        relay.configured = true;
        assertThat(router.wiring(TENANT, BRAND, "EMAIL", CampaignMessagePort.PURPOSE_COURIER)
                        .reason())
                .as("the relay carries marketing and nothing else")
                .isEqualTo("EMAIL_PURPOSE_NOT_PERMITTED");

        assertThat(router.reporting(TENANT, BRAND, "EMAIL"))
                .as("SMTP reports neither a delivery nor a read, so the report says «not reported»")
                .isEqualTo(CampaignMessagePort.ChannelReporting.NONE);
    }

    @Test
    @DisplayName("an unconfigured relay refuses at send time with a stable code and sends nothing")
    void anUnconfiguredRelayRefuses() {
        grantEmailConsent();
        relay.configured = false;

        UUID notificationId = enqueue("m1");
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("FAILED_TERMINAL");
        assertThat(relay.sent).isEmpty();
        assertThat(notifications.attempts(TENANT, notificationId).getFirst().failureCode())
                .isEqualTo("EMAIL_NOT_CONFIGURED");
    }

    // ---------------------------------------------------------------- fixtures

    private UUID enqueue(String key) {
        return java.util.Objects.requireNonNull(router.enqueue(new CampaignMessagePort.MarketingMessage(
                TENANT,
                BRAND,
                accountId,
                "EMAIL",
                "PROMO_EMAIL",
                "PROMOTIONS",
                UUID.randomUUID(),
                key,
                Map.of(),
                clock.instant(),
                null)));
    }

    private void grantEmailConsent() {
        grantConsent("EMAIL");
    }

    private void grantConsent(String channel) {
        consentService.record(
                TENANT,
                accountId,
                BRAND,
                "PROMOTIONS",
                channel,
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                "storefront-checkbox",
                NOW.minusSeconds(86_400));
    }

    private boolean consentGranted(String channel) {
        return consentService
                .consentFor(TENANT, accountId, BRAND, "PROMOTIONS", channel)
                .map(state -> state.granted())
                .orElse(false);
    }

    private int withdrawals() {
        return jdbc.sql("SELECT COUNT(*) FROM customer.consent_decisions WHERE decision = 'WITHDRAWN'")
                .query(Integer.class)
                .single();
    }

    private void activateEmailTemplate() {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, "PROMO_EMAIL", NotificationClass.MARKETING, NotificationChannel.EMAIL, "PROMOTIONS");
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required()
                .forEach(locale -> wordings.put(locale, new Wording("Pilot brand: aksiya", "Chegirma 20%")));
        int version = templates.addVersion(TENANT, BRAND, templateId, wordings, Map.of());
        templates.activate(TENANT, BRAND, templateId, version, "copy-approver");
    }

    private static String tokenOf(OutgoingMail mail) {
        String url = java.util.Objects.requireNonNull(mail.unsubscribeUrl());
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private String statusOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().status();
    }

    private @Nullable String suppressionReasonOf(UUID notificationId) {
        return jdbc.sql("SELECT suppression_reason FROM notifications.notifications WHERE id = :id")
                .param("id", notificationId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void makeDue(UUID notificationId) {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now WHERE id = :id")
                .param("id", notificationId)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private String everythingIn(String table) {
        return String.join(
                "|",
                jdbc.sql("SELECT t::text FROM %s t".formatted(table))
                        .query(String.class)
                        .list());
    }

    private void seedTenantAndCustomer(CustomerProfileService profiles) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'pilot', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'pilot-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
        accountId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status)
                VALUES (:id, :tenantId, 'ACTIVE')
                """).param("id", accountId).param("tenantId", TENANT).update();
        profiles.addContactPoint(TENANT, accountId, ContactType.EMAIL, ADDRESS, true);
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE notifications.unsubscribe_tokens, notifications.delivery_status_events, "
                        + "notifications.delivery_attempts, notifications.notifications, "
                        + "notifications.recipient_endpoints, notifications.template_versions, "
                        + "notifications.templates, notifications.notification_preferences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE marketing.suppressions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    /** The relay at the end of the line: records what it was asked to send and answers as told. */
    private static final class RecordingRelay implements PlatformMailer {
        final List<OutgoingMail> sent = new ArrayList<>();
        boolean configured = true;
        MailOutcome outcome = new MailOutcome.Sent();

        @Override
        public MailOutcome send(OutgoingMail mail) {
            sent.add(mail);
            return outcome;
        }

        @Override
        public boolean configured() {
            return configured;
        }
    }

    /** A clock a test advances, so a day is a duration and not an instant. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
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
            return java.util.Objects.requireNonNull(now.get());
        }
    }

    private static final class NoTelegramBindings implements OperationsSubscriptionDirectory {
        @Override
        public List<UUID> subscribedBindings(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String eventClass) {
            return List.of();
        }

        @Override
        public List<ScopedBinding> tenantDigestBindings(UUID tenantId, String eventClass) {
            return List.of();
        }

        @Override
        public List<ScopedBinding> platformDigestBindings(String eventClass) {
            return List.of();
        }
    }
}
