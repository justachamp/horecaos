package uz.horecaos.platform.telemetry.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.telemetry.infrastructure.realtime.SseStreamRegistry;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * {@code OperationsStreamController.open}'s own capability check, per channel
 * (ADR 0045, wave P08).
 *
 * <p>{@link uz.horecaos.platform.web.authorization.RequiresCapability} on the
 * endpoint method is enforced by an interceptor this direct-call test never
 * goes through — deliberately, since that annotation only guards {@code
 * location.read}, "the right to be looking at this branch at all", and what
 * this suite is about is the loop inside {@code authorize()} that checks each
 * requested channel's own capability separately. That loop already existed
 * before wave P08; what P08 added is the first real caller (COUNTERS,
 * DISPATCH_BOARD) worth proving it against, and no test asserted the refusal
 * at all.
 */
class OperationsStreamControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");

    private final FakeAuthorization authorization = new FakeAuthorization();
    private final SseStreamRegistry registry = new SseStreamRegistry(
            new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(), List.of());
    private OperationsStreamController controller;

    @BeforeEach
    void setUp() {
        controller = new OperationsStreamController(
                registry,
                authorization,
                () -> new AuthenticatedActor("dispatcher-1", Set.of(), Map.of()),
                allowEveryConnect(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a subscriber holding the channel's capability is let through")
    void aSubscriberWithTheChannelsCapabilitySucceeds() {
        authorization.grant(Capability.ORDER_READ);

        SseEmitter emitter = controller.open(TENANT, BRAND, LOCATION, null, List.of("order_queue"), null, null);

        assertThat(emitter).isNotNull();
        assertThat(registry.openStreams()).isOne();
    }

    @Test
    @DisplayName("a subscriber with no grant at all is refused, and nothing is opened")
    void aSubscriberWithoutTheChannelsCapabilityIsRefused() {
        assertThatThrownBy(() -> controller.open(TENANT, BRAND, LOCATION, null, List.of("order_queue"), null, null))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);

        assertThat(registry.openStreams())
                .as("a refused subscription must never reach the registry, half-open or otherwise")
                .isZero();
    }

    @Test
    @DisplayName("the capability is re-checked per channel, not once for the whole subscription")
    void capabilityIsCheckedPerChannelNotOnceForTheWholeSubscription() {
        // Holds ORDER_READ (enough for order_queue) but not DELIVERY_PLAN_READ
        // (dispatch_board's own capability, per StreamChannel).
        authorization.grant(Capability.ORDER_READ);

        assertThatThrownBy(() -> controller.open(
                        TENANT, BRAND, LOCATION, null, List.of("order_queue", "dispatch_board"), null, null))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class)
                .satisfies(failure -> assertThat(((AuthorizationService.AccessDeniedException) failure).capability())
                        .as("the channel that was actually missing its capability, not just any refusal")
                        .isEqualTo(Capability.DELIVERY_PLAN_READ));

        assertThat(registry.openStreams())
                .as("one refused channel refuses the whole connection; a granted channel earlier in "
                        + "the list must not have opened a stream this request never completes")
                .isZero();
    }

    @Test
    @DisplayName("COUNTERS is refused the same way when its ORDER_READ grant is absent")
    void countersIsRefusedLikeAnyOtherChannel() {
        assertThatThrownBy(() -> controller.open(TENANT, BRAND, LOCATION, null, List.of("counters"), null, null))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class)
                .satisfies(failure -> assertThat(((AuthorizationService.AccessDeniedException) failure).capability())
                        .isEqualTo(Capability.ORDER_READ));
    }

    @Test
    @DisplayName("KITCHEN_BOARD is refused the same way when KITCHEN_TICKET_READ is absent")
    void kitchenBoardIsRefusedLikeAnyOtherChannel() {
        assertThatThrownBy(() -> controller.open(TENANT, BRAND, LOCATION, null, List.of("kitchen_board"), null, null))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class)
                .satisfies(failure -> assertThat(((AuthorizationService.AccessDeniedException) failure).capability())
                        .isEqualTo(Capability.KITCHEN_TICKET_READ));

        assertThat(registry.openStreams())
                .as("a refused subscription must never reach the registry")
                .isZero();
    }

    @Test
    @DisplayName("a KDS or a VDU wall display holding KITCHEN_TICKET_READ connects")
    void kitchenBoardSucceedsWithTheKitchenCapability() {
        authorization.grant(Capability.KITCHEN_TICKET_READ);

        SseEmitter emitter = controller.open(TENANT, BRAND, LOCATION, null, List.of("kitchen_board"), null, null);

        assertThat(emitter).isNotNull();
        assertThat(registry.openStreams()).isOne();
    }

    // ------------------------------------------------- the brand-wide queue (gap map row 1.1)

    @Test
    @DisplayName("the brand-wide queue is authorized at the brand, not at the branch the connection was opened at")
    void theBrandQueueIsAuthorizedAtTheBrand() {
        ScopedAuthorization scoped = new ScopedAuthorization(ResourceScope.ScopeType.BRAND);
        OperationsStreamController brandController = controllerWith(scoped);

        SseEmitter emitter =
                brandController.open(TENANT, BRAND, LOCATION, "BRAND:" + BRAND, List.of("order_queue"), null, null);

        assertThat(emitter).isNotNull();
        assertThat(registry.openStreams()).isOne();
        assertThat(scoped.checked)
                .as("order.read was asked for at BRAND scope, naming this brand")
                .containsExactly(Capability.ORDER_READ + "@" + ResourceScope.brand(TENANT, BRAND));
    }

    @Test
    @DisplayName("a principal who reads only this branch is refused the brand-wide queue, and nothing is opened")
    void aBranchGrantDoesNotBuyTheBrandQueue() {
        // Holds order.read at LOCATION and nowhere wider: the branch board's grant.
        ScopedAuthorization scoped = new ScopedAuthorization(ResourceScope.ScopeType.LOCATION);
        OperationsStreamController branchOnly = controllerWith(scoped);

        assertThatThrownBy(() ->
                        branchOnly.open(TENANT, BRAND, LOCATION, "BRAND:" + BRAND, List.of("order_queue"), null, null))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
        assertThat(registry.openStreams()).isZero();

        // The same principal still gets the branch board.
        assertThat(branchOnly.open(TENANT, BRAND, LOCATION, null, List.of("order_queue"), null, null))
                .isNotNull();
        assertThat(registry.openStreams()).isOne();
    }

    @Test
    @DisplayName(
            "a brand key naming a brand the connection was not opened at is refused before any capability is asked")
    void aForeignBrandKeyIsRefusedBeforeAuthorization() {
        ScopedAuthorization scoped = new ScopedAuthorization(ResourceScope.ScopeType.BRAND);
        OperationsStreamController brandController = controllerWith(scoped);
        UUID otherBrand = UUID.randomUUID();

        assertThatThrownBy(() -> brandController.open(
                        TENANT, BRAND, LOCATION, "BRAND:" + otherBrand, List.of("order_queue"), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(scoped.checked).isEmpty();
        assertThat(registry.openStreams()).isZero();
    }

    private OperationsStreamController controllerWith(AuthorizationService authorization) {
        return new OperationsStreamController(
                registry,
                authorization,
                () -> new AuthenticatedActor("brand-board-1", Set.of(), Map.of()),
                allowEveryConnect(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RateLimiter allowEveryConnect() {
        return (key, policy) -> RateLimiter.Decision.allowed(policy.permits());
    }

    /** Grants exactly the capabilities added, at any scope — scope is not what this suite is about. */
    private static final class FakeAuthorization implements AuthorizationService {

        private final Set<Capability> granted = new HashSet<>();

        void grant(Capability capability) {
            granted.add(capability);
        }

        @Override
        public boolean has(String subject, Capability capability, ResourceScope scope) {
            return granted.contains(capability);
        }

        @Override
        public void require(String subject, Capability capability, ResourceScope scope) {
            if (!granted.contains(capability)) {
                throw new AccessDeniedException(capability, scope);
            }
        }

        @Override
        public CapabilityView viewFor(String subject, UUID tenantId) {
            throw new UnsupportedOperationException("Not exercised by this suite");
        }
    }

    /**
     * Holds {@code order.read} at exactly one scope type, and answers a request at any other
     * with the refusal a real grant would give: a grant at a narrower scope never covers a
     * request for a wider one. Records what it was asked, so a test can say at which scope.
     */
    private static final class ScopedAuthorization implements AuthorizationService {

        private final ResourceScope.ScopeType heldAt;
        final List<String> checked = new java.util.ArrayList<>();

        ScopedAuthorization(ResourceScope.ScopeType heldAt) {
            this.heldAt = heldAt;
        }

        @Override
        public boolean has(String subject, Capability capability, ResourceScope scope) {
            return capability == Capability.ORDER_READ && covers(scope);
        }

        @Override
        public void require(String subject, Capability capability, ResourceScope scope) {
            checked.add(capability + "@" + scope);
            if (!has(subject, capability, scope)) {
                throw new AccessDeniedException(capability, scope);
            }
        }

        private boolean covers(ResourceScope scope) {
            // BRAND covers a brand and everything under it; LOCATION covers a location only.
            return switch (heldAt) {
                case BRAND ->
                    scope.type() == ResourceScope.ScopeType.BRAND || scope.type() == ResourceScope.ScopeType.LOCATION;
                case LOCATION -> scope.type() == ResourceScope.ScopeType.LOCATION;
                default -> false;
            };
        }

        @Override
        public CapabilityView viewFor(String subject, UUID tenantId) {
            throw new UnsupportedOperationException("Not exercised by this suite");
        }
    }
}
