package uz.qoida.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * ADR 0027: the audit trail must not become a second copy of the data it
 * protects.
 */
class ChangeDocumentsTests {

    @Test
    void recordsAnOrdinaryFieldChangeInFull() {
        Map<String, Object> document = ChangeDocuments.change("status", "DRAFT", "ACTIVE");

        assertThat(document).containsKey("status");
        assertThat(asMap(document.get("status")))
                .containsEntry("before", "DRAFT")
                .containsEntry("after", "ACTIVE");
    }

    @Test
    void redactsAProtectedFieldWhileStillRecordingThatItChanged() {
        Map<String, Object> document = ChangeDocuments.sanitize(
                ChangeDocuments.change("customerPhone", "+998901231076", "+998901231077"));

        assertThat(asMap(document.get("customerPhone")))
                .as("the fact of the change is evidence; the value is not")
                .containsEntry("before", ChangeDocuments.REDACTED)
                .containsEntry("after", ChangeDocuments.REDACTED);
    }

    @Test
    void redactsNestedProtectedFields() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("line1", "Chilonzor 6A");
        nested.put("city", "Tashkent");
        Map<String, Object> document = Map.of("deliveryAddress", nested, "status", "ACTIVE");

        Map<String, Object> sanitized = ChangeDocuments.sanitize(document);

        assertThat(asMap(sanitized.get("deliveryAddress")))
                .as("a protected field name redacts its whole subtree")
                .containsEntry("line1", ChangeDocuments.REDACTED)
                .containsEntry("city", ChangeDocuments.REDACTED);
        assertThat(sanitized).containsEntry("status", "ACTIVE");
    }

    @Test
    void redactsProtectedLeavesInsideAnUnprotectedContainer() {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("displayName", "Acme");
        contact.put("email", "ops@example.com");

        Map<String, Object> sanitized = ChangeDocuments.sanitize(Map.of("profile", contact));

        assertThat(asMap(sanitized.get("profile")))
                .containsEntry("displayName", "Acme")
                .containsEntry("email", ChangeDocuments.REDACTED);
    }

    @Test
    void keepsNullsDistinguishableFromRedactions() {
        Map<String, Object> sanitized = ChangeDocuments.sanitize(
                ChangeDocuments.change("customerPhone", null, "+998901231077"));

        assertThat(asMap(sanitized.get("customerPhone")))
                .as("null means the field was unset, which is different from hidden")
                .containsEntry("before", null)
                .containsEntry("after", ChangeDocuments.REDACTED);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void recognisesTheProtectedTermsUsedAcrossTheAdrSet() {
        assertThat(ChangeDocuments.isProtected("customerEmail")).isTrue();
        assertThat(ChangeDocuments.isProtected("passportNumber")).isTrue();
        assertThat(ChangeDocuments.isProtected("pickupLatitude")).isTrue();
        assertThat(ChangeDocuments.isProtected("clientSecret")).isTrue();
        assertThat(ChangeDocuments.isProtected("status")).isFalse();
        assertThat(ChangeDocuments.isProtected("displayName")).isFalse();
    }
}
