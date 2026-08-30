package uz.qoida.platform.integration.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import uz.qoida.platform.ordering.api.OrderAwaitingApproval;
import uz.qoida.platform.ordering.api.OrderCancelled;
import uz.qoida.platform.ordering.api.OrderConfirmed;
import uz.qoida.platform.ordering.api.OrderExpired;
import uz.qoida.platform.ordering.api.OrderReceived;
import uz.qoida.platform.ordering.api.OrderRejected;
import uz.qoida.platform.tenancy.api.BrandCreated;
import uz.qoida.platform.tenancy.api.BrandId;
import uz.qoida.platform.tenancy.api.LocationCreated;
import uz.qoida.platform.tenancy.api.LocationId;
import uz.qoida.platform.tenancy.api.TenancyEvent;
import uz.qoida.platform.tenancy.api.TenantCreated;
import uz.qoida.platform.tenancy.api.TenantId;

/**
 * ADR 0032: a producer serializes from a version-specific DTO and that output
 * must validate against the published schema. A payload change that breaks the
 * contract fails here rather than in a consumer.
 */
class EventSchemaValidationTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120402");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120403");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120404");

    private static final UUID ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120405");
    private static final Instant ORDERED_AT = Instant.parse("2026-08-22T12:00:00Z");

    /**
     * ADR 0019 order facts, serialized exactly as the producer does.
     *
     * <p>Every one is validated against its published schema, so a payload that
     * gained a field, lost one, or started carrying something the schema forbids
     * fails here rather than in a consumer.
     */
    static Stream<Object> orderingSamples() {
        return Stream.of(
                new OrderReceived(UUID.randomUUID(), new TenantId(TENANT), ORDER, ORDERED_AT,
                        BRAND, LOCATION, "STOREFRONT", "0822-014", "PICKUP",
                        "RESTAURANT_APPROVAL", null, 0, "RECEIVED", 1, "UZS", 100_000L, 2),
                new OrderAwaitingApproval(UUID.randomUUID(), new TenantId(TENANT), ORDER,
                        ORDERED_AT, BRAND, LOCATION, "QOIDA_OPERATIONS",
                        ORDERED_AT.plusSeconds(300), "AUTO_REJECT", "AWAITING_APPROVAL", 2),
                new OrderConfirmed(UUID.randomUUID(), new TenantId(TENANT), ORDER, ORDERED_AT,
                        BRAND, LOCATION, "RESTAURANT_APPROVAL", "QOIDA_OPERATIONS", ORDERED_AT,
                        "UZS", 100_000L, "CONFIRMED", 3),
                new OrderRejected(UUID.randomUUID(), new TenantId(TENANT), ORDER, ORDERED_AT,
                        BRAND, LOCATION, "QOIDA_OPERATIONS", "KITCHEN_CLOSED", "REJECTED", 3),
                new OrderExpired(UUID.randomUUID(), new TenantId(TENANT), ORDER, ORDERED_AT,
                        BRAND, LOCATION, ORDERED_AT.plusSeconds(300), "EXPIRED", 3),
                new OrderCancelled(UUID.randomUUID(), new TenantId(TENANT), ORDER, ORDERED_AT,
                        BRAND, LOCATION, "CUSTOMER", "CUSTOMER_CHANGED_MIND", "AWAITING_APPROVAL",
                        "CANCELLED", 3));
    }

    @ParameterizedTest(name = "{0} ordering payload validates against its schema")
    @MethodSource("orderingSamples")
    void orderingPayloadValidatesAgainstSchema(Object event) throws Exception {
        var ordering = (uz.qoida.platform.ordering.api.OrderingEvent) event;
        EventContract contract = EventCatalog.require(ordering.eventType(), ordering.eventVersion());
        JsonNode payload = MAPPER.valueToTree(ordering.payload());

        Set<ValidationMessage> errors = schema(contract).validate(payload);

        assertThat(errors)
                .as("payload of %s does not satisfy %s: %s", contract.key(), contract.schemaPath(),
                        payload)
                .isEmpty();
    }

    static Stream<TenancyEvent> samples() {
        return Stream.of(
                new TenantCreated(
                        UUID.randomUUID(), new TenantId(TENANT), Instant.parse("2026-08-20T10:00:00Z"),
                        "acme", "Acme Foods LLC", "Acme", "UZS", "Asia/Tashkent",
                        "PROVISIONING", "TENANT_SHARED"),
                new BrandCreated(
                        UUID.randomUUID(), new TenantId(TENANT), new BrandId(BRAND),
                        Instant.parse("2026-08-20T10:00:01Z"),
                        "ACME_BURGERS", "acme-burgers", "Acme Burgers", "ACTIVE"),
                new LocationCreated(
                        UUID.randomUUID(), new TenantId(TENANT), new BrandId(BRAND), new LocationId(LOCATION),
                        Instant.parse("2026-08-20T10:00:02Z"),
                        "TASHKENT_01", "tashkent-01", "Chilonzor", "Asia/Tashkent", "ACTIVE"));
    }

    @ParameterizedTest(name = "{0} payload validates against its schema")
    @MethodSource("samples")
    void payloadValidatesAgainstSchema(TenancyEvent event) throws Exception {
        EventContract contract = EventCatalog.require(event.eventType(), event.eventVersion());
        JsonNode payload = MAPPER.valueToTree(event.payload());

        Set<ValidationMessage> errors = schema(contract).validate(payload);

        assertThat(errors)
                .as("payload of %s does not satisfy %s: %s", contract.key(), contract.schemaPath(), payload)
                .isEmpty();
    }

    @Test
    void schemaRejectsAnUndeclaredField() throws Exception {
        EventContract contract = EventCatalog.require("BrandCreated", 1);
        JsonNode payload = MAPPER.readTree("""
                {"brandId":"018f6f4e-899d-7b1c-a8cf-0242ac120403","status":"ACTIVE","ownerEmail":"a@b.c"}
                """);

        assertThat(schema(contract).validate(payload))
                .as("additionalProperties must be closed so a stray field cannot ship silently")
                .isNotEmpty();
    }

    @Test
    void schemaRejectsAnUnknownEnumValue() throws Exception {
        EventContract contract = EventCatalog.require("BrandCreated", 1);
        JsonNode payload = MAPPER.readTree("""
                {"brandId":"018f6f4e-899d-7b1c-a8cf-0242ac120403","status":"NOT_A_STATUS"}
                """);

        assertThat(schema(contract).validate(payload)).isNotEmpty();
    }

    private JsonSchema schema(EventContract contract) throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(source).as("schema %s must exist", contract.schemaPath()).isNotNull();
            return factory.getSchema(source, SchemaValidatorsConfig.builder().build());
        }
    }
}
