package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.TenantOrganizationDirectory;
import uz.horecaos.platform.iam.api.TenantRoleCatalog;
import uz.horecaos.platform.iam.application.GrantManagementService;

/** The two reads added for staff-and-access.md's Люди and Должности screens, and the IA 7.3 debugger. */
class GrantControllerTests {

    private final GrantManagementService grants = mock(GrantManagementService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final GrantController controller = new GrantController(
            grants, authorization, mock(CurrentActor.class), mock(TenantOrganizationDirectory.class));

    @Test
    void listDefaultsToActiveOnlyAndForwardsTheFlagOtherwise() {
        UUID tenantId = UUID.randomUUID();

        controller.list(tenantId, false);
        verify(grants).listForTenant(tenantId, false);

        controller.list(tenantId, true);
        verify(grants).listForTenant(tenantId, true);
    }

    @Test
    void rolesReturnsTheStaticTenantRoleCatalogWithoutTouchingTheDatabase() {
        List<TenantRoleCatalog.RoleDescriptor> result = controller.roles(UUID.randomUUID());

        assertThat(result).isEqualTo(TenantRoleCatalog.tenantVisible());
    }

    @Test
    void debugAccessReusesTheSameViewForServiceAnySessionContextCallReuses() {
        UUID tenantId = UUID.randomUUID();
        CapabilityView view = new CapabilityView("keycloak|colleague", tenantId.toString(), Set.of(), List.of(), 1L);
        when(authorization.viewFor("keycloak|colleague", tenantId)).thenReturn(view);

        var response = controller.debugAccess("keycloak|colleague", tenantId, null, null, null);

        assertThat(response.view()).isSameAs(view);
        assertThat(response.requestedCapability()).isNull();
        assertThat(response.granted())
                .as("no capability was asked about, so there is nothing to answer yes or no to")
                .isNull();
    }

    @Test
    void debugAccessAnswersWhetherTheNamedCapabilityIsHeldAtTheNamedScope() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        when(authorization.viewFor(eq("keycloak|colleague"), eq(tenantId)))
                .thenReturn(new CapabilityView("keycloak|colleague", tenantId.toString(), Set.of(), List.of(), 1L));
        when(authorization.has("keycloak|colleague", Capability.CATALOG_AUTHOR, ResourceScope.brand(tenantId, brandId)))
                .thenReturn(true);

        var response = controller.debugAccess("keycloak|colleague", tenantId, brandId, null, Capability.CATALOG_AUTHOR);

        assertThat(response.requestedCapability()).isEqualTo(Capability.CATALOG_AUTHOR);
        assertThat(response.granted()).isTrue();
    }

    @Test
    void debugAccessFallsBackToPlatformScopeWithNoTenantId() {
        when(authorization.viewFor(eq("keycloak|colleague"), any()))
                .thenReturn(new CapabilityView("keycloak|colleague", null, Set.of(), List.of(), 1L));
        when(authorization.has("keycloak|colleague", Capability.PLATFORM_ADMIN, ResourceScope.platform()))
                .thenReturn(false);

        var response = controller.debugAccess("keycloak|colleague", null, null, null, Capability.PLATFORM_ADMIN);

        assertThat(response.granted()).isFalse();
    }
}
