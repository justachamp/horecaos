package uz.horecaos.platform.integration.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.customers.api.CustomersEvent;
import uz.horecaos.platform.customers.api.LeadAssignedToLocation;
import uz.horecaos.platform.customers.api.LeadConverted;
import uz.horecaos.platform.customers.api.LeadRegistered;
import uz.horecaos.platform.customers.api.LeadStatusChanged;

/**
 * ADR 0111's four lead facts against ADR 0032 and ADR 0029: what each event's payload carries is
 * what its schema says, and nothing in it can be a phone number, a name or a note.
 *
 * <p>The tenancy and ordering payloads have a single classification test of their own; this is the
 * same rule for the customers module, where the data one field away from a lead is exactly the data
 * ADR 0029 protects. The assertions compare the payload record's components with the schema's
 * properties both ways -- a field added to the record and not the schema, or the schema and not the
 * record, fails here -- and then run the name heuristic the whole platform uses over them.
 */
class CustomersEventContractTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID LEAD = UUID.randomUUID();

    private static List<CustomersEvent> samples() {
        Instant now = Instant.parse("2026-10-07T10:00:00Z");
        return List.of(
                new LeadRegistered(UUID.randomUUID(), TENANT, LEAD, UUID.randomUUID(), "B2B_CATERING_ENQUIRY", now),
                new LeadStatusChanged(UUID.randomUUID(), TENANT, LEAD, "NEW", "CONTACTED", now),
                new LeadAssignedToLocation(UUID.randomUUID(), TENANT, LEAD, UUID.randomUUID(), now),
                new LeadConverted(UUID.randomUUID(), TENANT, LEAD, UUID.randomUUID(), null, now),
                new LeadConverted(UUID.randomUUID(), TENANT, LEAD, null, UUID.randomUUID(), now));
    }

    @Test
    void everyPayloadRecordMatchesItsSchemaPropertyForProperty() throws Exception {
        for (CustomersEvent event : samples()) {
            EventContract contract = EventCatalog.require(event.eventType(), event.eventVersion());
            JsonNode schema = schemaOf(contract);

            Set<String> components = new TreeSet<>();
            for (RecordComponent component : event.payload().getClass().getRecordComponents()) {
                components.add(component.getName());
            }
            Set<String> properties = new TreeSet<>();
            schema.path("properties").fieldNames().forEachRemaining(properties::add);

            assertThat(components)
                    .as("%s: the payload's fields and the schema's properties are one set", event.eventType())
                    .isEqualTo(properties);
            assertThat(schema.path("additionalProperties").asBoolean(true))
                    .as("%s: a consumer can rely on the shape", event.eventType())
                    .isFalse();
            assertThat(contract.topic()).isEqualTo("customers.events");
            assertThat(contract.partitionKey())
                    .as("%s: one lead's facts stay in order", event.eventType())
                    .isEqualTo("leadId");
        }
    }

    @Test
    void everyRequiredPropertyIsPresentInASerializedSample() throws Exception {
        for (CustomersEvent event : samples()) {
            JsonNode schema = schemaOf(EventCatalog.require(event.eventType(), event.eventVersion()));
            JsonNode payload = MAPPER.readTree(MAPPER.writeValueAsString(event.payload()));

            List<String> missing = new ArrayList<>();
            schema.path("required").forEach(required -> {
                if (payload.path(required.asText()).isMissingNode()
                        || payload.path(required.asText()).isNull()) {
                    missing.add(required.asText());
                }
            });
            assertThat(missing)
                    .as("%s: every required property a producer must send is sent", event.eventType())
                    .isEmpty();
        }
    }

    @Test
    void noPayloadFieldCanBePersonalData() {
        List<String> violations = new ArrayList<>();
        for (CustomersEvent event : samples()) {
            for (RecordComponent component : event.payload().getClass().getRecordComponents()) {
                if (ChangeDocuments.isProtected(component.getName())) {
                    violations.add(event.eventType() + "." + component.getName());
                }
            }
        }
        assertThat(violations)
                .as("a field whose name suggests a phone, a name or a note cannot be on a topic (ADR 0029)")
                .isEmpty();
    }

    @Test
    void aConversionNamesExactlyOneTarget() {
        Instant now = Instant.now();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new LeadConverted(UUID.randomUUID(), TENANT, LEAD, null, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new LeadConverted(UUID.randomUUID(), TENANT, LEAD, UUID.randomUUID(), UUID.randomUUID(), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFourEventsAreTheWholeOfTheSealedFamily() {
        assertThat(Arrays.stream(CustomersEvent.class.getPermittedSubclasses()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "LeadRegistered", "LeadStatusChanged", "LeadAssignedToLocation", "LeadConverted");
    }

    private JsonNode schemaOf(EventContract contract) throws Exception {
        try (InputStream schema = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(schema).as("the schema file %s", contract.schemaPath()).isNotNull();
            return MAPPER.readTree(schema);
        }
    }
}
