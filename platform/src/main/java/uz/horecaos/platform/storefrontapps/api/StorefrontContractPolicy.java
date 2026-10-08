package uz.horecaos.platform.storefrontapps.api;

/**
 * What the platform promises a storefront vendor about the published contract
 * (ADR 0070), as constants the generated document and the tests share.
 *
 * <p>The deprecation window is code-owned and single. ADR 0070 closes the open
 * input on "the deprecation window a published path is guaranteed for" without
 * naming a figure, so the figure here is a decision of this build and the one
 * place to change it; the published document reads it from here, so the number a
 * vendor is shown and the number the platform keeps cannot drift apart.
 */
public final class StorefrontContractPolicy {

    /**
     * How long a v1 path stays served alongside its v2 replacement, counted from the
     * day the replacement is announced. A path in the published surface is never
     * removed inside v1 at all; this is the window a major version change owes.
     */
    public static final int DEPRECATION_WINDOW_MONTHS = 12;

    /** The OpenAPI extension that marks an operation published or internal. */
    public static final String SURFACE_EXTENSION = "x-horecaos-surface";

    public static final String SURFACE_PUBLISHED = "published";

    public static final String SURFACE_INTERNAL = "internal";

    private StorefrontContractPolicy() {}
}
