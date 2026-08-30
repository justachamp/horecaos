package uz.qoida.platform.partner.domain;

/**
 * The kinds of identifier other systems use for one Qoida order (ADR 0040).
 *
 * <p>ADR 0026's {@code provider_entity_mappings} cannot hold these: its unique
 * keys make it a one-to-one map per binding per entity type, and one aggregator
 * order legitimately carries an internal order id, a short code printed on the
 * courier's screen, a venue-facing number and a delivery claim id at once.
 */
public enum ExternalReferenceType {

    /** The partner's own stable identifier, and half of the idempotency key. */
    PARTNER_ORDER_ID,

    /** The short code a customer or courier reads aloud. Not unique for long. */
    PARTNER_DISPLAY_CODE,

    /** The number the partner shows the venue, which is often a third value again. */
    PARTNER_VENUE_ORDER_NO,

    /** ADR 0014: the courier claim, when the partner exposes one. */
    DELIVERY_CLAIM_ID,

    /** ADR 0012: the identifier the till gave the exported order. */
    POS_ORDER_ID
}
