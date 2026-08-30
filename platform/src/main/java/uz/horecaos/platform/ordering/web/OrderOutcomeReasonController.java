package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OrderOutcomeReasonService;
import uz.horecaos.platform.ordering.domain.CustomerRefund;
import uz.horecaos.platform.ordering.domain.LiabilityParty;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.domain.OutcomeSystemCategory;
import uz.horecaos.platform.ordering.domain.StockDisposition;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Authoring the tenant's cancellation and completion reasons (ADR 0039).
 *
 * <p>A control-plane surface at {@code TENANT} scope, deliberately away from the
 * order board. The whole argument for putting the stock disposition and the
 * liable party on the reason rather than in the cancel dialog is that they are
 * decided once, in advance, by somebody who can be asked to justify them — and an
 * endpoint an operator could reach mid-shift would give that back.
 *
 * <p>Every reason is written in all three locales at once. A half-translated
 * reason is an intermediate state, and intermediate states are what get used by
 * accident — after which a customer reading Uzbek is told the operator's
 * shorthand or told nothing.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/order-outcome-reasons")
@Tag(name = "Order outcome reasons", description = "Why orders are cancelled and how they are completed, per tenant")
public class OrderOutcomeReasonController {

    private final OrderOutcomeReasonService reasons;

    public OrderOutcomeReasonController(OrderOutcomeReasonService reasons) {
        this.reasons = reasons;
    }

    @GetMapping
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The reasons of one kind",
            description = "Read with ORDER_READ rather than the manage capability, because the "
                    + "cancel dialog has to populate its list for every operator who can cancel "
                    + "an order and almost none of them may author one.")
    public ResponseEntity<List<ReasonResponse>> list(
            @PathVariable UUID tenantId,
            @RequestParam OutcomeReasonKind kind,
            @RequestParam(defaultValue = "true") boolean activeOnly) {

        return ResponseEntity.ok(reasons.list(tenantId, kind, activeOnly).stream()
                .map(row -> ReasonResponse.of(row, reasons.texts(row.id())))
                .toList());
    }

    @GetMapping("/categories")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The platform categories a reason of this kind may carry",
            description = "Closed and code-owned. Tenant registries drift into dozens of "
                    + "near-duplicates and that is contained rather than prevented: the category "
                    + "is what cross-tenant reporting groups by, and the tenant's own wording is "
                    + "only what the operator picks from.")
    public ResponseEntity<List<String>> categories(@PathVariable UUID tenantId, @RequestParam OutcomeReasonKind kind) {

        return ResponseEntity.ok(OutcomeSystemCategory.selectableFor(kind).stream()
                .map(Enum::name)
                .sorted()
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.ORDER_OUTCOME_REASON_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Register a reason",
            description = "A cancellation reason decides the stock disposition, the liable party "
                    + "and the refund posture; none of the three has a safe default and all three "
                    + "are required. A completion reason names the fulfilment modes it is valid "
                    + "for, because without that «Самовывоз выполнен» lands on a delivery order "
                    + "and the courier SLA report quietly loses it.")
    public ResponseEntity<IdResponse> create(@PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest body) {
        try {
            return ResponseEntity.ok(new IdResponse(reasons.create(tenantId, body.toCommand())));
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @PutMapping("/{reasonId}")
    @RequiresCapability(value = Capability.ORDER_OUTCOME_REASON_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Rewrite a reason and bump its version",
            description = "Outcomes already recorded keep the snapshot they were recorded with. "
                    + "That duplication is deliberate: renaming a reason next year must not "
                    + "rewrite last year's funnel.")
    public ResponseEntity<VersionResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID reasonId,
            @Valid @RequestBody ReasonRequest body,
            HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            return ResponseEntity.ok(new VersionResponse(
                    reasonId, reasons.update(tenantId, reasonId, (int) expected, body.toCommand())));
        } catch (OrderOutcomeReasonService.StaleReasonException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderOutcomeReasonService.ReasonNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @DeleteMapping("/{reasonId}")
    @RequiresCapability(value = Capability.ORDER_OUTCOME_REASON_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Retire a reason",
            description = "Archived, never deleted. Outcomes recorded under it must still resolve, "
                    + "and the active-name uniqueness index frees the name for a replacement.")
    public ResponseEntity<Void> archive(
            @PathVariable UUID tenantId, @PathVariable UUID reasonId, HttpServletRequest request) {
        try {
            reasons.archive(tenantId, reasonId, (int) AggregateVersion.requireIfMatch(request));
            return ResponseEntity.noContent().build();
        } catch (OrderOutcomeReasonService.StaleReasonException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderOutcomeReasonService.ReasonNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    /**
     * @param customerTexts what the customer is told, keyed by locale tag. A
     *                      different statement from {@code internalName}, and the
     *                      split is what stops «Не дозвонились» being published to
     *                      the person who was not reached
     */
    public record ReasonRequest(
            @NotNull OutcomeReasonKind kind,
            @NotNull OutcomeSystemCategory systemCategory,
            @NotBlank @Size(max = 120) String internalName,
            StockDisposition stockDisposition,
            LiabilityParty liabilityParty,
            CustomerRefund customerRefund,
            List<FulfillmentMode> allowedFulfillmentModes,
            @NotEmpty Map<String, String> customerTexts) {

        OrderOutcomeReasonService.CreateReason toCommand() {
            return new OrderOutcomeReasonService.CreateReason(
                    kind,
                    systemCategory,
                    internalName,
                    stockDisposition,
                    liabilityParty,
                    customerRefund,
                    allowedFulfillmentModes,
                    customerTexts);
        }
    }

    public record IdResponse(UUID id) {}

    public record VersionResponse(UUID reasonId, int version) {}

    public record ReasonResponse(
            UUID id,
            String kind,
            String systemCategory,
            String internalName,
            String stockDisposition,
            String liabilityParty,
            String customerRefund,
            List<String> allowedFulfillmentModes,
            Map<String, String> customerTexts,
            String status,
            int version,
            Instant updatedAt) {

        static ReasonResponse of(JdbcOutcomeReasonStore.ReasonRow row, Map<String, String> customerTexts) {
            return new ReasonResponse(
                    row.id(),
                    row.kind().name(),
                    row.systemCategory(),
                    row.internalName(),
                    row.stockDisposition(),
                    row.liabilityParty(),
                    row.customerRefund(),
                    row.allowedFulfillmentModes(),
                    customerTexts,
                    row.status(),
                    row.version(),
                    row.updatedAt());
        }
    }
}
