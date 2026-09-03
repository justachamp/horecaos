package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.events.EventCatalog;

class EventContractControllerTests {

    @Test
    void listsEveryRegisteredContractWithItsWireShape() {
        var contracts = new EventContractController().list();

        assertThat(contracts)
                .as("the registry must be the whole EventCatalog, not a hand-copied subset that drifts")
                .hasSize(EventCatalog.all().size());

        assertThat(contracts)
                .filteredOn(contract -> contract.eventType().equals("TenantCreated"))
                .singleElement()
                .satisfies(contract -> {
                    assertThat(contract.producingModule()).isEqualTo("tenancy");
                    assertThat(contract.eventVersion()).isEqualTo(1);
                    assertThat(contract.retention()).isEqualTo("BUSINESS_FACT");
                    assertThat(contract.classification()).isEqualTo("INTERNAL");
                });

        assertThat(contracts).allSatisfy(contract -> {
            assertThat(contract.eventType()).isNotBlank();
            assertThat(contract.topic()).isNotBlank();
            assertThat(contract.description()).isNotBlank();
        });
    }
}
