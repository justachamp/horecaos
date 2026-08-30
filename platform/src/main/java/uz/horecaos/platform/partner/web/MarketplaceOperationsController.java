package uz.horecaos.platform.partner.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.partner.application.HandoverVerificationService;
import uz.horecaos.platform.partner.application.MarketplaceLivenessService;
import uz.horecaos.platform.partner.domain.ExternalReference;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What staff do with marketplace orders (ADR 0040).
 *
 * <p>Three things live here and each answers a question the operations console
 * could not answer before.
 *
 * <p><strong>Reference search.</strong> A customer or a courier quotes the
 * aggregator's number, not HorecaOS's, and reads it back with whatever separators
 * they see. Search matches the normalised form across the tenant and may return
 * several rows — two aggregators do issue the same short numeric code on one
 * day — which the operator disambiguates by provider and branch.
 *
 * <p><strong>Handover.</strong> Verification and its override, on one table, for
 * every kind of handover. The two capabilities differ because the acts differ in
 * frequency and consequence.
 *
 * <p><strong>Liveness.</strong> Which branch has heard from which aggregator and
 * how long ago. A dead integration produces no errors, so this is the only place
 * it becomes visible before a manager notices a quiet week.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/marketplace")
@Tag(name = "Marketplace operations", description = "Reference search, handover, and liveness")
public class MarketplaceOperationsController {

    private static final int SEARCH_LIMIT = 20;

    private final JdbcPartnerStore store;
    private final HandoverVerificationService handovers;
    private final MarketplaceLivenessService liveness;
    private final CurrentActor currentActor;

    public MarketplaceOperationsController(
            JdbcPartnerStore store,
            HandoverVerificationService handovers,
            MarketplaceLivenessService liveness,
            CurrentActor currentActor) {
        this.store = store;
        this.handovers = handovers;
        this.liveness = liveness;
        this.currentActor = currentActor;
    }

    @GetMapping("/order-references")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Find an order by the number a partner quotes",
            description = "Matches a hyphenated, spaced, lowercase or #-prefixed rendering of the "
                    + "same code. May return several rows across providers.")
    public ResponseEntity<List<ReferenceMatchResponse>> search(
            @PathVariable UUID tenantId, @RequestParam String reference) {

        List<JdbcPartnerStore.ReferenceMatch> matches =
                store.searchByReference(tenantId, ExternalReference.normalise(reference), SEARCH_LIMIT);

        return ResponseEntity.ok(
                matches.stream().map(ReferenceMatchResponse::of).toList());
    }

    @PostMapping("/orders/{orderId}/handover-verifications")
    @RequiresCapability(value = Capability.ORDER_ADVANCE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Verify a handover code",
            description = "Consumes one attempt whether or not the code matches. The response says "
                    + "how many attempts remain and nothing about the expected value.")
    public ResponseEntity<VerificationResponse> verify(
            @PathVariable UUID tenantId, @PathVariable UUID orderId, @Valid @RequestBody VerificationRequest body) {

        // Declared against ORDER_ADVANCE rather than ADR 0040's stated
        // kitchen.handover.complete, which ADR 0041 owns and has not registered.
        // Advancing an order along the kitchen path is the closest existing
        // grant and is held by the same people; this moves to the narrower code
        // the moment ADR 0041 declares it, and the ADR records that.
        HandoverVerificationService.Verification result = handovers.verify(
                tenantId, orderId, body.code(), currentActor.get().subject());

        return ResponseEntity.ok(
                new VerificationResponse(result.verified(), result.status().name(), result.attemptsRemaining()));
    }

    @PostMapping("/orders/{orderId}/handover-bypasses")
    @RequiresCapability(value = Capability.MARKETPLACE_HANDOVER_BYPASS, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Override handover verification",
            description = "Requires a reason code and writes an ADR 0027 audit fact naming the "
                    + "supervisor. Available after attempts are exhausted as well as before.")
    public ResponseEntity<Void> bypass(
            @PathVariable UUID tenantId, @PathVariable UUID orderId, @Valid @RequestBody BypassRequest body) {

        handovers.bypass(
                tenantId,
                ResourceScope.tenant(tenantId),
                orderId,
                body.reasonCode(),
                currentActor.get().subject(),
                body.supervisorName(),
                null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/liveness")
    @RequiresCapability(value = Capability.MARKETPLACE_LIVENESS_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The locations by bindings liveness matrix",
            description = "silenceSeconds is null where nothing has ever arrived, which is an "
                    + "unfinished configuration rather than a channel that stopped.")
    public ResponseEntity<List<LivenessResponse>> liveness(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                liveness.matrix(tenantId).stream().map(LivenessResponse::of).toList());
    }

    public record VerificationRequest(
            @NotBlank @Size(max = 64) String code) {}

    public record BypassRequest(
            @NotBlank @Size(max = 48) String reasonCode,
            @NotBlank @Size(max = 255) String supervisorName) {}

    public record VerificationResponse(boolean verified, String status, int attemptsRemaining) {}

    public record ReferenceMatchResponse(
            UUID orderId,
            String publicOrderNumber,
            String orderStatus,
            String referenceType,
            String referenceValue,
            UUID bindingId,
            UUID locationId) {

        static ReferenceMatchResponse of(JdbcPartnerStore.ReferenceMatch match) {
            return new ReferenceMatchResponse(
                    match.orderId(),
                    match.publicOrderNumber(),
                    match.orderStatus(),
                    match.referenceType(),
                    match.referenceValue(),
                    match.bindingId(),
                    match.locationId());
        }
    }

    public record LivenessResponse(
            UUID bindingId,
            UUID locationId,
            String providerName,
            String direction,
            Instant lastSuccessAt,
            String lastSuccessReference,
            Instant lastFailureAt,
            String lastFailureCode,
            int staleAfterSeconds,
            Integer observedMedianIntervalSeconds,
            String alertState,
            Long silenceSeconds) {

        static LivenessResponse of(JdbcPartnerStore.LivenessRow row) {
            return new LivenessResponse(
                    row.bindingId(),
                    row.locationId(),
                    row.providerName(),
                    row.direction(),
                    row.lastSuccessAt(),
                    row.lastSuccessReference(),
                    row.lastFailureAt(),
                    row.lastFailureCode(),
                    row.staleAfterSeconds(),
                    row.observedMedianIntervalSeconds(),
                    row.alertState(),
                    row.silenceSeconds());
        }
    }
}
