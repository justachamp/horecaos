package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.NotificationContext;
import uz.horecaos.platform.reviews.ReviewsWiring.FakeBranches;
import uz.horecaos.platform.reviews.ReviewsWiring.InMemoryPolicies;
import uz.horecaos.platform.reviews.api.ReviewsConfigurationKeys;
import uz.horecaos.platform.reviews.application.FeedbackPromptPolicyService;
import uz.horecaos.platform.reviews.application.ReviewsSettings;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument.PromptRow;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument.SendWindow;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * When the rating prompt goes out (ADR 0159, Decision 4), through the one class that turns an order fact
 * into a notification intent (ADR 0020).
 *
 * <p>The real {@code FeedbackPromptPolicyService} answers; the intent the store receives is the evidence.
 * Four properties, each stated in the record: with the flag off the intent is byte for byte what it always
 * was; with it on the prompt is timed per channel inside the branch's window in the branch's own zone; a
 * channel the policy turns off creates no customer intent; and no other template is touched.
 *
 * <p>Tashkent is UTC+5 all year. Instants are written in UTC with the local time beside them.
 */
class OrderNotificationTriggerPromptTimingTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-f000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-f000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-f000-7000-8000-0000000000c1");
    private static final UUID ORDER = UUID.fromString("018f9f10-f000-7000-8000-0000000000d1");
    private static final UUID CUSTOMER = UUID.fromString("018f9f10-f000-7000-8000-0000000000e1");

    /** 14:00 in Tashkent. */
    private static final Instant COMPLETED = Instant.parse("2026-10-08T09:00:00Z");

    private static final Duration EXPIRY = Duration.ofHours(6);

    private JdbcNotificationStore store;
    private OperationsAlertFanoutService alerts;
    private OrderDirectory orders;
    private CustomerTelegramChannelRouter router;
    private InMemoryPolicies policies;
    private Map<String, Object> configuration;
    private FeedbackPromptPolicyService prompts;
    private List<NewNotification> created;
    private NotificationChannel routedTo;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = COMPLETED;
        created = new ArrayList<>();
        routedTo = NotificationChannel.TELEGRAM;
        store = mock(JdbcNotificationStore.class);
        when(store.createIntent(any())).thenAnswer(call -> created.add(call.getArgument(0)));
        alerts = mock(OperationsAlertFanoutService.class);
        orders = mock(OrderDirectory.class);
        when(orders.summary(any(), any()))
                .thenReturn(Optional.of(new OrderDirectory.OrderSummary(
                        ORDER, TENANT, BRAND, LOCATION, "A-17", CUSTOMER, null, "COMPLETED", "UZS", 12_500_000L, 3)));
        when(orders.notificationContext(any(), any()))
                .thenReturn(Optional.of(new NotificationContext(
                        uz.horecaos.platform.tenancy.api.FulfillmentMode.PICKUP,
                        uz.horecaos.platform.tenancy.api.SalesChannelSystemType.WEB)));
        router = mock(CustomerTelegramChannelRouter.class);
        when(router.resolve(any(), any(), any(), any(NotificationClass.class), any()))
                .thenAnswer(call -> routedTo);

        policies = new InMemoryPolicies();
        configuration = new java.util.LinkedHashMap<>();
        prompts = rebuilt();
    }

    // ----------------------------------------------------------------- flag off

    @Test
    @DisplayName(
            "with the feature off, the prompt is the intent it always was: sendable now, given up after the order expiry")
    void withTheFlagOffNothingChanges() {
        // A policy exists and says never to send; with the flag off nobody reads it.
        author(new FeedbackPromptDocument(null, 7, List.of(new PromptRow("*", "*", false, 0, 1))));

        trigger().onOrderingEvent(completed());

        assertThat(created).singleElement().satisfies(intent -> {
            assertThat(intent.scheduledAt()).isEqualTo(now);
            assertThat(intent.expiresAt()).isEqualTo(now.plus(EXPIRY));
            assertThat(intent.templateKey()).isEqualTo(OrderNotificationTrigger.ORDER_COMPLETED);
        });
    }

    @Test
    @DisplayName("the intent with the feature off is identical to the one a trigger built without a policy creates")
    void identicalToTheTriggerThatPredatesThePolicy() {
        trigger().onOrderingEvent(completed());
        NewNotification governedOff = created.getFirst();
        created.clear();

        legacyTrigger().onOrderingEvent(completed());
        NewNotification legacy = created.getFirst();

        assertThat(governedOff)
                .usingRecursiveComparison()
                .ignoringFields("notificationId", "triggerEventId")
                .isEqualTo(legacy);
    }

    // ------------------------------------------------------------------ flag on

    @Test
    @DisplayName(
            "with the feature on, Telegram goes out 30 minutes after completion inside the window, given up after 6 hours")
    void telegramThirtyMinutesAfterInsideTheWindow() {
        // Nothing authored: the built-in default (Telegram on, 30 minutes, 6 hours, 09:00-21:00).
        configuration.put("feature.feedback_settings", true);
        prompts = rebuilt();

        trigger().onOrderingEvent(completed());

        assertThat(created).singleElement().satisfies(intent -> {
            assertThat(intent.channel()).isEqualTo("TELEGRAM");
            assertThat(intent.scheduledAt())
                    .as("14:00 + 30 minutes = 14:30 Tashkent")
                    .isEqualTo(Instant.parse("2026-10-08T09:30:00Z"));
            assertThat(intent.expiresAt())
                    .as("given up 6 hours after it became sendable")
                    .isEqualTo(Instant.parse("2026-10-08T15:30:00Z"));
            assertThat(intent.notificationClass()).isEqualTo("TRANSACTIONAL_REQUIRED");
        });
    }

    @Test
    @DisplayName("a prompt that falls after the window waits for the next opening, in the branch's own time zone")
    void afterTheWindowWaitsForTheNextOpening() {
        configuration.put("feature.feedback_settings", true);
        prompts = rebuilt();
        // 21:45 Tashkent: the 30 minutes lands at 22:15, outside 09:00-21:00.
        now = Instant.parse("2026-10-08T16:45:00Z");

        trigger().onOrderingEvent(completedAt(now));

        assertThat(created).singleElement().satisfies(intent -> {
            assertThat(intent.scheduledAt())
                    .as("09:00 the next morning in Tashkent, which is 04:00 UTC")
                    .isEqualTo(Instant.parse("2026-10-09T04:00:00Z"));
            assertThat(intent.expiresAt()).isEqualTo(Instant.parse("2026-10-09T10:00:00Z"));
        });
    }

    @Test
    @DisplayName(
            "SMS is off in the built-in default: no customer intent is created, and the operations fan-out still runs")
    void smsIsOffByDefault() {
        configuration.put("feature.feedback_settings", true);
        prompts = rebuilt();
        routedTo = NotificationChannel.SMS;

        trigger().onOrderingEvent(completed());

        assertThat(created).as("asking for a review is not a receipt").isEmpty();
        verify(alerts)
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("a tenant that enables SMS for delivery orders only gets it for delivery orders, with its own delay")
    void theOrderTypeOutranksTheChannel() {
        configuration.put("feature.feedback_settings", true);
        author(new FeedbackPromptDocument(
                new SendWindow("08:00", "22:00"),
                7,
                List.of(new PromptRow("DELIVERY", "SMS", true, 120, 12), new PromptRow("*", "SMS", false, 30, 6))));
        routedTo = NotificationChannel.SMS;

        when(orders.notificationContext(any(), any()))
                .thenReturn(Optional.of(new NotificationContext(
                        uz.horecaos.platform.tenancy.api.FulfillmentMode.DELIVERY,
                        uz.horecaos.platform.tenancy.api.SalesChannelSystemType.WEB)));
        trigger().onOrderingEvent(completed());
        assertThat(created).singleElement().satisfies(intent -> {
            assertThat(intent.scheduledAt()).isEqualTo(Instant.parse("2026-10-08T11:00:00Z"));
            assertThat(intent.expiresAt()).isEqualTo(Instant.parse("2026-10-08T23:00:00Z"));
        });

        created.clear();
        when(orders.notificationContext(any(), any()))
                .thenReturn(Optional.of(new NotificationContext(
                        uz.horecaos.platform.tenancy.api.FulfillmentMode.PICKUP,
                        uz.horecaos.platform.tenancy.api.SalesChannelSystemType.WEB)));
        trigger().onOrderingEvent(completed());
        assertThat(created).as("pickup takes the wildcard row, which is off").isEmpty();
    }

    // ---------------------------------------------------- nothing else is timed

    @Test
    @DisplayName("no other template is timed by the policy: a cancellation is still sendable now with the order expiry")
    void otherTemplatesAreUntouched() {
        configuration.put("feature.feedback_settings", true);
        author(new FeedbackPromptDocument(null, 7, List.of(new PromptRow("*", "*", false, 0, 1))));

        trigger().onOrderingEvent(cancelled());

        assertThat(created).singleElement().satisfies(intent -> {
            assertThat(intent.templateKey()).isEqualTo(OrderNotificationTrigger.ORDER_CANCELLED);
            assertThat(intent.scheduledAt()).isEqualTo(now);
            assertThat(intent.expiresAt()).isEqualTo(now.plus(EXPIRY));
        });
    }

    @Test
    @DisplayName("the idempotency key is still one per order, template and channel, so a replay changes nothing")
    void theIdempotencyKeyIsStillTheOrders() {
        configuration.put("feature.feedback_settings", true);
        prompts = rebuilt();
        trigger().onOrderingEvent(completed());
        trigger().onOrderingEvent(completed());

        assertThat(created).hasSize(2);
        assertThat(created.get(0).idempotencyKey())
                .as("keyed on the subject and the channel, never on the event id or the schedule")
                .isEqualTo(created.get(1).idempotencyKey())
                .isEqualTo("ORDER_COMPLETED:Order:" + ORDER + ":TELEGRAM");
    }

    // ===================================================================== fixtures

    private FeedbackPromptPolicyService rebuilt() {
        FakeBranches branches = new FakeBranches();
        branches.register(LOCATION, "Chilonzor", "Asia/Tashkent");
        return new FeedbackPromptPolicyService(
                policies,
                policies,
                branches,
                new ReviewsSettings(new FakeConfigurationResolver(Map.copyOf(configuration))),
                AuditTrail.discarding(),
                Clock.fixed(COMPLETED, ZoneOffset.UTC));
    }

    private void author(FeedbackPromptDocument document) {
        policies.publish(ReviewsConfigurationKeys.FEEDBACK_PROMPT_POLICY, ResourceScope.tenant(TENANT), document);
        prompts = rebuilt();
    }

    private OrderNotificationTrigger trigger() {
        return new OrderNotificationTrigger(
                store,
                alerts,
                orders,
                router,
                JsonMapper.builder().build(),
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
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
                },
                "SMS",
                EXPIRY,
                prompts);
    }

    private OrderNotificationTrigger legacyTrigger() {
        return new OrderNotificationTrigger(
                store,
                alerts,
                orders,
                router,
                JsonMapper.builder().build(),
                Clock.fixed(now, ZoneOffset.UTC),
                "SMS",
                EXPIRY);
    }

    private OrderCompleted completed() {
        return completedAt(COMPLETED);
    }

    private OrderCompleted completedAt(Instant at) {
        return new OrderCompleted(
                UUID.randomUUID(), new TenantId(TENANT), ORDER, at, BRAND, LOCATION, at, "UZS", 12_500_000L, 4);
    }

    private OrderCancelled cancelled() {
        return new OrderCancelled(
                UUID.randomUUID(),
                new TenantId(TENANT),
                ORDER,
                now,
                BRAND,
                LOCATION,
                "CUSTOMER",
                null,
                "RECEIVED",
                "CANCELLED",
                3,
                null,
                null,
                null);
    }
}
