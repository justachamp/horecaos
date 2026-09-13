package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
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
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.web.OrderOutcomeReasonController.IdResponse;
import uz.horecaos.platform.ordering.web.OrderOutcomeReasonController.ReasonRequest;
import uz.horecaos.platform.ordering.web.OrderOutcomeReasonController.ReasonResponse;
import uz.horecaos.platform.ordering.web.OrderOutcomeReasonController.VersionResponse;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Cancellation and completion reasons, on the surface Settings 10.10 actually
 * lives on.
 *
 * <p>{@link OrderOutcomeReasonController} shipped in the wave that built ADR
 * 0039 under {@code /api/v1/control-plane/tenants/{tenantId}/order-outcome-reasons}
 * — a path named for the platform's admin console — and the operations app's
 * reference-data screen has called across surfaces to it ever since, exactly
 * the shape {@code settings-paths.ts}'s own doc comment names as the pattern
 * this class breaks: {@code OpenApiContractTests} forbids dropping the
 * original path, so it stays published and unused, and this controller
 * re-publishes the five operations (list, categories, create, update,
 * archive) under {@code /api/v1/operations/...}, the {@code operations}
 * OpenAPI group the console's generated client is a first-party consumer of
 * (ADR 0057).
 *
 * <p><strong>A delegate, not a second implementation.</strong> Every method
 * forwards, unmodified, to {@link OrderOutcomeReasonController} — the same
 * shape {@code OperationsProviderInstallationController} used for the
 * equivalent integrations move — so there is exactly one place that decides
 * how a reason is versioned, archived and snapshotted.
 *
 * <p>Capabilities are unchanged: {@link Capability#ORDER_READ} for the two
 * reads (already held by every bundle that can cancel an order, per the
 * delegate's own doc) and {@link Capability#ORDER_OUTCOME_REASON_MANAGE} for
 * the three writes.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/order-outcome-reasons")
@Tag(name = "Order outcome reasons", description = "Why orders are cancelled and how they are completed, per tenant")
public class OperationsOrderOutcomeReasonController {

    private final OrderOutcomeReasonController delegate;

    public OperationsOrderOutcomeReasonController(OrderOutcomeReasonController delegate) {
        this.delegate = delegate;
    }

    @GetMapping
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Operation(summary = "The reasons of one kind")
    public ResponseEntity<List<ReasonResponse>> list(
            @PathVariable UUID tenantId,
            @RequestParam OutcomeReasonKind kind,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return delegate.list(tenantId, kind, activeOnly);
    }

    @GetMapping("/categories")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Operation(summary = "The platform categories a reason of this kind may carry")
    public ResponseEntity<List<String>> categories(@PathVariable UUID tenantId, @RequestParam OutcomeReasonKind kind) {
        return delegate.categories(tenantId, kind);
    }

    @PostMapping
    @RequiresCapability(value = Capability.ORDER_OUTCOME_REASON_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(summary = "Register a reason")
    public ResponseEntity<IdResponse> create(@PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest body) {
        return delegate.create(tenantId, body);
    }

    @PutMapping("/{reasonId}")
    @RequiresCapability(value = Capability.ORDER_OUTCOME_REASON_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Rewrite a reason and bump its version",
            description = "Outcomes already recorded keep the snapshot they were recorded with; see the "
                    + "delegate's own Javadoc for why that duplication is deliberate.")
    public ResponseEntity<VersionResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID reasonId,
            @Valid @RequestBody ReasonRequest body,
            HttpServletRequest request) {
        return delegate.update(tenantId, reasonId, body, request);
    }

    @DeleteMapping("/{reasonId}")
    @RequiresCapability(value = Capability.ORDER_OUTCOME_REASON_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Retire a reason",
            description = "Archived, never deleted; see the delegate's own Javadoc for why the row " + "must survive.")
    public ResponseEntity<Void> archive(
            @PathVariable UUID tenantId, @PathVariable UUID reasonId, HttpServletRequest request) {
        return delegate.archive(tenantId, reasonId, request);
    }
}
