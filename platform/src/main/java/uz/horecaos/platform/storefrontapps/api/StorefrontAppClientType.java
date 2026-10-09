package uz.horecaos.platform.storefrontapps.api;

/**
 * The two kinds of storefront client ADR 0070 distinguishes, honestly.
 *
 * <p>The distinction is part of the published contract, not an internal detail,
 * so a vendor knows which they are building before they write a line.
 */
public enum StorefrontAppClientType {

    /**
     * A browser-only storefront. It holds no secret, because a browser keeps none:
     * it has an app id and an origin allowlist the platform enforces. It is
     * attributable and revocable. It is <em>not authenticated</em>, in the sense a
     * browser cannot deliver, and nothing here says otherwise.
     */
    PUBLIC,

    /**
     * A server-backed storefront. It presents a secret the platform resolves
     * through an ADR 0028 reference, and gets what that earns: access to anything
     * judged unsafe for a public client, and the higher quotas ADR 0070 reserves
     * for it.
     */
    CONFIDENTIAL
}
