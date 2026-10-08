package uz.horecaos.platform.storefrontapps.api;

/**
 * The request headers of the app tier (ADR 0070), named once so the check, the
 * published contract and the tests cannot disagree about them.
 */
public final class StorefrontAppHeaders {

    /**
     * Carries the app's id on every storefront request, in addition to the
     * customer's own session and never instead of it. The id is an identifier and
     * not a secret: it appears in a browser bundle by design.
     */
    public static final String APP_ID = "X-Storefront-App-Id";

    /**
     * Carries a confidential client's secret. A public client must not send one,
     * because there is nothing in a browser to keep it in.
     */
    public static final String APP_SECRET = "X-Storefront-App-Secret";

    private StorefrontAppHeaders() {}
}
