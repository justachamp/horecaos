package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.api.ItemDisplayLookup;
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
                port, named("Lagman"), Duration.ofMinutes(30));

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
                port, named("Lagman"), Duration.ofMinutes(30));

        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, true, "RESTOCKED", Instant.now()));

        assertThat(port.calls()).isEmpty();
    }

    @Test
    void anUnresolvableNameRendersAsAnEmptyStringRatherThanFailing() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        InventoryOperationsAlertTrigger trigger = new InventoryOperationsAlertTrigger(
                port, named(null), Duration.ofMinutes(30));

        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, false, "SOLD_OUT", Instant.now()));

        assertThat(port.calls())
                .singleElement()
                .satisfies(call -> assertThat(call.variables()).containsEntry("itemName", ""));
    }

    /**
     * {@link ItemDisplayLookup} stopped being a functional interface when the
     * cart's batch read added {@code displayNames}, so these can no longer be
     * lambdas. The batch method throws rather than answering: this trigger
     * handles one variant per event, and a call to it here would mean the
     * trigger had started reading names it was not given an event about.
     */
    private static ItemDisplayLookup named(@Nullable String displayName) {
        return new ItemDisplayLookup() {
            @Override
            public Optional<String> displayName(UUID tenantId, UUID variantId) {
                return Optional.ofNullable(displayName);
            }

            @Override
            public Map<UUID, String> displayNames(UUID tenantId, Set<UUID> variantIds) {
                throw new UnsupportedOperationException("one event names one variant");
            }
        };
    }
}
