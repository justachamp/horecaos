package uz.qoida.platform.courier.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import uz.qoida.platform.courier.domain.CourierCompensationPolicy;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.tenancy.api.PolicyResolver;
import uz.qoida.platform.tenancy.api.ResolvedPolicy;

/**
 * The module's one reader of ADR 0030 policy, so that "what does this tenant's
 * warning window say" has one answer and one default.
 *
 * <p>The resolved identity is returned as well as the document wherever a
 * durable decision is being made, because ADR 0042 requires the enforcement mode
 * and its version to be snapshotted onto the shift: without the snapshot,
 * tightening the policy in October makes September's shifts look illegal.
 */
@Component
public class CourierPolicyResolver {

    private final PolicyResolver policies;

    public CourierPolicyResolver(PolicyResolver policies) {
        this.policies = policies;
    }

    public CourierCompensationPolicy resolve(ResourceScope scope) {
        return resolveWithIdentity(scope).document();
    }

    /**
     * The document together with the identity to persist beside a decision.
     * Falls back to ADR 0042's provisional defaults with a null identity, which
     * is a truthful record that nothing was configured rather than a fabricated
     * policy version.
     */
    public ResolvedPolicy<CourierCompensationPolicy> resolveWithIdentity(ResourceScope scope) {
        Optional<ResolvedPolicy<CourierCompensationPolicy>> resolved =
                policies.resolve(CourierPolicies.COMPENSATION, scope);
        return resolved.orElseGet(() -> new ResolvedPolicy<>(
                CourierPolicies.COMPENSATION.code(),
                DEFAULTS_ID, 1, scope.type(), "defaults", CourierCompensationPolicy.DEFAULTS));
    }

    /**
     * A stable identifier for "nothing was configured, ADR 0042's provisional
     * defaults applied". A random id per call would make two identical decisions
     * look like they ran under two different policies.
     */
    public static final java.util.UUID DEFAULTS_ID =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000042");
}
