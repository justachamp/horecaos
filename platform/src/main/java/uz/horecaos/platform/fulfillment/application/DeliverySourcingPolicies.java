package uz.horecaos.platform.fulfillment.application;

import java.util.Set;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySubsidyPolicy;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyKey;

/** The ADR 0030 keys behind ADR 0014's sourcing timings and cost allocation. */
public final class DeliverySourcingPolicies {

    /**
     * Settable down to the branch, because every number in the document is a
     * fact about one kitchen and the streets around it: a courier reaches a
     * branch on Amir Temur in eight minutes and one in Yunusobod in twenty, and
     * a tenant-wide lead time is wrong at both.
     */
    public static final PolicyKey<DeliverySourcingPolicy> SOURCING = new PolicyKey<>(
            "fulfillment.sourcing",
            DeliverySourcingPolicy.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "fulfillment",
            true,
            "In-house and partner courier lead times, the safety buffer, the pickup window "
                    + "width, how many couriers are offered an order before an external partner "
                    + "is called, the ceiling on an offer's lifetime, and how far past the pickup "
                    + "window an assignment may still be attempted.");

    /**
     * Settable down to the branch for the same reason as {@link #SOURCING}: a
     * tenant that wants overruns escalated to a human at one high-value flagship
     * location and quietly platform-absorbed everywhere else needs the scope,
     * not just the value, to vary.
     */
    public static final PolicyKey<DeliverySubsidyPolicy> SUBSIDY = new PolicyKey<>(
            "fulfillment.delivery_subsidy",
            DeliverySubsidyPolicy.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "fulfillment",
            true,
            "Who absorbs the gap when a partner's actual delivery cost is higher than the "
                    + "customer's snapshotted delivery fee: the tenant, the brand, the location, "
                    + "the platform, or a person by hand. Provisional default is PLATFORM until "
                    + "product and finance settle the open bearer question (ADR 0013, ADR 0048).");

    private DeliverySourcingPolicies() {}
}
