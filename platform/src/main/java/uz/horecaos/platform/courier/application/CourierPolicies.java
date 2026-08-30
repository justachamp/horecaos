package uz.horecaos.platform.courier.application;

import java.util.Set;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyKey;

/** The ADR 0030 keys this module resolves. */
public final class CourierPolicies {

    /**
     * Settable at every scope down to the branch, because shift enforcement is
     * explicitly a per-location choice: one branch runs a pure gig model while
     * the branch across town runs a fleet of six.
     */
    public static final PolicyKey<CourierCompensationPolicy> COMPENSATION = new PolicyKey<>(
            "courier.compensation",
            CourierCompensationPolicy.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "courier",
            true,
            "Re-verification interval, expiry warning window, settlement period length, cash "
                    + "ceiling, penalty approval threshold, shift enforcement mode, on-time "
                    + "grace, and the post-settlement retention of confirmation coordinates.");

    private CourierPolicies() {}
}
