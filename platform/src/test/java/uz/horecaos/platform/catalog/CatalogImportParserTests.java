package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.application.CatalogImportParser;
import uz.horecaos.platform.catalog.application.CatalogImportParser.CatalogExportRow;
import uz.horecaos.platform.catalog.application.CatalogImportRow;

/**
 * {@link CatalogImportParser} on its own -- no database, no network (row
 * 4.5b): the round trip between {@link CatalogImportParser#template()},
 * {@link CatalogImportParser#export}, and {@link CatalogImportParser#parse}.
 */
class CatalogImportParserTests {

    private final CatalogImportParser parser = new CatalogImportParser();

    @Test
    @DisplayName("the template is a header row a merchant can fill, and nothing else")
    void templateIsHeaderOnly() {
        String template = parser.template();

        assertThat(template.strip())
                .isEqualTo("product_code,category_code,category_name,product_name,product_description,variant_sku,"
                        + "unit_code,price_amount_minor,price_currency,status,image_url");
        assertThat(parser.parse(template)).isEmpty();
    }

    @Test
    @DisplayName("every column round-trips through parse, tolerant of header naming")
    void parsesEveryColumnToleratingHeaderVariation() {
        String csv = """
                Product Code,category-code,Category Name,Product Name,product_description,Variant SKU,unit_code,price_amount_minor,price_currency,status,image_url
                PLOV-001,HOT,Issiq taomlar,Osh,"Traditional rice, carrot and meat",SKU-PLOV,PIECE,50000,UZS,ACTIVE,https://example.test/plov.jpg
                """;

        List<CatalogImportRow> rows = parser.parse(csv);

        assertThat(rows).hasSize(1);
        CatalogImportRow row = rows.get(0);
        assertThat(row.rowNumber()).isEqualTo(1);
        assertThat(row.productCode()).isEqualTo("PLOV-001");
        assertThat(row.categoryCode()).isEqualTo("HOT");
        assertThat(row.categoryName()).isEqualTo("Issiq taomlar");
        assertThat(row.productName()).isEqualTo("Osh");
        assertThat(row.productDescription()).isEqualTo("Traditional rice, carrot and meat");
        assertThat(row.variantSku()).isEqualTo("SKU-PLOV");
        assertThat(row.unitCode()).isEqualTo("PIECE");
        assertThat(row.priceAmountMinor()).isEqualTo("50000");
        assertThat(row.priceCurrency()).isEqualTo("UZS");
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.imageUrl()).isEqualTo("https://example.test/plov.jpg");
    }

    @Test
    @DisplayName("a blank cell parses as null, not as an empty string")
    void blankCellsAreNull() {
        String csv = """
                product_code,category_code,category_name,product_name,product_description,variant_sku,unit_code,price_amount_minor,price_currency,status,image_url
                PLOV-001,,,Osh,,,,,,,
                """;

        CatalogImportRow row = parser.parse(csv).get(0);

        assertThat(row.categoryCode()).isNull();
        assertThat(row.productDescription()).isNull();
        assertThat(row.variantSku()).isNull();
        assertThat(row.priceAmountMinor()).isNull();
        assertThat(row.imageUrl()).isNull();
    }

    @Test
    @DisplayName("row numbers are 1-based and match source order")
    void rowNumbersMatchSourceOrder() {
        String csv = """
                product_code,category_code,category_name,product_name,product_description,variant_sku,unit_code,price_amount_minor,price_currency,status,image_url
                A,,,Alpha,,,,,,,
                B,,,Beta,,,,,,,
                C,,,Gamma,,,,,,,
                """;

        List<CatalogImportRow> rows = parser.parse(csv);

        assertThat(rows).extracting(CatalogImportRow::rowNumber).containsExactly(1, 2, 3);
        assertThat(rows).extracting(CatalogImportRow::productCode).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("a genuinely malformed document throws rather than silently producing zero rows")
    void malformedDocumentThrows() {
        // An unterminated quote -- commons-csv's own failure mode.
        String malformed = "product_code,product_name\n\"PLOV-001,Osh\n";

        assertThatThrownBy(() -> parser.parse(malformed))
                .isInstanceOf(CatalogImportParser.CatalogImportFormatException.class);
    }

    @Test
    @DisplayName("export fills the same template import reads, and a re-parse of it round-trips the fields")
    void exportRoundTripsThroughParse() {
        CatalogExportRow exportRow = new CatalogExportRow(
                "PLOV-001",
                "HOT",
                "Issiq taomlar",
                "Osh",
                "Traditional rice",
                "SKU-PLOV",
                "PIECE",
                50_000L,
                "UZS",
                "ACTIVE");

        String csv = parser.export(List.of(exportRow));
        List<CatalogImportRow> reparsed = parser.parse(csv);

        assertThat(reparsed).hasSize(1);
        CatalogImportRow row = reparsed.get(0);
        assertThat(row.productCode()).isEqualTo("PLOV-001");
        assertThat(row.categoryCode()).isEqualTo("HOT");
        assertThat(row.productName()).isEqualTo("Osh");
        assertThat(row.variantSku()).isEqualTo("SKU-PLOV");
        assertThat(row.priceAmountMinor()).isEqualTo("50000");
        assertThat(row.priceCurrency()).isEqualTo("UZS");
        assertThat(row.status()).isEqualTo("ACTIVE");
        // Export never fills image_url -- there is no URL to hand back for an
        // already-stored asset, only the asset itself.
        assertThat(row.imageUrl()).isNull();
    }

    @Test
    @DisplayName("an unpriced export row leaves the price columns blank rather than writing a zero")
    void exportLeavesUnpricedRowsBlank() {
        CatalogExportRow exportRow =
                new CatalogExportRow("PLOV-001", null, null, "Osh", null, null, "PIECE", null, null, "ACTIVE");

        String csv = parser.export(List.of(exportRow));

        CatalogImportRow row = parser.parse(csv).get(0);
        assertThat(row.priceAmountMinor()).isNull();
        assertThat(row.priceCurrency()).isNull();
    }
}
