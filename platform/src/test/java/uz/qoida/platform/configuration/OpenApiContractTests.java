package uz.qoida.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

import uz.qoida.platform.support.TestDatabase;

/**
 * ADR 0031's release-contract gate.
 *
 * <p>Springdoc's running document is the contract of record. This test obtains
 * it through the real MVC surface, canonicalises it, checks the prior released
 * v1 document for breaking changes, and then refuses any undocumented drift.
 * A maintainer updates the baseline only through {@code make openapi-baseline};
 * that path still runs the compatibility check first, so copying a new document
 * over an old one cannot hide a removed operation or a narrowed schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path BASELINE = Path.of("api/openapi/v1/qoida-api.json");
    private static final Path GENERATED = Path.of("target/openapi/qoida-api-v1.json");
    private static final boolean UPDATE_BASELINE = Boolean.getBoolean("qoida.openapi.updateBaseline");
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the OpenAPI contract test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("qoida.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void generatedV1DocumentRemainsCompatibleWithAndRecordedAgainstItsReleasedBaseline() throws Exception {
        String current = canonicalDocument();
        Files.createDirectories(GENERATED.getParent());
        Files.writeString(GENERATED, current, StandardCharsets.UTF_8);

        if (Files.exists(BASELINE)) {
            JsonNode released = JSON.readTree(Files.readString(BASELINE, StandardCharsets.UTF_8));
            JsonNode generated = JSON.readTree(current);
            assertBackwardCompatible(released, generated);
        }

        if (UPDATE_BASELINE) {
            Files.createDirectories(BASELINE.getParent());
            Files.writeString(BASELINE, current, StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(BASELINE))
                .as("a released OpenAPI v1 baseline must be checked in")
                .isTrue();
        assertThat(Files.readString(BASELINE, StandardCharsets.UTF_8))
                .as("OpenAPI changed; run make openapi-baseline, review the diff, and commit it")
                .isEqualTo(current);
    }

    private String canonicalDocument() throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v3/api-docs"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode document = JSON.readTree(body);
        assertThat(document.path("openapi").asText()).startsWith("3.");
        return JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(canonical(document)) + System.lineSeparator();
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> sorted.set(field, canonical(node.get(field))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            node.forEach(value -> copy.add(canonical(value)));
            return copy;
        }
        return node;
    }

    private static void assertBackwardCompatible(JsonNode released, JsonNode generated) {
        JsonNode oldPaths = released.path("paths");
        JsonNode newPaths = generated.path("paths");
        oldPaths.fieldNames().forEachRemaining(path -> {
            JsonNode oldPath = oldPaths.path(path);
            JsonNode newPath = newPaths.path(path);
            assertThat(newPath.isObject())
                    .as("published path %s must remain in v1", path)
                    .isTrue();
            oldPath.fieldNames().forEachRemaining(method -> {
                if (!isHttpMethod(method)) {
                    return;
                }
                JsonNode oldOperation = oldPath.path(method);
                JsonNode newOperation = newPath.path(method);
                assertThat(newOperation.isObject())
                        .as("published %s %s must remain in v1", method.toUpperCase(), path)
                        .isTrue();
                assertParametersCompatible(path, method, oldOperation, newOperation);
                assertRequestBodyCompatible(path, method, oldOperation, newOperation, released, generated);
                assertResponsesCompatible(path, method, oldOperation, newOperation, released, generated);
            });
        });
    }

    private static void assertParametersCompatible(
            String path, String method, JsonNode oldOperation, JsonNode newOperation) {
        Map<String, JsonNode> current = indexedParameters(newOperation.path("parameters"));
        oldOperation.path("parameters").forEach(parameter -> {
            String key = parameter.path("in").asText() + ":" + parameter.path("name").asText();
            JsonNode replacement = current.get(key);
            assertThat(replacement)
                    .as("published %s parameter %s on %s %s must remain", key, method.toUpperCase(), path, method)
                    .isNotNull();
            if (!parameter.path("required").asBoolean(false)) {
                assertThat(replacement.path("required").asBoolean(false))
                        .as("published optional parameter %s on %s %s cannot become required", key,
                                method.toUpperCase(), path)
                        .isFalse();
            }
        });
    }

    private static Map<String, JsonNode> indexedParameters(JsonNode parameters) {
        java.util.LinkedHashMap<String, JsonNode> result = new java.util.LinkedHashMap<>();
        parameters.forEach(parameter -> result.put(
                parameter.path("in").asText() + ":" + parameter.path("name").asText(), parameter));
        return result;
    }

    private static void assertRequestBodyCompatible(
            String path, String method, JsonNode oldOperation, JsonNode newOperation,
            JsonNode released, JsonNode generated) {
        JsonNode oldBody = oldOperation.path("requestBody");
        if (oldBody.isMissingNode()) {
            return;
        }
        JsonNode newBody = newOperation.path("requestBody");
        assertThat(newBody.isObject())
                .as("published request body on %s %s must remain", method.toUpperCase(), path)
                .isTrue();
        if (!oldBody.path("required").asBoolean(false)) {
            assertThat(newBody.path("required").asBoolean(false))
                    .as("published optional request body on %s %s cannot become required",
                            method.toUpperCase(), path)
                    .isFalse();
        }
        assertSchemasCompatible(path + " " + method + " request", mediaSchema(oldBody), mediaSchema(newBody),
                released, generated, new HashSet<>());
    }

    private static void assertResponsesCompatible(
            String path, String method, JsonNode oldOperation, JsonNode newOperation,
            JsonNode released, JsonNode generated) {
        oldOperation.path("responses").fieldNames().forEachRemaining(status -> {
            JsonNode oldResponse = oldOperation.path("responses").path(status);
            JsonNode newResponse = newOperation.path("responses").path(status);
            assertThat(newResponse.isObject())
                    .as("published response %s on %s %s must remain", status, method.toUpperCase(), path)
                    .isTrue();
            assertSchemasCompatible(path + " " + method + " response " + status,
                    mediaSchema(oldResponse), mediaSchema(newResponse), released, generated, new HashSet<>());
        });
    }

    private static JsonNode mediaSchema(JsonNode node) {
        JsonNode content = node.path("content");
        Iterator<JsonNode> media = content.elements();
        return media.hasNext() ? media.next().path("schema") : null;
    }

    private static void assertSchemasCompatible(
            String context, JsonNode oldSchema, JsonNode newSchema, JsonNode released, JsonNode generated,
            Set<String> visitedReferences) {
        if (oldSchema == null || oldSchema.isMissingNode() || oldSchema.isNull()) {
            return;
        }
        assertThat(newSchema)
                .as("schema for %s must remain", context)
                .isNotNull();
        // The released and generated schemas commonly point at the same component
        // name. They are two independent reference chains, not a cycle; only a
        // repeated reference while resolving one document is recursive.
        JsonNode oldResolved = resolve(oldSchema, released, new HashSet<>(visitedReferences));
        JsonNode newResolved = resolve(newSchema, generated, new HashSet<>(visitedReferences));
        String oldType = oldResolved.path("type").asText();
        String newType = newResolved.path("type").asText();
        if (!oldType.isBlank() || !newType.isBlank()) {
            assertThat(newType).as("type for %s cannot narrow or change", context).isEqualTo(oldType);
        }
        if (oldResolved.has("enum")) {
            assertThat(newResolved.path("enum"))
                    .as("enum for %s cannot lose a published value", context)
                    .containsSubsequence(oldResolved.path("enum"));
        }
        Set<String> oldRequired = strings(oldResolved.path("required"));
        Set<String> newRequired = strings(newResolved.path("required"));
        assertThat(newRequired)
                .as("schema for %s cannot make an existing optional field required", context)
                .isSubsetOf(oldRequired);
        assertThat(oldRequired)
                .as("schema for %s cannot make a published required field optional", context)
                .isSubsetOf(newRequired);
        oldResolved.path("properties").fieldNames().forEachRemaining(property -> {
            JsonNode newProperty = newResolved.path("properties").path(property);
            assertThat(newProperty.isMissingNode())
                    .as("schema for %s cannot remove property %s", context, property)
                    .isFalse();
            assertSchemasCompatible(context + "." + property,
                    oldResolved.path("properties").path(property), newProperty, released, generated,
                    new HashSet<>(visitedReferences));
        });
        if (oldResolved.has("items")) {
            assertSchemasCompatible(context + "[]", oldResolved.path("items"), newResolved.path("items"),
                    released, generated, new HashSet<>(visitedReferences));
        }
    }

    private static JsonNode resolve(JsonNode schema, JsonNode document, Set<String> visitedReferences) {
        String reference = schema.path("$ref").asText();
        if (reference.isBlank()) {
            return schema;
        }
        assertThat(visitedReferences.add(reference))
                .as("cyclic schema reference %s requires an explicit compatibility rule", reference)
                .isTrue();
        JsonNode resolved = document.at(reference.substring(1));
        assertThat(resolved.isMissingNode()).as("schema reference %s must resolve", reference).isFalse();
        return resolve(resolved, document, visitedReferences);
    }

    private static Set<String> strings(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static boolean isHttpMethod(String value) {
        return Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace")
                .contains(value);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused").build();
        }
    }
}
