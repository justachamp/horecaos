package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.Capability;

class CapabilityRegistryControllerTests {

    @Test
    void listsEveryCapabilityTheEnumDeclaresWithItsWireShape() {
        var descriptors = new CapabilityRegistryController().list();

        assertThat(descriptors)
                .as("the registry must be the whole enum, not a hand-copied subset that drifts")
                .hasSize(Capability.values().length);

        assertThat(descriptors)
                .extracting(CapabilityRegistryController.CapabilityDescriptor::code)
                .as("codes are unique and match the wire value a role/grant/policy stores")
                .containsExactlyInAnyOrder(java.util.Arrays.stream(Capability.values())
                        .map(Capability::code)
                        .toArray(String[]::new));

        assertThat(descriptors)
                .filteredOn(descriptor -> descriptor.code().equals(Capability.TENANT_READ.code()))
                .singleElement()
                .satisfies(descriptor -> {
                    assertThat(descriptor.resourceType()).isEqualTo("tenant");
                    assertThat(descriptor.action()).isEqualTo("read");
                });

        assertThat(descriptors).allSatisfy(descriptor -> {
            assertThat(descriptor.code()).isNotBlank();
            assertThat(descriptor.resourceType()).isNotBlank();
            assertThat(descriptor.action()).isNotBlank();
        });
    }
}
