package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import uz.horecaos.platform.tenancy.api.PolicyKey;

/**
 * Settings.md §10.0 has «Find a setting» search the versioned policies too. There is no registry of {@link
 * PolicyKey}s -- each module declares its own -- so {@link TenantPolicyFamilies} is curated by hand, and this
 * test is what stops it going stale: every {@code PolicyKey} constant in the build is either listed (a tenant
 * can find it) or excluded with a reason, so a new policy cannot ship without someone deciding which.
 */
class TenantPolicyFamiliesTests {

    @Test
    @DisplayName(
            "every PolicyKey the build declares is listed for tenants or excluded with a reason, and nothing else is")
    void everyDeclaredPolicyIsAccountedFor() throws Exception {
        Set<String> declared = declaredPolicyCodes();

        assertThat(declared)
                .as("the scan found the policies it should (if this fails the scan is broken, not the list)")
                .contains("ordering.acceptance", "courier.compensation", "kitchen.paid_hold", "reviews.recovery");
        Set<String> accounted = new TreeSet<>(TenantPolicyFamilies.LISTED);
        accounted.addAll(TenantPolicyFamilies.NOT_FOR_TENANTS.keySet());

        assertThat(declared)
                .as("a PolicyKey that is neither in TenantPolicyFamilies.LISTED nor NOT_FOR_TENANTS: decide whether a "
                        + "tenant can find it in «Find a setting»")
                .isSubsetOf(accounted);
        assertThat(accounted)
                .as("a family listed here that no PolicyKey declares any more is a stale line")
                .isSubsetOf(declared);
    }

    @Test
    @DisplayName("a listed family is one a tenant can set at tenant, brand or branch; an excluded one is not")
    void listedMeansTenantSettable() throws Exception {
        Set<String> tenantSettable = new TreeSet<>();
        Set<String> platformOnly = new TreeSet<>();
        for (PolicyKey<?> key : declaredPolicyKeys()) {
            boolean tenantLevel = key.settableScopes().stream()
                    .anyMatch(scope -> scope != uz.horecaos.platform.iam.api.ResourceScope.ScopeType.PLATFORM);
            (tenantLevel ? tenantSettable : platformOnly).add(key.code());
        }

        assertThat(TenantPolicyFamilies.LISTED)
                .allSatisfy(code -> assertThat(tenantSettable).contains(code));
        assertThat(TenantPolicyFamilies.NOT_FOR_TENANTS.keySet())
                .allSatisfy(code -> assertThat(platformOnly).contains(code));
        assertThat(TenantPolicyFamilies.LISTED).doesNotHaveDuplicates();
    }

    private static Set<String> declaredPolicyCodes() throws Exception {
        Set<String> codes = new TreeSet<>();
        declaredPolicyKeys().forEach(key -> codes.add(key.code()));
        return codes;
    }

    private static List<PolicyKey<?>> declaredPolicyKeys() throws Exception {
        List<PolicyKey<?>> keys = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Resource resource : resolver.getResources("classpath*:uz/horecaos/platform/**/*.class")) {
            String path = resource.getURL().toString();
            int start = path.indexOf("uz/horecaos/platform/");
            if (start < 0 || path.contains("$") || path.contains("/test-classes/")) {
                continue;
            }
            String name =
                    path.substring(start, path.length() - ".class".length()).replace('/', '.');
            Class<?> type;
            try {
                type = Class.forName(name, false, TenantPolicyFamiliesTests.class.getClassLoader());
            } catch (Throwable unloadable) {
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == PolicyKey.class && Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof PolicyKey<?> key) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }
}
