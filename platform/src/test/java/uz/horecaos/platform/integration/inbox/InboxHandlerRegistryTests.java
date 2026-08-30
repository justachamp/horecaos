package uz.horecaos.platform.integration.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;
import uz.horecaos.platform.integration.api.InboxHandler;
import uz.horecaos.platform.integration.events.EventCatalog;

/**
 * Which consumers a listener may offer a record to (ADR 0005, ADR 0032).
 *
 * <p>The fan-out was harmless while there was one topic and became a defect the
 * moment there were two: {@code InboxExecutor} treats a missing handler as a
 * permanent contract failure and dead-letters the row, so offering a
 * {@code fulfillment.commands} record to the tenancy projection would write a
 * dead letter for every command, for a consumer that was never meant to see it.
 */
class InboxHandlerRegistryTests {

    @Test
    @DisplayName("a topic's record is offered only to consumers registered for that topic")
    void consumersAreSelectedByTheCataloguedTopic() {
        InboxHandlerRegistry registry = new InboxHandlerRegistry(List.of(
                handler("tenancy-projection", "TenantCreated", 1),
                handler("delivery-reconciliation", "ShipmentReconciliationRequested", 1)));

        assertThat(registry.consumerNamesFor(EventCatalog.TENANCY_EVENTS_TOPIC)).containsExactly("tenancy-projection");
        assertThat(registry.consumerNamesFor(EventCatalog.FULFILLMENT_COMMANDS_TOPIC))
                .containsExactly("delivery-reconciliation");
    }

    @Test
    @DisplayName("an uncatalogued handler keeps the behaviour it had before topics were filtered")
    void anUncataloguedHandlerIsOfferedEveryRecord() {
        InboxHandlerRegistry registry =
                new InboxHandlerRegistry(List.of(handler("controlled-consumer", "ControlledCommandIssued", 1)));

        // The controlled route's type is deliberately absent from the catalogue,
        // and a filter that silently stopped feeding it would make the ADR 0007
        // exit-criteria suite pass for the wrong reason.
        assertThat(registry.consumerNamesFor("anything.at.all")).containsExactly("controlled-consumer");
    }

    @Test
    void twoHandlersForOneKeyFailAtStartup() {
        assertThatThrownBy(() -> new InboxHandlerRegistry(
                        List.of(handler("a", "TenantCreated", 1), handler("a", "TenantCreated", 1))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static InboxHandler<Map<String, Object>> handler(String consumer, String eventType, int version) {

        return new InboxHandler<>() {
            @Override
            public String consumerName() {
                return consumer;
            }

            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public int eventVersion() {
                return version;
            }

            @SuppressWarnings("unchecked")
            @Override
            public Class<Map<String, Object>> payloadType() {
                return (Class<Map<String, Object>>) (Class<?>) Map.class;
            }

            @Override
            public void handle(ExternalEventEnvelope<Map<String, Object>> event) {
                // Registration is what is under test; nothing is dispatched here.
            }
        };
    }
}
