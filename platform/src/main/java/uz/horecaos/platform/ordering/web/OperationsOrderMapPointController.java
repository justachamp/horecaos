package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OrderMapPointService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Today's delivery orders of a branch as map points (row {@code 7.10a}, ADR 0145 decision 8).
 *
 * <p>A {@code POST}, never a {@code GET}, and mutating: it writes an ADR 0027 audit fact in the
 * same transaction as the decryption it performs, the reason {@code
 * OperationsCourierPositionController#revealTrack} is shaped the same way -- a cacheable,
 * prefetchable, replayable read is the wrong verb for an act that must be answerable one by one.
 * It takes an {@code Idempotency-Key} like every other mutation on this surface (ADR 0031).
 *
 * <p>Its own controller rather than a method of {@code OperationsOrderController}: the reveal is
 * a different power from reading the queue (it needs {@link Capability#ORDER_POINTS_REVEAL}, held
 * by the dispatcher and the branch manager and by nothing wider), and a class with one
 * capability is a class whose authorization can be read at a glance.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders")
@Tag(name = "Operations orders", description = "The branch's order queue, approvals, and timeline")
public class OperationsOrderMapPointController {

    private final OrderMapPointService mapPoints;
    private final CurrentActor currentActor;

    public OperationsOrderMapPointController(OrderMapPointService mapPoints, CurrentActor currentActor) {
        this.mapPoints = mapPoints;
        this.currentActor = currentActor;
    }

    @PostMapping("/map-point-reveals")
    @RequiresCapability(value = Capability.ORDER_POINTS_REVEAL, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Open today's delivery orders of this branch as map points (7.10a, 3.1)",
            description = "ADR 0145: a dispatcher-scope read with an ADR 0027 audited purpose, never a "
                    + "reporting fact -- a point is a doorstep, and a doorstep lives only inside the "
                    + "order's encrypted snapshot (ADR 0029). Opens the delivery orders of the tenant's "
                    + "current business day (ADR 0043) at this branch, newest first, at most 500. One "
                    + "audit fact for the whole call, written before any decryption, naming who, why "
                    + "and how many -- and not where. The answer carries an order number, a status, a "
                    + "time and a point, and nothing that identifies a person: no name, no phone, no "
                    + "address text. `withoutPoint` counts delivery orders with no point to show so a "
                    + "map never silently under-reports; `truncated` says the day held more than the "
                    + "cap. A purpose is required.")
    public ResponseEntity<MapPointRevealResponse> reveal(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody MapPointRevealRequest body) {

        OrderMapPointService.MapPoints revealed = mapPoints.reveal(
                tenantId,
                brandId,
                locationId,
                body.purpose().strip(),
                currentActor.get().subject());
        return ResponseEntity.ok(MapPointRevealResponse.of(revealed));
    }

    /** @param purpose why the day's doorsteps are being opened; recorded, never shown on the map */
    public record MapPointRevealRequest(
            @NotBlank @Size(max = 200) String purpose) {}

    public record MapPointResponse(
            UUID orderId,
            String publicOrderNumber,
            String status,
            Instant createdAt,
            double latitude,
            double longitude) {}

    public record MapPointRevealResponse(
            Instant windowFrom, Instant windowTo, List<MapPointResponse> points, int withoutPoint, boolean truncated) {

        static MapPointRevealResponse of(OrderMapPointService.MapPoints revealed) {
            return new MapPointRevealResponse(
                    revealed.window().from(),
                    revealed.window().to(),
                    revealed.points().stream()
                            .map(point -> new MapPointResponse(
                                    point.orderId(),
                                    point.publicOrderNumber(),
                                    point.status().name(),
                                    point.createdAt(),
                                    point.latitude(),
                                    point.longitude()))
                            .toList(),
                    revealed.withoutPoint(),
                    revealed.truncated());
        }
    }
}
