package uz.horecaos.platform.audit.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.ResetCommand;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.ResetResult;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0027's maker-checker in front of a platform account's second-factor reset (ADR 0148,
 * Decision 5; ADR 0050 fail-closed).
 *
 * <p>Lives in {@code audit} for the reason {@link PlatformGrantService} gives: the approval gate
 * needs {@code audit.api.ApprovalService}, and an approval gate in {@code iam} would close the
 * module cycle {@code ModularArchitectureTests} exists to catch. What {@code iam} owns is the act
 * ({@link StaffMfaAdministration#reset}); this class decides only <em>whether it may happen yet</em>.
 *
 * <p>A first call raises the request and answers that it awaits a second administrator; the
 * checker decides it on the ordinary approvals queue; the maker then repeats the call, the
 * approval for identical parameters is found and spent, and the reset runs. The parameters hash
 * covers the subject and the reason, so an approval given for one account never releases another.
 */
@Service
public class StaffMfaResetService {

    private final StaffMfaAdministration mfa;
    private final ApprovalService approvals;

    public StaffMfaResetService(StaffMfaAdministration mfa, ApprovalService approvals) {
        this.mfa = mfa;
        this.approvals = approvals;
    }

    @Transactional
    public Outcome reset(String subjectId, String actorSubject, String reason) {
        if (actorSubject.equals(subjectId)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "Nobody resets their own second factor. Ask another administrator.");
        }
        if (!mfa.isPlatformAccount(subjectId)) {
            // Narrow on purpose: this route is for the accounts that need a second signature. A
            // tenant's people are reset on the tenant's own route; asking here must not become a
            // way to learn which subjects exist.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such platform account.");
        }
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.IAM_STAFF_MFA_RESET.code(),
                ApprovalParameters.none()
                        .and("subject", subjectId)
                        .and("reason", reason)
                        .hash(),
                ResourceScope.platform(),
                ActorRef.user(actorSubject, null),
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        if (!approval.mayProceed()) {
            return Outcome.awaitingApproval(
                    approval instanceof ApprovalOutcome.Pending pending ? pending.requestId() : null);
        }
        approval.consume();
        return Outcome.reset(mfa.reset(new ResetCommand(subjectId, actorSubject, reason, null)));
    }

    public record Outcome(
            Status status,
            @Nullable ResetResult result,
            @Nullable UUID approvalRequestId) {

        public enum Status {
            RESET,
            AWAITING_APPROVAL
        }

        static Outcome reset(ResetResult result) {
            return new Outcome(Status.RESET, result, null);
        }

        static Outcome awaitingApproval(@Nullable UUID requestId) {
            return new Outcome(Status.AWAITING_APPROVAL, null, requestId);
        }
    }
}
