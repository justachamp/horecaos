package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
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
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.application.CustomerAlertFanoutService;
import uz.horecaos.platform.notifications.application.CustomerTelegramChannelRouter;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationPreferenceService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.PaymentRefundNotificationTrigger;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.PaymentRefunded;
import uz.horecaos.platform.payments.api.PaymentAttemptFailed;
import uz.horecaos.platform.payments.notifications.PaymentFailureCustomerTrigger;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The two ADR 0020 gaps this wave closes, against a real PostgreSQL: the
 * customer-facing payment-failed and refund-issued triggers ({@link
 * PaymentFailureCustomerTrigger}, {@link PaymentRefundNotificationTrigger}),
 * and quiet hours actually holding and releasing a message ({@link
 * NotificationEligibilityService}'s new window check).
 *
 * <p>Deliberately lighter than {@code NotificationDeliveryTests}: no Camel
 * route, no SMS gateway, no encryption stack. Nothing here sends a message —
 * every assertion is about whether, and when, a row becomes {@code READY},
 * which {@link NotificationEligibilityService#evaluate} alone decides. A
 * {@link StubRecipientContactDirectory} and {@link StubConsentDirectory}
 * stand in for the customer module's own directories, the same boundary
 * {@code NotificationDeliveryTests}'s own {@code StubOrderDirectory} stands
 * in for {@code ordering}.
 *
 * <p>The clock is mutable and every quiet-hours test advances it across the
 * window boundary it asserts on, per this repository's own rule that a
 * fixture's clock is the test's clock: an instant already inside a window
 * proves nothing about where that window actually closes.
 */
class PaymentAndQuietHoursNotificationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final String CONSENT_PURPOSE = "ORDER_UPDATES";
    private static final String ZONE = "Asia/Tashkent";

    // Asia/Tashkent has no DST: local time is always UTC+05:00. Noon local,
    // comfortably outside any sensible quiet-hours window, so fixture setup
    // (seeding templates and preferences) never accidentally runs inside one.
    private static final Instant NOW = Instant.parse("2026-08-22T07:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private MutableClock clock;
    private JdbcNotificationStore notifications;
    private NotificationTemplateService templates;
    private NotificationPreferenceService preferences;
    private NotificationEligibilityService eligibility;
    private StubOrderDirectory orders;
    private StubConsentDirectory consent;
    private StubRecipientContactDirectory contacts;
    private CustomerAlertFanoutService customerAlerts;
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
        templates = new NotificationTemplateService(templateStore, objectMapper, clock);
        preferences = new NotificationPreferenceService(notifications, clock);

        orders = new StubOrderDirectory();
        consent = new StubConsentDirectory();
        contacts = new StubRecipientContactDirectory();

        eligibility = new NotificationEligibilityService(
                notifications,
                templates,
                consent,
                contacts,
                orders,
                new FakeTransport(),
                new AlwaysSendingCampaignFeedback(),
                objectMapper,
                clock,
                "ru");

        CustomerTelegramChannelRouter channelRouter =
                new CustomerTelegramChannelRouter(notifications, new AlwaysEntitledService());
        customerAlerts = new CustomerAlertFanoutService(notifications, orders, channelRouter, objectMapper, "SMS");

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    // -------------------------------------------------------- payment failed

    @Test
    @DisplayName("a failed payment attempt creates exactly one intent, addressed to the order's own customer")
    void paymentFailureCreatesExactlyOneIntentForTheRightRecipient() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(
                TENANT, PaymentFailureCustomerTrigger.PAYMENT_FAILED, NotificationClass.TRANSACTIONAL_REQUIRED, null);
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(customerAlerts, Duration.ofHours(2));

        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(),
                TENANT,
                brandId(TENANT),
                locationId(TENANT),
                orderId,
                UUID.randomUUID(),
                "DECLINED",
                NOW));

        List<NotificationRow> rows = notifications.forSubject(TENANT, "Order", orderId).stream()
                .filter(row -> PaymentFailureCustomerTrigger.PAYMENT_FAILED.equals(row.templateKey()))
                .toList();
        assertThat(rows).hasSize(1);
        NotificationRow created = rows.get(0);
        assertThat(created.notificationClass()).isEqualTo(NotificationClass.TRANSACTIONAL_REQUIRED.name());
        assertThat(created.channel()).isEqualTo(NotificationChannel.SMS.name());

        // recipient_account_id is null until eligibility resolves it from the
        // order and freezes it onto the row (see markReady) — the actual
        // "right recipient" claim only holds once that step has run.
        boolean ready = eligibility.evaluate(claim(created.id()));
        assertThat(ready).isTrue();
        assertThat(notifications.find(TENANT, created.id()).orElseThrow().recipientAccountId())
                .isEqualTo(accountId);
    }

    @Test
    @DisplayName("a replayed payment-failed event for the same attempt creates no second intent")
    void aReplayedPaymentFailureCreatesNoSecondIntent() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(
                TENANT, PaymentFailureCustomerTrigger.PAYMENT_FAILED, NotificationClass.TRANSACTIONAL_REQUIRED, null);
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(customerAlerts, Duration.ofHours(2));
        UUID attemptId = UUID.randomUUID();

        // A fresh event id both times, exactly what a redelivered fact looks
        // like — NotificationDeliveryTests's own orderConfirmed() factory
        // documents the same idiom.
        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, brandId(TENANT), locationId(TENANT), orderId, attemptId, "DECLINED", NOW));
        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, brandId(TENANT), locationId(TENANT), orderId, attemptId, "DECLINED", NOW));

        assertThat(notifications.forSubject(TENANT, "Order", orderId)).hasSize(1);
    }

    @Test
    @DisplayName("a payment-failed event naming another tenant's order notifies nobody")
    void paymentFailureRespectsTenantIsolation() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(
                TENANT, PaymentFailureCustomerTrigger.PAYMENT_FAILED, NotificationClass.TRANSACTIONAL_REQUIRED, null);
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(customerAlerts, Duration.ofHours(2));

        // The order belongs to TENANT; this event claims OTHER_TENANT. The
        // real production path for this is a data fault that cannot happen —
        // an event only ever names its own tenant's order — but the isolation
        // it proves is CustomerAlertFanoutService's own tenant predicate, the
        // same guard that stands between one tenant's fixture and another's.
        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(),
                OTHER_TENANT,
                brandId(OTHER_TENANT),
                locationId(OTHER_TENANT),
                orderId,
                UUID.randomUUID(),
                "DECLINED",
                NOW));

        assertThat(notifications.forSubject(TENANT, "Order", orderId)).isEmpty();
        assertThat(notifications.forSubject(OTHER_TENANT, "Order", orderId)).isEmpty();
    }

    // ------------------------------------------------------- payment refund

    @Test
    @DisplayName("a refund creates exactly one intent, addressed to the order's own customer")
    void paymentRefundCreatesExactlyOneIntentForTheRightRecipient() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(
                TENANT,
                PaymentRefundNotificationTrigger.PAYMENT_REFUNDED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                null);
        PaymentRefundNotificationTrigger trigger =
                new PaymentRefundNotificationTrigger(customerAlerts, Duration.ofDays(3));

        trigger.onPaymentRefunded(new PaymentRefunded(UUID.randomUUID(), new TenantId(TENANT), orderId, NOW));

        List<NotificationRow> rows = notifications.forSubject(TENANT, "Order", orderId).stream()
                .filter(row -> PaymentRefundNotificationTrigger.PAYMENT_REFUNDED.equals(row.templateKey()))
                .toList();
        assertThat(rows).hasSize(1);
        NotificationRow created = rows.get(0);
        assertThat(created.notificationClass()).isEqualTo(NotificationClass.TRANSACTIONAL_REQUIRED.name());

        boolean ready = eligibility.evaluate(claim(created.id()));
        assertThat(ready).isTrue();
        assertThat(notifications.find(TENANT, created.id()).orElseThrow().recipientAccountId())
                .isEqualTo(accountId);
    }

    @Test
    @DisplayName("a replayed refund for the same order creates no second intent")
    void aReplayedPaymentRefundCreatesNoSecondIntent() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(
                TENANT,
                PaymentRefundNotificationTrigger.PAYMENT_REFUNDED,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                null);
        PaymentRefundNotificationTrigger trigger =
                new PaymentRefundNotificationTrigger(customerAlerts, Duration.ofDays(3));

        trigger.onPaymentRefunded(new PaymentRefunded(UUID.randomUUID(), new TenantId(TENANT), orderId, NOW));
        // A second remedy against the same order (e.g. a delivery-fee
        // reimbursement following an earlier order refund) publishes the
        // identical shape of fact — see this trigger's own Javadoc for why
        // that still collapses to one message rather than two.
        trigger.onPaymentRefunded(new PaymentRefunded(UUID.randomUUID(), new TenantId(TENANT), orderId, NOW));

        assertThat(notifications.forSubject(TENANT, "Order", orderId)).hasSize(1);
    }

    // ----------------------------------------------------------- quiet hours

    @Test
    @DisplayName("an optional message inside quiet hours is held, and released the instant the window closes")
    void anOptionalMessageInsideQuietHoursIsHeldUntilTheWindowCloses() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(TENANT, "OPTIONAL_UPDATE", NotificationClass.TRANSACTIONAL_OPTIONAL, CONSENT_PURPOSE);
        consent.grant(TENANT, accountId, CONSENT_PURPOSE, "SMS");
        preferences.set(
                TENANT,
                accountId,
                null,
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                NotificationChannel.SMS,
                true,
                LocalTime.of(22, 0),
                LocalTime.of(8, 0),
                ZONE);

        // 23:00 in Asia/Tashkent (UTC+5): inside the 22:00-08:00 window.
        clock.set(Instant.parse("2026-08-22T18:00:00Z"));
        UUID notificationId = createOptionalIntent(orderId);

        NotificationRow claimed = claim(notificationId);
        boolean ready = eligibility.evaluate(claimed);

        assertThat(ready).isFalse();
        NotificationRow held = notifications.find(TENANT, notificationId).orElseThrow();
        assertThat(held.status()).isEqualTo("CREATED");
        // The window's own close: 08:00 the next Tashkent day, i.e. 03:00Z
        // the next UTC day. Asserted exactly, not just "later than now" —
        // the whole point of this mechanism is landing on the actual
        // boundary rather than a short repeating backoff.
        assertThat(held.nextAttemptAt()).isEqualTo(Instant.parse("2026-08-23T03:00:00Z"));

        // Advance the mutable clock across the window boundary — the
        // property under test is what happens at the close, not merely
        // "eventually". One second before: claimDue itself will not even
        // hand the row back, which is the exact boundary claim asserted
        // above translated into what the worker's own claim query sees.
        clock.set(Instant.parse("2026-08-23T02:59:59Z"));
        assertThat(tryClaim(notificationId)).isEmpty();

        // Exactly at the close: released.
        clock.set(Instant.parse("2026-08-23T03:00:00Z"));
        boolean readyAfterWindow = eligibility.evaluate(claim(notificationId));

        assertThat(readyAfterWindow).isTrue();
        assertThat(notifications.find(TENANT, notificationId).orElseThrow().status())
                .isEqualTo("READY");
    }

    @Test
    @DisplayName("an optional message outside quiet hours is not held")
    void anOptionalMessageOutsideQuietHoursIsNotHeld() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(TENANT, "OPTIONAL_UPDATE", NotificationClass.TRANSACTIONAL_OPTIONAL, CONSENT_PURPOSE);
        consent.grant(TENANT, accountId, CONSENT_PURPOSE, "SMS");
        preferences.set(
                TENANT,
                accountId,
                null,
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                NotificationChannel.SMS,
                true,
                LocalTime.of(22, 0),
                LocalTime.of(8, 0),
                ZONE);

        // NOW is noon Tashkent time: outside the 22:00-08:00 window.
        UUID notificationId = createOptionalIntent(orderId);

        boolean ready = eligibility.evaluate(claim(notificationId));

        assertThat(ready).isTrue();
        assertThat(notifications.find(TENANT, notificationId).orElseThrow().status())
                .isEqualTo("READY");
    }

    @Test
    @DisplayName("quiet hours never hold a transactional-required message, even inside the window")
    void quietHoursDoesNotHoldATransactionalRequiredMessage() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(
                TENANT, PaymentFailureCustomerTrigger.PAYMENT_FAILED, NotificationClass.TRANSACTIONAL_REQUIRED, null);
        // NotificationPreferenceService#set refuses to write a quiet-hours
        // window for a class that does not respectsPreference, so a real
        // customer can never configure this through the API — this writes
        // the row directly, under the store, to prove the exemption is
        // NotificationClass#respectsQuietHours answering false for
        // TRANSACTIONAL_REQUIRED, not merely the absence of a row nothing
        // can create. If that predicate ever started answering true, this
        // test — not just the API guard — would catch the regression.
        notifications.upsertPreferenceWindow(
                TENANT,
                accountId,
                null,
                NotificationClass.TRANSACTIONAL_REQUIRED.name(),
                NotificationChannel.SMS.name(),
                true,
                LocalTime.of(22, 0),
                LocalTime.of(8, 0),
                ZONE,
                NOW);

        // 23:00 Tashkent — squarely inside the window this row's own
        // quiet-hours columns describe.
        clock.set(Instant.parse("2026-08-22T18:00:00Z"));
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(customerAlerts, Duration.ofHours(2));
        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(),
                TENANT,
                brandId(TENANT),
                locationId(TENANT),
                orderId,
                UUID.randomUUID(),
                "DECLINED",
                clock.instant()));

        UUID notificationId = notifications.forSubject(TENANT, "Order", orderId).stream()
                .filter(row -> PaymentFailureCustomerTrigger.PAYMENT_FAILED.equals(row.templateKey()))
                .findFirst()
                .orElseThrow()
                .id();

        boolean ready = eligibility.evaluate(claim(notificationId));

        assertThat(ready).isTrue();
        assertThat(notifications.find(TENANT, notificationId).orElseThrow().status())
                .isEqualTo("READY");
    }

    @Test
    @DisplayName("an SMS wording waiting on its gateway is withheld, and sends once the gateway approves it")
    void aWordingAwaitingItsGatewayIsWithheldUntilApproved() {
        UUID accountId = seedCustomer(TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        activateTemplate(
                TENANT, PaymentFailureCustomerTrigger.PAYMENT_FAILED, NotificationClass.TRANSACTIONAL_REQUIRED, null);
        setProviderReview(TENANT, "PENDING");
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(customerAlerts, Duration.ofHours(2));

        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(),
                TENANT,
                brandId(TENANT),
                locationId(TENANT),
                orderId,
                UUID.randomUUID(),
                "DECLINED",
                clock.instant()));
        UUID withheld =
                notifications.forSubject(TENANT, "Order", orderId).getFirst().id();

        assertThat(eligibility.evaluate(claim(withheld))).isFalse();
        assertThat(notifications.find(TENANT, withheld).orElseThrow().suppressionReason())
                .isEqualTo("TEMPLATE_AWAITING_PROVIDER");

        setProviderReview(TENANT, "APPROVED");
        UUID secondOrder = seedOrder(TENANT, accountId);
        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(),
                TENANT,
                brandId(TENANT),
                locationId(TENANT),
                secondOrder,
                UUID.randomUUID(),
                "DECLINED",
                clock.instant()));
        UUID sent = notifications
                .forSubject(TENANT, "Order", secondOrder)
                .getFirst()
                .id();

        assertThat(eligibility.evaluate(claim(sent))).isTrue();
    }

    private void setProviderReview(UUID tenantId, String state) {
        jdbc.sql("""
                UPDATE notifications.template_versions
                   SET provider_review = :state, provider_review_updated_by = 'moderation-desk',
                       provider_review_updated_at = now()
                 WHERE tenant_id = :tenantId
                """).param("state", state).param("tenantId", tenantId).update();
    }

    @Test
    @DisplayName("a quiet-hours window set for one tenant's customer does not hold another tenant's message")
    void quietHoursIsTenantScoped() {
        UUID accountId = seedCustomer(TENANT);
        UUID otherAccountId = seedCustomer(OTHER_TENANT);
        UUID orderId = seedOrder(TENANT, accountId);
        UUID otherOrderId = seedOrder(OTHER_TENANT, otherAccountId);
        activateTemplate(TENANT, "OPTIONAL_UPDATE", NotificationClass.TRANSACTIONAL_OPTIONAL, CONSENT_PURPOSE);
        activateTemplate(OTHER_TENANT, "OPTIONAL_UPDATE", NotificationClass.TRANSACTIONAL_OPTIONAL, CONSENT_PURPOSE);
        consent.grant(TENANT, accountId, CONSENT_PURPOSE, "SMS");
        consent.grant(OTHER_TENANT, otherAccountId, CONSENT_PURPOSE, "SMS");
        // Only TENANT's customer has a quiet-hours window.
        preferences.set(
                TENANT,
                accountId,
                null,
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                NotificationChannel.SMS,
                true,
                LocalTime.of(22, 0),
                LocalTime.of(8, 0),
                ZONE);

        clock.set(Instant.parse("2026-08-22T18:00:00Z")); // 23:00 Tashkent
        UUID heldId = createOptionalIntent(orderId);
        UUID freeId = createOptionalIntent(otherOrderId, OTHER_TENANT);

        // Claimed together, in one claimDue call: both rows are due at this
        // same instant, and claimDue's batch claims every due row it sees —
        // a second, separate claimDue call for freeId alone would silently
        // re-claim heldId too and push its own lease out, which is not the
        // property this test is about.
        List<NotificationRow> claimed = claimAllDue();
        assertThat(eligibility.evaluate(rowNamed(claimed, heldId))).isFalse();
        assertThat(eligibility.evaluate(rowNamed(claimed, freeId))).isTrue();
    }

    // --------------------------------------------------------------- helpers

    private NotificationRow claim(UUID notificationId) {
        return rowNamed(claimAllDue(), notificationId);
    }

    private List<NotificationRow> claimAllDue() {
        return notifications.claimDue(
                clock.instant(), clock.instant().plus(Duration.ofMinutes(2)), 50, UUID.randomUUID());
    }

    /** Empty when the row was not among the batch a single {@link #claimAllDue} call claimed. */
    private Optional<NotificationRow> tryClaim(UUID notificationId) {
        return claimAllDue().stream()
                .filter(row -> row.id().equals(notificationId))
                .findFirst();
    }

    private NotificationRow rowNamed(List<NotificationRow> rows, UUID notificationId) {
        return rows.stream()
                .filter(row -> row.id().equals(notificationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Notification " + notificationId + " was not due to claim"));
    }

    private UUID createOptionalIntent(UUID orderId) {
        return createOptionalIntent(orderId, TENANT);
    }

    /**
     * The account is deliberately not a parameter here: {@code
     * recipient_account_id} is left null, the same shape every real trigger in
     * this genre creates a row in — eligibility resolves the recipient from
     * {@code orderId} itself via {@code OrderDirectory}, which {@code
     * seedOrder} already published it into.
     */
    private UUID createOptionalIntent(UUID orderId, UUID tenantId) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        boolean created = notifications.createIntent(new NewNotification(
                id,
                tenantId,
                brandId(tenantId),
                locationId(tenantId),
                NotificationClass.TRANSACTIONAL_OPTIONAL.name(),
                NotificationChannel.SMS.name(),
                "OPTIONAL_UPDATE",
                "Order",
                orderId,
                null,
                UUID.randomUUID(),
                "OPTIONAL_UPDATE:Order:" + id,
                "{}",
                now,
                now.plus(Duration.ofDays(1)),
                now));
        assertThat(created).isTrue();
        return id;
    }

    private void activateTemplate(
            UUID tenantId, String templateKey, NotificationClass notificationClass, @Nullable String consentPurpose) {
        UUID templateId = templates.createTemplate(
                tenantId, brandId(tenantId), templateKey, notificationClass, NotificationChannel.SMS, consentPurpose);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, "Body")));
        int versionNumber = templates.addVersion(tenantId, templateId, wordings, Map.of("reasonCode", "string"));
        // The migrated endpoints include the VAS gateway, which moderates
        // wordings (ADR 0091, V0208), so a new SMS version starts PENDING and
        // sends nothing until its approval is recorded -- as it must be in
        // production before this tenant's customers hear from it.
        setProviderReview(tenantId, "APPROVED");
        templates.activate(tenantId, templateId, versionNumber, "copy-approver");
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

    private UUID seedOrder(UUID tenantId, UUID accountId) {
        UUID orderId = UUID.randomUUID();
        orders.publish(new OrderDirectory.OrderSummary(
                orderId,
                tenantId,
                brandId(tenantId),
                locationId(tenantId),
                "A-1",
                accountId,
                null,
                "CONFIRMED",
                "UZS",
                1_000_000L,
                1));
        return orderId;
    }

    /**
     * A tenant, and its own brand and location — never {@code TENANT}'s and
     * {@code OTHER_TENANT}'s sharing one brand/location id, which {@code
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

    private static final class StubOrderDirectory implements OrderDirectory {

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
