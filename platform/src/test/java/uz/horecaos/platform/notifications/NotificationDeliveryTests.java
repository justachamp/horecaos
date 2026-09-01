package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import uz.horecaos.platform.integration.camel.notification.SmsGatewayAdapter;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory.ScopedBinding;
import uz.horecaos.platform.notifications.application.CampaignBlockRateMonitor;
import uz.horecaos.platform.notifications.application.CustomerTelegramChannelRouter;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationPreferenceService;
import uz.horecaos.platform.notifications.application.NotificationQueryService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.IncompleteTranslationException;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.application.OperationsAlertFanoutService;
import uz.horecaos.platform.notifications.application.OrderNotificationTrigger;
import uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The ADR 0020 slice, end to end (ADR 0020, ADR 0015, ADR 0029, ADR 0007).
 *
 * <p>Runs the real path — order fact, intent, consent gate, template resolution,
 * render, attempt, Camel route, gateway — against a fake SMS gateway and a real
 * PostgreSQL. The only stand-in is {@link OrderDirectory}, and deliberately so:
 * that port is the boundary between the two modules, and building a genuine
 * {@code ordering.orders} row here would drag in carts, quotes and publications to
 * assert nothing about notifications.
 *
 * <p>The properties under test are the ones that decide whether a customer gets two
 * confirmations for one order, whether a refused message can be explained, and
 * whether a phone number ends up somewhere ADR 0029 says it must not.
 */
class NotificationDeliveryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String PHONE = "+998901234567";
    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");
    private static final String MARKETING_PURPOSE = "ORDER_UPDATES";

    private static TestDatabase.Handle db;

    private FakeSmsGateway gateway;
    private CamelContext camel;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private JdbcNotificationStore notifications;
    private JdbcTemplateStore templateStore;
    private NotificationTemplateService templates;
    private NotificationWorker worker;
    private NotificationQueryService queries;
    private NotificationPreferenceService preferences;
    private OrderNotificationTrigger trigger;
    private ConsentService consent;
    private CustomerProfileService profiles;
    private StubOrderDirectory orders;

    private UUID accountId;
    private UUID orderId;

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
    void setUp() throws Exception {
        gateway = FakeSmsGateway.start();

        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        objectMapper = JsonMapper.builder().build();

        // A real envelope-encryption stack over a throwaway key, so the contact
        // value genuinely round-trips through ADR 0029 rather than being stubbed
        // into agreeing.
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of(
                        "horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key",
                        "horecaos.secrets.provider_notification.platform.sms", "a-test-gateway-token")::get,
                clock);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        profiles = new CustomerProfileService(customerStore, protection, objectMapper, clock);
        consent = new ConsentService(customerStore, clock);
        RecipientContactDirectory contacts = new RecipientContactService(customerStore, protection);

        notifications = new JdbcNotificationStore(jdbc);
        templateStore = new JdbcTemplateStore(jdbc);
        templates = new NotificationTemplateService(templateStore, objectMapper, clock);

        seedTenantAndCustomer();
        seedProviderInstallation();

        NotificationGateway providerGateway = new NotificationGateway(
                java.util.List.of(
                        new SmsGatewayAdapter(new ProviderHttpClient(objectMapper, new ProviderExceptionClassifier()))),
                new JdbcProviderInstallationLookup(jdbc, clock),
                secrets);

        camel = new DefaultCamelContext();
        camel.addRoutes(
                new NotificationRouteBuilder(new NotificationProcessor(providerGateway, new SimpleMeterRegistry())));
        camel.start();

        CamelNotificationTransport transport =
                new CamelNotificationTransport(camel.createProducerTemplate(), providerGateway);

        orders = new StubOrderDirectory();
        orderId = UUID.randomUUID();
        orders.publish(new OrderDirectory.OrderSummary(
                orderId, TENANT, BRAND, LOCATION, "A-17", accountId, null, "CONFIRMED", "UZS", 12_500_000L, 3));

        // No Telegram binding exists in this SMS-focused suite, so the ADR 0058
        // fan-out this trigger also performs has nothing to fan out to; a
        // directory that always answers empty makes that a true no-op rather
        // than a null dependency.
        OperationsAlertFanoutService operationsAlerts = new OperationsAlertFanoutService(
                new NoTelegramBindings(),
                notifications,
                new TelegramOperationsEntitlementGate(new AlwaysEntitledService()),
                objectMapper,
                clock);
        // No campaign in this suite, so the ADR 0059 stage 4 block-rate guard
        // never has anything to ask about; see AlwaysSendingCampaignFeedback's
        // own doc comment.
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
                transport,
                campaignBlockRate,
                objectMapper,
                clock,
                8,
                Duration.ofSeconds(30));

        worker = new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));
        queries = new NotificationQueryService(notifications, clock);
        preferences = new NotificationPreferenceService(notifications, clock);
        CustomerTelegramChannelRouter channelRouter =
                new CustomerTelegramChannelRouter(notifications, new AlwaysEntitledService());
        trigger = new OrderNotificationTrigger(
                notifications,
                operationsAlerts,
                orders,
                channelRouter,
                objectMapper,
                clock,
                "SMS",
                Duration.ofHours(6));

        activateConfirmationTemplate();
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

    // ------------------------------------------------------------- idempotency

    @Test
    @DisplayName("the same order fact delivered twice produces one message")
    void aRedeliveredEventSendsOneConfirmation() {
        // At-least-once delivery means the second arrival is expected rather than
        // exceptional, and it carries its own event id — so keying deduplication
        // on the event would send the customer a second confirmation.
        trigger.onOrderingEvent(orderConfirmed());
        trigger.onOrderingEvent(orderConfirmed());

        assertThat(notificationCount()).isEqualTo(1);

        worker.drain();

        assertThat(gateway.messagesSent()).isEqualTo(1);
        assertThat(gateway.sentTo()).containsExactly(PHONE);
        assertThat(statusOf(onlyNotification())).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a second sweep does not send the message again")
    void aSettledMessageIsNotResent() {
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();
        worker.drain();

        assertThat(gateway.messagesSent()).isEqualTo(1);
    }

    // ----------------------------------------------------------- consent gate

    @Test
    @DisplayName("a confirmation is not gated on marketing consent")
    void aRequiredTransactionalMessageDoesNotNeedConsent() {
        // No consent decision exists at all. A receipt for money the customer
        // spent is not marketing and must not be withheld from someone who never
        // opted into promotions.
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        assertThat(statusOf(onlyNotification())).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("an optional message with no consent on record is refused, with the reason")
    void anOptionalMessageWithoutConsentIsSuppressedRatherThanDropped() {
        UUID notificationId = createOptionalUpdate();

        worker.drain();

        var detail = queries.detail(TENANT, notificationId).orElseThrow();
        assertThat(detail.notification().status()).isEqualTo("SUPPRESSED");
        assertThat(detail.notification().suppressionReason())
                .as("a tenant asking why the customer heard nothing needs an answer, "
                        + "and a silently filtered message has none")
                .isEqualTo("CONSENT_WITHHELD");
        assertThat(gateway.messagesSent()).isZero();
    }

    @Test
    @DisplayName("granted consent lets the same optional message through")
    void anOptionalMessageWithConsentIsSent() {
        consent.record(
                TENANT,
                accountId,
                BRAND,
                MARKETING_PURPOSE,
                "SMS",
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                "evidence-1",
                NOW.minusSeconds(60));

        UUID notificationId = createOptionalUpdate();
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a withdrawal after a grant refuses the next message")
    void aWithdrawalSupersedesAGrant() {
        consent.record(
                TENANT,
                accountId,
                BRAND,
                MARKETING_PURPOSE,
                "SMS",
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                null,
                NOW.minusSeconds(600));
        consent.record(
                TENANT,
                accountId,
                BRAND,
                MARKETING_PURPOSE,
                "SMS",
                ConsentService.Decision.WITHDRAWN,
                "v1",
                ConsentService.Source.STOREFRONT,
                null,
                NOW.minusSeconds(60));

        UUID notificationId = createOptionalUpdate();
        worker.drain();

        assertThat(queries.detail(TENANT, notificationId)
                        .orElseThrow()
                        .notification()
                        .suppressionReason())
                .isEqualTo("CONSENT_WITHHELD");
    }

    @Test
    @DisplayName("a customer preference switches off an optional message but not a required one")
    void preferenceAppliesOnlyToWhatTheCustomerMaySwitchOff() {
        consent.record(
                TENANT,
                accountId,
                BRAND,
                MARKETING_PURPOSE,
                "SMS",
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                null,
                NOW);
        preferences.set(
                TENANT, accountId, null, NotificationClass.TRANSACTIONAL_OPTIONAL, NotificationChannel.SMS, false);

        UUID optional = createOptionalUpdate();
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        assertThat(queries.detail(TENANT, optional).orElseThrow().notification().suppressionReason())
                .isEqualTo("PREFERENCE_DISABLED");
        assertThat(statusOf(confirmationFor(orderId))).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a required class cannot be switched off at all")
    void aRequiredClassIsRefusedRatherThanIgnored() {
        // Accepting the toggle and then sending anyway is worse than saying no:
        // the customer believes they opted out and the messages keep arriving.
        assertThat(catchThrowable(() -> preferences.set(
                        TENANT,
                        accountId,
                        null,
                        NotificationClass.TRANSACTIONAL_REQUIRED,
                        NotificationChannel.SMS,
                        false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a manual retry re-runs the gate rather than jumping past it")
    void anOperatorRetryCannotOverrideConsent() {
        UUID notificationId = createOptionalUpdate();
        worker.drain();
        assertThat(statusOf(notificationId)).isEqualTo("SUPPRESSED");

        assertThat(queries.retry(TENANT, notificationId, "customer called support"))
                .isTrue();
        worker.drain();

        assertThat(statusOf(notificationId))
                .as("consent still refuses it, so pressing retry must not send it")
                .isEqualTo("SUPPRESSED");
        assertThat(gateway.messagesSent()).isZero();
    }

    @Test
    @DisplayName("a delivered message is never retried")
    void aDeliveredMessageIsNotRetryable() {
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        assertThat(queries.retry(TENANT, confirmationFor(orderId), "support asked"))
                .as("resending is how one confirmation becomes two")
                .isFalse();
    }

    // ------------------------------------------------------- missing recipient

    @Test
    @DisplayName("a guest order is recorded as having no recipient, not silently skipped")
    void aGuestOrderIsSuppressedWithItsReason() {
        UUID guestOrder = UUID.randomUUID();
        orders.publish(new OrderDirectory.OrderSummary(
                guestOrder, TENANT, BRAND, LOCATION, "A-18", null, "hash", "CONFIRMED", "UZS", 1_000L, 1));

        trigger.onOrderingEvent(orderConfirmed(guestOrder));
        worker.drain();

        assertThat(suppressionReasonOf(confirmationFor(guestOrder))).isEqualTo("NO_RECIPIENT_ACCOUNT");
    }

    @Test
    @DisplayName("an account with no phone number is recorded as unreachable")
    void anAccountWithoutAContactPointIsSuppressed() {
        UUID silentAccount = insertAccount();
        UUID theirOrder = UUID.randomUUID();
        orders.publish(new OrderDirectory.OrderSummary(
                theirOrder, TENANT, BRAND, LOCATION, "A-19", silentAccount, null, "CONFIRMED", "UZS", 1_000L, 1));

        trigger.onOrderingEvent(orderConfirmed(theirOrder));
        worker.drain();

        assertThat(suppressionReasonOf(confirmationFor(theirOrder))).isEqualTo("NO_RECIPIENT_ENDPOINT");
    }

    // ------------------------------------------------------------- uncertainty

    @Test
    @DisplayName("a gateway that accepts and then loses the reply is reconciled, not repeated")
    void anUncertainOutcomeReconcilesBeforeAnythingElse() {
        gateway.behaveAs(FakeSmsGateway.Scenario.ACCEPTED_THEN_TIMEOUT);

        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        UUID notificationId = confirmationFor(orderId);
        assertThat(statusOf(notificationId))
                .as("the message may already have gone out; retrying blindly is how it goes twice")
                .isEqualTo("UNCERTAIN");
        assertThat(gateway.messagesSent()).isEqualTo(1);

        // The gateway is healthy again. The next sweep asks what happened rather
        // than sending a second message.
        gateway.behaveAs(FakeSmsGateway.Scenario.SUCCESS);
        makeDue(notificationId);
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        assertThat(gateway.messagesSent())
                .as("the reconcile found the message the gateway already had")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the strongest status the gateway actually gave is what is recorded")
    void deliveryNeverClaimsMoreThanTheGatewaySaid() {
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        var detail = queries.detail(TENANT, confirmationFor(orderId)).orElseThrow();
        var attempt = detail.attempts().getFirst();

        assertThat(attempt.attempt().status())
                .as("the gateway said accepted, so the attempt must not claim delivery")
                .isEqualTo("ACCEPTED");
        assertThat(attempt.attempt().acknowledgedAt()).isNull();
        assertThat(attempt.attempt().providerType())
                .as("which gateway handled it is half the answer to a missing message")
                .isEqualTo("GENERIC_SMS");
        assertThat(attempt.attempt().providerBindingId()).isNotNull();
        assertThat(attempt.statusEvents()).singleElement().satisfies(event -> {
            assertThat(event.normalizedStatus()).isEqualTo("ACCEPTED");
            assertThat(event.providerStatus()).isEqualTo("ACCEPTED");
        });
    }

    @Test
    @DisplayName("a permanent rejection is not retried as if it were an outage")
    void aPermanentRejectionStops() {
        gateway.behaveAs(FakeSmsGateway.Scenario.PERMANENT_REJECTION);

        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        assertThat(statusOf(confirmationFor(orderId))).isEqualTo("FAILED_TERMINAL");
    }

    @Test
    @DisplayName("a transient failure retries under the same provider key")
    void aRetryReusesTheProviderIdempotencyKey() {
        gateway.behaveAs(FakeSmsGateway.Scenario.SERVER_ERROR);

        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        UUID notificationId = confirmationFor(orderId);
        assertThat(statusOf(notificationId)).isEqualTo("RETRY_PENDING");
        String firstKey = providerKeyOf(notificationId);

        gateway.behaveAs(FakeSmsGateway.Scenario.SUCCESS);
        makeDue(notificationId);
        worker.drain();

        assertThat(providerKeyOf(notificationId))
                .as("a fresh key would defeat the gateway-side deduplication the retry depends on")
                .isEqualTo(firstKey);
        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        assertThat(gateway.messagesSent()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- templates

    @Test
    @DisplayName("a version missing a translation cannot be saved")
    void anIncompleteVersionIsRefusedWhileItIsBeingAuthored() {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, "ORDER_READY", NotificationClass.TRANSACTIONAL_REQUIRED, NotificationChannel.SMS, null);

        Map<MessageLocale, Wording> twoOfThree = new LinkedHashMap<>();
        twoOfThree.put(MessageLocale.RU, new Wording(null, "Готово"));
        twoOfThree.put(MessageLocale.EN, new Wording(null, "Ready"));

        assertThat(catchThrowable(() -> templates.addVersion(TENANT, templateId, twoOfThree, Map.of())))
                .as("a missing translation must fail at authoring time, not at 22:00 at a counter")
                .isInstanceOf(IncompleteTranslationException.class)
                .hasMessageContaining("UZ_LATN");
    }

    @Test
    @DisplayName("a template naming an undeclared variable cannot be saved")
    void anUndeclaredVariableIsRefused() {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, "ORDER_READY", NotificationClass.TRANSACTIONAL_REQUIRED, NotificationChannel.SMS, null);

        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, "Order {{orderNumbr}}")));

        assertThat(catchThrowable(
                        () -> templates.addVersion(TENANT, templateId, wordings, Map.of("orderNumber", "string"))))
                .hasMessageContaining("orderNumbr");
    }

    @Test
    @DisplayName("the customer's language decides which translation is sent")
    void localeIsResolvedFromTheCustomersPreference() {
        jdbc.sql("UPDATE customer.customer_accounts SET preferred_locale = 'uz-Latn' WHERE id = :id")
                .param("id", accountId)
                .update();

        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        var detail = queries.detail(TENANT, confirmationFor(orderId)).orElseThrow();
        assertThat(detail.notification().locale()).isEqualTo("uz-Latn");
    }

    @Test
    @DisplayName("a brand's own wording wins over the tenant's default")
    void brandWordingOverridesTheTenantDefault() {
        // The tenant-wide template is authored second, so this also proves the
        // precedence is the ORDER BY rather than insertion order.
        UUID tenantWide = templates.createTemplate(
                TENANT,
                null,
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null);
        activateAllLocales(tenantWide, "Tenant default {{orderNumber}}");

        var resolved = templates.resolve(
                TENANT, BRAND, OrderNotificationTrigger.ORDER_CONFIRMED, NotificationChannel.SMS, MessageLocale.RU);

        assertThat(resolved.isFound()).isTrue();
        assertThat(Objects.requireNonNull(resolved.template()).brandId()).isEqualTo(BRAND);
    }

    // ------------------------------------------------------------ personal data

    @Test
    @DisplayName("no notification row holds a contact value or a rendered body")
    void theNotificationsSchemaNeverHoldsPersonalData() {
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        // The table most likely to end up in a support export. Everything it holds
        // about the recipient is a reference and a keyed hash.
        assertThat(everythingIn("notifications.notifications")).doesNotContain(PHONE);
        assertThat(everythingIn("notifications.recipient_endpoints")).doesNotContain(PHONE);
        assertThat(everythingIn("notifications.delivery_attempts")).doesNotContain(PHONE);

        var detail = queries.detail(TENANT, confirmationFor(orderId)).orElseThrow();
        assertThat(detail.notification().renderedContentHash())
                .as("evidence of what was sent is a hash, not the sentence itself")
                .isNotNull()
                .doesNotContain("A-17");
        assertThat(detail.notification().recipientEndpointId()).isNotNull();
    }

    @Test
    @DisplayName("the endpoint row carries the same lookup hash ADR 0015 stores")
    void theEndpointIsFoundableWithoutStoringTheNumber() {
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();

        UUID endpointId = Objects.requireNonNull(queries.detail(TENANT, confirmationFor(orderId))
                .orElseThrow()
                .notification()
                .recipientEndpointId());
        var endpoint = notifications.endpoint(TENANT, endpointId).orElseThrow();

        assertThat(endpoint.normalizedHash()).isNotBlank();
        assertThat(endpoint.contactPointId()).isNotNull();
        assertThat(endpoint.normalizedHash())
                .isEqualTo(jdbc.sql("SELECT normalized_hash FROM customer.contact_points WHERE id = :id")
                        .param("id", endpoint.contactPointId())
                        .query(String.class)
                        .single());
    }

    // --------------------------------------------------------------- isolation

    @Test
    @DisplayName("another tenant cannot read this tenant's delivery record")
    void crossTenantReadsFindNothing() {
        trigger.onOrderingEvent(orderConfirmed());
        worker.drain();
        UUID notificationId = confirmationFor(orderId);

        assertThat(queries.detail(OTHER_TENANT, notificationId)).isEmpty();
        assertThat(queries.forOrder(OTHER_TENANT, orderId)).isEmpty();
        assertThat(notifications.endpoint(
                        OTHER_TENANT,
                        Objects.requireNonNull(queries.detail(TENANT, notificationId)
                                .orElseThrow()
                                .notification()
                                .recipientEndpointId())))
                .isEmpty();
    }

    @Test
    @DisplayName("another tenant's template is not resolvable here")
    void templatesDoNotCrossTenants() {
        assertThat(templates
                        .resolve(
                                OTHER_TENANT,
                                BRAND,
                                OrderNotificationTrigger.ORDER_CONFIRMED,
                                NotificationChannel.SMS,
                                MessageLocale.RU)
                        .isFound())
                .isFalse();
    }

    // ----------------------------------------------------------------- helpers

    private OrderConfirmed orderConfirmed() {
        return orderConfirmed(orderId);
    }

    /** A fresh event id every time, which is exactly what a replay looks like. */
    private OrderConfirmed orderConfirmed(UUID subject) {
        return new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(TENANT),
                subject,
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

    /**
     * An optional-class message, created directly.
     *
     * <p>Nothing in the first slice publishes one — order confirmations are
     * required transactional — so the consent gate has to be exercised through the
     * store rather than through a trigger that does not exist yet.
     */
    private UUID createOptionalUpdate() {
        UUID templateId = templates.createTemplate(
                TENANT,
                BRAND,
                "ORDER_UPDATE",
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                NotificationChannel.SMS,
                MARKETING_PURPOSE);
        activateAllLocales(templateId, "Update for {{orderNumber}}");

        UUID id = UUID.randomUUID();
        notifications.createIntent(new NewNotification(
                id,
                TENANT,
                BRAND,
                null,
                NotificationClass.TRANSACTIONAL_OPTIONAL.name(),
                "SMS",
                "ORDER_UPDATE",
                "Order",
                orderId,
                null,
                UUID.randomUUID(),
                "ORDER_UPDATE:Order:%s:SMS".formatted(orderId),
                "{}",
                NOW,
                NOW.plus(Duration.ofHours(6)),
                NOW));
        return id;
    }

    private void activateConfirmationTemplate() {
        UUID templateId = templates.createTemplate(
                TENANT,
                BRAND,
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null);
        activateAllLocales(templateId, "Buyurtma {{orderNumber}}: {{amount}} {{currency}}");
    }

    private void activateAllLocales(UUID templateId, String body) {
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, body)));

        int versionNumber = templates.addVersion(
                TENANT,
                templateId,
                wordings,
                Map.of("orderNumber", "string", "amount", "string", "currency", "string", "reasonCode", "string"));
        templates.activate(TENANT, templateId, versionNumber, "copy-approver");
    }

    private void seedTenantAndCustomer() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, 'pilot', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'pilot-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
        // The events and notification rows below carry a real location id, and
        // fk_notification_location requires it to resolve to an actual row.
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

        accountId = insertAccount();
        profiles.addContactPoint(TENANT, accountId, ContactType.PHONE, PHONE, true);
    }

    private UUID insertAccount() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status)
                VALUES (:id, :tenantId, 'ACTIVE')
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    /**
     * A real ADR 0026 installation and binding.
     *
     * <p>Seeded rather than stubbed, because "which external account sends this
     * tenant's messages" is exactly the wiring this test exists to prove, and the
     * gateway resolving nothing would look identical to a message nobody sent.
     */
    private void seedProviderInstallation() {
        UUID installationId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production,
                    egress_allowlist)
                VALUES ('sms-fake', 'NOTIFICATION', 'GENERIC_SMS', :baseUrl, false, '127.0.0.1')
                """).param("baseUrl", gateway.baseUrl()).update();

        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'GENERIC_SMS', 'sms-fake',
                        'Pilot SMS', 'ACTIVE', 'horecaos:local:provider_notification:platform:sms')
                """).param("id", installationId).param("tenantId", TENANT).update();

        // effective_from is set explicitly rather than left to now(). The clock
        // under test is fixed, and a binding that becomes effective at the
        // database's wall-clock time is not yet effective at the test's — which
        // presents as NO_PROVIDER_BINDING and looks like a wiring bug.
        jdbc.sql("""
                INSERT INTO integration.bindings (
                    id, tenant_id, installation_id, brand_id, status, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE', :from)
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    /** Brings a backed-off row forward, since the clock is deliberately fixed. */
    private void makeDue(UUID notificationId) {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now WHERE id = :id")
                .param("id", notificationId)
                .param("now", java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
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

    private UUID confirmationFor(UUID subject) {
        return jdbc.sql("""
                SELECT id FROM notifications.notifications
                WHERE subject_id = :subject AND template_key = 'ORDER_CONFIRMED'
                """).param("subject", subject).query(UUID.class).single();
    }

    private String statusOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().status();
    }

    private @Nullable String suppressionReasonOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().suppressionReason();
    }

    private String providerKeyOf(UUID notificationId) {
        return notifications.attempts(TENANT, notificationId).getFirst().providerIdempotencyKey();
    }

    /** Every column of every row, as text, so a leak cannot hide in a column nobody named. */
    private String everythingIn(String table) {
        return String.join(
                "|",
                jdbc.sql("SELECT t::text FROM %s t".formatted(table))
                        .query(String.class)
                        .list());
    }

    /** No Telegram binding exists in this SMS-focused suite: every lookup answers empty. */
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

    /**
     * ADR 0019's read port, without ADR 0019's tables.
     *
     * <p>The port is the boundary between the modules, so standing it in here tests
     * the same contract the real implementation satisfies. Building a genuine order
     * would drag in carts, quotes, and publications to assert nothing about
     * notifications.
     */
    private static final class StubOrderDirectory implements OrderDirectory {

        private final Map<UUID, OrderSummary> summaries = new LinkedHashMap<>();

        void publish(OrderSummary summary) {
            summaries.put(summary.orderId(), summary);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.ofNullable(summaries.get(orderId))
                    // The tenant predicate is part of the contract, not an
                    // implementation detail of the SQL one.
                    .filter(summary -> summary.tenantId().equals(tenantId));
        }
    }
}
