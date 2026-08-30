package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.courier.application.port.LegalEntityResolver;
import uz.horecaos.platform.courier.domain.AccrualCalculator;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.CostBasis;
import uz.horecaos.platform.courier.domain.CostPath;
import uz.horecaos.platform.courier.domain.CourierAccrual;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.DistanceSource;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.domain.OnTimeEvaluator;
import uz.horecaos.platform.courier.domain.OnTimeOutcome;
import uz.horecaos.platform.courier.domain.RateCard;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.EarningRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.CostLineRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What a delivered order earned, computed once at delivery (ADR 0042).
 *
 * <p>Nothing here can see the customer's delivery charge, and that is the design
 * rather than an omission. Delever coupled the two and shipped a correction after
 * they diverged and caused payout disputes: a free-delivery promotion would pay
 * the courier nothing, and a distant order priced flat would underpay the person
 * who drove it. A tenant wanting them equal configures a matching rate card,
 * which is a choice on record rather than a hidden coupling.
 *
 * <p>One delivery accrues exactly once. Duplicate delivery events are ordinary —
 * a retried webhook, a reconnecting handset — and the second one must not pay.
 * The unique constraint on the assignment attempt is what decides, not a prior
 * read.
 */
@Service
public class CourierAccrualService {

    private final JdbcCourierLedgerStore ledgerStore;
    private final JdbcCourierRateCardStore rateCards;
    private final JdbcCourierShiftStore shifts;
    private final JdbcCourierStore couriers;
    private final JdbcDeliveryCostStore costLines;
    private final CourierLedgerService ledger;
    private final CourierPolicyResolver policies;
    private final LegalEntityResolver legalEntities;
    private final FieldProtection protection;
    private final Clock clock;

    public CourierAccrualService(JdbcCourierLedgerStore ledgerStore,
            JdbcCourierRateCardStore rateCards, JdbcCourierShiftStore shifts,
            JdbcCourierStore couriers, JdbcDeliveryCostStore costLines, CourierLedgerService ledger,
            CourierPolicyResolver policies, LegalEntityResolver legalEntities,
            FieldProtection protection, Clock clock) {
        this.ledgerStore = ledgerStore;
        this.rateCards = rateCards;
        this.shifts = shifts;
        this.couriers = couriers;
        this.costLines = costLines;
        this.ledger = ledger;
        this.policies = policies;
        this.legalEntities = legalEntities;
        this.protection = protection;
        this.clock = clock;
    }

    /**
     * Records the accrual for one delivered order, its ledger entry, its
     * delivery cost line, and — on a cash order — the cash the courier is now
     * holding.
     *
     * <p>All four in one transaction, because an accrual without its cost line
     * is a delivery nobody can report the cost of, and a cash collection written
     * separately is a bag of money the ledger does not know about.
     */
    @Transactional
    public EarningRow recordDelivery(DeliveredAssignment command) {
        Optional<EarningRow> already = ledgerStore.findEarningByAttempt(
                command.tenantId(), command.assignmentAttemptId());
        if (already.isPresent()) {
            return already.get();
        }

        RateCard card = rateCards.resolve(command.tenantId(), command.brandId(),
                        command.locationId(), courierTypeOf(command), command.acceptedAt())
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                        "No active courier rate card covers this branch and courier type; "
                                + "a delivery cannot be accrued against nothing"));

        CourierCompensationPolicy policy = policies.resolve(ResourceScope.location(
                command.tenantId(), command.brandId(), command.locationId()));
        int graceSeconds = command.graceSeconds() == null
                ? policy.graceSeconds() : command.graceSeconds();

        CourierAccrual accrual = AccrualCalculator.forDelivery(card, command.distanceMeters());
        OnTimeOutcome outcome = OnTimeEvaluator.evaluate(command.deliveredAt(),
                command.promisedDeliveryEnd(), graceSeconds, command.kitchenHandoverAt(),
                command.pickupWindowEnd());

        LocalDate businessDate = LocalDate.ofInstant(command.deliveredAt(), ZoneOffset.UTC);
        UUID legalEntityId = legalEntities
                .resolve(command.tenantId(), command.locationId(), businessDate)
                .orElse(null);
        UUID earningId = UUID.randomUUID();
        UUID periodId = ledger.currentPeriod(command.tenantId(), command.courierId(),
                card.currency(), businessDate).id();

        boolean inserted = ledgerStore.insertEarning(new EarningRow(earningId, command.tenantId(),
                command.courierId(), command.shiftId(), command.shipmentId(),
                command.assignmentAttemptId(), legalEntityId, command.locationId(), businessDate,
                card.id(), card.version(), courierTypeOf(command), command.distanceMeters(),
                command.distanceSource(), outcome, command.promisedDeliveryEnd(), graceSeconds,
                command.onTimePolicyVersion(), command.deliveredAt(), command.kitchenHandoverAt(),
                command.pickupWindowEnd(), accrual.fixedMinor(), accrual.perOrderMinor(),
                accrual.perKmMinor(), accrual.minimumTopUpMinor(), accrual.totalMinor(),
                card.currency(), command.geoUnverified(),
                protectPoint(command.tenantId(), earningId, "protected_pickup_point",
                        command.pickupPoint()),
                protectPoint(command.tenantId(), earningId, "protected_delivery_point",
                        command.deliveryPoint()),
                null, periodId));

        if (!inserted) {
            // Somebody else won the race on the attempt's unique constraint.
            return ledgerStore.findEarningByAttempt(command.tenantId(),
                    command.assignmentAttemptId()).orElseThrow();
        }

        ledger.append(new CourierLedgerService.NewEntry(command.tenantId(), command.courierId(),
                command.locationId(), LedgerEntryType.DELIVERY_EARNING, accrual.totalMinor(),
                card.currency(), "courier_assignment_earning", earningId, AdjustmentOrigin.SYSTEM,
                null, command.deliveredAt(), "delivery-earning:" + command.assignmentAttemptId(),
                null, null, "courier-accrual-service"));

        // The internal half of ADR 0042's two cost paths. Recognised now, at
        // ACCRUED, and it becomes SETTLED when the period closes — never
        // INVOICED, because there is no invoice from HorecaOS to itself.
        costLines.insertLine(new CostLineRow(UUID.randomUUID(), command.tenantId(),
                command.shipmentId(), legalEntityId, businessDate, CostPath.INTERNAL,
                CostBasis.ACCRUED, accrual.totalMinor(), card.currency(),
                "courier_assignment_earning", earningId, command.courierId(), null,
                command.deliveredAt(), null, "courier-accrual-service"));

        if (command.cashToCollectMinor() > 0) {
            ledger.append(new CourierLedgerService.NewEntry(command.tenantId(), command.courierId(),
                    command.locationId(), LedgerEntryType.CASH_COLLECTED,
                    -command.cashToCollectMinor(), card.currency(), "shipment",
                    command.shipmentId(), AdjustmentOrigin.SYSTEM, null, command.deliveredAt(),
                    "cash-collected:" + command.assignmentAttemptId(), null, null,
                    "courier-accrual-service"));
        }

        if (command.shiftId() != null) {
            shifts.findShift(command.tenantId(), command.shiftId())
                    .ifPresent(shift -> ledgerStore.assignShiftToPeriod(command.tenantId(),
                            shift.id(), periodId));
        }

        return ledgerStore.findEarningByAttempt(command.tenantId(), command.assignmentAttemptId())
                .orElseThrow();
    }

    private UUID courierTypeOf(DeliveredAssignment command) {
        return couriers.findCourier(command.tenantId(), command.courierId())
                .map(JdbcCourierStore.CourierRow::courierTypeId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such courier: " + command.courierId()));
    }

    private String protectPoint(UUID tenantId, UUID earningId, String column, String point) {
        if (point == null) {
            return null;
        }
        return protection.protect(tenantId, DataClass.PERSONAL_SENSITIVE,
                        new FieldProtection.RecordRef("fulfillment.courier_assignment_earnings",
                                column, earningId),
                        point)
                .serialize();
    }

    /**
     * @param acceptedAt          when the courier accepted, which is the instant
     *                            the rate card is resolved at. Resolving at
     *                            delivery would let a card activated mid-trip
     *                            change what was agreed before it
     * @param cashToCollectMinor  the order total less anything already captured
     *                            and less any loyalty amount. Zero on a prepaid
     *                            order
     * @param geoUnverified       the delivery confirmation fell outside the
     *                            radius. Soft by default: a hard gate strands a
     *                            courier in a stairwell with a customer waiting,
     *                            and the workaround is marking delivered from the
     *                            street, which yields worse data and a worse
     *                            delivery
     */
    public record DeliveredAssignment(UUID tenantId, UUID brandId, UUID locationId, UUID courierId,
            UUID shiftId, UUID shipmentId, UUID assignmentAttemptId, int distanceMeters,
            DistanceSource distanceSource, Instant acceptedAt, Instant deliveredAt,
            Instant promisedDeliveryEnd, Integer graceSeconds, int onTimePolicyVersion,
            Instant kitchenHandoverAt, Instant pickupWindowEnd, long cashToCollectMinor,
            boolean geoUnverified, String pickupPoint, String deliveryPoint) { }
}
