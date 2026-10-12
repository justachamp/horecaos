package uz.horecaos.platform.iam.application.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0160, «Privacy» and decision 6, held where a future edit would have to get past it: the preference
 * code logs nothing a person saved, measures nothing but an outcome, writes no audit fact and no event, and
 * is read by no other module.
 *
 * <p>These read the source, on purpose. The runtime proofs (a captured log, a sealed row, an unchanged audit
 * table) are in {@code StaffPreferencesEndpointTests} and {@code StaffPreferencesServiceTests}; what a test
 * of a run cannot see is the log call on a path no test takes. A rule that is wrong in the direction of a
 * false alarm costs a reworded variable; one wrong the other way puts a typed label in a log line.
 */
class StaffPreferencesPrivacyTests {

    private static final Path MAIN = Path.of("src/main/java/uz/horecaos/platform");

    private static final List<String> PREFERENCE_CODE = List.of(
            "iam/application/staff/StaffPreferencesService.java",
            "iam/application/staff/StaffPreferenceSchemas.java",
            "iam/application/staff/StaffPreferencesCache.java",
            "iam/infrastructure/persistence/JdbcStaffPreferencesStore.java",
            "iam/web/StaffPreferencesController.java");

    /** Words that name what a person saved or what holds it. */
    private static final Pattern A_DOCUMENT = Pattern.compile(
            "(?i)\\b(document|label|plaintext|text|body|stored|view|views|columns|filters|request|response)\\b");

    /** An import of the service, the store or the cache, or SQL against the table. */
    private static final Pattern USES_THE_STORE = Pattern.compile(
            "import [\\w.]*\\.(StaffPreferencesService|JdbcStaffPreferencesStore|StaffPreferencesCache);|iam\\.staff_preferences");

    private static String read(String relative) {
        try {
            return Files.readString(MAIN.resolve(relative));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The statements of {@code source} that begin with one of {@code starts}, up to the closing semicolon. */
    private static List<String> statementsStartingWith(String source, String... starts) {
        List<String> found = new ArrayList<>();
        for (String start : starts) {
            Matcher matcher = Pattern.compile(Pattern.quote(start) + "[^;]*;", Pattern.DOTALL)
                    .matcher(source);
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    @Test
    @DisplayName("no log call in the preference code names a document, a label or a body")
    void nothingASavedPreferenceIsLogged() {
        int logCalls = 0;
        List<String> offending = new ArrayList<>();
        for (String file : PREFERENCE_CODE) {
            for (String statement : statementsStartingWith(
                    read(file), "log.debug(", "log.info(", "log.warn(", "log.error(", "log.trace(")) {
                logCalls++;
                // The message may say a document failed its check; its arguments may not be one.
                String arguments =
                        statement.substring(statement.indexOf(',') < 0 ? statement.length() : statement.indexOf(','));
                if (A_DOCUMENT.matcher(arguments).find()) {
                    offending.add(file + ": " + statement.replaceAll("\\s+", " "));
                }
            }
        }

        assertThat(logCalls)
                .as("the scan finds the one log call the service has; a scan that found none would pass for ever")
                .isGreaterThanOrEqualTo(1);
        assertThat(offending).isEmpty();
    }

    @Test
    @DisplayName("the only metric is a counter tagged with an outcome: no identity, surface or document")
    void theOnlyMeasurementIsAnOutcome() {
        String service = read("iam/application/staff/StaffPreferencesService.java");

        List<String> meterCalls = statementsStartingWith(service, "meters.");
        assertThat(meterCalls).hasSize(1);
        assertThat(meterCalls.getFirst().replaceAll("\\s+", " ").replace(" .", "."))
                .isEqualTo(
                        "meters.counter(\"horecaos.iam.staff.preferences.writes\", \"outcome\", outcome).increment();");
        for (String file : PREFERENCE_CODE) {
            assertThat(read(file))
                    .as(file)
                    .doesNotContain("Tag.of(")
                    .doesNotContain("MDC.put(")
                    .doesNotContain("Tracer");
        }
    }

    @Test
    @DisplayName("a preference write is neither a business act nor a security one: no audit fact, no event")
    void nothingIsAuditedAndNothingIsPublished() {
        for (String file : PREFERENCE_CODE) {
            String source = read(file);
            assertThat(source)
                    .as(file)
                    .doesNotContain("AuditRecorder")
                    .doesNotContain("AuditFact")
                    .doesNotContain("ChangeDocuments")
                    .doesNotContain("StaffSecurityAudit")
                    .doesNotContain("ApplicationEventPublisher")
                    .doesNotContain("publishEvent")
                    .doesNotContain("OutboxWriter");
        }
    }

    @Test
    @DisplayName("no other module reads a preference")
    void nobodyElseReadsOne() throws IOException {
        List<String> readers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (PREFERENCE_CODE.contains(relative)
                        || relative.startsWith("iam/web/OrderBoardPreferences")
                        || relative.startsWith("iam/web/ShellPreferences")) {
                    continue;
                }
                String source = Files.readString(file);
                if (USES_THE_STORE.matcher(source).find()) {
                    readers.add(relative);
                }
            }
        }

        // The member's anonymisation deletes the rows (the one write the record asks of that class) and
        // reads nothing; nothing else, in iam or outside it, so much as names the store.
        assertThat(readers).containsExactly("iam/application/staff/StaffMemberService.java");
    }
}
