package uz.horecaos.platform.kitchen.application;

import java.util.Set;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.kitchen.domain.KitchenPaidHoldPolicy;
import uz.horecaos.platform.kitchen.domain.StationProductivityPolicy;
import uz.horecaos.platform.tenancy.api.PolicyKey;

/** The ADR 0030 policy keys the kitchen module resolves. */
public final class KitchenPolicies {

    /**
     * ADR 0168: whether a buffered ticket is held while its order's payment is required and absent.
     * Settable at every level ADR 0030 resolves, platform included: the owner turns the hold on for the
     * platform by publishing here, and a branch that serves regulars on trust keeps its own mode.
     */
    public static final PolicyKey<KitchenPaidHoldPolicy> PAID_HOLD = new PolicyKey<>(
            "kitchen.paid_hold",
            KitchenPaidHoldPolicy.class,
            Set.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "kitchen",
            false,
            "SHADOW, HOLD_UNTIL_PAID or OFF, and the minutes past a blocked ticket's fire instant before it is "
                    + "treated as stuck. Built-in default: SHADOW, ten minutes.");

    /**
     * ADR 0163: how many portions one cook produces in an hour, per station role or station code. Settable at
     * tenant, brand (where a cuisine's different pace is stated) and location. The platform ships no figure:
     * the built-in default is the empty document.
     */
    public static final PolicyKey<StationProductivityPolicy> STATION_PRODUCTIVITY = new PolicyKey<>(
            "kitchen.station_productivity",
            StationProductivityPolicy.class,
            Set.of(ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
            "kitchen",
            false,
            "Whole portions one cook produces per hour, by station role and by station code. Empty by default: "
                    + "the platform never supplies a productivity figure.");

    private KitchenPolicies() {}
}
