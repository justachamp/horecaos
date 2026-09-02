package uz.horecaos.platform.iam.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantRoleCatalogTests {

    @Test
    void neverIncludesThePlatformOnlyBundles() {
        assertThat(TenantRoleCatalog.tenantVisible())
                .as("staff-and-access.md §5: platform-admin and platform-support "
                        + "are never listed, never returned to a tenant client")
                .extracting(TenantRoleCatalog.RoleDescriptor::code)
                .doesNotContain(PlatformRole.PLATFORM_ADMIN.code(), PlatformRole.PLATFORM_SUPPORT.code());
    }

    @Test
    void listsExactlyTheEightTenantVisibleBundles() {
        assertThat(TenantRoleCatalog.tenantVisible())
                .extracting(TenantRoleCatalog.RoleDescriptor::code)
                .containsExactlyInAnyOrder(
                        "tenant-owner",
                        "tenant-admin",
                        "tenant-finance",
                        "support-agent",
                        "brand-manager",
                        "courier-dispatcher",
                        "location-manager",
                        "location-staff");
    }

    @Test
    void eachDescriptorCarriesItsRolesOwnCapabilitiesAndScope() {
        var owner = TenantRoleCatalog.tenantVisible().stream()
                .filter(descriptor -> descriptor.code().equals(PlatformRole.LOCATION_STAFF.code()))
                .findFirst()
                .orElseThrow();

        assertThat(owner.scopeType()).isEqualTo(ResourceScope.ScopeType.LOCATION);
        assertThat(owner.capabilities())
                .as("the descriptor's capability codes must be the wire value a grant/role stores")
                .containsExactlyInAnyOrderElementsOf(PlatformRole.LOCATION_STAFF.capabilities().stream()
                        .map(Capability::code)
                        .toList());
    }
}
