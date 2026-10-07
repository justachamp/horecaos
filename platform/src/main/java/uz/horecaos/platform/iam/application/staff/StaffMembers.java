package uz.horecaos.platform.iam.application.staff;

import java.util.Set;
import uz.horecaos.platform.iam.api.PlatformRole;

/** Constants and small pure rules the staff member record shares (ADR 0139). */
final class StaffMembers {

    /**
     * The roles that are a grant without a colleague behind it: a kitchen
     * display device (ADR 0079) and a HorecaOS support session (ADR 0081). A
     * device or a support person is not a member of a tenant's staff, so a
     * grant of one of these never makes a subject someone the tenant keeps a
     * record of -- the directory answers "no such member" for them and callers
     * keep their existing labelled rendering.
     *
     * <p>Partner clients (ADR 0049) and couriers hold no {@code iam.grants} row
     * at all, so there is nothing to exclude for them here.
     */
    static final Set<String> MACHINE_ROLE_CODES = Set.of(
            PlatformRole.KITCHEN_DEVICE.code(),
            PlatformRole.SUPPORT_SESSION_VIEW.code(),
            PlatformRole.SUPPORT_SESSION_ASSIST.code());

    static final String PENDING = "PENDING";
    static final String ACTIVE = "ACTIVE";
    static final String ON_LEAVE = "ON_LEAVE";
    static final String ENDED = "ENDED";

    static final int MAX_SPOKEN_LANGUAGES = 8;

    private StaffMembers() {}

    /** {@code S-0142}: at least four digits, so references sort and read the same as they grow. */
    static String reference(int number) {
        return "S-%04d".formatted(number);
    }
}
