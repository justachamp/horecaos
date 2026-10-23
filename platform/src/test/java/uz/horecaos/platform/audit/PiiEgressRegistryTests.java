package uz.horecaos.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.PiiEgressActions;
import uz.horecaos.platform.audit.api.PiiEgressActions.Action;
import uz.horecaos.platform.audit.api.PiiEgressActions.Kind;

/**
 * Settings 10.11's "export audit visible to the merchant" is only true if every call site that opens personal data to a person
 * or carries it out is on the list the log filters by.
 *
 * <p>The list lived in the console and was written by hand; six emitters it had never heard of were already live when this
 * registry replaced it. A list that is checked by reading code is the only kind that stays complete, so this greps the main
 * sources the way {@code ChangeDocumentUsageTests} greps for flat audit calls: every string literal that names a reveal, an
 * export or a disclosure must be registered in {@link PiiEgressActions} or excused below with the reason it is not personal
 * data leaving, and every registered code must still be written by something.
 */
class PiiEgressRegistryTests {

    private static final Path MAIN = Path.of("src/main/java");

    /** A string literal shaped like an audit action code that names a reveal, an export or a disclosure. */
    private static final Pattern EGRESS_CODE_LITERAL = Pattern.compile(
            "\"([A-Za-z][A-Za-z0-9_.\\-]*(?:revealed|exported|disclosed|_EXPORTED|\\.export\\.completed))\"");

    /** Codes that match the shape and are not personal data leaving, each with the reason. */
    private static final Map<String, String> NOT_EGRESS = Map.of(
            "payments.document.marking_codes_disclosed",
            "A product marking code is printed on the goods and identifies a unit of stock, not a person.");

    @Test
    @DisplayName(
            "every reveal, export or disclosure code the platform writes is registered as personal-data egress, or excused with a reason")
    void everyEgressLookingCodeIsRegisteredOrExcused() throws IOException {
        Map<String, String> found = literalsByFile();
        List<String> unregistered = new ArrayList<>();
        found.forEach((code, file) -> {
            if (!PiiEgressActions.isEgress(code) && !NOT_EGRESS.containsKey(code)) {
                unregistered.add(code + "  (" + file + ")");
            }
        });
        assertThat(unregistered)
                .as("audit codes that reveal or export but are in neither PiiEgressActions nor this test's NOT_EGRESS: "
                        + "the merchant's egress log would omit them")
                .isEmpty();
    }

    @Test
    @DisplayName("every registered code is still written by something, so the registry cannot outlive its call sites")
    void everyRegisteredCodeIsStillEmitted() throws IOException {
        Set<String> written = literalsByFile().keySet();
        List<String> orphaned = PiiEgressActions.codes().stream()
                .filter(code -> !written.contains(code))
                .toList();
        assertThat(orphaned)
                .as("registered egress codes that no main source writes: remove them or restore the emitter")
                .isEmpty();
    }

    @Test
    @DisplayName("the excuses still name codes that exist, and none is also registered")
    void theExcusesAreStillNeeded() throws IOException {
        Set<String> written = literalsByFile().keySet();
        for (String excused : NOT_EGRESS.keySet()) {
            assertThat(written)
                    .as("%s is excused but no longer written", excused)
                    .contains(excused);
            assertThat(PiiEgressActions.isEgress(excused))
                    .as("%s is both registered and excused", excused)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a code is registered once, and its kind agrees with its name")
    void theRegistryIsConsistent() {
        Set<String> seen = new LinkedHashSet<>();
        for (Action action : PiiEgressActions.all()) {
            assertThat(seen.add(action.code()))
                    .as("%s registered twice", action.code())
                    .isTrue();
            boolean namedAsExport = action.code().toLowerCase().contains("export");
            assertThat(action.kind() == Kind.EXPORT)
                    .as("%s: an export is named as one and a reveal is not", action.code())
                    .isEqualTo(namedAsExport);
        }
        assertThat(PiiEgressActions.all()).as("the registry must not be empty").isNotEmpty();
    }

    @Test
    @DisplayName("the guard can fail: a new reveal literal that nobody registered is found")
    void theGuardCanFail() {
        Matcher matcher = EGRESS_CODE_LITERAL.matcher("AuditFact.of(\"billing.invoice.recipient_revealed\", SECURITY)");
        assertThat(matcher.find()).isTrue();
        assertThat(PiiEgressActions.isEgress(matcher.group(1))).isFalse();
        assertThat(NOT_EGRESS).doesNotContainKey(matcher.group(1));
    }

    /** Code to the first file that spells it, over every main source outside the registry itself. */
    private static Map<String, String> literalsByFile() throws IOException {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(MAIN)) {
            for (Path source :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (source.endsWith("audit/api/PiiEgressActions.java")) {
                    continue;
                }
                Matcher matcher = EGRESS_CODE_LITERAL.matcher(withoutComments(Files.readString(source)));
                while (matcher.find()) {
                    found.putIfAbsent(matcher.group(1), MAIN.relativize(source).toString());
                }
            }
        }
        return found;
    }

    /** Comments quote codes in prose all the time; only code is read. */
    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
