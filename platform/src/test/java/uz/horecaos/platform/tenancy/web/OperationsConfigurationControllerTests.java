package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.AuthoredConfigurationValue;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Level;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Outcome;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Source;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * {@link OperationsConfigurationController} — the tenant-visible, path-pinned
 * narrowing of {@link ConfigurationController} (wave P31, gap map row {@code
 * 10/X.1}). Mirrors {@link ConfigurationControllerTests}'s doubles; what is
 * different here is exactly what this controller adds: the {@code
 * tenantVisible()} filter and refusing a body-supplied tenant.
 */
class OperationsConfigurationControllerTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OTHER_TENANT_ID = UUID.randomUUID();
    private static final UUID BRAND_ID = UUID.randomUUID();
    private static final UUID LOCATION_ID = UUID.randomUUID();

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

    /** A hand-rolled double: real authoring logic already has JdbcConfigurationValueAuthorTests. */
    private static final class FakeValueAuthor implements ConfigurationValueAuthor {
        @Nullable
        ConfigurationKey<?> lastKey;

        @Nullable
        ResourceScope lastScope;

        @Nullable
        Object lastValue;

        boolean lastExplicitNull;

        @Nullable
        Long lastExpectedVersion;

        @Nullable
        Long versionToReport;

        @Override
        @SuppressWarnings("unchecked")
        public <T> AuthoredConfigurationValue<T> set(
                ConfigurationKey<T> key,
                ResourceScope scope,
                @Nullable T value,
                boolean explicitNull,
                @Nullable Long expectedVersion,
                ActorRef setBy,
                String reason) {
            lastKey = key;
            lastScope = scope;
            lastValue = value;
            lastExplicitNull = explicitNull;
            lastExpectedVersion = expectedVersion;
            long newVersion = expectedVersion == null ? 0 : expectedVersion + 1;
            return new AuthoredConfigurationValue<>(
                    UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac129998"),
                    key.code(),
                    scope.type(),
                    value,
                    explicitNull,
                    newVersion);
        }

        @Override
        public Optional<Long> currentVersion(ConfigurationKey<?> key, ResourceScope scope) {
            return Optional.ofNullable(versionToReport);
        }
    }

    private static CurrentActor fakeActor() {
        return () -> new AuthenticatedActor("test-tenant-admin", Set.of(), Map.of());
    }

    private static OperationsConfigurationController controller(
            ConfigurationResolver resolver, ConfigurationValueAuthor values) {
        return new OperationsConfigurationController(resolver, values, fakeActor());
    }

    // ---------------------------------------------------------------- keys

    @Test
    void listsOnlyTenantVisibleKeys() {
        List<OperationsConfigurationController.OperationsConfigurationKeyResponse> keys =
                controller(new FakeResolver(), new FakeValueAuthor()).keys();

        assertThat(keys)
                .as("QUOTE_TTL_SECONDS declares no .tenantVisible() and must not appear here")
                .extracting(OperationsConfigurationController.OperationsConfigurationKeyResponse::code)
                .doesNotContain(ConfigurationKeys.QUOTE_TTL_SECONDS.code())
                .contains(ConfigurationKeys.CART_EXPIRY_MINUTES.code());

        assertThat(keys)
                .as("every key answered here really does declare tenantVisible() in the registry")
                .allSatisfy(
                        key -> assertThat(ConfigurationKeys.require(key.code()).tenantVisible())
                                .isTrue());
    }

    // ----------------------------------------------------------- resolution

    @Test
    void resolvesATenantVisibleKeyAtEachOfTenantBrandAndLocationScope() {
        FakeResolver resolver = new FakeResolver();
        var controller = controller(resolver, new FakeValueAuthor());

        var tenantResponse = controller.resolution(
                TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.TENANT, null, null);
        assertThat(resolver.lastScope).isEqualTo(ResourceScope.tenant(TENANT_ID));
        assertThat(tenantResponse.value()).isEqualTo(240);
        assertThat(tenantResponse.cameFromDefault()).isTrue();

        var brandResponse = controller.resolution(
                TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.BRAND, BRAND_ID, null);
        assertThat(resolver.lastScope).isEqualTo(ResourceScope.brand(TENANT_ID, BRAND_ID));
        assertThat(brandResponse.keyCode()).isEqualTo(ConfigurationKeys.CART_EXPIRY_MINUTES.code());

        var locationResponse = controller.resolution(
                TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.LOCATION, BRAND_ID, LOCATION_ID);
        assertThat(resolver.lastScope).isEqualTo(ResourceScope.location(TENANT_ID, BRAND_ID, LOCATION_ID));
        assertThat(locationResponse.inspectedLevels()).hasSize(1);
    }

    @Test
    void refusesToResolvePlatformScope() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());

        assertThatThrownBy(() -> controller.resolution(
                        TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.PLATFORM, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void refusesAnUnknownKeyRatherThanGuessing() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());

        assertThatThrownBy(() -> controller.resolution(TENANT_ID, "no.such.key", ScopeType.TENANT, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void refusesToResolveAKeyThatIsNotTenantVisible() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());

        assertThatThrownBy(() -> controller.resolution(
                        TENANT_ID, ConfigurationKeys.QUOTE_TTL_SECONDS.code(), ScopeType.TENANT, null, null))
                .as("a platform-only key must be as unreachable as an unregistered one")
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ---------------------------------------------------------------- setValue

    @Test
    void setsATenantVisibleKeyPinningTheScopeToThePathTenant() {
        FakeValueAuthor values = new FakeValueAuthor();
        var controller = controller(new FakeResolver(), values);
        var request = new OperationsConfigurationController.OperationsSetConfigurationValueRequest(
                ScopeType.BRAND, BRAND_ID, null, false, null, 120L, null, null, null, "narrower cart window");

        var response = controller.setValue(TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(values.lastScope)
                .as("the scope is built from the path tenantId, never from the request body")
                .isEqualTo(ResourceScope.brand(TENANT_ID, BRAND_ID));
        assertThat(values.lastValue).isEqualTo(120);
    }

    @Test
    void refusesToWriteAKeyThatIsNotTenantVisible() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());
        var request = new OperationsConfigurationController.OperationsSetConfigurationValueRequest(
                ScopeType.TENANT, null, null, false, null, 100L, null, null, null, "because");

        assertThatThrownBy(() -> controller.setValue(TENANT_ID, ConfigurationKeys.QUOTE_TTL_SECONDS.code(), request))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void refusesToWriteAtPlatformScope() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());
        var request = new OperationsConfigurationController.OperationsSetConfigurationValueRequest(
                ScopeType.PLATFORM, null, null, false, null, 100L, null, null, null, "because");

        assertThatThrownBy(() -> controller.setValue(TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void explicitNullCarriesNoValueThrough() {
        FakeValueAuthor values = new FakeValueAuthor();
        var controller = controller(new FakeResolver(), values);
        var request = new OperationsConfigurationController.OperationsSetConfigurationValueRequest(
                ScopeType.BRAND, BRAND_ID, null, true, null, null, null, null, null, "disable locally");

        controller.setValue(TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request);

        assertThat(values.lastExplicitNull).isTrue();
        assertThat(values.lastValue).isNull();
    }

    @Test
    void aRequestCannotNameATenantOtherThanThePathTenant() {
        // OperationsSetConfigurationValueRequest simply has no tenantId field —
        // this is a compile-time guarantee, exercised here by proving the two
        // different path tenants really do produce two different scopes.
        FakeValueAuthor values = new FakeValueAuthor();
        var controller = controller(new FakeResolver(), values);
        var request = new OperationsConfigurationController.OperationsSetConfigurationValueRequest(
                ScopeType.TENANT, null, null, false, null, 60L, null, null, null, "because");

        controller.setValue(OTHER_TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request);

        assertThat(values.lastScope).isEqualTo(ResourceScope.tenant(OTHER_TENANT_ID));
    }
}
