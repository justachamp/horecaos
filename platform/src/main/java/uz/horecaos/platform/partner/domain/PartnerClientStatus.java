package uz.horecaos.platform.partner.domain;

/**
 * The lifecycle of an inbound partner credential (ADR 0040, ADR 0028).
 *
 * <p>There is no {@code DELETED}. Revoking a partner's access is a status change
 * with a time on it, because the record that a credential once existed is the
 * thing an incident review needs most and a deleted row does not have it.
 */
public enum PartnerClientStatus {

    /** The registry row exists; the confidential client has no secret yet. */
    PENDING,

    ACTIVE,

    /** Rollback: the partner is switched off without losing the relationship. */
    SUSPENDED,

    RETIRED
}
