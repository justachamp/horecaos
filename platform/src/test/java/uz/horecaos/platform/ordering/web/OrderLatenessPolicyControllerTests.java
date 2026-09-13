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

    /** Always answers the platform default, at whatever scope is asked. */
    private static final class FakeResolver implements PolicyResolver {
        @Nullable
        ResourceScope lastScope;

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            lastScope = scope;
            return Optional.of(new ResolvedPolicy<>(key.code(), UUID.randomUUID(), 1, scope.type(), "fake-hash", (P)
                    OrderLatenessPolicy.platformDefault()));
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
                new OrderLatenessPolicyService(resolver),
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
                new OrderLatenessPolicyService(resolver), orders, Clock.fixed(NOW, ZoneOffset.UTC));

        OrderLatenessPolicyController.OrderLatenessResponse response =
                controller.severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID);

        assertThat(response.level())
                .as("4 minutes before the promise is inside the 300s (5 min) at-risk window")
                .isEqualTo(OrderLatenessPolicy.LatenessLevel.AT_RISK.name());
    }

    @Test
    void aTerminalOrderResolvesNormalHoweverLateItsPromise() {
        FakeResolver resolver = new FakeResolver();
        OrderQueryService orders = mock(OrderQueryService.class);
        Instant promisedAt = NOW.minus(Duration.ofDays(3));
        when(orders.detail(TENANT_ID, ORDER_ID))
                .thenReturn(Optional.of(orderDetail(promisedAt, OrderStatus.COMPLETED)));

        OrderLatenessPolicyController controller = new OrderLatenessPolicyController(
                new OrderLatenessPolicyService(resolver), orders, Clock.fixed(NOW, ZoneOffset.UTC));

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
                new OrderLatenessPolicyService(resolver), orders, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> controller.severity(TENANT_ID, BRAND_ID, LOCATION_ID, ORDER_ID))
                .isInstanceOf(ApiException.class);
    }

    private static OrderQueryService.OrderDetail orderDetail(Instant promisedAt, OrderStatus status) {
        return orderDetail(promisedAt, status, LOCATION_ID);
    }

    private static OrderQueryService.OrderDetail orderDetail(Instant promisedAt, OrderStatus status, UUID locationId) {
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
                FulfillmentMode.DELIVERY,
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
