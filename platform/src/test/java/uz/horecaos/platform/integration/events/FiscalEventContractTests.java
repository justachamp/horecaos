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
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fiscal.api.FiscalCorrectionAttached;
import uz.horecaos.platform.fiscal.api.FiscalCorrectionRequested;
import uz.horecaos.platform.fiscal.api.FiscalEvent;

/**
 * ADR 0166's two fiscal facts against ADR 0032 and ADR 0029: what each payload carries is what its schema
 * says, and no field of either can be a piece of receipt evidence.
 *
 * <p>The structural half is the one ADR 0166 names: «no evidence field, no INN beyond the legal entity id, and
 * no marking code is reachable from either payload type». It walks the record components of every payload,
 * recursively, and refuses a name that is the fiscal sign, the receipt number or link, the terminal id, a
 * marking code or a taxpayer number, so a field added later fails here before it reaches a topic.
 */
class FiscalEventContractTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();

    /** Substrings of a field name that mean receipt evidence or a taxpayer identity (ADR 0029, ADR 0166). */
    private static final List<String> EVIDENCE_NAMES = List.of(
            "fiscalsign",
            "sign",
            "receiptnumber",
            "receiptreference",
            "receipturl",
            "url",
            "link",
            "terminalid",
            "marking",
            "markcode",
            "label",
            "tin",
            "inn",
            "pinfl",
            "sellername",
            "legalname",
            "externalreceiptid",
            "lines",
            "tender");

    private static List<FiscalEvent> samples() {
        Instant now = Instant.parse("2026-10-23T10:00:00Z");
        UUID entity = UUID.randomUUID();
        return List.of(
                new FiscalCorrectionRequested(
                        UUID.randomUUID(),
                        TENANT,
                        DOCUMENT,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        entity,
                        "CORRECTION",
                        "CORRECTION_REQUESTED",
                        "WRONG_LINES",
                        "OPERATOR",
                        now),
                new FiscalCorrectionRequested(
                        UUID.randomUUID(),
                        TENANT,
                        DOCUMENT,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "CORRECTION",
                        "CORRECTION_REQUESTED",
                        "AMENDED_AFTER_ISSUE",
                        "AMENDMENT",
                        now),
                new FiscalCorrectionAttached(
                        UUID.randomUUID(),
                        TENANT,
                        DOCUMENT,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        entity,
                        "VOID",
                        "VOIDED",
                        UUID.randomUUID(),
                        "REFUND",
                        now));
    }

    @Test
    void everyPayloadRecordMatchesItsSchemaPropertyForProperty() throws Exception {
        for (FiscalEvent event : samples()) {
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
            assertThat(contract.topic()).isEqualTo("fiscal.events");
            assertThat(contract.producingModule()).isEqualTo("fiscal");
            assertThat(contract.partitionKey())
                    .as("%s: one receipt's request and attachment stay in order", event.eventType())
                    .isEqualTo("documentId");
        }
    }

    @Test
    void everyRequiredPropertyIsPresentInASerializedSample() throws Exception {
        for (FiscalEvent event : samples()) {
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
    void everySerializedSampleSatisfiesItsSchemaEnums() throws Exception {
        for (FiscalEvent event : samples()) {
            JsonNode schema = schemaOf(EventCatalog.require(event.eventType(), event.eventVersion()));
            JsonNode payload = MAPPER.readTree(MAPPER.writeValueAsString(event.payload()));
            List<String> outside = new ArrayList<>();
            schema.path("properties").fields().forEachRemaining(property -> {
                JsonNode allowed = property.getValue().path("enum");
                if (allowed.isArray() && !payload.path(property.getKey()).isMissingNode()) {
                    boolean found = false;
                    for (JsonNode value : allowed) {
                        found |= value.asText()
                                .equals(payload.path(property.getKey()).asText());
                    }
                    if (!found) {
                        outside.add(property.getKey());
                    }
                }
            });
            assertThat(outside)
                    .as("%s: a value the schema's enum does not name", event.eventType())
                    .isEmpty();
        }
    }

    @Test
    void noEvidenceFieldIsReachableFromAnyPayloadType() {
        List<String> violations = new ArrayList<>();
        for (FiscalEvent event : samples()) {
            walk(event.eventType(), event.payload().getClass(), violations, 0);
        }
        assertThat(violations)
                .as("a field that is receipt evidence or a taxpayer identity cannot be on a topic (ADR 0029, 0166)")
                .isEmpty();
    }

    @Test
    void noPayloadFieldCanBePersonalData() {
        List<String> violations = new ArrayList<>();
        for (FiscalEvent event : samples()) {
            for (RecordComponent component : event.payload().getClass().getRecordComponents()) {
                if (ChangeDocuments.isProtected(component.getName())) {
                    violations.add(event.eventType() + "." + component.getName());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void theNameHeuristicActuallyCatchesTheFieldsItIsMeantTo() {
        List<String> violations = new ArrayList<>();
        walk("probe", EvidenceLeak.class, violations, 0);
        assertThat(violations)
                .as("the walk must flag a payload that leaks evidence, or the test above proves nothing")
                .containsExactlyInAnyOrder(
                        "probe.fiscalSign", "probe.receiptUrl", "probe.sellerTin", "probe.nested.markingCode");
    }

    @Test
    void theTwoEventsAreTheWholeOfTheSealedFamily() {
        assertThat(Arrays.stream(FiscalEvent.class.getPermittedSubclasses()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder("FiscalCorrectionRequested", "FiscalCorrectionAttached");
    }

    // -----------------------------------------------------------------------------------------------------------------

    private record EvidenceLeak(
            UUID documentId, String fiscalSign, String receiptUrl, String sellerTin, Nested nested) {
        record Nested(String markingCode) {}
    }

    private static void walk(String path, Class<?> type, List<String> violations, int depth) {
        if (depth > 4 || !type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String lower = component.getName().toLowerCase(Locale.ROOT);
            // «documentId» and «requestId» end in «id» and are identifiers; the evidence names are looked for as
            // whole words of the camel-case name, never as a substring of an unrelated word.
            if (EVIDENCE_NAMES.stream()
                    .anyMatch(name -> words(component.getName()).contains(name) || lower.equals(name))) {
                violations.add(path + "." + component.getName());
            }
            walk(path + "." + component.getName(), component.getType(), violations, depth + 1);
        }
    }

    /** The camel-case words of a name, lower-cased, and every run of adjacent words joined: «fiscal», «sign», «fiscalsign». */
    private static Set<String> words(String name) {
        String[] parts = name.split("(?<=[a-z0-9])(?=[A-Z])");
        Set<String> out = new TreeSet<>();
        for (int from = 0; from < parts.length; from++) {
            StringBuilder run = new StringBuilder();
            for (int to = from; to < parts.length; to++) {
                run.append(parts[to].toLowerCase(Locale.ROOT));
                out.add(run.toString());
            }
        }
        return out;
    }

    private JsonNode schemaOf(EventContract contract) throws Exception {
        try (InputStream schema = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(schema).as("the schema file %s", contract.schemaPath()).isNotNull();
            return MAPPER.readTree(schema);
        }
    }
}
