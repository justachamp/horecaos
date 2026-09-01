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
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.api.StopListPort;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.StopListPortAdapter;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.customers.api.RecipientContactDirectory.ContactEndpoint;
import uz.horecaos.platform.customers.api.RecipientContactDirectory.ContactMethod;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.camel.notification.CamelNotificationTransport;
import uz.horecaos.platform.integration.camel.notification.NotificationGateway;
import uz.horecaos.platform.integration.camel.notification.NotificationProcessor;
import uz.horecaos.platform.integration.camel.notification.NotificationRouteBuilder;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.integration.camel.notification.telegram.TelegramChannelAdapter;
import uz.horecaos.platform.integration.camel.notification.telegram.TelegramCircuitBreakers;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.integration.provider.telegram.BotActionTokenStore;
import uz.horecaos.platform.integration.provider.telegram.BotCallbackAuthorizer;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramChatLockService;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramMessageTracker;
import uz.horecaos.platform.integration.provider.telegram.TelegramRightsVerifier;
import uz.horecaos.platform.integration.provider.telegram.TelegramStaffLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramUpdateHandler;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;
import uz.horecaos.platform.inventory.api.StockAvailabilityPort;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.application.StockAvailabilityPortAdapter;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.notifications.api.CustomerAlertPort;
import uz.horecaos.platform.notifications.api.CustomerProviderBindingSync;
import uz.horecaos.platform.notifications.application.CustomerAlertFanoutService;
import uz.horecaos.platform.notifications.application.CustomerProviderBindingSyncService;
import uz.horecaos.platform.notifications.application.CustomerTelegramChannelRouter;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationPreferenceService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.application.OperationsAlertFanoutService;
import uz.horecaos.platform.notifications.application.OrderNotificationTrigger;
import uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderAwaitingApproval;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderDecisionPort;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.api.FiscalDocumentIssued;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalDocumentType;
import uz.horecaos.platform.payments.domain.FiscalReason;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.notifications.FiscalCustomerReceiptTrigger;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * ADR 0060's bot, end to end: link a staff account, watch the order
 * notification arrive with Approve/Reject buttons, tap through real
 * authorization and audit, see the keyboard strip on the first decision, a
 * second tap answered with the settler, and a revoked grant's next tap
 * refused — plus the multi-tenant DM picker and the bot's audited stop-list
 * toggle.
 *
 * <p>Order-decision scope: {@link FakeOrderDecisionPort} stands in for
 * {@code OrderDecisionPortAdapter}'s real {@code OrderStateService}, which
 * has its own dedicated first-decision-wins test coverage in {@code
 * ordering}. What this suite proves is the bot's own mechanism — token
 * resolution, live re-authorization on every tap, the first-decision-wins
 * interpretation, keyboard stripping, and revocation — and it proves the
 * fixture writes the identical ADR 0027 audit shape {@code
 * OrderStateService.decide} does, with the actor the bot resolved. {@link
 * OrderDecisionPortAdapter} itself is a direct field-mapping translator with
 * no branching, reviewed rather than separately fixtured here. The stop-list
 * toggle test, by contrast, runs the real {@code InventoryService} — that
 * mutation, and its ADR 0027 audit fact, is this suite's own to prove.
 */
class TelegramInteractiveBotIntegrationTest {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private Clock clock;
    private FakeTelegramBotApi bot;
    private AuditRecorder audit;
    private AuthorizationService authorization;
    private TelegramStaffLinkService staffLinks;
    private TelegramCustomerLinkService customerLinks;
    private CustomerProviderBindingSync bindingSync;
    private BotActionTokenStore actionTokens;
    private FakeOrderDecisionPort orderDecisions;
    private TelegramUpdateHandler updateHandler;
    private TelegramWebhookInstallationLookup webhookInstallations;
    private StockAvailabilityPort stockAvailability;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
        JdbcClient bootstrap = JdbcClient.create(db.dataSource());
        // Shared, static registry — see JdbcAuthorizationServiceTests' own note
        // on why re-synchronising per test costs a real chunk of build time for
        // no benefit: nothing here mutates iam.roles/iam.role_capabilities.
        new RoleRegistrySynchronizer(bootstrap).synchronize();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        bot = FakeTelegramBotApi.start();
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        objectMapper = JsonMapper.builder().build();
        clock = Clock.fixed(Instant.parse("2026-08-31T09:00:00Z"), ZoneOffset.UTC);
        audit = new JdbcAuditRecorder(jdbc, objectMapper);
        authorization = new JdbcAuthorizationService(jdbc, clock, () -> {
            throw new UnsupportedOperationException("not exercised by this suite");
        });
        staffLinks = new TelegramStaffLinkService(jdbc, clock, Duration.ofMinutes(15));
        bindingSync = new CustomerProviderBindingSyncService(
                new JdbcNotificationStore(jdbc),
                new NotificationPreferenceService(new JdbcNotificationStore(jdbc), clock));
        customerLinks = new TelegramCustomerLinkService(
                jdbc, clock, Duration.ofMinutes(15), new TelegramBindingStore(jdbc, clock, audit), bindingSync, audit);
        actionTokens = new BotActionTokenStore(jdbc, clock);
        orderDecisions = new FakeOrderDecisionPort(audit, clock);
        webhookInstallations = new TelegramWebhookInstallationLookup(jdbc);

        JdbcCatalogStore catalogStore = new JdbcCatalogStore(jdbc, objectMapper);
        CatalogAuthoringService catalogAuthoring = new CatalogAuthoringService(catalogStore, audit, clock);
        StopListPort stopList = new StopListPortAdapter(catalogAuthoring);
        InventoryService inventory = new InventoryService(new JdbcInventoryStore(jdbc), event -> {}, clock, audit);
        stockAvailability = new StockAvailabilityPortAdapter(inventory);

        BotCallbackAuthorizer callbackAuthorizer = new BotCallbackAuthorizer(
                actionTokens, staffLinks, authorization, new AlwaysEntitledService(), orderDecisions, clock);

        updateHandler = new TelegramUpdateHandler(
                new TelegramLinkService(jdbc, clock, Duration.ofMinutes(15)),
                staffLinks,
                customerLinks,
                new TelegramRightsVerifier(new TelegramBotApiClient(objectMapper)),
                new TelegramBindingStore(jdbc, clock, audit),
                actionTokens,
                callbackAuthorizer,
                authorization,
                new AlwaysEntitledService(),
                new NoSummaryOrderDirectory(),
                new NoOpContactDirectory(),
                stopList,
                stockAvailability,
                new TelegramBotApiClient(objectMapper),
                secretResolver(),
                audit,
                clock,
                "en");
    }

    @Test
    @DisplayName("link, notify with buttons, decide, strip, settle a late tap, then refuse a revoked one")
    void theWholeStory() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        long groupChatId = -100_555_001L;
        long staffTelegramUserId = 42_042L;
        String staffSubject = UUID.randomUUID().toString();

        seedTenancy(tenant, brand, location, "story-tenant", "STORY");
        UUID installationId = seedTelegramInstallation(tenant, "story-bot-env");
        activateAwaitingApprovalTemplate(tenant, brand);
        grant(staffSubject, PlatformRole.LOCATION_STAFF, location, tenant);

        NotificationGateway gateway = notificationGateway();
        CamelContext camel = new DefaultCamelContext();
        camel.addRoutes(new NotificationRouteBuilder(new NotificationProcessor(gateway, new SimpleMeterRegistry())));
        camel.start();
        CamelNotificationTransport transport = new CamelNotificationTransport(camel.createProducerTemplate(), gateway);

        JdbcNotificationStore notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(templateStore, objectMapper, clock);
        // NotificationEligibilityService.evaluate reads OrderDirectory.summary
        // even for an OPERATIONS_ALERT-class intent (it throws if the order is
        // not visible to the tenant, per its own comment) — unlike counts(),
        // this is not a default method, so the bot's own no-summary stub will
        // not do here.
        StubOrderDirectory orderSummaries = new StubOrderDirectory();
        orderSummaries.publish(new OrderDirectory.OrderSummary(
                orderId, tenant, brand, location, "A-1", null, "guest", "AWAITING_APPROVAL", "UZS", 50_000L, 1));
        NotificationEligibilityService eligibility = new NotificationEligibilityService(
                notifications,
                templates,
                (t, a, b, p, c) -> Optional.empty(),
                new NoOpContactDirectory(),
                orderSummaries,
                transport,
                objectMapper,
                clock,
                "en");
        NotificationDispatchService dispatch = new NotificationDispatchService(
                notifications,
                templateStore,
                new NoOpContactDirectory(),
                transport,
                objectMapper,
                clock,
                8,
                Duration.ofSeconds(30));
        NotificationWorker worker =
                new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));
        OperationsAlertFanoutService fanout = new OperationsAlertFanoutService(
                new uz.horecaos.platform.integration.provider.telegram.TelegramOperationsSubscriptionDirectory(
                        new TelegramBindingStore(jdbc, clock, audit)),
                notifications,
                new TelegramOperationsEntitlementGate(new AlwaysEntitledService()),
                objectMapper,
                clock);
        CustomerTelegramChannelRouter channelRouter =
                new CustomerTelegramChannelRouter(notifications, new AlwaysEntitledService());
        OrderNotificationTrigger trigger = new OrderNotificationTrigger(
                notifications, fanout, orderSummaries, channelRouter, objectMapper, clock, "SMS", Duration.ofHours(6));

        // Bind the group directly (skips the group /link handshake, which
        // ADR 0058's own suite already covers) and subscribe it to the new
        // event class.
        TelegramBindingStore bindingStore = new TelegramBindingStore(jdbc, clock, audit);
        UUID bindingId = bindingStore.createBinding(tenant, installationId, brand, location, groupChatId, null, null);
        bindingStore.subscribe(tenant, bindingId, java.util.Set.of("ORDER_AWAITING_APPROVAL"));

        // The staff member links their own Telegram account from a 1:1 chat.
        String code = staffLinks.issueCode(tenant, staffSubject);
        updateHandler.handle(installation(installationId, tenant), privateLinkUpdate(code, staffTelegramUserId));
        assertThat(bot.messagesSentTo(staffTelegramUserId)).hasSize(1);

        // The order needs a decision; the notification carries buttons.
        trigger.onOrderingEvent(new OrderAwaitingApproval(
                UUID.randomUUID(),
                new TenantId(tenant),
                orderId,
                clock.instant(),
                brand,
                location,
                "OPERATIONS",
                clock.instant().plus(Duration.ofMinutes(10)),
                "AUTO_CONFIRM",
                "AWAITING_APPROVAL",
                1));
        drainUntilQuiet(worker);

        assertThat(bot.messagesSentTo(groupChatId)).hasSize(1);
        long messageId = java.util.Objects.requireNonNull(bot.lastMessageIdSentTo(groupChatId));
        assertThat(bot.hasKeyboard(messageId)).isTrue();
        List<String> tokens = bot.callbackDataOn(messageId);
        assertThat(tokens).hasSize(2);
        String approveToken = tokens.get(0);
        String rejectToken = tokens.get(1);

        // The linked staff member taps Approve.
        updateHandler.handle(
                installation(installationId, tenant),
                callbackQueryUpdate("cbq-1", approveToken, groupChatId, messageId, staffTelegramUserId, null));

        assertThat(bot.answeredCallbackQueryIds()).contains("cbq-1");
        assertThat(bot.hasKeyboard(messageId))
                .as("stripped on the first successful decision")
                .isFalse();
        assertThat(lastMessageTo(groupChatId)).contains("approved");
        assertThat(auditRowsFor(orderId)).hasSize(1).allSatisfy(row -> {
            assertThat(row.get("actor_subject")).isEqualTo(staffSubject);
            assertThat(row.get("outcome")).isEqualTo("SUCCEEDED");
        });

        // A second tap — a different button, a different chat's render of the
        // same order in spirit — is told who already decided it.
        updateHandler.handle(
                installation(installationId, tenant),
                callbackQueryUpdate("cbq-2", rejectToken, groupChatId, messageId, staffTelegramUserId, null));

        assertThat(bot.answeredCallbackQueryIds()).contains("cbq-2");
        assertThat(lastMessageTo(groupChatId)).contains("already").contains("approved");
        assertThat(auditRowsFor(orderId)).hasSize(2); // the loser is audited too

        // Revoke the grant; the next tap on the (already-settled) Approve
        // button is refused before it ever reaches the order-decision port.
        revokeGrantsFor(staffSubject);
        updateHandler.handle(
                installation(installationId, tenant),
                callbackQueryUpdate("cbq-3", approveToken, groupChatId, messageId, staffTelegramUserId, null));

        assertThat(bot.answeredCallbackQueryIds()).contains("cbq-3");
        assertThat(lastMessageTo(groupChatId)).contains("not authorized");
        // Unauthorized taps are not recorded as ordering decisions.
        assertThat(auditRowsFor(orderId)).hasSize(2);
    }

    @Test
    @DisplayName("an ambiguous DM command from an account linked to two tenants is answered with a picker")
    void ambiguousDmCommandShowsATenantPicker() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID brandA = UUID.randomUUID();
        UUID brandB = UUID.randomUUID();
        UUID locationA = UUID.randomUUID();
        UUID locationB = UUID.randomUUID();
        long telegramUserId = 77_077L;
        String subjectA = UUID.randomUUID().toString();
        String subjectB = UUID.randomUUID().toString();

        seedTenancy(tenantA, brandA, locationA, "picker-tenant-a", "PICKA");
        seedTenancy(tenantB, brandB, locationB, "picker-tenant-b", "PICKB");
        grant(subjectA, PlatformRole.LOCATION_STAFF, locationA, tenantA);
        grant(subjectB, PlatformRole.LOCATION_STAFF, locationB, tenantB);

        String codeA = staffLinks.issueCode(tenantA, subjectA);
        String codeB = staffLinks.issueCode(tenantB, subjectB);
        // Both codes are redeemed against whichever installation the webhook
        // authenticates as; installation tenancy is what proves the code, not
        // the update body — see handleStaffLink's cross-tenant refusal.
        UUID installationA = seedTelegramInstallation(tenantA, "picker-bot-a");
        UUID installationB = seedTelegramInstallation(tenantB, "picker-bot-b");
        updateHandler.handle(installation(installationA, tenantA), privateLinkUpdate(codeA, telegramUserId));
        updateHandler.handle(installation(installationB, tenantB), privateLinkUpdate(codeB, telegramUserId));

        assertThat(staffLinks.tenantsFor(telegramUserId)).hasSize(2);

        updateHandler.handle(installation(installationA, tenantA), privateCommandUpdate("/stats", telegramUserId));

        List<String> messages = bot.messagesSentTo(telegramUserId);
        // [0] the first /link ack, [1] the second /link ack, [2] the picker.
        assertThat(messages).hasSize(3);
        long messageId = java.util.Objects.requireNonNull(bot.lastMessageIdSentTo(telegramUserId));
        List<String> pickerTokens = bot.callbackDataOn(messageId);
        assertThat(pickerTokens).hasSize(2);

        updateHandler.handle(
                installation(installationA, tenantA),
                callbackQueryUpdate("cbq-pick", pickerTokens.get(0), telegramUserId, messageId, telegramUserId, null));

        assertThat(bot.answeredCallbackQueryIds()).contains("cbq-pick");
        assertThat(lastMessageTo(telegramUserId)).contains("New");
    }

    @Test
    @DisplayName("the bot's typed /86 command reaches the same audited toggle as the web screen")
    void the86CommandAuditsThroughTheBot() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        long telegramUserId = 88_088L;
        String staffSubject = UUID.randomUUID().toString();

        seedTenancy(tenant, brand, location, "eightysix-tenant", "EIGHTY6");
        grant(staffSubject, PlatformRole.LOCATION_MANAGER, location, tenant);
        StockedVariant variant = seedStockedVariant(tenant, brand, location);

        String code = staffLinks.issueCode(tenant, staffSubject);
        updateHandler.handle(installation(UUID.randomUUID(), tenant), privateLinkUpdate(code, telegramUserId));

        updateHandler.handle(installation(UUID.randomUUID(), tenant), privateCommandUpdate("/86", telegramUserId));
        assertThat(lastMessageTo(telegramUserId)).contains(variant.reference());

        updateHandler.handle(
                installation(UUID.randomUUID(), tenant),
                privateCommandUpdate("/86 " + variant.reference(), telegramUserId));

        assertThat(lastMessageTo(telegramUserId)).contains("unavailable");
        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT actor_subject, action_code FROM audit.audit_events
                WHERE target_id = :variantId AND action_code = 'inventory.availability.set'
                """).param("variantId", variant.variantId()).query().listOfRows();
        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.get("actor_subject")).isEqualTo(staffSubject));
    }

    @Test
    @DisplayName(
            "a customer's own linked chat gets order and receipt messages, then a 403 revokes it and the next order falls back to SMS")
    void customerLinkRoutesAndRevokesOn403() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID orderId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();
        UUID orderId3 = UUID.randomUUID();
        long customerTelegramUserId = 55_055L;

        seedTenancy(tenant, brand, location, "customer-link-tenant", "CLINK");
        UUID installationId = seedTelegramInstallation(tenant, "customer-link-bot-env");
        UUID customerAccountId = seedCustomerAccount(tenant);

        activateTemplate(
                tenant,
                brand,
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.TELEGRAM,
                "Order confirmed",
                Map.of());
        activateTemplate(
                tenant,
                brand,
                FiscalCustomerReceiptTrigger.FISCAL_RECEIPT_ISSUED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.TELEGRAM,
                "Receipt: {{receiptUrl}}",
                Map.of("receiptUrl", "The OFD receipt link"));

        NotificationGateway gateway = notificationGateway();
        CamelContext camel = new DefaultCamelContext();
        camel.addRoutes(new NotificationRouteBuilder(new NotificationProcessor(gateway, new SimpleMeterRegistry())));
        camel.start();
        CamelNotificationTransport transport = new CamelNotificationTransport(camel.createProducerTemplate(), gateway);

        JdbcNotificationStore notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(templateStore, objectMapper, clock);

        StubOrderDirectory orderSummaries = new StubOrderDirectory();
        orderSummaries.publish(new OrderDirectory.OrderSummary(
                orderId1, tenant, brand, location, "A-100", customerAccountId, null, "CONFIRMED", "UZS", 100_000L, 1));
        orderSummaries.publish(new OrderDirectory.OrderSummary(
                orderId2, tenant, brand, location, "A-200", customerAccountId, null, "CONFIRMED", "UZS", 120_000L, 1));
        orderSummaries.publish(new OrderDirectory.OrderSummary(
                orderId3, tenant, brand, location, "A-300", customerAccountId, null, "CONFIRMED", "UZS", 130_000L, 1));

        NotificationEligibilityService eligibility = new NotificationEligibilityService(
                notifications,
                templates,
                (t, a, b, p, c) -> Optional.empty(),
                new NoOpContactDirectory(),
                orderSummaries,
                transport,
                objectMapper,
                clock,
                "en");
        NotificationDispatchService dispatch = new NotificationDispatchService(
                notifications,
                templateStore,
                new NoOpContactDirectory(),
                transport,
                objectMapper,
                clock,
                8,
                Duration.ofSeconds(30));
        NotificationWorker worker =
                new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));
        OperationsAlertFanoutService opsFanout = new OperationsAlertFanoutService(
                new uz.horecaos.platform.integration.provider.telegram.TelegramOperationsSubscriptionDirectory(
                        new TelegramBindingStore(jdbc, clock, audit)),
                notifications,
                new TelegramOperationsEntitlementGate(new AlwaysEntitledService()),
                objectMapper,
                clock);

        CustomerTelegramChannelRouter channelRouter =
                new CustomerTelegramChannelRouter(notifications, new AlwaysEntitledService());
        OrderNotificationTrigger orderTrigger = new OrderNotificationTrigger(
                notifications,
                opsFanout,
                orderSummaries,
                channelRouter,
                objectMapper,
                clock,
                "SMS",
                Duration.ofHours(6));

        CustomerAlertPort customerAlerts =
                new CustomerAlertFanoutService(notifications, orderSummaries, channelRouter, objectMapper, "SMS");
        // A second reader, sharing the same database as the writer below, so
        // FiscalCustomerReceiptTrigger#onDocumentIssued can look the document
        // back up without this fixture needing to route a real Spring
        // ApplicationEvent back into the object that published it.
        PaymentFiscalService fiscalReader =
                new PaymentFiscalService(new JdbcFiscalDocumentStore(jdbc), List.of(), event -> {});
        FiscalCustomerReceiptTrigger fiscalTrigger =
                new FiscalCustomerReceiptTrigger(customerAlerts, fiscalReader, Duration.ofDays(3));
        ApplicationEventPublisher events = event -> {
            if (event instanceof FiscalDocumentIssued issued) {
                fiscalTrigger.onDocumentIssued(issued);
            }
        };
        PaymentFiscalService fiscalService =
                new PaymentFiscalService(new JdbcFiscalDocumentStore(jdbc), List.of(), events);

        // The customer mints a deep-link code under their own storefront
        // session and opens it — /start arrives unprompted, exactly the way
        // Telegram delivers it the moment a customer taps the link.
        String code = customerLinks.issueCode(tenant, brand, customerAccountId);
        updateHandler.handle(installation(installationId, tenant), privateStartUpdate(code, customerTelegramUserId));
        assertThat(bot.messagesSentTo(customerTelegramUserId)).hasSize(1);

        UUID bindingId = jdbc.sql("""
                SELECT binding_id FROM integration.telegram_bindings
                WHERE tenant_id = :tenantId AND chat_id = :chatId AND audience = 'CUSTOMER' AND retired_at IS NULL
                """)
                .param("tenantId", tenant)
                .param("chatId", customerTelegramUserId)
                .query(UUID.class)
                .single();
        Map<String, Object> endpointRow = jdbc.sql("""
                SELECT customer_account_id, status FROM notifications.recipient_endpoints
                WHERE tenant_id = :tenantId AND provider_binding_id = :bindingId
                """)
                .param("tenantId", tenant)
                .param("bindingId", bindingId)
                .query()
                .singleRow();
        assertThat(endpointRow.get("customer_account_id")).isEqualTo(customerAccountId);
        assertThat(endpointRow.get("status")).isEqualTo("ACTIVE");

        // An order-confirmed intent for this customer routes to TELEGRAM and
        // lands in their own 1:1 chat.
        orderTrigger.onOrderingEvent(new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(tenant),
                orderId1,
                clock.instant(),
                brand,
                location,
                "AUTO_CONFIRM",
                null,
                clock.instant(),
                "UZS",
                100_000L,
                "CONFIRMED",
                1));
        drainUntilQuiet(worker);
        assertThat(bot.messagesSentTo(customerTelegramUserId)).hasSize(2);
        assertThat(orderChannel(orderId1, OrderNotificationTrigger.ORDER_CONFIRMED))
                .isEqualTo("TELEGRAM");

        // The fiscal document behind the same order reaches ISSUED; the
        // customer gets the OFD link in the same chat — "a legal artifact,
        // not a courtesy" (ADR 0058). fk_fiscal_document_order needs a real
        // order row, unlike every other order in this test.
        seedOrder(tenant, brand, location, orderId1, customerAccountId, 100_000L);
        UUID documentId = UUID.randomUUID();
        new JdbcFiscalDocumentStore(jdbc)
                .insert(new FiscalDocument(
                        documentId,
                        tenant,
                        orderId1,
                        null,
                        null,
                        null,
                        PaymentProviderType.CLICK,
                        FiscalDocumentType.SALE,
                        null,
                        FiscalStatus.PENDING,
                        FiscalReason.AWAITING_CAPTURE,
                        "test",
                        List.of(),
                        null,
                        1,
                        clock.instant()));
        boolean issued = fiscalService.attachEvidence(
                tenant,
                documentId,
                new FiscalDocument.FiscalEvidence(
                        "REC-1", "SIGN-1", "T-1", "R-1", clock.instant(), "https://ofd.soliq.uz/epi?t=1", "0", "OK"),
                "protected-ref",
                clock.instant());
        assertThat(issued).isTrue();
        drainUntilQuiet(worker);
        assertThat(lastMessageTo(customerTelegramUserId)).contains("https://ofd.soliq.uz/epi?t=1");

        // The customer blocks the bot. The next send to their chat retires
        // the binding and — because it is a customer's own — flips the
        // TELEGRAM preference off too: "a customer-binding 403 is consent
        // revocation in effect" (ADR 0058).
        bot.kick(customerTelegramUserId, false);
        orderTrigger.onOrderingEvent(new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(tenant),
                orderId2,
                clock.instant(),
                brand,
                location,
                "AUTO_CONFIRM",
                null,
                clock.instant(),
                "UZS",
                120_000L,
                "CONFIRMED",
                1));
        drainUntilQuiet(worker);

        Map<String, Object> retiredBinding = jdbc.sql("""
                SELECT retired_at, retired_reason FROM integration.telegram_bindings
                WHERE tenant_id = :tenantId AND binding_id = :bindingId
                """)
                .param("tenantId", tenant)
                .param("bindingId", bindingId)
                .query()
                .singleRow();
        assertThat(retiredBinding.get("retired_at")).isNotNull();

        Map<String, Object> retiredEndpoint = jdbc.sql("""
                SELECT status FROM notifications.recipient_endpoints
                WHERE tenant_id = :tenantId AND provider_binding_id = :bindingId
                """)
                .param("tenantId", tenant)
                .param("bindingId", bindingId)
                .query()
                .singleRow();
        assertThat(retiredEndpoint.get("status")).isEqualTo("RETIRED");

        List<Map<String, Object>> preferenceRows = jdbc.sql("""
                SELECT notification_class, enabled FROM notifications.notification_preferences
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND channel = 'TELEGRAM'
                """)
                .param("tenantId", tenant)
                .param("accountId", customerAccountId)
                .query()
                .listOfRows();
        assertThat(preferenceRows)
                .extracting(row -> row.get("notification_class"))
                .containsExactlyInAnyOrder("TRANSACTIONAL_OPTIONAL", "MARKETING");
        assertThat(preferenceRows)
                .allSatisfy(row -> assertThat(row.get("enabled")).isEqualTo(false));

        // The next order-confirmed intent for the same customer, created
        // after the link is gone, routes to SMS from the moment it is
        // created — the "next intent falls back" half of this build's
        // channel-selection design, distinct from the in-flight
        // NO_RECIPIENT_ENDPOINT suppression an already-TELEGRAM-routed
        // intent gets when its binding dies mid-flight.
        orderTrigger.onOrderingEvent(new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(tenant),
                orderId3,
                clock.instant(),
                brand,
                location,
                "AUTO_CONFIRM",
                null,
                clock.instant(),
                "UZS",
                130_000L,
                "CONFIRMED",
                1));
        assertThat(orderChannel(orderId3, OrderNotificationTrigger.ORDER_CONFIRMED))
                .isEqualTo("SMS");
    }

    // ------------------------------------------------------------------- helpers

    /**
     * @param installationId only used to satisfy the tenant it is stamped
     *                       with — {@code TelegramUpdateHandler} trusts the
     *                       webhook controller's own secret-token check, and
     *                       this fixture is that check's stand-in
     */
    private WebhookInstallation installation(UUID installationId, UUID tenantId) {
        return new WebhookInstallation(
                installationId,
                tenantId,
                "TELEGRAM_BOT_API",
                "ACTIVE",
                bot.baseUrl(),
                secretRef(tenantId),
                secretRef(tenantId));
    }

    private static Map<String, Object> privateLinkUpdate(String code, long userId) {
        return Map.of(
                "update_id",
                1,
                "message",
                Map.of(
                        "text", "/link " + code,
                        "chat", Map.of("id", userId, "type", "private"),
                        "from", Map.of("id", userId)));
    }

    private static Map<String, Object> privateStartUpdate(String code, long userId) {
        return Map.of(
                "update_id",
                1,
                "message",
                Map.of(
                        "text", "/start " + code,
                        "chat", Map.of("id", userId, "type", "private"),
                        "from", Map.of("id", userId)));
    }

    private static Map<String, Object> privateCommandUpdate(String text, long userId) {
        return Map.of(
                "update_id",
                1,
                "message",
                Map.of(
                        "text", text,
                        "chat", Map.of("id", userId, "type", "private"),
                        "from", Map.of("id", userId)));
    }

    private static Map<String, Object> callbackQueryUpdate(
            String callbackQueryId,
            String token,
            long chatId,
            long messageId,
            long fromUserId,
            @Nullable Integer topicId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("message_id", messageId);
        message.put("chat", Map.of("id", chatId));
        if (topicId != null) {
            message.put("message_thread_id", topicId);
        }
        return Map.of(
                "update_id",
                2,
                "callback_query",
                Map.of(
                        "id", callbackQueryId,
                        "from", Map.of("id", fromUserId),
                        "message", message,
                        "data", token));
    }

    private String lastMessageTo(long chatId) {
        List<String> messages = bot.messagesSentTo(chatId);
        assertThat(messages).isNotEmpty();
        return messages.get(messages.size() - 1);
    }

    private String orderChannel(UUID orderId, String templateKey) {
        return jdbc.sql("""
                SELECT channel FROM notifications.notifications
                WHERE subject_id = :orderId AND template_key = :templateKey
                """)
                .param("orderId", orderId)
                .param("templateKey", templateKey)
                .query(String.class)
                .single();
    }

    private List<Map<String, Object>> auditRowsFor(UUID orderId) {
        return jdbc.sql("""
                SELECT actor_subject, outcome FROM audit.audit_events
                WHERE action_code = 'ordering.order.approval-decision' AND target_id = :orderId
                ORDER BY occurred_at
                """).param("orderId", orderId).query().listOfRows();
    }

    private NotificationGateway notificationGateway() {
        TelegramBotApiClient botApiClient = new TelegramBotApiClient(objectMapper);
        TelegramBindingStore bindingStore = new TelegramBindingStore(jdbc, clock, audit);
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                botApiClient,
                bindingStore,
                new TelegramChatLockService(jdbc, clock),
                new TelegramMessageTracker(jdbc, clock),
                new TelegramCircuitBreakers(new SimpleMeterRegistry(), clock),
                actionTokens,
                bindingSync,
                clock,
                Duration.ofSeconds(20),
                Duration.ofHours(6),
                "en");
        return new NotificationGateway(
                List.of(adapter), new JdbcProviderInstallationLookup(jdbc, clock), secretResolver());
    }

    private void activateAwaitingApprovalTemplate(UUID tenantId, UUID brandId) {
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(templateStore, objectMapper, clock);
        UUID templateId = templates.createTemplate(
                tenantId,
                brandId,
                OrderNotificationTrigger.ORDER_AWAITING_APPROVAL,
                NotificationClass.OPERATIONS_ALERT,
                NotificationChannel.TELEGRAM,
                null);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, "Order needs a decision")));
        int version = templates.addVersion(tenantId, templateId, wordings, Map.of());
        templates.activate(tenantId, templateId, version, "test");
    }

    /** A generic sibling of {@link #activateAwaitingApprovalTemplate}, for a template that names variables. */
    private void activateTemplate(
            UUID tenantId,
            UUID brandId,
            String templateKey,
            NotificationClass notificationClass,
            NotificationChannel channel,
            String body,
            Map<String, String> declaredVariables) {
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(templateStore, objectMapper, clock);
        UUID templateId = templates.createTemplate(tenantId, brandId, templateKey, notificationClass, channel, null);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, body)));
        int version = templates.addVersion(tenantId, templateId, wordings, declaredVariables);
        templates.activate(tenantId, templateId, version, "test");
    }

    /**
     * A real {@code ordering.orders} row — needed only for the one order this
     * suite attaches a real {@code fiscal.fiscal_documents} row to;
     * {@code fk_fiscal_document_order} enforces it. Every other order in this
     * suite is a {@link StubOrderDirectory} entry with no backing row, which
     * is sufficient everywhere {@code OrderDirectory} is the only reader.
     */
    private void seedOrder(
            UUID tenantId, UUID brandId, UUID locationId, UUID orderId, UUID customerAccountId, long totalMinor) {
        UUID channel = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID cart = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channel).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MENU', 'Menu', 'ACTIVE')
                """)
                .param("id", catalog)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalog)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE', :publicationId, 1,
                    'hash', :totalMinor, 0, :totalMinor, now() + interval '1 day')
                """)
                .param("id", quote)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("publicationId", publication)
                .param("totalMinor", totalMinor)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId,
                    :accountId, 'DELIVERY', 'UZS', 'CHECKOUT_IN_PROGRESS', :quoteId, 'hash', :publicationId,
                    now() + interval '1 day')
                """)
                .param("id", cart)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelId", channel)
                .param("accountId", customerAccountId)
                .param("quoteId", quote)
                .param("publicationId", publication)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (
                    id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, customer_account_id, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    confirmed_at)
                VALUES (
                    :id, 'A-CUST', :tenantId, :brandId, :locationId, :channelId, 'WEB', :accountId,
                    'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'CONFIRMED', 'UZS', :totalMinor, 0, :totalMinor,
                    :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey, now())
                """)
                .param("id", orderId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("channelId", channel)
                .param("accountId", customerAccountId)
                .param("totalMinor", totalMinor)
                .param("quoteId", quote)
                .param("publicationId", publication)
                .param("cartId", cart)
                .param("idempotencyKey", UUID.randomUUID().toString())
                .update();
    }

    private UUID seedCustomerAccount(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer.customer_accounts (id, tenant_id) VALUES (:id, :tenantId)")
                .param("id", id)
                .param("tenantId", tenantId)
                .update();
        return id;
    }

    private SecretResolver secretResolver() {
        return new EnvironmentSecretResolver(key -> "a-test-bot-token", clock);
    }

    private String secretRef(UUID tenantId) {
        return "horecaos:local:provider_notification:platform:telegram-bot";
    }

    private UUID seedTelegramInstallation(UUID tenantId, String environmentCode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                ON CONFLICT (code) DO UPDATE SET base_url = EXCLUDED.base_url
                """)
                .param("code", environmentCode)
                .param("baseUrl", bot.baseUrl())
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', :env,
                        'Test bot', 'ACTIVE', :secret, :secret)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("env", environmentCode)
                .param("secret", secretRef(tenantId))
                .update();
        return id;
    }

    private void grant(String subject, PlatformRole role, UUID locationId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test', 'test grant', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", locationId)
                .param(
                        "validFrom",
                        OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofHours(1)), ZoneOffset.UTC))
                .update();
    }

    private void revokeGrantsFor(String subject) {
        jdbc.sql("UPDATE iam.grants SET status = 'REVOKED' WHERE principal_subject = :subject")
                .param("subject", subject)
                .update();
    }

    private record StockedVariant(UUID variantId, String reference) {}

    private StockedVariant seedStockedVariant(UUID tenantId, UUID brandId, UUID locationId) {
        JdbcCatalogStore catalogStore = new JdbcCatalogStore(jdbc, objectMapper);
        CatalogAuthoringService authoring = new CatalogAuthoringService(catalogStore, audit, clock);
        InventoryService inventory = new InventoryService(new JdbcInventoryStore(jdbc), event -> {}, clock, audit);

        UUID catalogId = authoring.createCatalog(tenantId, brandId, "MAIN", "Main menu", "en");
        var plov = authoring.createProduct(
                tenantId,
                brandId,
                catalogId,
                "PLOV",
                "Plov",
                null,
                "en",
                "SKU-PLOV",
                "PIECE",
                FiscalClassification.unclassified(),
                UUID.randomUUID());
        authoring.setOffering(
                tenantId, brandId, locationId, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DINE_IN"));
        inventory.listVariantAtLocation(tenantId, brandId, locationId, plov.defaultVariantId(), TrackingMode.BINARY);

        UUID variantId = plov.defaultVariantId();
        return new StockedVariant(
                variantId, variantId.toString().replace("-", "").substring(0, 8));
    }

    private void seedTenancy(UUID tenantId, UUID brandId, UUID locationId, String tenantSlug, String brandCode) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", tenantSlug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", brandCode)
                .param("slug", brandCode.toLowerCase(java.util.Locale.ROOT))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("slug", brandCode.toLowerCase(java.util.Locale.ROOT) + "-loc")
                .update();
    }

    private void drainUntilQuiet(NotificationWorker worker) {
        for (int round = 0; round < 5 && nonTerminalTelegramCount() > 0; round++) {
            forceAllDue();
            worker.drain();
        }
    }

    private long nonTerminalTelegramCount() {
        Long count = jdbc.sql("""
                SELECT count(*) FROM notifications.notifications
                WHERE channel = 'TELEGRAM'
                  AND status NOT IN ('DELIVERED', 'FAILED_TERMINAL', 'EXPIRED', 'SUPPRESSED', 'MANUAL_REVIEW')
                """).query(Long.class).single();
        return count == null ? 0 : count;
    }

    private void forceAllDue() {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now WHERE channel = 'TELEGRAM'")
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE integration.telegram_tracked_messages, integration.telegram_chat_locks, "
                        + "integration.telegram_binding_events, integration.telegram_bindings, "
                        + "integration.telegram_pending_links, integration.telegram_staff_link_codes, "
                        + "integration.telegram_staff_links, integration.bot_action_tokens CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, notifications.delivery_attempts, "
                        + "notifications.notifications, notifications.recipient_endpoints, "
                        + "notifications.notification_preferences, "
                        + "notifications.template_versions, notifications.templates CASCADE")
                .update();
        // payments.fiscal_documents is a compatibility view over the real
        // table (V0039 moved it to fiscal.fiscal_documents); TRUNCATE targets
        // the real table.
        jdbc.sql("TRUNCATE TABLE fiscal.fiscal_documents CASCADE").update();
        // seedOrder's own prerequisite chain, for the one order this suite
        // attaches a real fiscal document to.
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts, pricing.quotes, "
                        + "catalog.publications, tenant.sales_channels CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.location_offerings, catalog.translations, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        // Deliberately not tenant.tenants: TRUNCATE ... CASCADE empties every
        // table with a foreign key into it, including iam.roles
        // (fk_role_tenant) — even the platform-defined rows, whose own
        // tenant_id is NULL. That would silently undo startDatabase()'s
        // one-time RoleRegistrySynchronizer.synchronize() on the very first
        // @BeforeEach. Each test method mints its own fresh tenant/brand/
        // location UUIDs, so a previous test's tenant rows are simply
        // unreferenced leftovers, not a fixture collision.
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
    }

    // ---------------------------------------------------------------- fakes

    private static final class NoOpContactDirectory implements RecipientContactDirectory {
        @Override
        public Optional<ContactEndpoint> primaryContact(UUID tenantId, UUID accountId, ContactMethod method) {
            return Optional.empty();
        }

        @Override
        public Optional<String> resolveValue(UUID tenantId, UUID contactPointId, String purpose) {
            return Optional.empty();
        }

        @Override
        public Optional<String> preferredLocale(UUID tenantId, UUID accountId) {
            return Optional.empty();
        }
    }

    private static final class NoSummaryOrderDirectory implements OrderDirectory {
        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.empty();
        }
    }

    /** Publishable order summaries, for the one caller that needs one: NotificationEligibilityService.evaluate. */
    private static final class StubOrderDirectory implements OrderDirectory {
        private final Map<UUID, OrderSummary> summaries = new ConcurrentHashMap<>();

        void publish(OrderSummary summary) {
            summaries.put(summary.orderId(), summary);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.ofNullable(summaries.get(orderId))
                    .filter(row -> row.tenantId().equals(tenantId));
        }
    }

    private static final class AlwaysEntitledService implements EntitlementService {
        @Override
        public EntitlementSnapshot snapshot(UUID tenantId) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key) {
            return true;
        }

        @Override
        public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {}
    }

    /**
     * A faithful in-memory stand-in for {@code OrderStateService.decide}'s
     * compare-and-set semantics — see the class doc for what this is and is
     * not testing. Same rules: a decisionId retried returns the identical
     * result; the first decisionId to reach a given order wins; every loser
     * is audited too, exactly as {@code OrderStateService.decide}'s own
     * comment insists ("who tried to reject this order, and when").
     */
    private static final class FakeOrderDecisionPort implements OrderDecisionPort {
        private final AuditRecorder audit;
        private final Clock clock;
        private final Map<UUID, Decision> settledByOrder = new ConcurrentHashMap<>();
        private final Map<String, Decision> byDecisionId = new ConcurrentHashMap<>();

        FakeOrderDecisionPort(AuditRecorder audit, Clock clock) {
            this.audit = audit;
            this.clock = clock;
        }

        @Override
        public synchronized Decision decide(UUID tenantId, UUID orderId, DecisionCommand command) {
            Decision cached = byDecisionId.get(command.decisionId());
            if (cached != null) {
                return cached;
            }

            Decision existing = settledByOrder.get(orderId);
            Decision result;
            String outcome;
            if (existing != null) {
                result = new Decision(false, existing.status(), existing.orderVersion(), existing.settledBy());
                outcome = "REJECTED";
            } else {
                String status = command.action() == Action.APPROVE ? "CONFIRMED" : "REJECTED";
                SettledBy settledBy =
                        new SettledBy(command.decisionId(), command.action().name(), command.actorId());
                result = new Decision(true, status, 2, settledBy);
                settledByOrder.put(orderId, result);
                outcome = "SUCCEEDED";
            }
            byDecisionId.put(command.decisionId(), result);

            audit.record(uz.horecaos.platform.audit.api.AuditFact.of(
                            "ordering.order.approval-decision", uz.horecaos.platform.audit.api.AuditClass.BUSINESS)
                    .by(uz.horecaos.platform.audit.api.ActorRef.user(command.actorId(), null))
                    .at(ResourceScope.tenant(tenantId))
                    .target("ordering.order", orderId)
                    .because("Telegram bot decision (fixture)")
                    .outcome(
                            "SUCCEEDED".equals(outcome)
                                    ? uz.horecaos.platform.audit.api.AuditFact.Outcome.SUCCEEDED
                                    : uz.horecaos.platform.audit.api.AuditFact.Outcome.REJECTED)
                    .correlatedBy(orderId.toString())
                    .occurredAt(clock.instant())
                    .build());

            return result;
        }
    }
}
