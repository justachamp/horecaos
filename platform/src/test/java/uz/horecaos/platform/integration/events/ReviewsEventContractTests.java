package uz.horecaos.platform.integration.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.reviews.api.RecoveryCaseOpened;
import uz.horecaos.platform.reviews.api.RecoveryCaseSlaBreached;
import uz.horecaos.platform.reviews.api.RecoveryCaseStateChanged;
import uz.horecaos.platform.reviews.api.ReviewCommentModerated;
import uz.horecaos.platform.reviews.api.ReviewSubmitted;
import uz.horecaos.platform.reviews.api.ReviewsEvent;

/**
 * ADR 0159's five facts against ADR 0032 and ADR 0029: what each event's payload carries is exactly what
 * its schema says, a serialized sample validates against it, and nothing in it can be a comment, a note, a
 * tag's wording or a person.
 *
 * <p>The tenancy and ordering payloads share {@code EventPayloadClassificationTests}; this is the same rule
 * for {@code reviews}, where the data one field away from an event is exactly the data ADR 0029 protects.
 * The record's components and the schema's properties are compared both ways, so a field added to one and
 * not the other fails here, and then the platform's name heuristic runs over them with a short, named list
 * of reviewed exceptions.
 */
class ReviewsEventContractTests {

    /** Serializes as the producer does: Jackson 3, dates as ISO-8601 text. */
    private static final JsonMapper PRODUCER = JsonMapper.builder().build();

    private static final ObjectMapper READER = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018f9f10-e000-7000-8000-0000000000a1");
    private static final UUID ORDER = UUID.fromString("018f9f10-e000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9f10-e000-7000-8000-0000000000a3");
    private static final UUID LOCATION = UUID.fromString("018f9f10-e000-7000-8000-0000000000a4");
    private static final UUID REVIEW = UUID.fromString("018f9f10-e000-7000-8000-0000000000a5");
    private static final UUID CASE = UUID.fromString("018f9f10-e000-7000-8000-0000000000a6");
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    /**
     * Component names the heuristic flags and review accepted, each with its reason. {@code rating} contains
     * "tin" and {@code hasComment} contains "comment"; neither carries a value a person typed. The first is a
     * number from a completed order, the second a flag that says words exist and nothing about what they say.
     */
    private static final Set<String> REVIEWED_EXCEPTIONS = Set.of("rating", "hasComment");

    private static List<ReviewsEvent> samples() {
        return List.of(
                new ReviewSubmitted(UUID.randomUUID(), TENANT, REVIEW, ORDER, BRAND, LOCATION, 1, true, 2, NOW),
                new ReviewSubmitted(UUID.randomUUID(), TENANT, REVIEW, ORDER, BRAND, LOCATION, 5, false, 0, NOW),
                new ReviewCommentModerated(
                        UUID.randomUUID(),
                        TENANT,
                        REVIEW,
                        ORDER,
                        BRAND,
                        "PENDING",
                        "HIDDEN",
                        "NAMES_AN_INDIVIDUAL",
                        "STAFF",
                        NOW),
                new RecoveryCaseOpened(
                        UUID.randomUUID(),
                        TENANT,
                        CASE,
                        REVIEW,
                        ORDER,
                        BRAND,
                        LOCATION,
                        "AUTO",
                        1,
                        NOW.plusSeconds(14_400),
                        NOW.plusSeconds(259_200),
                        NOW),
                new RecoveryCaseStateChanged(
                        UUID.randomUUID(),
                        TENANT,
                        CASE,
                        ORDER,
                        BRAND,
                        LOCATION,
                        "NEW",
                        "IN_PROGRESS",
                        null,
                        "S-0142",
                        NOW),
                new RecoveryCaseStateChanged(
                        UUID.randomUUID(),
                        TENANT,
                        CASE,
                        ORDER,
                        BRAND,
                        LOCATION,
                        "IN_PROGRESS",
                        "RESOLVED",
                        "REMEDY_GRANTED",
                        "S-0142",
                        NOW),
                new RecoveryCaseSlaBreached(
                        UUID.randomUUID(),
                        TENANT,
                        CASE,
                        ORDER,
                        BRAND,
                        LOCATION,
                        "FIRST_RESPONSE",
                        NOW,
                        NOW.plusSeconds(60)));
    }

    @Test
    @DisplayName("the five events are the whole of the sealed family, and every one is in the catalogue")
    void theFiveEventsAreRegistered() {
        assertThat(Arrays.stream(ReviewsEvent.class.getPermittedSubclasses()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder(
                        "ReviewSubmitted",
                        "ReviewCommentModerated",
                        "RecoveryCaseOpened",
                        "RecoveryCaseStateChanged",
                        "RecoveryCaseSlaBreached");
        List<String> registered =
                EventCatalog.all().stream().map(EventContract::eventType).toList();
        assertThat(registered)
                .as("every permitted ReviewsEvent needs an ADR 0032 catalogue entry")
                .containsAll(Arrays.stream(ReviewsEvent.class.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .toList());
    }

    @Test
    @DisplayName("every payload record matches its schema property for property, on the reviews topic keyed by order")
    void everyPayloadRecordMatchesItsSchemaPropertyForProperty() throws Exception {
        for (ReviewsEvent event : samples()) {
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
            assertThat(contract.topic()).isEqualTo("reviews.events");
            assertThat(contract.partitionKey())
                    .as("%s: one order's review, moderation and case facts stay in order", event.eventType())
                    .isEqualTo("orderId");
            assertThat(components)
                    .as("%s: the key the contract names is in the payload", event.eventType())
                    .contains("orderId");
        }
    }

    @Test
    @DisplayName("a serialized sample of every event validates against its published schema")
    void everySerializedSampleValidates() throws Exception {
        for (ReviewsEvent event : samples()) {
            EventContract contract = EventCatalog.require(event.eventType(), event.eventVersion());
            JsonNode payload = READER.readTree(PRODUCER.writeValueAsString(event.payload()));

            Set<ValidationMessage> errors = schema(contract).validate(payload);

            assertThat(errors)
                    .as("payload of %s does not satisfy %s: %s", contract.key(), contract.schemaPath(), payload)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the schemas refuse a stray field, a rating out of range and an unknown state")
    void theSchemasAreClosed() throws Exception {
        JsonSchema submitted = schema(EventCatalog.require("ReviewSubmitted", 1));
        String valid = """
                {"reviewId":"%s","orderId":"%s","brandId":"%s","locationId":"%s","rating":%s,"hasComment":true,
                 "tagCount":1,"submittedAt":"2026-10-08T10:00:00Z"%s}
                """;
        assertThat(submitted.validate(READER.readTree(valid.formatted(REVIEW, ORDER, BRAND, LOCATION, 3, ""))))
                .isEmpty();
        assertThat(submitted.validate(READER.readTree(valid.formatted(REVIEW, ORDER, BRAND, LOCATION, 6, ""))))
                .as("six stars")
                .isNotEmpty();
        assertThat(submitted.validate(READER.readTree(valid.formatted(REVIEW, ORDER, BRAND, LOCATION, 0, ""))))
                .as("zero stars")
                .isNotEmpty();
        assertThat(submitted.validate(
                        READER.readTree(valid.formatted(REVIEW, ORDER, BRAND, LOCATION, 3, ",\"comment\":\"cold\""))))
                .as("the comment can never ride along")
                .isNotEmpty();
        assertThat(submitted.validate(READER.readTree(
                        valid.formatted(REVIEW, ORDER, BRAND, LOCATION, 3, ",\"customerName\":\"Aziza\""))))
                .as("nor the customer")
                .isNotEmpty();

        JsonSchema changed = schema(EventCatalog.require("RecoveryCaseStateChanged", 1));
        String change = """
                {"caseId":"%s","orderId":"%s","brandId":"%s","locationId":"%s","fromState":"NEW","toState":"%s",
                 "changedAt":"2026-10-08T10:00:00Z"%s}
                """;
        assertThat(changed.validate(READER.readTree(change.formatted(CASE, ORDER, BRAND, LOCATION, "IN_PROGRESS", ""))))
                .isEmpty();
        assertThat(changed.validate(READER.readTree(change.formatted(CASE, ORDER, BRAND, LOCATION, "ESCALATED", ""))))
                .as("a state the machine does not have")
                .isNotEmpty();
        assertThat(changed.validate(READER.readTree(
                        change.formatted(CASE, ORDER, BRAND, LOCATION, "IN_PROGRESS", ",\"note\":\"phoned her\""))))
                .as("a case note never rides along")
                .isNotEmpty();
        assertThat(changed.validate(READER.readTree(
                        change.formatted(CASE, ORDER, BRAND, LOCATION, "IN_PROGRESS", ",\"ownerName\":\"Aziza K\""))))
                .as("the owner is a reference, never a name")
                .isNotEmpty();
    }

    @Test
    @DisplayName("no payload field can be personal data: the heuristic is clean apart from two reviewed names")
    void noPayloadFieldCanBePersonalData() {
        List<String> violations = new ArrayList<>();
        Set<String> flaggedAndExcepted = new TreeSet<>();
        for (ReviewsEvent event : samples()) {
            for (RecordComponent component : event.payload().getClass().getRecordComponents()) {
                if (!ChangeDocuments.isProtected(component.getName())) {
                    continue;
                }
                if (REVIEWED_EXCEPTIONS.contains(component.getName())) {
                    flaggedAndExcepted.add(component.getName());
                } else {
                    violations.add(event.eventType() + "." + component.getName());
                }
            }
        }
        assertThat(violations)
                .as("a field whose name suggests a phone, a name or a note cannot be on a topic (ADR 0029)")
                .isEmpty();
        assertThat(flaggedAndExcepted)
                .as("an exception that nothing needs any more is a hole waiting for a field of that name")
                .containsExactlyInAnyOrderElementsOf(REVIEWED_EXCEPTIONS);
    }

    @Test
    @DisplayName("a payload is built from the record's fields and never from the wrapper's tenant")
    void thePayloadCarriesNoTenantAndNoCustomer() {
        for (ReviewsEvent event : samples()) {
            List<String> names = Arrays.stream(event.payload().getClass().getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            assertThat(names)
                    .as("%s: the tenant is in the envelope, the customer is not in the event at all", event.eventType())
                    .doesNotContain("tenantId", "customerAccountId", "customerId", "customer", "staffMemberId");
        }
    }

    @Test
    @DisplayName("an event with a missing identifier is refused at construction")
    @SuppressWarnings("NullAway")
    void aMissingIdentifierIsRefused() {
        assertThatThrownBy(() ->
                        new ReviewSubmitted(UUID.randomUUID(), TENANT, REVIEW, ORDER, BRAND, null, 3, false, 0, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    private JsonSchema schema(EventContract contract) throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(source).as("schema %s must exist", contract.schemaPath()).isNotNull();
            return factory.getSchema(
                    Objects.requireNonNull(source),
                    SchemaValidatorsConfig.builder().build());
        }
    }

    private JsonNode schemaOf(EventContract contract) throws Exception {
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(source).as("the schema file %s", contract.schemaPath()).isNotNull();
            return READER.readTree(source);
        }
    }
}
