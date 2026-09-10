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

class ConfigurationControllerTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID BRAND_ID = UUID.randomUUID();

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
        String lastReason;

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
            lastReason = reason;
            long newVersion = expectedVersion == null ? 0 : expectedVersion + 1;
            return new AuthoredConfigurationValue<>(
                    UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac129999"),
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
        return () -> new AuthenticatedActor("test-admin", Set.of(), Map.of());
    }

    private static ConfigurationController controller(ConfigurationResolver resolver, ConfigurationValueAuthor values) {
        return new ConfigurationController(resolver, values, fakeActor());
    }

    @Test
    void listsEveryDeclaredKeyWithItsCodeOwnedShape() {
        List<ConfigurationController.ConfigurationKeyResponse> keys =
                controller(new FakeResolver(), new FakeValueAuthor()).keys();

        assertThat(keys)
                .as("the registry must be the whole ConfigurationKeys catalogue, not a hand-copied subset")
                .hasSize(ConfigurationKeys.all().size());

        assertThat(keys)
                .filteredOn(key -> key.code().equals(ConfigurationKeys.CART_EXPIRY_MINUTES.code()))
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.owningModule()).isEqualTo("ordering");
                    assertThat(key.tenantVisible()).isTrue();
                    assertThat(key.defaultValue()).isEqualTo(240);
                });
    }

    @Test
    void resolvesAKeyAtATenantScopeAndCarriesTheTraceThrough() {
        FakeResolver resolver = new FakeResolver();
        var controller = controller(resolver, new FakeValueAuthor());

        var response = controller.resolution(
                ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.TENANT, TENANT_ID, null, null);

        assertThat(resolver.lastKey).isNotNull();
        assertThat(java.util.Objects.requireNonNull(resolver.lastKey).code())
                .isEqualTo(ConfigurationKeys.CART_EXPIRY_MINUTES.code());
        assertThat(resolver.lastScope).isEqualTo(ResourceScope.tenant(TENANT_ID));
        assertThat(response.value()).isEqualTo(240);
        assertThat(response.cameFromDefault()).isTrue();
        assertThat(response.source()).isEqualTo("CODE_DEFAULT");
        assertThat(response.inspectedLevels()).hasSize(1);
    }

    @Test
    void resolutionCarriesTheVersionStoredAtExactlyTheRequestedScope() {
        FakeValueAuthor values = new FakeValueAuthor();
        values.versionToReport = 4L;
        var controller = controller(new FakeResolver(), values);

        var response = controller.resolution(
                ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.TENANT, TENANT_ID, null, null);

        assertThat(response.currentVersionAtScope()).isEqualTo(4L);
    }

    @Test
    void refusesAnUnknownKeyRatherThanGuessing() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());

        assertThatThrownBy(() -> controller.resolution("no.such.key", ScopeType.PLATFORM, null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void refusesATenantScopeWithNoTenantIdRatherThanResolvingAtPlatform() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());

        assertThatThrownBy(() -> controller.resolution(
                        ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.TENANT, null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ---------------------------------------------------------------- setValue

    @Test
    void setsAnIntegerKeyAndPassesTheTypedValueAndActorThrough() {
        FakeValueAuthor values = new FakeValueAuthor();
        var controller = controller(new FakeResolver(), values);

        var request = new ConfigurationController.SetConfigurationValueRequest(
                ScopeType.TENANT, TENANT_ID, null, null, false, null, 120L, null, null, null, "narrower cart window");

        var response = controller.setValue(ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(values.lastKey).isNotNull();
        assertThat(java.util.Objects.requireNonNull(values.lastKey).code())
                .isEqualTo(ConfigurationKeys.CART_EXPIRY_MINUTES.code());
        assertThat(values.lastScope).isEqualTo(ResourceScope.tenant(TENANT_ID));
        assertThat(values.lastValue).isEqualTo(120);
        assertThat(values.lastExplicitNull).isFalse();
        assertThat(values.lastExpectedVersion).isNull();
        assertThat(values.lastReason).isEqualTo("narrower cart window");
        assertThat(java.util.Objects.requireNonNull(response.getBody()).version())
                .isEqualTo(0L);
    }

    @Test
    void refusesAnUnregisteredKeyRatherThanInsertingAnUntypedRow() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());
        var request = new ConfigurationController.SetConfigurationValueRequest(
                ScopeType.PLATFORM, null, null, null, false, null, null, null, "x", null, "because");

        assertThatThrownBy(() -> controller.setValue("no.such.key", request))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void refusesAStringForAnIntegerKeyRatherThanCastingIt() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());
        // CART_EXPIRY_MINUTES is an Integer key; stringValue is the wrong field.
        var request = new ConfigurationController.SetConfigurationValueRequest(
                ScopeType.TENANT, TENANT_ID, null, null, false, null, null, null, "120", null, "because");

        assertThatThrownBy(() -> controller.setValue(ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void refusesAMissingValueRatherThanSettingNull() {
        var controller = controller(new FakeResolver(), new FakeValueAuthor());
        var request = new ConfigurationController.SetConfigurationValueRequest(
                ScopeType.TENANT, TENANT_ID, null, null, false, null, null, null, null, null, "because");

        assertThatThrownBy(() -> controller.setValue(ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void aBrandScopedRequestBuildsABrandScopeForTheAuthor() {
        // The controller only builds the ResourceScope and forwards it; whether a key
        // declares BRAND settable at all is the author's own check, covered against a
        // real key/scope combination by JdbcConfigurationValueAuthorTests.
        FakeValueAuthor values = new FakeValueAuthor();
        var controller = controller(new FakeResolver(), values);
        var request = new ConfigurationController.SetConfigurationValueRequest(
                ScopeType.BRAND, TENANT_ID, BRAND_ID, null, false, null, 21L, null, null, null, "because");

        controller.setValue(ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request);

        assertThat(values.lastScope).isEqualTo(ResourceScope.brand(TENANT_ID, BRAND_ID));
    }

    @Test
    void explicitNullCarriesNoValueThrough() {
        FakeValueAuthor values = new FakeValueAuthor();
        var controller = controller(new FakeResolver(), values);
        var request = new ConfigurationController.SetConfigurationValueRequest(
                ScopeType.BRAND, TENANT_ID, BRAND_ID, null, true, null, null, null, null, null, "disable locally");

        controller.setValue(ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request);

        assertThat(values.lastExplicitNull).isTrue();
        assertThat(values.lastValue).isNull();
    }
}
