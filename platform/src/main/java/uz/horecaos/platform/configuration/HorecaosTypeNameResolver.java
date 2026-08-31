package uz.horecaos.platform.configuration;

import io.swagger.v3.core.jackson.TypeNameResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Disambiguates OpenAPI component schema names for the small set of web-layer types whose
 * simple class name is declared more than once under {@code uz.horecaos.platform.**.web}.
 *
 * <p>springdoc/swagger-core key the component registry by {@link Class#getSimpleName()} (see
 * {@link TypeNameResolver#getNameOfClass}), never by fully-qualified name, unless {@code
 * useFqn} is turned on globally. HorecaOS controllers deliberately nest their own
 * request/response records rather than sharing module-wide DTOs (see {@code
 * http-api-conventions}), so names like {@code AddressResponse} or {@code LineResponse} recur
 * across unrelated controllers with completely different shapes. Two such records silently
 * overwrite one another in the schema map: whichever is resolved last wins the name, and every
 * other declaration's schema — and its generated TypeScript type — is wrong, with no build
 * failure to say so.
 *
 * <p>This resolver leaves every schema name that swagger-core's default {@link
 * TypeNameResolver} would already produce untouched. Only for a simple name that a one-time
 * classpath scan finds declared more than once across the web layer does it prefix the name
 * with the declaring type's nearest enclosing name — the enclosing controller's simple name for
 * a nested record (the overwhelming majority of cases), or a package-derived qualifier for the
 * rare top-level collision (e.g. {@code migration.web.RunView} vs. a nested {@code RunView}
 * elsewhere). The scan covers the whole web layer regardless of {@link OpenApiSurface} group, so
 * a colliding type is renamed the same way in the full document and in every per-group document
 * — the full v1 document reaches every controller and would otherwise disagree with a group
 * document that only reaches one side of the collision.
 *
 * <p>The alternative — annotating every colliding record with {@code @Schema(name = ...)} —
 * does not scale here: the inventory this resolver was written against found 23 colliding
 * simple names across 57 individual record declarations (one name, {@code ReasonRequest}, alone
 * declared eight times), and every new controller risks adding one more. A resolver that acts
 * only on an actual collision keeps the common case — a unique name — exactly as short as it
 * would otherwise be, unlike a global {@code useFqn}, which would rename all ~300 web-layer
 * schemas and every generated client type with them.
 */
class HorecaosTypeNameResolver extends TypeNameResolver {

    private static final String SCAN_PATTERN = "classpath*:uz/horecaos/platform/**/*.class";
    private static final String WEB_SEGMENT = "/web/";
    private static final String TEST_CLASSES_SEGMENT = "/test-classes/";

    private static volatile Set<String> collidingSimpleNames;

    @Override
    protected String getNameOfClass(Class<?> cls) {
        String name = super.getNameOfClass(cls);
        if (!collidingSimpleNames().contains(name)) {
            return name;
        }
        Class<?> enclosingClass = cls.getEnclosingClass();
        return enclosingClass != null ? enclosingClass.getSimpleName() + name : packageQualifier(cls) + name;
    }

    /** For a top-level colliding type, e.g. {@code uz.horecaos.platform.migration.web.RunView} -> "Migration". */
    private static String packageQualifier(Class<?> cls) {
        String[] segments = cls.getPackageName().split("\\.");
        String module = segments.length >= 2 ? segments[segments.length - 2] : segments[segments.length - 1];
        return Character.toUpperCase(module.charAt(0)) + module.substring(1);
    }

    private static Set<String> collidingSimpleNames() {
        Set<String> resolved = collidingSimpleNames;
        if (resolved == null) {
            synchronized (HorecaosTypeNameResolver.class) {
                resolved = collidingSimpleNames;
                if (resolved == null) {
                    resolved = scanForCollidingSimpleNames();
                    collidingSimpleNames = resolved;
                }
            }
        }
        return resolved;
    }

    private static Set<String> scanForCollidingSimpleNames() {
        Map<String, Integer> occurrences = new HashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(SCAN_PATTERN);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan uz.horecaos.platform for OpenAPI schema-name collisions", e);
        }
        for (Resource resource : resources) {
            String simpleName = webLayerSimpleNameOf(resource);
            if (simpleName != null) {
                occurrences.merge(simpleName, 1, Integer::sum);
            }
        }
        return occurrences.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The simple name a {@code .class} resource would resolve to under {@link
     * Class#getSimpleName()}, or {@code null} if the resource is not a web-layer main-source
     * class. Read from the resource's path and filename only — the class is never loaded, so
     * this scan has no risk of triggering static initialisers or classloading cycles.
     */
    private static String webLayerSimpleNameOf(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || !filename.endsWith(".class")) {
            return null;
        }
        String path;
        try {
            path = resource.getURL().toString();
        } catch (IOException e) {
            return null;
        }
        if (!path.contains(WEB_SEGMENT) || path.contains(TEST_CLASSES_SEGMENT)) {
            return null;
        }
        String simpleName = filename.substring(0, filename.length() - ".class".length());
        int nestedSeparator = simpleName.lastIndexOf('$');
        if (nestedSeparator >= 0) {
            simpleName = simpleName.substring(nestedSeparator + 1);
        }
        if (simpleName.isEmpty() || Character.isDigit(simpleName.charAt(0))) {
            return null; // anonymous or local class: never a schema type
        }
        return simpleName;
    }
}
