package uz.horecaos.platform.iam.api.devices;

/**
 * The closed set of device kinds that may enrol as an ADR 0079 device
 * principal.
 *
 * <p>Code-owned for the same reason {@code StationRole} is (ADR 0041): free
 * text means one deployment spells a class {@code "kds"} and another
 * {@code "KDS-1"}, and a reader six months later cannot tell whether they
 * name the same thing. ADR 0041 names three device shapes — a touch KDS, a
 * VDU wall display with no controls, and an expo screen — and ADR 0079
 * deliberately did not invent the second and third bundles in advance: adding
 * one is a new constant here, a new {@code ck_device_principal_class} value in a
 * migration, and a new {@link uz.horecaos.platform.iam.api.PlatformRole}
 * bundle, never a redesign of this primitive. ADR 0151 added {@link
 * #KITCHEN_VDU} that way; the expo screen stays unbuilt (a handover names the
 * person who made it, and a device cannot be that actor).
 */
public enum DevicePrincipalClass {

    /**
     * A kitchen display station: reads the board and marks its own branch's
     * lines started and ready. Granted exactly {@code
     * PlatformRole.KITCHEN_DEVICE} — {@code kitchen.ticket.read} and {@code
     * kitchen.ticket.advance} — never more.
     */
    KITCHEN_KDS,

    /**
     * A kitchen wall display (ADR 0151): reads the VDU projection and nothing
     * else. Granted exactly {@code PlatformRole.KITCHEN_VDU_DEVICE} — {@code
     * kitchen.display.read} — and has no controls: it cannot start, ready or
     * recall a line. Its station is a server-side configuration of the device,
     * not a parameter of the request.
     */
    KITCHEN_VDU;

    /**
     * Whether an enrolment that asked to be this class may be approved as {@code approved}
     * (ADR 0151: the approval narrows and never widens). A request is a claim a person nearby can
     * make; what it asks for is shown to the approver, who may give less but never more. A touch
     * display may be approved as a wall display (a TV that was enrolled as a tablet, or a tablet
     * mounted as a wall); a wall display may not be approved as a touch display, so a TV that
     * announced itself as read-only cannot be turned into a control surface by an approval click.
     */
    public boolean mayBeApprovedAs(DevicePrincipalClass approved) {
        return switch (this) {
            case KITCHEN_KDS -> approved == KITCHEN_KDS || approved == KITCHEN_VDU;
            case KITCHEN_VDU -> approved == KITCHEN_VDU;
        };
    }
}
