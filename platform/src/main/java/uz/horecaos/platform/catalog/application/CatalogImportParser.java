package uz.horecaos.platform.catalog.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A UTF-8 CSV and {@code .xlsx} reading and template for the catalog import
 * (row 4.5b), modelled on {@code CustomerCsvImportParser}: header-driven,
 * tolerant of column naming, one row per parsed input line, 1-based.
 *
 * <p><b>Format is decided by the file name's extension</b> ({@link
 * #parse(String, String)}), never sniffed from the bytes: a merchant who
 * renames a CSV to {@code .xlsx} gets a clear parse failure rather than a
 * guess. {@code .xlsx} content travels the identical {@code content} string
 * field CSV always has — this endpoint has never taken a multipart body —
 * Base64-encoded by the caller, since an {@code .xlsx} is a binary zip
 * archive and the field is JSON text.
 *
 * <p>fastexcel (org.dhatim) rather than Apache POI: a small, actively
 * maintained streaming reader/writer, chosen over POI's much larger surface
 * for a job that only ever needs one sheet of text and number cells. See
 * {@code pom.xml}'s own dependency comment for the version and the excluded
 * test-scoped POI transitive.
 */
@Component
public class CatalogImportParser {

    /** The exact column order {@link #template()} writes and {@link #export} fills — also what a re-imported export round-trips through unchanged. */
    static final List<String> COLUMNS = List.of(
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

    /**
     * Parses the whole document, rows in source order, 1-based.
     *
     * @param sourceFileName decides the format: a name ending {@code .xlsx}
     *                       (case-insensitive) is read as a Base64-encoded
     *                       workbook, everything else as CSV text
     */
    public List<CatalogImportRow> parse(String sourceFileName, String content) {
        return isXlsx(sourceFileName) ? parseXlsx(content) : parseCsv(content);
    }

    /** CSV only — kept for the handful of call sites (tests, mainly) that never carry a file name. */
    public List<CatalogImportRow> parse(String content) {
        return parseCsv(content);
    }

    private static boolean isXlsx(String sourceFileName) {
        return sourceFileName.toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    private List<CatalogImportRow> parseCsv(String content) {
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();
        try (CSVParser parser = CSVParser.parse(new StringReader(content), format)) {
            return parser.stream()
                    .map(record -> toRow(record.toMap(), (int) record.getRecordNumber()))
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            // commons-csv only throws for a genuinely malformed CSV document
            // (an unterminated quote, for example) -- nothing a per-row
            // rejection can express, because there are no rows yet. See
            // CustomerCsvImportParser's own catch for why both shapes land here.
            throw new CatalogImportFormatException("The CSV document could not be parsed: " + e.getMessage());
        }
    }

    /**
     * Reads the workbook's first sheet: row 1 is the header (matched the same
     * tolerant way {@link #normalizeKey} already matches a CSV header), every
     * row after it one import row. An entirely blank row (every cell empty)
     * is skipped rather than parsed as a row with no product code, the same
     * forgiveness a merchant's own copy-paste habits need — a trailing blank
     * row under the last real one is the single most common shape a filled
     * template comes back in.
     */
    private List<CatalogImportRow> parseXlsx(String base64Content) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException notBase64) {
            throw new CatalogImportFormatException("The .xlsx document is not valid Base64: " + notBase64.getMessage());
        }
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes))) {
            List<Row> all = workbook.getFirstSheet().read();
            if (all.isEmpty()) {
                return List.of();
            }
            Row header = all.get(0);
            Map<Integer, String> columnByIndex = new LinkedHashMap<>();
            for (int c = 0; c < header.getCellCount(); c++) {
                String cell = header.getCellText(c).strip();
                if (!cell.isEmpty()) {
                    columnByIndex.put(c, cell);
                }
            }
            List<CatalogImportRow> parsed = new ArrayList<>();
            int rowNumber = 0;
            for (int r = 1; r < all.size(); r++) {
                Row row = all.get(r);
                rowNumber++;
                Map<String, String> raw = new LinkedHashMap<>();
                columnByIndex.forEach((index, columnName) -> {
                    String text = row.getCellText(index).strip();
                    if (!text.isEmpty()) {
                        raw.put(columnName, text);
                    }
                });
                if (raw.isEmpty()) {
                    continue;
                }
                parsed.add(toRow(raw, rowNumber));
            }
            return parsed;
        } catch (IOException | RuntimeException malformed) {
            // fastexcel-reader throws a range of unchecked exceptions for a
            // document that is not actually a valid OOXML workbook (a
            // renamed CSV, a corrupted upload); all land here as the same
            // per-document parse failure the CSV path already has.
            throw new CatalogImportFormatException("The .xlsx document could not be parsed: " + malformed.getMessage());
        }
    }

    /**
     * The empty template a merchant downloads and fills — header row only,
     * UTF-8, this import's own column order.
     *
     * <p>Only {@code product_code} and {@code product_name} are required.
     * Every other column may be left blank on a row that updates an
     * existing product, and a blank one there always means "leave this
     * field as it already is" — never "clear it". In particular, a blank
     * {@code status}, {@code unit_code} or {@code variant_sku} column on a
     * corrective re-import (one that only fixes, say, {@code
     * price_amount_minor}) leaves the product's current status, unit and
     * SKU untouched; it can never re-activate an archived product or null
     * out a SKU. See {@link CatalogImportRowService}'s own class doc for the
     * full rule and why {@code create} (a blank cell there defaults to
     * {@code ACTIVE}/{@code PIECE}) is the one case it does not apply to.
     */
    public String template() {
        return writeCsv(List.of());
    }

    /**
     * The same template as {@link #template}, as a real {@code .xlsx}
     * workbook rather than CSV text (row 4.5b's own "template download as a
     * workbook" ask): a first {@code Import} sheet with the header row only
     * — ready to fill and re-upload — an {@code Examples} sheet showing two
     * filled rows (a plain create and a price-only correction, the two
     * shapes {@link CatalogImportRowService}'s own class doc names as the
     * ones a blank cell means something different on), and a {@code
     * Reference} sheet naming the closed vocabularies {@code status} and
     * {@code unit_code} accept — the two columns a merchant is otherwise
     * left guessing at from the CSV template alone.
     */
    public byte[] templateWorkbook() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "HorecaOS", "1.0")) {
            writeHeaderRow(workbook.newWorksheet("Import"));

            Worksheet examples = workbook.newWorksheet("Examples");
            writeHeaderRow(examples);
            writeRow(
                    examples,
                    1,
                    List.of(
                            "BURGER-CLASSIC",
                            "MAIN",
                            "Основные блюда",
                            "Классический бургер",
                            "Говяжья котлета, сыр, соус",
                            "SKU-BURGER-001",
                            "PIECE",
                            "45000",
                            "UZS",
                            "ACTIVE",
                            ""));
            writeRow(examples, 2, List.of("BURGER-CLASSIC", "", "", "", "", "", "", "48000", "UZS", "", ""));

            Worksheet reference = workbook.newWorksheet("Reference");
            reference.value(0, 0, "column");
            reference.value(0, 1, "allowed values");
            writeReferenceRow(reference, 1, "status", "DRAFT, ACTIVE, ARCHIVED");
            writeReferenceRow(reference, 2, "unit_code", "PIECE, KG, LITER, PORTION (tenant's own configured units)");
            writeReferenceRow(
                    reference,
                    3,
                    "price_currency",
                    "ISO 4217, e.g. UZS -- present only together with price_amount_minor");
            writeReferenceRow(
                    reference,
                    4,
                    "price_amount_minor",
                    "Integer minor units (som, not tiyin) -- present only together with price_currency");
        } catch (IOException impossible) {
            // A ByteArrayOutputStream never throws IOException.
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }

    private static void writeHeaderRow(Worksheet sheet) {
        for (int c = 0; c < COLUMNS.size(); c++) {
            sheet.value(0, c, COLUMNS.get(c));
        }
    }

    private static void writeRow(Worksheet sheet, int rowIndex, List<String> values) {
        for (int c = 0; c < values.size(); c++) {
            String value = values.get(c);
            if (!value.isEmpty()) {
                sheet.value(rowIndex, c, value);
            }
        }
    }

    private static void writeReferenceRow(Worksheet sheet, int rowIndex, String column, String allowedValues) {
        sheet.value(rowIndex, 0, column);
        sheet.value(rowIndex, 1, allowedValues);
    }

    /** The brand's catalog, filled into the same template {@link #template} hands out — export/import share one shape by construction. */
    public String export(List<CatalogExportRow> rows) {
        List<Map<String, String>> records =
                rows.stream().map(CatalogImportParser::toRecord).toList();
        return writeCsv(records);
    }

    private String writeCsv(List<Map<String, String>> records) {
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setHeader(COLUMNS.toArray(String[]::new))
                .build();
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, format)) {
            for (Map<String, String> record : records) {
                printer.printRecord(COLUMNS.stream().map(record::get).toList());
            }
        } catch (IOException impossible) {
            // A StringWriter never throws IOException.
            throw new IllegalStateException(impossible);
        }
        return out.toString();
    }

    private static Map<String, String> toRecord(CatalogExportRow row) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("product_code", row.productCode());
        record.put("category_code", row.categoryCode());
        record.put("category_name", row.categoryName());
        record.put("product_name", row.productName());
        record.put("product_description", row.productDescription());
        record.put("variant_sku", row.variantSku());
        record.put("unit_code", row.unitCode());
        record.put(
                "price_amount_minor", row.priceAmountMinor() == null ? null : String.valueOf(row.priceAmountMinor()));
        record.put("price_currency", row.priceCurrency());
        record.put("status", row.status());
        record.put("image_url", null);
        return record;
    }

    private CatalogImportRow toRow(Map<String, ?> raw, int rowNumber) {
        Map<String, String> normalized = normalize(raw);
        return new CatalogImportRow(
                rowNumber,
                normalized.get("product_code"),
                normalized.get("category_code"),
                normalized.get("category_name"),
                normalized.get("product_name"),
                normalized.get("product_description"),
                normalized.get("variant_sku"),
                normalized.get("unit_code"),
                normalized.get("price_amount_minor"),
                normalized.get("price_currency"),
                normalized.get("status"),
                normalized.get("image_url"));
    }

    private static Map<String, String> normalize(Map<String, ?> raw) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String value = String.valueOf(entry.getValue()).strip();
            if (value.isEmpty()) {
                continue;
            }
            normalized.putIfAbsent(normalizeKey(entry.getKey()), value);
        }
        return normalized;
    }

    /** Case- and punctuation-insensitive, matching {@code CustomerCsvImportParser}'s own rule: {@code "Product Code"} and {@code "product-code"} both match {@code product_code}. */
    private static String normalizeKey(String key) {
        return key.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    /** The document could not be parsed as CSV at all -- nothing to report per row. */
    public static final class CatalogImportFormatException extends RuntimeException {
        public CatalogImportFormatException(String message) {
            super(message);
        }
    }

    /** One line of a filled export -- the same eleven columns {@link CatalogImportRow} reads, minus {@code image_url}, which export never fills (there is no URL to hand back for an already-stored asset). */
    public record CatalogExportRow(
            String productCode,
            @Nullable String categoryCode,
            @Nullable String categoryName,
            String productName,
            @Nullable String productDescription,
            @Nullable String variantSku,
            String unitCode,
            @Nullable Long priceAmountMinor,
            @Nullable String priceCurrency,
            String status) {}
}
