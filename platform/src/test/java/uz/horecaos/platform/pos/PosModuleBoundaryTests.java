package uz.horecaos.platform.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundaries the POS module keeps (ADR 0007, ADR 0011).
 *
 * <p>Checked on imports rather than by Spring Modulith, which permits any
 * dependency on a third-party library and so cannot see the rule that matters
 * here. The module's own {@code ModularArchitectureTests} does the same for the
 * domain modules it lists; this one is the POS module's, kept here so it lives
 * beside what it protects.
 */
class PosModuleBoundaryTests {

    private static final Path MODULE = Path.of("src/main/java/uz/horecaos/platform/pos");

    @Test
    @DisplayName("Camel does not reach the POS module")
    void theModuleCompilesWithoutCamel() throws IOException {
        // ADR 0007's central rule. A route deciding whether an export landed is
        // exactly the coupling this boundary exists to prevent: that decision
        // needs to be shown to a person, and route DSL has nobody to show it to.
        List<String> offenders = sources()
                .filter(path -> imports(path, "org.apache.camel"))
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .as("adapters name calls; integration.camel.pos turns them into exchanges")
                .isEmpty();
    }

    @Test
    @DisplayName("no provider name escapes the provider package")
    void onlyTheAdapterKnowsWhoTheProviderIs() throws IOException {
        List<String> offenders = sources()
                .filter(path -> !path.toString().contains("/infrastructure/clopos/"))
                .filter(path -> {
                    String source = read(path);
                    return source.contains("uz.horecaos.platform.pos.infrastructure.clopos.Clopos");
                })
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .as("domain and application code asks for a capability, never for a vendor")
                .isEmpty();
    }

    @Test
    @DisplayName("the domain layer holds no JDBC and no HTTP")
    void theDifferenceEngineIsPure() throws IOException {
        // The difference engine and the uncertainty resolver are the two pieces
        // whose correctness has to be provable by reading them. A database call
        // inside either would make every assertion about them a question about
        // what was in the database at the time.
        List<String> offenders = sources()
                .filter(path -> path.toString().contains("/pos/domain/"))
                .filter(path -> imports(path, "org.springframework.jdbc")
                        || imports(path, "java.net.http")
                        || imports(path, "org.springframework.stereotype"))
                .map(Path::toString)
                .toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("the scan finds the files it claims to check")
    void theScanIsNotSilentlyEmpty() throws IOException {
        assertThat(sources().count())
                .as("a scan that finds nothing would pass forever")
                .isGreaterThan(20);
    }

    private static Stream<Path> sources() throws IOException {
        return Files.walk(MODULE).filter(path -> path.toString().endsWith(".java"));
    }

    private static boolean imports(Path source, String prefix) {
        return read(source).lines().filter(line -> line.startsWith("import ")).anyMatch(line -> line.contains(prefix));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + path, failure);
        }
    }
}
