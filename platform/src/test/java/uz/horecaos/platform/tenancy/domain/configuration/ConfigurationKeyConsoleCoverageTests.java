package uz.horecaos.platform.tenancy.domain.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.application.TenantPolicyFamilies;

/**
 * «Find a setting» (settings.md §10.0, gap map row {@code 10.0}) is only as good as its index: a tenant-visible key
 * with no entry in it cannot be found, and nobody notices, because a missing result looks the same as a setting that
 * does not exist. So the console's index ({@code settings-search-index.ts}) is held to the registry here: every
 * tenant-visible {@link ConfigurationKeys} key is either routed to the screen that sets it ({@code SETTING_ENTRIES})
 * or listed in {@code NO_CONSOLE_SCREEN} against the gap-map row that owes it a screen, and every listed policy
 * family ({@link TenantPolicyFamilies#LISTED}) is routed ({@code POLICY_ROUTES}) or listed in
 * {@code NO_POLICY_SCREEN}. A new key or policy fails this build until someone says where it is found.
 *
 * <p>Reads the console's source from the monorepo checkout, the way {@code FiscalReasonCodeConsoleMirrorTests} does,
 * and is skipped, saying so, in a checkout that has only the platform; the console's own spec holds the file to
 * itself and to the screens it names.
 */
class ConfigurationKeyConsoleCoverageTests {

    private static final Path INDEX =
            Path.of("../frontend/operations/src/app/features/settings/settings-search-index.ts");
    private static final Path GAP_MAP = Path.of("docs/operations-gap-map.md");

    private static final Pattern ENTRY_KEY = Pattern.compile("^\\s*'([a-z][a-z0-9_.]*)':", Pattern.MULTILINE);
    private static final Pattern OWNED_ROW =
            Pattern.compile("^\\s*'([a-z][a-z0-9_.]*)':\\s*'([^']+)',?\\s*$", Pattern.MULTILINE);

    @Test
    @DisplayName("every tenant-visible configuration key is routed to a screen or owed one by a named gap-map row")
    void everyTenantVisibleKeyIsFindableOrOwed() throws IOException {
        String source = indexSource();
        Set<String> routed = keysOf(block(source, "export const SETTING_ENTRIES"));
        Map<String, String> owed = rowsOf(block(source, "export const NO_CONSOLE_SCREEN"));

        Set<String> registry = new TreeSet<>();
        for (ConfigurationKey<?> key : ConfigurationKeys.all()) {
            if (key.tenantVisible()) {
                registry.add(key.code());
            }
        }

        assertThat(routed).as("a key is routed or owed, never both").doesNotContainAnyElementsOf(owed.keySet());
        Set<String> indexed = new TreeSet<>(routed);
        indexed.addAll(owed.keySet());
        assertThat(registry)
                .as(
                        "every tenant-visible key is in the console's index (route it, or list it against the row that owes it a screen)")
                .isSubsetOf(indexed);
        assertThat(indexed)
                .as("the console's index names no key the registry does not have (a renamed or removed key)")
                .isSubsetOf(registry);
    }

    @Test
    @DisplayName("every listed policy family is routed to a screen or owed one, and the console lists no other")
    void everyListedPolicyIsFindableOrOwed() throws IOException {
        String source = indexSource();
        Set<String> routed = keysOf(block(source, "export const POLICY_ROUTES"));
        Map<String, String> owed = rowsOf(block(source, "export const NO_POLICY_SCREEN"));

        assertThat(routed).doesNotContainAnyElementsOf(owed.keySet());
        Set<String> indexed = new TreeSet<>(routed);
        indexed.addAll(owed.keySet());
        assertThat(indexed)
                .as("the console's policy index is exactly TenantPolicyFamilies.LISTED")
                .containsExactlyInAnyOrderElementsOf(TenantPolicyFamilies.LISTED);
    }

    @Test
    @DisplayName("the row named against each key and policy without a screen is a row of the gap map")
    void everyOwingRowExists() throws IOException {
        Assumptions.assumeTrue(Files.exists(GAP_MAP), "the gap map is not in this checkout: " + GAP_MAP);
        String source = indexSource();
        String gapMap = Files.readString(GAP_MAP);
        Map<String, String> owed = new LinkedHashMap<>(rowsOf(block(source, "export const NO_CONSOLE_SCREEN")));
        owed.putAll(rowsOf(block(source, "export const NO_POLICY_SCREEN")));

        assertThat(owed).isNotEmpty();
        owed.forEach((code, row) -> assertThat(gapMap)
                .as("%s is owed a screen by row %s, which the gap map must have", code, row)
                .satisfiesAnyOf(
                        text -> assertThat(text).contains("`" + row + "`"),
                        text -> assertThat(text).contains("`" + row + " †`")));
    }

    private static String indexSource() throws IOException {
        Assumptions.assumeTrue(Files.exists(INDEX), "the operations console is not in this checkout: " + INDEX);
        return Files.readString(INDEX);
    }

    /** The text of one {@code export const NAME ... };} declaration, from its name to the line that closes it. */
    private static String block(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertThat(start).as("%s is declared in %s", declaration, INDEX).isGreaterThanOrEqualTo(0);
        int end = source.indexOf("\n};", start);
        int endAsConst = source.indexOf("\n} as const", start);
        int stop = end < 0 ? endAsConst : (endAsConst < 0 ? end : Math.min(end, endAsConst));
        assertThat(stop).as("%s ends", declaration).isGreaterThan(start);
        return source.substring(start, stop);
    }

    private static Set<String> keysOf(String block) {
        Set<String> keys = new TreeSet<>();
        Matcher matcher = ENTRY_KEY.matcher(block.substring(block.indexOf('{')));
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static Map<String, String> rowsOf(String block) {
        Map<String, String> rows = new LinkedHashMap<>();
        Matcher matcher = OWNED_ROW.matcher(block);
        while (matcher.find()) {
            rows.put(matcher.group(1), matcher.group(2));
        }
        return rows;
    }
}
