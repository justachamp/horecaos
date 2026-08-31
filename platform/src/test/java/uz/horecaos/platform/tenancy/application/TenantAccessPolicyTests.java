package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;
import uz.horecaos.platform.tenancy.domain.TenantStatus;

class TenantAccessPolicyTests {

    private static final Tenant TENANT = Tenant.reconstitute(
            new TenantId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120100")),
            new Slug("tenant-a"),
            "Tenant A LLC",
            "Tenant A",
            Currency.getInstance("UZS"),
            ZoneId.of("Asia/Tashkent"),
            "keycloak-organization-a",
            TenantStatus.ACTIVE);

    @Test
    void allowsMembersToReadOnlyTheirOrganization() {
        TenantAccessPolicy policy = policy(actor(Set.of(), Map.of("keycloak-organization-a", Set.of("tenant-viewer"))));

        assertThatNoException().isThrownBy(() -> policy.requireTenantRead(TENANT));
    }

    @Test
    void deniesCrossTenantReads() {
        TenantAccessPolicy policy = policy(actor(Set.of(), Map.of("another-organization", Set.of("tenant-owner"))));

        assertThatThrownBy(() -> policy.requireTenantRead(TENANT)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    /**
     * Gap C of the 2026-08-30 proving run: {@code requireTenantManagement} no
     * longer checks a Keycloak org-nested role — nothing in this codebase
     * ever assigned one, and the proving run hit a live 403 because of it for
     * an owner who held a real {@code iam.grants} grant the whole time. It
     * now resolves through the same {@link uz.horecaos.platform.iam.api.AuthorizationService}
     * {@link TenantAccessPolicy#requireTenantRead} already uses, with the exact capability and
     * scope the calling controller's own {@code @RequiresCapability} already
     * declared.
     */
    @Test
    void underEnforcementAMemberHoldingTheCapabilityCanManage() {
        TenantAccessPolicy policy =
                enforcingPolicy(actor(Set.of(), Map.of("keycloak-organization-a", Set.of())), allowAll());

        assertThatNoException()
                .isThrownBy(() -> policy.requireTenantManagement(
                        TENANT,
                        Capability.BRAND_WRITE,
                        ResourceScope.tenant(TENANT.id().value())));
    }

    /** The other proving-run assertion: a member without the grant is still refused. */
    @Test
    void underEnforcementAMemberLackingTheCapabilityIsRefused() {
        TenantAccessPolicy policy =
                enforcingPolicy(actor(Set.of(), Map.of("keycloak-organization-a", Set.of())), denyAll());

        assertThatThrownBy(() -> policy.requireTenantManagement(
                        TENANT,
                        Capability.BRAND_WRITE,
                        ResourceScope.tenant(TENANT.id().value())))
                .isInstanceOf(uz.horecaos.platform.iam.api.AuthorizationService.AccessDeniedException.class);
    }

    @Test
    void aStrangerToTheOrganizationIsRefusedManagementRegardlessOfCapability() {
        TenantAccessPolicy policy =
                enforcingPolicy(actor(Set.of(), Map.of("another-organization", Set.of())), allowAll());

        assertThatThrownBy(() -> policy.requireTenantManagement(
                        TENANT,
                        Capability.BRAND_WRITE,
                        ResourceScope.tenant(TENANT.id().value())))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void platformAdminBypassesTenantManagementCapabilityChecks() {
        TenantAccessPolicy policy = enforcingPolicy(actor(Set.of("platform-admin"), Map.of()), denyAll());

        assertThatNoException()
                .isThrownBy(() -> policy.requireTenantManagement(
                        TENANT,
                        Capability.BRAND_WRITE,
                        ResourceScope.tenant(TENANT.id().value())));
    }

    /** Shadow mode extends the same way to management as it already does to reads. */
    @Test
    void underShadowModeMembershipAloneAuthorisesManagement() {
        TenantAccessPolicy policy = policy(actor(Set.of(), Map.of("keycloak-organization-a", Set.of())));

        assertThatNoException()
                .isThrownBy(() -> policy.requireTenantManagement(
                        TENANT,
                        Capability.BRAND_WRITE,
                        ResourceScope.tenant(TENANT.id().value())));
    }

    @Test
    void underEnforcementMembershipAloneNoLongerAuthorisesARead() {
        // The narrowing ADR 0025 exists for. Organization membership used to be
        // the whole read rule, which meant a single-location employee could read
        // every location's orders and revenue in the tenant.
        TenantAccessPolicy policy =
                enforcingPolicy(actor(Set.of(), Map.of("keycloak-organization-a", Set.of("tenant-viewer"))), denyAll());

        assertThatThrownBy(() -> policy.requireTenantRead(TENANT))
                .isInstanceOf(uz.horecaos.platform.iam.api.AuthorizationService.AccessDeniedException.class);
    }

    @Test
    void underEnforcementAMemberHoldingTheCapabilityIsStillAllowed() {
        TenantAccessPolicy policy = enforcingPolicy(
                actor(Set.of(), Map.of("keycloak-organization-a", Set.of("tenant-viewer"))), allowAll());

        assertThatNoException().isThrownBy(() -> policy.requireTenantRead(TENANT));
    }

    @Test
    void underEnforcementTenantMembershipIsStillCheckedBeforeTheCapability() {
        // Both halves of the conjunction, in the order that matters: a grant is
        // never a substitute for belonging to the tenant, so a principal from
        // another organization is refused before its capabilities are consulted.
        TenantAccessPolicy policy =
                enforcingPolicy(actor(Set.of(), Map.of("another-organization", Set.of("tenant-owner"))), allowAll());

        assertThatThrownBy(() -> policy.requireTenantRead(TENANT)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    /** Shadow mode, which the build no longer runs in but still supports. */
    private static TenantAccessPolicy policy(AuthenticatedActor actor) {
        return new TenantAccessPolicy(() -> actor, denyAll(), false);
    }

    private static TenantAccessPolicy enforcingPolicy(
            AuthenticatedActor actor, uz.horecaos.platform.iam.api.AuthorizationService authorization) {
        return new TenantAccessPolicy(() -> actor, authorization, true);
    }

    /** A resolver standing in for a principal who has been granted a role. */
    private static uz.horecaos.platform.iam.api.AuthorizationService allowAll() {
        return new uz.horecaos.platform.iam.api.AuthorizationService() {
            @Override
            public boolean has(
                    String subject,
                    uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                return true;
            }

            @Override
            public void require(
                    String subject,
                    uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                // Held, so nothing to refuse.
            }

            @Override
            public uz.horecaos.platform.iam.api.CapabilityView viewFor(String subject, java.util.UUID tenantId) {
                return new uz.horecaos.platform.iam.api.CapabilityView(
                        subject, "", java.util.Set.of(), java.util.List.of(), 0);
            }
        };
    }

    private static AuthenticatedActor actor(Set<String> globalRoles, Map<String, Set<String>> organizationRoles) {
        return new AuthenticatedActor("keycloak-user-42", globalRoles, organizationRoles);
    }

    /**
     * A resolver that grants nothing, so a test using it exercises the ADR 0003
     * rule alone rather than accidentally passing on a capability.
     */
    private static uz.horecaos.platform.iam.api.AuthorizationService denyAll() {
        return new uz.horecaos.platform.iam.api.AuthorizationService() {
            @Override
            public boolean has(
                    String subject,
                    uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                return false;
            }

            @Override
            public void require(
                    String subject,
                    uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                throw new uz.horecaos.platform.iam.api.AuthorizationService.AccessDeniedException(capability, scope);
            }

            @Override
            public uz.horecaos.platform.iam.api.CapabilityView viewFor(String subject, java.util.UUID tenantId) {
                return new uz.horecaos.platform.iam.api.CapabilityView(
                        subject, "", java.util.Set.of(), java.util.List.of(), 0);
            }
        };
    }
}
