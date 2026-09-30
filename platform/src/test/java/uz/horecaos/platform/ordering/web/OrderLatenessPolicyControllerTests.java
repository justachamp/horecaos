package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument.ModeThresholds;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.PromiseBasis;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;

/**
 * {@link OrderLatenessPolicyController} against a hand-rolled resolver — real
 * ADR 0030 precedence is exhaustively tested where {@code JdbcPolicyResolver}
 * lives, matching {@code OrderAcceptancePolicyControllerTests}' own split.
 */
class OrderLatenessPolicyControllerTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID BRAND_ID = UUID.randomUUID();
    private static final UUID LOCATION_ID = UUID.randomUUID();
    private static final UUID OTHER_LOCATION_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    /** Answers one document -- the platform default unless a test sets another -- at whatever scope is asked. */
    private static final class FakeResolver implements PolicyResolver {
        @Nullable
        ResourceScope lastScope;

        OrderLatenessDocument document = OrderLatenessDocument.platformDefault();

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            lastScope = scope;
            return Optional.of(
                    new ResolvedPolicy<>(key.code(), UUID.randomUUID(), 1, scope.type(), "fake-hash", (P) document));
        }

        @Override
        public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
            throw new UnsupportedOperationException("not exercised by this controller's own endpoints");
        }
    }

    @Test
    void servesThePlatformDefaultAtLocationScope() {
        FakeResolver resolver = new FakeResolver();
        OrderLatenessPolicyController controller = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(resolver, new uz.horecaos.platform.support.FakeConfigurationResolver()),
                mock(OrderQueryService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        OrderLatenessPolicyController.LatenessPolicyResponse response =
                controller.policy(TENANT_ID, BRAND_ID, LOCATION_ID);

        assertThat(response.isPlatformDefault()).isFalse(); // FakeResolver always "finds" a row
        assertThat(response.delivery().atRiskBeforeSeconds()).isEqualTo(300);
        assertThat(response.pickup().noPromiseFallbackSeconds()).isEqualTo(2700);
        assertThat(resolver.lastScope).isEqualTo(ResourceScope.location(TENANT_ID, BRAND_ID, LOCATION_ID));
    }

    @Test
    void resolvesAnOrdersSeverityFromItsRealPromiseAndTheResolvedPolicy() {
        FakeResolver resolver = new FakeResolver();
        OrderQueryService orders = mock(OrderQueryService.class);
        // 300s at-risk window, no grace, five days from the platform default —
        // NOW is 4 minutes before a promise placed 25 minutes before NOW.
        Instant promisedAt = NOW.plus(Duration.ofMinutes(4));
        when(orders.detail(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.of(orderDetail(promisedAt, OrderStatus.PREPARING)));

        OrderLatenessPolicyController controller = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(resolver, new uz.horecaos.platform.support.FakeConfigurationResolver()),
                orders,
                Clock.fixed(NOW, ZoneOffset.UTC));

        OrderLatenessPolicyController.OrderLatenessResponse response =
                controller.severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID);

        assertThat(response.level())
                .as("4 minutes before the promise is inside the 300s (5 min) at-risk window")
                .isEqualTo(OrderLatenessPolicy.LatenessLevel.AT_RISK.name());
    }

    /**
     * Row {@code X.39}: the server-side severity answer and the boards read one
     * document. A tenant's at-risk minutes reach {@code GET .../{orderId}/lateness}
     * exactly as they reach {@code GET .../lateness-policy}, so a caller that is not
     * a per-render UI cannot disagree with the boards about the same order.
     */
    @Test
    void aTenantsAtRiskMinutesMoveTheServerSideSeverityToo() {
        FakeResolver resolver = new FakeResolver();
        OrderQueryService orders = mock(OrderQueryService.class);
        // 30 minutes before the promise: NORMAL under the platform's 5-minute window.
        Instant promisedAt = NOW.plus(Duration.ofMinutes(30));
        when(orders.detail(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.of(orderDetail(promisedAt, OrderStatus.PREPARING)));

        OrderLatenessPolicyController withDefaults = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(resolver, new uz.horecaos.platform.support.FakeConfigurationResolver()),
                orders,
                Clock.fixed(NOW, ZoneOffset.UTC));
        OrderLatenessPolicyController withTenantWindow = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(
                        resolver,
                        new uz.horecaos.platform.support.FakeConfigurationResolver(
                                java.util.Map.of("ordering.at_risk_before_minutes", 45))),
                orders,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(withDefaults
                        .severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID)
                        .level())
                .isEqualTo(OrderLatenessPolicy.LatenessLevel.NORMAL.name());
        assertThat(withTenantWindow
                        .severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID)
                        .level())
                .as("the tenant asked to be warned 45 minutes ahead, and 30 minutes ahead is inside that")
                .isEqualTo(OrderLatenessPolicy.LatenessLevel.AT_RISK.name());
        assertThat(withTenantWindow
                        .policy(TENANT_ID, BRAND_ID, LOCATION_ID)
                        .pickup()
                        .atRiskBeforeSeconds())
                .isEqualTo(45 * 60);
    }

    /**
     * Wave 16 (X.39 / 10.3b): the at-risk window is per fulfilment mode, and the server-side severity
     * answer reads the mode of the order it is asked about -- the same order, promised 8 minutes out,
     * is at risk as a delivery (10-minute window) and normal as a pickup (2-minute window).
     */
    @Test
    void aModesOwnAtRiskWindowDecidesTheServerSideSeverityOfAnOrderOfThatMode() {
        FakeResolver resolver = new FakeResolver();
        resolver.document = new OrderLatenessDocument(
                new ModeThresholds(600, 0, 2700), new ModeThresholds(120, 0, 2700), new ModeThresholds(null, 0, 2700));
        Instant promisedAt = NOW.plus(Duration.ofMinutes(8));

        OrderQueryService delivery = mock(OrderQueryService.class);
        when(delivery.detail(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.of(
                        orderDetail(promisedAt, OrderStatus.PREPARING, LOCATION_ID, FulfillmentMode.DELIVERY)));
        OrderQueryService pickup = mock(OrderQueryService.class);
        when(pickup.detail(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.of(
                        orderDetail(promisedAt, OrderStatus.PREPARING, LOCATION_ID, FulfillmentMode.PICKUP)));
        OrderQueryService dineIn = mock(OrderQueryService.class);
        when(dineIn.detail(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.of(
                        orderDetail(promisedAt, OrderStatus.PREPARING, LOCATION_ID, FulfillmentMode.DINE_IN)));
        OrderLatenessPolicyService service =
                new OrderLatenessPolicyService(resolver, new uz.horecaos.platform.support.FakeConfigurationResolver());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThat(new OrderLatenessPolicyController(service, delivery, clock)
                        .severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID)
                        .level())
                .as("8 minutes out is inside delivery's own 10-minute window")
                .isEqualTo(OrderLatenessPolicy.LatenessLevel.AT_RISK.name());
        assertThat(new OrderLatenessPolicyController(service, pickup, clock)
                        .severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID)
                        .level())
                .as("and outside pickup's own 2-minute window")
                .isEqualTo(OrderLatenessPolicy.LatenessLevel.NORMAL.name());
        assertThat(new OrderLatenessPolicyController(service, dineIn, clock)
                        .severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID)
                        .level())
                .as("dine-in sets none, so it takes the default 5 minutes, and 8 minutes out is outside that")
                .isEqualTo(OrderLatenessPolicy.LatenessLevel.NORMAL.name());

        LatenessPolicyResponseView served = LatenessPolicyResponseView.of(
                new OrderLatenessPolicyController(service, delivery, clock).policy(TENANT_ID, BRAND_ID, LOCATION_ID));
        assertThat(served.delivery()).isEqualTo(600);
        assertThat(served.pickup()).isEqualTo(120);
        assertThat(served.dineIn()).isEqualTo(300);
    }

    private record LatenessPolicyResponseView(int delivery, int pickup, int dineIn) {
        static LatenessPolicyResponseView of(OrderLatenessPolicyController.LatenessPolicyResponse response) {
            return new LatenessPolicyResponseView(
                    response.delivery().atRiskBeforeSeconds(),
                    response.pickup().atRiskBeforeSeconds(),
                    response.dineIn().atRiskBeforeSeconds());
        }
    }

    @Test
    void theLateColourRidesOnTheServedPolicyOnlyWhenItIsAValidHex() {
        OrderLatenessPolicyController controller = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(
                        new FakeResolver(),
                        new uz.horecaos.platform.support.FakeConfigurationResolver(
                                java.util.Map.of("ordering.late_colour", "#8A3FFC"))),
                mock(OrderQueryService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        OrderLatenessPolicyController injected = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(
                        new FakeResolver(),
                        new uz.horecaos.platform.support.FakeConfigurationResolver(
                                java.util.Map.of("ordering.late_colour", "red; background:url(x)"))),
                mock(OrderQueryService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(controller.policy(TENANT_ID, BRAND_ID, LOCATION_ID).lateColour())
                .isEqualTo("#8a3ffc");
        assertThat(injected.policy(TENANT_ID, BRAND_ID, LOCATION_ID).lateColour())
                .as("nothing but exactly #rrggbb is served to a style binding")
                .isNull();
    }

    @Test
    void aTerminalOrderResolvesNormalHoweverLateItsPromise() {
        FakeResolver resolver = new FakeResolver();
        OrderQueryService orders = mock(OrderQueryService.class);
        Instant promisedAt = NOW.minus(Duration.ofDays(3));
        when(orders.detail(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.of(orderDetail(promisedAt, OrderStatus.COMPLETED)));

        OrderLatenessPolicyController controller = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(resolver, new uz.horecaos.platform.support.FakeConfigurationResolver()),
                orders,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(controller
                        .severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID)
                        .level())
                .isEqualTo(OrderLatenessPolicy.LatenessLevel.NORMAL.name());
    }

    @Test
    void refusesAnOrderThatBelongsToAnotherLocation() {
        FakeResolver resolver = new FakeResolver();
        OrderQueryService orders = mock(OrderQueryService.class);
        when(orders.detail(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.of(orderDetail(NOW.plusSeconds(600), OrderStatus.PREPARING, OTHER_LOCATION_ID)));

        OrderLatenessPolicyController controller = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(resolver, new uz.horecaos.platform.support.FakeConfigurationResolver()),
                orders,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> controller.severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID))
                .isInstanceOf(ApiException.class);
    }

    private static OrderQueryService.OrderDetail orderDetail(Instant promisedAt, OrderStatus status) {
        return orderDetail(promisedAt, status, LOCATION_ID);
    }

    private static OrderQueryService.OrderDetail orderDetail(Instant promisedAt, OrderStatus status, UUID locationId) {
        return orderDetail(promisedAt, status, locationId, FulfillmentMode.DELIVERY);
    }

    private static OrderQueryService.OrderDetail orderDetail(
            Instant promisedAt, OrderStatus status, UUID locationId, FulfillmentMode mode) {
        OrderPromise promise = new OrderPromise(promisedAt, PromiseBasis.PREPARATION_BAND, 25, null);
        OrderRow row = new OrderRow(
                ORDER_ID,
                "0001",
                TENANT_ID,
                BRAND_ID,
                locationId,
                UUID.randomUUID(),
                "DIRECT",
                UUID.randomUUID(),
                "guest-hash",
                mode,
                "AUTO_CONFIRM",
                null,
                0,
                "NONE",
                null,
                null,
                status,
                "NONE",
                "NONE",
                "UZS",
                10_000L,
                1_200L,
                0L,
                0L,
                11_200L,
                UUID.randomUUID(),
                "hash",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "idem-key",
                promise,
                1,
                promisedAt.minus(Duration.ofMinutes(25)),
                null,
                null,
                1,
                "CUSTOMER",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                0L,
                "");
        return new OrderQueryService.OrderDetail(
                row,
                List.of(),
                List.of(),
                new OrderQueryService.CustomerDetail(null, null, false, false, false, null, false));
    }
}
