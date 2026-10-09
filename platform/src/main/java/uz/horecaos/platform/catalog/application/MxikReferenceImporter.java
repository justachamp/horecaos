package uz.horecaos.platform.catalog.application;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.application.MxikFileParser.FileRow;
import uz.horecaos.platform.catalog.application.MxikFileParser.ParsedFile;
import uz.horecaos.platform.catalog.application.MxikMappingProfile.Column;

/**
 * Turns the rows of the official ИКПУ/MXIK file into rows fit for {@code catalog.mxik_reference} (ADR 0173),
 * and says what is wrong with the rest.
 *
 * <p>A pure function over a parsed file: no database, no clock, no object store. It refuses what the table
 * would refuse and, more to the point, what would make a wrong list look right: a blank or duplicated code, a
 * code too long for the column, a code a spreadsheet stored as a number (a seventeen-digit number has lost its
 * last digits before any reader sees it), a parent the file does not contain and the list does not hold, a
 * cycle. A row that has one of the two labels only takes that text in both columns instead of being dropped,
 * and the count of those is reported. <strong>It asserts no format for the code itself</strong>: the code's
 * shape belongs to the official list (ADR 0038), so a length or digit pattern here would be this platform
 * deciding what the authority's codes look like.
 */
@Component
public class MxikReferenceImporter {

    /** The table's own column limits ({@code V0028}). */
    static final int CODE_LENGTH = 32;

    static final int LABEL_LENGTH = 400;

    /** A cell a spreadsheet stored as a floating-point number: {@code 7.1311E16}. */
    private static final Pattern SCIENTIFIC = Pattern.compile("^[0-9](\\.[0-9]+)?[eE][+-]?[0-9]+$");

    private static final Pattern LIST_SEPARATOR = Pattern.compile("[;,\\s]+");

    /** The first day a code that the file gives no start for is treated as valid: the 17-digit regime (ADR 0173). */
    public static final LocalDate DEFAULT_VALID_FROM = LocalDate.of(2021, 8, 1);

    /**
     * One row, ready to write.
     *
     * @param ordinal the row's position in the file, 1-based
     * @param depth   the row's depth in the file's own tree, so a parent is written before its children
     */
    public record ReferenceRow(
            int ordinal,
            String code,
            @Nullable String parentCode,
            String labelRu,
            String labelUz,
            @Nullable String labelEn,
            List<String> packageCodes,
            List<Integer> unitCodes,
            @Nullable String replacedByCode,
            @Nullable Short level,
            LocalDate validFrom,
            int depth) {}

    /** One row the importer refused, with a stable code and no row content. */
    public record RowError(int row, @Nullable String code, String detailCode) {}

    /**
     * @param labelsBorrowed rows that had one label only and took it in both columns
     * @param codeLengths    how many accepted-or-refused codes there are of each length, for the human to read
     */
    public record Normalised(
            List<ReferenceRow> rows, List<RowError> errors, int labelsBorrowed, Map<Integer, Integer> codeLengths) {}

    /**
     * @param inTheList whether a code is already a row of the national list, so a delta may name a parent it
     *     does not itself contain
     */
    public Normalised normalise(ParsedFile file, Predicate<String> inTheList) {
        List<RowError> errors = new ArrayList<>();
        Map<String, Draft> byCode = new LinkedHashMap<>();
        Map<Integer, Integer> codeLengths = new TreeMap<>();
        int borrowed = 0;

        for (FileRow row : file.rows()) {
            Map<Column, String> values = row.values();
            String code = values.getOrDefault(Column.CODE, "").strip();
            if (code.isEmpty()) {
                errors.add(new RowError(row.row(), null, "CODE_BLANK"));
                continue;
            }
            codeLengths.merge(code.length(), 1, Integer::sum);
            if (code.length() > CODE_LENGTH) {
                errors.add(new RowError(row.row(), null, "CODE_TOO_LONG"));
                continue;
            }
            if (SCIENTIFIC.matcher(code).matches()) {
                errors.add(new RowError(row.row(), null, "CODE_STORED_AS_NUMBER"));
                continue;
            }
            if (byCode.containsKey(code)) {
                errors.add(new RowError(row.row(), code, "CODE_DUPLICATED"));
                continue;
            }
            String ru = values.getOrDefault(Column.LABEL_RU, "").strip();
            String uz = values.getOrDefault(Column.LABEL_UZ, "").strip();
            if (ru.isEmpty() && uz.isEmpty()) {
                errors.add(new RowError(row.row(), code, "LABEL_MISSING"));
                continue;
            }
            boolean borrowedHere = ru.isEmpty() || uz.isEmpty();
            if (ru.isEmpty()) {
                ru = uz;
            } else if (uz.isEmpty()) {
                uz = ru;
            }
            String en = values.getOrDefault(Column.LABEL_EN, "").strip();
            if (ru.length() > LABEL_LENGTH || uz.length() > LABEL_LENGTH || en.length() > LABEL_LENGTH) {
                errors.add(new RowError(row.row(), code, "LABEL_TOO_LONG"));
                continue;
            }
            List<Integer> unitCodes = new ArrayList<>();
            boolean unitsOk = true;
            for (String token : tokens(values.get(Column.UNIT_CODES))) {
                try {
                    unitCodes.add(Integer.valueOf(token));
                } catch (NumberFormatException notNumeric) {
                    unitsOk = false;
                }
            }
            if (!unitsOk) {
                errors.add(new RowError(row.row(), code, "UNIT_CODE_NOT_A_NUMBER"));
                continue;
            }
            Short level = null;
            String levelText = values.get(Column.LEVEL);
            if (levelText != null) {
                try {
                    level = Short.valueOf(levelText.strip());
                } catch (NumberFormatException notNumeric) {
                    errors.add(new RowError(row.row(), code, "LEVEL_NOT_A_NUMBER"));
                    continue;
                }
            }
            LocalDate validFrom = DEFAULT_VALID_FROM;
            String validFromText = values.get(Column.VALID_FROM);
            if (validFromText != null) {
                try {
                    validFrom = LocalDate.parse(validFromText.strip());
                } catch (DateTimeParseException unreadable) {
                    errors.add(new RowError(row.row(), code, "VALID_FROM_UNREADABLE"));
                    continue;
                }
            }
            String parent = blankToNull(values.get(Column.PARENT_CODE));
            if (parent != null && parent.equals(code)) {
                errors.add(new RowError(row.row(), code, "PARENT_IS_SELF"));
                continue;
            }
            String replacedBy = blankToNull(values.get(Column.REPLACED_BY));
            if (replacedBy != null && replacedBy.length() > CODE_LENGTH) {
                errors.add(new RowError(row.row(), code, "CODE_TOO_LONG"));
                continue;
            }
            if (borrowedHere) {
                borrowed++;
            }
            byCode.put(
                    code,
                    new Draft(
                            row.row(),
                            code,
                            parent,
                            ru,
                            uz,
                            blankToNull(en),
                            tokens(values.get(Column.PACKAGE_CODES)),
                            unitCodes,
                            replacedBy,
                            level,
                            validFrom));
        }

        // A parent the file does not contain and the list does not hold drops its row, and then its children:
        // repeated until nothing more falls out.
        Set<String> dropped = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Draft draft : byCode.values()) {
                if (dropped.contains(draft.code) || draft.parent == null) {
                    continue;
                }
                boolean parentPresent = (byCode.containsKey(draft.parent) && !dropped.contains(draft.parent))
                        || inTheList.test(draft.parent);
                if (!parentPresent) {
                    dropped.add(draft.code);
                    errors.add(new RowError(draft.row, draft.code, "PARENT_UNKNOWN"));
                    changed = true;
                }
            }
        }

        // Depth, parents first. A row whose chain never reaches a root is in a cycle.
        Map<String, Integer> depthOf = new HashMap<>();
        for (Draft draft : byCode.values()) {
            if (dropped.contains(draft.code)) {
                continue;
            }
            int depth = depth(draft, byCode, dropped, depthOf, new HashSet<>());
            if (depth < 0) {
                dropped.add(draft.code);
                errors.add(new RowError(draft.row, draft.code, "PARENT_CYCLE"));
            }
        }

        List<ReferenceRow> rows = new ArrayList<>();
        for (Draft draft : byCode.values()) {
            if (dropped.contains(draft.code) || !depthOf.containsKey(draft.code)) {
                continue;
            }
            rows.add(new ReferenceRow(
                    draft.row,
                    draft.code,
                    draft.parent,
                    draft.ru,
                    draft.uz,
                    draft.en,
                    draft.packages,
                    draft.units,
                    draft.replacedBy,
                    draft.level,
                    draft.validFrom,
                    depthOf.get(draft.code)));
        }
        errors.sort(java.util.Comparator.comparingInt(RowError::row));
        return new Normalised(List.copyOf(rows), List.copyOf(errors), borrowed, codeLengths);
    }

    private static int depth(
            Draft draft,
            Map<String, Draft> byCode,
            Set<String> dropped,
            Map<String, Integer> depthOf,
            Set<String> path) {
        Integer known = depthOf.get(draft.code);
        if (known != null) {
            return known;
        }
        if (!path.add(draft.code)) {
            return -1;
        }
        int depth = 0;
        Draft parent = draft.parent == null ? null : byCode.get(draft.parent);
        if (parent != null && dropped.contains(parent.code)) {
            // Its parent was dropped (a cycle found a moment ago): left in, it would name a parent that is
            // never written and the apply would stop on the foreign key.
            return -1;
        }
        if (parent != null) {
            int parentDepth = depth(parent, byCode, dropped, depthOf, path);
            if (parentDepth < 0) {
                return -1;
            }
            depth = parentDepth + 1;
        }
        path.remove(draft.code);
        depthOf.put(draft.code, depth);
        return depth;
    }

    private static List<String> tokens(@Nullable String cell) {
        if (cell == null || cell.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : LIST_SEPARATOR.split(cell.strip())) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record Draft(
            int row,
            String code,
            @Nullable String parent,
            String ru,
            String uz,
            @Nullable String en,
            List<String> packages,
            List<Integer> units,
            @Nullable String replacedBy,
            @Nullable Short level,
            LocalDate validFrom) {}
}
