package uz.horecaos.platform.pos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;

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

    @Test
    @DisplayName(
            "ADR 0171: loyalty balance sync is four-valued per vendor, and a vendor with no assessed row is UNASSESSED"
                    + " rather than unsupported")
    void loyaltyBalanceSyncHasFourValues() {
        PosAdapter noRow = new NamedAdapter("clopos-like");
        PosAdapter supported = new NamedAdapter("assessed-supported");
        PosAdapter partial = new NamedAdapter("assessed-partial");
        PosAdapter unsupported = new NamedAdapter("assessed-unsupported");
        Map<String, CapabilitySupport> assessed = Map.of(
                "assessed-supported", CapabilitySupport.SUPPORTED,
                "assessed-partial", CapabilitySupport.PARTIAL,
                "assessed-unsupported", CapabilitySupport.UNSUPPORTED);
        var controller = new PosCapabilityMatrixController(
                List.of(noRow, supported, partial, unsupported),
                providerType -> assessed.containsKey(providerType)
                        ? Map.of(PosCapability.LOYALTY_BALANCE_SYNC, assessed.get(providerType))
                        : Map.of());

        Map<String, String> byVendor = new java.util.LinkedHashMap<>();
        controller.matrix().forEach(entry -> byVendor.put(entry.providerType(), entry.loyaltyBalanceSync()));

        assertThat(byVendor)
                .containsEntry("clopos-like", "UNASSESSED")
                .containsEntry("assessed-supported", "SUPPORTED")
                .containsEntry("assessed-partial", "PARTIAL")
                .containsEntry("assessed-unsupported", "UNSUPPORTED");
    }

    @Test
    @DisplayName("without a ceiling source every vendor is unassessed, which is the honest default")
    void everyVendorIsUnassessedWithoutACeiling() {
        var controller = new PosCapabilityMatrixController(List.of(new FakePosAdapter()));

        assertThat(controller.matrix())
                .singleElement()
                .satisfies(entry -> assertThat(entry.loyaltyBalanceSync()).isEqualTo("UNASSESSED"));
    }

    /** An adapter that declares nothing and answers nothing: only its name matters to the matrix. */
    private record NamedAdapter(String providerType) implements PosAdapter {

        @Override
        public Set<PosCapability> declaredCapabilities() {
            return EnumSet.noneOf(PosCapability.class);
        }

        @Override
        public CapabilitySnapshot discoverCapabilities(PosContext context) {
            return CapabilitySnapshot.empty();
        }

        @Override
        public CatalogRead readCatalog(PosContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AvailabilityRead readAvailability(PosContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceListRead discoverPaymentTypes(PosContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceListRead discoverDiscounts(PosContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExportResult exportOrder(PosContext context, OrderExport order) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RecoveryRead findExportedOrder(PosContext context, ExportProbe probe) {
            throw new UnsupportedOperationException();
        }

        @Override
        public uz.horecaos.platform.integration.api.provider.ProviderOutcome cancelExportedOrder(
                PosContext context, String externalOrderId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public uz.horecaos.platform.integration.api.provider.ProviderOutcome writeFiscalIdentifier(
                PosContext context, String externalReceiptId, String fiscalId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public uz.horecaos.platform.integration.api.provider.ProviderOutcome writeFulfillmentStatus(
                PosContext context, String externalReceiptId, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApprovalRead readApprovalStatus(PosContext context, String externalOrderId) {
            throw new UnsupportedOperationException();
        }
    }
}
