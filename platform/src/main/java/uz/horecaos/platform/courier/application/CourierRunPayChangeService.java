package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.courier.domain.RunPayMode;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The gate in front of a change to how a courier is paid for a run (ADR 0170 Decision 6, ADR 0050).
 *
 * <p>Moving the pay mode <em>to</em> {@link RunPayMode#FIRST_FULL_THEN_INCREMENT} pays couriers less for the
 * same kilometres. That is the purpose of grouping and also a labour-relations event, so the move needs two
 * things beyond the ordinary policy write: the publisher's explicit statement that the tenant's engagement
 * terms already allow a pay basis that depends on the run (the platform asserts nothing about the tenant's
 * contracts), recorded in the audit fact, and a pass through the registered approval action {@code
 * courier.run-pay.change}, which asks for a second signature only where the tenant has an approval policy for
 * it. Any other change to the document, and a move <em>away</em> from the increment, passes untouched.
 */
@Service
public class CourierRunPayChangeService {

    public static final String AUDIT_ACTION = "courier.run-pay.changed";

    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierRunPayChangeService(ApprovalService approvals, AuditRecorder audit, Clock clock) {
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Decides whether the publication may go ahead.
     *
     * @param current      the pay mode in force at the scope, null when undecided
     * @param requested    the pay mode the new document carries
     * @param acknowledged the publisher's statement that the engagement terms allow it; required only when
     *                     the mode is moving to {@code FIRST_FULL_THEN_INCREMENT}
     * @throws ApiException {@code VALIDATION_FAILED} when the acknowledgement is missing
     */
    public Decision gate(
            ResourceScope scope,
            @Nullable RunPayMode current,
            @Nullable RunPayMode requested,
            @Nullable Boolean acknowledged,
            ActorRef actor,
            String reason) {

        boolean movesToIncrement =
                requested == RunPayMode.FIRST_FULL_THEN_INCREMENT && current != RunPayMode.FIRST_FULL_THEN_INCREMENT;
        if (!movesToIncrement) {
            return Decision.unchanged();
        }
        if (!Boolean.TRUE.equals(acknowledged)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Paying later orders of a run by their extra distance needs acknowledgeEngagementTerms = true: "
                            + "the tenant states that its courier engagement terms allow a pay basis that depends on the run");
        }

        ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.COURIER_RUN_PAY_CHANGE.code(),
                parametersHash(scope, current, requested),
                scope,
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));

        return switch (outcome) {
            case ApprovalOutcome.Approved approved -> {
                approved.grant().consume();
                yield Decision.approved(approved.requestId());
            }
            case ApprovalOutcome.Pending pending -> Decision.pending(pending.requestId());
            case ApprovalOutcome.Declined declined ->
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE, "The pay-mode change was declined: " + declined.reason());
            case ApprovalOutcome.NotRequired ignored -> Decision.unchanged().withAcknowledgement();
        };
    }

    /**
     * Records what changed, once the document is published: the mode before and after and the
     * acknowledgement, naming the approval that covered it when there was one.
     */
    public void record(
            ResourceScope scope,
            @Nullable RunPayMode before,
            @Nullable RunPayMode after,
            Decision decision,
            ActorRef actor,
            String reason,
            @Nullable UUID policyId,
            int policyVersion) {

        if (before == after) {
            return;
        }
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("runPayMode", before == null ? "UNDECIDED" : before.name());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("runPayMode", after == null ? "UNDECIDED" : after.name());
        now.put("acknowledgedEngagementTerms", decision.acknowledged());

        AuditFact.Builder fact = AuditFact.of(AUDIT_ACTION, AuditClass.BUSINESS)
                .by(actor)
                .at(scope)
                .target("courier.compensation", policyId == null ? UUID.randomUUID() : policyId)
                .targetVersion((long) policyVersion)
                .because(reason)
                .changed(ChangeDocuments.diff(was, now))
                .occurredAt(clock.instant());
        if (decision.approvalRequestId() != null) {
            fact.underApproval(decision.approvalRequestId());
        }
        audit.record(fact.build());
    }

    static String parametersHash(ResourceScope scope, @Nullable RunPayMode current, @Nullable RunPayMode requested) {
        return ApprovalParameters.of(new Covered(
                        scope.tenantId(),
                        scope.type().name(),
                        scope.brandId(),
                        scope.locationId(),
                        current == null ? "UNDECIDED" : current.name(),
                        requested == null ? "UNDECIDED" : requested.name()))
                .hash();
    }

    /** What an approval covers: the scope and the move, so one signature cannot be spent on another scope. */
    private record Covered(
            @Nullable UUID tenantId,
            String scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String from,
            String to) {}

    /**
     * @param pending           a second signature is awaited; the document must not be published yet
     * @param approvalRequestId the approval that covered the change, or that is awaited
     * @param acknowledged      the publisher stated the engagement terms allow it
     */
    public record Decision(boolean pending, @Nullable UUID approvalRequestId, boolean acknowledged) {

        static Decision unchanged() {
            return new Decision(false, null, false);
        }

        static Decision approved(UUID requestId) {
            return new Decision(false, requestId, true);
        }

        static Decision pending(UUID requestId) {
            return new Decision(true, requestId, true);
        }

        Decision withAcknowledgement() {
            return new Decision(pending, approvalRequestId, true);
        }
    }
}
