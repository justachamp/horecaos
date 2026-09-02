package uz.horecaos.platform.configuration;

import java.util.List;

/**
 * The path-prefix membership of each per-surface OpenAPI document (ADR 0031).
 *
 * <p>{@link OpenApiConfiguration} registers one Springdoc {@code GroupedOpenApi} bean per
 * constant here, published at {@code /v3/api-docs/<id>} alongside the full, unchanged v1
 * document at {@code /v3/api-docs}. {@code OpenApiContractTests} asserts every path in the full
 * document is covered by exactly one constant, so a controller landing under an uncategorised
 * prefix fails the build instead of silently missing from every per-surface document and its
 * generated TypeScript client.
 *
 * <p>The four groups fold ADR 0031's five stated surfaces (platform-admin, control-plane,
 * operations, storefront, customer) plus the partner surface (ADR 0040) down to the three
 * frontends that exist today (storefront, operations, control-plane) plus a fourth for every
 * externally-initiated integration (payment provider callbacks and inbound partner/marketplace
 * traffic). {@code platform-admin} moved to {@code CONTROL_PLANE} in ADR 0066, once wave 28 gave
 * it the dedicated frontend this class's own history names as the open question; the courier's
 * own self-service endpoints still have none, so they remain in {@code operations} by
 * elimination — see {@code api/README.md} for the current table.
 */
enum OpenApiSurface {

    /** Public and customer-authenticated commerce; the {@code storefront} Angular app and mobile. */
    STOREFRONT("storefront", "/api/v1/storefront/**"),

    /**
     * Platform-staff administration; the {@code control-plane} Angular app.
     *
     * <p>{@code /api/v1/platform-admin/**} joined this group in ADR 0066: those paths were
     * always HorecaOS-staff-only (never a tenant's own operations staff), and the only reason
     * they had sat in {@link #OPERATIONS} was the absence of a frontend to claim them. Wave 28
     * builds one.
     */
    CONTROL_PLANE("control-plane", "/api/v1/control-plane/**", "/api/v1/platform-admin/**"),

    /**
     * Externally-initiated integration traffic: payment provider callbacks (Click, Payme) and
     * inbound partner/marketplace order pushes (ADR 0040). No frontend consumes this group; it
     * exists so the contract of every non-tenant-staff, non-customer caller is reviewed and
     * versioned like every other surface.
     */
    PROVIDERS("providers", "/providers/**", "/api/v1/partner/**"),

    /**
     * Brand and location staff, plus everything operator-facing that is not one of the other
     * three groups: today {@code /api/v1/tenants/**} (kitchen, dine-in, inventory, orders,
     * reporting, and more), {@code /api/v1/session/**}, the {@code operations} Angular app's own
     * {@code /api/v1/operations/**}, and the courier's own {@code /api/v1/courier/**}.
     * {@code /api/v1/platform-admin/**} moved out to {@link #CONTROL_PLANE} in ADR 0066.
     */
    OPERATIONS("operations", "/api/v1/tenants/**", "/api/v1/session/**", "/api/v1/operations/**", "/api/v1/courier/**");

    private final String id;

    // The checker cannot see immutability through the interface type, but the
    // constructor below always assigns a List.of(...) unmodifiable list.
    @SuppressWarnings("ImmutableEnumChecker")
    private final List<String> pathPatterns;

    OpenApiSurface(String id, String... pathPatterns) {
        this.id = id;
        this.pathPatterns = List.of(pathPatterns);
    }

    /** The stable group identifier; also the URL segment at {@code /v3/api-docs/<id>}. */
    String id() {
        return id;
    }

    /** Ant-style path patterns, as passed to Springdoc's {@code GroupedOpenApi.pathsToMatch}. */
    List<String> pathPatterns() {
        return pathPatterns;
    }
}
