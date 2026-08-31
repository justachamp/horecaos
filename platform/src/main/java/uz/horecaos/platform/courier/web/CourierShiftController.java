package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.courier.api.CourierSelfAuthorized;
import uz.horecaos.platform.courier.application.CourierCashService;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The courier's own shift (ADR 0042).
 *
 * <p>Every endpoint here resolves the courier from the caller's own token
 * subject and never from a path variable. That is what makes "the courier alone
 * opens a shift" true rather than merely intended: there is no parameter through
 * which a holder of {@code courier.shift.open} could name somebody else, so the
 * capability cannot be turned into the power to create paid hours for a person
 * who was at home.
 */
@RestController
@RequestMapping("/api/v1/courier/tenants/{tenantId}/brands/{brandId}/locations/{locationId}")
@Tag(name = "Courier shift", description = "A courier's own shift, breaks, and cash declaration")
public class CourierShiftController {

    private final CourierShiftService shifts;
    private final CourierCashService cash;
    private final JdbcCourierStore couriers;
    private final CurrentActor currentActor;

    public CourierShiftController(
            CourierShiftService shifts, CourierCashService cash, JdbcCourierStore couriers, CurrentActor currentActor) {
        this.shifts = shifts;
        this.cash = cash;
        this.couriers = couriers;
        this.currentActor = currentActor;
    }

    @PostMapping("/shifts")
    @CourierSelfAuthorized(Capability.COURIER_SHIFT_OPEN)
    @Idempotent
    @Operation(
            summary = "Open my shift",
            description = "Refused while the engagement is not ACTIVE. A lapsed registration "
                    + "cannot open a shift; work already accepted still finishes and everything "
                    + "already accrued is still owed.")
    public ResponseEntity<ShiftResponse> open(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody OpenShiftRequest body) {

        UUID courierId = me(tenantId);
        ShiftRow shift = shifts.open(new CourierShiftService.OpenShift(
                tenantId,
                brandId,
                locationId,
                courierId,
                ShiftActor.COURIER,
                actor(),
                "Courier opened their own shift",
                body.point(),
                body.currency()));

        return ResponseEntity.ok(ShiftResponse.of(shift));
    }

    @PostMapping("/shifts/{shiftId}/close")
    @CourierSelfAuthorized(Capability.COURIER_SHIFT_OPEN)
    @Idempotent
    @Operation(summary = "Close my shift", description = "Ends any open break first, so its seconds are not paid.")
    public ResponseEntity<CloseResponse> close(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shiftId,
            @Valid @RequestBody CloseShiftRequest body) {

        requireOwnShift(tenantId, shiftId);
        CourierShiftService.CloseOutcome outcome = shifts.close(new CourierShiftService.CloseShift(
                tenantId,
                shiftId,
                ShiftActor.COURIER,
                actor(),
                null,
                "Courier closed their own shift",
                body.point(),
                body.currency()));

        return ResponseEntity.ok(new CloseResponse(
                outcome.status().name(), outcome.paidSeconds(), outcome.breakSeconds(), outcome.cashHandoverId()));
    }

    @PostMapping("/shifts/{shiftId}/breaks")
    @CourierSelfAuthorized(Capability.COURIER_SHIFT_BREAK)
    @Idempotent
    @Operation(
            summary = "Start my break",
            description = "ADR 0045 stops collecting telemetry for the duration, so a courier on "
                    + "break is not tracked at all. A dispatcher loses the pin, which is correct: "
                    + "a courier on break is not assignable, so the pin had no operational use.")
    public ResponseEntity<Void> startBreak(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shiftId) {

        requireOwnShift(tenantId, shiftId);
        shifts.startBreak(tenantId, shiftId, ShiftActor.COURIER, actor(), "Courier started their own break");
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/shifts/{shiftId}/breaks/end")
    @CourierSelfAuthorized(Capability.COURIER_SHIFT_BREAK)
    @Idempotent
    @Operation(summary = "End my break")
    public ResponseEntity<Void> endBreak(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shiftId) {

        requireOwnShift(tenantId, shiftId);
        shifts.endBreak(tenantId, shiftId, ShiftActor.COURIER, actor(), "Courier ended their own break");
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/cash-handovers/{handoverId}/declaration")
    @CourierSelfAuthorized(Capability.COURIER_SHIFT_OPEN)
    @Idempotent
    @Operation(
            summary = "Declare the cash I am handing over",
            description = "The courier's statement about the bag. A branch cashier confirms what "
                    + "was received separately, and each gap becomes its own variance entry.")
    public ResponseEntity<Void> declareCash(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID handoverId,
            @Valid @RequestBody CashDeclarationRequest body) {

        cash.declare(tenantId, handoverId, me(tenantId), body.declaredMinor(), actor());
        return ResponseEntity.accepted().build();
    }

    private UUID me(UUID tenantId) {
        return couriers.findCourierBySubject(tenantId, currentActor.get().subject())
                .map(JdbcCourierStore.CourierRow::id)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The caller is not a courier of this tenant"));
    }

    /** A courier acts on their own shift and no other, whatever id they send. */
    private void requireOwnShift(UUID tenantId, UUID shiftId) {
        UUID courierId = me(tenantId);
        boolean mine = shifts.liveShiftOf(tenantId, courierId)
                .map(shift -> shift.id().equals(shiftId))
                .orElse(false);
        if (!mine) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such live shift of yours");
        }
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    /**
     * The open-shift payload.
     *
     * @param point "latitude,longitude" from the handset, stored encrypted;
     *              absent when the handset sent none
     */
    record OpenShiftRequest(
            @Size(max = 64) @Nullable String point,
            @Size(min = 3, max = 3) String currency) {}

    record CloseShiftRequest(
            @Size(max = 64) @Nullable String point,
            @Size(min = 3, max = 3) String currency) {}

    record CashDeclarationRequest(long declaredMinor) {}

    record ShiftResponse(UUID shiftId, String status, String dutyState, String enforcementMode, int version) {

        static ShiftResponse of(ShiftRow shift) {
            return new ShiftResponse(
                    shift.id(),
                    shift.status().name(),
                    shift.dutyState().name(),
                    shift.enforcementMode().name(),
                    shift.version());
        }
    }

    record CloseResponse(String status, long paidSeconds, long breakSeconds, @Nullable UUID cashHandoverId) {}

    /** Never a list of other couriers. Kept here so the shape is obvious. */
    record MyShifts(List<ShiftResponse> shifts) {}
}
