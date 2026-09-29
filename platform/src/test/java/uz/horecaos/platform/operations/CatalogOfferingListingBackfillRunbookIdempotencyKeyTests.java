package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code catalog-offering-listing-backfill.md}'s step-2 {@code curl} call to {@code POST
 * .../inventory/listing-backfill} sends only {@code Authorization} and {@code Content-Type} —
 * no {@code Idempotency-Key}. That endpoint is {@code
 * InventoryController#backfillLocationListing}, annotated {@code
 * @RequiresCapability(..., mutating = true)}, which {@code IdempotencyInterceptor} treats as
 * requiring the header (ADR 0031): every call this runbook documents would be rejected with
 * {@code IDEMPOTENCY_KEY_REQUIRED} before an operator following it verbatim ever lists a
 * single variant.
 */
class CatalogOfferingListingBackfillRunbookIdempotencyKeyTests {

    private static final Path RUNBOOK = Path.of("docs/runbooks/catalog-offering-listing-backfill.md");

    @Test
    @DisplayName("the listing-backfill curl example sends an Idempotency-Key header")
    void backfillCurlExampleSendsIdempotencyKey() throws IOException {
        String runbook = Files.readString(RUNBOOK, StandardCharsets.UTF_8);

        // Backslash-continued lines are one command to a shell; join them so a header on a
        // wrapped line is not missed, and find the POST by its flag rather than by the exact
        // spelling of the other flags in front of it.
        String flat = runbook.replaceAll("\\\\\\R\\s*", " ");
        Matcher post = Pattern.compile("curl\\s+-[^\\n]*-X POST[^\\n]*").matcher(flat);
        assertThat(post.find()).as("runbook's listing-backfill curl example").isTrue();
        String curlBlock = post.group().replaceAll("\\s+", " ");

        assertThat(curlBlock)
                .as("every call this loop makes to a mutating, capability-guarded endpoint "
                        + "(ADR 0031) needs an Idempotency-Key, or the real endpoint answers "
                        + "400 IDEMPOTENCY_KEY_REQUIRED for every tenant/brand/location row")
                .contains("Idempotency-Key");
    }
}
