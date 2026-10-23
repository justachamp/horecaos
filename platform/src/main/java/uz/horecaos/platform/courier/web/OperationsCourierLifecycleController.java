package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.courier.application.CourierLifecycleService;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Correcting a courier, ending their engagement, archiving them (IA 3.3 «courier CRUD», ADR 0178).
 *
 * <p>Beside {@link OperationsCourierController} rather than inside it, which is already the longest
 * controller in the platform; the routes share its prefix and its capability, {@code
 * courier.engagement.manage}. A courier is an aggregate with a {@code version}: the roster reads it
 * and a correction sends it back as {@code expectedVersion} (ADR 0031).
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Courier lifecycle", description = "Correct, end the engagement of, and archive a courier")
public class OperationsCourierLifecycleController {

    private final CourierLifecycleService lifecycle;
    private final CurrentActor currentActor;

    public OperationsCourierLifecycleController(CourierLifecycleService lifecycle, CurrentActor currentActor) {
        this.lifecycle = lifecycle;
        this.currentActor = currentActor;
    }

    @PutMapping("/couriers/{courierId}")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            operationId = "updateCourier",
            summary = "Correct a courier's display reference or type",
            description = "expectedVersion is the version the roster last reported for this courier; a "
                    + "stale one is refused with STALE_VERSION. Name the display reference, the type, or "
                    + "both; a reference another courier of the tenant holds is a RESOURCE_CONFLICT with "
                    + "field displayReference. An archived courier is not edited. Audited with the "
                    + "reason.")
    public ResponseEntity<CourierCorrectedResponse> update(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @Valid @RequestBody UpdateCourierRequest body) {

        CourierRow updated = lifecycle.update(
                tenantId,
                courierId,
                body.expectedVersion(),
                blankToNull(body.displayReference()),
                body.courierTypeId(),
                actor(),
                body.reason());
        return ResponseEntity.ok(CourierCorrectedResponse.of(updated));
    }

    @PostMapping("/couriers/{courierId}/engagement-ending")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            operationId = "endCourierEngagement",
            summary = "End the courier's engagement",
            description = "The date they stopped working for the tenant. Refused with 422 "
                    + "UNPROCESSABLE_STATE and reason COURIER_HAS_OPEN_SHIFT or COURIER_HAS_ACTIVE_DELIVERIES "
                    + "while they stand on a shift or carry a delivery, and with RESOURCE_CONFLICT when "
                    + "there is no engagement to end. The courier stays on the roster until archived.")
    public ResponseEntity<Void> endEngagement(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @Valid @RequestBody CourierReasonRequest body) {

        lifecycle.endEngagement(tenantId, courierId, actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/couriers/{courierId}/archival")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            operationId = "archiveCourier",
            summary = "Archive a courier whose balance is zero",
            description = "Ends any engagement the courier still holds and archives them. Refused with "
                    + "422 UNPROCESSABLE_STATE and reason COURIER_BALANCE_NOT_ZERO, COURIER_HAS_OPEN_SHIFT "
                    + "or COURIER_HAS_ACTIVE_DELIVERIES. Archived, never deleted: the ledger, the shifts and "
                    + "the deliveries still name the courier.")
    public ResponseEntity<Void> archive(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @Valid @RequestBody CourierReasonRequest body) {

        lifecycle.archive(tenantId, courierId, actor(), body.reason());
        return ResponseEntity.accepted().build();
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    record UpdateCourierRequest(
            @NotNull Integer expectedVersion,
            @Nullable @Size(max = 32) String displayReference,
            @Nullable UUID courierTypeId,
            @NotBlank @Size(max = 500) String reason) {}

    record CourierReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    record CourierCorrectedResponse(
            UUID courierId, String displayReference, UUID courierTypeId, String status, int version) {

        static CourierCorrectedResponse of(CourierRow courier) {
            return new CourierCorrectedResponse(
                    courier.id(),
                    courier.displayReference(),
                    courier.courierTypeId(),
                    courier.status(),
                    courier.version());
        }
    }
}
