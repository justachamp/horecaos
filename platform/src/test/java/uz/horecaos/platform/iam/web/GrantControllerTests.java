package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.TenantOrganizationDirectory;
import uz.horecaos.platform.iam.api.TenantRoleCatalog;
import uz.horecaos.platform.iam.application.GrantManagementService;

/** The two reads added for staff-and-access.md's Люди and Должности screens. */
class GrantControllerTests {

    private final GrantManagementService grants = mock(GrantManagementService.class);
    private final GrantController controller = new GrantController(
            grants,
            mock(AuthorizationService.class),
            mock(CurrentActor.class),
            mock(TenantOrganizationDirectory.class));

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
}
