package uz.horecaos.platform.iam.api.devices;

/**
 * The closed set of device kinds that may enrol as an ADR 0079 device
 * principal.
 *
 * <p>Code-owned for the same reason {@code StationRole} is (ADR 0041): free
 * text means one deployment spells a class {@code "kds"} and another
 * {@code "KDS-1"}, and a reader six months later cannot tell whether they
 * name the same thing. Only {@link #KITCHEN_KDS} exists today. ADR 0041
 * names two more device shapes it has not built — a VDU wall display with no
 * controls, and an expo screen — and ADR 0079 deliberately does not invent
 * their bundle in advance: adding one is a new constant here, a new {@code
 * ck_device_principal_class} value in a migration, and a new {@link
 * uz.horecaos.platform.iam.api.PlatformRole} bundle, never a redesign of this
 * primitive.
 */
public enum DevicePrincipalClass {

    /**
     * A kitchen display station: reads the board and marks its own branch's
     * lines started and ready. Granted exactly {@code
     * PlatformRole.KITCHEN_DEVICE} — {@code kitchen.ticket.read} and {@code
     * kitchen.ticket.advance} — never more.
     */
    KITCHEN_KDS
}
