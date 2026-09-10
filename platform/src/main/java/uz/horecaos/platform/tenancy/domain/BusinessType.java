package uz.horecaos.platform.tenancy.domain;

import java.util.List;

/**
 * What kind of business a tenant is (ADR 0090), and the shape each usually
 * takes: how it hands orders over, and whether it runs a kitchen display.
 *
 * <p>Recorded on the tenant and shown in the control plane. It enables and
 * disables nothing on its own today: onboarding applies the default template
 * to every type, and what a tenant may do is decided by its capabilities and
 * entitlements, never by its type. The shape here is the default a future
 * per-type template would start from.
 */
public enum BusinessType {
    RESTAURANT(List.of(Handover.DELIVERY, Handover.PICKUP, Handover.DINE_IN), true),
    CAFE(List.of(Handover.DELIVERY, Handover.PICKUP, Handover.DINE_IN), true),
    FAST_FOOD(List.of(Handover.DELIVERY, Handover.PICKUP, Handover.DINE_IN), true),
    BAKERY(List.of(Handover.DELIVERY, Handover.PICKUP), true),
    DARK_KITCHEN(List.of(Handover.DELIVERY, Handover.PICKUP), true),
    CATERING(List.of(Handover.DELIVERY), true),
    COURIER_SERVICE(List.of(Handover.DELIVERY), false),
    PHARMACY(List.of(Handover.DELIVERY, Handover.PICKUP), false),
    FLORIST(List.of(Handover.DELIVERY, Handover.PICKUP), false);

    /** How an order reaches the customer. */
    public enum Handover {
        DELIVERY,
        PICKUP,
        DINE_IN
    }

    private final List<Handover> handovers;
    private final boolean kitchenDisplay;

    BusinessType(List<Handover> handovers, boolean kitchenDisplay) {
        this.handovers = List.copyOf(handovers);
        this.kitchenDisplay = kitchenDisplay;
    }

    public List<Handover> handovers() {
        return handovers;
    }

    public boolean kitchenDisplay() {
        return kitchenDisplay;
    }
}
