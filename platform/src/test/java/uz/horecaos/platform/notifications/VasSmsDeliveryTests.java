package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.notification.CamelNotificationTransport;
import uz.horecaos.platform.integration.camel.notification.NotificationGateway;
import uz.horecaos.platform.integration.camel.notification.NotificationProcessor;
import uz.horecaos.platform.integration.camel.notification.NotificationRouteBuilder;
import uz.horecaos.platform.integration.camel.sms.RecordingSmsGateway;
import uz.horecaos.platform.integration.camel.sms.SmsGateway;
import uz.horecaos.platform.integration.camel.sms.SmsVerificationOperation;
import uz.horecaos.platform.integration.camel.sms.VasSmsGatewayAdapter;
import uz.horecaos.platform.integration.provider.JdbcProviderActivityRecorder;
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.integration.provider.JdbcSmsAccountLookup;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.application.DeliverabilityFeedbackService;
import uz.horecaos.platform.marketing.application.MarketingSuppressionService;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.application.CampaignBlockRateMonitor;
import uz.horecaos.platform.notifications.application.CampaignMessageRouter;
import uz.horecaos.platform.notifications.application.CampaignSmsDeliveryService;
import uz.horecaos.platform.notifications.application.CustomerTelegramChannelRouter;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.application.OperationsAlertFanoutService;
import uz.horecaos.platform.notifications.application.OrderNotificationTrigger;
import uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * ADR 0146's exit criteria, over the real dispatch path: a tenant bound to the VAS
 * gateway receives an order confirmation by SMS on the very binding that carries its
 * customers' sign-in codes, and no message is ever sent twice because a response was
 * lost.
 *
 * <p>The same ADR 0020 slice {@link NotificationDeliveryTests} runs against the
 * generic fake, with {@code VasSmsGatewayAdapter} in its place and a fake that
 * speaks smsgw.vas.uz's own wire: a real installation, binding and environment row
 * (the approved base URL is the fake's, inserted by the test), real envelope
 * encryption for the number, and a real {@code /search} to resolve what a lost reply
 * left behind.
 */
class VasSmsDeliveryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String PHONE = "+998901234567";
    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");

    private static TestDatabase.Handle db;

    private RecordingSmsGateway gateway;
    private CamelContext camel;
    private JdbcClient jdbc;
    private JdbcNotificationStore notifications;
    private NotificationWorker worker;
    private OrderNotificationTrigger trigger;
    private SimpleMeterRegistry meters;
    private SmsGateway verificationGateway;
    private CampaignMessageRouter router;
    private ConsentService consentService;
    private UUID installationId;

    private UUID accountId;
    private UUID bindingId;
    private UUID orderId;

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
        gateway = RecordingSmsGateway.start();
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        meters = new SimpleMeterRegistry();

        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of(
                        "horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key",
                        "horecaos.secrets.provider_notification.platform.sms", "the-vas-key")::get,
                clock);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        CustomerProfileService profiles = new CustomerProfileService(
                customerStore, protection, objectMapper, clock, new JdbcAuditRecorder(jdbc, objectMapper));
        ConsentService consent = new ConsentService(customerStore, clock);
        consentService = consent;
        RecipientContactDirectory contacts = new RecipientContactService(customerStore, protection);

        notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(
                templateStore, objectMapper, clock, AuditTrail.discarding(), AuditTrail.actor("template-author"));

        seedTenantAndCustomer(profiles);
        seedVasInstallation();

        ProviderHttpClient http = new ProviderHttpClient(objectMapper, new ProviderExceptionClassifier());
        JdbcProviderInstallationLookup installations =
                new JdbcProviderInstallationLookup(jdbc, clock, new JdbcProviderEnvironmentLookup(jdbc));
        JdbcSmsAccountLookup accounts = new JdbcSmsAccountLookup(jdbc, objectMapper);
        VasSmsGatewayAdapter vas = new VasSmsGatewayAdapter(http);
        NotificationGateway providerGateway = new NotificationGateway(List.of(vas), installations, secrets, accounts);
        verificationGateway = new SmsGateway(installations, accounts, secrets, vas);

        camel = new DefaultCamelContext();
        camel.addRoutes(
                new NotificationRouteBuilder(new NotificationProcessor(providerGateway, new SimpleMeterRegistry())));
        camel.start();
        CamelNotificationTransport transport = new CamelNotificationTransport(
                camel.createProducerTemplate(), providerGateway, new JdbcProviderActivityRecorder(jdbc), clock);

        StubOrders orders = new StubOrders();
        orderId = UUID.randomUUID();
        orders.publish(new OrderDirectory.OrderSummary(
                orderId, TENANT, BRAND, LOCATION, "A-17", accountId, null, "CONFIRMED", "UZS", 12_500_000L, 3));

        OperationsAlertFanoutService operationsAlerts = new OperationsAlertFanoutService(
                new NoTelegramBindings(),
                notifications,
                new TelegramOperationsEntitlementGate(new AlwaysEntitledService()),
                objectMapper,
                clock);
        CampaignBlockRateMonitor blockRate =
                new CampaignBlockRateMonitor(new AlwaysSendingCampaignFeedback(), operationsAlerts, meters);
        NotificationEligibilityService eligibility = new NotificationEligibilityService(
                notifications,
                templates,
                consent,
                contacts,
                orders,
                transport,
                new AlwaysSendingCampaignFeedback(),
                objectMapper,
                clock,
                "ru");

        JdbcEngagementStore engagement = new JdbcEngagementStore(jdbc);
        DeliverabilityFeedbackService deliverability = new DeliverabilityFeedbackService(
                new MarketingSuppressionService(engagement, new JdbcAuditRecorder(jdbc, objectMapper), clock),
                engagement,
                clock);
        NotificationDispatchService dispatch = new NotificationDispatchService(
                notifications,
                templateStore,
                contacts,
                transport,
                blockRate,
                deliverability,
                meters,
                objectMapper,
                clock,
                8,
                Duration.ofSeconds(30));

        worker = new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));
        router = new CampaignMessageRouter(
                List.of(new CampaignSmsDeliveryService(
                        notifications, templates, objectMapper, clock, Duration.ofDays(1))),
                transport,
                notifications,
                new JdbcDeliveryReceiptStore(jdbc));
        activatePromotionTemplate(templates);
        trigger = new OrderNotificationTrigger(
                notifications,
                operationsAlerts,
                orders,
                new CustomerTelegramChannelRouter(notifications, new AlwaysEntitledService()),
                objectMapper,
                clock,
                "SMS",
                Duration.ofHours(6));

        UUID templateId = templates.createTemplate(
                TENANT,
                BRAND,
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required()
                .forEach(locale ->
                        wordings.put(locale, new Wording(null, "Buyurtma {{orderNumber}}: {{amount}} {{currency}}")));
        int version = templates.addVersion(
                TENANT,
                BRAND,
                templateId,
                wordings,
                Map.of("orderNumber", "string", "amount", "string", "currency", "string", "reasonCode", "string"));
        templates.activate(TENANT, BRAND, templateId, version, "copy-approver");
    }

    @AfterEach
    void tearDown() {
        if (camel != null) {
            camel.stop();
        }
        if (gateway != null) {
            gateway.close();
        }
    }

    @Test
    @DisplayName("an order confirmation leaves through the VAS binding that also carries sign-in codes")
    void oneBindingCarriesCodesAndOrderMessages() {
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"5981980","parts":1}""");

        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        UUID notificationId = onlyNotification();
        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        var attempt = notifications.attempts(TENANT, notificationId).getFirst();
        assertThat(attempt.status())
                .as("a gateway that said 'created' has not said delivered")
                .isEqualTo("ACCEPTED");
        assertThat(attempt.providerType()).isEqualTo("SMSGW_VAS");
        assertThat(attempt.providerBindingId()).isEqualTo(bindingId);
        assertThat(attempt.externalMessageId()).isEqualTo("5981980");
        assertThat(segmentsOf(attempt.id())).isEqualTo(1);
        assertThat(notifications.statusEvents(TENANT, attempt.id()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.normalizedStatus()).isEqualTo("ACCEPTED");
                    assertThat(event.providerStatus()).isEqualTo("CREATED");
                });
        assertThat(gateway.callTo("/send").body())
                .containsEntry("login", "horecaos")
                .containsEntry("sender", "16888")
                .containsEntry("key", "the-vas-key");

        // The same binding, now for a sign-in code. No second account, no second
        // installation: the notification route used to refuse this binding with
        // PROVIDER_ADAPTER_MISMATCH and the verification route used to be the only
        // thing that could speak to it.
        var code = verificationGateway.send(new SmsVerificationOperation(
                SmsVerificationOperation.Kind.SEND,
                TENANT,
                BRAND,
                UUID.randomUUID(),
                PHONE,
                "482913",
                "Code 482913",
                NOW));
        assertThat(code.status().name()).isEqualTo("SUCCESS");
        assertThat(gateway.callsTo("/send")).isEqualTo(2);
        assertThat(gateway.calls().get(1).body())
                .containsEntry("sender", "16888")
                .containsEntry("login", "horecaos");
    }

    @Test
    @DisplayName("a lost reply is resolved from what the gateway already holds, and nothing is sent twice")
    void aLostReplyIsFoundNotResent() {
        gateway.dropConnectionAfterReceiving("/send");

        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        UUID notificationId = onlyNotification();
        assertThat(statusOf(notificationId)).isEqualTo("UNCERTAIN");
        assertThat(gateway.callsTo("/send")).isEqualTo(1);

        String sentText = String.valueOf(gateway.callTo("/send").body().get("text"));
        gateway.reply("/search", """
                {"status":{"code":0,"description":"success"},
                 "data":[{"id":777,"msg":"someone else's message","send_dt":1,"status":4},
                         {"id":5981990,"msg":"%s","send_dt":2,"status":3}]}""".formatted(sentText));

        makeDue(notificationId);
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        assertThat(gateway.callsTo("/send"))
                .as("the reconcile asked, it did not send")
                .isEqualTo(1);
        var attempt = notifications.attempts(TENANT, notificationId).getFirst();
        assertThat(attempt.externalMessageId()).isEqualTo("5981990");
        assertThat(attempt.status()).isEqualTo("ACCEPTED");
        assertThat(notifications.statusEvents(TENANT, attempt.id()))
                .extracting(event -> event.normalizedStatus())
                .containsExactly("DISPATCHED");
        assertThat(gateway.callTo("/search").body()).containsEntry("phone", PHONE);
    }

    @Test
    @DisplayName("a search that finds nothing stays unknown however often it is asked, and never resends")
    void absenceNeverLicensesAResend() {
        gateway.dropConnectionAfterReceiving("/send");
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();
        UUID notificationId = onlyNotification();

        gateway.reply("/search", """
                {"status":{"code":0,"description":"success"},"data":[]}""");
        for (int round = 0; round < 3; round++) {
            makeDue(notificationId);
            worker.drain();
        }

        assertThat(gateway.callsTo("/send")).isEqualTo(1);
        assertThat(gateway.callsTo("/search")).isEqualTo(3);
        assertThat(statusOf(notificationId)).isIn("UNCERTAIN", "RETRY_PENDING", "MANUAL_REVIEW");
        assertThat(notifications.attempts(TENANT, notificationId)).hasSize(1);
    }

    @Test
    @DisplayName("a message found blacklisted fails terminally, resends nothing, and suppresses that customer's SMS")
    void aBlacklistedMessageSuppressesNarrowly() {
        gateway.dropConnectionAfterReceiving("/send");
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();
        UUID notificationId = onlyNotification();

        String sentText = String.valueOf(gateway.callTo("/send").body().get("text"));
        gateway.reply("/search", """
                {"status":{"code":0,"description":"success"},
                 "data":[{"id":9001,"msg":"%s","send_dt":2,"status":7}]}""".formatted(sentText));
        makeDue(notificationId);
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("FAILED_TERMINAL");
        var attempt = notifications.attempts(TENANT, notificationId).getFirst();
        assertThat(attempt.status()).isEqualTo("FAILED");
        assertThat(attempt.failureCode()).isEqualTo("RECEIVER_UNREACHABLE");
        assertThat(gateway.callsTo("/send")).isEqualTo(1);

        var suppression = jdbc.sql("""
                SELECT reason, channel, brand_id, applied_by_type, customer_account_id
                  FROM marketing.suppressions WHERE tenant_id = :tenantId
                """)
                .param("tenantId", TENANT)
                .query((row, number) -> Map.of(
                        "reason", row.getString("reason"),
                        "channel", row.getString("channel"),
                        "by", row.getString("applied_by_type"),
                        "account", row.getObject("customer_account_id", UUID.class),
                        "brand", String.valueOf(row.getObject("brand_id"))))
                .list();
        assertThat(suppression).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("reason", "HARD_BOUNCE").containsEntry("channel", "SMS");
            assertThat(row).containsEntry("by", "PROVIDER").containsEntry("account", accountId);
            assertThat(row)
                    .as("an unreachable number is unreachable for every brand")
                    .containsEntry("brand", "null");
        });
    }

    @Test
    @DisplayName("a gateway that bills a different number of segments than the platform estimated is a metric")
    void aSegmentMismatchIsCounted() {
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"5981981","parts":3}""");

        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        UUID notificationId = onlyNotification();
        assertThat(segmentsOf(notifications
                        .attempts(TENANT, notificationId)
                        .getFirst()
                        .id()))
                .isEqualTo(3);
        assertThat(meters.get("horecaos.sms.segments.mismatch")
                        .tag("provider", "SMSGW_VAS")
                        .tag("estimate", "under")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "a brand whose account is cleared for transactional SMS only is not wired for a campaign, and says why")
    void aCampaignChannelIsWiredPerBrandAndPurpose() {
        assertThat(router.wiring(TENANT, BRAND, "SMS", "MARKETING"))
                .isEqualTo(CampaignMessagePort.Wiring.no("SMS_PURPOSE_NOT_PERMITTED"));
        assertThat(router.wiring(TENANT, BRAND, "SMS", "COURIER").reason()).isEqualTo("SMS_PURPOSE_NOT_PERMITTED");
        assertThat(router.wiring(TENANT, UUID.randomUUID(), "SMS", "MARKETING").reason())
                .as("an adapter in this build is not an account for that brand")
                .isEqualTo("NO_PROVIDER_BINDING");
        assertThat(router.wiring(TENANT, BRAND, "EMAIL", "MARKETING").reason()).isEqualTo("NO_DELIVERY_ADAPTER");
        assertThat(router.isWired(TENANT, BRAND, "SMS")).isFalse();

        allowPurposes("TRANSACTIONAL, MARKETING");

        assertThat(router.isWired(TENANT, BRAND, "SMS")).isTrue();
        assertThat(router.wiring(TENANT, BRAND, "SMS", "COURIER").reason())
                .as("marketing and courier are separate answers")
                .isEqualTo("SMS_PURPOSE_NOT_PERMITTED");
    }

    @Test
    @DisplayName(
            "a marketing message that slips past the check is refused at send with the same code, and nothing leaves")
    void aMarketingMessageIsRefusedAtTheGatewayToo() {
        grantMarketingConsent();
        UUID notificationId = enqueue("campaign-refused");

        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("FAILED_TERMINAL");
        assertThat(notifications.find(TENANT, notificationId).orElseThrow().lastError())
                .isEqualTo("SMS_PURPOSE_NOT_PERMITTED");
        assertThat(gateway.callsTo("/send")).isZero();
    }

    @Test
    @DisplayName(
            "once the installation names marketing, a campaign message is sent, and its recipient shows 'handed to the operator'")
    void aClearedAccountCarriesACampaignMessage() {
        allowPurposes("TRANSACTIONAL, MARKETING");
        grantMarketingConsent();
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"6100","parts":1}""");
        UUID notificationId = enqueue("campaign-ok");
        assertThat(enqueue("campaign-ok"))
                .as("the same key is the same message, not a second one")
                .isEqualTo(notificationId);

        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        assertThat(gateway.callsTo("/send")).isEqualTo(1);
        assertThat(String.valueOf(gateway.callTo("/send").body().get("text"))).isEqualTo("Chegirma 20%");
        var evidence = java.util.Objects.requireNonNull(
                router.deliveryEvidence(TENANT, List.of(notificationId)).get(notificationId));
        assertThat(evidence.state())
                .as("accepted by the gateway, and nothing has reported on it: neither delivered nor failed")
                .isEqualTo("HANDED_TO_OPERATOR");
        assertThat(evidence.segmentsBilled()).isEqualTo(1);
        assertThat(evidence.receiptState()).isNull();

        // A receipt is what moves it, and the recipient list reads it from the
        // attempt rather than from a copy.
        jdbc.sql("UPDATE notifications.delivery_attempts SET status = 'DELIVERED', acknowledged_at = now() "
                        + "WHERE notification_id = :id")
                .param("id", notificationId)
                .update();
        assertThat(java.util.Objects.requireNonNull(router.deliveryEvidence(TENANT, List.of(notificationId))
                                .get(notificationId))
                        .state())
                .isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a cleared account does not relax consent: no decision, no message")
    void consentStillDecides() {
        allowPurposes("TRANSACTIONAL, MARKETING");
        UUID notificationId = enqueue("campaign-no-consent");

        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("SUPPRESSED");
        assertThat(notifications.find(TENANT, notificationId).orElseThrow().suppressionReason())
                .isEqualTo("CONSENT_WITHHELD");
        assertThat(gateway.callsTo("/send")).isZero();
    }

    @Test
    @DisplayName("no number, text or credential is written to any notification table")
    void nothingPersonalIsKept() {
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"5981982","parts":1}""");
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        String everything = everythingIn("notifications.delivery_attempts")
                + everythingIn("notifications.delivery_status_events")
                + everythingIn("notifications.notifications");
        assertThat(everything)
                .doesNotContain("998901234567")
                .doesNotContain("the-vas-key")
                .doesNotContain("Buyurtma A-17");
    }

    // ------------------------------------------------------------------- helpers

    private OrderConfirmed orderConfirmed() {
        return new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(TENANT),
                orderId,
                NOW,
                BRAND,
                LOCATION,
                "RESTAURANT_APPROVAL",
                "HORECAOS_OPERATIONS",
                NOW,
                "UZS",
                12_500_000L,
                "CONFIRMED",
                3);
    }

    private UUID enqueue(String key) {
        return java.util.Objects.requireNonNull(router.enqueue(marketing(key)));
    }

    private void allowPurposes(String purposes) {
        jdbc.sql("""
                UPDATE integration.installations
                   SET non_sensitive_config = CAST(:config AS jsonb)
                 WHERE id = :id
                """)
                .param(
                        "config",
                        "{\"login\":\"horecaos\",\"sender\":\"16888\",\"permittedPurposes\":\"%s\"}"
                                .formatted(purposes))
                .param("id", installationId)
                .update();
    }

    private void grantMarketingConsent() {
        consentService.record(
                TENANT,
                accountId,
                BRAND,
                "PROMOTIONS",
                "SMS",
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                "storefront-checkbox",
                NOW.minusSeconds(86_400));
    }

    private CampaignMessagePort.MarketingMessage marketing(String key) {
        return new CampaignMessagePort.MarketingMessage(
                TENANT,
                BRAND,
                accountId,
                "SMS",
                "PROMO_SMS",
                "PROMOTIONS",
                UUID.randomUUID(),
                key,
                Map.of(),
                NOW,
                null);
    }

    private void activatePromotionTemplate(NotificationTemplateService templates) {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, "PROMO_SMS", NotificationClass.MARKETING, NotificationChannel.SMS, "PROMOTIONS");
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, "Chegirma 20%")));
        int version = templates.addVersion(TENANT, BRAND, templateId, wordings, Map.of());
        templates.activate(TENANT, BRAND, templateId, version, "copy-approver");
    }

    private UUID onlyNotification() {
        return jdbc.sql("SELECT id FROM notifications.notifications")
                .query(UUID.class)
                .single();
    }

    private String statusOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().status();
    }

    private @Nullable Integer segmentsOf(UUID attemptId) {
        return jdbc.sql("SELECT provider_segments FROM notifications.delivery_attempts WHERE id = :id")
                .param("id", attemptId)
                .query((row, number) -> row.getObject("provider_segments", Integer.class))
                .single();
    }

    private void makeDue(UUID notificationId) {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now WHERE id = :id")
                .param("id", notificationId)
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
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
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :tenantId, :brandId, 'PILOT', 'pilot-location', 'Pilot location',
                    'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        accountId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status)
                VALUES (:id, :tenantId, 'ACTIVE')
                """).param("id", accountId).param("tenantId", TENANT).update();
        profiles.addContactPoint(TENANT, accountId, ContactType.PHONE, PHONE, true);
    }

    /** A real VAS installation: login and sender in its non-secret configuration, the key behind a reference. */
    private void seedVasInstallation() {
        installationId = UUID.randomUUID();
        bindingId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('vas-fake', 'NOTIFICATION', 'SMSGW_VAS', :baseUrl, false, '127.0.0.1')
                """).param("baseUrl", gateway.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, non_sensitive_config)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'SMSGW_VAS', 'vas-fake', 'Pilot VAS', 'ACTIVE',
                        'horecaos:local:provider_notification:platform:sms',
                        CAST('{"login":"horecaos","sender":"16888"}' AS jsonb))
                """).param("id", installationId).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (
                    id, tenant_id, installation_id, brand_id, status, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE', :from)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("from", OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities (
                    binding_id, tenant_id, capability_code, enabled, is_primary)
                VALUES (:bindingId, :tenantId, :capability, true, true)
                """)
                .param("bindingId", bindingId)
                .param("tenantId", TENANT)
                .param("capability", NotificationGateway.SEND_SMS)
                .update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, "
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

    private static final class StubOrders implements OrderDirectory {
        private final Map<UUID, OrderSummary> summaries = new LinkedHashMap<>();

        void publish(OrderSummary summary) {
            summaries.put(summary.orderId(), summary);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.ofNullable(summaries.get(orderId))
                    .filter(summary -> summary.tenantId().equals(tenantId));
        }
    }
}
