package uz.qoida.platform.integration.camel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One parsed ADR 0007 route descriptor from {@code docs/routes/}.
 *
 * <p>Lives in test sources because nothing at runtime has any business reading
 * markdown. A descriptor is a review artifact: its job is to let someone judge a
 * route's timeouts, retry safety, and data classification without reading the
 * builder and three processors, and the only enforcement that can honestly be
 * automated is that the inventory is complete and nothing is left blank. See
 * {@code docs/routes/README.md} for the format this parses.
 */
record RouteDescriptor(Path file, List<String> routeIds, Map<String, String> fields) {

    /** The fields ADR 0007 names, as they appear in the descriptor table. */
    static final List<String> REQUIRED_FIELDS = List.of(
            "Route IDs",
            "Version",
            "Owning module",
            "Owner",
            "Input contract",
            "Output contract",
            "Source",
            "Destination",
            "Service identity",
            "Secret reference type",
            "Connect timeout",
            "Total timeout",
            "Retry classification",
            "Idempotency key",
            "Circuit breaker",
            "Dead-letter destination",
            "PII classification",
            "Expected volume",
            "SLO",
            "Runbook",
            "Dashboard");

    /**
     * Values that look filled in and are not. Without this the required-field
     * check is satisfied by typing a dash, which is how an inventory quietly
     * stops being one.
     */
    private static final List<String> PLACEHOLDERS = List.of("", "-", "—", "?", "tbd", "todo", "n/a", "none");

    private static final Pattern TABLE_ROW = Pattern.compile("^\\|([^|]+)\\|(.+)\\|\\s*$");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    static List<RouteDescriptor> loadAll(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".md"))
                    .filter(file -> !file.getFileName().toString().equals("README.md"))
                    .sorted()
                    .map(RouteDescriptor::parse)
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static RouteDescriptor parse(Path file) {
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                Matcher row = TABLE_ROW.matcher(line);
                if (!row.matches()) {
                    continue;
                }
                String name = row.group(1).trim();
                String value = row.group(2).trim();
                // The field table is the first one in the file; later tables
                // describe outcomes and partners and share the row syntax, so
                // only a known field name is taken and only the first time.
                if (REQUIRED_FIELDS.contains(name)) {
                    fields.putIfAbsent(name, value);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return new RouteDescriptor(file, backtickedValues(fields.getOrDefault("Route IDs", "")), fields);
    }

    private static List<String> backtickedValues(String value) {
        List<String> found = new ArrayList<>();
        Matcher tokens = BACKTICKED.matcher(value);
        while (tokens.find()) {
            found.add(tokens.group(1));
        }
        return List.copyOf(found);
    }

    /** Required field names whose value is missing or is a placeholder. */
    List<String> unansweredFields() {
        return REQUIRED_FIELDS.stream()
                .filter(name -> {
                    String value = fields.get(name);
                    return value == null
                            || PLACEHOLDERS.contains(value.toLowerCase(Locale.ROOT).replace(".", "").trim());
                })
                .toList();
    }

    String name() {
        return file.getFileName().toString();
    }
}
