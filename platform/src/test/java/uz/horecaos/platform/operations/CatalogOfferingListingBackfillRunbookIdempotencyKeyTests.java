package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        int postIndex = runbook.indexOf("curl -sS -X POST");
        assertThat(postIndex).as("runbook's listing-backfill curl example").isNotNegative();

        // The header block is the few lines right after the curl invocation, up to the
        // closing of that shell command (the blank line that ends the code fence's loop
        // body). Collapse whitespace so a header spread across a wrapped line is not missed.
        int blockEnd = runbook.indexOf("```", postIndex);
        String curlBlock = runbook.substring(postIndex, blockEnd < 0 ? runbook.length() : blockEnd)
                .replaceAll("\\s+", " ");

        assertThat(curlBlock)
                .as("every call this loop makes to a mutating, capability-guarded endpoint "
                        + "(ADR 0031) needs an Idempotency-Key, or the real endpoint answers "
                        + "400 IDEMPOTENCY_KEY_REQUIRED for every tenant/brand/location row")
                .contains("Idempotency-Key");
    }
}
