package uz.horecaos.platform.telemetry.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService.CoarseCourier;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService.CourierPin;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService.FleetView;
import uz.horecaos.platform.telemetry.application.CourierTrackRevealService;
import uz.horecaos.platform.telemetry.application.CourierTrackRevealService.Reveal;
import uz.horecaos.platform.telemetry.application.CourierTrackRevealService.RevealCommand;
import uz.horecaos.platform.telemetry.application.CourierTrackRevealService.RevealedWindow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The dispatcher's map, and the audited reveal of a stored track (ADR 0045).
 *
 * <p>Two endpoints with deliberately different weights, and putting them in one
 * file is the point: a reader should see the difference rather than have to find
 * it.
 *
 * <p>The map is an ordinary capability-gated read at a location scope, refreshed
 * every few seconds all shift, and it is <strong>not audited per refresh</strong>.
 * Auditing it would produce more audit rows than the tenant has orders and would
 * bury the record below.
 *
 * <p>The reveal opens one named self-employed courier's movement history for a
 * bounded window. It requires a capability that is in no default role bundle and
 * not in {@code platform.admin}, it requires a stated purpose, and it always
 * writes an ADR 0027 audit entry naming the actor, the courier, the window, and
 * the reason.
 *
 * <p><strong>Why the reveal is a POST.</strong> It reads, so a {@code GET} looks
 * right, and it is wrong twice. The purpose is a sentence, and a sentence in a
 * query string is written to every access log, every reverse proxy, and every
 * {@code Referer} the page emits — the same argument ADR 0047's dine-in
 * controller makes for keeping a printed token out of a path. And the call
 * creates something: an audit record. A request that leaves evidence behind it is
 * a request ADR 0031's idempotency conventions should cover.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/operations/couriers")
@Tag(name = "Courier positions", description = "The dispatcher's live map, and the audited track reveal")
public class OperationsCourierPositionController {

    private final CourierPositionQueryService positions;
    private final CourierTrackRevealService reveals;
    private final CurrentActor currentActor;
    private final Clock clock;

    public OperationsCourierPositionController(
            CourierPositionQueryService positions,
            CourierTrackRevealService reveals,
            CurrentActor currentActor,
            Clock clock) {
        this.positions = positions;
        this.reveals = reveals;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping("/positions")
    @RequiresCapability(value = Capability.COURIER_POSITION_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Where this branch's on-duty couriers are now",
            description = "Only couriers with an open duty session appear, because the table this "
                    + "reads is a working set rather than a history. A fix worse than 100 metres "
                    + "or older than ten minutes is returned without a coordinate: the courier is "
                    + "on duty and cannot honestly be drawn, and an accuracy circle rendered as a "
                    + "pin sends somebody to the wrong street. Not audited per refresh — opening a "
                    + "stored track is.")
    public ResponseEntity<FleetResponse> fleet(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        FleetView fleet = positions.fleetAt(tenantId, locationId, clock.instant());
        return ResponseEntity.ok(new FleetResponse(fleet.pins(), fleet.withoutPin()));
    }

    @PostMapping("/{courierId}/track-reveals")
    @RequiresCapability(value = Capability.COURIER_TRACK_REVEAL, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Open one courier's stored track for a stated purpose",
            description = "Requires courier.track.reveal, which is in no default role bundle and "
                    + "which platform.admin does not imply: somebody granted it to a named person "
                    + "on purpose. The purpose is mandatory and is recorded with the actor, the "
                    + "courier, and the window in an ADR 0027 audit entry written in the same "
                    + "transaction as the decryption. The window is bounded, so \"this courier's "
                    + "track\" is not a request anybody can make, and nothing survives past the "
                    + "retention floor to be revealed.")
    public ResponseEntity<RevealResponse> reveal(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID courierId,
            @Valid @RequestBody RevealRequest body) {

        Reveal reveal = reveals.reveal(new RevealCommand(
                tenantId,
                brandId,
                locationId,
                courierId,
                body.from(),
                body.to(),
                body.purpose(),
                ActorRef.user(currentActor.get().subject(), null),
                MDC.get("correlationId") == null ? UUID.randomUUID().toString() : MDC.get("correlationId")));

        return ResponseEntity.ok(
                new RevealResponse(reveal.courierId(), reveal.from(), reveal.to(), reveal.purpose(), reveal.windows()));
    }

    /**
     * @param purpose why somebody is looking, in a sentence they can be held to.
     *                Not an enum: the reasons a track is opened are an open set —
     *                a customer says it never arrived, a courier disputes a
     *                distance, a scooter was stolen — and a dropdown of four
     *                options would be filled in truthfully once and then always
     *                left on the first entry.
     */
    public record RevealRequest(
            @NotNull Instant from,
            @NotNull Instant to,
            @NotBlank @Size(min = 12, max = 500) String purpose) {}

    public record FleetResponse(List<CourierPin> pins, List<CoarseCourier> withoutPin) {}

    public record RevealResponse(
            UUID courierId,

            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,

            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,

            String purpose,
            List<RevealedWindow> windows) {}
}
