package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0149: there is one place in code that says which languages exist, and one command that finds
 * no other list.
 *
 * <p>The record counted sixty-odd independent declarations of {@code ru, uz-Latn, en} on the day it
 * was written, and its first draft was already short. A count is a date-stamped fact; this is the
 * command. It greps the main sources the way {@code ChangeDocumentUsageTests} greps for flat audit
 * calls, so a module that declares a locale list of its own again fails the build the day it does,
 * not the day somebody remembers to look.
 *
 * <p>What it looks for is a <em>list</em> of languages (the three spelled together, in either
 * spelling of Uzbek), an enum constant per language, and a SQL {@code CASE} that ranks languages.
 * A wording table keyed by one tag (an email's words, a bot's reply) is a catalogue, not a list of
 * which languages exist, and is not flagged: it reads the registry for which entry it is answering.
 */
class LocaleListsLiveInTheRegistryTests {

    private static final Path MAIN = Path.of("src/main/java");

    /** The registry itself, which is where the list belongs. */
    private static final String REGISTRY = "uz/horecaos/platform/tenancy/api/PlatformLocales.java";

    /**
     * Named exemptions, each with the reason, asserted to still be there so the list cannot rot into
     * a hiding place for a real declaration.
     */
    private static final List<String[]> EXEMPT = List.<String[]>of(
            // Payme's own published vocabulary for the language of its payment page, not ours.
            new String[] {"uz/horecaos/platform/payments/domain/PresentationRequest.java", "Payme's vocabulary"});

    private static final Pattern THREE_TOGETHER = Pattern.compile(
            "\"ru\"\\s*,\\s*\"uz(?:-Latn)?\"\\s*,\\s*\"en\"|\"uz(?:-Latn)?\"\\s*,\\s*\"ru\"\\s*,\\s*\"en\""
                    + "|\"ru\"\\s*,\\s*\"en\"\\s*,\\s*\"uz(?:-Latn)?\"|\"en\"\\s*,\\s*\"ru\"\\s*,\\s*\"uz(?:-Latn)?\"");

    private static final Pattern ENUM_PER_LANGUAGE =
            Pattern.compile("^\\s*(?:RU|UZ_LATN|UZ|EN)\\(\"(?:ru|uz-Latn|uz|en)\"\\)", Pattern.MULTILINE);

    private static final Pattern SQL_CASE_PER_LANGUAGE =
            Pattern.compile("WHEN\\s+'(?:ru|uz-Latn|uz|en)'\\s+THEN", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName(
            "no main source declares a list of languages of its own, spells an enum constant per language or ranks them in SQL")
    void noModuleDeclaresALocaleList() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources()) {
            String relative = MAIN.relativize(source).toString();
            if (relative.equals(REGISTRY) || EXEMPT.stream().anyMatch(exempt -> exempt[0].equals(relative))) {
                continue;
            }
            String text = Files.readString(source);
            // Comments say "ru, uz-Latn, en" in prose all the time; only code is flagged.
            String code = withoutComments(text);
            if (THREE_TOGETHER.matcher(code).find()) {
                offenders.add(relative + " spells the three languages together");
            }
            if (ENUM_PER_LANGUAGE.matcher(code).find()) {
                offenders.add(relative + " declares an enum constant per language");
            }
            if (SQL_CASE_PER_LANGUAGE.matcher(code).find()) {
                offenders.add(relative + " ranks languages in a SQL CASE (use PlatformLocales.fallbackOrderSql)");
            }
        }
        assertThat(offenders)
                .as("a locale list outside tenancy.api.PlatformLocales (ADR 0149)")
                .isEmpty();
    }

    @Test
    @DisplayName("no Java type is a closed set of the platform's languages: the enums the record named are gone")
    void theEnumsAreGone() throws IOException {
        for (String gone : List.of(
                "uz/horecaos/platform/legal/domain/TermsLocale.java",
                "uz/horecaos/platform/tenancy/domain/channel/ChannelPageLocale.java")) {
            assertThat(MAIN.resolve(gone)).as(gone).doesNotExist();
        }
        String messageLocale =
                Files.readString(MAIN.resolve("uz/horecaos/platform/notifications/domain/MessageLocale.java"));
        assertThat(messageLocale)
                .as("MessageLocale is a handle on a registry entry, no longer an enum of its own")
                .doesNotContain("enum MessageLocale")
                .contains("PlatformLocales");
    }

    @Test
    @DisplayName("the named exemptions still exist and still say what they are for")
    void theExemptionsAreStillNeeded() throws IOException {
        for (String[] exempt : EXEMPT) {
            Path source = MAIN.resolve(exempt[0]);
            assertThat(source).as("%s (%s)", exempt[0], exempt[1]).exists();
            assertThat(THREE_TOGETHER
                            .matcher(withoutComments(Files.readString(source)))
                            .find())
                    .as(
                            "%s is exempt because it spells the three together (%s); once it does not, remove the exemption",
                            exempt[0], exempt[1])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("no migration after V0499 spells a closed list of languages in a CHECK")
    void noNewMigrationClosesTheSet() throws IOException {
        Path migrations = Path.of("src/main/resources/db/migration");
        Pattern closed =
                Pattern.compile("locale[a-z_]*\\s+(?:IS NULL OR\\s+\\w+\\s+)?IN\\s*\\(\\s*'(?:ru|uz|en|uz-Latn)'");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("V0(49[9]|5\\d\\d)__.*\\.sql"))
                    .toList()) {
                String sql = Files.readString(file);
                // V0499 itself names the closed lists it removes, in comments.
                String code = sql.lines()
                        .filter(line -> !line.strip().startsWith("--"))
                        .reduce("", (a, b) -> a + b + "\n");
                if (closed.matcher(code).find()) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertThat(offenders)
                .as("a CHECK that closes the set of languages again")
                .isEmpty();
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /** Strips block and line comments; strings keep their text, which is what is being searched. */
    static String withoutComments(String source) {
        String noBlock =
                Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll(" ");
        return Pattern.compile("(?m)^\\s*//.*$|(?<=[;{}),])\\s*//.*$")
                .matcher(noBlock)
                .replaceAll("");
    }
}
