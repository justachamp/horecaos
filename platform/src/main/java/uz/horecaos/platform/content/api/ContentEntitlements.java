package uz.horecaos.platform.content.api;

/**
 * The entitlement keys content gates on, as strings (ADR 0157, Entitlements).
 *
 * <p>Both keys are declared in {@code commercial.api.EntitlementKeys} as feature keys owned by
 * {@code content} with {@code safeDefault(FALSE)} -- off until the platform owner switches them on
 * for a tenant -- because {@code EntitlementGate.checkFeature} answers empty for a code
 * {@code EntitlementKeys} does not know. This module names them as strings through the gate, so
 * there is no edge from {@code content} to {@code commercial}. An empty answer from the gate is
 * read as <em>not entitled</em>.
 */
public final class ContentEntitlements {

    /** Tier 2: news, gallery albums, recipes and the vacancy listing. The platform owner's switch per tenant. */
    public static final String EDITORIAL_ENABLED = "content.editorial.enabled";

    /** Video upload and playback (Release 3). */
    public static final String VIDEO_ENABLED = "content.video.enabled";

    private ContentEntitlements() {}
}
