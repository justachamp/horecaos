package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
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
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.application.ConfigurationPlacementService;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationPlacementStore.StoredPolicy;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationPlacementStore.StoredValue;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's own read, write and resolution trace over its ADR 0030
 * configuration — settings.md §1.1's scope bar and §1.2's {@code
 * q-inherited-field} (wave P31, gap map row {@code 10/X.1}).
 *
 * <p>{@link ConfigurationController}'s own Javadoc named this gap before it
 * existed: every method there is {@link Capability#PLATFORM_ADMIN} at {@code
 * PLATFORM} scope, "not tenant self-service, which would need its own
 * narrower capability and its own conversation about who holds it". {@link
 * Capability#TENANT_CONFIGURATION_READ} and {@link
 * Capability#TENANT_CONFIGURATION_WRITE} are that capability, and this
 * controller is deliberately its own — narrower in two ways a shared
 * implementation could not enforce implicitly:
 *
 * <ol>
 *   <li>Every key this controller names, in the list and in a direct lookup
 *       by code, is filtered to {@link ConfigurationKey#tenantVisible()}. A
 *       platform-only default (audit retention, the commercial enforcement
 *       ceiling) is not merely omitted from the index — asking for it by
 *       code answers {@link ErrorCode#RESOURCE_NOT_FOUND}, the same answer
 *       an unregistered code gets, so a tenant cannot distinguish "this key
 *       does not exist" from "this key is not yours to see".
 *   <li>{@code tenantId} is taken only from the path, never from the request
 *       body — unlike {@link ConfigurationController.SetConfigurationValueRequest},
 *       which lets a {@code PLATFORM_ADMIN} caller target any tenant by
 *       design. A caller here holds {@link Capability#TENANT_CONFIGURATION_WRITE}
 *       at exactly the tenant the path names (ADR 0025's scope check), and
 *       every {@link ResourceScope} this controller builds is pinned to that
 *       same tenant; a request body cannot smuggle a different one in.
 * </ol>
 *
 * <p>{@code scopeType=PLATFORM} is refused outright (as {@link
 * ErrorCode#VALIDATION_FAILED}): the scope bar offers TENANT, BRAND and
 * LOCATION — the levels a tenant may actually edit — and a platform default
 * is something this surface only ever inherits from, never writes.
 *
 * <p><strong>Locked by plan (settings.md §1.2's fifth state, ADR 0021).</strong> A key may name the plan
 * feature that gates it ({@link ConfigurationKey#entitledBy()}). The key list and the resolution report
 * {@code lockedByPlan} while the tenant's plan does not include that feature, as the platform currently
 * enforces it ({@link EntitlementGate#checkFeature}: under ADR 0021's meter-only default a feature nobody
 * granted still answers yes, so nothing is locked until enforcement is raised, and the screen never claims
 * a refusal the platform would not make). A write to a locked key is refused with {@link
 * ErrorCode#ENTITLEMENT_REQUIRED} and not {@code INSUFFICIENT_CAPABILITY}: the person holds the right to
 * edit, the plan lacks the feature, and the remedy is a purchase and not a grant.
 *
 * <p><strong>Where each value is set (settings.md §10.0).</strong> {@code GET .../placements} answers
 * «Find a setting»: for every key this tenant may see, the rows stored for it at tenant, brand and branch
 * level, with the brand and branch named and the value shortened for a line of text; and for each listed
 * policy family the newest version at every scope that holds one, who published it and when.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/configuration")
@Tag(
        name = "Operations configuration",
        description = "A tenant's own read, write and resolution trace over its ADR 0030 configuration")
public class OperationsConfigurationController {

    private final ConfigurationResolver resolver;
    private final ConfigurationValueAuthor values;
    private final CurrentActor currentActor;
    private final StaffDirectory staff;
    private final ConfigurationPlacementService placements;
    private final ObjectProvider<EntitlementGate> entitlementGates;

    /** The longest value a placement line prints; a longer string is cut and says so. */
    static final int DISPLAY_VALUE_MAX_CHARACTERS = 80;

    public OperationsConfigurationController(
            ConfigurationResolver resolver,
            ConfigurationValueAuthor values,
            CurrentActor currentActor,
            StaffDirectory staff,
            ConfigurationPlacementService placements,
            ObjectProvider<EntitlementGate> entitlementGates) {
        this.resolver = resolver;
        this.values = values;
        this.currentActor = currentActor;
        this.staff = staff;
        this.placements = placements;
        this.entitlementGates = entitlementGates;
    }

    @GetMapping("/keys")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "Every configuration key this tenant may see",
            description = "ConfigurationKeys.all() filtered to tenantVisible() — the settings.md "
                    + "\"find a setting\" registry and the scope bar's own key picker. A platform-only "
                    + "default is absent here even though ConfigurationController's PLATFORM_ADMIN "
                    + "surface still lists it.")
    List<OperationsConfigurationKeyResponse> keys(@PathVariable UUID tenantId) {
        return ConfigurationKeys.all().stream()
                .filter(ConfigurationKey::tenantVisible)
                .map(key -> OperationsConfigurationKeyResponse.of(key, lockOf(tenantId, key)))
                .sorted((left, right) -> left.code().compareTo(right.code()))
                .toList();
    }

    @GetMapping("/placements")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "Where each setting and policy of this tenant is set",
            description = "settings.md §10.0 «Find a setting»: for every key this tenant may see, the "
                    + "values stored for it at TENANT, BRAND and LOCATION scope (brand and branch named, "
                    + "the value shortened to a line of text, who set it and when), and for each listed "
                    + "policy family the newest published version at every scope that holds one. A key "
                    + "nobody has set has an empty list: the code default applies. Secrets are not "
                    + "configuration (ADR 0030), so values are shown; a long string is cut at "
                    + "80 characters and flagged.")
    OperationsConfigurationPlacementsResponse placements(@PathVariable UUID tenantId) {
        List<StoredValue> stored = placements.values(tenantId);
        List<StoredPolicy> policies = placements.policies(tenantId);
        List<String> principals = new ArrayList<>();
        stored.stream().map(StoredValue::setBy).filter(Objects::nonNull).forEach(principals::add);
        policies.stream().map(StoredPolicy::principal).filter(Objects::nonNull).forEach(principals::add);
        Map<String, String> names = principals.isEmpty()
                ? Map.of()
                : staff.namesOf(tenantId, principals.stream().distinct().toList());

        Map<String, List<OperationsConfigurationPlacement>> byKey = new LinkedHashMap<>();
        for (ConfigurationKey<?> key : ConfigurationKeys.all()) {
            if (key.tenantVisible()) {
                byKey.put(key.code(), new ArrayList<>());
            }
        }
        for (StoredValue value : stored) {
            // A row for a key this tenant may not see (or that no longer exists) is not shown, whatever it holds.
            List<OperationsConfigurationPlacement> bucket = byKey.get(value.keyCode());
            if (bucket != null) {
                bucket.add(OperationsConfigurationPlacement.of(value, names));
            }
        }
        List<OperationsConfigurationKeyPlacements> keys = byKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new OperationsConfigurationKeyPlacements(entry.getKey(), entry.getValue()))
                .toList();

        Map<String, List<OperationsPolicyPlacement>> byPolicy = new LinkedHashMap<>();
        for (String code : uz.horecaos.platform.tenancy.application.TenantPolicyFamilies.LISTED) {
            byPolicy.put(code, new ArrayList<>());
        }
        for (StoredPolicy policy : policies) {
            List<OperationsPolicyPlacement> bucket = byPolicy.get(policy.keyCode());
            if (bucket != null) {
                bucket.add(OperationsPolicyPlacement.of(policy, names));
            }
        }
        List<OperationsPolicyPlacements> policyFamilies = byPolicy.entrySet().stream()
                .map(entry -> new OperationsPolicyPlacements(entry.getKey(), entry.getValue()))
                .toList();
        return new OperationsConfigurationPlacementsResponse(keys, policyFamilies);
    }

    @GetMapping("/keys/{code}/resolution")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "Resolve one key at TENANT, BRAND or LOCATION scope, and explain why",
            description = "Walks the ADR 0030 chain and reports which level supplied the value, or "
                    + "that the code default did. tenantId always comes from the path; scopeType "
                    + "decides whether brandId/locationId are required, on the same rule ResourceScope "
                    + "itself enforces. Refused for a key that is not tenantVisible() and for "
                    + "scopeType=PLATFORM.")
    OperationsConfigurationResolutionResponse resolution(
            @PathVariable UUID tenantId,
            @PathVariable String code,
            @RequestParam ScopeType scopeType,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ConfigurationKey<?> key = tenantVisibleKey(code);
        ResourceScope scope = scopeOf(tenantId, scopeType, brandId, locationId);
        return resolveAndExplain(tenantId, key, scope, lockOf(tenantId, key));
    }

    @PostMapping("/keys/{code}/values")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, mutating = true)
    @Operation(
            summary = "Set the value stored at exactly one key and scope, for this tenant",
            description = "Same shape as ConfigurationController.setValue, minus tenantId in the "
                    + "body: the path is the only source of tenant scope here. Refused for an "
                    + "unregistered or non-tenantVisible key, a scope the key does not declare "
                    + "settable, scopeType=PLATFORM, or a value whose shape does not match the "
                    + "key's declared type. expectedVersion null means \"nothing is set here yet\"; "
                    + "otherwise it must be the version a prior GET .../resolution reported at "
                    + "exactly this scope, or the write is refused with STALE_VERSION.")
    ResponseEntity<OperationsConfigurationValueResponse> setValue(
            @PathVariable UUID tenantId,
            @PathVariable String code,
            @Valid @RequestBody OperationsSetConfigurationValueRequest request) {

        ConfigurationKey<?> key = tenantVisibleKey(code);
        ResourceScope scope = scopeOf(tenantId, request.scopeType(), request.brandId(), request.locationId());
        // Before the value is looked at: a key the plan does not include has no valid value to send. The
        // person holds the capability, so this is the plan's refusal (ENTITLEMENT_REQUIRED), not a grant's.
        LockedByPlan locked = lockOf(tenantId, key);
        if (locked != null) {
            throw ApiException.entitlementRequired(locked.entitlementKey());
        }
        return ResponseEntity.ok(setErased(key, scope, request));
    }

    /**
     * Whether the plan locks {@code key} for this tenant right now: the key names a gating feature, the gate
     * knows the feature, and the platform currently enforces its absence. Empty for a key no plan gates, and
     * for a gate that does not know the code (a code drifting from {@code EntitlementKeys} is a test failure,
     * {@code ConfigurationKeyEntitlementTests}, and not a tenant locked out of a setting).
     */
    private @Nullable LockedByPlan lockOf(UUID tenantId, ConfigurationKey<?> key) {
        String entitlement = key.entitledBy();
        if (entitlement == null) {
            return null;
        }
        for (EntitlementGate gate : entitlementGates.orderedStream().toList()) {
            Optional<EntitlementGate.Answer> answer = gate.checkFeature(tenantId, entitlement);
            if (answer.isPresent() && !answer.get().entitled()) {
                return new LockedByPlan(entitlement, answer.get().description());
            }
        }
        return null;
    }

    /** {@link ConfigurationKeys#find}, refusing a key this surface does not show at all. */
    private static ConfigurationKey<?> tenantVisibleKey(String code) {
        ConfigurationKey<?> key = ConfigurationKeys.find(code)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No configuration key " + code));
        if (!key.tenantVisible()) {
            // Deliberately the same error as "unregistered": a tenant must not be
            // able to tell a platform-only key apart from one that does not exist.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No configuration key " + code);
        }
        return key;
    }

    /** Captures the wildcard from {@link ConfigurationKeys#find} so the author's generic methods apply. */
    private <T> OperationsConfigurationValueResponse setErased(
            ConfigurationKey<T> key, ResourceScope scope, OperationsSetConfigurationValueRequest request) {
        T typedValue = request.explicitNull() ? null : extractTypedValue(key, request);
        AuthoredConfigurationValue<T> authored = values.set(
                key,
                scope,
                typedValue,
                request.explicitNull(),
                request.expectedVersion(),
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return OperationsConfigurationValueResponse.of(authored);
    }

    /**
     * Picks the one request field matching {@code key.valueType()} and refuses
     * everything else — a string sent for an integer key is a refusal, not a
     * cast. Mirrors {@link ConfigurationController}'s own extraction exactly.
     */
    private static <T> T extractTypedValue(ConfigurationKey<T> key, OperationsSetConfigurationValueRequest request) {
        Class<T> type = key.valueType();
        String expectedField;
        Object raw;
        if (type == Boolean.class) {
            expectedField = "booleanValue";
            raw = request.booleanValue();
        } else if (type == Integer.class || type == Long.class) {
            expectedField = "integerValue";
            raw = request.integerValue();
        } else if (type == BigDecimal.class) {
            expectedField = "decimalValue";
            raw = request.decimalValue();
        } else if (type == String.class) {
            expectedField = "stringValue";
            raw = request.stringValue();
        } else {
            throw new IllegalStateException("Unsupported configuration value type " + type);
        }
        requireOnlyField(request, expectedField);
        if (raw == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s is a %s key: set %s, or explicitNull"
                            .formatted(key.code(), type.getSimpleName(), expectedField));
        }
        if (type == Integer.class) {
            // The request carries a Long; an Integer key stores 32 bits. Narrowing with toIntExact
            // would surface a number outside them as an unmapped ArithmeticException, a 500.
            long wide = (Long) raw;
            if (wide < Integer.MIN_VALUE || wide > Integer.MAX_VALUE) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "%s is an Integer key: %d is outside %d to %d"
                                .formatted(key.code(), wide, Integer.MIN_VALUE, Integer.MAX_VALUE));
            }
            raw = (int) wide;
        }
        return type.cast(raw);
    }

    private static void requireOnlyField(OperationsSetConfigurationValueRequest request, String expectedField) {
        List<String> extraneous = new ArrayList<>();
        if (!"booleanValue".equals(expectedField) && request.booleanValue() != null) {
            extraneous.add("booleanValue");
        }
        if (!"integerValue".equals(expectedField) && request.integerValue() != null) {
            extraneous.add("integerValue");
        }
        if (!"decimalValue".equals(expectedField) && request.decimalValue() != null) {
            extraneous.add("decimalValue");
        }
        if (!"stringValue".equals(expectedField) && request.stringValue() != null) {
            extraneous.add("stringValue");
        }
        if (!extraneous.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Only %s applies to this key's type; also set: %s".formatted(expectedField, extraneous));
        }
    }

    /** Captures the wildcard from {@link ConfigurationKey#find} so the resolver's generic methods apply. */
    private <T> OperationsConfigurationResolutionResponse resolveAndExplain(
            UUID tenantId, ConfigurationKey<T> key, ResourceScope scope, @Nullable LockedByPlan lockedByPlan) {
        Resolved<T> resolved = resolver.resolve(key, scope);
        ResolutionTrace trace = resolver.explain(key, scope);
        Long currentVersionAtScope = values.currentVersion(key, scope).orElse(null);
        return OperationsConfigurationResolutionResponse.of(
                key, resolved, trace, currentVersionAtScope, namesOfChangers(tenantId, trace), lockedByPlan);
    }

    /**
     * The people behind the trace's rungs, named. The trace holds subjects (ids) and nothing else
     * (ADR 0029); the names are looked up here, for the tenant the path names, in one read, and go
     * into this response only. A subject with no member row in this tenant (a support session, a
     * device, an account the tenant never recorded) is absent from the map and the console says so.
     */
    private Map<String, String> namesOfChangers(UUID tenantId, ResolutionTrace trace) {
        List<String> principals = trace.inspectedLevels().stream()
                .map(ResolutionTrace.Level::provenance)
                .filter(Objects::nonNull)
                .map(ResolutionTrace.Provenance::principal)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return principals.isEmpty() ? Map.of() : staff.namesOf(tenantId, principals);
    }

    /**
     * Builds a scope pinned to {@code tenantId} from the path — the request
     * never supplies its own tenant. {@code PLATFORM} is refused: nothing on
     * this surface ever sets a platform default.
     */
    private static ResourceScope scopeOf(
            UUID tenantId, ScopeType scopeType, @Nullable UUID brandId, @Nullable UUID locationId) {
        try {
            return switch (scopeType) {
                case PLATFORM ->
                    throw new IllegalArgumentException("scopeType PLATFORM is not settable from operations");
                case TENANT -> ResourceScope.tenant(tenantId);
                case BRAND -> ResourceScope.brand(tenantId, require(brandId, "brandId"));
                case LOCATION ->
                    ResourceScope.location(tenantId, require(brandId, "brandId"), require(locationId, "locationId"));
            };
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    private static UUID require(@Nullable UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required for this scopeType");
        }
        return value;
    }

    /** One declared, tenant-visible key, as the code registered it. */
    public record OperationsConfigurationKeyResponse(
            String code,
            String valueType,
            @Nullable Object defaultValue,
            List<ScopeType> settableScopes,
            String owningModule,
            boolean explicitNullTerminates,
            String description,
            @Nullable String entitledBy,
            @Nullable LockedByPlan lockedByPlan) {

        static OperationsConfigurationKeyResponse of(ConfigurationKey<?> key, @Nullable LockedByPlan lockedByPlan) {
            return new OperationsConfigurationKeyResponse(
                    key.code(),
                    key.valueType().getSimpleName(),
                    key.defaultValue(),
                    // PLATFORM is never a settable scope from this surface's own POST,
                    // but it is still true information about the key's declaration —
                    // shown so the scope bar can explain why a value it did not set
                    // still resolves to something.
                    key.settableScopes().stream().sorted().toList(),
                    key.owningModule(),
                    key.explicitNullTerminates(),
                    key.description(),
                    key.entitledBy(),
                    lockedByPlan);
        }
    }

    /**
     * A key the tenant's plan does not include (settings.md §1.2, ADR 0021): the entitlement it needs and what that
     * entitlement means, in the catalogue's words. Never an upgrade link: where a merchant buys is the console's own
     * Finance screen, and the platform's plan path is an API path.
     */
    public record LockedByPlan(String entitlementKey, String description) {}

    /** What a key resolved to at one scope, and the trace that explains it. */
    public record OperationsConfigurationResolutionResponse(
            String keyCode,
            @Nullable Object value,
            boolean cameFromDefault,
            String source,
            @Nullable ScopeType winningScope,
            List<OperationsTraceLevel> inspectedLevels,
            String describe,
            @Nullable Long currentVersionAtScope,
            @Nullable LockedByPlan lockedByPlan) {

        /**
         * @param currentVersionAtScope the version of the row stored at exactly
         *                              the requested scope, not the (possibly
         *                              more general) {@code winningScope} —
         *                              absent when nothing is set exactly here.
         *                              Read this before a {@code POST
         *                              .../values} at the same scope: {@code
         *                              null} means pass {@code expectedVersion:
         *                              null} to create, otherwise pass this
         *                              value back.
         */
        static <T> OperationsConfigurationResolutionResponse of(
                ConfigurationKey<T> key,
                Resolved<T> resolved,
                ResolutionTrace trace,
                @Nullable Long currentVersionAtScope,
                Map<String, String> names,
                @Nullable LockedByPlan lockedByPlan) {
            return new OperationsConfigurationResolutionResponse(
                    key.code(),
                    resolved.value(),
                    resolved.cameFromDefault(),
                    trace.source().name(),
                    trace.winningScope(),
                    trace.inspectedLevels().stream()
                            .map(level -> OperationsTraceLevel.of(level, names))
                            .toList(),
                    trace.describe(),
                    currentVersionAtScope,
                    lockedByPlan);
        }
    }

    /**
     * One rung of the ladder. The last three fields are the facts about the row stored at this level
     * (settings.md §1.2: who set it, and when) and are absent for a level where nothing is stored.
     *
     * @param version       the stored row's version
     * @param changedByName who set it, by the name this tenant knows them by; absent when they have no
     *                      member record here
     * @param changedAt     when it was last changed
     */
    public record OperationsTraceLevel(
            ScopeType scopeType,
            String outcome,
            @Nullable Long version,
            @Nullable String changedByName,
            @Nullable Instant changedAt) {

        static OperationsTraceLevel of(ResolutionTrace.Level level, Map<String, String> names) {
            ResolutionTrace.Provenance provenance = level.provenance();
            return new OperationsTraceLevel(
                    level.scopeType(),
                    level.outcome().name(),
                    provenance == null ? null : provenance.version(),
                    provenance == null || provenance.principal() == null ? null : names.get(provenance.principal()),
                    provenance == null ? null : provenance.since());
        }
    }

    /**
     * Sets the value at one key and scope, for the tenant the path names.
     * Exactly one of {@code booleanValue} / {@code integerValue} / {@code
     * decimalValue} / {@code stringValue} applies, chosen by the key's
     * declared type — the other three, and a value at all when {@code
     * explicitNull} is set, are refusals. Carries no {@code tenantId}: unlike
     * {@link ConfigurationController.SetConfigurationValueRequest}, this
     * surface never lets a caller name a tenant other than the one in the
     * path.
     */
    public record OperationsSetConfigurationValueRequest(
            @NotNull ScopeType scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            boolean explicitNull,
            @Nullable Boolean booleanValue,
            @Nullable Long integerValue,
            @Nullable BigDecimal decimalValue,
            @Nullable String stringValue,
            @Nullable Long expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {}

    /** The row {@link #setValue} just wrote. */
    public record OperationsConfigurationValueResponse(
            UUID id,
            String keyCode,
            ScopeType scopeType,
            @Nullable Object value,
            boolean explicitNull,
            long version) {

        static <T> OperationsConfigurationValueResponse of(AuthoredConfigurationValue<T> authored) {
            return new OperationsConfigurationValueResponse(
                    authored.id(),
                    authored.keyCode(),
                    authored.scopeType(),
                    authored.value(),
                    authored.explicitNull(),
                    authored.version());
        }
    }

    /** Everything {@code GET .../placements} answers: a line per visible key, and a line per listed policy family. */
    public record OperationsConfigurationPlacementsResponse(
            List<OperationsConfigurationKeyPlacements> keys, List<OperationsPolicyPlacements> policies) {}

    /** One key and every place its value is stored; empty when only the code default applies. */
    public record OperationsConfigurationKeyPlacements(
            String keyCode, List<OperationsConfigurationPlacement> placements) {}

    /**
     * One stored value, ready to print as a line of text.
     *
     * @param brandName     the brand's display name, for a brand or branch row
     * @param locationName  the branch's display name, for a branch row
     * @param displayValue  the value as text; null for a deliberate «unset here» (an explicit null)
     * @param displayTruncated whether {@code displayValue} was cut at {@value #MAX} characters
     * @param changedByName who set it, by the name this tenant knows them by; absent when they have no member
     *                      record here (the subject is never sent)
     */
    public record OperationsConfigurationPlacement(
            ScopeType scopeType,
            @Nullable UUID brandId,
            @Nullable String brandName,
            @Nullable UUID locationId,
            @Nullable String locationName,
            boolean explicitNull,
            @Nullable String displayValue,
            boolean displayTruncated,
            long version,
            @Nullable String changedByName,
            @Nullable Instant changedAt) {

        static final int MAX = DISPLAY_VALUE_MAX_CHARACTERS;

        static OperationsConfigurationPlacement of(StoredValue value, Map<String, String> names) {
            String text = value.explicitNull() ? null : displayText(value);
            boolean cut = false;
            if (text != null && text.codePointCount(0, text.length()) > MAX) {
                text = text.substring(0, text.offsetByCodePoints(0, MAX));
                cut = true;
            }
            return new OperationsConfigurationPlacement(
                    value.scopeType(),
                    value.brandId(),
                    value.brandName(),
                    value.locationId(),
                    value.locationName(),
                    value.explicitNull(),
                    text,
                    cut,
                    value.version(),
                    value.setBy() == null ? null : names.get(value.setBy()),
                    value.updatedAt());
        }

        private static @Nullable String displayText(StoredValue value) {
            return switch (value.valueType()) {
                case "BOOLEAN" ->
                    value.booleanValue() == null ? null : value.booleanValue().toString();
                case "INTEGER" ->
                    value.integerValue() == null ? null : value.integerValue().toString();
                case "DECIMAL" ->
                    value.decimalValue() == null
                            ? null
                            : value.decimalValue().stripTrailingZeros().toPlainString();
                default -> value.stringValue();
            };
        }
    }

    /** One listed policy family and the scopes that hold a version of it. */
    public record OperationsPolicyPlacements(String policyCode, List<OperationsPolicyPlacement> placements) {}

    /**
     * The newest published version of a policy at one scope: which version, who approved it and when it took
     * effect (the same facts a resolution trace shows, ADR 0030).
     */
    public record OperationsPolicyPlacement(
            ScopeType scopeType,
            @Nullable UUID brandId,
            @Nullable String brandName,
            @Nullable UUID locationId,
            @Nullable String locationName,
            int version,
            @Nullable String changedByName,
            @Nullable Instant effectiveFrom) {

        static OperationsPolicyPlacement of(StoredPolicy policy, Map<String, String> names) {
            return new OperationsPolicyPlacement(
                    policy.scopeType(),
                    policy.brandId(),
                    policy.brandName(),
                    policy.locationId(),
                    policy.locationName(),
                    policy.version(),
                    policy.principal() == null ? null : names.get(policy.principal()),
                    policy.validFrom());
        }
    }
}
