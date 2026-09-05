package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularArchitectureTests {

    /** Domain modules. Integration is excluded: it is where the adapters belong. */
    private static final List<String> DOMAIN_MODULES = List.of(
            "audit",
            "catalog",
            "commercial",
            "configuration",
            "conversations",
            "courier",
            "customers",
            "dinein",
            "fiscal",
            "fulfillment",
            "iam",
            "inventory",
            "loyalty",
            "marketing",
            "media",
            "notifications",
            "observability",
            "ordering",
            "payments",
            "pricing",
            "reporting",
            "telemetry",
            "tenancy",
            "voice",
            "web");

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(HorecaOSPlatformApplication.class).verify();
    }

    @Test
    @DisplayName("Camel stays inside the integration module")
    void camelDoesNotLeakIntoDomainModules() throws IOException {
        // ADR 0007's central rule. A domain class importing org.apache.camel is
        // how integration concerns start making business decisions — a route
        // deciding whether an order is acceptable, rather than fulfilment.
        // Checked on imports rather than by Modulith, which allows any dependency
        // on a third-party library.
        List<String> offenders = sourcesIn(DOMAIN_MODULES).stream()
                .filter(ModularArchitectureTests::importsCamel)
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .as("domain modules must not import Camel; adapters live in integration.camel")
                .isEmpty();
    }

    @Test
    @DisplayName("provider adapters do not import each other")
    void adaptersStayIndependent() throws IOException {
        // Noor and Yandex differ in ways that matter — one has a hold, the other
        // does not; one documents its idempotency key, the other does not. Sharing
        // code between them is how one partner's semantics silently become the
        // other's.
        Path delivery = Path.of("src/main/java/uz/horecaos/platform/integration/camel/delivery");
        try (Stream<Path> paths = Files.walk(delivery)) {
            List<String> offenders = paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String source = read(path);
                        boolean isNoor = path.toString().contains("/noor/");
                        boolean isYandex = path.toString().contains("/yandex/");
                        return (isNoor && source.contains(".delivery.yandex."))
                                || (isYandex && source.contains(".delivery.noor."));
                    })
                    .map(Path::toString)
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    private static List<Path> sourcesIn(List<String> modules) throws IOException {
        Path root = Path.of("src/main/java/uz/horecaos/platform");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> modules.stream().anyMatch(module -> path.startsWith(root.resolve(module))))
                    .toList();
        }
    }

    private static boolean importsCamel(Path source) {
        return read(source)
                .lines()
                .filter(line -> line.startsWith("import "))
                .anyMatch(line -> line.contains("org.apache.camel"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + path, failure);
        }
    }
}
