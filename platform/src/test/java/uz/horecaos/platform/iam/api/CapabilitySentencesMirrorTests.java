package uz.horecaos.platform.iam.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * «Таблица прав» and the job editor print one plain sentence per capability the company can see, under a
 * heading in the reader's language ({@code capability-sentences.ts}, ADR 0186). The rows come from {@code GET
 * .../capabilities}, which is {@link TenantJobRules#catalogue()}: 216 capabilities when 156 had a sentence, so
 * sixty rows showed a dotted code and fourteen headings were the server's raw resource type ({@code print},
 * {@code voice}, {@code terms}), on the one screen built to explain the whole vocabulary. The console's own spec
 * kept passing because it compared the table with a list pasted into the spec, and a pasted list drifts from
 * the catalogue exactly as the table did.
 *
 * <p>This reads the table from the monorepo checkout and holds it against the live catalogue, so a capability
 * added to a visible job without a sentence, or a sentence under a heading nobody wrote, fails the build that
 * adds it. Skipped, and says so, in a checkout that has only the platform. The console's spec holds what only it
 * can: that the table is non-blank in all three languages and that no sentence is its own dotted code.
 */
class CapabilitySentencesMirrorTests {

    private static final Path SENTENCES =
            Path.of("../frontend/operations/src/app/features/staff/capability-sentences.ts");

    private static final Pattern AREAS_BLOCK =
            Pattern.compile("CAPABILITY_AREAS[^=]*=\\s*\\{(.*?)\\n};", Pattern.DOTALL);

    private static final Pattern AREA_KEY = Pattern.compile("^  '?([a-z][a-z0-9\\-]*)'?:", Pattern.MULTILINE);

    private static final Pattern SENTENCES_BLOCK =
            Pattern.compile("CAPABILITY_SENTENCES[^=]*=\\s*\\{(.*?)\\n};", Pattern.DOTALL);

    private static final Pattern ENTRY = Pattern.compile(
            "^  '([a-z][a-z0-9.\\-_]*)':\\s*\\{\\s*area:\\s*'([^']+)',\\s*ru:\\s*(.+?),\\s*'uz-Latn':\\s*(.+?),"
                    + "\\s*en:\\s*(.+?),?\\s*\\},?\\s*$",
            Pattern.MULTILINE | Pattern.DOTALL);

    private record Sentence(String code, String area, String ru, String uzLatn, String en) {}

    private static Map<String, Sentence> sentences(String source) {
        Matcher block = SENTENCES_BLOCK.matcher(source);
        assertThat(block.find())
                .as("CAPABILITY_SENTENCES is declared in %s", SENTENCES)
                .isTrue();
        Map<String, Sentence> byCode = new LinkedHashMap<>();
        // Entries are tried one at a time, from each "  'code': {" line to its own closing "  }," line, so a
        // malformed one fails here instead of swallowing its neighbour and a comment between two is ignored.
        String body = block.group(1);
        Matcher starts = Pattern.compile("^  '([a-z][a-z0-9.\\-_]*)': \\{", Pattern.MULTILINE)
                .matcher(body);
        while (starts.find()) {
            int close = body.indexOf("\n  },", starts.start());
            assertThat(close).as("the entry %s is closed", starts.group(1)).isPositive();
            String chunk = body.substring(starts.start(), close + "\n  },".length());
            Matcher entry = ENTRY.matcher(chunk);
            assertThat(entry.matches())
                    .as("an entry the mirror can read, in the shape the file keeps:%n%s", chunk)
                    .isTrue();
            byCode.put(
                    entry.group(1),
                    new Sentence(entry.group(1), entry.group(2), entry.group(3), entry.group(4), entry.group(5)));
        }
        return byCode;
    }

    private static Set<String> areas(String source) {
        Matcher block = AREAS_BLOCK.matcher(source);
        assertThat(block.find())
                .as("CAPABILITY_AREAS is declared in %s", SENTENCES)
                .isTrue();
        return AREA_KEY.matcher(block.group(1)).results().map(m -> m.group(1)).collect(Collectors.toSet());
    }

    private static boolean blank(String literal) {
        return literal.replaceAll("^[\\s'\"`]+|[\\s'\"`]+$", "").isBlank();
    }

    @Test
    @DisplayName("every capability the grid and the editor can show has a sentence in ru, uz-Latn and en")
    void everyCatalogueRowHasASentence() throws IOException {
        Assumptions.assumeTrue(Files.exists(SENTENCES), "the operations console is not in this checkout: " + SENTENCES);
        Map<String, Sentence> table = sentences(Files.readString(SENTENCES));

        List<String> missing = TenantJobRules.catalogue().stream()
                .map(Capability::code)
                .filter(code -> !table.containsKey(code))
                .sorted()
                .toList();
        assertThat(missing)
                .as("these rows would print as a dotted code: add a sentence for each to capability-sentences.ts, "
                        + "in all three languages")
                .isEmpty();

        List<String> blanks = TenantJobRules.catalogue().stream()
                .map(Capability::code)
                .map(table::get)
                .filter(sentence -> blank(sentence.ru()) || blank(sentence.uzLatn()) || blank(sentence.en()))
                .map(Sentence::code)
                .sorted()
                .toList();
        assertThat(blanks)
                .as("a sentence missing in one of the three languages")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "every capability in the catalogue sits under a heading the console wrote, never the raw resource type")
    void everyCatalogueRowHasAHeading() throws IOException {
        Assumptions.assumeTrue(Files.exists(SENTENCES), "the operations console is not in this checkout: " + SENTENCES);
        String source = Files.readString(SENTENCES);
        Map<String, Sentence> table = sentences(source);
        Set<String> headings = areas(source);

        assertThat(headings).as("the areas were read").contains("order", "courier", "kitchen");
        Map<String, String> unnamed = new LinkedHashMap<>();
        TenantJobRules.catalogue().stream()
                .sorted(Comparator.comparing(Capability::code))
                .forEach(capability -> {
                    Sentence sentence = table.get(capability.code());
                    // The console groups by the sentence's area; the server's resource type is only the fallback
                    // for a row with no sentence, and it is an English word in a Russian reader's heading.
                    if (sentence != null && !headings.contains(sentence.area())) {
                        unnamed.put(capability.code(), sentence.area());
                    }
                });
        assertThat(unnamed)
                .as("a sentence's area must be a key of CAPABILITY_AREAS, with a name in all three languages")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "every sentence the console keeps is for a capability the platform declares, so a typo or a retired one shows")
    void noSentenceOutlivesItsCapability() throws IOException {
        Assumptions.assumeTrue(Files.exists(SENTENCES), "the operations console is not in this checkout: " + SENTENCES);
        Map<String, Sentence> table = sentences(Files.readString(SENTENCES));

        assertThat(table).as("the table was read").hasSizeGreaterThan(100);
        assertThat(table.keySet())
                .as("each sentence names a declared capability")
                .allSatisfy(code -> assertThat(Capability.find(code)).as(code).isPresent());
    }
}
