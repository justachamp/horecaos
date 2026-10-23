package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import uz.horecaos.platform.notifications.api.CourierFirstNameDirectory;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.application.CampaignBlockRateMonitor;
import uz.horecaos.platform.notifications.application.CustomerTelegramChannelRouter;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationQueryService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.application.OperationsAlertFanoutService;
import uz.horecaos.platform.notifications.application.OrderNotificationTrigger;
import uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.PlatformDefaultTemplates;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderFulfilling;
import uz.horecaos.platform.ordering.api.OrderPreparing;
import uz.horecaos.platform.ordering.api.OrderReady;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBrandLocaleLookup;

/**
 * Gap-map row {@code 10.9a}, ADR 0184: what a customer is told about an order, from the fact to the
 * handset.
 *
 * <p>The real path (trigger, eligibility, consent gate, resolution, render, dispatch) over a real
 * PostgreSQL, ending at {@link NotificationTransport} with a transport that keeps the {@link
 * NotificationDispatch} it was handed. The rendered text is the thing under test here, and the SMS
 * gateway fake records recipients and not bodies, so the port is the right place to stand: it is the
 * only object in the system that holds the sentence and the recipient at once. The only other stand-in
 * is {@link OrderDirectory}, for the reason {@code NotificationDeliveryTests} gives.
 *
 * <p>Three properties decide whether this feature is safe to ship and are each asserted against every
 * column of the notifications table and not only the one named: a customer's name and a courier's first
 * name are never written down (D21), the platform's wording is only ever sent to a customer-addressed
 * row of the class it was written for (D20/ADR 0020), and a customer whose language the brand does not
 * serve is written to in the brand's.
 */
class OrderMilestoneNotificationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String PHONE = "+998901234567";
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");
    private static final String PROGRESS = PlatformDefaultTemplates.ORDER_PROGRESS_PURPOSE;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private JdbcNotificationStore notifications;
    private NotificationTemplateService templates;
    private NotificationWorker worker;
    private NotificationQueryService queries;
    private OrderNotificationTrigger trigger;
    private ConsentService consent;
    private CustomerProfileService profiles;
    private StubOrders orders;
    private CapturingTransport transport;
    private StubCourierNames courierNames;

    private UUID accountId;
    private UUID orderId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for notification tests");
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
        truncate();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        objectMapper = JsonMapper.builder().build();

        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get, clock);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));
        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        profiles = new CustomerProfileService(
                customerStore, protection, objectMapper, clock, new JdbcAuditRecorder(jdbc, objectMapper));
        consent = new ConsentService(customerStore, clock);
        RecipientContactDirectory contacts = new RecipientContactService(customerStore, protection);

        notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        templates = new NotificationTemplateService(
                templateStore,
                objectMapper,
                clock,
                AuditTrail.discarding(),
                AuditTrail.actor("template-author"),
                new JdbcBrandLocaleLookup(jdbc));

        seedTenantAndCustomer();

        orders = new StubOrders();
        orderId = UUID.randomUUID();
        orders.publish(
                new OrderDirectory.OrderSummary(
                        orderId, TENANT, BRAND, LOCATION, "A-17", accountId, null, "CONFIRMED", "UZS", 12_500_000L, 3),
                FulfillmentMode.DELIVERY);

        transport = new CapturingTransport();
        courierNames = new StubCourierNames();

        OperationsAlertFanoutService operationsAlerts = new OperationsAlertFanoutService(
                new NoTelegramBindings(),
                notifications,
                new TelegramOperationsEntitlementGate(new AlwaysEntitledService()),
                objectMapper,
                clock);
        CampaignBlockRateMonitor campaignBlockRate = new CampaignBlockRateMonitor(
                new AlwaysSendingCampaignFeedback(), operationsAlerts, new SimpleMeterRegistry());
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
        NotificationDispatchService dispatch = new NotificationDispatchService(
                notifications,
                templateStore,
                contacts,
                courierNames,
                transport,
                campaignBlockRate,
                (tenantId, brandId, account, channel, correlationId) -> false,
                new SimpleMeterRegistry(),
                objectMapper,
                clock,
                8,
                Duration.ofSeconds(30));
        worker = new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));
        queries = new NotificationQueryService(notifications, clock);
        trigger = new OrderNotificationTrigger(
                notifications,
                operationsAlerts,
                orders,
                new CustomerTelegramChannelRouter(notifications, new AlwaysEntitledService()),
                objectMapper,
                clock,
                "SMS",
                Duration.ofHours(6));
    }

    // ------------------------------------------------- the platform default layer

    @Test
    @DisplayName("ADR 0184: a tenant that wrote nothing still sends the confirmation, from the platform's wording")
    void aTenantWithNoTemplateIsSentThePlatformsWording() {
        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Заказ A-17 принят. Сумма: 12500000 UZS.");
        UUID id = onlyNotification();
        var row = queries.detail(TENANT, id).orElseThrow().notification();
        assertThat(row.status()).isEqualTo("DELIVERED");
        assertThat(row.templateId()).as("there is no template row to point at").isNull();
        assertThat(row.templateVersion()).isNull();
        assertThat(row.platformDefaultVersion())
                .as("the version of the bundled wording is the one handle on what was sent")
                .isEqualTo(PlatformDefaultTemplates.VERSION);
        assertThat(row.locale()).isEqualTo("ru");
    }

    @Test
    @DisplayName("the platform's wording is in the customer's language, and the Uzbek is the Uzbek")
    void thePlatformsWordingFollowsTheCustomersLanguage() {
        setPreferredLocale("uz");

        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("A-17-buyurtma qabul qilindi. Summa: 12500000 UZS.");
    }

    /**
     * ADR 0091 against ADR 0184: the migrated schema marks the VAS gateway as moderating, and a moderating
     * gateway refuses a wording it has not approved. The platform's bundled sentences are not template
     * versions, so no approval is recorded for them anywhere; sending them anyway meant a provider error
     * per customer after every retry, where ADR 0091 exists to give a reason. This suite truncates the
     * environments (see {@code truncate}) so every other case is about what is sent, and so, until this
     * case, nothing here ever met a gateway that moderates.
     */
    @Test
    @DisplayName("ADR 0091: on a gateway that moderates, the platform's own wording is withheld with the reason named")
    void aModeratingGatewayWithholdsThePlatformsWording() {
        insertSmsEnvironment("moderating-gateway", true);

        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies())
                .as("nothing the gateway has not approved reaches it")
                .isEmpty();
        UUID id = onlyNotification();
        assertThat(reasonOf(id))
                .as("the delivery evidence says the wording awaits the gateway, not that the provider failed")
                .isEqualTo("TEMPLATE_AWAITING_PROVIDER");
        assertThat(queries.detail(TENANT, id).orElseThrow().notification().status())
                .isEqualTo("SUPPRESSED");
    }

    @Test
    @DisplayName("a gateway that does not moderate is sent the platform's wording as before")
    void aGatewayThatDoesNotModerateIsSentThePlatformsWording() {
        insertSmsEnvironment("quiet-gateway", false);

        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Заказ A-17 принят. Сумма: 12500000 UZS.");
    }

    @Test
    @DisplayName("a brand's own template outranks the platform's wording as soon as it is active")
    void aTenantTemplateOutranksThePlatformDefault() {
        activate(
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                null,
                "Mine {{orderNumber}}");

        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Mine A-17");
        var row = queries.detail(TENANT, onlyNotification()).orElseThrow().notification();
        assertThat(row.templateId()).isNotNull();
        assertThat(row.platformDefaultVersion()).isNull();
    }

    @Test
    @DisplayName("ORDER_COMPLETED has no platform default: the rating prompt stays opt-in (ADR 0075)")
    void theRatingPromptIsNotSwitchedOnByTheDefaultLayer() {
        trigger.onOrderingEvent(new uz.horecaos.platform.ordering.api.OrderCompleted(
                UUID.randomUUID(), new TenantId(TENANT), orderId, NOW, BRAND, LOCATION, NOW, "UZS", 12_500_000L, 4));
        worker.drain();

        assertThat(transport.bodies()).isEmpty();
        assertThat(reasonOf(onlyNotification())).isEqualTo("NO_ACTIVE_TEMPLATE");
    }

    // ------------------------------------------------ the three optional milestones

    @Test
    @DisplayName("ADR 0045: the kitchen starting is an optional message, withheld until the customer agreed to it")
    void preparingIsWithheldWithoutConsent() {
        trigger.onOrderingEvent(preparing());
        worker.drain();

        assertThat(transport.bodies()).isEmpty();
        UUID id = onlyNotification();
        assertThat(queries.detail(TENANT, id).orElseThrow().notification().notificationClass())
                .isEqualTo("TRANSACTIONAL_OPTIONAL");
        assertThat(reasonOf(id))
                .as("absence of a decision is withheld, and a tenant can read why the customer heard nothing")
                .isEqualTo("CONSENT_WITHHELD");
    }

    @Test
    @DisplayName("a customer who agreed to order-progress messages is told the kitchen has started")
    void preparingIsSentWithConsent() {
        grantProgressConsent();

        trigger.onOrderingEvent(preparing());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Заказ A-17: мы начали его готовить.");
    }

    @Test
    @DisplayName("ADR 0045: a delivery order is not told it is ready (it is waiting for a courier); a pickup order is")
    void onlyAPickupOrderIsToldItIsReady() {
        grantProgressConsent();

        trigger.onOrderingEvent(ready("DELIVERY"));
        assertThat(notificationCount())
                .as("a delivery order's READY is not a customer message")
                .isZero();

        trigger.onOrderingEvent(ready("PICKUP"));
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Заказ A-17 готов. Можно забирать.");
    }

    @Test
    @DisplayName("a milestone message stops being worth sending after thirty minutes at most")
    void aMilestoneExpiresSoonerThanAConfirmation() {
        trigger.onOrderingEvent(preparing());
        trigger.onOrderingEvent(confirmed());

        assertThat(expiryMinutes("ORDER_PREPARING"))
                .as("«we have started» that arrives once the food is ready is noise")
                .isEqualTo(30);
        assertThat(expiryMinutes("ORDER_CONFIRMED")).isEqualTo(360);
    }

    @Test
    @DisplayName("the milestones are customer-only: nothing is fanned out to a branch's operations chat")
    void aMilestoneIsNotFannedOutToOperations() {
        trigger.onOrderingEvent(preparing());

        assertThat(jdbc.sql(
                                "SELECT count(*) FROM notifications.notifications WHERE notification_class = 'OPERATIONS_ALERT'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName(
            "D21: the courier's first name is in the on-the-way message and in no column of the notifications table")
    void theCouriersFirstNameIsResolvedAtRenderAndNeverStored() {
        grantProgressConsent();
        activate(
                OrderNotificationTrigger.ORDER_FULFILLING,
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                PROGRESS,
                "Заказ {{orderNumber}} везёт {{courierFirstName}}");
        courierNames.answer = Optional.of("Bobur");

        trigger.onOrderingEvent(fulfilling());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Заказ A-17 везёт Bobur");
        assertThat(everythingIn("notifications.notifications"))
                .as("a table a support export reads must not hold a courier's name")
                .doesNotContain("Bobur");
        assertThat(everythingIn("notifications.delivery_attempts")).doesNotContain("Bobur");
    }

    @Test
    @DisplayName("an order with no in-house courier reads as a neutral phrase, never as a hole or a placeholder")
    void withoutACourierTheMessageStillReadsWell() {
        grantProgressConsent();
        activate(
                OrderNotificationTrigger.ORDER_FULFILLING,
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                PROGRESS,
                "Заказ {{orderNumber}} везёт {{courierFirstName}}");
        courierNames.answer = Optional.empty();

        trigger.onOrderingEvent(fulfilling());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Заказ A-17 везёт наш курьер");
    }

    // ------------------------------------------------------------- merge variables

    @Test
    @DisplayName(
            "D21: the customer's name greets them and is stored nowhere; a customer with none reads a neutral word")
    void theCustomersNameIsResolvedAtRenderAndNeverStored() {
        activate(
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                null,
                "{{customerName}}, заказ {{orderNumber}} принят");
        setDisplayName("Aziz Karimov");

        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Aziz Karimov, заказ A-17 принят");
        assertThat(everythingIn("notifications.notifications"))
                .doesNotContain("Aziz")
                .doesNotContain("Karimov");

        transport.clear();
        setDisplayName(null);
        jdbc.sql("DELETE FROM notifications.notifications").update();
        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("клиент, заказ A-17 принят");
    }

    @Test
    @DisplayName("D21: a name an event or trigger tried to put in the frozen variables is removed before it is written")
    void aPersonalValueOnTheRowIsNotFrozen() {
        activate(
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                null,
                "Заказ {{orderNumber}}");
        notifications.createIntent(new NewNotification(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                NotificationClass.TRANSACTIONAL_REQUIRED.name(),
                "SMS",
                OrderNotificationTrigger.ORDER_CONFIRMED,
                "Order",
                orderId,
                null,
                UUID.randomUUID(),
                "ORDER_CONFIRMED:Order:%s:SMS".formatted(orderId),
                "{\"customerName\":\"Leaky Name\",\"courierFirstName\":\"Leaky Courier\",\"reasonCode\":\"X\"}",
                NOW,
                NOW.plus(Duration.ofHours(6)),
                NOW));

        worker.drain();

        assertThat(everythingIn("notifications.notifications"))
                .doesNotContain("Leaky")
                .as("the legitimate variable the trigger supplied is still frozen")
                .contains("reasonCode");
    }

    @Test
    @DisplayName(
            "the order's own facts: restaurant, promised time on the branch's clock, preparation time, discount and fee")
    void theOrderDetailVariablesResolve() {
        orders.facts = Optional.of(new OrderDirectory.NotificationFacts(
                "Oqtepa Lavash",
                "Asia/Tashkent",
                Instant.parse("2026-10-14T14:45:00Z"),
                35,
                "UZS",
                5_000L,
                12_000L,
                List.of(
                        new OrderDirectory.NotificationLine("Lavash", new BigDecimal("2.000"), 50_000L),
                        new OrderDirectory.NotificationLine("Cola", BigDecimal.ONE, 8_000L))));
        activate(
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                null,
                "{{restaurantName}}: к {{deliveryTime}}, готовим {{preparationTime}} мин. Скидка {{discountAmount}}, доставка {{deliveryFee}}.");

        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies())
                .containsExactly("Oqtepa Lavash: к 19:45, готовим 35 мин. Скидка 5000, доставка 12000.");
    }

    @Test
    @DisplayName("the «show item prices» switch is the version's: the same list with and without the prices")
    void theProductListFollowsTheVersionsPriceSwitch() {
        orders.facts = Optional.of(new OrderDirectory.NotificationFacts(
                "Oqtepa Lavash",
                "Asia/Tashkent",
                null,
                null,
                "UZS",
                0L,
                0L,
                List.of(
                        new OrderDirectory.NotificationLine("Lavash", new BigDecimal("2.000"), 50_000L),
                        new OrderDirectory.NotificationLine("Cola", BigDecimal.ONE, 8_000L))));
        UUID withoutPrices = createTemplate(
                OrderNotificationTrigger.ORDER_CONFIRMED, NotificationClass.TRANSACTIONAL_REQUIRED, null, null, null);
        activateVersion(withoutPrices, "Вы заказали: {{productList}}", false);

        trigger.onOrderingEvent(confirmed());
        worker.drain();
        assertThat(transport.bodies()).containsExactly("Вы заказали: 2x Lavash, 1x Cola");

        // A new version with the switch on is approved like any other, and the next message follows it.
        activateVersion(withoutPrices, "Вы заказали: {{productList}}", true);
        transport.clear();
        jdbc.sql("DELETE FROM notifications.notifications").update();
        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Вы заказали: 2x Lavash (50000 UZS), 1x Cola (8000 UZS)");
    }

    // ------------------------------------------------------------------- language

    @Test
    @DisplayName("a customer whose language the brand does not serve is written to in the brand's, not failed")
    void aCustomerInAnUnservedLanguageIsWrittenToInTheBrandsDefault() {
        // The brand serves ru and uz-Latn only; the customer reads English. Before ADR 0184 the row froze
        // the customer's language, and the dispatcher then looked the version up in a language it has no
        // row for.
        jdbc.sql("""
                INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                VALUES (:tenantId, :brandId, 'ru', true), (:tenantId, :brandId, 'uz-Latn', false)
                """).param("tenantId", TENANT).param("brandId", BRAND).update();
        setPreferredLocale("en");
        UUID template = createTemplate(
                OrderNotificationTrigger.ORDER_CONFIRMED, NotificationClass.TRANSACTIONAL_REQUIRED, null, null, null);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        wordings.put(MessageLocale.RU, new Wording(null, "Заказ {{orderNumber}}"));
        wordings.put(MessageLocale.UZ_LATN, new Wording(null, "Buyurtma {{orderNumber}}"));
        int version = templates.addVersion(TENANT, BRAND, template, wordings, Map.of("orderNumber", "string"));
        templates.activate(TENANT, BRAND, template, version, "approver");

        trigger.onOrderingEvent(confirmed());
        worker.drain();

        assertThat(transport.bodies()).containsExactly("Заказ A-17");
        assertThat(queries.detail(TENANT, onlyNotification())
                        .orElseThrow()
                        .notification()
                        .locale())
                .as("the language frozen onto the row is the language the wording is in")
                .isEqualTo("ru");
    }

    // ------------------------------------------------------------------- fixtures

    private OrderConfirmed confirmed() {
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

    private OrderPreparing preparing() {
        return new OrderPreparing(
                UUID.randomUUID(), new TenantId(TENANT), orderId, NOW, BRAND, LOCATION, "DELIVERY", NOW, 4);
    }

    private OrderReady ready(String mode) {
        return new OrderReady(UUID.randomUUID(), new TenantId(TENANT), orderId, NOW, BRAND, LOCATION, mode, NOW, 5);
    }

    private OrderFulfilling fulfilling() {
        return new OrderFulfilling(
                UUID.randomUUID(), new TenantId(TENANT), orderId, NOW, BRAND, LOCATION, "DELIVERY", NOW, 6);
    }

    private void grantProgressConsent() {
        consent.record(
                TENANT,
                accountId,
                BRAND,
                PROGRESS,
                "SMS",
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                "evidence-1",
                NOW.minusSeconds(60));
    }

    private UUID createTemplate(
            String key,
            NotificationClass notificationClass,
            @Nullable String purpose,
            @Nullable FulfillmentMode mode,
            @Nullable SalesChannelSystemType source) {
        return templates.createTemplate(
                TENANT, BRAND, key, notificationClass, NotificationChannel.SMS, purpose, mode, source);
    }

    private void activate(String key, NotificationClass notificationClass, @Nullable String purpose, String body) {
        activateVersion(createTemplate(key, notificationClass, purpose, null, null), body, false);
    }

    /** Saves the body in every language the brand serves, as a new version, and activates it. */
    private void activateVersion(UUID templateId, String body, boolean showItemPrices) {
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        templates.requiredLocales(TENANT, BRAND).forEach(locale -> wordings.put(locale, new Wording(null, body)));
        Map<String, String> schema = new LinkedHashMap<>();
        uz.horecaos.platform.notifications.domain.TemplateRenderer.variablesUsedIn(body)
                .forEach(name -> schema.put(name, "string"));
        int version = templates.addVersion(TENANT, BRAND, templateId, wordings, schema, showItemPrices);
        templates.activate(TENANT, BRAND, templateId, version, "approver");
    }

    private void insertSmsEnvironment(String code, boolean moderates) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist,
                    moderates_wordings)
                VALUES (:code, 'NOTIFICATION', 'SMSGW_QUIET', 'http://127.0.0.1:1', false, '127.0.0.1', :moderates)
                """).param("code", code).param("moderates", moderates).update();
    }

    private void setPreferredLocale(String tag) {
        jdbc.sql("UPDATE customer.customer_accounts SET preferred_locale = :tag WHERE id = :id")
                .param("tag", tag)
                .param("id", accountId)
                .update();
    }

    private void setDisplayName(@Nullable String name) {
        jdbc.sql("UPDATE customer.customer_accounts SET display_name = :name WHERE id = :id")
                .param("name", name, java.sql.Types.VARCHAR)
                .param("id", accountId)
                .update();
    }

    private long notificationCount() {
        return jdbc.sql("SELECT count(*) FROM notifications.notifications")
                .query(Long.class)
                .single();
    }

    private UUID onlyNotification() {
        return jdbc.sql("SELECT id FROM notifications.notifications")
                .query(UUID.class)
                .single();
    }

    private @Nullable String reasonOf(UUID id) {
        return notifications.find(TENANT, id).orElseThrow().suppressionReason();
    }

    private long expiryMinutes(String templateKey) {
        return jdbc.sql("""
                        SELECT (extract(epoch FROM expires_at - created_at) / 60)::bigint
                        FROM notifications.notifications WHERE template_key = :key
                        """).param("key", templateKey).query(Long.class).single();
    }

    /** Every column of every row, as text, so a leak cannot hide in a column nobody named. */
    private String everythingIn(String table) {
        return String.join(
                "|",
                jdbc.sql("SELECT t::text FROM %s t".formatted(table))
                        .query(String.class)
                        .list());
    }

    private void seedTenantAndCustomer() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'milestones', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'milestones-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                VALUES (:id, :tenantId, :brandId, 'PILOT', 'milestones-location', 'Pilot location',
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

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, "
                        + "notifications.delivery_attempts, notifications.notifications, "
                        + "notifications.recipient_endpoints, notifications.template_versions, "
                        + "notifications.templates, notifications.notification_preferences CASCADE")
                .update();
        // The migrated schema seeds a moderating SMS gateway (ADR 0091), under which every new SMS version
        // starts awaiting its approval; this suite is about what a customer is sent, not about moderation.
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    /** Keeps what it was asked to send: the port is the one place the sentence and the recipient meet. */
    private static final class CapturingTransport implements NotificationTransport {

        private final List<NotificationDispatch> sent = new ArrayList<>();

        List<String> bodies() {
            return sent.stream().map(NotificationDispatch::body).toList();
        }

        void clear() {
            sent.clear();
        }

        @Override
        public DispatchOutcome dispatch(NotificationDispatch dispatch) {
            sent.add(dispatch);
            return DispatchOutcome.accepted("ext-" + sent.size(), "accepted");
        }

        @Override
        public DispatchOutcome reconcile(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String channel, String providerIdempotencyKey) {
            throw new UnsupportedOperationException("Nothing in this suite is uncertain");
        }

        @Override
        public boolean supports(String channel) {
            return "SMS".equals(channel);
        }
    }

    private static final class StubCourierNames implements CourierFirstNameDirectory {

        Optional<String> answer = Optional.empty();

        @Override
        public Optional<String> firstNameCarrying(UUID tenantId, UUID orderId) {
            return answer;
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

    /** ADR 0019's read port without its tables; see {@code NotificationDeliveryTests.StubOrderDirectory}. */
    private static final class StubOrders implements OrderDirectory {

        private final Map<UUID, OrderSummary> summaries = new LinkedHashMap<>();
        private final Map<UUID, FulfillmentMode> modes = new LinkedHashMap<>();
        Optional<NotificationFacts> facts = Optional.empty();

        void publish(OrderSummary summary, FulfillmentMode mode) {
            summaries.put(summary.orderId(), summary);
            modes.put(summary.orderId(), mode);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.ofNullable(summaries.get(orderId))
                    .filter(summary -> summary.tenantId().equals(tenantId));
        }

        @Override
        public Optional<NotificationContext> notificationContext(UUID tenantId, UUID orderId) {
            return summary(tenantId, orderId)
                    .map(summary -> new NotificationContext(
                            java.util.Objects.requireNonNull(modes.get(orderId)), SalesChannelSystemType.WEB));
        }

        @Override
        public Optional<NotificationFacts> notificationFacts(UUID tenantId, UUID orderId) {
            return summary(tenantId, orderId).flatMap(summary -> facts);
        }
    }
}
