package uz.qoida.platform.integration.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR 0032 compatibility gate.
 *
 * <p>Within one {@code eventVersion} a schema may only grow: adding an optional
 * property is allowed, while removing a property, narrowing its type, or making
 * an existing property required is a breaking change that requires a new
 * version. The frozen baseline under {@code src/test/resources/events-baseline}
 * is the last released shape; update it deliberately in the same commit that
 * introduces a new event version, never to silence this test.
 */
class EventSchemaCompatibilityTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path BASELINE_ROOT = Path.of("src/test/resources/events-baseline");

    static List<EventContract> contracts() {
        return List.copyOf(EventCatalog.all());
    }

    @ParameterizedTest(name = "{0} stays backward compatible with its baseline")
    @MethodSource("contracts")
    void staysBackwardCompatible(EventContract contract) throws Exception {
        Path baselinePath = BASELINE_ROOT.resolve(
                "%s/%s.v%d.schema.json".formatted(contract.topic(), contract.eventType(), contract.eventVersion()));

        if (!Files.exists(baselinePath)) {
            // A brand new event version has no released baseline yet. Freeze it now.
            Files.createDirectories(baselinePath.getParent());
            Files.writeString(baselinePath, currentSchemaSource(contract));
            return;
        }

        JsonNode baseline = MAPPER.readTree(Files.readString(baselinePath));
        JsonNode current = MAPPER.readTree(currentSchemaSource(contract));

        assertThat(breakingChanges(baseline, current))
                .as("""
                        %s changed incompatibly within version %d.
                        Removing a property, narrowing its type, or adding a required property
                        needs a new eventVersion, not an edit to the existing schema.""",
                        contract.key(), contract.eventVersion())
                .isEmpty();
    }

    private static List<String> breakingChanges(JsonNode baseline, JsonNode current) {
        List<String> breaks = new ArrayList<>();

        JsonNode baseProps = baseline.path("properties");
        JsonNode currProps = current.path("properties");

        for (Iterator<Map.Entry<String, JsonNode>> it = baseProps.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> property = it.next();
            JsonNode currentProperty = currProps.path(property.getKey());

            if (currentProperty.isMissingNode()) {
                breaks.add("removed property: " + property.getKey());
                continue;
            }
            Set<String> baseTypes = typesOf(property.getValue());
            Set<String> currentTypes = typesOf(currentProperty);
            if (!currentTypes.containsAll(baseTypes)) {
                breaks.add("narrowed type of %s from %s to %s"
                        .formatted(property.getKey(), baseTypes, currentTypes));
            }
            Set<String> baseEnum = enumOf(property.getValue());
            Set<String> currentEnum = enumOf(currentProperty);
            if (!baseEnum.isEmpty() && !currentEnum.containsAll(baseEnum)) {
                breaks.add("removed enum values from %s: %s"
                        .formatted(property.getKey(), difference(baseEnum, currentEnum)));
            }
        }

        Set<String> baseRequired = stringSet(baseline.path("required"));
        Set<String> currentRequired = stringSet(current.path("required"));
        Set<String> newlyRequired = difference(currentRequired, baseRequired);
        if (!newlyRequired.isEmpty()) {
            breaks.add("newly required properties: " + newlyRequired);
        }

        return breaks;
    }

    private static Set<String> typesOf(JsonNode property) {
        JsonNode type = property.path("type");
        Set<String> types = new TreeSet<>();
        if (type.isTextual()) {
            types.add(type.asText());
        } else if (type.isArray()) {
            type.forEach(node -> types.add(node.asText()));
        }
        return types;
    }

    private static Set<String> enumOf(JsonNode property) {
        Set<String> values = new TreeSet<>();
        property.path("enum").forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Set<String> stringSet(JsonNode array) {
        Set<String> values = new TreeSet<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private String currentSchemaSource(EventContract contract) throws Exception {
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(contract.schemaPath())) {
            assertThat(source).as("schema %s must exist", contract.schemaPath()).isNotNull();
            return new String(source.readAllBytes());
        }
    }

    @Test
    void detectsARemovedPropertyAsBreaking() throws Exception {
        JsonNode baseline = MAPPER.readTree("""
                {"properties":{"a":{"type":"string"},"b":{"type":"string"}},"required":["a"]}""");
        JsonNode current = MAPPER.readTree("""
                {"properties":{"a":{"type":"string"}},"required":["a"]}""");

        assertThat(breakingChanges(baseline, current)).containsExactly("removed property: b");
    }

    @Test
    void detectsANewlyRequiredPropertyAsBreaking() throws Exception {
        JsonNode baseline = MAPPER.readTree("""
                {"properties":{"a":{"type":"string"}},"required":[]}""");
        JsonNode current = MAPPER.readTree("""
                {"properties":{"a":{"type":"string"}},"required":["a"]}""");

        assertThat(breakingChanges(baseline, current)).containsExactly("newly required properties: [a]");
    }

    @Test
    void allowsAddingAnOptionalProperty() throws Exception {
        JsonNode baseline = MAPPER.readTree("""
                {"properties":{"a":{"type":"string"}},"required":["a"]}""");
        JsonNode current = MAPPER.readTree("""
                {"properties":{"a":{"type":"string"},"b":{"type":"string"}},"required":["a"]}""");

        assertThat(breakingChanges(baseline, current)).isEmpty();
    }

    @Test
    void detectsANarrowedTypeAsBreaking() throws Exception {
        JsonNode baseline = MAPPER.readTree("""
                {"properties":{"a":{"type":["string","null"]}},"required":[]}""");
        JsonNode current = MAPPER.readTree("""
                {"properties":{"a":{"type":"string"}},"required":[]}""");

        assertThat(breakingChanges(baseline, current))
                .containsExactly("narrowed type of a from [null, string] to [string]");
    }
}
