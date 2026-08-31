package uz.horecaos.platform.integration.camel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR 0007: no production route ships without a checked-in descriptor.
 *
 * <p>The descriptor exists so a route's contract can be reviewed without reading
 * its builder, which is why this test enforces coverage and completeness rather
 * than values. It cannot verify that a descriptor's stated 20-second timeout
 * matches the code, and asserting that it could would be false assurance. What it
 * can prove is that every route the code declares is claimed by exactly one
 * descriptor, that no descriptor claims a route that no longer exists, and that
 * no required field has been left blank — which is the failure ADR 0007 actually
 * named, an unowned production integration.
 *
 * <p>Route ids are read from the source rather than from a started
 * {@code CamelContext} because every builder takes a processor with a live
 * dependency graph behind it. Booting Spring to enumerate ten strings would make
 * this test slow enough that someone eventually deletes it.
 */
class RouteDescriptorTests {

    private static final Path ROUTES = Path.of("docs/routes");
    private static final Path CAMEL_SOURCES = Path.of("src/main/java/uz/horecaos/platform/integration/camel");
    private static final Pattern ROUTE_ID = Pattern.compile("\\.routeId\\(\"([^\"]+)\"\\)");

    @Test
    void everyProductionRouteIsClaimedByADescriptor() {
        Set<String> declared = declaredRouteIds();
        Set<String> described = new LinkedHashSet<>();
        RouteDescriptor.loadAll(ROUTES).forEach(descriptor -> described.addAll(descriptor.routeIds()));

        assertThat(declared)
                .as("a route with no descriptor is an integration nobody owns, which is the "
                        + "specific thing ADR 0007's descriptor requirement exists to prevent")
                .isNotEmpty()
                .isSubsetOf(described);
    }

    @Test
    void noDescriptorClaimsARouteThatNoLongerExists() {
        Set<String> declared = declaredRouteIds();

        for (RouteDescriptor descriptor : RouteDescriptor.loadAll(ROUTES)) {
            assertThat(descriptor.routeIds())
                    .as(
                            "%s describes routes that are not in the code; a stale descriptor is "
                                    + "worse than none, because it is read and believed",
                            descriptor.name())
                    .isNotEmpty()
                    .isSubsetOf(declared);
        }
    }

    @Test
    void everyRouteIsClaimedByExactlyOneDescriptor() {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (RouteDescriptor descriptor : RouteDescriptor.loadAll(ROUTES)) {
            descriptor
                    .routeIds()
                    .forEach(routeId -> owners.computeIfAbsent(routeId, key -> new ArrayList<>())
                            .add(descriptor.name()));
        }

        assertThat(owners.entrySet().stream()
                        .filter(entry -> entry.getValue().size() > 1)
                        .toList())
                .as("two descriptors for one route means two answers to every question about it")
                .isEmpty();
    }

    @Test
    void everyDescriptorAnswersEveryRequiredField() {
        for (RouteDescriptor descriptor : RouteDescriptor.loadAll(ROUTES)) {
            assertThat(descriptor.unansweredFields())
                    .as(
                            "%s leaves required ADR 0007 fields blank or placeholder. 'None' alone is "
                                    + "not an answer either: 'no retry' and 'nobody considered retry' look "
                                    + "identical in code and are opposite facts about a route",
                            descriptor.name())
                    .isEmpty();
        }
    }

    @Test
    void everyDescriptorLinksARunbookThatExists() {
        for (RouteDescriptor descriptor : RouteDescriptor.loadAll(ROUTES)) {
            String runbook = Objects.requireNonNull(
                            descriptor.fields().get("Runbook"), () -> descriptor.name() + " has no Runbook field")
                    .replace("`", "");
            Path target = Path.of(runbook.split("#", 2)[0].trim());

            assertThat(Files.exists(target))
                    .as(
                            "%s links a runbook at %s that does not exist; an alert pointing at a "
                                    + "missing page is found at 3am by the person least able to fix it",
                            descriptor.name(), target)
                    .isTrue();
        }
    }

    private static Set<String> declaredRouteIds() {
        Set<String> ids = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(CAMEL_SOURCES)) {
            for (Path source :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = ROUTE_ID.matcher(Files.readString(source));
                while (matcher.find()) {
                    ids.add(matcher.group(1));
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return ids;
    }
}
