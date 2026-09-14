package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.EntitlementValue;
import uz.horecaos.platform.commercial.application.ModuleCatalogService;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.StatementService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.application.UsageMeteringService;
import uz.horecaos.platform.commercial.domain.PlanVersion;
import uz.horecaos.platform.commercial.domain.SellableModule;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.Subscription;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
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
 * <p>Finance 8/X.4 adds the one read that was already tenant-scoped and simply
 * unreachable from here: {@code statements}/{@code oneStatement}/{@code
 * statementExport} below mirror {@code CommercialStatementController}'s three
 * reads (ADR 0088) — same {@link StatementService}, same {@code
 * COMMERCIAL_USAGE_READ} capability, same wire shape — at a path this
 * console's own OpenAPI group can reach, so it stops calling
 * {@code /api/v1/control-plane/**} for its own invoices. Issuing and voiding a
 * statement stay HorecaOS-staff-only and stay on the control-plane controller.
 *
 * <p>Finance 8.6 (ADR 0127) adds the tenant-facing purchasable-module
 * catalogue: {@code modulesOnSale} browses what HorecaOS sells under the new
 * {@code COMMERCIAL_MODULE_READ} capability — a tenant-scoped mirror of
 * {@code CommercialModuleController.onSale}'s {@code ScopeType.PLATFORM} read,
 * which no tenant grant could ever satisfy — and {@code purchaseModule} gives
 * the calling tenant one of those modules under {@code
 * COMMERCIAL_SUBSCRIPTION_MANAGE} at {@code ScopeType.TENANT}, the same
 * capability {@code TENANT_OWNER}/{@code TENANT_FINANCE} already hold for
 * this exact scope (`PlatformRole.java`) with no bundle change required. It
 * reuses {@link ModuleCatalogService#add} unchanged — the same on-sale check,
 * per-unit quantity rule, and one-live-instance-per-module guard the
 * platform-admin route enforces — with a fixed, non-PII reason recorded on
 * the audit trail rather than asking the merchant to type one for a purchase
 * click. Reading is composed into tenant roles the same way {@link
 * #entitlements} is; purchasing is execution authority, kept to the same pair
 * that already holds {@code refund.execute} (`PlatformRoleTests
 * .aTenantAdminHasNoCommercialOrExecutionAuthority`).
 *
 * <p>Period close does not belong on this list: ADR 0088 (Built) already
 * decided a month is closed by issuing its statement, which is HorecaOS-staff
 * work on {@code CommercialStatementController.issue} — deliberately manual
 * until tax and invoicing are approved (that ADR's own Alternatives table).
 * There is nothing left here to add for it; ADR 0021's older checklist line
 * predates ADR 0088 and is stale on that point.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/commercial")
@Tag(name = "Commercial", description = "The merchant's own plan, entitlements, and usage")
public class CommercialOperationsController {

    /** Non-PII: a purchase from this screen is the tenant's own act, recorded like any other. */
    private static final String SELF_SERVICE_PURCHASE_REASON = "Purchased from the operations console";

    private final SubscriptionService subscriptions;
    private final EntitlementService entitlements;
    private final UsageMeteringService usage;
    private final PlanCatalogService plans;
    private final StatementService statements;
    private final ModuleCatalogService modules;
    private final CurrentActor currentActor;

    public CommercialOperationsController(
            SubscriptionService subscriptions,
            EntitlementService entitlements,
            UsageMeteringService usage,
            PlanCatalogService plans,
            StatementService statements,
            ModuleCatalogService modules,
            CurrentActor currentActor) {
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.usage = usage;
        this.plans = plans;
        this.statements = statements;
        this.modules = modules;
        this.currentActor = currentActor;
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

    @GetMapping("/statements")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every statement this tenant has been issued",
            description = "Newest month first, void ones included. Mirrors "
                    + "CommercialStatementController.list (ADR 0088).")
    public ResponseEntity<List<CommercialStatementController.StatementView>> statements(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(statements.list(tenantId).stream()
                .map(CommercialStatementController.StatementView::of)
                .toList());
    }

    // Named oneStatement, not statement: OperationsCourierController already has a
    // statement() (the settlement-period download), and springdoc would otherwise
    // silently rename one of the two operationIds to "statement_1" in the
    // generated client — a rename callers would not see coming.
    @GetMapping("/statements/{statementId}")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(summary = "One issued statement with its lines")
    public ResponseEntity<CommercialStatementController.StatementView> oneStatement(
            @PathVariable UUID tenantId, @PathVariable UUID statementId) {
        return ResponseEntity.ok(
                CommercialStatementController.StatementView.of(statements.find(tenantId, statementId)));
    }

    @GetMapping(path = "/statements/{statementId}/export", produces = "text/csv")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "One issued statement as CSV",
            description = "For the accounting system an invoice is made in. Amounts are integer "
                    + "minor units of the statement's currency.")
    public ResponseEntity<String> statementExport(@PathVariable UUID tenantId, @PathVariable UUID statementId) {
        Statement statement = statements.find(tenantId, statementId);
        String filename = "statement-" + statement.number() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString())
                .body(CommercialStatementController.csv(statement));
    }

    @GetMapping("/modules")
    @RequiresCapability(value = Capability.COMMERCIAL_MODULE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Modules HorecaOS sells, that this tenant could add",
            description = "Activated and not retired; drafts are not quotable. Mirrors "
                    + "CommercialModuleController.onSale (ADR 0127).")
    public ResponseEntity<List<CommercialModuleController.ModuleView>> modulesOnSale(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(modules.onSale().stream()
                .map(CommercialModuleController.ModuleView::of)
                .toList());
    }

    @GetMapping("/modules/held")
    @RequiresCapability(value = Capability.COMMERCIAL_MODULE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every module this tenant has had",
            description = "Live ones first. Mirrors CommercialModuleController.tenantModules (ADR 0127), "
                    + "at a path this console can reach.")
    public ResponseEntity<List<CommercialModuleController.TenantModuleView>> modulesHeld(@PathVariable UUID tenantId) {
        Map<UUID, SellableModule> byId =
                modules.all().stream().collect(Collectors.toMap(SellableModule::id, Function.identity()));
        return ResponseEntity.ok(modules.tenantModules(tenantId).stream()
                .map(held -> CommercialModuleController.TenantModuleView.of(held, byId.get(held.moduleId())))
                .toList());
    }

    @PostMapping("/modules")
    @RequiresCapability(value = Capability.COMMERCIAL_SUBSCRIPTION_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Add one of the on-sale modules to this tenant",
            description = "The inline purchase Finance 8.6 asks for: a quantity is required exactly "
                    + "when the module is billed per unit, and its features switch on at once. "
                    + "Billed on the next statement (ADR 0088) — this does not move money by itself.")
    public ResponseEntity<CommercialModuleController.TenantModuleAdded> purchaseModule(
            @PathVariable UUID tenantId, @Valid @RequestBody PurchaseModuleRequest body) {
        UUID id = modules.add(
                tenantId, body.moduleId(), body.quantity(), actor(), SELF_SERVICE_PURCHASE_REASON, correlationId());
        return ResponseEntity.ok(new CommercialModuleController.TenantModuleAdded(id));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    // ---------------------------------------------------------- wire records

    public record PurchaseModuleRequest(
            @NotNull UUID moduleId, @Min(1) Integer quantity) {}

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
