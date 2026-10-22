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
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.EntitlementGate;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.tenancy.api.AuthoredConfigurationValue;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Level;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Outcome;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Source;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.application.ConfigurationPlacementService;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationPlacementStore.StoredPolicy;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationPlacementStore.StoredValue;
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
        return controller(resolver, values, new FakeStaff());
    }

    private static OperationsConfigurationController controller(
            ConfigurationResolver resolver, ConfigurationValueAuthor values, StaffDirectory staff) {
        return controller(resolver, values, staff, Mockito.mock(ConfigurationPlacementService.class), null);
    }

    private static OperationsConfigurationController controller(
            ConfigurationResolver resolver,
            ConfigurationValueAuthor values,
            StaffDirectory staff,
            ConfigurationPlacementService placements,
            @Nullable EntitlementGate gate) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (gate != null) {
            beans.addBean("entitlementGate", gate);
        }
        return new OperationsConfigurationController(
                resolver, values, fakeActor(), staff, placements, beans.getBeanProvider(EntitlementGate.class));
    }

    /** A gate that knows each code it is told about, and answers "entitled" or "not" for it. */
    private static EntitlementGate gate(Map<String, Boolean> entitledByCode) {
        return (tenantId, code) -> entitledByCode.containsKey(code)
                ? Optional.of(new EntitlementGate.Answer(
                        entitledByCode.get(code),
                        "Whether the assistant answers customers",
                        "/api/v1/control-plane/plans"))
                : Optional.empty();
    }

    /** Knows one person, in one tenant: the real directory is tenant-scoped, so this one is too. */
    private static final class FakeStaff implements StaffDirectory {
        final List<String> asked = new java.util.ArrayList<>();

        @Override
        public @Nullable String nameOf(UUID tenantId, String subject) {
            return namesOf(tenantId, List.of(subject)).get(subject);
        }

        @Override
        public Map<String, String> namesOf(UUID tenantId, java.util.Collection<String> subjects) {
            asked.addAll(subjects);
            return TENANT_ID.equals(tenantId) && subjects.contains("subject-aziza")
                    ? Map.of("subject-aziza", "Aziza Karimova")
                    : Map.of();
        }

        @Override
        public Optional<UUID> memberIdOf(UUID tenantId, String subject) {
            return Optional.empty();
        }
    }

    /** A resolver whose trace has provenance at the tenant rung and none at the brand rung. */
    private static final class TracedResolver implements ConfigurationResolver {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
            return new Resolved<>((T) key.defaultValue(), explain(key, scope));
        }

        @Override
        public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
            return new ResolutionTrace(
                    key.code(),
                    Source.SCOPED_VALUE,
                    ScopeType.TENANT,
                    List.of(
                            new Level(ScopeType.BRAND, Outcome.NOT_SET),
                            new Level(
                                    ScopeType.TENANT,
                                    Outcome.VALUE,
                                    new ResolutionTrace.Provenance(
                                            5, "subject-aziza", java.time.Instant.parse("2026-09-29T12:00:00Z")))));
        }
    }

    // ---------------------------------------------------------------- who changed it

    @Test
    void theResolutionNamesWhoSetEachStoredLevelAndWhenButNeverGivesTheSubjectId() {
        FakeStaff staff = new FakeStaff();

        var response = controller(new TracedResolver(), new FakeValueAuthor(), staff)
                .resolution(TENANT_ID, tenantVisibleCode(), ScopeType.BRAND, BRAND_ID, null);

        assertThat(response.inspectedLevels()).hasSize(2);
        assertThat(response.inspectedLevels().get(0).version())
                .as("nothing stored at the brand")
                .isNull();
        assertThat(response.inspectedLevels().get(0).changedByName()).isNull();
        assertThat(response.inspectedLevels().get(1).version()).isEqualTo(5L);
        assertThat(response.inspectedLevels().get(1).changedByName()).isEqualTo("Aziza Karimova");
        assertThat(response.inspectedLevels().get(1).changedAt())
                .isEqualTo(java.time.Instant.parse("2026-09-29T12:00:00Z"));
        assertThat(response.toString())
                .as("the response is built from names; the principal's id is not a field of it")
                .doesNotContain("subject-aziza");
    }

    @Test
    void aSubjectWithNoMemberRecordInThisTenantIsLeftUnnamedAndAnotherTenantsPersonIsNotNamed() {
        var inOtherTenant = controller(new TracedResolver(), new FakeValueAuthor(), new FakeStaff())
                .resolution(OTHER_TENANT_ID, tenantVisibleCode(), ScopeType.BRAND, BRAND_ID, null);

        assertThat(inOtherTenant.inspectedLevels().get(1).version())
                .as("the version and date are facts about the row and do not depend on a name")
                .isEqualTo(5L);
        assertThat(inOtherTenant.inspectedLevels().get(1).changedByName())
                .as("the directory answers per tenant: Aziza works for TENANT_ID, not here")
                .isNull();
    }

    @Test
    void theDirectoryIsAskedOnceForTheWholeLadderAndNotAtAllWhenNothingIsStored() {
        FakeStaff staff = new FakeStaff();

        controller(new TracedResolver(), new FakeValueAuthor(), staff)
                .resolution(TENANT_ID, tenantVisibleCode(), ScopeType.BRAND, BRAND_ID, null);
        assertThat(staff.asked).containsExactly("subject-aziza");

        staff.asked.clear();
        controller(new FakeResolver(), new FakeValueAuthor(), staff)
                .resolution(TENANT_ID, tenantVisibleCode(), ScopeType.BRAND, BRAND_ID, null);
        assertThat(staff.asked).isEmpty();
    }

    private static String tenantVisibleCode() {
        return ConfigurationKeys.all().stream()
                .filter(ConfigurationKey::tenantVisible)
                .findFirst()
                .orElseThrow()
                .code();
    }

    // ---------------------------------------------------------------- keys

    @Test
    void listsOnlyTenantVisibleKeys() {
        List<OperationsConfigurationController.OperationsConfigurationKeyResponse> keys =
                controller(new FakeResolver(), new FakeValueAuthor()).keys(TENANT_ID);

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
    void refusesANumberAnIntegerKeyCannotStoreInsteadOfFailingWithAnArithmeticError() {
        FakeValueAuthor values = new FakeValueAuthor();
        var controller = controller(new FakeResolver(), values);

        // CART_EXPIRY_MINUTES is an Integer key; the request's number is a Long, narrowed to 32 bits.
        for (long outOfRange : new long[] {2_147_483_648L, 3_000_000_000L, -2_147_483_649L}) {
            var request = new OperationsConfigurationController.OperationsSetConfigurationValueRequest(
                    ScopeType.TENANT, null, null, false, null, outOfRange, null, null, null, "because");

            assertThatThrownBy(
                            () -> controller.setValue(TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), request))
                    .as("value " + outOfRange)
                    .isInstanceOf(ApiException.class)
                    .satisfies(error ->
                            assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(values.lastKey).as("nothing reached the author").isNull();

        var edge = new OperationsConfigurationController.OperationsSetConfigurationValueRequest(
                ScopeType.TENANT, null, null, false, null, 2_147_483_647L, null, null, null, "the largest");
        controller.setValue(TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), edge);
        assertThat(values.lastValue).as("the largest Integer is still storable").isEqualTo(Integer.MAX_VALUE);
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

    // ------------------------------------------------------- locked by plan (settings.md §1.2, ADR 0021)

    private static final String ASSISTANT_ENTITLEMENT = "assistant.answering.enabled";

    @Test
    void theKeyListNamesTheLockOfAKeyWhosePlanFeatureIsMissingAndNoOtherKeys() {
        var controller = controller(
                new FakeResolver(),
                new FakeValueAuthor(),
                new FakeStaff(),
                Mockito.mock(ConfigurationPlacementService.class),
                gate(Map.of(ASSISTANT_ENTITLEMENT, false)));

        var keys = controller.keys(TENANT_ID);

        var assistant = keys.stream()
                .filter(key -> key.code().equals("assistant.enabled"))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.entitledBy()).isEqualTo(ASSISTANT_ENTITLEMENT);
        var lock = java.util.Objects.requireNonNull(assistant.lockedByPlan());
        assertThat(lock.entitlementKey()).isEqualTo(ASSISTANT_ENTITLEMENT);
        assertThat(lock.description()).isNotBlank();
        assertThat(keys)
                .filteredOn(key -> !key.code().equals("assistant.enabled"))
                .as("a key no plan feature gates is never locked")
                .allSatisfy(key -> {
                    assertThat(key.entitledBy()).isNull();
                    assertThat(key.lockedByPlan()).isNull();
                });
    }

    @Test
    void aKeyIsNotLockedWhenThePlanHasTheFeatureOrTheGateDoesNotKnowIt() {
        for (Map<String, Boolean> answers : List.of(Map.of(ASSISTANT_ENTITLEMENT, true), Map.<String, Boolean>of())) {
            var controller = controller(
                    new FakeResolver(),
                    new FakeValueAuthor(),
                    new FakeStaff(),
                    Mockito.mock(ConfigurationPlacementService.class),
                    gate(answers));

            var assistant = controller.keys(TENANT_ID).stream()
                    .filter(key -> key.code().equals("assistant.enabled"))
                    .findFirst()
                    .orElseThrow();

            assertThat(assistant.entitledBy())
                    .as("the gate is declared either way")
                    .isEqualTo(ASSISTANT_ENTITLEMENT);
            assertThat(assistant.lockedByPlan()).as(answers.toString()).isNull();
        }
    }

    @Test
    void aBuildWithNoEntitlementGateLocksNothing() {
        var controller = controller(
                new FakeResolver(),
                new FakeValueAuthor(),
                new FakeStaff(),
                Mockito.mock(ConfigurationPlacementService.class),
                null);

        assertThat(controller.keys(TENANT_ID))
                .allSatisfy(key -> assertThat(key.lockedByPlan()).isNull());
    }

    @Test
    void theResolutionCarriesTheLockToo() {
        var locked = controller(
                new FakeResolver(),
                new FakeValueAuthor(),
                new FakeStaff(),
                Mockito.mock(ConfigurationPlacementService.class),
                gate(Map.of(ASSISTANT_ENTITLEMENT, false)));

        var response = locked.resolution(TENANT_ID, "assistant.enabled", ScopeType.BRAND, BRAND_ID, null);
        var ordinary = locked.resolution(
                TENANT_ID, ConfigurationKeys.CART_EXPIRY_MINUTES.code(), ScopeType.TENANT, null, null);

        assertThat(java.util.Objects.requireNonNull(response.lockedByPlan()).entitlementKey())
                .isEqualTo(ASSISTANT_ENTITLEMENT);
        assertThat(ordinary.lockedByPlan()).isNull();
    }

    @Test
    void aWriteToALockedKeyIsRefusedForThePlanAndNeverReachesTheAuthor() {
        FakeValueAuthor values = new FakeValueAuthor();
        var controller = controller(
                new FakeResolver(),
                values,
                new FakeStaff(),
                Mockito.mock(ConfigurationPlacementService.class),
                gate(Map.of(ASSISTANT_ENTITLEMENT, false)));
        var request = new OperationsConfigurationController.OperationsSetConfigurationValueRequest(
                ScopeType.BRAND, BRAND_ID, null, false, true, null, null, null, null, "switch it on");

        assertThatThrownBy(() -> controller.setValue(TENANT_ID, "assistant.enabled", request))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode())
                            .as("the person may edit; the plan lacks the feature")
                            .isEqualTo(ErrorCode.ENTITLEMENT_REQUIRED);
                    assertThat(refused.properties()).containsEntry("entitlementKey", ASSISTANT_ENTITLEMENT);
                });
        assertThat(values.lastKey).as("nothing was written").isNull();

        var entitled = controller(
                new FakeResolver(),
                values,
                new FakeStaff(),
                Mockito.mock(ConfigurationPlacementService.class),
                gate(Map.of(ASSISTANT_ENTITLEMENT, true)));
        entitled.setValue(TENANT_ID, "assistant.enabled", request);
        assertThat(values.lastKey).isNotNull();
        assertThat(values.lastValue).isEqualTo(true);
    }

    // ------------------------------------------------- where each setting is set (settings.md §10.0)

    private static StoredValue stored(
            String key, ScopeType scope, @Nullable String brandName, @Nullable String locationName, String setBy) {
        return new StoredValue(
                key,
                scope,
                brandName == null ? null : BRAND_ID,
                brandName,
                locationName == null ? null : LOCATION_ID,
                locationName,
                "INTEGER",
                null,
                15L,
                null,
                null,
                false,
                3,
                setBy,
                java.time.Instant.parse("2026-10-01T08:00:00Z"));
    }

    @Test
    void theEveryVisibleKeyIsListedWithItsPlacementsAndAnUnsetKeyHasNone() {
        ConfigurationPlacementService placements = Mockito.mock(ConfigurationPlacementService.class);
        Mockito.when(placements.values(TENANT_ID))
                .thenReturn(List.of(
                        stored(
                                ConfigurationKeys.CART_EXPIRY_MINUTES.code(),
                                ScopeType.TENANT,
                                null,
                                null,
                                "subject-aziza"),
                        stored(
                                ConfigurationKeys.CART_EXPIRY_MINUTES.code(),
                                ScopeType.BRAND,
                                "Rayhon",
                                null,
                                "subject-aziza"),
                        stored(
                                ConfigurationKeys.CART_EXPIRY_MINUTES.code(),
                                ScopeType.LOCATION,
                                "Rayhon",
                                "Chilonzor",
                                "subject-unknown")));
        var controller = controller(new FakeResolver(), new FakeValueAuthor(), new FakeStaff(), placements, null);

        var response = controller.placements(TENANT_ID);

        assertThat(response.keys())
                .extracting(OperationsConfigurationController.OperationsConfigurationKeyPlacements::keyCode)
                .as("every tenant-visible key, in code order, and no platform-only one")
                .containsExactlyElementsOf(ConfigurationKeys.all().stream()
                        .filter(ConfigurationKey::tenantVisible)
                        .map(ConfigurationKey::code)
                        .sorted()
                        .toList())
                .doesNotContain(ConfigurationKeys.QUOTE_TTL_SECONDS.code());
        var cart = response.keys().stream()
                .filter(key -> key.keyCode().equals(ConfigurationKeys.CART_EXPIRY_MINUTES.code()))
                .findFirst()
                .orElseThrow();
        assertThat(cart.placements())
                .extracting(
                        OperationsConfigurationController.OperationsConfigurationPlacement::scopeType,
                        OperationsConfigurationController.OperationsConfigurationPlacement::brandName,
                        OperationsConfigurationController.OperationsConfigurationPlacement::locationName,
                        OperationsConfigurationController.OperationsConfigurationPlacement::displayValue,
                        OperationsConfigurationController.OperationsConfigurationPlacement::changedByName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ScopeType.TENANT, null, null, "15", "Aziza Karimova"),
                        org.assertj.core.groups.Tuple.tuple(ScopeType.BRAND, "Rayhon", null, "15", "Aziza Karimova"),
                        org.assertj.core.groups.Tuple.tuple(ScopeType.LOCATION, "Rayhon", "Chilonzor", "15", null));
        assertThat(response.keys().stream()
                        .filter(key -> !key.keyCode().equals(ConfigurationKeys.CART_EXPIRY_MINUTES.code()))
                        .flatMap(key -> key.placements().stream()))
                .as("a key nobody set has no placement: the code default applies")
                .isEmpty();
        assertThat(response.toString())
                .as("names, never the subject ids")
                .doesNotContain("subject-aziza")
                .doesNotContain("subject-unknown");
    }

    @Test
    void aRowForAKeyTheTenantMayNotSeeIsNotShownWhateverItHolds() {
        ConfigurationPlacementService placements = Mockito.mock(ConfigurationPlacementService.class);
        Mockito.when(placements.values(TENANT_ID))
                .thenReturn(List.of(stored(
                        ConfigurationKeys.QUOTE_TTL_SECONDS.code(), ScopeType.TENANT, null, null, "subject-aziza")));
        var controller = controller(new FakeResolver(), new FakeValueAuthor(), new FakeStaff(), placements, null);

        var response = controller.placements(TENANT_ID);

        assertThat(response.toString()).doesNotContain(ConfigurationKeys.QUOTE_TTL_SECONDS.code());
    }

    @Test
    void aLongStringIsCutAtEightyCharactersAndSaysSoAndABooleanAndADecimalPrintPlainly() {
        String longText = "ж".repeat(95);
        ConfigurationPlacementService placements = Mockito.mock(ConfigurationPlacementService.class);
        Mockito.when(placements.values(TENANT_ID))
                .thenReturn(List.of(
                        new StoredValue(
                                "assistant.enabled",
                                ScopeType.TENANT,
                                null,
                                null,
                                null,
                                null,
                                "STRING",
                                null,
                                null,
                                null,
                                longText,
                                false,
                                0,
                                null,
                                null),
                        new StoredValue(
                                ConfigurationKeys.CART_EXPIRY_MINUTES.code(),
                                ScopeType.TENANT,
                                null,
                                null,
                                null,
                                null,
                                "DECIMAL",
                                null,
                                null,
                                new java.math.BigDecimal("12.500"),
                                null,
                                false,
                                0,
                                null,
                                null),
                        new StoredValue(
                                ConfigurationKeys.CATALOG_USE_STOCK_LOGIC.code(),
                                ScopeType.BRAND,
                                BRAND_ID,
                                "Rayhon",
                                null,
                                null,
                                "BOOLEAN",
                                true,
                                null,
                                null,
                                null,
                                false,
                                0,
                                null,
                                null),
                        new StoredValue(
                                ConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE.code(),
                                ScopeType.TENANT,
                                null,
                                null,
                                null,
                                null,
                                "BOOLEAN",
                                null,
                                null,
                                null,
                                null,
                                true,
                                1,
                                null,
                                null)));
        var response = controller(new FakeResolver(), new FakeValueAuthor(), new FakeStaff(), placements, null)
                .placements(TENANT_ID);

        var cut = placementOf(response, "assistant.enabled");
        assertThat(cut.displayValue()).hasSize(80).isEqualTo("ж".repeat(80));
        assertThat(cut.displayTruncated()).isTrue();
        assertThat(placementOf(response, ConfigurationKeys.CART_EXPIRY_MINUTES.code())
                        .displayValue())
                .isEqualTo("12.5");
        assertThat(placementOf(response, ConfigurationKeys.CATALOG_USE_STOCK_LOGIC.code())
                        .displayValue())
                .isEqualTo("true");
        var unset = placementOf(response, ConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE.code());
        assertThat(unset.explicitNull()).as("deliberately unset here").isTrue();
        assertThat(unset.displayValue()).isNull();
    }

    private static OperationsConfigurationController.OperationsConfigurationPlacement placementOf(
            OperationsConfigurationController.OperationsConfigurationPlacementsResponse response, String code) {
        return response.keys().stream()
                .filter(key -> key.keyCode().equals(code))
                .findFirst()
                .orElseThrow()
                .placements()
                .getFirst();
    }

    @Test
    void everyListedPolicyFamilyIsAnswerredWithItsNewestVersionAndWhoPublishedItButNeverTheirId() {
        ConfigurationPlacementService placements = Mockito.mock(ConfigurationPlacementService.class);
        Mockito.when(placements.policies(TENANT_ID))
                .thenReturn(List.of(
                        new StoredPolicy(
                                "ordering.lateness",
                                ScopeType.BRAND,
                                BRAND_ID,
                                "Rayhon",
                                null,
                                null,
                                4,
                                "subject-aziza",
                                java.time.Instant.parse("2026-10-02T09:00:00Z")),
                        new StoredPolicy(
                                "not.a.listed.family",
                                ScopeType.TENANT,
                                null,
                                null,
                                null,
                                null,
                                1,
                                "subject-aziza",
                                null)));
        var response = controller(new FakeResolver(), new FakeValueAuthor(), new FakeStaff(), placements, null)
                .placements(TENANT_ID);

        assertThat(response.policies())
                .extracting(OperationsConfigurationController.OperationsPolicyPlacements::policyCode)
                .containsExactlyElementsOf(uz.horecaos.platform.tenancy.application.TenantPolicyFamilies.LISTED);
        var lateness = response.policies().stream()
                .filter(policy -> policy.policyCode().equals("ordering.lateness"))
                .findFirst()
                .orElseThrow();
        assertThat(lateness.placements()).singleElement().satisfies(placement -> {
            assertThat(placement.version()).isEqualTo(4);
            assertThat(placement.brandName()).isEqualTo("Rayhon");
            assertThat(placement.changedByName()).isEqualTo("Aziza Karimova");
            assertThat(placement.effectiveFrom()).isEqualTo(java.time.Instant.parse("2026-10-02T09:00:00Z"));
        });
        assertThat(response.toString())
                .doesNotContain("subject-aziza")
                .as("a family the build does not list is not a family the tenant can search")
                .doesNotContain("not.a.listed.family");
    }
}
