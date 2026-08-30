package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.LedgerEntryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.AdjustmentReasonRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Bonuses and penalties (ADR 0042).
 *
 * <p>One mechanism with two origins. A rule-derived adjustment evaluates a
 * versioned condition set and is reproducible; a manual one names an actor and a
 * reason code from the managed registry. Every manual penalty, and any penalty
 * above the configured amount, requires ADR 0027 four-eyes approval — a manager
 * who can silently debit a courier's pay is a labour dispute and a fraud vector
 * in one instrument.
 *
 * <p>Under a self-employed engagement a penalty is a reduction in the amount
 * invoiced for, agreed in the engagement terms, and not a disciplinary
 * deduction. The reason registry is narrow for that reason: every code names a
 * delivery outcome rather than a behaviour, because routinely sanctioning how a
 * self-employed person conducts themselves is the fact pattern that reclassifies
 * the engagement, and a free-text penalty field is how that arrives one reason
 * code at a time.
 */
@Service
public class CourierAdjustmentService {

    private final JdbcCourierStore couriers;
    private final CourierLedgerService ledger;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final CourierPolicyResolver policies;
    private final Clock clock;

    public CourierAdjustmentService(
            JdbcCourierStore couriers,
            CourierLedgerService ledger,
            ApprovalService approvals,
            AuditRecorder audit,
            CourierPolicyResolver policies,
            Clock clock) {
        this.couriers = couriers;
        this.ledger = ledger;
        this.approvals = approvals;
        this.audit = audit;
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * Requests an adjustment. A bonus, or a penalty small enough and rule-derived
     * enough not to need approval, is written immediately; anything else returns
     * {@link Outcome#pendingApproval} and writes nothing.
     *
     * <p>The caller must not treat pending as written. That is why the outcome is
     * a record with a nullable entry rather than an exception: an exception on
     * the approval path invites a caller to catch it and carry on.
     */
    @Transactional
    public Outcome request(AdjustmentCommand command) {
        AdjustmentReasonRow reason = couriers.findAdjustmentReason(command.tenantId(), command.reasonCode())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "No such adjustment reason: " + command.reasonCode()));
        if (!"ACTIVE".equals(reason.status())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "That reason is archived: " + command.reasonCode());
        }

        boolean penalty = command.amountMinor() < 0;
        if (penalty != "PENALTY".equals(reason.kind())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A %s reason cannot carry %d".formatted(reason.kind(), command.amountMinor()));
        }

        CourierCompensationPolicy policy = policies.resolve(ResourceScope.tenant(command.tenantId()));
        boolean needsApproval = penalty
                && (command.origin() == AdjustmentOrigin.MANUAL
                        || Math.abs(command.amountMinor()) > policy.penaltyApprovalThresholdMinor());

        UUID approvalRequestId = null;
        if (needsApproval) {
            ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                    ApprovalAction.COURIER_ADJUSTMENT_CREATE.code(),
                    parametersHash(command),
                    ResourceScope.tenant(command.tenantId()),
                    command.actor(),
                    command.reason(),
                    ApprovalRequestCommand.DEFAULT_VALIDITY));

            switch (outcome) {
                case ApprovalOutcome.Approved approved -> {
                    // Spent in this transaction, alongside the ledger entry below.
                    // Without it the same penalty could be posted again under the
                    // same signature for the rest of the approval's validity.
                    approved.grant().consume();
                    approvalRequestId = approved.requestId();
                }
                case ApprovalOutcome.Pending pending -> {
                    return Outcome.pendingApproval(pending.requestId());
                }
                case ApprovalOutcome.Declined declined ->
                    throw new ApiException(
                            ErrorCode.UNPROCESSABLE_STATE, "The adjustment was declined: " + declined.reason());
                case ApprovalOutcome.NotRequired ignored -> {
                    // A tenant with no approval policy configured still gets the
                    // four-eyes rule for a manual penalty, because ADR 0042 makes
                    // that unconditional rather than policy-driven.
                    if (command.origin() == AdjustmentOrigin.MANUAL) {
                        ApprovalOutcome forced = approvals.requireApproval(new ApprovalRequestCommand(
                                ApprovalAction.COURIER_MANUAL_PENALTY.code(),
                                parametersHash(command),
                                ResourceScope.tenant(command.tenantId()),
                                command.actor(),
                                command.reason(),
                                ApprovalRequestCommand.DEFAULT_VALIDITY));
                        if (forced instanceof ApprovalOutcome.Approved approved) {
                            approved.grant().consume();
                            approvalRequestId = approved.requestId();
                        } else if (forced instanceof ApprovalOutcome.Pending pending) {
                            return Outcome.pendingApproval(pending.requestId());
                        } else {
                            throw new ApiException(
                                    ErrorCode.UNPROCESSABLE_STATE,
                                    "A manual penalty requires an approval that was not granted");
                        }
                    }
                }
            }
        }

        LedgerEntryRow entry = ledger.append(new CourierLedgerService.NewEntry(
                command.tenantId(),
                command.courierId(),
                command.locationId(),
                penalty ? LedgerEntryType.PENALTY : LedgerEntryType.BONUS,
                command.amountMinor(),
                command.currency(),
                "courier_adjustment",
                null,
                command.origin(),
                command.reasonCode(),
                clock.instant(),
                command.idempotencyKey(),
                approvalRequestId,
                null,
                command.actor().subject()));

        audit.record(AuditFact.of("courier.adjustment.recorded", AuditClass.BUSINESS)
                .by(command.actor())
                .at(ResourceScope.tenant(command.tenantId()))
                .target("courier_ledger_entry", entry.id())
                .because(command.reason())
                .changed(Map.of(
                        "amountMinor",
                        command.amountMinor(),
                        "reasonCode",
                        command.reasonCode(),
                        "origin",
                        command.origin().name(),
                        "outcomeBasis",
                        reason.outcomeBasis()))
                .underApproval(approvalRequestId)
                .usingCapability("courier.adjustment.create")
                .correlatedBy(command.correlationId())
                .occurredAt(clock.instant())
                .build());

        return Outcome.recorded(entry);
    }

    /**
     * The parameters an approval covers.
     *
     * <p><strong>The location was missing, and the location is who pays.</strong>
     * The list was tenant, courier, amount, currency, reason code and origin, and
     * {@code locationId} goes straight onto the ledger entry — it is the branch
     * whose P&amp;L bears the penalty and the figure a branch manager is measured
     * on. One signature therefore covered debiting the courier and charging it to
     * Chilonzor, or to Yunusobod: the checker saw one and the maker could post the
     * other, and on the console the two requests were identical.
     *
     * <p>It is derived from the command now rather than transcribed from it, so a
     * component added to {@link AdjustmentCommand} is covered without anyone
     * having to remember this method exists. Excluded, deliberately:
     * {@code idempotencyKey}, because a retry of the same submission carries a
     * fresh one and is the same intended action; {@code actor}, because the
     * four-eyes rule governs who decides rather than who executes; and
     * {@code correlationId}, which is a trace identifier and changes nothing.
     * {@code reason} is covered — it is what the checker read.
     */
    static String parametersHash(AdjustmentCommand command) {
        return ApprovalParameters.of(command)
                .excluding("idempotencyKey", "actor", "correlationId")
                .hash();
    }

    /** @param amountMinor positive for a bonus, negative for a penalty */
    public record AdjustmentCommand(
            UUID tenantId,
            UUID courierId,
            UUID locationId,
            long amountMinor,
            String currency,
            String reasonCode,
            AdjustmentOrigin origin,
            String idempotencyKey,
            ActorRef actor,
            String reason,
            String correlationId) {}

    public record Outcome(LedgerEntryRow entry, UUID approvalRequestId) {

        public static Outcome recorded(LedgerEntryRow entry) {
            return new Outcome(entry, entry.approvalRequestId());
        }

        public static Outcome pendingApproval(UUID requestId) {
            return new Outcome(null, requestId);
        }

        public boolean written() {
            return entry != null;
        }
    }
}
