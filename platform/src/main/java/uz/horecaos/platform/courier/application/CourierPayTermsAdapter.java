package uz.horecaos.platform.courier.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.fulfillment.api.CourierPayTermsPort;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * {@code courier}'s answer to the one pay question {@code fulfillment} asks before a dispatch rule may group
 * (ADR 0170 Decision 6): has this scope decided how a run of several orders is paid?
 *
 * <p>Answered from the resolved {@code courier.compensation} document and nowhere else, so «how is a courier
 * paid» keeps one answer. The scope is the one the rule is being published at: the tenant, a brand or a branch.
 */
@Component
public class CourierPayTermsAdapter implements CourierPayTermsPort {

    private final CourierPolicyResolver policies;

    public CourierPayTermsAdapter(CourierPolicyResolver policies) {
        this.policies = policies;
    }

    @Override
    public RunPayTerms runPayTerms(UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        ResourceScope scope = locationId != null && brandId != null
                ? ResourceScope.location(tenantId, brandId, locationId)
                : brandId != null ? ResourceScope.brand(tenantId, brandId) : ResourceScope.tenant(tenantId);
        CourierCompensationPolicy policy = policies.resolve(scope);
        if (policy.runPayMode() == null) {
            return RunPayTerms.UNDECIDED;
        }
        return switch (policy.runPayMode()) {
            case PER_ORDER -> RunPayTerms.PER_ORDER;
            case FIRST_FULL_THEN_INCREMENT -> RunPayTerms.FIRST_FULL_THEN_INCREMENT;
        };
    }
}
