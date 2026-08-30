package uz.horecaos.platform.integration.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.integration.outbox.ShipmentReconciliationOutbox.Command;
import uz.horecaos.platform.integration.outbox.ShipmentReconciliationOutbox.Settlement;

/**
 * ADR 0032 for ADR 0007's command path: what the producer actually serializes
 * must satisfy the published schema.
 *
 * <p>Serialized through the same mapper family the outbox uses rather than
 * hand-written JSON, because a payload that a person typed proves the schema is
 * satisfiable and not that the producer satisfies it. The two disagree exactly
 * when somebody renames a record component.
 */
class FulfillmentCommandSchemaTests {

    private static final ObjectMapper VALIDATION_MAPPER = new ObjectMapper();
    private static final JsonMapper PRODUCER_MAPPER = JsonMapper.builder().build();

    private static final UUID OPERATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f11");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f12");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f13");

    @Test
    void theCommandProducerSatisfiesItsSchema() throws Exception {
        Command command = new Command(OPERATION, BINDING, BRAND, null, "yandex-delivery",
                "CREATE_ON_DEMAND_SHIPMENT", "claim-9911", "READ_TIMEOUT");

        assertThat(errors("ShipmentReconciliationRequested", command)).isEmpty();
    }

    @Test
    void theSettlementProducerSatisfiesItsSchema() throws Exception {
        Settlement settlement = Settlement.at(Instant.parse("2026-08-25T11:00:00Z"),
                OPERATION, BINDING, "yandex-delivery", "CREATE_ON_DEMAND_SHIPMENT",
                "claim-9911", "CONFIRMED", "SUCCESS", null, 2);

        assertThat(errors("ShipmentOutcomeReconciled", settlement)).isEmpty();
    }

    @Test
    void aCommandCarryingAContactIsRefusedByTheSchema() throws Exception {
        // ADR 0029, made checkable. A courier command is the most tempting place
        // in the platform to put a phone number, because the partner needs one —
        // and the partner gets it from an authorized call, never from a topic.
        JsonNode smuggled = VALIDATION_MAPPER.readTree("""
                {"operationCommandId":"%s","bindingId":"%s","brandId":"%s","locationId":null,
                 "providerType":"yandex-delivery","capability":"CANCEL_SHIPMENT",
                 "externalReference":"claim-9911","uncertainErrorCode":null,
                 "recipientPhone":"+998901234567"}
                """.formatted(OPERATION, BINDING, BRAND));

        assertThat(schema("ShipmentReconciliationRequested").validate(smuggled))
                .as("additionalProperties must be closed, or a field like this ships silently")
                .isNotEmpty();
    }

    @Test
    void anUnknownResolutionIsRefused() throws Exception {
        JsonNode payload = VALIDATION_MAPPER.readTree("""
                {"operationCommandId":"%s","bindingId":"%s","providerType":"yandex-delivery",
                 "capability":"CANCEL_SHIPMENT","externalReference":"claim-9911",
                 "resolution":"PROBABLY","providerStatus":"SUCCESS","errorCode":null,
                 "attempts":1,"reconciledAt":"2026-08-25T11:00:00Z"}
                """.formatted(OPERATION, BINDING));

        assertThat(schema("ShipmentOutcomeReconciled").validate(payload))
                .as("a fourth resolution would be read by consumers that only handle three")
                .isNotEmpty();
    }

    private Set<ValidationMessage> errors(String eventType, Object payload) throws Exception {
        JsonNode serialized = VALIDATION_MAPPER.readTree(PRODUCER_MAPPER.writeValueAsString(payload));
        return schema(eventType).validate(serialized);
    }

    private JsonSchema schema(String eventType) throws Exception {
        EventContract contract = EventCatalog.require(eventType, 1);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(source).as("schema %s must exist", contract.schemaPath()).isNotNull();
            return factory.getSchema(source, SchemaValidatorsConfig.builder().build());
        }
    }
}
