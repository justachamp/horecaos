package uz.horecaos.platform.tenancy.domain.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Outcome;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Source;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * ADR 0030 precedence is the rule at least eight capability ADRs depend on, so
 * it is tested exhaustively and without a database.
 */
class ScopeResolutionTests {

    private static final ConfigurationKey<Integer> KEY = ConfigurationKey.of("ordering.timeout_seconds", Integer.class)
            .defaultValue(600)
            .build();

    private static final ConfigurationKey<Integer> TERMINATING_KEY = ConfigurationKey.of(
                    "notifications.quiet_start", Integer.class)
            .defaultValue(22)
            .explicitNullTerminates()
            .build();

    private static final ResourceScope LOCATION = ResourceScope.location(
            UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120001"),
            UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120002"),
            UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120003"));

    @Test
    void locationWinsOverEveryBroaderScope() {
        Resolved<Integer> resolved = ScopeResolution.resolve(
                KEY,
                LOCATION,
                values(
                        ScopeType.PLATFORM, 100,
                        ScopeType.TENANT, 200,
                        ScopeType.BRAND, 300,
                        ScopeType.LOCATION, 400));

        assertThat(resolved.value()).isEqualTo(400);
        assertThat(resolved.trace().winningScope()).isEqualTo(ScopeType.LOCATION);
    }

    @Test
    void brandWinsWhenTheLocationHasNoValue() {
        Resolved<Integer> resolved = ScopeResolution.resolve(
                KEY,
                LOCATION,
                values(
                        ScopeType.PLATFORM, 100,
                        ScopeType.TENANT, 200,
                        ScopeType.BRAND, 300));

        assertThat(resolved.value()).isEqualTo(300);
        assertThat(resolved.trace().winningScope()).isEqualTo(ScopeType.BRAND);
    }

    @Test
    void tenantWinsWhenNeitherLocationNorBrandHasAValue() {
        Resolved<Integer> resolved = ScopeResolution.resolve(
                KEY,
                LOCATION,
                values(
                        ScopeType.PLATFORM, 100,
                        ScopeType.TENANT, 200));

        assertThat(resolved.value()).isEqualTo(200);
        assertThat(resolved.trace().winningScope()).isEqualTo(ScopeType.TENANT);
    }

    @Test
    void platformWinsWhenNothingNarrowerIsSet() {
        Resolved<Integer> resolved = ScopeResolution.resolve(KEY, LOCATION, values(ScopeType.PLATFORM, 100));

        assertThat(resolved.value()).isEqualTo(100);
        assertThat(resolved.trace().winningScope()).isEqualTo(ScopeType.PLATFORM);
    }

    @Test
    void fallsBackToTheCodeDefaultWhenNoLevelIsSet() {
        Resolved<Integer> resolved = ScopeResolution.resolve(KEY, LOCATION, Map.of());

        assertThat(resolved.value()).isEqualTo(600);
        assertThat(resolved.cameFromDefault()).isTrue();
        assertThat(resolved.trace().winningScope()).isNull();
        assertThat(resolved.trace().source()).isEqualTo(Source.CODE_DEFAULT);
    }

    @Test
    void aBrandScopedRequestNeverSeesALocationValue() {
        ResourceScope brandScope = ResourceScope.brand(LOCATION.tenantId(), LOCATION.brandId());

        Resolved<Integer> resolved = ScopeResolution.resolve(
                KEY,
                brandScope,
                values(
                        ScopeType.TENANT, 200,
                        ScopeType.LOCATION, 400));

        assertThat(resolved.value())
                .as("a location value must not leak upward into a brand-scoped resolution")
                .isEqualTo(200);
    }

    @Test
    void explicitNullContinuesResolutionByDefault() {
        Map<ScopeType, ScopedConfigurationRow> stored = new EnumMap<>(ScopeType.class);
        stored.put(ScopeType.LOCATION, ScopedConfigurationRow.explicitNull(ScopeType.LOCATION));
        stored.put(ScopeType.TENANT, ScopedConfigurationRow.of(ScopeType.TENANT, 200));

        Resolved<Integer> resolved = ScopeResolution.resolve(KEY, LOCATION, stored);

        assertThat(resolved.value()).isEqualTo(200);
        assertThat(resolved.trace().inspectedLevels())
                .extracting("scopeType", "outcome")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ScopeType.LOCATION, Outcome.EXPLICIT_NULL_CONTINUED),
                        org.assertj.core.groups.Tuple.tuple(ScopeType.BRAND, Outcome.NOT_SET),
                        org.assertj.core.groups.Tuple.tuple(ScopeType.TENANT, Outcome.VALUE));
    }

    @Test
    void explicitNullTerminatesForAKeyThatDeclaresIt() {
        Map<ScopeType, ScopedConfigurationRow> stored = new EnumMap<>(ScopeType.class);
        stored.put(ScopeType.BRAND, ScopedConfigurationRow.explicitNull(ScopeType.BRAND));
        stored.put(ScopeType.TENANT, ScopedConfigurationRow.of(ScopeType.TENANT, 21));

        Resolved<Integer> resolved = ScopeResolution.resolve(TERMINATING_KEY, LOCATION, stored);

        assertThat(resolved.value())
                .as("an explicit null on a terminating key means deliberately unset, not inherit")
                .isNull();
        assertThat(resolved.trace().winningScope()).isEqualTo(ScopeType.BRAND);
    }

    @Test
    void theTraceRecordsEveryLevelInspected() {
        Resolved<Integer> resolved = ScopeResolution.resolve(KEY, LOCATION, values(ScopeType.TENANT, 200));

        assertThat(resolved.trace().inspectedLevels()).hasSize(3);
        assertThat(resolved.trace().describe())
                .contains("ordering.timeout_seconds")
                .contains("LOCATION=NOT_SET")
                .contains("TENANT=VALUE");
    }

    @Test
    void aPlatformScopedRequestInspectsOnlyThePlatform() {
        Resolved<Integer> resolved = ScopeResolution.resolve(
                KEY,
                ResourceScope.platform(),
                values(
                        ScopeType.PLATFORM, 100,
                        ScopeType.TENANT, 200));

        assertThat(resolved.value()).isEqualTo(100);
        assertThat(resolved.trace().inspectedLevels()).hasSize(1);
    }

    private static Map<ScopeType, ScopedConfigurationRow> values(Object... pairs) {
        Map<ScopeType, ScopedConfigurationRow> stored = new EnumMap<>(ScopeType.class);
        for (int index = 0; index < pairs.length; index += 2) {
            ScopeType scopeType = (ScopeType) pairs[index];
            stored.put(scopeType, ScopedConfigurationRow.of(scopeType, pairs[index + 1]));
        }
        return stored;
    }
}
