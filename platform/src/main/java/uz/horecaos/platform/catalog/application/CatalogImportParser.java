package uz.horecaos.platform.catalog.application;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A UTF-8 CSV reading and template for the catalog import (row 4.5b),
 * modelled on {@code CustomerCsvImportParser}: header-driven, tolerant of
 * column naming, one row per parsed input line, 1-based.
 *
 * <p><b>CSV only.</b> {@code platform/pom.xml} carries no spreadsheet library
 * (no Apache POI, no equivalent) at the time this wave was built — checked
 * directly rather than assumed — and this wave does not add one, per the
 * brief's own instruction to say so rather than reach for a new dependency.
 * A merchant exporting from Excel or Google Sheets as CSV/UTF-8 is the
 * supported path; native {@code .xlsx} is a follow-up once a library is
 * actually chosen.
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

    /** Parses the whole document, rows in source order, 1-based. */
    public List<CatalogImportRow> parse(String content) {
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
