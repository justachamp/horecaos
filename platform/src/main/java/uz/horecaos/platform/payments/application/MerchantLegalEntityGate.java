package uz.horecaos.platform.payments.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Whether a legal entity a merchant binding is about to name may be named
 * (ADR 0038, consumed by ADR 0013's merchant-binding registry).
 *
 * <p>A port rather than a direct dependency on {@code tenancy.api}, the same
 * reason {@link PaymentLegalEntityResolver} is one: {@code payments.application}
 * imports nothing outside its own module, and the adapter that answers this
 * against {@code tenancy.api.LegalEntityDirectory} lives in
 * {@code payments.infrastructure}. Narrowed to exactly what registration needs —
 * a fact about one entity id — rather than reusing
 * {@link PaymentLegalEntityResolver}, whose {@code sellerFor} takes a location
 * and answers a different question: which entity sells at a branch today, not
 * whether an id a request named directly is real.
 */
public interface MerchantLegalEntityGate {

    /**
     * Returns empty when {@code legalEntityId} does not belong to {@code tenantId}
     * at all; otherwise whether it may currently be named as a seller.
     * The two are kept apart so a caller can refuse "no such legal
     * entity" as not-found and "that entity cannot sell right now" as a
     * conflict, rather than collapsing both into one answer.
     */
    Optional<Boolean> activeFor(UUID tenantId, UUID legalEntityId);
}
