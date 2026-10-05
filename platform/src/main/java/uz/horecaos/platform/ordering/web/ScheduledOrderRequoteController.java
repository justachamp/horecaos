package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.ScheduledOrderRequoteService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A scheduled order's promotions, judged again (ADR 0140, ADR 0019, row 6.1).
 *
 * <p>The reads show what the checkpoint found; the one write is a re-check an operator asks for.
 * Neither changes the order: a re-quote is evidence, and a price change the customer would see
 * needs their agreement or a cancellation (ADR 0019), which are the order's own endpoints and
 * not this one's.
 */
@RestController
@RequestMapping(
        "/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/{orderId}/promotion-requotes")
@Tag(
        name = "Scheduled-order promotion re-quotes",
        description = "Whether a scheduled order's promotions still hold when its checkpoint arrives")
public class ScheduledOrderRequoteController {

    private final ScheduledOrderRequoteService requotes;
    private final CurrentActor currentActor;

    public ScheduledOrderRequoteController(ScheduledOrderRequoteService requotes, CurrentActor currentActor) {
        this.requotes = requotes;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "What the checkpoint found about this scheduled order's promotions, newest first",
            description = "At most twenty findings. Each says which promotions would give a different amount "
                    + "if the order were priced again at the checkpoint, and the totals either side. "
                    + "The order itself is unchanged by any of them.")
    public ResponseEntity<List<RequoteResponse>> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(requotes.findings(tenantId, brandId, locationId, orderId).stream()
                .map(RequoteResponse::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.ORDER_AMEND, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Re-check a scheduled order's promotions now",
            description = "Prices the order's live basket again at this moment, under everything else it was "
                    + "bought with, and records how the promotions and the total differ. Writes no change to "
                    + "the order. Refused for an order that is not scheduled, is priced by an aggregator, or "
                    + "has already gone to its kitchen.")
    public ResponseEntity<RequoteResponse> recheck(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {
        var finding = requotes.requoteNow(
                tenantId,
                brandId,
                locationId,
                orderId,
                ActorRef.user(currentActor.get().subject(), null));
        return ResponseEntity.status(HttpStatus.CREATED).body(RequoteResponse.of(finding));
    }

    /**
     * @param trigger {@code CHECKPOINT} (the sweep) or {@code OPERATOR} (a re-check someone asked for)
     * @param checkpointAt the instant the promotions were judged at
     * @param outcome {@code UNCHANGED}, {@code CHANGED} (the order keeps the price it was taken at) or
     *     {@code NOT_PRICEABLE} (it could not be priced at the checkpoint; {@code refusalCode} says why)
     * @param requoteTotalMinor null when the order could not be priced again
     * @param deltaTotalMinor what the customer would pay more (positive) or less (negative) if the
     *     order were priced again, or null when it could not be
     */
    public record RequoteResponse(
            UUID id,
            String trigger,
            Instant checkpointAt,
            String outcome,
            @Nullable String refusalCode,
            String currency,
            long heldTotalMinor,
            long heldDiscountMinor,
            @Nullable Long requoteTotalMinor,
            @Nullable Long requoteDiscountMinor,
            @Nullable Long deltaTotalMinor,
            List<PromotionChangeResponse> promotionChanges,
            Instant createdAt) {

        static RequoteResponse of(JdbcOrderRequoteStore.Finding finding) {
            Long requoteTotal = finding.requoteTotalMinor();
            return new RequoteResponse(
                    finding.id(),
                    finding.trigger(),
                    finding.checkpointAt(),
                    finding.outcome(),
                    finding.refusalCode(),
                    finding.currency(),
                    finding.heldTotalMinor(),
                    finding.heldDiscountMinor(),
                    requoteTotal,
                    finding.requoteDiscountMinor(),
                    requoteTotal == null ? null : requoteTotal - finding.heldTotalMinor(),
                    finding.promotionChanges().stream()
                            .map(change -> new PromotionChangeResponse(
                                    change.promotionId(), change.change(), change.heldMinor(), change.requoteMinor()))
                            .toList(),
                    finding.createdAt());
        }
    }

    /**
     * @param change {@code DROPPED}, {@code GAINED} or {@code CHANGED}
     * @param heldMinor the promotion's signed effect on the order the customer holds: a discount is negative
     * @param requoteMinor the same if the order were priced again at the checkpoint
     */
    public record PromotionChangeResponse(UUID promotionId, String change, long heldMinor, long requoteMinor) {}
}
