package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Sheet;
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

    // ---------------------------------------------------------------- row 4.5b: .xlsx

    @Test
    @DisplayName("row 4.5b: the workbook template carries an Import, an Examples and a Reference sheet")
    void templateWorkbookCarriesThreeSheets() throws IOException {
        byte[] workbook = parser.templateWorkbook();

        try (ReadableWorkbook readable = new ReadableWorkbook(new ByteArrayInputStream(workbook))) {
            List<String> sheetNames = readable.getSheets().map(Sheet::getName).toList();
            assertThat(sheetNames).containsExactly("Import", "Examples", "Reference");

            Sheet importSheet = readable.findSheet("Import").orElseThrow();
            assertThat(rowsOf(importSheet)).hasSize(1);
            assertThat(rowsOf(importSheet).get(0).getCellText(0)).isEqualTo("product_code");

            Sheet examples = readable.findSheet("Examples").orElseThrow();
            // Header plus the two example rows the workbook writes.
            assertThat(rowsOf(examples)).hasSize(3);
            assertThat(rowsOf(examples).get(1).getCellText(0)).isEqualTo("BURGER-CLASSIC");

            Sheet reference = readable.findSheet("Reference").orElseThrow();
            List<String> referencedColumns = rowsOf(reference).stream()
                    .skip(1)
                    .map(row -> row.getCellText(0))
                    .toList();
            assertThat(referencedColumns).contains("status", "unit_code");
        }
    }

    /**
     * The workbook template's own header row parses back to zero rows,
     * exactly as {@link #templateIsHeaderOnly} already proves for the CSV
     * template — the identical guarantee, in the other format.
     */
    @Test
    @DisplayName("row 4.5b: the workbook template's Import sheet parses to zero rows")
    void templateWorkbookImportSheetParsesEmpty() {
        String base64 = Base64.getEncoder().encodeToString(parser.templateWorkbook());

        assertThat(parser.parse("catalog-import-template.xlsx", base64)).isEmpty();
    }

    @Test
    @DisplayName("row 4.5b: an uploaded .xlsx parses through the same fields the CSV path does")
    void parsesXlsxUpload() {
        String base64 = Base64.getEncoder().encodeToString(oneRowWorkbook());

        List<CatalogImportRow> rows = parser.parse("upload.XLSX", base64);

        assertThat(rows).hasSize(1);
        CatalogImportRow row = rows.get(0);
        assertThat(row.rowNumber()).isEqualTo(1);
        assertThat(row.productCode()).isEqualTo("PLOV-001");
        assertThat(row.categoryCode()).isEqualTo("HOT");
        assertThat(row.productName()).isEqualTo("Osh");
        assertThat(row.variantSku()).isEqualTo("SKU-PLOV");
        assertThat(row.priceAmountMinor()).isEqualTo("50000");
        assertThat(row.priceCurrency()).isEqualTo("UZS");
        assertThat(row.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("row 4.5b: a blank row in the middle of an .xlsx upload is skipped, not parsed as an empty product")
    void skipsBlankXlsxRows() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Test", "1.0")) {
            Worksheet sheet = workbook.newWorksheet("Import");
            sheet.value(0, 0, "product_code");
            sheet.value(0, 1, "product_name");
            sheet.value(1, 0, "A");
            sheet.value(1, 1, "Alpha");
            // Row index 2 is materialized (an empty string, so the row
            // exists in the sheet at all) but carries no real content -- the
            // shape a filled template most often comes back in, a trailing
            // or in-between empty row rather than a genuinely absent one.
            sheet.value(2, 0, "");
            sheet.value(3, 0, "B");
            sheet.value(3, 1, "Beta");
        }

        List<CatalogImportRow> rows =
                parser.parse("upload.xlsx", Base64.getEncoder().encodeToString(out.toByteArray()));

        assertThat(rows).extracting(CatalogImportRow::productCode).containsExactly("A", "B");
    }

    @Test
    @DisplayName("row 4.5b: a document that is not a real workbook refuses rather than crashing")
    void malformedXlsxThrows() {
        String notAWorkbook = Base64.getEncoder().encodeToString("this is not a zip file".getBytes());

        assertThatThrownBy(() -> parser.parse("upload.xlsx", notAWorkbook))
                .isInstanceOf(CatalogImportParser.CatalogImportFormatException.class);
    }

    @Test
    @DisplayName("row 4.5b: content that is not valid Base64 refuses rather than crashing")
    void nonBase64XlsxContentThrows() {
        assertThatThrownBy(() -> parser.parse("upload.xlsx", "not base64 at all !!"))
                .isInstanceOf(CatalogImportParser.CatalogImportFormatException.class);
    }

    @Test
    @DisplayName("row 4.5b: decoded .xlsx content above the size cap refuses before a workbook is ever opened")
    void oversizedXlsxContentRefusesBeforeOpeningTheWorkbook() {
        // Not a real workbook at all -- the point is that the size check
        // runs before ReadableWorkbook is ever constructed from these bytes,
        // so it does not matter that they are not a valid zip.
        byte[] tooLarge = new byte[CatalogImportParser.MAX_DECODED_XLSX_BYTES + 1];
        String base64 = Base64.getEncoder().encodeToString(tooLarge);

        assertThatThrownBy(() -> parser.parse("upload.xlsx", base64))
                .isInstanceOf(CatalogImportParser.CatalogImportFormatException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    @DisplayName("row 4.5b: an .xlsx with more rows than the cap refuses instead of materializing them all -- "
            + "the DEFLATE-amplification guard a small, highly compressed upload could otherwise defeat")
    void tooManyXlsxRowsRefusesRatherThanMaterializingThemAll() throws IOException {
        String base64 = Base64.getEncoder().encodeToString(manyRowWorkbook(CatalogImportParser.MAX_XLSX_ROWS + 1));

        assertThatThrownBy(() -> parser.parse("upload.xlsx", base64))
                .isInstanceOf(CatalogImportParser.CatalogImportFormatException.class)
                .hasMessageContaining("more than")
                .hasMessageContaining(String.valueOf(CatalogImportParser.MAX_XLSX_ROWS));
    }

    private static List<org.dhatim.fastexcel.reader.Row> rowsOf(Sheet sheet) throws IOException {
        return sheet.read();
    }

    /** A header row plus {@code dataRows} minimal, one-cell data rows. */
    private static byte[] manyRowWorkbook(int dataRows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Test", "1.0")) {
            Worksheet sheet = workbook.newWorksheet("Import");
            sheet.value(0, 0, "product_code");
            for (int r = 1; r <= dataRows; r++) {
                sheet.value(r, 0, "P-" + r);
            }
        }
        return out.toByteArray();
    }

    private static byte[] oneRowWorkbook() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Test", "1.0")) {
            Worksheet sheet = workbook.newWorksheet("Import");
            List<String> header = List.of(
                    "product_code",
                    "category_code",
                    "category_name",
                    "product_name",
                    "product_description",
                    "variant_sku",
                    "unit_code",
                    "price_amount_minor",
                    "price_currency",
                    "status",
                    "image_url");
            for (int c = 0; c < header.size(); c++) {
                sheet.value(0, c, header.get(c));
            }
            List<String> values = List.of(
                    "PLOV-001",
                    "HOT",
                    "Issiq taomlar",
                    "Osh",
                    "Traditional rice, carrot and meat",
                    "SKU-PLOV",
                    "PIECE",
                    "50000",
                    "UZS",
                    "ACTIVE",
                    "https://example.test/plov.jpg");
            for (int c = 0; c < values.size(); c++) {
                sheet.value(1, c, values.get(c));
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }
}
