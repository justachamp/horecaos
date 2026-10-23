package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
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
import uz.horecaos.platform.integration.camel.notification.push.FakeBrowser;
import uz.horecaos.platform.integration.camel.notification.push.FakePushService;
import uz.horecaos.platform.integration.camel.notification.push.WebPushChannelAdapter;
import uz.horecaos.platform.integration.camel.notification.push.WebPushEncryptionAccess;
import uz.horecaos.platform.integration.provider.JdbcProviderActivityRecorder;
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.application.DeliverabilityFeedbackService;
import uz.horecaos.platform.marketing.application.MarketingSuppressionService;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.media.api.TenantImageCheck;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.api.PushEndpointPolicy;
import uz.horecaos.platform.notifications.application.CampaignBlockRateMonitor;
import uz.horecaos.platform.notifications.application.CampaignMessageRouter;
import uz.horecaos.platform.notifications.application.CampaignPushDeliveryService;
import uz.horecaos.platform.notifications.application.CampaignPushDispatches;
import uz.horecaos.platform.notifications.application.DeliveryReceiptService;
import uz.horecaos.platform.notifications.application.EmailEnvelopes;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.application.OperationsAlertFanoutService;
import uz.horecaos.platform.notifications.application.PushClickService;
import uz.horecaos.platform.notifications.application.PushSubscriptionService;
import uz.horecaos.platform.notifications.application.PushSubscriptionService.Subscribe;
import uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcPushClickTokenStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcPushSubscriptionStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.JvmLogCapture;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;

/**
 * ADR 0182, D14, over the real dispatch path: a campaign push is an ordinary ADR 0020 intent that
 * leaves for the customer's own browser subscription through the platform's Web Push, is counted read
 * only when the customer taps it, ends a subscription the push service says is gone, and never
 * reaches a customer who turned push off or whose browser moved to someone else.
 *
 * <p>Real PostgreSQL, real envelope encryption of the subscription, the real worker, eligibility,
 * dispatch, gateway, Camel route and adapter, and a fake push service on a real socket at the end. The
 * fake browser holds the subscription's keys, so the payload that a device would show is decrypted here
 * and not assumed.
 */
class CampaignPushDeliveryTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");
    private static final String ORIGIN = "https://api.horecaos.test";
    private static final String SUBJECT = "mailto:push@horecaos.uz";
    private static final String PURPOSE = PushSubscriptionService.CONSENT_PURPOSE;
    private static final String REFERENCE = "horecaos:test:provider_notification:platform:vapid";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static TestDatabase.Handle db;

    private FakePushService push;
    private FakeBrowser browser;
    private MutableClock clock;
    private CamelContext camel;
    private JdbcClient jdbc;
    private JdbcNotificationStore notifications;
    private JdbcEngagementStore engagement;
    private NotificationWorker worker;
    private CampaignMessageRouter router;
    private ConsentService consentService;
    private PushSubscriptionService subscriptions;
    private PushClickService clicks;
    private NotificationTemplateService templates;
    private final AtomicReference<TenantImageCheck.Verdict> imageVerdict =
            new AtomicReference<>(TenantImageCheck.Verdict.ACCEPTED);
    private UUID accountId = UUID.randomUUID();
    private KeyPair vapid;

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
        push = FakePushService.start();
        browser = new FakeBrowser();
        clock = new MutableClock(NOW);
        imageVerdict.set(TenantImageCheck.Verdict.ACCEPTED);
        build(true);
    }

    /** Wires the real stack around the fake push service; again, unconfigured, for the test that needs it. */
    private void build(boolean vapidConfigured) throws Exception {
        if (camel != null) {
            camel.stop();
        }
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        ObjectMapper objectMapper = JsonMapper.builder().build();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        vapid = WebPushEncryptionAccess.generateKeyPair();
        String vapidPublic = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(WebPushEncryptionAccess.uncompressed((ECPublicKey) vapid.getPublic()));
        String vapidPrivate = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(WebPushEncryptionAccess.fixed(((ECPrivateKey) vapid.getPrivate()).getS()));
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of(
                        "horecaos.secrets.data_encryption.platform.kek",
                        "a-test-key-encryption-key",
                        "horecaos.secrets.provider_notification.platform.vapid",
                        vapidPrivate)::get,
                clock);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        CustomerProfileService profiles = new CustomerProfileService(
                customerStore, protection, objectMapper, clock, new JdbcAuditRecorder(jdbc, objectMapper));
        consentService = new ConsentService(customerStore, clock);
        RecipientContactDirectory contacts = new RecipientContactService(customerStore, protection);

        notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        templates = new NotificationTemplateService(
                templateStore,
                objectMapper,
                clock,
                AuditTrail.discarding(),
                AuditTrail.actor("template-author"),
                BrandLocaleLookup.platformFallback(),
                (tenantId, assetId, ratioWidth, ratioHeight, maximumBytes) ->
                        java.util.Objects.requireNonNull(imageVerdict.get()));

        seedTenantAndCustomer(profiles);

        PushEndpointPolicy policy = new PushEndpointPolicy("127.0.0.1", false);
        JdbcProviderInstallationLookup installations =
                new JdbcProviderInstallationLookup(jdbc, clock, new JdbcProviderEnvironmentLookup(jdbc));
        NotificationGateway providerGateway = new NotificationGateway(
                List.of(new WebPushChannelAdapter(
                        secrets,
                        policy,
                        objectMapper,
                        clock,
                        vapidConfigured ? vapidPublic : "",
                        vapidConfigured ? REFERENCE : "",
                        vapidConfigured ? SUBJECT : "")),
                installations,
                secrets);

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
                objectMapper,
                clock,
                "ru");

        engagement = new JdbcEngagementStore(jdbc);
        DeliverabilityFeedbackService deliverability = new DeliverabilityFeedbackService(
                new MarketingSuppressionService(engagement, new JdbcAuditRecorder(jdbc, objectMapper), clock),
                engagement,
                clock);
        JdbcDeliveryReceiptStore receiptStore = new JdbcDeliveryReceiptStore(jdbc);
        DeliveryReceiptService receipts =
                new DeliveryReceiptService(receiptStore, notifications, deliverability, clock);

        subscriptions = new PushSubscriptionService(
                new JdbcPushSubscriptionStore(jdbc),
                notifications,
                protection,
                policy,
                consentService,
                consentService,
                objectMapper,
                clock);
        clicks = new PushClickService(new JdbcPushClickTokenStore(jdbc), receipts, clock, Duration.ofDays(7));
        CampaignPushDispatches pushes =
                new CampaignPushDispatches(subscriptions, clicks, notifications, clock, ORIGIN + "/");

        NotificationDispatchService dispatch = new NotificationDispatchService(
                notifications,
                templateStore,
                contacts,
                transport,
                blockRate,
                deliverability,
                meters,
                EmailEnvelopes.NONE,
                () -> false,
                pushes,
                objectMapper,
                clock,
                8,
                Duration.ofSeconds(30));

        worker = new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));
        router = new CampaignMessageRouter(
                List.of(new CampaignPushDeliveryService(
                        notifications, templates, objectMapper, clock, Duration.ofHours(12))),
                transport,
                notifications,
                receiptStore);
        activatePushTemplate("PROMO_PUSH", null, null);
    }

    @AfterEach
    void tearDown() {
        if (camel != null) {
            camel.stop();
        }
        push.close();
    }

    @Test
    @DisplayName(
            "a campaign push reaches the customer's browser, encrypted to it, and is only «handed to the operator»")
    void aCampaignPushReachesTheBrowser() throws Exception {
        subscribe(accountId, browser, "/wpush/a");

        UUID notificationId = enqueue("m1", accountId);
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        assertThat(push.receivedAt("/wpush/a")).hasSize(1);
        var request = push.receivedAt("/wpush/a").getFirst();
        assertThat(push.vapidVerifies(request, (ECPublicKey) vapid.getPublic(), SUBJECT))
                .isTrue();

        JsonNode payload = JSON.readTree(browser.read(request.body()));
        assertThat(payload.path("title").asString()).isEqualTo("Aksiya");
        assertThat(payload.path("body").asString()).isEqualTo("Chegirma 20%");
        assertThat(payload.path("path").asString())
                .as("no target, the front page")
                .isEqualTo("/");
        assertThat(payload.path("click").asString()).startsWith(ORIGIN + "/providers/push/clicks/");
        assertThat(payload.has("image")).isFalse();

        var attempt = notifications.attempts(TENANT, notificationId).getFirst();
        assertThat(attempt.providerType()).isEqualTo("WEB_PUSH");
        assertThat(attempt.providerBindingId())
                .as("the platform's own sender has no tenant binding")
                .isNull();
        assertThat(attempt.status())
                .as("a push service that took the message has not said it was shown")
                .isEqualTo("ACCEPTED");
        assertThat(stateOf(notificationId)).isEqualTo("HANDED_TO_OPERATOR");

        String everything = everythingIn("notifications.notifications")
                + everythingIn("notifications.delivery_attempts")
                + everythingIn("notifications.delivery_status_events")
                + everythingIn("notifications.recipient_endpoints")
                + everythingIn("notifications.push_subscriptions")
                + everythingIn("notifications.push_click_tokens");
        assertThat(everything)
                .as("ADR 0029: the subscription's URL and keys are one ciphertext and are on no other row")
                .doesNotContain("/wpush/a")
                .doesNotContain("127.0.0.1")
                .doesNotContain(browser.p256dh())
                .doesNotContain(browser.auth());
        assertThat(everythingIn("notifications.push_click_tokens"))
                .as("only the token's hash is kept")
                .doesNotContain(tokenOf(payload));
    }

    @Test
    @DisplayName("a tap on the notification is the read, counted once, and the channel says what it can report")
    void aTapIsTheRead() throws Exception {
        subscribe(accountId, browser, "/wpush/a");
        UUID notificationId = enqueue("m1", accountId);
        worker.drain();
        String token = tokenOf(JSON.readTree(
                browser.read(push.receivedAt("/wpush/a").getFirst().body())));
        UUID campaign = campaignOf(notificationId);

        assertThat(router.deliveryCounts(TENANT, campaign)).satisfies(counts -> {
            assertThat(counts.handedToOperator()).isEqualTo(1);
            assertThat(counts.read()).isZero();
        });

        assertThat(clicks.click(token)).isTrue();

        var counts = router.deliveryCounts(TENANT, campaign);
        assertThat(counts.read()).as("the tap is the read").isEqualTo(1);
        assertThat(counts.delivered())
                .as("a tap proves the message reached the browser, so it is delivered as well")
                .isEqualTo(1);
        assertThat(counts.handedToOperator()).isZero();
        assertThat(stateOf(notificationId)).isEqualTo("READ");
        assertThat(notifications.attempts(TENANT, notificationId).getFirst().status())
                .isEqualTo("DELIVERED");

        assertThat(clicks.click(token)).as("a second tap is the same fact").isTrue();
        assertThat(router.deliveryCounts(TENANT, campaign).read()).isEqualTo(1);
        assertThat(readEvents()).isEqualTo(1);

        assertThat(clicks.click("A".repeat(32)))
                .as("a token nothing minted, and one that is malformed, are one answer")
                .isFalse();
        clock.advance(Duration.ofDays(30));
        assertThat(clicks.click(token))
                .as("a token outlives the message it was minted for by a week and no more")
                .isFalse();

        assertThat(router.reporting(TENANT, BRAND, "PUSH"))
                .as("the push service reports no delivery, and a tap is a read")
                .isEqualTo(new CampaignMessagePort.ChannelReporting(false, true));
    }

    @Test
    @DisplayName("a push service that says the subscription is gone ends it, and the next campaign skips the customer")
    void aGoneSubscriptionEndsAndTheNextCampaignSkipsIt() {
        subscribe(accountId, browser, "/wpush/a");
        push.respond("/wpush/a", 410);

        UUID first = enqueue("m1", accountId);
        worker.drain();

        assertThat(statusOf(first)).isEqualTo("FAILED_TERMINAL");
        assertThat(notifications.attempts(TENANT, first).getFirst().failureCode())
                .isEqualTo("PUSH_SUBSCRIPTION_GONE");
        assertThat(subscriptionStatus()).isEqualTo("EXPIRED");
        assertThat(subscriptionSecret())
                .as("an ended subscription keeps no secret")
                .isNull();
        assertThat(endpointStatus()).isEqualTo("RETIRED");
        assertThat(engagement.hasActiveSuppression(TENANT, BRAND, accountId, "PUSH", NOW))
                .as("a dead device is not a withdrawn consent: nothing is suppressed")
                .isFalse();
        assertThat(stateOf(first)).isEqualTo("REJECTED");

        UUID second = enqueue("m2", accountId);
        worker.drain();
        assertThat(statusOf(second)).isEqualTo("SUPPRESSED");
        assertThat(suppressionReasonOf(second)).isEqualTo("NO_RECIPIENT_ENDPOINT");
        assertThat(push.received()).as("the dead address is not tried again").hasSize(1);

        FakeBrowser fresh = new FakeBrowser();
        push.respond("/wpush/b", 201);
        subscribe(accountId, fresh, "/wpush/b");
        UUID third = enqueue("m3", accountId);
        worker.drain();
        assertThat(statusOf(third)).as("subscribing again restores it").isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("turning push off withdraws the consent, and the next campaign refuses the customer")
    void turningPushOffWithdrawsTheConsent() {
        subscribe(accountId, browser, "/wpush/a");
        assertThat(consentGranted("PUSH")).isTrue();
        assertThat(subscriptions.subscribed(TENANT, BRAND, accountId)).isTrue();

        subscriptions.unsubscribe(TENANT, BRAND, accountId);

        assertThat(consentGranted("PUSH")).isFalse();
        assertThat(subscriptions.subscribed(TENANT, BRAND, accountId)).isFalse();
        assertThat(subscriptionStatus()).isEqualTo("REVOKED");
        assertThat(subscriptionSecret()).isNull();
        UUID notificationId = enqueue("m1", accountId);
        worker.drain();
        assertThat(statusOf(notificationId)).isEqualTo("SUPPRESSED");
        assertThat(suppressionReasonOf(notificationId)).isEqualTo("CONSENT_WITHHELD");
        assertThat(push.received()).isEmpty();

        subscriptions.unsubscribe(TENANT, BRAND, accountId);
        assertThat(withdrawals()).as("turning off what is off records nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("consent without a subscription is an honest «no recipient endpoint», not a failure")
    void consentWithoutASubscription() {
        grantConsent("PUSH");

        UUID notificationId = enqueue("m1", accountId);
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("SUPPRESSED");
        assertThat(suppressionReasonOf(notificationId)).isEqualTo("NO_RECIPIENT_ENDPOINT");
    }

    @Test
    @DisplayName(
            "a browser that moves to another customer leaves the first one, so a promotion is never shown to a stranger")
    void aBrowserBelongsToOneCustomerAtATime() {
        UUID second = secondCustomer();
        subscribe(accountId, browser, "/wpush/shared");
        subscribe(second, browser, "/wpush/shared");

        assertThat(subscriptions.subscribed(TENANT, BRAND, accountId)).isFalse();
        assertThat(subscriptions.subscribed(TENANT, BRAND, second)).isTrue();
        assertThat(endedReasonOf(accountId)).isEqualTo("BROWSER_USED_BY_ANOTHER_CUSTOMER");

        UUID forFirst = enqueue("m1", accountId);
        UUID forSecond = enqueue("m2", second);
        worker.drain();

        assertThat(statusOf(forFirst)).isEqualTo("SUPPRESSED");
        assertThat(suppressionReasonOf(forFirst)).isEqualTo("NO_RECIPIENT_ENDPOINT");
        assertThat(statusOf(forSecond)).isEqualTo("DELIVERED");
        assertThat(push.receivedAt("/wpush/shared")).hasSize(1);
    }

    @Test
    @DisplayName("a customer has one live subscription per brand: a newer one replaces the older")
    void aNewerSubscriptionReplacesTheOlder() {
        subscribe(accountId, browser, "/wpush/old");
        FakeBrowser phone = new FakeBrowser();
        subscribe(accountId, phone, "/wpush/new");

        UUID notificationId = enqueue("m1", accountId);
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        assertThat(push.receivedAt("/wpush/old")).isEmpty();
        assertThat(push.receivedAt("/wpush/new")).hasSize(1);
        assertThat(liveSubscriptions()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retry of the same message carries the same replacement key, so it is never shown twice")
    void aRetryKeepsTheTopic() {
        subscribe(accountId, browser, "/wpush/a");
        push.respond("/wpush/a", 503);

        UUID notificationId = enqueue("m1", accountId);
        worker.drain();
        assertThat(statusOf(notificationId)).isEqualTo("RETRY_PENDING");

        push.respond("/wpush/a", 201);
        clock.advance(Duration.ofMinutes(5));
        makeDue(notificationId);
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("DELIVERED");
        var requests = push.receivedAt("/wpush/a");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).header("topic")).isEqualTo(requests.get(0).header("topic"));
        assertThat(requests.get(0).header("ttl"))
                .as("the push service may hold a promotion for as long as the platform would send it")
                .isEqualTo(Long.toString(Duration.ofHours(12).toSeconds()));
    }

    @Test
    @DisplayName("the console is told why push cannot send, per brand, and what the channel reports")
    void wiringAndReporting() throws Exception {
        assertThat(router.wiring(TENANT, BRAND, "PUSH", CampaignMessagePort.PURPOSE_MARKETING)
                        .isWired())
                .isTrue();
        assertThat(router.wiring(TENANT, BRAND, "PUSH", CampaignMessagePort.PURPOSE_COURIER)
                        .reason())
                .as("web push carries marketing and nothing else")
                .isEqualTo("PUSH_PURPOSE_NOT_PERMITTED");

        build(false);
        var notConfigured = router.wiring(TENANT, BRAND, "PUSH", CampaignMessagePort.PURPOSE_MARKETING);
        assertThat(notConfigured.isWired()).isFalse();
        assertThat(notConfigured.reason()).isEqualTo("PUSH_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("an unconfigured platform refuses at send time with a stable code and sends nothing")
    void anUnconfiguredPlatformRefuses() throws Exception {
        build(false);
        subscribe(accountId, browser, "/wpush/a");

        UUID notificationId = enqueue("m1", accountId);
        worker.drain();

        assertThat(statusOf(notificationId)).isEqualTo("FAILED_TERMINAL");
        assertThat(push.received()).isEmpty();
        assertThat(notifications.attempts(TENANT, notificationId).getFirst().failureCode())
                .isEqualTo("PUSH_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("a click token that can no longer be tapped is removed, and one that still can is kept")
    void expiredClickTokensAreSwept() throws Exception {
        subscribe(accountId, browser, "/wpush/a");
        enqueue("m1", accountId);
        worker.drain();
        String live = tokenOf(JSON.readTree(
                browser.read(push.receivedAt("/wpush/a").getFirst().body())));
        assertThat(everythingIn("notifications.push_click_tokens")).isNotEmpty();
        var sweeper = new uz.horecaos.platform.notifications.application.PushClickTokenSweeper(clicks);

        assertThat(sweeper.purgeExpired()).as("nothing has expired yet").isZero();
        assertThat(clicks.click(live)).isTrue();

        clock.advance(Duration.ofDays(30));
        assertThat(sweeper.purgeExpired()).isEqualTo(1);
        assertThat(everythingIn("notifications.push_click_tokens")).isEmpty();
        assertThat(sweeper.purgeExpired())
                .as("and a second pass has nothing left to do")
                .isZero();
    }

    @Test
    @DisplayName("a push of any class but marketing is not sent, and cannot be authored")
    void onlyMarketingPushIsWired() {
        subscribe(accountId, browser, "/wpush/a");
        UUID transactional = UUID.randomUUID();
        notifications.createIntent(new NewNotification(
                transactional,
                TENANT,
                BRAND,
                null,
                NotificationClass.TRANSACTIONAL_OPTIONAL.name(),
                NotificationChannel.PUSH.name(),
                "PROMO_PUSH",
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
        assertThat(push.received()).isEmpty();

        assertThatThrownBy(() -> templates.createTemplate(
                        TENANT,
                        BRAND,
                        "ORDER_PUSH",
                        NotificationClass.TRANSACTIONAL_REQUIRED,
                        NotificationChannel.PUSH,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MARKETING");
    }

    @Test
    @DisplayName(
            "a push wording needs a title in every language, a body a notification can hold, and a path on the storefront")
    void pushWordingRules() {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, "RULES", NotificationClass.MARKETING, NotificationChannel.PUSH, PURPOSE);

        assertThatThrownBy(() -> addVersion(templateId, null, "Matn", null, null))
                .hasMessageContaining("subject");
        assertThatThrownBy(() -> addVersion(templateId, "x".repeat(101), "Matn", null, null))
                .hasMessageContaining("title is at most 100");
        assertThatThrownBy(() -> addVersion(templateId, "Aksiya", "x".repeat(401), null, null))
                .hasMessageContaining("body is at most 400");
        for (String path : new String[] {"//evil.example/x", "https://evil.example", "menu", "/a b", "/a\\b", ""}) {
            assertThatThrownBy(() -> addVersion(templateId, "Aksiya", "Matn", null, path))
                    .as("a target of «%s» is not a path on the storefront", path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String path : new String[] {"/", "/menu", "/category/pizza?promo=PIZZA20#top"}) {
            assertThat(addVersion(templateId, "Aksiya", "Matn", null, path)).isPositive();
        }

        imageVerdict.set(TenantImageCheck.Verdict.WRONG_ASPECT_RATIO);
        assertThatThrownBy(() -> addVersion(templateId, "Aksiya", "Matn", UUID.randomUUID(), "/menu"))
                .hasMessageContaining("wrong aspect ratio");
        imageVerdict.set(TenantImageCheck.Verdict.ACCEPTED);
        assertThat(addVersion(templateId, "Aksiya", "Matn", UUID.randomUUID(), "/menu"))
                .isPositive();

        UUID smsTemplate = templates.createTemplate(
                TENANT, BRAND, "SMS_COVER", NotificationClass.MARKETING, NotificationChannel.SMS, PURPOSE);
        assertThatThrownBy(() -> addVersion(smsTemplate, null, "Matn", UUID.randomUUID(), null))
                .as("nothing would show a cover or a target on an SMS")
                .hasMessageContaining("PUSH");
    }

    @Test
    @DisplayName(
            "a wording's cover and target reach the notification: the cover through the storefront's own media route")
    void theCoverAndTargetReachTheNotification() throws Exception {
        UUID asset = UUID.randomUUID();
        activatePushTemplate("PROMO_COVER", asset, "/category/pizza?promo=PIZZA20");
        subscribe(accountId, browser, "/wpush/a");

        enqueue("m1", accountId, "PROMO_COVER");
        worker.drain();

        JsonNode payload = JSON.readTree(
                browser.read(push.receivedAt("/wpush/a").getFirst().body()));
        assertThat(payload.path("image").asString())
                .isEqualTo(ORIGIN + "/api/v1/storefront/tenants/" + TENANT + "/media/" + asset + "?variant=w800");
        assertThat(payload.path("path").asString()).isEqualTo("/category/pizza?promo=PIZZA20");
    }

    @Test
    @DisplayName(
            "a subscription the platform will not send to is refused at the door, with a reason and without the URL")
    void refusedSubscriptions() {
        PushSubscriptionService strict = new PushSubscriptionService(
                new JdbcPushSubscriptionStore(jdbc),
                notifications,
                new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                        new EnvironmentSecretResolver(
                                Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")
                                        ::get,
                                clock),
                        "local")),
                new PushEndpointPolicy("fcm.googleapis.com", true),
                consentService,
                consentService,
                JsonMapper.builder().build(),
                clock);

        for (String endpoint : new String[] {
            "http://fcm.googleapis.com/fcm/send/abc",
            "https://evil.example/fcm/send/abc",
            "https://fcm.googleapis.com.evil.example/fcm/send/abc",
            "https://169.254.169.254/latest/meta-data",
            "https://user:pass@fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x",
            "not a url"
        }) {
            assertThatThrownBy(() -> strict.subscribe(
                            TENANT, BRAND, accountId, new Subscribe(endpoint, browser.p256dh(), browser.auth(), null)))
                    .as("%s is not a push service", endpoint)
                    .isInstanceOf(PushSubscriptionService.Refused.class)
                    .satisfies(refused -> assertThat(refused.getMessage())
                            .doesNotContain("evil")
                            .doesNotContain("169"));
        }
        assertThatThrownBy(() -> strict.subscribe(
                        TENANT,
                        BRAND,
                        accountId,
                        new Subscribe("https://fcm.googleapis.com/fcm/send/abc", browser.p256dh(), "AAAA", null)))
                .hasMessage("PUSH_KEYS_INVALID");
        String offCurve = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]);
        assertThatThrownBy(() -> strict.subscribe(
                        TENANT,
                        BRAND,
                        accountId,
                        new Subscribe("https://fcm.googleapis.com/fcm/send/abc", offCurve, browser.auth(), null)))
                .hasMessage("PUSH_KEYS_INVALID");
        assertThatThrownBy(() -> strict.subscribe(
                        TENANT,
                        BRAND,
                        accountId,
                        new Subscribe(
                                "https://fcm.googleapis.com/fcm/send/abc",
                                browser.p256dh(),
                                browser.auth(),
                                NOW.minusSeconds(1))))
                .hasMessage("PUSH_SUBSCRIPTION_EXPIRED");
        assertThat(liveSubscriptions()).isZero();
        assertThat(consentGranted("PUSH"))
                .as("a refused subscription records no consent")
                .isFalse();

        strict.subscribe(
                TENANT,
                BRAND,
                accountId,
                new Subscribe("https://fcm.googleapis.com/fcm/send/abc", browser.p256dh(), browser.auth(), null));
        assertThat(liveSubscriptions()).isEqualTo(1);
        assertThat(consentGranted("PUSH")).as("the opt-in is the consent").isTrue();
    }

    @Test
    @DisplayName("a subscription whose browser-given expiry has passed is not a recipient")
    void anExpiredSubscriptionIsNotARecipient() {
        subscriptions.subscribe(
                TENANT,
                BRAND,
                accountId,
                new Subscribe(
                        push.endpoint("/wpush/a"), browser.p256dh(), browser.auth(), NOW.plus(Duration.ofDays(1))));
        assertThat(subscriptions.subscribed(TENANT, BRAND, accountId)).isTrue();

        clock.advance(Duration.ofDays(2));

        assertThat(subscriptions.subscribed(TENANT, BRAND, accountId)).isFalse();
        UUID notificationId = enqueue("m1", accountId);
        worker.drain();
        assertThat(suppressionReasonOf(notificationId)).isEqualTo("NO_RECIPIENT_ENDPOINT");
        assertThat(push.received()).isEmpty();
    }

    @Test
    @DisplayName("an erased customer's subscription ends and keeps no secret")
    void erasureEndsTheSubscription() {
        subscribe(accountId, browser, "/wpush/a");

        var counts = new uz.horecaos.platform.notifications.application.PushSubscriptionErasureParticipant(
                        new JdbcPushSubscriptionStore(jdbc), notifications, clock)
                .erase(
                        TENANT,
                        accountId,
                        uz.horecaos.platform.customers.spi.CustomerErasureParticipant.ErasedContacts.none());

        assertThat(counts.rowsByTable()).containsEntry("notifications.push_subscriptions", 1);
        assertThat(subscriptionStatus()).isEqualTo("REVOKED");
        assertThat(endedReasonOf(accountId)).isEqualTo("CUSTOMER_ERASED");
        assertThat(subscriptionSecret()).isNull();
        assertThat(endpointStatus()).isEqualTo("RETIRED");
    }

    @Test
    @DisplayName(
            "neither the push service's address nor a key is in a log line, through a gone subscription and a fault")
    void nothingSensitiveIsLogged() {
        subscribe(accountId, browser, "/wpush/secret-device-token");
        try (JvmLogCapture logs = JvmLogCapture.start()) {
            push.respond("/wpush/secret-device-token", 503);
            enqueue("m1", accountId);
            worker.drain();
            push.respond("/wpush/secret-device-token", 410);
            clock.advance(Duration.ofMinutes(5));
            makeDueAll();
            worker.drain();

            for (ILoggingEvent event : logs.snapshot()) {
                if (event.getLoggerName().contains("httpserver")) {
                    // The fake push service's own server logging: it is the far side of the wire, not the platform.
                    continue;
                }
                assertThat(event.getFormattedMessage())
                        .doesNotContain("secret-device-token")
                        .doesNotContain("127.0.0.1")
                        .doesNotContain(browser.p256dh())
                        .doesNotContain(browser.auth());
            }
        }
        assertThat(subscriptionStatus()).isEqualTo("EXPIRED");
    }

    // ---------------------------------------------------------------- fixtures

    private int addVersion(
            UUID templateId, @Nullable String title, String body, @Nullable UUID cover, @Nullable String path) {
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(title, body, cover, path)));
        return templates.addVersion(TENANT, BRAND, templateId, wordings, Map.of());
    }

    private void activatePushTemplate(String key, @Nullable UUID cover, @Nullable String path) {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, key, NotificationClass.MARKETING, NotificationChannel.PUSH, PURPOSE);
        int version = addVersion(templateId, "Aksiya", "Chegirma 20%", cover, path);
        templates.activate(TENANT, BRAND, templateId, version, "copy-approver");
    }

    private void subscribe(UUID account, FakeBrowser who, String path) {
        subscriptions.subscribe(
                TENANT, BRAND, account, new Subscribe(push.endpoint(path), who.p256dh(), who.auth(), null));
    }

    private UUID enqueue(String key, UUID account) {
        return enqueue(key, account, "PROMO_PUSH");
    }

    private UUID enqueue(String key, UUID account, String templateKey) {
        return java.util.Objects.requireNonNull(router.enqueue(new CampaignMessagePort.MarketingMessage(
                TENANT,
                BRAND,
                account,
                "PUSH",
                templateKey,
                PURPOSE,
                campaign,
                key + "-" + account,
                Map.of(),
                clock.instant(),
                null)));
    }

    private final UUID campaign = UUID.randomUUID();

    private String stateOf(UUID notificationId) {
        return java.util.Objects.requireNonNull(
                        router.deliveryEvidence(TENANT, List.of(notificationId)).get(notificationId))
                .state();
    }

    private UUID campaignOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().subjectId();
    }

    private static String tokenOf(JsonNode payload) {
        String click = payload.path("click").asString();
        return click.substring(click.lastIndexOf('/') + 1);
    }

    private void grantConsent(String channel) {
        consentService.record(
                TENANT,
                accountId,
                BRAND,
                PURPOSE,
                channel,
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                "storefront-checkbox",
                NOW.minusSeconds(86_400));
    }

    private boolean consentGranted(String channel) {
        return consentService
                .consentFor(TENANT, accountId, BRAND, PURPOSE, channel)
                .map(state -> state.granted())
                .orElse(false);
    }

    private int withdrawals() {
        return jdbc.sql("SELECT COUNT(*) FROM customer.consent_decisions WHERE decision = 'WITHDRAWN'")
                .query(Integer.class)
                .single();
    }

    private int readEvents() {
        return jdbc.sql("SELECT COUNT(*) FROM notifications.delivery_status_events WHERE normalized_status = 'READ'")
                .query(Integer.class)
                .single();
    }

    private int liveSubscriptions() {
        return jdbc.sql("SELECT COUNT(*) FROM notifications.push_subscriptions WHERE status = 'ACTIVE'")
                .query(Integer.class)
                .single();
    }

    private String subscriptionStatus() {
        return jdbc.sql("SELECT status FROM notifications.push_subscriptions WHERE customer_account_id = :id "
                        + "ORDER BY created_at DESC LIMIT 1")
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    private @Nullable String subscriptionSecret() {
        return jdbc.sql("SELECT secret_ciphertext FROM notifications.push_subscriptions "
                        + "WHERE customer_account_id = :id ORDER BY created_at DESC LIMIT 1")
                .param("id", accountId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String endpointStatus() {
        return jdbc.sql("SELECT status FROM notifications.recipient_endpoints WHERE customer_account_id = :id "
                        + "AND push_subscription_id IS NOT NULL ORDER BY created_at DESC LIMIT 1")
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    private String endedReasonOf(UUID account) {
        return jdbc.sql("SELECT ended_reason FROM notifications.push_subscriptions WHERE customer_account_id = :id "
                        + "AND status <> 'ACTIVE' ORDER BY created_at DESC LIMIT 1")
                .param("id", account)
                .query(String.class)
                .single();
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

    private void makeDueAll() {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now WHERE status = 'RETRY_PENDING'")
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

    private UUID secondCustomer() {
        UUID second = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status)
                VALUES (:id, :tenantId, 'ACTIVE')
                """).param("id", second).param("tenantId", TENANT).update();
        return second;
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
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE notifications.push_click_tokens, notifications.push_subscriptions, "
                        + "notifications.delivery_status_events, "
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
