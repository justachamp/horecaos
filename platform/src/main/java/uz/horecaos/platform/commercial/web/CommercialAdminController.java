package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.application.UsageMeteringService;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.commercial.domain.PlanVersion;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The commercial acts only HorecaOS staff perform (ADR 0021), and the two
 * reads that authoring them needs: every plan with its drafts, and the
 * entitlement keys a plan may name.
 *
 * <p>Every declaration is {@link ScopeType#PLATFORM}, including the paths that
 * name a tenant. Assigning a subscription, granting an override and adjusting a
 * metered figure are decisions about what HorecaOS sells, taken by HorecaOS; a
 * tenant-scoped grant that reached any of them would let a restaurant's own
 * administrator raise its limits.
 *
 * <p>Every mutation carries a reason and an {@code Idempotency-Key} per
 * ADR 0031, and every one produces an ADR 0027 audit fact in the same
 * transaction as the change.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/commercial")
@Tag(name = "Commercial administration", description = "Plans, subscriptions, overrides, and usage corrections")
public class CommercialAdminController {

    private final PlanCatalogService plans;
    private final SubscriptionService subscriptions;
    private final UsageMeteringService metering;
    private final CurrentActor currentActor;

    public CommercialAdminController(
            PlanCatalogService plans,
            SubscriptionService subscriptions,
            UsageMeteringService metering,
            CurrentActor currentActor) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.metering = metering;
        this.currentActor = currentActor;
    }

    @GetMapping("/plans")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every plan with every version, drafts included",
            description = "The authoring view. Each draft names its author, because the person "
                    + "who activates it must be somebody else. The price list tenants read stays "
                    + "live versions only.")
    public ResponseEntity<List<PlanResponse>> plans() {
        return ResponseEntity.ok(plans.catalogueWithDrafts().stream()
                .map(plan -> PlanResponse.of(plan, plans::entitlementsOf))
                .toList());
    }

    @GetMapping("/entitlement-keys")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The entitlement keys a plan version or override may name",
            description =
                    "A counted key takes a limit and a feature takes on or off; a key not " + "listed here is refused.")
    public ResponseEntity<List<EntitlementKeyResponse>> entitlementKeys() {
        return ResponseEntity.ok(EntitlementKeys.all().stream()
                .map(EntitlementKeyResponse::of)
                .sorted(Comparator.comparing(EntitlementKeyResponse::code))
                .toList());
    }

    @PostMapping("/plans")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Register a plan")
    public ResponseEntity<Map<String, Object>> createPlan(@Valid @RequestBody CreatePlanRequest body) {
        UUID id = plans.createPlan(body.code(), body.name(), actor(), body.reason(), correlationId());
        return ResponseEntity.ok(Map.of("planId", id));
    }

    @PostMapping("/plans/{planId}/versions")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Draft a new version of a plan",
            description = "Always a new version, never an edit. The price is integer minor units "
                    + "of the version's currency; for UZS a minor unit is one whole som.")
    public ResponseEntity<Map<String, Object>> draftVersion(
            @PathVariable UUID planId, @Valid @RequestBody DraftVersionRequest body) {

        UUID id = plans.draftVersion(
                planId,
                body.currency(),
                body.priceMinor(),
                body.billingPeriod(),
                body.termsReference(),
                toEntitlements(body.entitlements()),
                actor(),
                body.reason(),
                correlationId());
        return ResponseEntity.ok(Map.of("planVersionId", id));
    }

    @PostMapping("/plan-versions/{planVersionId}/activation")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_ACTIVATE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Make a plan version live",
            description = "Irreversible: the version and its entitlements become immutable at the "
                    + "database. Refused when the approver is the version's author.")
    public ResponseEntity<Void> activate(@PathVariable UUID planVersionId, @Valid @RequestBody ReasonRequest body) {

        plans.activate(planVersionId, actor(), body.reason(), correlationId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tenants/{tenantId}/subscriptions")
    @RequiresCapability(value = Capability.COMMERCIAL_SUBSCRIPTION_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Put a tenant on a plan version",
            description = "Manual assignment. ADR 0021's first slice does not automate recurring "
                    + "billing, and an operator's name against a subscription is an honest record "
                    + "of that rather than a placeholder.")
    public ResponseEntity<Map<String, Object>> startSubscription(
            @PathVariable UUID tenantId, @Valid @RequestBody StartSubscriptionRequest body) {

        UUID id = subscriptions.start(
                tenantId, body.planVersionId(), body.trialDays(), actor(), body.reason(), correlationId());
        return ResponseEntity.ok(Map.of("subscriptionId", id));
    }

    @PostMapping("/tenants/{tenantId}/subscription-transitions")
    @RequiresCapability(value = Capability.COMMERCIAL_SUBSCRIPTION_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Move a subscription's status",
            description = "Suspension degrades what may be added and deletes nothing. The "
                    + "expected version is required, so two operators deciding at once settle at "
                    + "one outcome.")
    public ResponseEntity<Void> transition(@PathVariable UUID tenantId, @Valid @RequestBody TransitionRequest body) {

        SubscriptionStatus target;
        try {
            target = SubscriptionStatus.valueOf(body.status());
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Unknown subscription status %s".formatted(body.status()));
        }

        subscriptions.transition(
                tenantId,
                target,
                body.expectedVersion(),
                body.suspensionReason(),
                body.cancelAt(),
                actor(),
                body.reason(),
                correlationId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tenants/{tenantId}/entitlement-overrides")
    @RequiresCapability(value = Capability.COMMERCIAL_OVERRIDE_APPROVE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Grant a time-bounded entitlement override",
            description = "Time-bounded is not optional. An open-ended override is a plan change "
                    + "made without changing the plan, and it outlives everyone who remembers why "
                    + "it was granted.")
    public ResponseEntity<Map<String, Object>> override(
            @PathVariable UUID tenantId, @Valid @RequestBody OverrideRequest body) {

        EntitlementKey<?> key = EntitlementKeys.find(body.entitlementKey())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "Unknown entitlement key %s".formatted(body.entitlementKey())));

        if (key.isCounted() == (body.limit() == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s is %s".formatted(body.entitlementKey(), key.isCounted() ? "a counted limit" : "a feature"));
        }

        UUID id = subscriptions.override(
                tenantId,
                key.code(),
                body.limit(),
                body.enabled(),
                body.validUntil(),
                actor(),
                body.approvedBy(),
                body.reason(),
                correlationId());
        return ResponseEntity.ok(Map.of("overrideId", id));
    }

    @PostMapping("/tenants/{tenantId}/usage-adjustments")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_ADJUST, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Correct a metered figure with a signed adjustment",
            description = "The only way a consumed figure ever changes. The movements that "
                    + "produced the original number stay exactly as they were recorded, which is "
                    + "what makes the correction defensible rather than merely applied.")
    public ResponseEntity<Map<String, Object>> adjust(
            @PathVariable UUID tenantId, @Valid @RequestBody AdjustmentRequest body) {

        EntitlementKey<?> key = EntitlementKeys.find(body.entitlementKey())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "Unknown entitlement key %s".formatted(body.entitlementKey())));
        if (!key.isCounted()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A feature has no quantity to adjust: " + key.code());
        }

        @SuppressWarnings("unchecked")
        EntitlementKey<Long> counted = (EntitlementKey<Long>) key;
        UUID id = metering.adjust(
                tenantId,
                counted,
                body.periodKey(),
                body.quantityDelta(),
                body.reason(),
                body.sourceReference(),
                subject(),
                body.approvedBy());
        return ResponseEntity.ok(Map.of("adjustmentId", id));
    }

    @PostMapping("/tenants/{tenantId}/usage-rebuilds")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_ADJUST, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Recompute every cached total from the ledger",
            description = "Returns the periods whose cached figure disagreed, which should be "
                    + "empty. A non-empty answer is the alert an aggregate cache exists to make "
                    + "possible; a counter updated in place could never produce one.")
    public ResponseEntity<List<UsageMeteringService.Divergence>> rebuild(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(metering.rebuild(tenantId));
    }

    // ----------------------------------------------------------- conversions

    private Map<String, PlanEntitlement> toEntitlements(List<EntitlementLineRequest> lines) {
        Map<String, PlanEntitlement> entitlements = new LinkedHashMap<>();
        if (lines == null) {
            return entitlements;
        }
        for (EntitlementLineRequest line : lines) {
            EnforcementMode mode = parse(EnforcementMode.class, line.enforcementMode(), "enforcement mode");
            ResetPeriod reset = line.resetPeriod() == null
                    ? ResetPeriod.NONE
                    : parse(ResetPeriod.class, line.resetPeriod(), "reset period");

            entitlements.put(
                    line.entitlementKey(),
                    new PlanEntitlement(
                            line.entitlementKey(),
                            line.limit(),
                            line.enabled(),
                            mode,
                            reset,
                            line.warnThresholdBasisPoints(),
                            line.overageUnitPriceMinor()));
        }
        return entitlements;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown %s %s".formatted(what, value));
        }
    }

    private ActorRef actor() {
        return ActorRef.user(subject(), null);
    }

    private String subject() {
        return currentActor.get().subject();
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    // ----------------------------------------------------------- wire records

    /** A plan and all its versions, newest first. */
    public record PlanResponse(UUID planId, String code, String name, String status, List<PlanVersionDetail> versions) {

        static PlanResponse of(
                PlanCatalogService.PlanHistory plan, Function<UUID, Map<String, PlanEntitlement>> entitlementsOf) {
            return new PlanResponse(
                    plan.planId(),
                    plan.code(),
                    plan.name(),
                    plan.status(),
                    plan.versions().stream()
                            .map(version -> PlanVersionDetail.of(version, entitlementsOf.apply(version.id())))
                            .toList());
        }
    }

    /** One version with who drafted it and, once live, who approved it. */
    public record PlanVersionDetail(
            UUID planVersionId,
            int versionNumber,
            ApiMoney price,
            String billingPeriod,
            String status,
            @Nullable String termsReference,
            String createdBy,
            @Nullable String approvedBy,
            @Nullable String activatedAt,
            List<CommercialControlPlaneController.EntitlementLine> entitlements) {

        static PlanVersionDetail of(PlanVersion version, Map<String, PlanEntitlement> entitlements) {
            Instant activatedAt = version.activatedAt();
            return new PlanVersionDetail(
                    version.id(),
                    version.versionNumber(),
                    ApiMoney.of(version.priceMinor(), version.currency()),
                    version.billingPeriod(),
                    version.status(),
                    version.termsReference(),
                    version.createdBy(),
                    version.approvedBy(),
                    activatedAt == null ? null : activatedAt.toString(),
                    entitlements.values().stream()
                            .map(line -> CommercialControlPlaneController.EntitlementLine.of(line, version.currency()))
                            .toList());
        }
    }

    /** An entitlement key: whether it counts or switches, and what it counts. */
    public record EntitlementKeyResponse(
            String code, boolean counted, String unit, String defaultMode, String resetPeriod) {

        static EntitlementKeyResponse of(EntitlementKey<?> key) {
            return new EntitlementKeyResponse(
                    key.code(),
                    key.isCounted(),
                    key.unit(),
                    key.defaultMode().name(),
                    key.resetPeriod().name());
        }
    }

    /** Every mutation carries why it happened; ADR 0027 refuses a user action without one. */
    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record CreatePlanRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 1000) String reason) {}

    public record DraftVersionRequest(
            @NotBlank @Size(min = 3, max = 3) String currency,
            long priceMinor,
            @NotBlank String billingPeriod,
            @Size(max = 500) String termsReference,
            List<EntitlementLineRequest> entitlements,
            @NotBlank @Size(max = 1000) String reason) {}

    public record EntitlementLineRequest(
            @NotBlank String entitlementKey,
            Long limit,
            Boolean enabled,
            @NotBlank String enforcementMode,
            String resetPeriod,
            Integer warnThresholdBasisPoints,
            Long overageUnitPriceMinor) {}

    public record StartSubscriptionRequest(
            @NotNull UUID planVersionId,
            Integer trialDays,
            @NotBlank @Size(max = 1000) String reason) {}

    public record TransitionRequest(
            @NotBlank String status,
            long expectedVersion,
            @Size(max = 500) String suspensionReason,
            Instant cancelAt,
            @NotBlank @Size(max = 1000) String reason) {}

    public record OverrideRequest(
            @NotBlank String entitlementKey,
            Long limit,
            Boolean enabled,
            @NotNull Instant validUntil,
            @NotBlank String approvedBy,
            @NotBlank @Size(max = 1000) String reason) {}

    public record AdjustmentRequest(
            @NotBlank String entitlementKey,
            @NotBlank String periodKey,
            long quantityDelta,
            @Size(max = 200) String sourceReference,
            @NotBlank String approvedBy,
            @NotBlank @Size(max = 1000) String reason) {}
}
