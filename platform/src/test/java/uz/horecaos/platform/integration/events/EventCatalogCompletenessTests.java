package uz.horecaos.platform.integration.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import uz.horecaos.platform.media.api.MediaEvent;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.tenancy.api.TenancyEvent;

/**
 * ADR 0032: an event must have a catalogue entry, a schema file, and a
 * documentation row before its producer ships. These tests make the three
 * inseparable.
 */
class EventCatalogCompletenessTests {

    private static final String EVENT_CATALOGUE_DOC = "docs/domains/events.md";

    static List<EventContract> contracts() {
        return List.copyOf(EventCatalog.all());
    }

    @Test
    void everyPublishableTenancyEventIsRegistered() {
        List<String> registered = EventCatalog.all().stream()
                .map(EventContract::eventType)
                .toList();

        List<String> publishable = Arrays.stream(TenancyEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .toList();

        assertThat(registered)
                .as("every permitted TenancyEvent needs an ADR 0032 catalogue entry")
                .containsAll(publishable);
    }

    @Test
    void everyPublishableOrderingEventIsRegistered() {
        List<String> registered = EventCatalog.all().stream()
                .map(EventContract::eventType)
                .toList();

        List<String> publishable = Arrays.stream(OrderingEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .toList();

        assertThat(publishable)
                .as("the scan must actually find the ADR 0019 events it claims to check")
                .isNotEmpty();
        assertThat(registered)
                .as("every permitted OrderingEvent needs an ADR 0032 catalogue entry")
                .containsAll(publishable);
    }

    @Test
    void everyPublishableMediaEventIsRegistered() {
        List<String> registered = EventCatalog.all().stream()
                .map(EventContract::eventType)
                .toList();

        List<String> publishable = Arrays.stream(MediaEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .toList();

        assertThat(publishable)
                .as("the scan must actually find the ADR 0010 events it claims to check")
                .isNotEmpty();
        assertThat(registered)
                .as("every permitted MediaEvent needs an ADR 0032 catalogue entry")
                .containsAll(publishable);
    }

    @ParameterizedTest(name = "{0} has a schema file")
    @MethodSource("contracts")
    void schemaFileExists(EventContract contract) throws IOException {
        try (InputStream schema = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(schema)
                    .as("missing schema file %s for %s", contract.schemaPath(), contract.key())
                    .isNotNull();
        }
    }

    @ParameterizedTest(name = "{0} is documented in the event catalogue")
    @MethodSource("contracts")
    void documentedInEventCatalogue(EventContract contract) throws IOException {
        String catalogue = Files.readString(Path.of(EVENT_CATALOGUE_DOC), StandardCharsets.UTF_8);

        assertThat(catalogue)
                .as("%s must appear in %s", contract.eventType(), EVENT_CATALOGUE_DOC)
                .contains(contract.eventType());
    }

    @ParameterizedTest(name = "{0} declares a schema path matching its topic and version")
    @MethodSource("contracts")
    void schemaPathFollowsConvention(EventContract contract) {
        assertThat(contract.schemaPath())
                .isEqualTo("events/%s/%s.v%d.schema.json"
                        .formatted(contract.topic(), contract.eventType(), contract.eventVersion()));
    }

    @Test
    void unregisteredEventIsRejected() {
        assertThatThrownBy(() -> EventCatalog.require("SomethingUndocumented", 1))
                .isInstanceOf(EventCatalog.UnregisteredEventException.class)
                .hasMessageContaining("ADR 0032");
    }

    @Test
    void unregisteredVersionOfARegisteredEventIsRejected() {
        assertThatThrownBy(() -> EventCatalog.require("TenantCreated", 2))
                .isInstanceOf(EventCatalog.UnregisteredEventException.class);
    }
}
