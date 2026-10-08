package uz.horecaos.platform.notifications.api;

/**
 * Which gateways have a receipt source at all (ADR 0146 Decision 4 and 5).
 *
 * <p>One answer read by both halves, because they have to agree. The receipt
 * endpoint answers 404 for a provider type without one, and the "no receipt"
 * sweeper skips the same types: with nothing listening, the absence of a receipt
 * says nothing, and reporting it as one would be a claim the platform cannot
 * support.
 *
 * <p>Enabled per provider type by deployment configuration, not by a tenant. VAS
 * stays off until one real callback has been captured against a controlled
 * account.
 */
public interface ReceiptEnablement {

    boolean isEnabled(String providerType);

    /** Every provider type that has one, which is what the sweepers walk. */
    java.util.Set<String> enabledProviderTypes();
}
