package uz.horecaos.platform.catalog.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.application.MxikMappingProfile.Column;
import uz.horecaos.platform.catalog.application.MxikMappingProfile.MxikImportFormatException;

/**
 * Reads the official ИКПУ/MXIK file into rows keyed by the profile's logical columns (ADR 0173).
 *
 * <p>The spreadsheet stack is the catalog import's own ({@code fastexcel} for {@code .xlsx}, {@code
 * commons-csv} for CSV); format follows the file extension and is never sniffed. It reads a file as bytes, not
 * the Base64-in-JSON text the catalog import takes: the caller (the controller) decodes first, because the raw
 * bytes are what is hashed and kept. It parses and maps; it does not judge a row. Blank codes, duplicates and
 * orphan parents are row errors the importer reports, not parse failures.
 *
 * <p>An {@code .xlsx} cell is read as the text the sheet shows. A seventeen-digit code stored as a number has
 * already lost digits by the time any reader sees it, so the importer reports such a row instead of loading a
 * wrong code (see {@link MxikReferenceImporter}).
 */
@Component
public class MxikFileParser {

    /** More rows than any real national list; a sheet beyond this is the wrong file, not a big one. */
    public static final int MAX_ROWS = 600_000;

    /** One data row: its 1-based position in the file (the header is row 0) and its cells by logical column. */
    public record FileRow(int row, Map<Column, String> values) {}

    /** The result of reading a file. */
    public record ParsedFile(MxikMappingProfile profile, List<String> headers, List<FileRow> rows) {}

    public ParsedFile parse(String fileName, byte[] bytes) {
        MxikMappingProfile profile = MxikMappingProfile.forFile(fileName);
        return profile == MxikMappingProfile.TASNIF_XLSX_V1 ? parseXlsx(profile, bytes) : parseCsv(profile, bytes);
    }

    private ParsedFile parseCsv(MxikMappingProfile profile, byte[] bytes) {
        byte[] content = withoutByteOrderMark(bytes);
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build();
        try (CSVParser parser = CSVParser.parse(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8), format)) {
            List<String> headers = parser.getHeaderNames();
            Map<Column, String> mapping = mappingOf(profile, headers);
            List<FileRow> rows = new ArrayList<>();
            int row = 0;
            for (CSVRecord record : parser) {
                row++;
                if (row > MAX_ROWS) {
                    throw tooManyRows();
                }
                Map<Column, String> values = new EnumMap<>(Column.class);
                mapping.forEach((column, header) -> {
                    String cell = record.isMapped(header) && record.isSet(header)
                            ? record.get(header).strip()
                            : "";
                    if (!cell.isEmpty()) {
                        values.put(column, cell);
                    }
                });
                if (!values.isEmpty()) {
                    rows.add(new FileRow(row, values));
                }
            }
            return new ParsedFile(profile, headers, rows);
        } catch (IOException | UncheckedIOException malformed) {
            throw new MxikImportFormatException(
                    "CSV_UNREADABLE", "The CSV file could not be parsed: " + malformed.getMessage());
        } catch (IllegalArgumentException duplicateHeader) {
            throw new MxikImportFormatException("DUPLICATE_HEADER", "The CSV header names a column twice");
        }
    }

    private ParsedFile parseXlsx(MxikMappingProfile profile, byte[] bytes) {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes))) {
            List<Row> all;
            try (Stream<Row> rows = workbook.getFirstSheet().openStream()) {
                all = rows.limit(MAX_ROWS + 2L).toList();
            }
            if (all.size() > MAX_ROWS + 1) {
                throw tooManyRows();
            }
            if (all.isEmpty()) {
                throw new MxikImportFormatException("FILE_EMPTY", "The workbook's first sheet has no rows");
            }
            Row headerRow = all.get(0);
            List<String> headers = new ArrayList<>();
            for (int cell = 0; cell < headerRow.getCellCount(); cell++) {
                headers.add(headerRow.getCellText(cell).strip());
            }
            Map<Column, String> mapping = mappingOf(profile, headers);
            Map<Column, Integer> indexOf = new EnumMap<>(Column.class);
            mapping.forEach((column, header) -> indexOf.put(column, headers.indexOf(header)));

            List<FileRow> rows = new ArrayList<>();
            for (int index = 1; index < all.size(); index++) {
                Row sheetRow = all.get(index);
                Map<Column, String> values = new EnumMap<>(Column.class);
                indexOf.forEach((column, position) -> {
                    String cell = sheetRow.getCellText(position).strip();
                    if (!cell.isEmpty()) {
                        values.put(column, cell);
                    }
                });
                if (!values.isEmpty()) {
                    rows.add(new FileRow(index, values));
                }
            }
            return new ParsedFile(profile, headers, rows);
        } catch (MxikImportFormatException refused) {
            throw refused;
        } catch (IOException | RuntimeException malformed) {
            throw new MxikImportFormatException(
                    "XLSX_UNREADABLE", "The workbook could not be parsed: " + malformed.getMessage());
        }
    }

    private static Map<Column, String> mappingOf(MxikMappingProfile profile, List<String> headers) {
        return profile.resolve(headers)
                .orElseThrow(() -> new MxikImportFormatException(
                        "REQUIRED_COLUMN_MISSING",
                        "The file has no code column, or no Russian or Uzbek label column, under any name "
                                + profile.name()
                                + " knows"));
    }

    private static MxikImportFormatException tooManyRows() {
        return new MxikImportFormatException(
                "TOO_MANY_ROWS", "The file has more than the " + MAX_ROWS + " rows this importer accepts");
    }

    private static byte[] withoutByteOrderMark(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return bytes;
    }
}
