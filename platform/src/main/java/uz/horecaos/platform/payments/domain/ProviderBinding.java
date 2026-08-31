package uz.horecaos.platform.payments.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.secrets.SecretReference;

/**
 * Which merchant account handles this payment, and whose it is (ADR 0013, ADR 0026).
 *
 * <p>The legal-entity dimension is the whole point. Neither provider accepts a
 * seller identity as a per-request field — Payme derives it from the cashbox and
 * Click from {@code service_id} plus {@code merchant_user_id} — so one HorecaOS
 * account cannot serve many restaurants without naming HorecaOS as the seller on
 * every receipt it issues. Each legal entity therefore holds its own Click service
 * and its own Payme cashbox, and a binding that carried only a tenant would be
 * unable to say which.
 *
 * <p>No credential appears here and none ever will. {@link #secretReference()} is
 * an ADR 0028 handle the adapter resolves at call time; rotation changes what is
 * behind it and never the value itself, so no row is rewritten when a key rotates.
 *
 * @param merchantAccountReference Click {@code service_id}, or the Payme cashbox
 *                                 id. Identifies the account without
 *                                 authenticating to it
 * @param merchantUserReference    Click {@code merchant_user_id}; empty on
 *                                 providers with no second account field
 * @param merchantIdReference      Click {@code merchant_id}, a third identifier
 *                                 that appears on the {@code my.click.uz} payment
 *                                 link and nowhere else. Empty on Payme, and
 *                                 empty on a Click binding registered before the
 *                                 column existed — in which case the link is
 *                                 refused rather than built without the parameter
 *                                 Click documents as mandatory
 * @param callbackPathSegment      the segment that carries this binding in the
 *                                 inbound URL. Guessable by design: because the
 *                                 credential is per cashbox and per service, one
 *                                 shared callback URL cannot authenticate a
 *                                 request, so there is an endpoint per binding and
 *                                 the signature or Basic credential is what
 *                                 authenticates it
 */
public record ProviderBinding(
        UUID bindingId,
        UUID tenantId,
        UUID legalEntityId,
        PaymentProviderType providerType,
        UUID installationId,
        UUID integrationBindingId,
        String merchantAccountReference,
        @Nullable String merchantUserReference,
        @Nullable String merchantIdReference,
        SecretReference secretReference,
        String callbackPathSegment,
        boolean supportsReversal,
        boolean supportsPartnerFiscalization,
        LocalDate effectiveFrom,
        @Nullable LocalDate effectiveUntil) {

    public ProviderBinding {
        Objects.requireNonNull(bindingId, "A binding id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(legalEntityId, "A legal entity is required: it is the seller");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(merchantAccountReference, "A merchant account reference is required");
        Objects.requireNonNull(secretReference, "A secret reference is required");
        Objects.requireNonNull(callbackPathSegment, "A callback path segment is required");
        Objects.requireNonNull(effectiveFrom, "An effective date is required");
    }

    public Optional<String> merchantUser() {
        return Optional.ofNullable(merchantUserReference);
    }

    /**
     * Click's {@code merchant_id}, absent until a binding carries one.
     *
     * <p>Empty is not an error and not a default. It means the payment link
     * cannot be built for this binding, and the presentation refuses rather than
     * omitting a parameter Click documents as mandatory — a link missing it is
     * either rejected at {@code my.click.uz} or, worse, resolved against whatever
     * merchant Click infers, which would be another restaurant's account.
     */
    public Optional<String> merchantId() {
        return Optional.ofNullable(merchantIdReference).filter(reference -> !reference.isBlank());
    }

    public boolean effectiveOn(LocalDate businessDate) {
        return !businessDate.isBefore(effectiveFrom)
                && (effectiveUntil == null || businessDate.isBefore(effectiveUntil));
    }

    /**
     * Deliberately omits the secret reference and the account identifiers.
     *
     * <p>A binding ends up in exception messages and log lines, and the account
     * reference is the one field that names a specific restaurant's merchant
     * account. The id is enough to find the row.
     */
    @Override
    public String toString() {
        return "ProviderBinding[" + providerType + " binding=" + bindingId + " legalEntity=" + legalEntityId + "]";
    }
}
