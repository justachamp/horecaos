package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.EntitlementValue;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.application.UsageMeteringService;
import uz.horecaos.platform.commercial.domain.PlanVersion;
import uz.horecaos.platform.commercial.domain.Subscription;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The merchant's own read of its HorecaOS account — Finance 8.6's Subscription &amp;
 * billing screen (ADR 0021).
 *
 * <p>{@code CommercialControlPlaneController} already serves the same three
 * reads, but at {@code /api/v1/control-plane}, which the operations frontend's
 * OpenAPI group cannot reach (ADR 0057). Reusing the same services rather than
 * a second read path keeps "what the plan says" answerable one way.
 *
 * <p>Deliberately thin: ADR 0021's own status line is explicit that there is no
 * period close and no invoice export yet, and the platform-wide plan catalogue
 * (an inline-purchase source) is a {@code ScopeType.PLATFORM} read a tenant
 * grant cannot satisfy — a tenant does not yet browse and buy a module from
 * this screen, and the screen says so rather than a stub inviting a click that
 * goes nowhere.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/commercial")
@Tag(name = "Commercial", description = "The merchant's own plan, entitlements, and usage")
public class CommercialOperationsController {

    private final SubscriptionService subscriptions;
    private final EntitlementService entitlements;
    private final UsageMeteringService usage;
    private final PlanCatalogService plans;

    public CommercialOperationsController(
            SubscriptionService subscriptions,
            EntitlementService entitlements,
            UsageMeteringService usage,
            PlanCatalogService plans) {
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.usage = usage;
        this.plans = plans;
    }

    @GetMapping("/subscription")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.TENANT)
    @Operation(summary = "This tenant's live subscription, plan and term")
    public ResponseEntity<SubscriptionResponse> subscription(@PathVariable UUID tenantId) {
        Subscription live = subscriptions
                .live(tenantId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The tenant has no live subscription"));

        PlanVersion version = plans.versionOf(live.planVersionId());

        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(live.version()))
                .body(SubscriptionResponse.of(live, version));
    }

    @GetMapping("/entitlements")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every module this tenant is entitled to, and why",
            description = "The locked-by-plan half of IA 9.1's locked-vs-denied distinction: a "
                    + "module absent here is not a permission gap, it is a plan that does not "
                    + "include it.")
    public ResponseEntity<EntitlementSnapshotResponse> entitlements(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(EntitlementSnapshotResponse.of(entitlements.snapshot(tenantId)));
    }

    @GetMapping("/usage")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "This tenant's metered usage, reconciled toward an invoice ADR 0021 does not produce yet",
            description = "Measured and adjusted quantities stay apart, the same discipline as "
                    + "the control-plane read: a single consumed total cannot answer how much of "
                    + "a figure a person decided.")
    public ResponseEntity<List<UsageResponse>> usage(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                usage.totals(tenantId).stream().map(UsageResponse::of).toList());
    }

    // ---------------------------------------------------------- wire records

    /** A subscription as the merchant sees it, with its plan named rather than only its id. */
    public record SubscriptionResponse(
            UUID subscriptionId,
            UUID planVersionId,
            String planCode,
            int planVersionNumber,
            ApiMoney price,
            String billingPeriod,
            String status,
            String startAt,
            @Nullable String trialEndAt,
            String currentPeriodStart,
            String currentPeriodEnd,
            @Nullable String suspensionReason,
            long version) {

        static SubscriptionResponse of(Subscription subscription, PlanVersion planVersion) {
            return new SubscriptionResponse(
                    subscription.id(),
                    subscription.planVersionId(),
                    planVersion.planCode(),
                    planVersion.versionNumber(),
                    ApiMoney.of(planVersion.priceMinor(), planVersion.currency()),
                    planVersion.billingPeriod(),
                    subscription.status().name(),
                    subscription.startAt().toString(),
                    text(subscription.trialEndAt()),
                    subscription.currentPeriodStart().toString(),
                    subscription.currentPeriodEnd().toString(),
                    subscription.suspensionReason(),
                    subscription.version());
        }

        private static @Nullable String text(java.time.@Nullable Instant instant) {
            return instant == null ? null : instant.toString();
        }
    }

    /** The whole entitlement set with its hash — mirrors the control-plane response shape. */
    public record EntitlementSnapshotResponse(
            UUID tenantId,
            @Nullable UUID subscriptionId,
            String hash,
            String resolvedAt,
            List<ResolvedEntitlement> entitlements) {

        static EntitlementSnapshotResponse of(EntitlementSnapshot snapshot) {
            return new EntitlementSnapshotResponse(
                    snapshot.tenantId(),
                    snapshot.subscriptionId(),
                    snapshot.hash(),
                    snapshot.resolvedAt().toString(),
                    snapshot.values().values().stream()
                            .map(ResolvedEntitlement::of)
                            .sorted((left, right) -> left.entitlementKey().compareTo(right.entitlementKey()))
                            .toList());
        }
    }

    public record ResolvedEntitlement(
            String entitlementKey,
            @Nullable Long limit,
            @Nullable Boolean enabled,
            String declaredMode,
            String effectiveMode,
            String resetPeriod,
            @Nullable ApiMoney overageUnitPrice,
            String source) {

        static ResolvedEntitlement of(EntitlementValue value) {
            Long overageUnitPriceMinor = value.overageUnitPriceMinor();
            return new ResolvedEntitlement(
                    value.key().code(),
                    value.limit(),
                    value.featureEnabled(),
                    value.declaredMode().name(),
                    value.effectiveMode().name(),
                    value.resetPeriod().name(),
                    overageUnitPriceMinor == null
                            ? null
                            : ApiMoney.of(overageUnitPriceMinor, Objects.requireNonNull(value.currency())),
                    value.source().name());
        }
    }

    public record UsageResponse(
            String entitlementKey,
            String periodKey,
            String periodStart,
            String periodEnd,
            long measuredQuantity,
            long adjustedQuantity,
            long consumedQuantity,
            int movementCount,
            @Nullable String lastEventAt) {

        static UsageResponse of(JdbcUsageStore.StoredPeriodTotal total) {
            java.time.Instant lastEventAt = total.lastEventAt();
            return new UsageResponse(
                    total.entitlementKey(),
                    total.periodKey(),
                    total.periodStart().toString(),
                    total.periodEnd().toString(),
                    total.eventQuantity(),
                    total.adjustmentQuantity(),
                    total.consumedQuantity(),
                    total.eventCount(),
                    lastEventAt == null ? null : lastEventAt.toString());
        }
    }
}
