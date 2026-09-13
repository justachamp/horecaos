package uz.horecaos.platform.ordering.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.tenancy.api.PolicyResolver;

/**
 * Resolves the {@code ordering.lateness} policy (ADR 0030, orders.md §2.7,
 * gap map rows {@code 1.1g}/{@code X.39}).
 *
 * <p>Precedence is not implemented here, matching {@code
 * OrderAcceptancePolicyService}'s own doc: it comes from the shared ADR 0030
 * mechanism, so lateness resolves by exactly the same rule as every other
 * scoped behavior in the platform. This service adds only the platform
 * default.
 *
 * <p>No {@code author} method: authoring {@code ordering.lateness} is {@code
 * Capability.TENANT_CONFIGURATION_WRITE} and lands with wave P31's settings
 * surface. Until a tenant, brand, or location publishes an override, {@link
 * OrderLatenessPolicy#platformDefault()} applies everywhere.
 */
@Service
public class OrderLatenessPolicyService {

    private final PolicyResolver policies;

    public OrderLatenessPolicyService(PolicyResolver policies) {
        this.policies = policies;
    }

    /** The policy in force for a location right now. */
    public Effective resolve(UUID tenantId, UUID brandId, UUID locationId) {
        return resolveAt(ResourceScope.location(tenantId, brandId, locationId));
    }

    /**
     * The same resolution at whatever scope a caller already has — mirroring
     * {@code OrderAcceptancePolicyService.resolveAt}'s own reason: a tenant or
     * brand may want to see what is in force without naming one location.
     */
    public Effective resolveAt(ResourceScope scope) {
        return policies.resolve(OrderingConfigurationKeys.LATENESS_POLICY, scope)
                .map(resolved -> new Effective(resolved.document(), resolved.policyId(), resolved.policyVersion()))
                .orElseGet(() -> new Effective(OrderLatenessPolicy.platformDefault(), null, 0));
    }

    /**
     * @param policyId null when the platform default applied, which is itself
     *                 a fact worth carrying rather than inventing an
     *                 identifier for
     */
    public record Effective(
            OrderLatenessPolicy policy, @Nullable UUID policyId, int policyVersion) {

        public boolean isPlatformDefault() {
            return policyId == null;
        }
    }
}
