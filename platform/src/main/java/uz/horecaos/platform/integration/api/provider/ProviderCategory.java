package uz.horecaos.platform.integration.api.provider;

/**
 * The kind of external system an installation talks to (ADR 0026).
 *
 * <p>Category decides which capability codes an adapter may declare, so a
 * payment installation cannot be bound as a courier partner.
 *
 * <p>There is deliberately no {@code FISCAL} category. Click and Payme fiscalise
 * as part of accepting a payment, so fiscalisation is a capability of a
 * {@code PAYMENT} installation. A separate category would model a provider
 * relationship HorecaOS does not have.
 */
public enum ProviderCategory {
    POS,
    PAYMENT,
    DELIVERY,

    /**
     * An aggregator that sends orders in (ADR 0040).
     *
     * <p>Distinct from {@link #DELIVERY}, and the distinction is direction rather
     * than taxonomy: Yandex Delivery sources a courier for an order HorecaOS owns,
     * Yandex Eda sends an order HorecaOS did not create. Same company, opposite
     * direction, two installations, two sets of credentials, two failure modes.
     * Folding them together would let a delivery binding carry an order, which is
     * the one thing a trigger on {@code ordering.orders} now refuses.
     *
     * <p>It is also the only category whose credentials run in both directions.
     * Outbound is ADR 0026's {@code secret_reference}; inbound is an OAuth 2.0
     * confidential client registered in {@code partner.api_clients}.
     */
    MARKETPLACE,

    NOTIFICATION,
    GEOCODING,

    /**
     * IP telephony (ADR 0064): a hosted SIP/PBX or an Asterisk-class
     * self-hosted system, behind the normalized call-event vocabulary. Never
     * carries audio — only offered/answered/ended/missed/transferred events,
     * caller number, line/DID, and timestamps.
     */
    VOICE,

    /**
     * ADR 0106: a GTM container, a GA4 property, or a Search Console
     * verification token — never a secret. Every field an {@code ANALYTICS}
     * installation carries is a public identifier a browser's view-source
     * already reveals, so it lives in {@code non_sensitive_config}, never
     * behind the ADR 0028 secret door: hiding a public identifier would teach
     * an operator that the mask means nothing.
     *
     * <p>Distinct from every outbound category in one respect: the platform's
     * own backend never calls out to an analytics provider. The customer's
     * browser does, injected by the storefront from the tenant's own
     * non-secret configuration (see {@code StorefrontAnalyticsConfigController}).
     * An {@code ANALYTICS} installation is still bound to a brand exactly like
     * any other (ADR 0026) — that binding is what the storefront's read
     * resolves to decide which tenant's identifiers to inject.
     */
    ANALYTICS,

    OTHER
}
