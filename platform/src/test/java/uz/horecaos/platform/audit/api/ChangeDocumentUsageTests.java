package uz.horecaos.platform.audit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Staff 9.3a's regression guard: a new {@code AuditFact.Builder#changed(...)}
 * call must build a before/after document, not a flat after-only map.
 *
 * <p>{@link ChangeDocuments#diff} (a merged before/after snapshot), {@link
 * ChangeDocuments#change} (a single field) and {@link ChangeDocuments#created}
 * (a deliberate, self-documenting creation with no prior state) are the three
 * shapes this migration allows. Everything else — {@code Map.of(...)}, a
 * hand-built {@code HashMap}, a root-level {@code {before, after}} pair of
 * whole snapshots — is the flat, after-only shape 9.3a exists to remove: it
 * answers "that a field changed" and not "from what, to what".
 *
 * <p>This test does not convert the ~120 call sites this migration has not
 * reached yet; it freezes them. Every one is named, by file and line, in
 * {@code change-document-allowlist.txt}, so the debt is visible and finite
 * rather than an unbounded "most of them" a reader has to take on faith. A
 * site drops off the list the same commit that converts it — {@link
 * #theAllowListNamesOnlyRealCurrentDebt} fails if it does not, so the list can
 * only ever shrink toward the truth, never silently rot as a stale grant of
 * absolution for code that has since changed shape entirely.
 *
 * <p>Detection is a source scan, not a compiler: it resolves at most one hop
 * of local-variable indirection (the nearest earlier {@code x =
 * ChangeDocuments.diff(...)} in the same file) and gives up past that — a
 * value forwarded through a private helper's parameter reads as
 * non-compliant at the helper's own {@code .changed(...)} line and needs its
 * own allow-list entry, with a comment pointing at the call sites that were
 * actually verified. That is a deliberate false negative in the scan's
 * favour (it under-trusts rather than over-trusts), not a hole in the rule:
 * every call site the rule cannot see through is one a human looked at once,
 * on the record, in the allow-list diff.
 */
class ChangeDocumentUsageTests {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Path ALLOW_LIST =
            Path.of("src", "test", "resources", "audit", "change-document-allowlist.txt");

    /** A fluent-chain continuation line: {@code .changed(} at the start of a line, this codebase's own format. */
    private static final Pattern CALL_LINE = Pattern.compile("^\\s*\\.changed\\(\\s*(.*)$");

    private static final Pattern COMPLIANT_HEAD = Pattern.compile("^ChangeDocuments\\.(diff|change|created)\\(");
    private static final Pattern BARE_IDENTIFIER = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\)?\\s*$");

    private record Site(String path, int line) {
        String key() {
            return path + ":" + line;
        }
    }

    @Test
    void everyChangedCallBuildsABeforeAfterDocumentOrIsOnTheAllowList() throws IOException {
        List<Site> allSites = new ArrayList<>();
        List<Site> nonCompliant = new ArrayList<>();
        scan(allSites, nonCompliant);

        assertThat(allSites)
                .as("the scan found too few AuditFact#changed(...) call sites, which means the "
                        + "pattern stopped matching rather than that the codebase shrank")
                .hasSizeGreaterThan(150);

        Set<String> allowed = readAllowList();

        List<String> unexplained = nonCompliant.stream()
                .map(Site::key)
                .filter(key -> !allowed.contains(key))
                .sorted()
                .toList();

        assertThat(unexplained).as("""
                        A .changed(...) call writes a flat, after-only document instead of a \
                        before/after diff (Staff 9.3a). Build it with ChangeDocuments.diff(before, \
                        after) for an update, ChangeDocuments.change(field, before, after) for one \
                        field, or ChangeDocuments.created(after) for a genuine creation with no \
                        prior state to diff against. If this specific line is reviewed, known debt \
                        rather than a new regression, name it in \
                        src/test/resources/audit/change-document-allowlist.txt as "path:line".""").isEmpty();
    }

    @Test
    void theAllowListNamesOnlyRealCurrentDebt() throws IOException {
        List<Site> allSites = new ArrayList<>();
        List<Site> nonCompliant = new ArrayList<>();
        scan(allSites, nonCompliant);

        Set<String> nonCompliantKeys =
                nonCompliant.stream().map(Site::key).collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> stale = readAllowList().stream()
                .filter(key -> !nonCompliantKeys.contains(key))
                .sorted()
                .toList();

        assertThat(stale).as("""
                        An allow-list entry names a line that is no longer a non-compliant \
                        .changed(...) call -- it was converted to ChangeDocuments.diff/change/created, \
                        moved, or deleted. Remove it from change-document-allowlist.txt so the list \
                        only ever names debt that still exists; a stale entry hides how much of \
                        9.3a's migration is actually left.""").isEmpty();
    }

    private static void scan(List<Site> allSites, List<Site> nonCompliant) throws IOException {
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            List<Path> files = sources.filter(
                            path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path path : files) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(path);
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
                String relative = SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
                for (int i = 0; i < lines.size(); i++) {
                    Matcher call = CALL_LINE.matcher(lines.get(i));
                    if (!call.matches()) {
                        continue;
                    }
                    Site site = new Site(relative, i + 1);
                    allSites.add(site);
                    if (!isCompliant(call.group(1).strip(), lines, i)) {
                        nonCompliant.add(site);
                    }
                }
            }
        }
    }

    private static boolean isCompliant(String argument, List<String> lines, int callLineIndex) {
        if (COMPLIANT_HEAD.matcher(argument).find()) {
            return true;
        }
        Matcher identifier = BARE_IDENTIFIER.matcher(argument);
        if (!identifier.matches()) {
            return false;
        }
        Pattern assignment = Pattern.compile(
                "\\b" + Pattern.quote(identifier.group(1)) + "\\s*=\\s*ChangeDocuments\\.(diff|change|created)\\(");
        for (int i = 0; i < callLineIndex; i++) {
            if (assignment.matcher(lines.get(i)).find()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> readAllowList() throws IOException {
        Set<String> allowed = new LinkedHashSet<>();
        for (String line : Files.readAllLines(ALLOW_LIST)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            allowed.add(trimmed);
        }
        return allowed;
    }
}
