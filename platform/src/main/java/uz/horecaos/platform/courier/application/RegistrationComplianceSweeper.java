package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.application.port.CourierNotificationPort;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.EngagementStatus;
import uz.horecaos.platform.courier.domain.RegistrationWarningState;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * The sweeper that notices a registration running out (ADR 0042).
 *
 * <p>The platform is the only party holding the courier's work history, dispatch
 * decisions, and payment record together, so it is the only party positioned to
 * notice. A lapse surfaces as nothing at all otherwise: no error, no failed
 * call, no unhappy customer.
 *
 * <p>What a lapse does is stop new offers and refuse a shift open. What it never
 * does is touch money. Freezing an accrued balance to enforce a compliance rule
 * withholds payment for work already performed, which is a dispute the tenant
 * loses and probably may not do at all — the correct lever is prospective and
 * proportionate, and that lever is refusing new work.
 */
@Service
public class RegistrationComplianceSweeper {

    /** The ladder, in days remaining. The manager joins at fourteen. */
    private static final List<Integer> COURIER_RUNGS = List.of(30, 14, 7, 1);

    private static final List<Integer> MANAGER_RUNGS = List.of(14, 7, 1);

    private final JdbcCourierStore couriers;
    private final CourierNotificationPort notifications;
    private final CourierPolicyResolver policies;
    private final AuditRecorder audit;
    private final Clock clock;

    public RegistrationComplianceSweeper(
            JdbcCourierStore couriers,
            CourierNotificationPort notifications,
            CourierPolicyResolver policies,
            AuditRecorder audit,
            Clock clock) {
        this.couriers = couriers;
        this.notifications = notifications;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * One pass. Returns what it changed, so the job that calls it can be observed
     * rather than trusted.
     */
    @Transactional
    public SweepResult sweep() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int warned = 0;
        int lapsed = 0;

        // The widest warning window any tenant could have configured bounds the
        // candidate set; each engagement is then judged against its own tenant's
        // policy, because a thirty-day window at one tenant and sixty at another
        // are both legitimate.
        for (EngagementRow engagement : couriers.expiringBetween(today, today.plusDays(90))) {
            CourierCompensationPolicy policy = policies.resolve(ResourceScope.tenant(engagement.tenantId()));
            LocalDate dueOn = engagement.reverificationDueOn();
            long remaining = today.until(dueOn).getDays()
                    + 31L * today.until(dueOn).getMonths()
                    + 366L * today.until(dueOn).getYears();

            if (remaining > policy.warningDays()) {
                continue;
            }
            couriers.markWarningState(
                    engagement.tenantId(), engagement.id(), RegistrationWarningState.EXPIRING, clock.instant());

            for (int rung : COURIER_RUNGS) {
                if (remaining <= rung
                        && couriers.claimNotice(
                                engagement.tenantId(), engagement.id(), rung, "COURIER", dueOn, clock.instant())) {
                    notifications.registrationExpiring(
                            engagement.tenantId(),
                            engagement.courierId(),
                            dueOn,
                            rung,
                            CourierNotificationPort.Audience.COURIER);
                    warned++;
                    // One rung per pass. Crossing several at once — a sweeper
                    // that was down for a week — should not send four messages
                    // in one minute, and the lowest rung still fires next pass.
                    break;
                }
            }
            for (int rung : MANAGER_RUNGS) {
                if (remaining <= rung
                        && couriers.claimNotice(
                                engagement.tenantId(), engagement.id(), rung, "MANAGER", dueOn, clock.instant())) {
                    notifications.registrationExpiring(
                            engagement.tenantId(),
                            engagement.courierId(),
                            dueOn,
                            rung,
                            CourierNotificationPort.Audience.MANAGER);
                    warned++;
                    break;
                }
            }
        }

        for (EngagementRow engagement : couriers.dueBy(today.minusDays(1))) {
            if (engagement.status() != EngagementStatus.ACTIVE) {
                continue;
            }
            boolean suspended = couriers.suspend(
                    engagement.tenantId(),
                    engagement.id(),
                    EngagementStatus.SUSPENDED_COMPLIANCE,
                    "REGISTRATION_LAPSED",
                    RegistrationWarningState.LAPSED,
                    clock.instant());
            if (!suspended) {
                continue;
            }
            lapsed++;
            couriers.claimNotice(
                    engagement.tenantId(),
                    engagement.id(),
                    0,
                    "COURIER",
                    engagement.reverificationDueOn(),
                    clock.instant());
            couriers.claimNotice(
                    engagement.tenantId(),
                    engagement.id(),
                    0,
                    "MANAGER",
                    engagement.reverificationDueOn(),
                    clock.instant());
            notifications.registrationLapsed(
                    engagement.tenantId(), engagement.courierId(), engagement.reverificationDueOn());

            audit.record(AuditFact.of("courier.registration.lapsed", AuditClass.BUSINESS)
                    .by(ActorRef.systemJob("courier-registration-sweeper"))
                    .at(ResourceScope.tenant(engagement.tenantId()))
                    .target("courier_engagement", engagement.id())
                    .changed(Map.of(
                            "status",
                            EngagementStatus.SUSPENDED_COMPLIANCE.name(),
                            "reverificationDueOn",
                            String.valueOf(engagement.reverificationDueOn()),
                            "accruedEarningsReversed",
                            false))
                    .correlatedBy("courier-registration-sweeper")
                    .occurredAt(clock.instant())
                    .build());
        }

        return new SweepResult(warned, lapsed);
    }

    public record SweepResult(int notificationsSent, int engagementsSuspended) {}
}
