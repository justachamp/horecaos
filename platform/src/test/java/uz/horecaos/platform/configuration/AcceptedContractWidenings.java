package uz.horecaos.platform.configuration;

import java.util.List;
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
 * <p>ADR 0149 (Decision 3, and the headers of V0430, V0433 and V0678) is the third, and the first
 * <em>removal</em>: a preset comment's wording was three fixed fields ({@code labelRu}, {@code labelUz},
 * {@code labelEn}) beside a {@code labels} map for any further language, and the three fixed fields left the
 * wire when the columns behind them were dropped, so that the map is the only shape of the wording. The
 * allowance names the three properties and the surfaces that carried them (the control-plane preset
 * endpoints, a product's offered presets, the storefront menu's {@code commentPresets[]} and an order
 * line's {@code commentPresets[]}); V0679 does the same for a region's and a service zone's name
 * ({@code displayNameRu/Uz/En}) and the dispatch editor's zone options ({@code nameRu/Uz/En}), which are
 * now the {@code displayNames} / {@code names} maps alone; no other schema may lose a property. No production tenant exists
 * (ADR 0055), so a client generated from the released document has nobody to break.
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

    /** ADR 0149: the three fixed-language fields a preset comment's wording no longer has. */
    private static final Set<String> ADR_0149_PRESET_LEGACY_LABELS = Set.of("labelRu", "labelUz", "labelEn");

    /**
     * ADR 0149: the fixed-language name fields a region, a service zone and a dispatch-rule zone option no
     * longer have (V0679). {@code displayNameRu/Uz/En} on the region and zone surfaces, {@code nameEn/Ru/Uz} on
     * the dispatch editor's zone options.
     */
    private static final Set<String> ADR_0149_NAME_LEGACY_FIELDS =
            Set.of("displayNameRu", "displayNameUz", "displayNameEn", "nameRu", "nameUz", "nameEn");

    /** The surfaces that carried them; a path fragment is enough, the property names do the narrowing. */
    private static final List<String> ADR_0149_NAME_SURFACES =
            List.of("/regions", "/service-zones", "/zones", "/dispatch-rules/options");

    private AcceptedContractWidenings() {}

    /**
     * The properties of the schema at {@code context} that the released contract carried and that may be gone
     * now. Empty everywhere but the preset comment surfaces (ADR 0149, V0678: the control-plane endpoints
     * under {@code /comment-presets} and every {@code commentPresets[]} array a response carries) and the
     * region and zone surfaces (V0679: the regions, service zones and the dispatch editor's zone options).
     */
    static Set<String> mayBeRemoved(String context) {
        if (context.contains("/comment-presets") || context.endsWith("commentPresets[]")) {
            return ADR_0149_PRESET_LEGACY_LABELS;
        }
        if (ADR_0149_NAME_SURFACES.stream().anyMatch(context::contains)) {
            return ADR_0149_NAME_LEGACY_FIELDS;
        }
        return Set.of();
    }

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
