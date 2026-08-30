package uz.qoida.platform.courier.domain;

/**
 * How a registration's validity was established (ADR 0042).
 *
 * <p>{@link #REGISTRY_LOOKUP} is modelled and not implemented. Whether an
 * authoritative machine-readable source for самозанятость status exists in
 * Uzbekistan is an open input on ADR 0042; the manual path ships either way and
 * the engagement model is identical under both, so adding the lookup later adds
 * an adapter rather than changing what an engagement is.
 */
public enum VerificationMethod {

    MANUAL_ATTESTATION,
    REGISTRY_LOOKUP
}
