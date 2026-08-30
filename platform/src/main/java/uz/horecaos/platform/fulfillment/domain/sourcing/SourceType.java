package uz.horecaos.platform.fulfillment.domain.sourcing;

/**
 * Which lane carried the order (ADR 0014).
 *
 * <p>Two values and not a provider name. ADR 0014 refuses to treat internal
 * couriers as another provider adapter, and the database says the same thing in
 * {@code ck_shipment_internal_courier}: exactly one of a courier and a binding is
 * present, so a mixed row is unreachable rather than merely unlikely.
 */
public enum SourceType {
    INTERNAL,
    PARTNER
}
