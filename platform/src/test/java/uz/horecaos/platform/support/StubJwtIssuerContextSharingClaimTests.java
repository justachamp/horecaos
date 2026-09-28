package uz.horecaos.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Pins {@link StubJwtIssuer}'s actual, honest contract. wave13-w8 (commit
 * 7ae7b72d) shipped that class with a javadoc claim that a suite importing
 * it "shares a single cached context with every other suite that does the
 * same" — false for all eleven suites that imported it then and now, and
 * provably so: run any two of them back to back in one Surefire fork and
 * each prints its own "Starting X" / "Started X in N seconds" Spring Boot
 * banner, opens its own {@code HikariPool}, and clones its own database
 * (verified 2026-09-28 with {@code MenuControllerTests} and
 * {@code CommentPresetControllerTests}: {@code HikariPool-1} against
 * {@code horecaos_menucontrollertests_1}, then a second, independent
 * startup opening {@code HikariPool-2} against
 * {@code horecaos_commentpresetcontrollertests_2} — not one context reused,
 * two contexts built).
 *
 * <p>The one fact that actually decides whether two suites <em>could</em>
 * share a Spring test context is mechanical, not a matter of how similar
 * the code looks: Spring's test-context cache keys a context on (among
 * other things) the exact {@code @DynamicPropertySource} {@link
 * java.lang.reflect.Method} objects it finds on the test class, and {@code
 * Method.equals} treats two methods as different the moment their
 * <em>declaring class</em> differs. Two suites resolve to the same method
 * only when they <em>inherit</em> it, unmodified, from a common ancestor —
 * importing the same {@code @TestConfiguration} bean is not enough by
 * itself, because {@code @Import} contributes a configuration class, not a
 * context-customizer identity.
 *
 * <p>This test finds every real {@code @Import(StubJwtIssuer.class)} call
 * site under {@code src/test/java} and checks that each one still declares
 * (rather than inherits) its own {@code @DynamicPropertySource} method —
 * i.e. that it still opens its own private {@link TestDatabase}, exactly as
 * {@link TestDatabase}'s own class doc says a suite should ("the database
 * is per class, and that is not an optimization to take back" — several
 * suites reset state more narrowly than a full TRUNCATE and would corrupt
 * each other silently if they ever shared one). If this ever starts
 * failing because a suite now inherits its method from a shared base class,
 * that suite has started sharing both a cached {@code ApplicationContext}
 * and, with it, {@link TestDatabase}'s per-class database with whichever
 * suites inherit the same method — re-read {@link TestDatabase}'s class doc
 * and confirm every affected suite's own cleanup is safe under that before
 * treating this failure as permission to proceed.
 */
class StubJwtIssuerContextSharingClaimTests {

    private static final Path SOURCE_ROOT = Path.of("src", "test", "java");
    private static final String IMPORT_MARKER = "@Import(StubJwtIssuer.class)";

    @Test
    void everyImportingSuiteDeclaresItsOwnDynamicPropertySourceMethod() throws IOException {
        List<Class<?>> importers = findImporters();

        assertThat(importers)
                .as(
                        "the source scan for \"%s\" under %s found too few suites -- it stopped "
                                + "matching, the ones this test knows about were deleted, or "
                                + "StubJwtIssuer was renamed and every caller updated without this "
                                + "test noticing",
                        IMPORT_MARKER, SOURCE_ROOT)
                .hasSizeGreaterThanOrEqualTo(10);

        List<String> suitesThatDoNotOwnTheirMethod = importers.stream()
                .filter(testClass -> !declaresItsOwnDynamicPropertySourceMethod(testClass))
                .map(Class::getName)
                .sorted()
                .toList();

        assertThat(suitesThatDoNotOwnTheirMethod).as("""
                        Each of these suites imports StubJwtIssuer but does NOT declare its own \
                        @DynamicPropertySource method -- it must have started inheriting one from \
                        a shared base class instead. That means it now shares both a cached Spring \
                        ApplicationContext and TestDatabase's per-class database with every other \
                        suite that inherits the same method. Before accepting that as a win, \
                        re-read TestDatabase's own class doc ("not an optimization to take back") \
                        and confirm every one of these suites' own state cleanup (its own \
                        @BeforeEach TRUNCATE/reset) is still correct against a database another \
                        suite's tests also write to.""").isEmpty();
    }

    private static boolean declaresItsOwnDynamicPropertySourceMethod(Class<?> testClass) {
        return Stream.of(testClass.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(DynamicPropertySource.class));
    }

    private static List<Class<?>> findImporters() throws IOException {
        List<Class<?>> importers = new ArrayList<>();
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
                if (lines.stream().noneMatch(line -> line.strip().equals(IMPORT_MARKER))) {
                    continue;
                }
                String relative = SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
                String className = relative.substring(0, relative.length() - ".java".length())
                        .replace('/', '.');
                try {
                    importers.add(Class.forName(className));
                } catch (ClassNotFoundException notFound) {
                    throw new IllegalStateException(
                            "scanned source file has no matching compiled class: " + path, notFound);
                }
            }
        }
        return importers;
    }
}
