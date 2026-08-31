package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.CourierAccrual;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.DutyState;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.domain.RateCard;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.domain.ShiftEnforcement;
import uz.horecaos.platform.courier.domain.ShiftStatus;
import uz.horecaos.platform.courier.domain.ShiftTransition;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.PeriodRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.HandoverRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Opening, breaking, and closing a shift (ADR 0042).
 *
 * <p>Every entry point takes a {@link ShiftActor} and asks
 * {@link ShiftTransition} whether that actor may perform it. The rule is not
 * that a manager is less trusted; it is that a manager who can open a shift can
 * create paid hours for somebody who was at home, and a manager who can end a
 * break is directing a self-employed person's rest periods — which is the fact
 * pattern that reclassifies the engagement. What a manager may do is end
 * service, with a reason, and approve the hours afterwards.
 *
 * <p>Paid seconds come from this shift and never from ADR 0014's
 * {@code courier_availability}, which is a dispatch toggle. A roster is not a
 * source either: paying from a roster pays a courier who did not show up, and
 * the person who wrote the roster is usually the person approving the pay.
 */
@Service
public class CourierShiftService {

    private final JdbcCourierShiftStore shifts;
    private final JdbcCourierStore couriers;
    private final JdbcCourierLedgerStore ledgerStore;
    private final JdbcCourierRateCardStore rateCards;
    private final CourierLedgerService ledger;
    private final CourierPolicyResolver policies;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierShiftService(
            JdbcCourierShiftStore shifts,
            JdbcCourierStore couriers,
            JdbcCourierLedgerStore ledgerStore,
            JdbcCourierRateCardStore rateCards,
            CourierLedgerService ledger,
            CourierPolicyResolver policies,
            FieldProtection protection,
            AuditRecorder audit,
            Clock clock) {
        this.shifts = shifts;
        this.couriers = couriers;
        this.ledgerStore = ledgerStore;
        this.rateCards = rateCards;
        this.ledger = ledger;
        this.policies = policies;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
    }

    /** Thrown when an actor attempts a transition their role does not hold. */
    public static ApiException notPermitted(ShiftTransition transition, ShiftActor actor) {
        return new ApiException(
                ErrorCode.INSUFFICIENT_CAPABILITY,
                "%s may not %s a shift; that transition belongs to %s (ADR 0042)"
                        .formatted(actor, transition.name().toLowerCase(java.util.Locale.ROOT), transition.permitted()),
                Map.of("transition", transition.name(), "actor", actor.name()));
    }

    /**
     * The courier declares they are working.
     *
     * <p>Refused outright while the engagement is not {@code ACTIVE}: a lapsed
     * registration cannot open a shift, which is half of what the compliance
     * lever does. The other half is that dispatch stops offering, and neither
     * half touches an accrual.
     */
    @Transactional
    public ShiftRow open(OpenShift command) {
        if (!ShiftTransition.OPEN.permits(command.actorKind())) {
            throw notPermitted(ShiftTransition.OPEN, command.actorKind());
        }

        EngagementRow engagement = couriers.findLiveEngagement(command.tenantId(), command.courierId())
                .orElseThrow(
                        () -> new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This courier has no live engagement"));
        if (!engagement.status().dispatchable()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A shift cannot be opened while the engagement is %s".formatted(engagement.status()),
                    Map.of(
                            "engagementStatus",
                            engagement.status().name(),
                            "warningState",
                            engagement.warningState().name()));
        }
        if (shifts.findLiveShift(command.tenantId(), command.courierId()).isPresent()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This courier already has a live shift");
        }

        ResolvedPolicy<CourierCompensationPolicy> policy = policies.resolveWithIdentity(
                ResourceScope.location(command.tenantId(), command.brandId(), command.locationId()));

        UUID shiftId = UUID.randomUUID();
        String openPoint = protectPoint(command.tenantId(), shiftId, "protected_open_point", command.point());
        PeriodRow period = ledger.currentPeriod(
                command.tenantId(),
                command.courierId(),
                command.currency(),
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));

        shifts.insertShift(new ShiftRow(
                shiftId,
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                command.courierId(),
                engagement.id(),
                ShiftStatus.OPEN,
                DutyState.AVAILABLE,
                clock.instant(),
                null,
                "COURIER",
                null,
                null,
                openPoint,
                null,
                0,
                policy.document().shiftEnforcement(),
                policy.policyId(),
                policy.policyVersion(),
                null,
                period.id(),
                1));

        audit.record(fact(
                "courier.shift.opened",
                command.actor(),
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                shiftId,
                command.reason(),
                Map.of(
                        "openSource",
                        "COURIER",
                        "enforcementMode",
                        policy.document().shiftEnforcement().name(),
                        "enforcementPolicyVersion",
                        policy.policyVersion())));

        return shifts.findShift(command.tenantId(), shiftId).orElseThrow();
    }

    /** The courier goes on break. ADR 0045 stops collecting for the duration. */
    @Transactional
    public void startBreak(UUID tenantId, UUID shiftId, ShiftActor actorKind, ActorRef actor, String reason) {

        if (!ShiftTransition.START_BREAK.permits(actorKind)) {
            throw notPermitted(ShiftTransition.START_BREAK, actorKind);
        }
        ShiftRow shift = liveShift(tenantId, shiftId);
        if (!shifts.setDutyState(tenantId, shiftId, DutyState.AVAILABLE, DutyState.ON_BREAK, clock.instant())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "A break starts from AVAILABLE; this shift is " + shift.dutyState());
        }
        shifts.startBreak(UUID.randomUUID(), tenantId, shiftId, clock.instant());

        audit.record(fact(
                "courier.shift.break-started",
                actor,
                tenantId,
                shift.brandId(),
                shift.locationId(),
                shiftId,
                reason,
                Map.of("dutyState", DutyState.ON_BREAK.name())));
    }

    @Transactional
    public void endBreak(UUID tenantId, UUID shiftId, ShiftActor actorKind, ActorRef actor, String reason) {

        if (!ShiftTransition.END_BREAK.permits(actorKind)) {
            throw notPermitted(ShiftTransition.END_BREAK, actorKind);
        }
        ShiftRow shift = liveShift(tenantId, shiftId);
        if (!shifts.endOpenBreak(tenantId, shiftId, "COURIER", clock.instant())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "No break is open on this shift");
        }
        shifts.setDutyState(tenantId, shiftId, DutyState.ON_BREAK, DutyState.AVAILABLE, clock.instant());

        audit.record(fact(
                "courier.shift.break-ended",
                actor,
                tenantId,
                shift.brandId(),
                shift.locationId(),
                shiftId,
                reason,
                Map.of("dutyState", DutyState.AVAILABLE.name())));
    }

    /**
     * Closes the shift and computes its paid seconds.
     *
     * <p>A manager close is permitted and always carries a reason code: ending
     * service, closing the premises, and safety are the tenant's to decide, and
     * doing it without saying why is not. The hours still need approval if they
     * vary, and an auto-close always does.
     */
    @Transactional
    public CloseOutcome close(CloseShift command) {
        if (!ShiftTransition.CLOSE.permits(command.actorKind())) {
            throw notPermitted(ShiftTransition.CLOSE, command.actorKind());
        }
        ShiftRow shift = liveShift(command.tenantId(), command.shiftId());
        if (command.actorKind() == ShiftActor.MANAGER
                && (command.reasonCode() == null || command.reasonCode().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A manager close records a reason code");
        }

        Instant closedAt = clock.instant();
        // Sweep up an open break first, or its seconds would be paid.
        shifts.endOpenBreak(command.tenantId(), command.shiftId(), "SHIFT_CLOSE", closedAt);
        long breakSeconds = shifts.breakSeconds(command.tenantId(), command.shiftId(), closedAt);
        long wallSeconds = Duration.between(shift.openedAt(), closedAt).toSeconds();
        long paidSeconds = Math.max(0, wallSeconds - breakSeconds);

        ShiftStatus status =
                switch (command.actorKind()) {
                    // An auto-closed shift always needs a manager: paying an unreviewed
                    // self-opened shift pays somebody who opened the app at home.
                    case SWEEPER -> ShiftStatus.AUTO_CLOSED;
                    case MANAGER -> ShiftStatus.AWAITING_APPROVAL;
                    case COURIER -> ShiftStatus.CLOSED;
                };
        String closeSource =
                switch (command.actorKind()) {
                    case SWEEPER -> "SWEEPER";
                    case MANAGER -> "MANAGER";
                    case COURIER -> "COURIER";
                };

        String closePoint =
                protectPoint(command.tenantId(), command.shiftId(), "protected_close_point", command.point());
        boolean closed = shifts.close(
                command.tenantId(),
                command.shiftId(),
                status,
                closeSource,
                command.reasonCode(),
                closePoint,
                paidSeconds,
                breakSeconds,
                closedAt);
        if (!closed) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The shift was closed by somebody else first");
        }

        UUID handoverId = openCashHandover(shift, command.currency());
        if (status == ShiftStatus.CLOSED) {
            creditShiftEarning(shift, paidSeconds, closedAt);
        }

        audit.record(fact(
                "courier.shift.closed",
                command.actor(),
                command.tenantId(),
                shift.brandId(),
                shift.locationId(),
                command.shiftId(),
                command.reason(),
                Map.of(
                        "closeSource",
                        closeSource,
                        "status",
                        status.name(),
                        "paidSeconds",
                        paidSeconds,
                        "breakSeconds",
                        breakSeconds,
                        "reasonCode",
                        String.valueOf(command.reasonCode()))));

        return new CloseOutcome(status, paidSeconds, breakSeconds, handoverId);
    }

    /**
     * A manager approves hours that needed reviewing, and the shift's fixed
     * component accrues at that point rather than at close.
     */
    @Transactional
    public void approveHours(UUID tenantId, UUID shiftId, UUID approvalRequestId, ActorRef actor, String reason) {

        if (!ShiftTransition.APPROVE_HOURS.permits(ShiftActor.MANAGER)) {
            throw notPermitted(ShiftTransition.APPROVE_HOURS, ShiftActor.MANAGER);
        }
        ShiftRow shift = shifts.findShift(tenantId, shiftId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such shift: " + shiftId));
        if (!shifts.approveHours(tenantId, shiftId, approvalRequestId, clock.instant())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This shift is %s and has no hours awaiting approval".formatted(shift.status()));
        }
        creditShiftEarning(
                shift,
                shift.paidSeconds() == null ? 0 : shift.paidSeconds(),
                shift.closedAt() == null ? clock.instant() : shift.closedAt());

        audit.record(fact(
                "courier.shift.hours-approved",
                actor,
                tenantId,
                shift.brandId(),
                shift.locationId(),
                shiftId,
                reason,
                Map.of("paidSeconds", String.valueOf(shift.paidSeconds()))));
    }

    /**
     * Auto-closes shifts left open past the cut-off. Couriers forget, and a shift
     * left open overnight is a paid night nobody worked.
     */
    @Transactional
    public int autoCloseStale(Duration after, String currency) {
        Instant cutoff = clock.instant().minus(after);
        int closed = 0;
        for (ShiftRow shift : shifts.openBefore(cutoff)) {
            close(new CloseShift(
                    shift.tenantId(),
                    shift.id(),
                    ShiftActor.SWEEPER,
                    ActorRef.systemJob("courier-shift-auto-close"),
                    "AUTO_CLOSED_PAST_SERVICE",
                    "Open past delivery hours plus the configured margin",
                    null,
                    currency));
            closed++;
        }
        return closed;
    }

    private void creditShiftEarning(ShiftRow shift, long paidSeconds, Instant closedAt) {
        Optional<UUID> courierType = courierTypeOf(shift);
        if (courierType.isEmpty()) {
            // No courier row, no type, no card to resolve — same silence as a
            // branch without an active card, which is the case below.
            return;
        }
        Optional<RateCard> card = rateCards.resolve(
                shift.tenantId(), shift.brandId(), shift.locationId(), courierType.orElseThrow(), closedAt);
        if (card.isEmpty()) {
            return;
        }
        CourierAccrual accrual =
                uz.horecaos.platform.courier.domain.AccrualCalculator.forShift(card.get(), paidSeconds);
        if (accrual.totalMinor() == 0) {
            return;
        }
        ledger.append(new CourierLedgerService.NewEntry(
                shift.tenantId(),
                shift.courierId(),
                shift.locationId(),
                LedgerEntryType.SHIFT_EARNING,
                accrual.totalMinor(),
                card.get().currency(),
                "courier_shift",
                shift.id(),
                AdjustmentOrigin.SYSTEM,
                null,
                closedAt,
                "shift-earning:" + shift.id(),
                null,
                null,
                "courier-shift-service"));
        ledgerStore.assignShiftToPeriod(
                shift.tenantId(),
                shift.id(),
                ledger.currentPeriod(
                                shift.tenantId(),
                                shift.courierId(),
                                card.get().currency(),
                                LocalDate.ofInstant(closedAt, ZoneOffset.UTC))
                        .id());
    }

    private Optional<UUID> courierTypeOf(ShiftRow shift) {
        return couriers.findCourier(shift.tenantId(), shift.courierId())
                .map(
                        uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow
                                ::courierTypeId);
    }

    /**
     * Opens the cash handover for the shift, if the courier took any cash. No
     * cash, no handover: an empty confirmation step at the end of every shift is
     * how the step stops being taken seriously on the shifts that matter.
     */
    private @Nullable UUID openCashHandover(ShiftRow shift, String currency) {
        long expected = ledgerStore.cashCollectedDuringShift(shift.tenantId(), shift.id());
        if (expected <= 0) {
            return null;
        }
        Optional<HandoverRow> existing = shifts.findHandoverByShift(shift.tenantId(), shift.id());
        if (existing.isPresent()) {
            return existing.get().id();
        }
        UUID handoverId = UUID.randomUUID();
        shifts.insertHandover(new HandoverRow(
                handoverId,
                shift.tenantId(),
                shift.id(),
                shift.courierId(),
                shift.locationId(),
                "PENDING",
                currency,
                expected,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        return handoverId;
    }

    private ShiftRow liveShift(UUID tenantId, UUID shiftId) {
        ShiftRow shift = shifts.findShift(tenantId, shiftId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such shift: " + shiftId));
        if (!shift.status().live()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This shift is " + shift.status());
        }
        return shift;
    }

    private @Nullable String protectPoint(UUID tenantId, UUID shiftId, String column, @Nullable String point) {
        if (point == null) {
            return null;
        }
        return protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL_SENSITIVE,
                        new FieldProtection.RecordRef("fulfillment.courier_shifts", column, shiftId),
                        point)
                .serialize();
    }

    private AuditFact fact(
            String action,
            ActorRef actor,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID shiftId,
            String reason,
            Map<String, Object> changes) {

        return AuditFact.of(action, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("courier_shift", shiftId)
                .because(reason)
                .changed(changes)
                .correlatedBy("courier-shift")
                .occurredAt(clock.instant())
                .build();
    }

    /**
     * The courier's declaration that they are starting work.
     *
     * @param point    "latitude,longitude" of the device at open, envelope
     *                 encrypted; never disclosed to a customer, who sees status
     *                 milestones only. Null when the handset sent none
     * @param currency the courier's settlement currency, so a period can be
     *                 opened for them
     */
    public record OpenShift(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID courierId,
            ShiftActor actorKind,
            ActorRef actor,
            String reason,
            @Nullable String point,
            String currency) {}

    /**
     * A close request, from whichever of the three actors is making it.
     *
     * @param reasonCode required for a manager close, null for the courier's own
     * @param point      the device's position at close, when the handset sent one
     */
    public record CloseShift(
            UUID tenantId,
            UUID shiftId,
            ShiftActor actorKind,
            ActorRef actor,
            @Nullable String reasonCode,
            String reason,
            @Nullable String point,
            String currency) {}

    /**
     * What the close computed.
     *
     * @param cashHandoverId null when the shift took no cash, so no handover opened
     */
    public record CloseOutcome(
            ShiftStatus status,
            long paidSeconds,
            long breakSeconds,
            @Nullable UUID cashHandoverId) {}

    /** Exposed for the dispatch gate's readers and the operations board. */
    public Optional<ShiftRow> liveShiftOf(UUID tenantId, UUID courierId) {
        return shifts.findLiveShift(tenantId, courierId);
    }

    public ShiftEnforcement enforcementAt(UUID tenantId, UUID brandId, UUID locationId) {
        return policies.resolve(ResourceScope.location(tenantId, brandId, locationId))
                .shiftEnforcement();
    }
}
