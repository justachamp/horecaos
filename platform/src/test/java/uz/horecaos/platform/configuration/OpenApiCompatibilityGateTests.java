package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What the release-contract gate holds, and what ADR 0070's {@code x-horecaos-surface} marker
 * lets it stop holding.
 *
 * <p>The marker is only worth anything if it is exactly as strong as it claims: an internal
 * operation, marked so in the <em>released</em> document, may leave or change; a published one
 * may not; and an operation with no marker — every operation in every document that predates
 * the marker, and every non-storefront group — is held as before. The second half matters more
 * than the first, because a gate that quietly stopped holding unmarked operations would pass
 * every one of these tests' first assertion and fail the contract it is for.
 */
class OpenApiCompatibilityGateTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String OK = "\"responses\":{\"200\":{\"description\":\"ok\"}}";

    private static JsonNode document(String paths) throws Exception {
        return JSON.readTree("{\"paths\":{" + paths + "}}");
    }

    private static String operation(String path, @Nullable String surface) {
        String marker = surface == null ? "" : "\"x-horecaos-surface\":\"" + surface + "\",";
        return "\"" + path + "\":{\"get\":{" + marker + OK + "}}";
    }

    @Test
    void aPublishedOperationMayNotLeaveTheContract() throws Exception {
        JsonNode released = document(operation("/menu", "published"));

        assertThatThrownBy(() -> OpenApiContractTests.assertBackwardCompatible(released, document("")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("/menu");
    }

    @Test
    void anInternalOperationMayLeaveTheContract() throws Exception {
        JsonNode released = document(operation("/menu", "published") + "," + operation("/telegram/link", "internal"));
        JsonNode generated = document(operation("/menu", "published"));

        assertThatCode(() -> OpenApiContractTests.assertBackwardCompatible(released, generated))
                .doesNotThrowAnyException();
    }

    @Test
    void anOperationWithNoMarkerIsHeldAsItAlwaysWas() throws Exception {
        JsonNode released = document(operation("/older", null));

        assertThatThrownBy(() -> OpenApiContractTests.assertBackwardCompatible(released, document("")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("/older");
    }

    @Test
    void aPublishedMethodOnAPathWithAnInternalOneIsStillHeld() throws Exception {
        JsonNode released = JSON.readTree("{\"paths\":{\"/mixed\":{"
                + "\"get\":{\"x-horecaos-surface\":\"published\"," + OK + "},"
                + "\"post\":{\"x-horecaos-surface\":\"internal\"," + OK + "}}}}");
        JsonNode droppedInternal = JSON.readTree(
                "{\"paths\":{\"/mixed\":{" + "\"get\":{\"x-horecaos-surface\":\"published\"," + OK + "}}}}");
        JsonNode droppedPublished = JSON.readTree(
                "{\"paths\":{\"/mixed\":{" + "\"post\":{\"x-horecaos-surface\":\"internal\"," + OK + "}}}}");

        assertThatCode(() -> OpenApiContractTests.assertBackwardCompatible(released, droppedInternal))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> OpenApiContractTests.assertBackwardCompatible(released, droppedPublished))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void markingAnOperationInternalInTheSameChangeThatRemovesItBuysNothing() throws Exception {
        // The released document had no marker; the change that removes the operation cannot add one
        // retroactively, because only the released document's marker counts.
        JsonNode released = document(operation("/menu", null));
        JsonNode generated = document(operation("/other", "internal"));

        assertThatThrownBy(() -> OpenApiContractTests.assertBackwardCompatible(released, generated))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("/menu");
    }
}
