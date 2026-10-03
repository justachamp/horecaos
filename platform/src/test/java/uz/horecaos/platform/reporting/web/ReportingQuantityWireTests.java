package uz.horecaos.platform.reporting.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.reporting.domain.ClassificationRun;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * What a reporting client reads for a quantity once the column behind it is {@code numeric(10,3)}
 * (ADR 0137).
 *
 * <p>A sum over {@code numeric(10,3)} carries three fraction digits whatever it adds up to, and
 * Jackson writes a {@code BigDecimal} as it prints: {@code 3.000}. A report of whole plates must
 * read {@code 3}, as it did when the column was an integer; only a half portion earns a fraction.
 */
class ReportingQuantityWireTests {

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("a variant-sales row of whole portions is written as the integers it was, and a half stays a half")
    void variantSalesQuantitiesAreWrittenCanonically() {
        var row = ReportingController.VariantSalesRowResponse.of(new JdbcReportingStore.VariantSalesRow(
                UUID.randomUUID(),
                null,
                "Plov",
                new BigDecimal("3.000"),
                90_000L,
                80_000L,
                new BigDecimal("1.500"),
                40_000L,
                new BigDecimal("1.500"),
                40_000L));

        String wire = json.writeValueAsString(row);

        assertThat(wire).contains("\"totalQuantity\":3,").contains("\"deliveryQuantity\":1.5,");
        assertThat(wire).contains("\"pickupQuantity\":1.5,");
    }

    @Test
    @DisplayName("a row with no per-channel split writes null for it, not a zero")
    void anAbsentSplitStaysAbsent() {
        var row = ReportingController.VariantSalesRowResponse.of(new JdbcReportingStore.VariantSalesRow(
                UUID.randomUUID(), null, "Plov", new BigDecimal("20.000"), 1L, 1L, null, null, null, null));

        assertThat(json.writeValueAsString(row))
                .contains("\"totalQuantity\":20,")
                .contains("\"deliveryQuantity\":null")
                .contains("\"pickupQuantity\":null");
    }

    @Test
    @DisplayName("a product classification's quantity total is written canonically too")
    void classificationQuantitiesAreWrittenCanonically() {
        var row = ProductClassificationController.ClassificationRowResponse.of(new ClassificationRun.Row(
                UUID.randomUUID(), null, "Plov", 1L, 100, 100, 'A', new BigDecimal("12.000"), 1.0, 0.5, 5000, 'X'));

        assertThat(json.writeValueAsString(row)).contains("\"quantityTotal\":12,");
    }
}
