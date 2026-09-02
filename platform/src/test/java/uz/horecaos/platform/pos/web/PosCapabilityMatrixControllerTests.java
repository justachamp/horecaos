package uz.horecaos.platform.pos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.api.PosCapability;

class PosCapabilityMatrixControllerTests {

    @Test
    void reflectsWhateverAdaptersAreActuallyWiredRatherThanAHandMaintainedList() {
        var controller = new PosCapabilityMatrixController(List.of(new FakePosAdapter()));

        var matrix = controller.matrix();

        assertThat(matrix).singleElement().satisfies(entry -> {
            assertThat(entry.providerType()).isEqualTo(FakePosAdapter.PROVIDER_TYPE);
            assertThat(entry.declaredCapabilities())
                    .as("the fake declares everything on purpose; the matrix must not drop or reorder any")
                    .containsExactlyInAnyOrder(PosCapability.values());
        });
    }

    @Test
    void anEmptyAdapterListIsAnEmptyMatrixRatherThanAnError() {
        var controller = new PosCapabilityMatrixController(List.of());

        assertThat(controller.matrix()).isEmpty();
    }
}
