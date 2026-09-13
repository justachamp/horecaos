package uz.horecaos.platform.customers.application;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A documented, tolerant reading of a generic customer CSV export (row {@code
 * X.13}/{@code 5.1b}) — the non-Telegram counterpart {@code
 * SendPulseContactFileParser} does not cover, because that parser's own
 * required field is a Telegram chat id and rejects any row without one.
 *
 * <p>Column names vary by whatever system a merchant is migrating off of, so
 * this class matches a small set of recognised aliases, case- and
 * punctuation-insensitively, the same way {@code SendPulseContactFileParser}
 * does for its own columns. Phone is the only required field: this is a
 * generic address book, not a messaging-platform audience export, so there
 * is no status column to classify and no subscription state to reject on.
 */
@Component
public class CustomerCsvImportParser {

    private static final List<String> PHONE_KEYS =
            List.of("phone", "phone_number", "phonenumber", "msisdn", "tel", "telephone", "mobile", "contact_phone");

    /** Parses the whole document, rows in source order, 1-based. */
    public List<CustomerCsvImportRow> parse(String content) {
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
        } catch (IOException | java.io.UncheckedIOException e) {
            // commons-csv only throws for a genuinely malformed CSV document
            // (an unterminated quote, for example) — nothing a per-row
            // rejection can express, because there are no rows yet. An
            // unterminated quote surfaces mid-stream, while iterating the
            // parser inside .stream()/.toList() rather than from
            // CSVParser.parse itself, as an UncheckedIOException wrapping
            // the real cause — caught here alongside the plain IOException
            // so neither shape of a malformed document ever reaches a caller
            // as an unclassified RuntimeException.
            throw new CustomerCsvImportFormatException("The CSV document could not be parsed: " + e.getMessage());
        }
    }

    private CustomerCsvImportRow toRow(Map<String, ?> raw, int rowNumber) {
        Map<String, String> normalized = normalize(raw);
        String rawPhone = firstMatch(normalized, PHONE_KEYS);
        if (rawPhone == null) {
            return new CustomerCsvImportRow(rowNumber, null, CustomerCsvImportRejectReason.MISSING_PHONE);
        }
        return new CustomerCsvImportRow(rowNumber, rawPhone, null);
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

    /** Case- and punctuation-insensitive: {@code "Phone Number"} and {@code "phone-number"} both match the same alias. */
    private static String normalizeKey(String key) {
        return key.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static @Nullable String firstMatch(Map<String, String> normalized, List<String> candidateKeys) {
        for (String key : candidateKeys) {
            String value = normalized.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** The document could not be parsed as CSV at all — nothing to report per row. */
    public static final class CustomerCsvImportFormatException extends RuntimeException {
        public CustomerCsvImportFormatException(String message) {
            super(message);
        }
    }
}
