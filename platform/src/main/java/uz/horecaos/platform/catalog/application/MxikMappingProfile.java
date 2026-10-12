package uz.horecaos.platform.catalog.application;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How the official ИКПУ/MXIK file's columns map onto {@code catalog.mxik_reference} (ADR 0173).
 *
 * <p>Code-owned, like the closed sets everywhere else: <strong>nothing about the real file is assumed</strong>.
 * The file's exact column names, whether it carries a level or both languages, and whether package and unit
 * lists are columns are the open inputs the first real file answers. Until then the aliases below are the
 * shape of the committed fixture ({@code src/test/resources/mxik/}), and an importer that meets a header none
 * of them names <em>stops and says so</em> instead of guessing. Updating this class against the first real
 * file is the whole of "writing the profile".
 *
 * <p>Two profiles name the same columns for the two formats the importer reads; format follows the file
 * extension, never a byte sniff.
 */
public enum MxikMappingProfile {
    TASNIF_XLSX_V1,
    TASNIF_CSV_V1;

    /** The logical columns of the reference table a file may fill. */
    public enum Column {
        CODE(true),
        PARENT_CODE(false),
        LABEL_RU(false),
        LABEL_UZ(false),
        LABEL_EN(false),
        PACKAGE_CODES(false),
        UNIT_CODES(false),
        REPLACED_BY(false),
        LEVEL(false),
        VALID_FROM(false);

        private final boolean required;

        Column(boolean required) {
            this.required = required;
        }

        /** A file without a required column is not this profile's file. */
        public boolean required() {
            return required;
        }
    }

    /** The header spellings each logical column is read from, normalised. */
    private static final Map<Column, List<String>> ALIASES = aliases();

    private static Map<Column, List<String>> aliases() {
        Map<Column, List<String>> byColumn = new EnumMap<>(Column.class);
        byColumn.put(
                Column.CODE, List.of("code", "mxik", "mxik code", "mxik_code", "ikpu", "ikpu code", "код", "икпу"));
        byColumn.put(
                Column.PARENT_CODE, List.of("parent code", "parent_code", "parent", "родитель", "родительский код"));
        byColumn.put(
                Column.LABEL_RU,
                List.of("label ru", "label_ru", "name ru", "name_ru", "наименование", "наименование (ru)", "название"));
        byColumn.put(
                Column.LABEL_UZ, List.of("label uz", "label_uz", "name uz", "name_uz", "nomi", "наименование (uz)"));
        byColumn.put(Column.LABEL_EN, List.of("label en", "label_en", "name en", "name_en", "наименование (en)"));
        byColumn.put(
                Column.PACKAGE_CODES,
                List.of(
                        "package codes",
                        "package_codes",
                        "default package codes",
                        "default_package_codes",
                        "коды упаковки"));
        byColumn.put(
                Column.UNIT_CODES,
                List.of("unit codes", "unit_codes", "default unit codes", "default_unit_codes", "коды единиц"));
        byColumn.put(
                Column.REPLACED_BY,
                List.of(
                        "replaced by",
                        "replaced_by",
                        "replaced by code",
                        "replaced_by_code",
                        "successor",
                        "заменён на",
                        "заменен на"));
        byColumn.put(Column.LEVEL, List.of("level", "уровень"));
        byColumn.put(Column.VALID_FROM, List.of("valid from", "valid_from", "действует с"));
        return byColumn;
    }

    /** The profile that reads a file of this name, by extension: {@code .xlsx} is a workbook, anything else CSV. */
    public static MxikMappingProfile forFile(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? TASNIF_XLSX_V1 : TASNIF_CSV_V1;
    }

    /** A header spelling collapsed the way {@link #resolve} compares: lower case, single spaces. */
    static String normalise(String header) {
        return header.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Maps a file's header row to logical columns.
     *
     * @return the header's text at each recognised column, or empty when a required column is missing
     * @throws MxikImportFormatException {@code UNKNOWN_HEADER} for a header no alias names, naming it
     */
    public Optional<Map<Column, String>> resolve(List<String> headers) {
        Map<Column, String> found = new EnumMap<>(Column.class);
        List<String> unknown = new java.util.ArrayList<>();
        for (String header : headers) {
            String normalised = normalise(header);
            if (normalised.isEmpty()) {
                continue;
            }
            Column match = null;
            for (Map.Entry<Column, List<String>> entry : ALIASES.entrySet()) {
                if (entry.getValue().contains(normalised)) {
                    match = entry.getKey();
                    break;
                }
            }
            if (match == null) {
                unknown.add(header.strip());
            } else if (found.putIfAbsent(match, header) != null) {
                throw new MxikImportFormatException(
                        "DUPLICATE_HEADER", "Two columns of the file both read as " + match + ": " + header);
            }
        }
        if (!unknown.isEmpty()) {
            // Stops and says so. A guessed mapping against an unseen file is the way a legal list gets loaded wrong.
            throw new MxikImportFormatException(
                    "UNKNOWN_HEADER",
                    "The file has columns this mapping profile (" + name() + ") does not know: "
                            + String.join(", ", unknown));
        }
        for (Column column : Column.values()) {
            if (column.required() && !found.containsKey(column)) {
                return Optional.empty();
            }
        }
        if (!found.containsKey(Column.LABEL_RU) && !found.containsKey(Column.LABEL_UZ)) {
            return Optional.empty();
        }
        return Optional.of(new LinkedHashMap<>(found));
    }

    /** A file the importer cannot read as this profile, with a stable code. */
    public static final class MxikImportFormatException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        public MxikImportFormatException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
