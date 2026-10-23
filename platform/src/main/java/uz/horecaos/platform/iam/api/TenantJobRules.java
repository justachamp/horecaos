package uz.horecaos.platform.iam.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a tenant-defined job may be made of (ADR 0186, which reverses ADR 0025's closed input
 * "no tenant-defined roles in v1").
 *
 * <p>A job is a named subset of the capability catalogue. This class is where "subset"
 * becomes executable: it answers which capabilities a tenant job could ever carry,
 * independent of who is asking. Who is asking is the second rule ({@code TenantRoleService}
 * requires the author to hold every capability at tenant scope) and the two compose.
 *
 * <p>The deny-list is derived from code rather than written a second time. A capability is
 * <em>composable</em> only if some tenant-visible code-owned job ({@link
 * TenantRoleCatalog#tenantVisibleRoles()}) already carries it, and its resource type is
 * neither {@code platform} nor {@code iam}:
 *
 * <ul>
 *   <li>"Some human job already carries it" excludes by construction every capability that is
 *       a machine's (a kitchen display, a print agent, a kiosk), HorecaOS staff's (plans,
 *       metrics, migration), conferred only by a support session, granted one person at a
 *       time (a courier's movement history), or authorised by a typed relationship (a
 *       partner, a courier). None of those is a job a restaurant hands a colleague.
 *   <li>The two resource types are the power to give power. {@code platform.admin} is
 *       Keycloak's (ADR 0003); {@code iam.grant.manage} and {@code iam.role.manage} would let
 *       a job widen who may give authority without the owner deciding it. They stay with the
 *       code-owned bundles.
 * </ul>
 *
 * <p>A new capability that no human bundle carries yet is therefore unavailable to tenant
 * jobs until a code-owned bundle takes it, which is the same "adding one is a release" ADR
 * 0025 already says of every capability.
 */
public final class TenantJobRules {

    /** At most this many active jobs per tenant; a retired job does not count. */
    public static final int MAX_ACTIVE_JOBS = 20;

    /** The longest job name, in characters, after trimming and collapsing whitespace. */
    public static final int MAX_NAME_LENGTH = 80;

    /** Every generated job code starts with this, which no code-owned role code does. */
    public static final String CODE_PREFIX = "job-";

    private static final Set<String> NEVER_COMPOSABLE_RESOURCE_TYPES = Set.of("platform", "iam");

    private static final Set<Capability> CATALOGUE = computeCatalogue();

    private static final Set<Capability> COMPOSABLE = computeComposable(CATALOGUE);

    private TenantJobRules() {}

    /**
     * Every capability a tenant-visible code-owned job carries -- the rows of the console's
     * capability grid. A superset of {@link #composable()}: the {@code iam.*} powers are
     * shown, locked, so the grid does not pretend the owner's job holds less than it does.
     */
    public static Set<Capability> catalogue() {
        return CATALOGUE;
    }

    /** What a tenant job may carry, whoever the author is. */
    public static Set<Capability> composable() {
        return COMPOSABLE;
    }

    public static boolean isComposable(Capability capability) {
        return COMPOSABLE.contains(capability);
    }

    private static Set<Capability> computeCatalogue() {
        Set<Capability> all = EnumSet.noneOf(Capability.class);
        for (PlatformRole role : TenantRoleCatalog.tenantVisibleRoles()) {
            all.addAll(role.capabilities());
        }
        return Collections.unmodifiableSet(all);
    }

    private static Set<Capability> computeComposable(Set<Capability> catalogue) {
        Set<Capability> composable = EnumSet.noneOf(Capability.class);
        for (Capability capability : catalogue) {
            if (!NEVER_COMPOSABLE_RESOURCE_TYPES.contains(capability.resourceType())) {
                composable.add(capability);
            }
        }
        return Collections.unmodifiableSet(composable);
    }
}
