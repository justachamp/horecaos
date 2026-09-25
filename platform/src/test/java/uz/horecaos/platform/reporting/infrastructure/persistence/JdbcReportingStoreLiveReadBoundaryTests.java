package uz.horecaos.platform.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * w6-reporting-facts, batch 11 (7.4b/7.4c, ADR 0023): the boundary {@link
 * JdbcReportingStore#readTariffAudit} and {@link
 * JdbcReportingStore#readExternalDeliveryCost} now hold — a source scan, on
 * the same footing {@code OrderCrmLogTests#neverReadsTheReportingSchema}
 * already runs for {@code JdbcOrderCrmLogStore}'s own boundary the other
 * way. Both methods used to join {@code fulfillment} and {@code ordering}
 * live, on every request; since this wave they read only {@code
 * reporting.fact_delivery_fee_resolution} (V0411) and {@code
 * reporting.fact_external_delivery_cost} (V0412). A runtime check could only
 * ever prove the fixture it was given never happened to exercise the live
 * path — the whole point is that the SQL text itself must never name the
 * other two schemas again, so this reads the method bodies straight off
 * disk.
 */
class JdbcReportingStoreLiveReadBoundaryTests {

    private static final Path SOURCE =
            Path.of("src/main/java/uz/horecaos/platform/reporting/infrastructure/persistence/JdbcReportingStore.java");

    @Test
    @DisplayName("readTariffAudit no longer joins fulfillment or ordering live")
    void readTariffAuditNeverReadsFulfillmentOrOrderingLive() throws Exception {
        String body = methodBody(readSource(), "readTariffAudit");

        assertThat(body).doesNotContainIgnoringCase("fulfillment.");
        assertThat(body).doesNotContainIgnoringCase("ordering.");
        // The replacement: a plain read of its own closed fact.
        assertThat(body).contains("reporting.fact_delivery_fee_resolution");
    }

    @Test
    @DisplayName("readExternalDeliveryCost no longer joins fulfillment or ordering live")
    void readExternalDeliveryCostNeverReadsFulfillmentOrOrderingLive() throws Exception {
        String body = methodBody(readSource(), "readExternalDeliveryCost");

        assertThat(body).doesNotContainIgnoringCase("fulfillment.");
        assertThat(body).doesNotContainIgnoringCase("ordering.");
        assertThat(body).contains("reporting.fact_external_delivery_cost");
    }

    @Test
    @DisplayName("the two new close-time source reads are exactly where the live joins moved to")
    void theSourceReadsCarryTheJoinsInstead() throws Exception {
        String content = readSource();

        String tariffSource = methodBody(content, "readSourceTariffResolutions");
        assertThat(tariffSource).contains("fulfillment.delivery_fee_resolutions");
        assertThat(tariffSource).contains("ordering.orders");
        assertThat(tariffSource).contains("fulfillment.shipments");

        String externalCostSource = methodBody(content, "readSourceExternalDeliveryCosts");
        assertThat(externalCostSource).contains("fulfillment.shipments");
        assertThat(externalCostSource).contains("ordering.orders");
        assertThat(externalCostSource).contains("fulfillment.delivery_cost_lines");
        assertThat(externalCostSource).contains("fulfillment.partner_delivery_invoice_lines");
    }

    private static String readSource() throws Exception {
        return Files.readString(SOURCE);
    }

    /**
     * The named method's body (the text between its own opening and closing
     * brace), with block and line comments blanked out first — the same
     * "track the code, not the commentary about it" rule {@code
     * OrderCrmLogTests#neverReadsTheReportingSchema} states for its own scan
     * — so a doc comment mentioning {@code fulfillment.delivery_fee_resolutions}
     * in prose (this class's own header does) never fails the assertion.
     */
    private static String methodBody(String content, String methodName) {
        String withoutComments = content.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");

        int nameIndex = withoutComments.indexOf(" " + methodName + "(");
        assertThat(nameIndex)
                .as("method %s must exist in %s", methodName, SOURCE)
                .isGreaterThan(-1);

        int bodyStart = withoutComments.indexOf('{', nameIndex);
        assertThat(bodyStart).as("method %s must have a body", methodName).isGreaterThan(-1);

        int depth = 0;
        for (int i = bodyStart; i < withoutComments.length(); i++) {
            char c = withoutComments.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return withoutComments.substring(bodyStart, i + 1);
                }
            }
        }
        throw new AssertionError("Never found the closing brace for " + methodName);
    }
}
