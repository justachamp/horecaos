package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;

/** {@link InventoryOperationsAlertTrigger}. */
class InventoryOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();

    @Test
    void goingUnavailableFansOutNamingTheItem() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        InventoryOperationsAlertTrigger trigger = new InventoryOperationsAlertTrigger(
                port, (tenantId, variantId) -> Optional.of("Lagman"), Duration.ofMinutes(30));

        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, false, "SOLD_OUT", Instant.now()));

        assertThat(port.calls()).hasSize(1);
        RecordingOperationsAlertPort.Call call = port.calls().get(0);
        assertThat(call.eventClass()).isEqualTo(InventoryOperationsAlertTrigger.ITEM_86D);
        assertThat(call.subjectType()).isEqualTo("Variant");
        assertThat(call.subjectId()).isEqualTo(VARIANT);
        assertThat(call.variables()).containsEntry("itemName", "Lagman").containsEntry("reasonCode", "SOLD_OUT");
    }

    @Test
    void comingBackAvailableRaisesNoAlert() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        InventoryOperationsAlertTrigger trigger = new InventoryOperationsAlertTrigger(
                port, (tenantId, variantId) -> Optional.of("Lagman"), Duration.ofMinutes(30));

        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, true, "RESTOCKED", Instant.now()));

        assertThat(port.calls()).isEmpty();
    }

    @Test
    void anUnresolvableNameRendersAsAnEmptyStringRatherThanFailing() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        InventoryOperationsAlertTrigger trigger = new InventoryOperationsAlertTrigger(
                port, (tenantId, variantId) -> Optional.empty(), Duration.ofMinutes(30));

        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, false, "SOLD_OUT", Instant.now()));

        assertThat(port.calls())
                .singleElement()
                .satisfies(call -> assertThat(call.variables()).containsEntry("itemName", ""));
    }
}
