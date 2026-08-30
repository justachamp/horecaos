package uz.horecaos.platform.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * ADR 0030: the scope chain is the only definition of precedence, and ADR 0025
 * reuses {@code covers} for capability scope checks, so both are pinned here.
 */
class ResourceScopeTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120003");

    @Test
    void aLocationChainRunsFromLocationToPlatform() {
        assertThat(ResourceScope.location(TENANT, BRAND, LOCATION).chain())
                .extracting(ResourceScope::type)
                .containsExactly(ScopeType.LOCATION, ScopeType.BRAND, ScopeType.TENANT, ScopeType.PLATFORM);
    }

    @Test
    void aTenantChainSkipsBrandAndLocation() {
        assertThat(ResourceScope.tenant(TENANT).chain())
                .extracting(ResourceScope::type)
                .containsExactly(ScopeType.TENANT, ScopeType.PLATFORM);
    }

    @Test
    void thePlatformChainIsJustThePlatform() {
        assertThat(ResourceScope.platform().chain()).containsExactly(ResourceScope.platform());
    }

    @Test
    void aBroaderScopeCoversANarrowerOne() {
        ResourceScope location = ResourceScope.location(TENANT, BRAND, LOCATION);

        assertThat(ResourceScope.tenant(TENANT).covers(location)).isTrue();
        assertThat(ResourceScope.brand(TENANT, BRAND).covers(location)).isTrue();
        assertThat(ResourceScope.platform().covers(location)).isTrue();
    }

    @Test
    void aNarrowerScopeDoesNotCoverABroaderOne() {
        assertThat(ResourceScope.location(TENANT, BRAND, LOCATION).covers(ResourceScope.tenant(TENANT)))
                .isFalse();
    }

    @Test
    void aSiblingScopeIsNotCovered() {
        UUID otherBrand = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1200ff");

        assertThat(ResourceScope.brand(TENANT, otherBrand).covers(ResourceScope.location(TENANT, BRAND, LOCATION)))
                .as("a grant at one brand must never reach a sibling brand")
                .isFalse();
    }

    @Test
    void anotherTenantIsNeverCovered() {
        UUID otherTenant = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1200fe");

        assertThat(ResourceScope.tenant(otherTenant).covers(ResourceScope.location(TENANT, BRAND, LOCATION)))
                .isFalse();
    }

    @Test
    void anIncompleteAncestryIsRejected() {
        assertThatThrownBy(() -> new ResourceScope(ScopeType.LOCATION, TENANT, null, LOCATION))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("brand ID");
    }

    @Test
    void identifiersBelowTheScopeTypeAreRejected() {
        assertThatThrownBy(() -> new ResourceScope(ScopeType.TENANT, TENANT, BRAND, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be absent");
    }
}
