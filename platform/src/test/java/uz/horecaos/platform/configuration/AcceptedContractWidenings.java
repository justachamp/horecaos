package uz.horecaos.platform.configuration;

import java.util.Set;

/**
 * The released-contract changes an Accepted ADR decided, and the only ones the OpenAPI
 * compatibility gate lets through a type change.
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
 * <p>The allowance is deliberately narrow. It names the properties, it admits one direction
 * of one type change, and it needs the property to be the one being compared rather than a
 * path that merely ends in the same word. A new endpoint, a price, a count or any other
 * integer that becomes a number still fails the gate.
 */
final class AcceptedContractWidenings {

    /** ADR 0137: the quantity of a line, and the report columns that sum it. */
    private static final Set<String> ADR_0137_QUANTITY_PROPERTIES =
            Set.of("quantity", "totalQuantity", "quantityTotal", "pickupQuantity", "deliveryQuantity");

    private AcceptedContractWidenings() {}

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
