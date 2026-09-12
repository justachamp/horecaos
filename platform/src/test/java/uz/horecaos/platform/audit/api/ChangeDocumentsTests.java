package uz.horecaos.platform.audit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
        Map<String, Object> document =
                ChangeDocuments.sanitize(ChangeDocuments.change("customerPhone", "+998901231076", "+998901231077"));

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
        Map<String, Object> sanitized =
                ChangeDocuments.sanitize(ChangeDocuments.change("customerPhone", null, "+998901231077"));

        assertThat(asMap(sanitized.get("customerPhone")))
                .as("null means the field was unset, which is different from hidden")
                .containsEntry("before", null)
                .containsEntry("after", ChangeDocuments.REDACTED);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(@Nullable Object value) {
        // Every call site looks up a key this test just put there, so a null
        // here would mean the fixture itself is broken, not that "before"/"after"
        // is genuinely absent.
        return (Map<String, Object>) Objects.requireNonNull(value);
    }

    /**
     * Staff 9.3a's write-side primitive: {@code TenantControlPlaneService}
     * used to hand-roll a root-level {@code {before, after}} pair of whole
     * snapshots for {@code brand.revised}/{@code location.revised}; {@link
     * ChangeDocuments#diff} turns two such snapshots into the per-field shape
     * the activity log's viewer actually parses — the same round trip {@link
     * #recordsAnOrdinaryFieldChangeInFull} proves for a single field.
     */
    @Test
    void mergesTwoSnapshotsIntoThePerFieldShape() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("code", "north");
        before.put("slug", "north-branch");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", "north");
        after.put("slug", "north-2");

        Map<String, Object> document = ChangeDocuments.diff(before, after);

        assertThat(asMap(document.get("slug")))
                .as("the field that actually changed carries a real before/after")
                .containsEntry("before", "north-branch")
                .containsEntry("after", "north-2");
        assertThat(asMap(document.get("code")))
                .as("an unchanged field is still recorded, honestly, as unchanged")
                .containsEntry("before", "north")
                .containsEntry("after", "north");
    }

    @Test
    void diffRecordsAFieldPresentOnlyOnOneSide() {
        Map<String, Object> before = Map.of("timezone", "Asia/Tashkent");
        Map<String, Object> after = Map.of();

        Map<String, Object> document = ChangeDocuments.diff(before, after);

        assertThat(asMap(document.get("timezone")))
                .as("a field that existed and was cleared is a change, not an omission")
                .containsEntry("before", "Asia/Tashkent")
                .containsEntry("after", null);
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
