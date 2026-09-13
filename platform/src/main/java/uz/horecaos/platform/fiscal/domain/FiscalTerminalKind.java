package uz.horecaos.platform.fiscal.domain;

/**
 * What kind of equipment discharges the {@code TERMINAL} fiscal responsibility
 * at a branch (ADR 0038 lines 503-513).
 *
 * <p>A kiosk is deliberately in this set rather than off to one side: ADR
 * 0038 calls a kiosk "a terminal of kind KIOSK bound to a location and
 * resolving to that location's legal entity, the whole of kiosk fiscal
 * identity, at the cost of one row" — the device/hardware integration itself
 * stays declined, but the fiscal row it needs is the same row every other
 * kind needs.
 */
public enum FiscalTerminalKind {
    /** A restaurant's own point-of-sale register. */
    POS,
    /** A courier's handheld unit, printing or transmitting on handover. */
    COURIER_TERMINAL,
    /** A self-service kiosk; the hardware integration is out of scope. */
    KIOSK,
    /** A terminal with no physical device — issued directly for a location. */
    VIRTUAL
}
