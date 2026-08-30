package uz.horecaos.platform.dinein.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.dinein.domain.DineInStateMachine;
import uz.horecaos.platform.dinein.domain.ReservationStatus;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.AvailabilityRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.ReservationRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SettingsRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Bookings (ADR 0047).
 *
 * <p>A reservation is its own aggregate and holds nothing but a table. It reserves
 * no stock and takes no pricing quote at any point: booking a table on Friday
 * reserves no food, and wiring a booking into checkout would hold ADR 0017 stock
 * for a party that has not ordered.
 *
 * <p>The one guarantee this class exists to keep is not in this class. Two hosts
 * confirming table 12 for 20:00 in the same second is Friday, not a rare race, and
 * read-then-write cannot exclude it; V0034's exclusion constraint can, and does.
 * What this class does is order its statements so the constraint sees the right
 * interval, and translate the violation into a conflict a host stand can render
 * rather than a leaked SQLSTATE.
 *
 * <p>The guest's name, phone and note are ADR 0029 personal data, encrypted before
 * they reach the store and never logged, evented, or put in an audit change
 * document. A booking for a guest with no HorecaOS account creates no customer record
 * and no consent, per ADR 0015, which is why the name lives on the booking.
 */
@Service
public class ReservationService {

    /** ADR 0029 lookup domain, so a phone hashes the same way it does elsewhere. */
    private static final String PHONE_LOOKUP_DOMAIN = "phone";

    private final JdbcDineInStore store;
    private final FloorPlanService floorPlan;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final Clock clock;

    public ReservationService(
            JdbcDineInStore store,
            FloorPlanService floorPlan,
            FieldProtection protection,
            AuditRecorder audit,
            Clock clock) {
        this.store = store;
        this.floorPlan = floorPlan;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param tableIds every table the party is booked onto. A booking for four
     *                 tables that can hold three holds none: the whole set is one
     *                 transaction, so the constraint that refuses the fourth rolls
     *                 back the first three
     */
    public record NewReservation(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID customerAccountId,
            String guestName,
            String guestPhone,
            String secondaryPhone,
            String note,
            int partySize,
            Instant requestedFrom,
            Instant requestedTo,
            List<UUID> tableIds,
            UUID sourceChannelId,
            String createdBy) {}

    @Transactional
    public ReservationRow request(NewReservation request) {
        if (request.tableIds() == null || request.tableIds().isEmpty()) {
            // A booking with no table is a note in a diary. It cannot be held,
            // cannot be excluded against, and cannot answer "which table".
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A booking names at least one table");
        }
        if (!request.requestedTo().isAfter(request.requestedFrom())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A booking's end is after its start");
        }

        SettingsRow settings = floorPlan.settings(request.tenantId(), request.brandId(), request.locationId());

        UUID reservationId = UUID.randomUUID();
        Instant now = clock.instant();

        ReservationRow reservation = new ReservationRow(
                reservationId,
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                request.customerAccountId(),
                protect(
                        request.tenantId(),
                        reservationId,
                        "guest_name_encrypted",
                        require(request.guestName(), "A booking needs a name to call at the door")),
                protect(
                        request.tenantId(),
                        reservationId,
                        "guest_phone_encrypted",
                        require(request.guestPhone(), "A booking needs a number to ring")),
                protection.lookupHash(request.tenantId(), PHONE_LOOKUP_DOMAIN, normalizePhone(request.guestPhone())),
                protectNullable(
                        request.tenantId(), reservationId, "secondary_phone_encrypted", request.secondaryPhone()),
                protectNullable(request.tenantId(), reservationId, "note_encrypted", request.note()),
                request.partySize(),
                request.requestedFrom(),
                request.requestedTo(),
                settings.turnaroundMinutes(),
                ReservationStatus.REQUESTED,
                request.sourceChannelId(),
                request.createdBy(),
                1);

        store.insertReservation(reservation, now);

        Instant heldFrom = request.requestedFrom().minus(Duration.ofMinutes(settings.turnaroundMinutes()));
        Instant heldTo = request.requestedTo().plus(Duration.ofMinutes(settings.turnaroundMinutes()));

        for (UUID tableId : request.tableIds()) {
            TableRow table = store.findTable(request.tenantId(), tableId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such table"));
            if (!table.locationId().equals(request.locationId())) {
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST, "Table %s is at another branch".formatted(table.code()));
            }
            if (!"ACTIVE".equals(table.status())) {
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "Table %s is %s and cannot be booked".formatted(table.code(), table.status()));
            }
            store.insertReservationTable(
                    reservationId, tableId, request.tenantId(), request.locationId(), heldFrom, heldTo);
        }

        // No guest name, phone or note in the change document. ADR 0029 keeps
        // personal data inside the envelope, and an audit record is read by more
        // people than the booking is.
        audit.record(AuditFact.of("dinein.reservation.requested", AuditClass.BUSINESS)
                .by(ActorRef.user(request.createdBy(), null))
                .at(ResourceScope.location(request.tenantId(), request.brandId(), request.locationId()))
                .target("dinein.reservation", reservationId)
                .targetVersion(1L)
                .because("Booking taken")
                .changed(Map.of(
                        "partySize", request.partySize(),
                        "tables", request.tableIds().size(),
                        "requestedFrom", request.requestedFrom().toString(),
                        "requestedTo", request.requestedTo().toString()))
                .usingCapability("reservation.manage")
                .correlatedBy(reservationId.toString())
                .occurredAt(now)
                .build());

        return reservation;
    }

    /**
     * Moves a booking, and re-snapshots its hold on the way into
     * {@link ReservationStatus#CONFIRMED}.
     *
     * <p>The order of the two statements is the point. The effective hold is
     * written first and the status second, because it is the status change that
     * V0034's trigger propagates and the exclusion constraint checks. Confirming
     * therefore never tests an interval built from a turnaround buffer that has
     * since been edited — and editing that buffer moves no interval that has
     * already been taken.
     */
    @Transactional
    public ReservationRow move(
            UUID tenantId,
            UUID reservationId,
            ReservationStatus to,
            int expectedVersion,
            String actorSubject,
            String reason) {

        ReservationRow reservation = store.findReservation(tenantId, reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such booking"));

        if (!DineInStateMachine.permits(reservation.status(), to)) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "A %s booking cannot become %s. Permitted: %s"
                            .formatted(reservation.status(), to, DineInStateMachine.nextFor(reservation.status())),
                    Map.of("currentStatus", reservation.status().name(), "requestedStatus", to.name()));
        }

        Instant now = clock.instant();
        int turnaround = reservation.turnaroundMinutes();

        if (to == ReservationStatus.CONFIRMED) {
            SettingsRow settings = floorPlan.settings(tenantId, reservation.brandId(), reservation.locationId());
            turnaround = settings.turnaroundMinutes();
            store.rewriteHolds(
                    tenantId,
                    reservationId,
                    reservation.requestedFrom().minus(Duration.ofMinutes(turnaround)),
                    reservation.requestedTo().plus(Duration.ofMinutes(turnaround)));
        }

        boolean moved;
        try {
            moved = store.moveReservation(tenantId, reservationId, reservation.status(), to, expectedVersion, now);
        } catch (DataIntegrityViolationException conflict) {
            if (isDoubleBooking(conflict)) {
                // The loser of a Friday-night race. A stable conflict code and a
                // sentence a host can act on, never a leaked constraint violation:
                // the person reading this is standing at a door with a guest in
                // front of them.
                throw new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "One of these tables is already booked for an overlapping time. "
                                + "Re-read availability and choose another table or another time.",
                        Map.of("conflict", "TABLE_ALREADY_BOOKED"));
            }
            throw conflict;
        }

        if (!moved) {
            throw ApiException.staleVersion(expectedVersion, reservation.version());
        }

        audit.record(AuditFact.of("dinein.reservation." + to.name().toLowerCase(), AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, reservation.brandId(), reservation.locationId()))
                .target("dinein.reservation", reservationId)
                .targetVersion((long) expectedVersion + 1)
                .because(reason)
                .changed(Map.of(
                        "from", reservation.status().name(),
                        "to", to.name(),
                        "turnaroundMinutes", turnaround))
                .usingCapability("reservation.manage")
                .correlatedBy(reservationId.toString())
                .occurredAt(now)
                .build());

        return store.findReservation(tenantId, reservationId).orElseThrow();
    }

    /**
     * Which tables are free, and which are merely occupied.
     *
     * <p>Advisory, and honestly so. Two hosts reading this in the same second both
     * see a free table; the constraint decides between them. A booking screen that
     * treated this answer as a reservation would be exactly the check-on-read
     * ADR 0047 rejects.
     */
    public List<AvailabilityRow> availability(UUID tenantId, UUID locationId, Instant from, Instant to) {

        if (!to.isAfter(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An availability window's end is after its start");
        }
        return store.tableAvailability(tenantId, locationId, from, to);
    }

    public ReservationRow find(UUID tenantId, UUID reservationId) {
        return store.findReservation(tenantId, reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such booking"));
    }

    public List<UUID> tablesFor(UUID tenantId, UUID reservationId) {
        return store.tablesForReservation(tenantId, reservationId);
    }

    /**
     * Whether a failure is the double-booking exclusion rather than some other
     * integrity problem.
     *
     * <p>Matched on the constraint name, which V0034 gives it explicitly for this
     * reason. Matching on the exception type alone would turn a foreign-key
     * violation — a table at another branch, say — into "already booked", and the
     * host would spend the evening trying other times for a table that does not
     * exist.
     */
    static boolean isDoubleBooking(DataIntegrityViolationException conflict) {
        Throwable cursor = conflict;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains(JdbcDineInStore.DOUBLE_BOOKING_CONSTRAINT)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String protect(UUID tenantId, UUID reservationId, String column, String plaintext) {
        return protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef("dinein.reservations", column, reservationId),
                        plaintext)
                .serialize();
    }

    private String protectNullable(UUID tenantId, UUID reservationId, String column, String plaintext) {
        return plaintext == null || plaintext.isBlank() ? null : protect(tenantId, reservationId, column, plaintext);
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
        return value;
    }

    /** Digits only, so the same number typed two ways hashes to one value. */
    private static String normalizePhone(String raw) {
        return raw.replaceAll("[^0-9]", "");
    }
}
