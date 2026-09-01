package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
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
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramChatLockService;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramMessageTracker;
import uz.horecaos.platform.integration.provider.telegram.TelegramOperationsSubscriptionDirectory;
import uz.horecaos.platform.marketing.application.AudienceService;
import uz.horecaos.platform.marketing.application.CampaignCostEstimator;
import uz.horecaos.platform.marketing.application.CampaignFeedbackService;
import uz.horecaos.platform.marketing.application.CampaignSendService;
import uz.horecaos.platform.marketing.application.CampaignService;
import uz.horecaos.platform.marketing.application.CustomerMetricProjectionService;
import uz.horecaos.platform.marketing.application.MarketingEligibility;
import uz.horecaos.platform.marketing.domain.AudiencePredicate;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.PredicateOperator;
import uz.horecaos.platform.marketing.domain.PredicateType;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.notifications.api.CustomerProviderBindingSync;
import uz.horecaos.platform.notifications.application.CampaignBlockRateMonitor;
import uz.horecaos.platform.notifications.application.CampaignPacer;
import uz.horecaos.platform.notifications.application.CampaignTelegramDeliveryService;
import uz.horecaos.platform.notifications.application.CustomerProviderBindingSyncService;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationPreferenceService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.application.OperationsAlertFanoutService;
import uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcCampaignPaceCursorStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0059 stage 4, end to end: an approved TELEGRAM campaign expands,
 * paces itself under the bot's ceiling, delivers to every consented and
 * linked recipient, skips the rest with an honest reason, and the block-rate
 * guard pauses the send once its threshold is crossed — against a real
 * PostgreSQL and {@link FakeTelegramBotApi}, riding the same worker, adapter,
 * lease, and binding-lifecycle machinery {@code TelegramOperationsNotificationIntegrationTest}
 * proves for operations alerts.
 *
 * <p>The clock is fixed rather than advanced: every message this suite sends
 * is forced due with a direct {@code next_attempt_at} update (the same idiom
 * {@code NotificationDeliveryTests#makeDue} uses), so dispatch ordering is
 * chosen by the test rather than by real elapsed time, and the pacer's own
 * spacing is asserted from the {@code scheduled_at} values it wrote — the
 * "inject the clock, never sleep-and-hope" discipline this stage's own tests
 * were asked for.
 */
class CampaignBroadcastIntegrationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final String PURPOSE = "MARKETING_PROMOTIONS";
    private static final String TEMPLATE_KEY = "MARKETING_BROADCAST_PROMO";

    /** 10/s, {@link CampaignPacer#TELEGRAM_CAMPAIGN_RATE_PER_SECOND}'s own default: a 100ms slot spacing. */
    private static final double RATE_PER_SECOND = CampaignPacer.TELEGRAM_CAMPAIGN_RATE_PER_SECOND;

    private static TestDatabase.Handle db;

    private FakeTelegramBotApi bot;
    private CamelContext camel;
    private JdbcClient jdbc;
    private Clock clock;

    private JdbcNotificationStore notifications;
    private JdbcCampaignStore campaignStore;
    private TelegramCustomerLinkService customerLinks;
    private NotificationWorker worker;
    private CampaignSendService sends;
    private CampaignService campaigns;
    private AudienceService audiences;
    private CustomerMetricProjectionService projection;
    private ConsentService consent;
    private JdbcEngagementStore engagementStore;
    private SimpleMeterRegistry meters;
    private OperationsAlertFanoutService operationsAlerts;

    private UUID installationId;
    private long nextChatId = 700_001;
    private final Map<UUID, Long> chatIdByAccount = new LinkedHashMap<>();

    private final ActorRef author = ActorRef.user(UUID.randomUUID().toString(), "Author");
    private final ActorRef approver = ActorRef.user(UUID.randomUUID().toString(), "Approver");

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the broadcast path test");
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
        bot = FakeTelegramBotApi.start();

        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        meters = new SimpleMeterRegistry();

        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.provider_notification.platform.telegram-bot", "a-test-bot-token")::get, clock);

        seedTenant();
        installationId = seedTelegramInstallation();

        notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(templateStore, objectMapper, clock);
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, objectMapper);

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        consent = new ConsentService(customerStore, clock);

        campaignStore = new JdbcCampaignStore(jdbc);
        JdbcAudienceStore audienceStore = new JdbcAudienceStore(jdbc, objectMapper);
        JdbcCustomerMetricStore metricStore = new JdbcCustomerMetricStore(jdbc);
        engagementStore = new JdbcEngagementStore(jdbc);

        RecipientContactDirectory contacts = new NoOpContactDirectory();
        MarketingEligibility eligibility = new MarketingEligibility(consent, contacts, engagementStore);
        projection = new CustomerMetricProjectionService(metricStore, clock);
        audiences = new AudienceService(audienceStore, metricStore, engagementStore, eligibility, audit, clock);

        // Telegram plumbing, the same wiring TelegramOperationsNotificationIntegrationTest
        // uses for the operations-alert side of the same adapter.
        TelegramBotApiClient botApiClient = new TelegramBotApiClient(objectMapper);
        TelegramBindingStore bindingStore = new TelegramBindingStore(jdbc, clock, audit);
        TelegramChatLockService locks = new TelegramChatLockService(jdbc, clock);
        TelegramMessageTracker tracker = new TelegramMessageTracker(jdbc, clock);
        BotActionTokenStore actionTokens = new BotActionTokenStore(jdbc, clock);
        CustomerProviderBindingSync bindingSync = new CustomerProviderBindingSyncService(
                notifications, new NotificationPreferenceService(notifications, clock));
        customerLinks =
                new TelegramCustomerLinkService(jdbc, clock, Duration.ofMinutes(15), bindingStore, bindingSync, audit);

        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                botApiClient,
                bindingStore,
                locks,
                tracker,
                new TelegramCircuitBreakers(new SimpleMeterRegistry(), clock),
                actionTokens,
                bindingSync,
                clock,
                Duration.ofSeconds(20),
                Duration.ofHours(6),
                "ru");
        NotificationGateway gateway =
                new NotificationGateway(List.of(adapter), new JdbcProviderInstallationLookup(jdbc, clock), secrets);

        camel = new DefaultCamelContext();
        camel.addRoutes(new NotificationRouteBuilder(new NotificationProcessor(gateway, meters)));
        camel.start();
        CamelNotificationTransport transport = new CamelNotificationTransport(camel.createProducerTemplate(), gateway);

        // ADR 0059 stage 4: the pacer and the port implementation this whole
        // stage is about. A real pacer against a real cursor table, not a fake —
        // the pacing behaviour under test lives here.
        CampaignPacer pacer = new CampaignPacer(new JdbcCampaignPaceCursorStore(jdbc), clock, RATE_PER_SECOND);
        CampaignTelegramDeliveryService messagePort = new CampaignTelegramDeliveryService(
                notifications, templates, pacer, objectMapper, clock, Duration.ofDays(1));

        // The real block-rate guard, threshold lowered so two blocks — not
        // hundreds — cross it deterministically in a test.
        CampaignFeedbackService campaignFeedback = new CampaignFeedbackService(campaignStore, 2, 1.0, 1);

        operationsAlerts = new OperationsAlertFanoutService(
                new TelegramOperationsSubscriptionDirectory(bindingStore),
                notifications,
                new TelegramOperationsEntitlementGate(new AlwaysEntitledService()),
                objectMapper,
                clock);
        CampaignBlockRateMonitor campaignBlockRate =
                new CampaignBlockRateMonitor(campaignFeedback, operationsAlerts, meters);

        NotificationEligibilityService notificationEligibility = new NotificationEligibilityService(
                notifications,
                templates,
                consent,
                contacts,
                new StubOrderDirectory(),
                transport,
                campaignFeedback,
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
        worker = new NotificationWorker(
                notifications, notificationEligibility, dispatch, clock, 50, Duration.ofMinutes(2));

        CampaignCostEstimator estimator = new CampaignCostEstimator();
        campaigns = new CampaignService(
                campaignStore,
                engagementStore,
                audiences,
                estimator,
                messagePort,
                audit,
                new AlwaysEntitledService(),
                clock);
        sends = new CampaignSendService(
                campaignStore, audienceStore, engagementStore, eligibility, estimator, messagePort, clock, 100);

        activateCustomerTemplate(templates);
        activateBlockAlertTemplate(templates);
        subscribeOperationsChat(bindingStore);
    }

    @AfterEach
    void tearDown() {
        if (camel != null) {
            camel.stop();
        }
        if (bot != null) {
            bot.close();
        }
    }

    @Test
    @DisplayName("a campaign paces itself, delivers to the reachable and consented, and skips the rest honestly")
    void theBroadcastPath() {
        UUID delivered = customer();
        grantConsent(delivered);
        long delivredChat = linkCustomer(delivered);

        UUID blockedFirst = customer();
        grantConsent(blockedFirst);
        long blockedFirstChat = linkCustomer(blockedFirst);

        UUID blockedSecond = customer();
        grantConsent(blockedSecond);
        long blockedSecondChat = linkCustomer(blockedSecond);

        UUID pausedAfter = customer();
        grantConsent(pausedAfter);
        long pausedAfterChat = linkCustomer(pausedAfter);

        UUID unlinked = customer();
        grantConsent(unlinked);
        // No customerLinks.link call: consented, but never linked a chat.

        UUID neverConsented = customer();
        // No grantConsent call at all: absence is not consent.

        UUID frequencyCapped = customer();
        grantConsent(frequencyCapped);
        for (int index = 0; index < 3; index++) {
            engagementStore.recordSend(
                    TENANT, BRAND, frequencyCapped, "SMS", "CAMPAIGN", UUID.randomUUID(), null, NOW.minusSeconds(3600));
        }

        projection.backfill(TENANT, BRAND);

        UUID audience = audiences.define(
                TENANT,
                BRAND,
                "Everybody " + UUID.randomUUID(),
                null,
                List.of(AudiencePredicate.numeric(
                        PredicateType.ORDER_COUNT, PredicateOperator.AT_MOST, 1_000_000L, null)),
                UUID.fromString(author.subject()),
                "corr");

        UUID campaignId = readyTelegramCampaign(audience);

        var outcome = sends.expandNextBatch(TENANT, campaignId);
        // F and G are excluded when the snapshot is built (below), so they are
        // never even candidates for expansion; nothing changed for the other
        // five between snapshot and send, so none of them is refused a second
        // time here either — expandNextBatch's own refusal count is for the
        // unsubscribe-in-the-gap case, not this one.
        assertThat(outcome.queued()).isEqualTo(5);
        assertThat(outcome.refused()).isZero();

        // -------------------------------------------------- refused at snapshot
        UUID snapshotId = campaignStore.find(TENANT, campaignId).orElseThrow().snapshotId();
        assertThat(snapshotExclusionReason(snapshotId, neverConsented)).isEqualTo("CONSENT_WITHHELD");
        assertThat(snapshotExclusionReason(snapshotId, frequencyCapped)).isEqualTo("FREQUENCY_CAP_REACHED");

        // ------------------------------------------------------- the pacer
        // Every queued recipient's message was paced, spaced RATE_PER_SECOND
        // apart, regardless of which one lands on which slot.
        List<Instant> scheduledTimes = new ArrayList<>();
        for (UUID account : List.of(delivered, blockedFirst, blockedSecond, pausedAfter, unlinked)) {
            scheduledTimes.add(scheduledAtOf(notificationIdFor(campaignId, account)));
        }
        scheduledTimes.sort(Instant::compareTo);
        Duration interval = Duration.ofNanos((long) (1_000_000_000.0 / RATE_PER_SECOND));
        assertThat(scheduledTimes.getFirst()).isEqualTo(NOW);
        for (int index = 1; index < scheduledTimes.size(); index++) {
            assertThat(Duration.between(scheduledTimes.get(index - 1), scheduledTimes.get(index)))
                    .as("no burst sends: consecutive campaign slots are exactly one pacing interval apart")
                    .isEqualTo(interval);
        }

        // Exactly one recipient's slot lands on NOW itself and would already be
        // claimable; every other slot is strictly later and never becomes
        // claimable on its own since the clock under test never advances. Pushed
        // out of the way so the rest of this test controls, one at a time, which
        // recipient's message the next drain() actually reaches — the same
        // determinism makeDue below gives for bringing one forward.
        for (UUID account : List.of(delivered, blockedFirst, blockedSecond, pausedAfter, unlinked)) {
            quiesce(notificationIdFor(campaignId, account));
        }

        // ---------------------------------------------------- the happy path
        UUID deliveredNotification = notificationIdFor(campaignId, delivered);
        makeDue(deliveredNotification);
        worker.drain();
        assertThat(statusOf(deliveredNotification)).isEqualTo("DELIVERED");
        assertThat(bot.messagesSentTo(delivredChat)).hasSize(1);
        assertThat(notifications.attempts(TENANT, deliveredNotification)).hasSize(1);
        assertThat(campaignStore.find(TENANT, campaignId).orElseThrow().blockedCount())
                .isZero();
        assertThat(campaignStore.find(TENANT, campaignId).orElseThrow().status())
                .isEqualTo(CampaignStatus.SENDING);

        // -------------------------------------------- the unreachable recipient
        UUID unlinkedNotification = notificationIdFor(campaignId, unlinked);
        makeDue(unlinkedNotification);
        worker.drain();
        assertThat(statusOf(unlinkedNotification)).isEqualTo("SUPPRESSED");
        assertThat(suppressionReasonOf(unlinkedNotification)).isEqualTo("NO_RECIPIENT_ENDPOINT");
        assertThat(notifications.attempts(TENANT, unlinkedNotification)).isEmpty();

        // ------------------------------------------------- the first block
        bot.kick(blockedFirstChat, false);
        UUID blockedFirstNotification = notificationIdFor(campaignId, blockedFirst);
        makeDue(blockedFirstNotification);
        worker.drain();

        assertThat(statusOf(blockedFirstNotification)).isEqualTo("FAILED_TERMINAL");
        assertThat(notifications
                        .attempts(TENANT, blockedFirstNotification)
                        .getFirst()
                        .failureCode())
                .startsWith("BINDING_RETIRED");
        assertThat(telegramPreferenceEnabled(blockedFirst))
                .as("ADR 0058: a customer-binding 403 is consent revocation in effect")
                .isFalse();
        assertThat(campaignStore.find(TENANT, campaignId).orElseThrow().blockedCount())
                .isEqualTo(1);
        assertThat(campaignStore.find(TENANT, campaignId).orElseThrow().status())
                .as("one block, under the threshold of two, does not pause the campaign")
                .isEqualTo(CampaignStatus.SENDING);
        assertThat(meters.counter("horecaos.notifications.campaign_blocks", "channel", "TELEGRAM")
                        .count())
                .isEqualTo(1.0);

        // ------------------------------------------------ the second block: pause
        bot.kick(blockedSecondChat, false);
        UUID blockedSecondNotification = notificationIdFor(campaignId, blockedSecond);
        makeDue(blockedSecondNotification);
        worker.drain();

        var pausedCampaign = campaignStore.find(TENANT, campaignId).orElseThrow();
        assertThat(pausedCampaign.blockedCount()).isEqualTo(2);
        assertThat(pausedCampaign.status())
                .as("the second block crosses the configured threshold of two")
                .isEqualTo(CampaignStatus.PAUSED);
        assertThat(meters.counter("horecaos.notifications.campaign_blocks", "channel", "TELEGRAM")
                        .count())
                .isEqualTo(2.0);

        // The pause fanned out a fresh operations-alert notification within
        // that same drain() call; it is due immediately (fanOut schedules for
        // "now") but was created after that call had already claimed its
        // batch, so it needs one more sweep to actually go out — the same
        // adapter and the same fake, over a real operations binding.
        worker.drain();
        assertThat(bot.messagesSentTo(OPERATIONS_CHAT_ID)).hasSize(1);

        // -------------------------------------------- suppressed after the pause
        // pausedAfter's message was already created and paced before the pause;
        // it must never reach the bot once the campaign has stopped.
        UUID pausedAfterNotification = notificationIdFor(campaignId, pausedAfter);
        makeDue(pausedAfterNotification);
        worker.drain();

        assertThat(statusOf(pausedAfterNotification)).isEqualTo("SUPPRESSED");
        assertThat(suppressionReasonOf(pausedAfterNotification)).isEqualTo("CAMPAIGN_NOT_SENDING");
        assertThat(bot.messagesSentTo(pausedAfterChat))
                .as("a paused campaign must not reach a chat it had already scheduled a slot for")
                .isEmpty();
        assertThat(notifications.attempts(TENANT, pausedAfterNotification)).isEmpty();

        // ADR 0059 stage 4's own estimate: 5 reachable-by-consent members paced
        // at RATE_PER_SECOND, computed and stored at prepare() time.
        assertThat(campaignStore.find(TENANT, campaignId).orElseThrow().estimatedDeliverySeconds())
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------- fixtures

    private static final long OPERATIONS_CHAT_ID = 900_001;

    private UUID readyTelegramCampaign(UUID audienceId) {
        UUID id = campaigns.create(
                TENANT,
                BRAND,
                "Broadcast " + UUID.randomUUID(),
                MarketingChannel.MESSAGING_APP,
                PURPOSE,
                audienceId,
                TEMPLATE_KEY,
                100,
                null,
                "UZS",
                null,
                null,
                UUID.fromString(author.subject()));
        campaigns.prepare(TENANT, id, author, "corr");
        campaigns.submitForReview(TENANT, id);
        campaigns.approve(
                TENANT, id, UUID.fromString(approver.subject()), UUID.randomUUID(), approver, "Reviewed", "corr");
        assertThat(campaigns.start(TENANT, id)).isTrue();
        return id;
    }

    private UUID customer() {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', 'ru', :now)
                """)
                .param("id", accountId)
                .param("tenantId", TENANT)
                .param("now", OffsetDateTime.ofInstant(NOW.minusSeconds(172_800), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO customer.brand_profiles (id, tenant_id, brand_id, customer_account_id)
                VALUES (:id, :tenantId, :brandId, :accountId)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("accountId", accountId)
                .update();
        return accountId;
    }

    private void grantConsent(UUID accountId) {
        // Recorded under "TELEGRAM", the ADR 0020 consent channel a
        // MESSAGING_APP campaign is actually checked against at both snapshot
        // build and send — see MarketingEligibility#consentChannel.
        consent.record(
                TENANT,
                accountId,
                BRAND,
                PURPOSE,
                "TELEGRAM",
                ConsentService.Decision.GRANTED,
                "v1",
                ConsentService.Source.STOREFRONT,
                "storefront-checkbox",
                NOW.minusSeconds(86_400));
    }

    private long linkCustomer(UUID accountId) {
        long chatId = nextChatId++;
        customerLinks.link(TENANT, installationId, BRAND, accountId, chatId, chatId, NOW);
        chatIdByAccount.put(accountId, chatId);
        return chatId;
    }

    private void activateCustomerTemplate(NotificationTemplateService templates) {
        UUID templateId = templates.createTemplate(
                TENANT, BRAND, TEMPLATE_KEY, NotificationClass.MARKETING, NotificationChannel.TELEGRAM, PURPOSE);
        activateAllLocales(templates, templateId, "Скидка 20% на всё сегодня!", Map.of());
    }

    private void activateBlockAlertTemplate(NotificationTemplateService templates) {
        UUID templateId = templates.createTemplate(
                TENANT,
                BRAND,
                CampaignBlockRateMonitor.BLOCK_RATE_PAUSED_TEMPLATE_KEY,
                NotificationClass.OPERATIONS_ALERT,
                NotificationChannel.TELEGRAM,
                null);
        activateAllLocales(
                templates,
                templateId,
                "Campaign paused: {{blockedCount}} of {{attempted}} blocked",
                Map.of("blockedCount", "string", "attempted", "string"));
    }

    private void activateAllLocales(
            NotificationTemplateService templates, UUID templateId, String body, Map<String, String> schema) {
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, body)));
        int versionNumber = templates.addVersion(TENANT, templateId, wordings, schema);
        templates.activate(TENANT, templateId, versionNumber, "test-approver");
    }

    private void subscribeOperationsChat(TelegramBindingStore bindingStore) {
        UUID bindingId =
                bindingStore.createBinding(TENANT, installationId, BRAND, null, OPERATIONS_CHAT_ID, null, 555_000_001L);
        bindingStore.subscribe(TENANT, bindingId, Set.of(CampaignBlockRateMonitor.BLOCK_RATE_PAUSED_TEMPLATE_KEY));
    }

    private void seedTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'broadcast-pilot', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'broadcast-pilot-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private UUID seedTelegramInstallation() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('telegram-fake', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                """).param("baseUrl", bot.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'telegram-fake',
                        'Pilot bot', 'ACTIVE',
                        'horecaos:local:provider_notification:platform:telegram-bot',
                        'horecaos:local:provider_notification:platform:telegram-bot')
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE integration.telegram_tracked_messages, integration.telegram_chat_locks, "
                        + "integration.telegram_binding_events, integration.telegram_bindings, "
                        + "integration.telegram_pending_links CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE notifications.campaign_pace_cursors, notifications.delivery_status_events, "
                        + "notifications.delivery_attempts, notifications.notifications, "
                        + "notifications.recipient_endpoints, notifications.template_versions, "
                        + "notifications.templates, notifications.notification_preferences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE marketing.campaign_recipients, marketing.campaign_batches, "
                        + "marketing.campaigns, marketing.audience_snapshot_members, "
                        + "marketing.audience_snapshots, marketing.audience_predicates, "
                        + "marketing.audiences, marketing.marketing_sends, marketing.suppressions, "
                        + "marketing.metric_drift_observations, marketing.customer_metrics, "
                        + "marketing.engagement_policies CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.brand_profiles, customer.principal_links, "
                        + "customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    // ----------------------------------------------------------------- reads

    private UUID notificationIdFor(UUID campaignId, UUID accountId) {
        return jdbc.sql("""
                SELECT notification_id FROM marketing.campaign_recipients
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId AND customer_account_id = :accountId
                """)
                .param("tenantId", TENANT)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .query(UUID.class)
                .single();
    }

    private String snapshotExclusionReason(UUID snapshotId, UUID accountId) {
        return jdbc.sql("""
                SELECT exclusion_reason FROM marketing.audience_snapshot_members
                 WHERE snapshot_id = :snapshotId AND customer_account_id = :accountId
                """)
                .param("snapshotId", snapshotId)
                .param("accountId", accountId)
                .query(String.class)
                .single();
    }

    private Instant scheduledAtOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().scheduledAt();
    }

    private String statusOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().status();
    }

    private @org.jspecify.annotations.Nullable String suppressionReasonOf(UUID notificationId) {
        return notifications.find(TENANT, notificationId).orElseThrow().suppressionReason();
    }

    private boolean telegramPreferenceEnabled(UUID accountId) {
        return notifications
                .effectivePreference(TENANT, accountId, BRAND, "MARKETING", "TELEGRAM")
                .orElseThrow()
                .enabled();
    }

    /** Brings a paced row forward, since the clock under test is fixed and never advances. */
    private void makeDue(UUID notificationId) {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now WHERE id = :id")
                .param("id", notificationId)
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
    }

    /** Pushes a row's next claim out of reach, so a drain() aimed at a different row never touches it. */
    private void quiesce(UUID notificationId) {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :far WHERE id = :id")
                .param("id", notificationId)
                .param("far", OffsetDateTime.ofInstant(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }

    /**
     * No order is ever named by a campaign message or by this suite's own
     * operations alert — both resolve their subject and their recipient
     * without one, per {@code NotificationEligibilityService}'s own doc
     * comment — so an empty directory proves the broadcast path never needs
     * one, the same guarantee {@code NoOpContactDirectory} proves for the
     * customer contact directory.
     */
    private static final class StubOrderDirectory implements OrderDirectory {
        @Override
        public java.util.Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return java.util.Optional.empty();
        }
    }

    /**
     * No SMS/EMAIL contact is ever resolved by a TELEGRAM campaign message —
     * its recipient is a Telegram binding, resolved through {@link
     * JdbcNotificationStore#activeCustomerTelegramEndpointId} — so a
     * directory that answers nothing proves the campaign path never touches
     * the ADR 0015 contact machinery it holds no PII from.
     */
    private static final class NoOpContactDirectory implements RecipientContactDirectory {
        @Override
        public java.util.Optional<ContactEndpoint> primaryContact(UUID tenantId, UUID accountId, ContactMethod method) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<String> resolveValue(UUID tenantId, UUID contactPointId, String purpose) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<String> preferredLocale(UUID tenantId, UUID accountId) {
            return java.util.Optional.empty();
        }
    }
}
