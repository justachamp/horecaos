package uz.horecaos.platform.commercial.domain;

/**
 * Which door a tenant's module came through (ADR 0127).
 *
 * <p>The distinction is an authorization one, not a bookkeeping one: a tenant
 * may end a module it bought itself and may not end one HorecaOS staff gave it,
 * so the row records the door rather than leaving it to be inferred from a
 * free-text reason.
 */
public enum ModuleAcquisition {

    /** HorecaOS staff gave the tenant the module (ADR 0087). Only staff end it. */
    PLATFORM,

    /** The tenant bought the module from its own console (ADR 0127). The tenant may end it. */
    SELF_SERVICE
}
