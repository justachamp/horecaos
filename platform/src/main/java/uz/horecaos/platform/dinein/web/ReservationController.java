package uz.horecaos.platform.dinein.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
import uz.horecaos.platform.dinein.application.ReservationService;
import uz.horecaos.platform.dinein.domain.ReservationStatus;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.AvailabilityRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.ReservationRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The host stand (ADR 0047).
 *
 * <p>The first thing ADR 0047 says to ship, and deliberately so: a host stand that
 * only books tables is useful on its own and exercises the exclusion constraint
 * under real Friday load before any money depends on it.
 *
 * <p>No response here carries a guest's name, phone number, or note. Those are
 * ADR 0029 personal data, encrypted at rest and revealed only through the customer
 * module's audited reveal path with a stated purpose. A booking list that rendered
 * two hundred phone numbers to build a screen would be exactly the bulk exposure
 * that control exists to prevent.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}")
@Tag(name = "Reservations", description = "Table availability and the booking lifecycle")
public class ReservationController {

    private final ReservationService reservations;
    private final CurrentActor currentActor;

    public ReservationController(ReservationService reservations, CurrentActor currentActor) {
        this.reservations = reservations;
        this.currentActor = currentActor;
    }

    @GetMapping("/table-availability")
    @RequiresCapability(value = Capability.RESERVATION_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Which tables are free for a window, and which are occupied now",
            description = "Advisory, and honestly so. Two hosts reading this in the same second "
                    + "both see a free table; the database decides between them when they "
                    + "confirm. A screen that treated this as a hold would be the check-on-read "
                    + "ADR 0047 rejects.")
    public ResponseEntity<List<AvailabilityResponse>> availability(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam Instant from,
            @RequestParam Instant to) {

        return ResponseEntity.ok(reservations.availability(tenantId, locationId, from, to).stream()
                .map(AvailabilityResponse::of)
                .toList());
    }

    @GetMapping("/reservations")
    @RequiresCapability(value = Capability.RESERVATION_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "A branch's bookings for a window — the day plan",
            description = "Every status in range, not only the ones holding a table: a host "
                    + "reading tonight's plan needs the cancellation next to the confirmed "
                    + "booking it replaced. No guest name, phone or note, the same restraint "
                    + "the single-booking read below keeps.")
    public ResponseEntity<List<ReservationResponse>> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam Instant from,
            @RequestParam Instant to) {

        return ResponseEntity.ok(reservations.listForDay(tenantId, locationId, from, to).stream()
                .map(row -> ReservationResponse.of(row, reservations.tablesFor(tenantId, row.id())))
                .toList());
    }

    @PostMapping("/reservations")
    @RequiresCapability(value = Capability.RESERVATION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Take a booking",
            description = "One transaction for the whole party: a booking for four tables that "
                    + "can hold three holds none. A booking for a guest with no account creates "
                    + "no customer record and no consent (ADR 0015); the name and number are "
                    + "encrypted onto the booking itself.")
    public ResponseEntity<ReservationResponse> request(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody ReservationRequest body) {

        ReservationRow saved = reservations.request(new ReservationService.NewReservation(
                tenantId,
                brandId,
                locationId,
                body.customerAccountId(),
                body.guestName(),
                body.guestPhone(),
                body.secondaryPhone(),
                body.note(),
                body.partySize(),
                body.requestedFrom(),
                body.requestedTo(),
                body.tableIds(),
                body.sourceChannelId(),
                currentActor.get().subject()));

        return ResponseEntity.ok(ReservationResponse.of(saved, reservations.tablesFor(tenantId, saved.id())));
    }

    @GetMapping("/reservations/{reservationId}")
    @RequiresCapability(value = Capability.RESERVATION_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "One booking, without the guest's details")
    public ResponseEntity<ReservationResponse> find(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID reservationId) {

        ReservationRow reservation = reservations.find(tenantId, reservationId);
        return ResponseEntity.ok(ReservationResponse.of(reservation, reservations.tablesFor(tenantId, reservationId)));
    }

    @PostMapping("/reservations/{reservationId}/state-actions")
    @RequiresCapability(value = Capability.RESERVATION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Confirm, reject, cancel, or mark a booking as a no-show",
            description = "Confirmation is where the hold is taken, and where a second host "
                    + "booking the same table for an overlapping time is refused by the database "
                    + "with a stable conflict code. Seating is not here: it opens a session, and "
                    + "lives on the session endpoint.")
    public ResponseEntity<ReservationResponse> stateAction(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID reservationId,
            @Valid @RequestBody StateActionRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        ReservationStatus target = parse(body.targetStatus());

        if (target == ReservationStatus.SEATED) {
            // Seating is an occupancy, not a booking edit. Allowing it here would
            // move the booking without opening the session that occupies the
            // table, and the table would then be booked, unoccupied, and unable to
            // take a walk-in.
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Seat a booking by opening a session against it, which is what occupies the " + "table");
        }

        ReservationRow moved = reservations.move(
                tenantId,
                reservationId,
                target,
                (int) expected,
                currentActor.get().subject(),
                body.reason());

        return ResponseEntity.ok(ReservationResponse.of(moved, reservations.tablesFor(tenantId, reservationId)));
    }

    @PostMapping("/reservations/{reservationId}/amendments")
    @RequiresCapability(value = Capability.RESERVATION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Change the party size, the time, or the tables of a booking not yet seated",
            description = "The guest's name, phone and note are not writable here — a host "
                    + "correcting a table or a time has no need to re-type a number, and a wrong "
                    + "one is a cancel-and-rebook. Refused once the booking is SEATED or terminal.")
    public ResponseEntity<ReservationResponse> amend(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID reservationId,
            @Valid @RequestBody AmendmentRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);

        ReservationRow amended = reservations.amend(
                tenantId,
                reservationId,
                body.partySize(),
                body.requestedFrom(),
                body.requestedTo(),
                body.tableIds(),
                (int) expected,
                currentActor.get().subject(),
                body.reason());

        return ResponseEntity.ok(ReservationResponse.of(amended, reservations.tablesFor(tenantId, reservationId)));
    }

    private static ReservationStatus parse(String value) {
        try {
            return ReservationStatus.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown booking status " + value);
        }
    }

    // -------------------------------------------------------------- contracts

    record ReservationRequest(
            UUID customerAccountId,
            @NotBlank @Size(max = 200) String guestName,
            @NotBlank @Size(max = 32) String guestPhone,
            @Size(max = 32) String secondaryPhone,
            @Size(max = 500) String note,
            @Min(1) @Max(200) int partySize,
            @NotNull Instant requestedFrom,
            @NotNull Instant requestedTo,
            @NotEmpty List<UUID> tableIds,
            @NotNull UUID sourceChannelId) {}

    /**
     * No name, no phone, no note. What a host stand renders is a time, a party
     * size, and which tables; the guest's details are revealed one booking at a
     * time through the audited ADR 0029 path when somebody actually needs them.
     */
    record ReservationResponse(
            UUID reservationId,
            int partySize,
            Instant requestedFrom,
            Instant requestedTo,
            int turnaroundMinutes,
            String status,
            List<UUID> tableIds,
            int version) {

        static ReservationResponse of(ReservationRow row, List<UUID> tableIds) {
            return new ReservationResponse(
                    row.id(),
                    row.partySize(),
                    row.requestedFrom(),
                    row.requestedTo(),
                    row.turnaroundMinutes(),
                    row.status().name(),
                    tableIds,
                    row.version());
        }
    }

    record AvailabilityResponse(
            UUID tableId, String code, int seats, UUID sectionId, boolean booked, boolean occupied) {

        static AvailabilityResponse of(AvailabilityRow row) {
            return new AvailabilityResponse(
                    row.tableId(), row.code(), row.seats(), row.sectionId(), row.booked(), row.occupied());
        }
    }

    record StateActionRequest(
            @NotBlank @Size(max = 16) String targetStatus,
            @NotBlank @Size(max = 500) String reason) {}

    record AmendmentRequest(
            @Min(1) @Max(200) int partySize,
            @NotNull Instant requestedFrom,
            @NotNull Instant requestedTo,
            @NotEmpty List<UUID> tableIds,
            @NotBlank @Size(max = 500) String reason) {}
}
