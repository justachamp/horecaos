package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Level;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Outcome;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Source;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

class ConfigurationControllerTests {

    private static final UUID TENANT_ID = UUID.randomUUID();

    /** A hand-rolled double: real resolution logic already has JdbcConfigurationResolverTests. */
    private static final class FakeResolver implements ConfigurationResolver {
        @Nullable
        ConfigurationKey<?> lastKey;

        @Nullable
        ResourceScope lastScope;

        @Override
        @SuppressWarnings("unchecked")
        public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
            lastKey = key;
            lastScope = scope;
            return new Resolved<>(
                    (T) key.defaultValue(),
                    new ResolutionTrace(
                            key.code(), Source.CODE_DEFAULT, null, List.of(new Level(scope.type(), Outcome.NOT_SET))));
        }

        @Override
        public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
            return new ResolutionTrace(
                    key.code(), Source.CODE_DEFAULT, null, List.of(new Level(scope.type(), Outcome.NOT_SET)));
        }
    }

    @Test
    void listsEveryDeclaredKeyWithItsCodeOwnedShape() {
        List<ConfigurationController.ConfigurationKeyResponse> keys =
                new ConfigurationController(new FakeResolver()).keys();

        assertThat(keys)
                .as("the registry must be the whole ConfigurationKeys catalogue, not a hand-copied subset")
                .hasSize(ConfigurationKeys.all().size());

        assertThat(keys)
                .filteredOn(key -> key.code().equals(ConfigurationKeys.CART_EXPIRY_MINUTES.code()))
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.owningModule()).isEqualTo("ordering");
                    assertThat(key.tenantVisible()).isTrue();
                    assertThat(key.defaultValue()).isEqualTo(60);
                });
    }

    @Test
    void resolvesAKeyAtATenantScopeAndCarriesTheTraceThrough() {
        FakeResolver resolver = new FakeResolver();
        var controller = new ConfigurationController(resolver);

        var response = controller.resolution(
                ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.TENANT, TENANT_ID, null, null);

        assertThat(resolver.lastKey).isNotNull();
        assertThat(java.util.Objects.requireNonNull(resolver.lastKey).code())
                .isEqualTo(ConfigurationKeys.CART_EXPIRY_MINUTES.code());
        assertThat(resolver.lastScope).isEqualTo(ResourceScope.tenant(TENANT_ID));
        assertThat(response.value()).isEqualTo(60);
        assertThat(response.cameFromDefault()).isTrue();
        assertThat(response.source()).isEqualTo("CODE_DEFAULT");
        assertThat(response.inspectedLevels()).hasSize(1);
    }

    @Test
    void refusesAnUnknownKeyRatherThanGuessing() {
        var controller = new ConfigurationController(new FakeResolver());

        assertThatThrownBy(() -> controller.resolution("no.such.key", ScopeType.PLATFORM, null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void refusesATenantScopeWithNoTenantIdRatherThanResolvingAtPlatform() {
        var controller = new ConfigurationController(new FakeResolver());

        assertThatThrownBy(() -> controller.resolution(
                        ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.TENANT, null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
