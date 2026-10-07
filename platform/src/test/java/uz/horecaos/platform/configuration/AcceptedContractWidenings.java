package uz.horecaos.platform.configuration;

import java.util.Set;

/**
 * The released-contract changes an Accepted ADR decided, and the only ones the OpenAPI
 * compatibility gate lets through (a type change, or a required request field made optional).
 *
 * <p>{@link OpenApiContractTests} refuses any change of a published property's type, because
 * a client generated from the released document is entitled to it. ADR 0137 is a decision
 * that changes one on purpose: {@code ordering.order_lines.quantity} and the columns it
 * feeds were integers and became {@code numeric(10,3)}, so that a splittable variant can be
 * ordered by the portion (0.5 of a plov). Every document that carries a quantity therefore
 * moves from {@code integer} to {@code number}. On the wire a whole quantity still
 * serialises as {@code 2}, not {@code 2.000}, so a basket of whole portions looks exactly as
 * it did; only a half portion is new.
 *
 * <p>ADR 0150 is the other. Decision 3 makes a mode's no-promise fallback optional in the authored
 * {@code ordering.lateness} document, as its at-risk window already was: a blank means "none of its
 * own", and the tenant's late-order threshold applies. The editor's request carried the field as
 * required, so relaxing it is the one place the gate's "cannot make a published required field
 * optional" is lifted. Relaxing a request is safe for a client generated from the released document
 * (it can still send the number); the response gains no required field and loses none.
 *
 * <p>The allowance is deliberately narrow. It names the properties, it admits one direction
 * of one type change, and it needs the property to be the one being compared rather than a
 * path that merely ends in the same word. A new endpoint, a price, a count or any other
 * integer that becomes a number still fails the gate.
 */
final class AcceptedContractWidenings {

    /** ADR 0137: the quantity of a line, and the report columns that sum it. */
    private static final Set<String> ADR_0137_QUANTITY_PROPERTIES =
            Set.of("quantity", "totalQuantity", "quantityTotal", "pickupQuantity", "deliveryQuantity");

    /** ADR 0150: the lateness editor's write, where the fallback may now be left blank. */
    private static final String ADR_0150_EDITOR_REQUEST =
            "/api/v1/operations/tenants/{tenantId}/order-lateness-policy post request.";

    private static final Set<String> ADR_0150_MODES = Set.of("delivery", "pickup", "dineIn");

    private AcceptedContractWidenings() {}

    /**
     * The properties of the schema at {@code context} that were required in the released contract and
     * may be optional now. Empty everywhere but the three per-mode objects of the lateness editor's
     * request, where it is the one field ADR 0150 relaxed.
     */
    static Set<String> mayBecomeOptional(String context) {
        if (context.startsWith(ADR_0150_EDITOR_REQUEST)
                && ADR_0150_MODES.contains(context.substring(ADR_0150_EDITOR_REQUEST.length()))) {
            return Set.of("noPromiseFallbackSeconds");
        }
        return Set.of();
    }

    /**
     * Whether a property that was {@code oldType} in the released contract may be {@code
     * newType} now.
     *
     * @param context the gate's description of where the schema sits, ending in {@code
     *     .property} when it is a property of an object
     */
    static boolean permits(String context, String oldType, String newType) {
        if (!"integer".equals(oldType) || !"number".equals(newType)) {
            return false;
        }
        int dot = context.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return ADR_0137_QUANTITY_PROPERTIES.contains(context.substring(dot + 1));
    }
}
