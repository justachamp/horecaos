package uz.horecaos.platform.iam.api;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The platform-defined role bundles a tenant may see and grant (ADR 0025,
 * staff-and-access.md §5 "Должности").
 *
 * <p>Static and code-owned, the same reasoning {@link CapabilityView}'s own
 * doc gives for why it is not an authorization decision: this exists so a
 * frontend can render what a job permits before someone hands it to a
 * colleague, not to decide whether the caller may act.
 *
 * <p>{@link PlatformRole#PLATFORM_ADMIN} and {@link PlatformRole#PLATFORM_SUPPORT}
 * are never included. staff-and-access.md §5 is explicit that the two are
 * "never listed, never returned to a tenant client, and never grantable", and
 * {@code GrantManagementService} already enforces the "never grantable" half;
 * this is the "never listed" half, held at the one place that would otherwise
 * have to remember it on every caller's behalf.
 *
 * <p>{@link PlatformRole#KITCHEN_DEVICE}, {@link PlatformRole#KITCHEN_VDU_DEVICE} and {@link
 * PlatformRole#PRINT_AGENT} (ADR 0154) and {@link PlatformRole#KIOSK_DEVICE} (ADR 0162) join the exclusion for a different
 * reason (ADR 0079): this catalogue answers "what job could I hand a
 * colleague", and a device is not a colleague. The bundle is real and is
 * granted through the ordinary {@code iam.grants} path like any other, but
 * only ever by {@code DeviceEnrolmentService} as the fixed, non-negotiable
 * consequence of an approved enrolment — never chosen from a picker the way
 * the eight bundles below are.
 *
 * <p>Tenant-defined jobs (ADR 0186; {@code iam.roles} rows with a non-null
 * {@code tenant_id}) are deliberately absent from this static list. They are a database
 * read, per tenant: {@code TenantRoleService} lists them and {@code
 * GrantManagementService#grantableRoles} joins them to these bundles. What this class
 * still owns, because it is the one place that already knows which bundles are people's
 * and which are machines', is the answer to "which code-owned jobs may a tenant see" and
 * therefore, through {@link TenantJobRules}, which capabilities a tenant job may be
 * composed from.
 */
public final class TenantRoleCatalog {

    private TenantRoleCatalog() {}

    /** The eight tenant-visible bundles, in {@link PlatformRole} declaration order. */
    public static List<RoleDescriptor> tenantVisible() {
        return tenantVisibleRoles().stream().map(RoleDescriptor::of).toList();
    }

    /** The same eight, as the code-owned roles themselves. */
    public static List<PlatformRole> tenantVisibleRoles() {
        return Arrays.stream(PlatformRole.values())
                .filter(role -> role != PlatformRole.PLATFORM_ADMIN
                        && role != PlatformRole.PLATFORM_SUPPORT
                        && role != PlatformRole.KITCHEN_DEVICE
                        && role != PlatformRole.KITCHEN_VDU_DEVICE
                        && role != PlatformRole.PRINT_AGENT
                        && role != PlatformRole.KIOSK_DEVICE
                        && !role.supportSessionOnly())
                .toList();
    }

    /**
     * One job, as the console renders it: the code a grant names, the level it
     * is normally granted at, and the capability codes it carries. Sentences —
     * "Отменять заказы" rather than {@code order.cancel} — are a frontend
     * translation table keyed by these codes (staff-and-access.md §3), never
     * server-rendered text, so the one catalogue serves all three locales.
     */
    public record RoleDescriptor(String code, ResourceScope.ScopeType scopeType, Set<String> capabilities) {

        static RoleDescriptor of(PlatformRole role) {
            return new RoleDescriptor(
                    role.code(),
                    role.scopeType(),
                    role.capabilities().stream()
                            .map(Capability::code)
                            .collect(Collectors.collectingAndThen(Collectors.toCollection(TreeSet::new), Set::copyOf)));
        }
    }
}
