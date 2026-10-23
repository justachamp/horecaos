package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
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
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.customers.application.ConsentService;
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
import uz.horecaos.platform.integration.camel.sms.VasSmsGatewayAdapter;
import uz.horecaos.platform.integration.provider.JdbcProviderActivityRecorder;
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.integration.provider.JdbcSmsAccountLookup;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.api.CourierContactSource;
import uz.horecaos.platform.marketing.application.CourierBroadcastLedger;
import uz.horecaos.platform.marketing.application.CourierBroadcastService;
import uz.horecaos.platform.marketing.application.DeliverabilityFeedbackService;
import uz.horecaos.platform.marketing.application.MarketingSuppressionService;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.CourierBroadcastRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.application.CampaignBlockRateMonitor;
import uz.horecaos.platform.notifications.application.CampaignMessageRouter;
import uz.horecaos.platform.notifications.application.CampaignSmsDeliveryService;
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
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0182, D15, over the real path: a dispatcher's broadcast becomes one ordinary ADR 0020 intent per
 * courier, which leaves through the VAS gateway as a message of the {@code COURIER} purpose, to a
 * number read from the identity provider for that one message and kept nowhere.
 *
 * <p>Real PostgreSQL, the real worker, eligibility, dispatch, gateway and Camel route, and the VAS wire
 * (a recording fake of {@code smsgw.vas.uz}). The identity provider is a fake that holds numbers, because
 * what is under test is what the platform does with them: it asks for them, uses them, and leaves
 * them on no row.
 */
class CourierBroadcastDeliveryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final String NUMBER_ONE = "+998901112233";
    private static final String NUMBER_TWO = "+998909998877";
    /**
     * The test's own clock. The database stamps {@code next_attempt_at} on a new intent with its own
     * {@code now()}, which this fixed clock does not reach, so {@link #send} makes the intents due.
     */
    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");

    private static TestDatabase.Handle db;

    private RecordingSmsGateway gateway;
    private CamelContext camel;
    private JdbcClient jdbc;
    private JdbcNotificationStore notifications;
    private NotificationWorker worker;
    private CampaignMessageRouter router;
    private CourierBroadcastService broadcasts;
    private final FakeIdentityProvider identity = new FakeIdentityProvider();
    private final RecordingAudit audit = new RecordingAudit();
    private UUID installationId;
    private UUID courierType;
    private UUID courierOne;
    private UUID courierTwo;

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
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of(
                        "horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key",
                        "horecaos.secrets.provider_notification.platform.sms", "the-vas-key")::get,
                clock);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        ConsentService consent = new ConsentService(customerStore, clock);
        RecipientContactDirectory contacts = new RecipientContactService(customerStore, protection);

        notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(
                templateStore, objectMapper, clock, AuditTrail.discarding(), AuditTrail.actor("template-author"));

        seedTenant();
        seedVasInstallation("TRANSACTIONAL,COURIER");
        courierOne = seedCourier("K-001");
        courierTwo = seedCourier("K-002");
        identity.numbers.put(courierOne, NUMBER_ONE);
        identity.numbers.put(courierTwo, NUMBER_TWO);
        activateCourierWording(templates);

        ProviderHttpClient http = new ProviderHttpClient(objectMapper, new ProviderExceptionClassifier());
        JdbcProviderInstallationLookup installations =
                new JdbcProviderInstallationLookup(jdbc, clock, new JdbcProviderEnvironmentLookup(jdbc));
        JdbcSmsAccountLookup accounts = new JdbcSmsAccountLookup(jdbc, objectMapper);
        NotificationGateway providerGateway =
                new NotificationGateway(List.of(new VasSmsGatewayAdapter(http)), installations, secrets, accounts);

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
                consent,
                contacts,
                noOrders,
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
                uz.horecaos.platform.notifications.application.EmailEnvelopes.NONE,
                identity,
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
        JdbcCourierBroadcastStore store = new JdbcCourierBroadcastStore(jdbc);
        broadcasts = new CourierBroadcastService(
                store,
                router,
                identity,
                new CourierBroadcastLedger(store, router, audit, clock),
                audit,
                objectMapper,
                clock);
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
    @DisplayName(
            "a broadcast reaches each courier through the VAS gateway as courier traffic, with no consent anywhere")
    void aBroadcastReachesEachCourier() {
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"7001","parts":1}""");
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"7002","parts":1}""");

        CourierBroadcastRow sent = send(draft());
        worker.drain();

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.recipientCount()).isEqualTo(2);
        assertThat(gateway.callsTo("/send")).isEqualTo(2);
        Map<String, String> textByPhone = gateway.calls().stream()
                .filter(call -> "/send".equals(call.path()))
                .collect(Collectors.toMap(
                        call -> String.valueOf(call.body().get("phone")),
                        call -> String.valueOf(call.body().get("text"))));
        assertThat(textByPhone)
                .as("each courier's own number, and the approved wording with the dispatcher's values")
                .containsOnlyKeys(NUMBER_ONE, NUMBER_TWO)
                .containsEntry(NUMBER_ONE, "Smena: ruxsat 18:00");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer.consent_decisions")
                        .query(Integer.class)
                        .single())
                .as("a courier has no marketing consent and none is invented for them")
                .isZero();

        var results = broadcasts.results(TENANT, sent.id());
        assertThat(results)
                .extracting(CourierBroadcastService.CourierResult::courierReference)
                .containsExactly("K-001", "K-002");
        assertThat(results)
                .extracting(CourierBroadcastService.CourierResult::state)
                .as("a gateway that said «created» has not said «delivered»")
                .containsOnly("HANDED_TO_OPERATOR");
        var attempt =
                notifications.attempts(TENANT, onlyNotificationOf("K-001")).getFirst();
        assertThat(attempt.providerType()).isEqualTo("SMSGW_VAS");
    }

    @Test
    @DisplayName("no number is on any row the platform keeps, and the reads of it are audited without it")
    void noNumberIsKept() {
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"7003","parts":1}""");
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"7004","parts":1}""");

        send(draft());
        worker.drain();

        for (String table : List.of(
                "notifications.notifications",
                "notifications.delivery_attempts",
                "notifications.delivery_status_events",
                "notifications.recipient_endpoints",
                "marketing.courier_broadcasts",
                "marketing.courier_broadcast_recipients")) {
            assertThat(everythingIn(table))
                    .as(table + " holds the courier's id and not their number (ADR 0029)")
                    .doesNotContain("901112233")
                    .doesNotContain("909998877");
        }
        assertThat(audit.facts.stream().map(Object::toString).collect(Collectors.joining()))
                .doesNotContain("901112233")
                .doesNotContain("909998877");
        assertThat(identity.numberReads)
                .as("one read of a courier's number per message, each for its purpose")
                .containsExactlyInAnyOrder(
                        courierOne + "/COURIER_BROADCAST_DELIVERY", courierTwo + "/COURIER_BROADCAST_DELIVERY");
        assertThat(jdbc.sql("SELECT endpoint_type FROM notifications.recipient_endpoints")
                        .query(String.class)
                        .list())
                .containsOnly("COURIER");
    }

    @Test
    @DisplayName("an account not cleared for courier traffic is refused before anything is read or sent")
    void anUnclearedAccountIsRefused() {
        allowPurposes("TRANSACTIONAL,MARKETING");
        UUID broadcastId = draft().id();

        ApiException failure =
                catchThrowableOfType(() -> broadcasts.send(TENANT, broadcastId, AUTHOR), ApiException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
        assertThat(failure.getMessage()).contains("SMS_PURPOSE_NOT_PERMITTED");
        assertThat(gateway.callsTo("/send")).isZero();
        assertThat(identity.addressableAsked)
                .as("the identity provider was not asked")
                .isZero();
        assertThat(router.wiring(TENANT, BRAND, "SMS", CampaignMessagePort.PURPOSE_COURIER)
                        .reason())
                .isEqualTo("SMS_PURPOSE_NOT_PERMITTED");
        assertThat(router.wiring(TENANT, BRAND, "SMS", CampaignMessagePort.PURPOSE_MARKETING)
                        .isWired())
                .as("marketing is a different purpose, and this account was cleared for it separately")
                .isTrue();
    }

    @Test
    @DisplayName(
            "an identity provider that fails when a message is sent is retried later, never a courier with no number")
    void anOutageAtSendTimeIsRetried() {
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"7005","parts":1}""");
        gateway.reply("/send", """
                {"status":{"code":0,"description":"success"},"id":"7006","parts":1}""");
        CourierBroadcastRow sent = send(draft());
        identity.numberReadsFail = true;

        worker.drain();

        assertThat(gateway.callsTo("/send")).as("nothing was sent to anyone").isZero();
        assertThat(onlyNotificationOf("K-001"))
                .satisfies(id -> assertThat(statusOf(id)).isEqualTo("RETRY_PENDING"));
        assertThat(broadcasts.results(TENANT, sent.id()))
                .extracting(CourierBroadcastService.CourierResult::state)
                .as("still on its way, and not reported as failed or as having no number")
                .containsOnly("PENDING");

        identity.numberReadsFail = false;
        makeDue();
        worker.drain();

        assertThat(gateway.callsTo("/send")).isEqualTo(2);
        assertThat(statusOf(onlyNotificationOf("K-001"))).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a courier whose number went away between the send and the message is rejected, not retried")
    void aNumberThatWentAway() {
        CourierBroadcastRow sent = send(draft());
        identity.numbers.remove(courierTwo);

        worker.drain();

        assertThat(statusOf(onlyNotificationOf("K-002"))).isEqualTo("FAILED_TERMINAL");
        assertThat(broadcasts.results(TENANT, sent.id()))
                .filteredOn(result -> "K-002".equals(result.courierReference()))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.state()).isEqualTo("REJECTED");
                    assertThat(result.reason()).isEqualTo("RECIPIENT_UNRESOLVABLE");
                });
    }

    @Test
    @DisplayName("a courier message is not a marketing message: it carries no unsubscribe and answers to no consent")
    void aCourierMessageIsNotMarketing() {
        CourierBroadcastRow sent = send(draft());

        assertThat(jdbc.sql("SELECT DISTINCT notification_class FROM notifications.notifications")
                        .query(String.class)
                        .list())
                .containsExactly("COURIER_DISPATCH");
        assertThat(jdbc.sql("SELECT DISTINCT subject_type FROM notifications.notifications")
                        .query(String.class)
                        .list())
                .containsExactly("CourierBroadcast");
        assertThat(jdbc.sql("SELECT DISTINCT subject_id FROM notifications.notifications")
                        .query(UUID.class)
                        .list())
                .containsExactly(sent.id());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM marketing.campaign_recipients")
                        .query(Integer.class)
                        .single())
                .as("couriers are not a campaign audience (ADR 0112 is not widened)")
                .isZero();
    }

    // ---------------------------------------------------------------- fixtures

    private CourierBroadcastRow draft() {
        UUID id = broadcasts.draft(
                TENANT,
                BRAND,
                "ALL_ACTIVE",
                null,
                "COURIER_SHIFT_CHANGE",
                Map.of("note", "ruxsat", "time", "18:00"),
                AUTHOR);
        return broadcasts.require(TENANT, id);
    }

    private CourierBroadcastRow send(CourierBroadcastRow draft) {
        CourierBroadcastRow sent = broadcasts.send(TENANT, draft.id(), AUTHOR);
        makeDue();
        return sent;
    }

    private void activateCourierWording(NotificationTemplateService templates) {
        UUID templateId = templates.createTemplate(
                TENANT,
                BRAND,
                "COURIER_SHIFT_CHANGE",
                NotificationClass.COURIER_DISPATCH,
                NotificationChannel.SMS,
                null);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, "Smena: {{note}} {{time}}")));
        int version =
                templates.addVersion(TENANT, BRAND, templateId, wordings, Map.of("note", "string", "time", "string"));
        templates.activate(TENANT, BRAND, templateId, version, "copy-approver");
    }

    private UUID onlyNotificationOf(String courierReference) {
        return jdbc.sql("""
                SELECT r.notification_id FROM marketing.courier_broadcast_recipients r
                 WHERE r.courier_reference = :reference
                """)
                .param("reference", courierReference)
                .query(UUID.class)
                .single();
    }

    private String statusOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().status();
    }

    private void makeDue() {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now")
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

    private void seedTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'pilot', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'pilot-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
        courierType = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :tenantId, 'BIKE', 'Bicycle', 'BICYCLE')
                """).param("id", courierType).param("tenantId", TENANT).update();
    }

    private UUID seedCourier(String reference) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id, principal_subject,
                    display_reference, protected_full_name, status, version)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'encrypted-placeholder', 'ACTIVE', 1)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("typeId", courierType)
                .param("subject", "subject-" + reference)
                .param("reference", reference)
                .update();
        return id;
    }

    /** A real VAS installation: login and sender in its non-secret configuration, the key behind a reference. */
    private void seedVasInstallation(String purposes) {
        installationId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
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
                        CAST(:config AS jsonb))
                """)
                .param("id", installationId)
                .param("tenantId", TENANT)
                .param(
                        "config",
                        "{\"login\":\"horecaos\",\"sender\":\"16888\",\"permittedPurposes\":\"%s\"}"
                                .formatted(purposes))
                .update();
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
        jdbc.sql("TRUNCATE TABLE marketing.courier_broadcast_recipients, marketing.courier_broadcasts CASCADE")
                .update();
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
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers, fulfillment.courier_groups CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    /** The identity provider: holds numbers, records how it was asked, and can be made to fail. */
    private static final class FakeIdentityProvider implements CourierContactSource {
        final Map<UUID, String> numbers = new LinkedHashMap<>();
        final List<String> numberReads = new CopyOnWriteArrayList<>();
        boolean numberReadsFail;
        int addressableAsked;

        @Override
        public boolean isWired() {
            return true;
        }

        @Override
        public Set<UUID> addressable(UUID tenantId, Collection<UUID> courierIds, String purpose) {
            addressableAsked++;
            return courierIds.stream().filter(numbers::containsKey).collect(Collectors.toSet());
        }

        @Override
        public Optional<String> number(UUID tenantId, UUID courierId, String purpose) {
            if (numberReadsFail) {
                throw new UnavailableException("the identity provider is down", new IllegalStateException());
            }
            numberReads.add(courierId + "/" + purpose);
            return Optional.ofNullable(numbers.get(courierId));
        }
    }

    private static final class RecordingAudit implements AuditRecorder {
        final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
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
