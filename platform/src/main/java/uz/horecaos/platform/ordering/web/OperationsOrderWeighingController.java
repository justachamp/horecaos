package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.CatchweightReconciliationService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The scale at the pass (ADR 0137): capturing the weighed total of a catchweight line at
 * pick or handover.
 *
 * <p>Its own controller on {@code OperationsOrderController}'s path rather than a method on
 * it, because the two have nothing in common but the prefix: that class is the branch's
 * queue, approvals and timeline, with twenty collaborators; this is one write with one.
 *
 * <p>ADR 0137 places the endpoint "beside whatever endpoint ADR 0038 already uses to capture a
 * mark at PICK/HANDOVER". No such endpoint exists yet (marking is not built), so it sits with
 * the other operations writes of the same moment, under {@code order.advance} -- the capability
 * held by the hands that hand the food over.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders")
@Tag(name = "Operations orders", description = "The branch's order queue, approvals, and timeline")
public class OperationsOrderWeighingController {

    private final CatchweightReconciliationService reconciliation;
    private final OrderQueryService orderQuery;
    private final CurrentActor currentActor;

    public OperationsOrderWeighingController(
            CatchweightReconciliationService reconciliation, OrderQueryService orderQuery, CurrentActor currentActor) {
        this.reconciliation = reconciliation;
        this.orderQuery = orderQuery;
        this.currentActor = currentActor;
    }

    @PutMapping("/{orderId}/lines/{lineId}/actual-weight")
    @RequiresCapability(value = Capability.ORDER_ADVANCE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Capture the weighed total of a catchweight line at pick or handover",
            description = "ADR 0137. Writes the line's actual weight and corrects the line, the order's "
                    + "totals and, for an order still to be paid at the door, the amount it will collect: "
                    + "the same 'captured late, reconciled before the receipt is final' shape ADR 0038 "
                    + "gave marking codes. The weight is the whole line's, all its units together. "
                    + "Appends an order revision (source CATCHWEIGHT) carrying the signed delta. An order "
                    + "whose payment already runs through a provider is refused when the weight moves its "
                    + "total (PAYMENT_ALREADY_TAKEN) -- an increase or a refund is a provider operation "
                    + "this build does not perform. Until every weighed line of an order is reconciled "
                    + "the order cannot leave the pass (CATCHWEIGHT_NOT_RECONCILED). Capability "
                    + "order.advance: the same hands that hand the food over.")
    public ResponseEntity<ActualWeightResponse> captureActualWeight(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody ActualWeightRequest body,
            HttpServletRequest request) {
        // The path names a location the caller's grant was checked against; an order of another
        // branch must not be reachable through it just because its id was guessed.
        boolean atLocation = orderQuery
                .detail(tenantId, orderId)
                .filter(found -> found.order().locationId().equals(locationId))
                .isPresent();
        if (!atLocation) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            var result = reconciliation.reconcile(
                    tenantId,
                    orderId,
                    lineId,
                    body.actualWeightGrams(),
                    (int) expected,
                    "USER",
                    currentActor.get().subject(),
                    null);
            return ResponseEntity.ok()
                    .eTag(AggregateVersion.toETag(result.orderVersion()))
                    .body(new ActualWeightResponse(
                            orderId,
                            lineId,
                            result.changed(),
                            result.actualWeightGrams(),
                            result.lineFinalAmountMinor(),
                            result.totalMinor(),
                            result.deltaTotalMinor(),
                            result.revision(),
                            result.orderVersion()));
        } catch (OrderStateService.StaleOrderException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderStateService.OrderNotFoundException
                | CatchweightReconciliationService.LineNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (CatchweightReconciliationService.RefusedException refused) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, refused.getMessage(), Map.of("reason", refused.code()));
        }
    }

    /** @param actualWeightGrams the weighed total of the whole line -- all its units together */
    public record ActualWeightRequest(
            @NotNull @Positive @Max(10_000_000) Integer actualWeightGrams) {}

    /**
     * What capturing a weight did.
     *
     * @param changed         false when the weight was the one already on the line and nothing was written
     * @param deltaTotalMinor the order total after minus before, signed
     */
    public record ActualWeightResponse(
            UUID orderId,
            UUID lineId,
            boolean changed,
            int actualWeightGrams,
            long lineFinalAmountMinor,
            long totalMinor,
            long deltaTotalMinor,
            int revision,
            int orderVersion) {}
}
