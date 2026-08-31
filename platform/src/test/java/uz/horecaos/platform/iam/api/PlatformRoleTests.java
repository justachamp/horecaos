package uz.horecaos.platform.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * ADR 0025 role bundles.
 *
 * <p>A granular role set was chosen deliberately, accepting that some bundles
 * are guesswork until real tenants exist. These tests convert the two failure
 * modes of that choice - a capability nobody holds, and a role that quietly
 * accumulates authority - into build failures.
 */
class PlatformRoleTests {

    /**
     * Capabilities exercised only from a platform path, and deliberately never
     * composed into a bundle a tenant can be granted.
     *
     * <p>Each one is named as platform-only by its own declaration: the plan
     * catalogue is HorecaOS's price list rather than a tenant's, a metric signature
     * moves a definition to settled on every tenant's screen at once, the
     * migration control plane records which estate is being retired, and the
     * inbox failure worklist spans tenants. {@code control-plane-alert.raise}
     * joins them for the same reason (ADR 0058): a control-band metric is
     * arithmetic over the whole fleet, never one tenant's concern, so there
     * is no tenant-scoped grant of it to withhold — see that capability's
     * own Javadoc.
     */
    private static final Set<Capability> PLATFORM_STAFF_ONLY = EnumSet.of(
            Capability.PLATFORM_ADMIN,
            Capability.METRIC_MANAGE,
            Capability.INTEGRATION_FAILURE_RESOLVE,
            Capability.COMMERCIAL_PLAN_MANAGE,
            Capability.COMMERCIAL_PLAN_ACTIVATE,
            Capability.COMMERCIAL_USAGE_ADJUST,
            Capability.MIGRATION_READ,
            Capability.MIGRATION_SCOPE_MANAGE,
            Capability.MIGRATION_RUN_EXECUTE,
            Capability.MIGRATION_CUTOVER_APPROVE,
            Capability.MIGRATION_QUARANTINE_RESOLVE,
            Capability.CONTROL_PLANE_ALERT_RAISE);

    /**
     * ADR 0049 operations authorised by a typed non-staff relationship rather
     * than by a role bundle.
     *
     * <p>The partner client is constrained to its active bindings; a courier is
     * constrained to their own shift or handover. Keeping these here says why a
     * role does not hold them without treating that absence as an unresolved gap.
     */
    private static final Set<Capability> NON_STAFF_RELATIONSHIP_AUTHORIZED = EnumSet.of(
            Capability.MARKETPLACE_ORDER_RECEIVE, Capability.COURIER_SHIFT_OPEN, Capability.COURIER_SHIFT_BREAK);

    /**
     * ADR 0045: capabilities that are in no bundle because being in one is the
     * failure.
     *
     * <p>Unlike the two lists above, this is a decision rather than a gap or a
     * platform boundary. {@code courier.track.reveal} opens one named
     * self-employed courier's stored movement history, and ADR 0045 requires
     * every use to be granted per person, for a declared purpose, with an ADR
     * 0027 audit entry. A bundle is standing access, which is the opposite of
     * that; {@code PLATFORM_ADMIN} excludes it too, which is why
     * {@code aSuperuserDoesNotSilentlyHoldTheTrackReveal} exists below rather
     * than this entry being enough on its own.
     */
    private static final Set<Capability> GRANTED_ONLY_PER_PERSON = EnumSet.of(
            Capability.COURIER_TRACK_REVEAL,
            // ADR 0042 gives the registration number the same treatment for the
            // same reason: it is a self-employed person's tax identifier, ADR 0029
            // protected, and reading one is a purposeful act rather than a
            // standing permission.
            Capability.COURIER_REGISTRATION_REVEAL);

    /**
     * The guard this test used to be passed for the wrong reason: {@code
     * PLATFORM_ADMIN} holds {@code allOf(Capability.class)}, so "held by at least
     * one role" was true of every capability the moment it was declared, and
     * sixty of the hundred and five were held by nothing else — forty-four of
     * them for no reason but that no request had ever been refused. Excluding the
     * superuser is what makes the assertion mean what its name says.
     */
    @Test
    void everyCapabilityIsHeldByARoleOtherThanTheSuperuser() {
        Set<Capability> held = EnumSet.noneOf(Capability.class);
        Arrays.stream(PlatformRole.values())
                .filter(role -> role != PlatformRole.PLATFORM_ADMIN)
                .forEach(role -> held.addAll(role.capabilities()));

        Set<Capability> orphaned = EnumSet.allOf(Capability.class);
        orphaned.removeAll(held);
        orphaned.removeAll(PLATFORM_STAFF_ONLY);
        orphaned.removeAll(NON_STAFF_RELATIONSHIP_AUTHORIZED);
        orphaned.removeAll(GRANTED_ONLY_PER_PERSON);

        assertThat(orphaned).as("""
                        A capability only the superuser holds is a job nobody can be
                        given. Put it in the bundle whose name claims the job, or record
                        it above as platform-only or as having no staff principal.""").isEmpty();
    }

    /**
     * Keeps the two exemption lists from silently outliving their reason: an
     * entry that a bundle later picks up is no longer an exemption, and leaving
     * it listed would exempt the next capability somebody adds beside it.
     */
    @Test
    void anExemptedCapabilityIsActuallyExempt() {
        Set<Capability> heldByATenantRole = EnumSet.noneOf(Capability.class);
        Arrays.stream(PlatformRole.values())
                .filter(role -> role != PlatformRole.PLATFORM_ADMIN)
                .forEach(role -> heldByATenantRole.addAll(role.capabilities()));

        Set<Capability> stale = EnumSet.copyOf(PLATFORM_STAFF_ONLY);
        stale.addAll(NON_STAFF_RELATIONSHIP_AUTHORIZED);
        stale.addAll(GRANTED_ONLY_PER_PERSON);
        stale.retainAll(heldByATenantRole);

        assertThat(stale)
                .as("this capability is in a bundle now; take it off the exemption list")
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(PlatformRole.class)
    void everyRoleGrantsSomething(PlatformRole role) {
        assertThat(role.capabilities())
                .as("an empty role is a bundle nobody finished defining")
                .isNotEmpty();
    }

    @Test
    void onlyThePlatformAdministratorHoldsPlatformAdmin() {
        assertThat(Arrays.stream(PlatformRole.values())
                        .filter(role -> role.grants(Capability.PLATFORM_ADMIN))
                        .toList())
                .as("ADR 0025: platform.admin is never granted through tenant administration")
                .containsExactly(PlatformRole.PLATFORM_ADMIN);
    }

    /**
     * ADR 0045. The superuser bundle is {@code complementOf} exactly one
     * capability, and this is the test that says which and why. On the ADR 0034
     * topology one person holds this role, so an implicit grant here means a
     * fleet's movement history is readable by that person forever with nothing
     * recorded — and ADR 0045's whole control is that a reveal is answerable.
     */
    @Test
    void aSuperuserDoesNotSilentlyHoldTheTrackReveal() {
        assertThat(Arrays.stream(PlatformRole.values())
                        .filter(role -> role.grants(Capability.COURIER_TRACK_REVEAL))
                        .toList())
                .as("""
                        courier.track.reveal is granted per person with a declared purpose
                        and an audit entry (ADR 0045). A bundle holding it is standing
                        access, and platform.admin holding it is standing access nobody
                        had to ask for.""")
                .isEmpty();
    }

    /**
     * The live map is a different power from the history, and ADR 0045 puts it
     * in exactly two bundles at a location scope.
     */
    @Test
    void theLiveCourierMapIsHeldByDispatchAndTheBranchOnly() {
        assertThat(Arrays.stream(PlatformRole.values())
                        .filter(role -> role != PlatformRole.PLATFORM_ADMIN)
                        .filter(role -> role.grants(Capability.COURIER_POSITION_READ))
                        .toList())
                .as("a cross-tenant or tenant-wide standing fleet map is not what ADR 0045 decided")
                .containsExactlyInAnyOrder(PlatformRole.COURIER_DISPATCHER, PlatformRole.LOCATION_MANAGER);
    }

    @Test
    void platformSupportCanReadButNeverMutate() {
        Set<Capability> mutations = EnumSet.of(
                Capability.TENANT_WRITE,
                Capability.CATALOG_PUBLISH,
                Capability.INVENTORY_ADJUST,
                Capability.ORDER_APPROVE,
                Capability.REFUND_EXECUTE,
                Capability.CUSTOMER_MANAGE);

        assertThat(PlatformRole.PLATFORM_SUPPORT.capabilities())
                .as("cross-tenant support is read-only by construction")
                .doesNotContainAnyElementsOf(mutations);
        assertThat(PlatformRole.PLATFORM_SUPPORT.grants(Capability.ORDER_READ)).isTrue();
    }

    @Test
    void refundExecutionIsLimitedToOwnerAndFinance() {
        assertThat(Arrays.stream(PlatformRole.values())
                        .filter(role -> role != PlatformRole.PLATFORM_ADMIN)
                        .filter(role -> role.grants(Capability.REFUND_EXECUTE))
                        .toList())
                .as("refund execution moves money out of the tenant's merchant account")
                .containsExactlyInAnyOrder(PlatformRole.TENANT_OWNER, PlatformRole.TENANT_FINANCE);
    }

    @Test
    void rolesThatCanRequestARefundOutnumberThoseThatCanExecuteIt() {
        long requesters = Arrays.stream(PlatformRole.values())
                .filter(role -> role.grants(Capability.REFUND_REQUEST))
                .count();
        long executors = Arrays.stream(PlatformRole.values())
                .filter(role -> role.grants(Capability.REFUND_EXECUTE))
                .count();

        assertThat(requesters)
                .as("front-line staff raise refunds; a narrower set executes them")
                .isGreaterThan(executors);
    }

    @Test
    void locationStaffCannotTouchCatalogueOrMoney() {
        Set<Capability> forbidden = EnumSet.of(
                Capability.CATALOG_AUTHOR, Capability.CATALOG_PUBLISH,
                Capability.PRICING_AUTHOR, Capability.PRICING_ACTIVATE,
                Capability.REFUND_REQUEST, Capability.REFUND_EXECUTE,
                Capability.INVENTORY_ADJUST, Capability.ORDER_STATE_OVERRIDE);

        assertThat(PlatformRole.LOCATION_STAFF.capabilities()).doesNotContainAnyElementsOf(forbidden);
        assertThat(PlatformRole.LOCATION_STAFF.grants(Capability.ORDER_APPROVE))
                .as("working the order feed is the whole point of the role")
                .isTrue();
    }

    @Test
    void integrationInstallationIsLimitedToTenantOwnerAndAdmin() {
        assertThat(Arrays.stream(PlatformRole.values())
                        .filter(role -> role != PlatformRole.PLATFORM_ADMIN)
                        .filter(role -> role.grants(Capability.INTEGRATION_INSTALLATION_MANAGE))
                        .toList())
                .containsExactlyInAnyOrder(PlatformRole.TENANT_OWNER, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void aTenantAdminHasNoCommercialOrExecutionAuthority() {
        assertThat(PlatformRole.TENANT_ADMIN.capabilities())
                .as("subscription and refund execution stay with the owner and finance")
                .doesNotContain(
                        Capability.REFUND_EXECUTE, Capability.COMMERCIAL_SUBSCRIPTION_MANAGE, Capability.TENANT_WRITE);
    }

    @ParameterizedTest
    @EnumSource(PlatformRole.class)
    void aRoleNeverExceedsWhatItsScopeCanReach(PlatformRole role) {
        if (role.scopeType() == ScopeType.LOCATION) {
            assertThat(role.capabilities())
                    .as("a location-scoped role must not carry tenant-wide authority")
                    .doesNotContain(
                            Capability.TENANT_WRITE, Capability.BRAND_WRITE,
                            Capability.CATALOG_PUBLISH, Capability.COMMERCIAL_SUBSCRIPTION_MANAGE);
        }
        if (role.scopeType() == ScopeType.BRAND) {
            assertThat(role.capabilities())
                    .doesNotContain(Capability.TENANT_WRITE, Capability.COMMERCIAL_SUBSCRIPTION_MANAGE);
        }
    }

    @Test
    void rolesAndCapabilitiesResolveByCode() {
        assertThat(PlatformRole.find("location-manager")).contains(PlatformRole.LOCATION_MANAGER);
        assertThat(Capability.find("order.approve")).contains(Capability.ORDER_APPROVE);
        assertThatThrownBy(() -> Capability.require("order.teleport"))
                .isInstanceOf(Capability.UnknownCapabilityException.class)
                .hasMessageContaining("ADR 0025");
    }
}
