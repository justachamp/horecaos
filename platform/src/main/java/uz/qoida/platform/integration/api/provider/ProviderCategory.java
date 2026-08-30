package uz.qoida.platform.integration.api.provider;

/**
 * The kind of external system an installation talks to (ADR 0026).
 *
 * <p>Category decides which capability codes an adapter may declare, so a
 * payment installation cannot be bound as a courier partner.
 *
 * <p>There is deliberately no {@code FISCAL} category. Click and Payme fiscalise
 * as part of accepting a payment, so fiscalisation is a capability of a
 * {@code PAYMENT} installation. A separate category would model a provider
 * relationship Qoida does not have.
 */
public enum ProviderCategory {
    POS,
    PAYMENT,
    DELIVERY,

    /**
     * An aggregator that sends orders in (ADR 0040).
     *
     * <p>Distinct from {@link #DELIVERY}, and the distinction is direction rather
     * than taxonomy: Yandex Delivery sources a courier for an order Qoida owns,
     * Yandex Eda sends an order Qoida did not create. Same company, opposite
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
    OTHER
}
