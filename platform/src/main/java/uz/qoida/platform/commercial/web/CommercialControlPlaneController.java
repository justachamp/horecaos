package uz.qoida.platform.commercial.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.commercial.api.EntitlementSnapshot;
import uz.qoida.platform.commercial.api.EntitlementValue;
import uz.qoida.platform.commercial.application.PlanCatalogService;
import uz.qoida.platform.commercial.application.SubscriptionService;
import uz.qoida.platform.commercial.application.UsageMeteringService;
import uz.qoida.platform.commercial.domain.PlanEntitlement;
import uz.qoida.platform.commercial.domain.PlanVersion;
import uz.qoida.platform.commercial.domain.Subscription;
import uz.qoida.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.qoida.platform.commercial.api.EntitlementService;
import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.web.api.AggregateVersion;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ApiMoney;
import uz.qoida.platform.web.api.ErrorCode;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * What a tenant and its account manager can read (ADR 0021).
 *
 * <p>Reads only. Every commercial mutation is a platform-admin act and lives on
 * the other controller, because a tenant raising its own limit is not a
 * self-service feature, it is a pricing decision.
 *
 * <p>The usage response carries the measured and the adjusted quantity
 * separately, and every entitlement carries where its value came from. Both are
 * there for the same reason: the console prototype's whole argument is that a
 * figure an operator cannot explain to an owner on the phone is a figure that
 * gets mistrusted, and "14 250 000" without "9 000 000 plus 21 at 250 000" is
 * that figure.
 */
@RestController
@RequestMapping("/api/v1/control-plane")
@Tag(name = "Commercial", description = "Plans, subscriptions, entitlements, and metered usage")
public class CommercialControlPlaneController {

    private final PlanCatalogService plans;
    private final SubscriptionService subscriptions;
    private final EntitlementService entitlements;
    private final UsageMeteringService usage;

    public CommercialControlPlaneController(PlanCatalogService plans,
            SubscriptionService subscriptions, EntitlementService entitlements,
            UsageMeteringService usage) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.usage = usage;
    }

    @GetMapping("/plans")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "The activated plan catalogue",
            description = "Only activated versions. A draft is an unfinished commercial "
                    + "decision and showing one invites somebody to quote it.")
    public ResponseEntity<List<PlanVersionResponse>> planCatalogue() {
        return ResponseEntity.ok(plans.activeVersions().stream()
                .map(version -> PlanVersionResponse.of(version, plans.entitlementsOf(version.id())))
                .toList());
    }

    @GetMapping("/tenants/{tenantId}/subscription")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.TENANT)
    @Operation(summary = "The tenant's live subscription")
    public ResponseEntity<SubscriptionResponse> subscription(@PathVariable UUID tenantId) {
        Subscription live = subscriptions.live(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "The tenant has no live subscription"));

        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(live.version()))
                .body(SubscriptionResponse.of(live));
    }

    @GetMapping("/tenants/{tenantId}/entitlements")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.TENANT)
    @Operation(summary = "Everything the tenant is entitled to, and why",
            description = "Each entry names the source its value came from — an override, the "
                    + "plan version, a suspension policy, or the code default — and the mode "
                    + "actually in force after the tenant's enforcement ceiling. Support reads "
                    + "this instead of guessing which of four tables applied.")
    public ResponseEntity<EntitlementSnapshotResponse> entitlements(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(EntitlementSnapshotResponse.of(entitlements.snapshot(tenantId)));
    }

    @GetMapping("/tenants/{tenantId}/usage")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(summary = "Metered usage per key and period",
            description = "Measured and adjusted quantities stay apart. A single consumed total "
                    + "cannot answer how much of a figure a person decided, and that is the first "
                    + "question asked about a disputed one.")
    public ResponseEntity<List<UsageResponse>> usage(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(usage.totals(tenantId).stream()
                .map(UsageResponse::of)
                .toList());
    }

    // ---------------------------------------------------------- wire records

    /** A plan version as the price list shows it. */
    public record PlanVersionResponse(
            UUID planVersionId,
            String planCode,
            int versionNumber,
            ApiMoney price,
            String billingPeriod,
            List<EntitlementLine> entitlements) {

        static PlanVersionResponse of(PlanVersion version, Map<String, PlanEntitlement> entitlements) {
            return new PlanVersionResponse(
                    version.id(), version.planCode(), version.versionNumber(),
                    ApiMoney.of(version.priceMinor(), version.currency()),
                    version.billingPeriod(),
                    entitlements.values().stream()
                            .map(line -> EntitlementLine.of(line, version.currency()))
                            .toList());
        }
    }

    /** One line of a plan: the limit, the boundary behaviour, and the overage rate. */
    public record EntitlementLine(
            String entitlementKey,
            Long limit,
            Boolean enabled,
            String enforcementMode,
            String resetPeriod,
            Integer warnThresholdBasisPoints,
            ApiMoney overageUnitPrice) {

        static EntitlementLine of(PlanEntitlement entitlement, String currency) {
            return new EntitlementLine(
                    entitlement.entitlementKey(),
                    entitlement.integerValue(),
                    entitlement.booleanValue(),
                    entitlement.enforcementMode().name(),
                    entitlement.resetPeriod().name(),
                    entitlement.warnThresholdBasisPoints(),
                    entitlement.overageUnitPriceMinor() == null
                            ? null : ApiMoney.of(entitlement.overageUnitPriceMinor(), currency));
        }
    }

    /** A subscription as the console shows it. */
    public record SubscriptionResponse(
            UUID subscriptionId,
            UUID planVersionId,
            String status,
            String startAt,
            String trialEndAt,
            String currentPeriodStart,
            String currentPeriodEnd,
            String suspensionReason,
            long version) {

        static SubscriptionResponse of(Subscription subscription) {
            return new SubscriptionResponse(
                    subscription.id(), subscription.planVersionId(), subscription.status().name(),
                    text(subscription.startAt()), text(subscription.trialEndAt()),
                    text(subscription.currentPeriodStart()), text(subscription.currentPeriodEnd()),
                    subscription.suspensionReason(), subscription.version());
        }

        private static String text(java.time.Instant instant) {
            return instant == null ? null : instant.toString();
        }
    }

    /** The whole entitlement set with its hash. */
    public record EntitlementSnapshotResponse(
            UUID tenantId,
            UUID subscriptionId,
            String hash,
            String resolvedAt,
            List<ResolvedEntitlement> entitlements) {

        static EntitlementSnapshotResponse of(EntitlementSnapshot snapshot) {
            return new EntitlementSnapshotResponse(
                    snapshot.tenantId(), snapshot.subscriptionId(), snapshot.hash(),
                    snapshot.resolvedAt().toString(),
                    snapshot.values().values().stream()
                            .map(ResolvedEntitlement::of)
                            .sorted((left, right) -> left.entitlementKey().compareTo(right.entitlementKey()))
                            .toList());
        }
    }

    /** One resolved entitlement, with its provenance. */
    public record ResolvedEntitlement(
            String entitlementKey,
            Long limit,
            Boolean enabled,
            String declaredMode,
            String effectiveMode,
            String resetPeriod,
            ApiMoney overageUnitPrice,
            String source) {

        static ResolvedEntitlement of(EntitlementValue value) {
            return new ResolvedEntitlement(
                    value.key().code(), value.limit(), value.featureEnabled(),
                    value.declaredMode().name(), value.effectiveMode().name(),
                    value.resetPeriod().name(),
                    value.overageUnitPriceMinor() == null
                            ? null : ApiMoney.of(value.overageUnitPriceMinor(), value.currency()),
                    value.source().name());
        }
    }

    /** One period's usage, decomposed. */
    public record UsageResponse(
            String entitlementKey,
            String periodKey,
            String periodStart,
            String periodEnd,
            long measuredQuantity,
            long adjustedQuantity,
            long consumedQuantity,
            int movementCount,
            String lastEventAt) {

        static UsageResponse of(JdbcUsageStore.StoredPeriodTotal total) {
            return new UsageResponse(
                    total.entitlementKey(), total.periodKey(),
                    total.periodStart().toString(), total.periodEnd().toString(),
                    total.eventQuantity(), total.adjustmentQuantity(), total.consumedQuantity(),
                    total.eventCount(),
                    total.lastEventAt() == null ? null : total.lastEventAt().toString());
        }
    }
}
