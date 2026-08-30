package uz.horecaos.platform.payments.domain;

/**
 * The lifecycle {@code payments.merchant_bindings.status} enforces (ADR 0013,
 * V0027).
 *
 * <p>Matches {@code tenancy.domain.OperatingUnitStatus} in shape and not in type:
 * both are DRAFT, then ACTIVE, then optionally SUSPENDED and back, ending at a
 * terminal state the row survives under. The terminal name differs on purpose —
 * {@code RETIRED} here, {@code ARCHIVED} there — because the two are enforced by
 * two different check constraints in two different schemas, and a shared enum
 * would be a domain type crossing a module boundary for a coincidence of
 * vocabulary.
 */
public enum MerchantBindingStatus {
    DRAFT,
    ACTIVE,
    SUSPENDED,
    RETIRED;

    /** Whether {@link uz.horecaos.platform.payments.application.PaymentBindingResolver} may return this row. */
    public boolean resolvable() {
        return this == ACTIVE;
    }
}
