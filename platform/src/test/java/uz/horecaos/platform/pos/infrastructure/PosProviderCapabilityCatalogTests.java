package uz.horecaos.platform.pos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.api.PosCapability;

/**
 * The vendor-ceiling declaration gap-map row 10.8a's capability-assignment
 * picker reads (through {@code ProviderCapabilityReconciliationService
 * #declaredCapabilities}) to default a POS binding to "every capability the
 * installation's provider declares".
 */
class PosProviderCapabilityCatalogTests {

    @Test
    @DisplayName("category is POS")
    void categoryIsPos() {
        var catalog = new PosProviderCapabilityCatalog(List.of(new FakePosAdapter()));

        assertThat(catalog.category()).isEqualTo(ProviderCategory.POS);
    }

    @Test
    @DisplayName(
            "a wired adapter's declaration mirrors PosAdapter#declaredCapabilities exactly, not a broader or narrower set")
    void declarationMirrorsTheAdaptersOwnCeiling() {
        var adapter = new FakePosAdapter();
        var catalog = new PosProviderCapabilityCatalog(List.of(adapter));

        Optional<ProviderCapabilityCatalog.Declaration> declaration =
                catalog.declarationFor(FakePosAdapter.PROVIDER_TYPE);

        assertThat(declaration).isPresent();
        assertThat(declaration.orElseThrow().capabilities())
                .containsExactlyInAnyOrderElementsOf(adapter.declaredCapabilities().stream()
                        .map(PosCapability::code)
                        .toList());
    }

    @Test
    @DisplayName("no adapter for the requested provider type is an empty declaration, never a guess")
    void noAdapterIsEmpty() {
        var catalog = new PosProviderCapabilityCatalog(List.of(new FakePosAdapter()));

        assertThat(catalog.declarationFor("some-other-till")).isEmpty();
    }
}
