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
 * <p>Tenant-defined roles ({@code iam.roles} rows with a non-null
 * {@code tenant_id}) are deliberately absent — ADR 0025 defers them
 * ("Tenants may later define custom roles from the same capability
 * catalogue"), and {@code GrantManagementService.resolveRole} does not
 * accept one as a target for a tenant that has not defined it. This catalogue
 * grows a database read the day that ships; until then every tenant sees the
 * same eight bundles, so a static list is the honest shape rather than a
 * premature one.
 */
public final class TenantRoleCatalog {

    private TenantRoleCatalog() {}

    /** The eight tenant-visible bundles, in {@link PlatformRole} declaration order. */
    public static List<RoleDescriptor> tenantVisible() {
        return Arrays.stream(PlatformRole.values())
                .filter(role -> role != PlatformRole.PLATFORM_ADMIN && role != PlatformRole.PLATFORM_SUPPORT)
                .map(RoleDescriptor::of)
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
