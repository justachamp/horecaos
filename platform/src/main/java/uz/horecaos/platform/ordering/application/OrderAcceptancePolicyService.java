package uz.horecaos.platform.ordering.application;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Resolves the acceptance policy for an order (ADR 0002, ADR 0030).
 *
 * <p>Precedence is not implemented here. It comes from the shared mechanism, so
 * order acceptance resolves by exactly the same rule as every other scoped
 * behavior in the platform. This service adds only the platform default and the
 * pinning helper.
 */
@Service
public class OrderAcceptancePolicyService {

    /**
     * Settable at every level: a chain restaurant sets one policy for the tenant
     * and a single busy location overrides it.
     */
    public static final PolicyKey<OrderAcceptancePolicy> ACCEPTANCE = new PolicyKey<>(
            "ordering.acceptance",
            OrderAcceptancePolicy.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "ordering",
            false,
            "How an order is accepted: automatically, or by restaurant approval.");

    private final PolicyResolver policies;

    public OrderAcceptancePolicyService(PolicyResolver policies) {
        this.policies = policies;
    }

    /**
     * The policy in force for a location right now, together with the identity
     * an order must persist so the decision stays explainable.
     */
    public Effective resolve(UUID tenantId, UUID brandId, UUID locationId) {
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);

        return policies.resolve(ACCEPTANCE, scope)
                .map(resolved -> new Effective(
                        resolved.document(), resolved.policyId(), resolved.policyVersion()))
                .orElseGet(() -> new Effective(OrderAcceptancePolicy.platformDefault(), null, 0));
    }

    /**
     * Re-resolves the exact policy an order was accepted under, so a later
     * policy change cannot alter what that order was permitted to do.
     */
    public OrderAcceptancePolicy pinned(UUID policyId, int policyVersion) {
        if (policyId == null) {
            return OrderAcceptancePolicy.platformDefault();
        }
        return policies.pinned(ACCEPTANCE, policyId, policyVersion)
                .map(ResolvedPolicy::document)
                .orElseThrow(() -> new IllegalStateException(
                        "Order references policy %s v%d, which no longer exists"
                                .formatted(policyId, policyVersion)));
    }

    /**
     * A resolved policy and the identity to snapshot onto the order.
     *
     * @param policyId null when the platform default applied, which is itself a
     *                 fact worth recording rather than inventing an identifier for
     */
    public record Effective(OrderAcceptancePolicy policy, UUID policyId, int policyVersion) {

        public boolean isPlatformDefault() {
            return policyId == null;
        }
    }
}
