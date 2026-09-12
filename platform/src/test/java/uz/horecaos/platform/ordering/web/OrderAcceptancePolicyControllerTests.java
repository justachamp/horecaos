package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.application.OrderAcceptancePolicyService;
import uz.horecaos.platform.ordering.domain.AcceptanceMode;
import uz.horecaos.platform.ordering.domain.ApprovalChannel;
import uz.horecaos.platform.ordering.domain.ApprovalTimeoutAction;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * {@link OrderAcceptancePolicyController} at every scope its own {@code
 * scopeOf} already builds — TENANT (no brandId/locationId), BRAND
 * (brandId only) and LOCATION (both) — closing the gap named in wave P46's
 * brief (gap map row {@code 10.3b}): the controller already accepted all
 * three, nothing on the Java side proved it, and only {@code
 * order-policy-api.ts} on the frontend narrowed every call to BRAND by
 * always sending a {@code brandId} and never a {@code locationId}.
 */
class OrderAcceptancePolicyControllerTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID BRAND_ID = UUID.randomUUID();
    private static final UUID LOCATION_ID = UUID.randomUUID();

    /** A hand-rolled double: real precedence is exhaustively tested where JdbcPolicyResolver lives. */
    private static final class FakeResolver implements PolicyResolver {
        @Nullable
        ResourceScope lastScope;

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            lastScope = scope;
            return Optional.of(new ResolvedPolicy<>(key.code(), UUID.randomUUID(), 1, scope.type(), "fake-hash", (P)
                    OrderAcceptancePolicy.platformDefault()));
        }

        @Override
        public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
            throw new UnsupportedOperationException("not exercised by this controller's own endpoints");
        }
    }

    /** A hand-rolled double: real authoring is exhaustively tested where JdbcPolicyAuthor lives. */
    private static final class FakeAuthor implements PolicyAuthor {
        @Nullable
        ResourceScope lastScope;

        @Nullable
        Object lastDocument;

        @Override
        public <P> ResolvedPolicy<P> author(
                PolicyKey<P> key, ResourceScope scope, P document, ActorRef authoredBy, String reason) {
            lastScope = scope;
            lastDocument = document;
            return new ResolvedPolicy<>(key.code(), UUID.randomUUID(), 2, scope.type(), "fake-hash", document);
        }
    }

    private static CurrentActor fakeActor() {
        return () -> new AuthenticatedActor("test-tenant-owner", Set.of(), Map.of());
    }

    private static OrderAcceptancePolicyController controller(PolicyResolver resolver, PolicyAuthor author) {
        return new OrderAcceptancePolicyController(new OrderAcceptancePolicyService(resolver, author), fakeActor());
    }

    @Test
    void readsAtTenantScopeWhenNeitherBrandNorLocationIsGiven() {
        FakeResolver resolver = new FakeResolver();
        controller(resolver, new FakeAuthor()).effective(TENANT_ID, null, null);

        assertThat(resolver.lastScope).isEqualTo(ResourceScope.tenant(TENANT_ID));
    }

    @Test
    void readsAtBrandScopeWhenOnlyBrandIsGiven() {
        FakeResolver resolver = new FakeResolver();
        controller(resolver, new FakeAuthor()).effective(TENANT_ID, BRAND_ID, null);

        assertThat(resolver.lastScope).isEqualTo(ResourceScope.brand(TENANT_ID, BRAND_ID));
    }

    @Test
    void readsAtLocationScopeWhenBothAreGiven() {
        FakeResolver resolver = new FakeResolver();
        controller(resolver, new FakeAuthor()).effective(TENANT_ID, BRAND_ID, LOCATION_ID);

        assertThat(resolver.lastScope).isEqualTo(ResourceScope.location(TENANT_ID, BRAND_ID, LOCATION_ID));
    }

    @Test
    void authorsAtTenantScopeWhenTheRequestNamesNeitherBrandNorLocation() {
        FakeAuthor author = new FakeAuthor();
        controller(new FakeResolver(), author).author(TENANT_ID, request(null, null));

        assertThat(author.lastScope).isEqualTo(ResourceScope.tenant(TENANT_ID));
        assertThat(author.lastDocument).isInstanceOf(OrderAcceptancePolicy.class);
    }

    @Test
    void authorsAtBrandScopeWhenTheRequestNamesOnlyABrand() {
        FakeAuthor author = new FakeAuthor();
        controller(new FakeResolver(), author).author(TENANT_ID, request(BRAND_ID, null));

        assertThat(author.lastScope).isEqualTo(ResourceScope.brand(TENANT_ID, BRAND_ID));
    }

    @Test
    void authorsAtLocationScopeWhenTheRequestNamesABranch() {
        FakeAuthor author = new FakeAuthor();
        controller(new FakeResolver(), author).author(TENANT_ID, request(BRAND_ID, LOCATION_ID));

        assertThat(author.lastScope).isEqualTo(ResourceScope.location(TENANT_ID, BRAND_ID, LOCATION_ID));
    }

    private static OrderAcceptancePolicyController.AuthorRequest request(
            @Nullable UUID brandId, @Nullable UUID locationId) {
        return new OrderAcceptancePolicyController.AuthorRequest(
                brandId,
                locationId,
                AcceptanceMode.RESTAURANT_APPROVAL,
                ApprovalChannel.HORECAOS_OPERATIONS,
                300,
                ApprovalTimeoutAction.AUTO_REJECT,
                true,
                true,
                "reachable-scope test");
    }
}
