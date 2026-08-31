package uz.horecaos.platform.audit.application;

import java.time.Instant;
import java.util.List;
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
import uz.horecaos.platform.iam.api.grants.PlatformGrantAuthority;

/**
 * ADR 0027's maker-checker in front of a {@code PLATFORM}-scope grant (Gap A
 * of the 2026-08-30 proving run) — the highest-authority write this
 * platform's own model can express, since {@code PLATFORM_ADMIN} covers
 * every capability but one.
 *
 * <p>Lives in {@code audit} rather than {@code iam}, which is where the grant
 * write itself ({@link PlatformGrantAuthority}) lives. {@code audit} already
 * depends on {@code iam.api} — every approval request carries a {@link
 * ResourceScope} and a requester — so this direction adds nothing new;
 * putting the approval gate in {@code iam} instead would give {@code iam} a
 * dependency on {@code audit}, closing a cycle {@code ModularArchitectureTests}
 * exists to catch. See {@link PlatformGrantAuthority}'s own javadoc for the
 * fuller argument.
 *
 * <p>Absent a configured {@code audit.approval_policies} row for {@link
 * ApprovalAction#IAM_PLATFORM_GRANT_MANAGE}, ADR 0050's registered default
 * ({@code ALLOW_WITHOUT_APPROVAL}) applies and a single signature suffices —
 * the same honest default {@code TENANT_ACTIVATE} gets. A deployment that
 * wants a second signature on its own platform grants authors a {@code
 * PLATFORM}-scope policy for this action code (the schema has carried {@code
 * PLATFORM} scope since V0082); this class does not invent a stricter
 * default on its own.
 */
@Service
public class PlatformGrantService {

    private final PlatformGrantAuthority authority;
    private final ApprovalService approvals;

    public PlatformGrantService(PlatformGrantAuthority authority, ApprovalService approvals) {
        this.authority = authority;
        this.approvals = approvals;
    }

    @Transactional
    public Outcome grant(
            String principalSubject,
            String roleCode,
            String reason,
            @Nullable Instant validUntil,
            String granterSubject) {
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.IAM_PLATFORM_GRANT_MANAGE.code(),
                parametersHash("grant", principalSubject, roleCode, reason, validUntil),
                ResourceScope.platform(),
                ActorRef.user(granterSubject, null),
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));

        if (!approval.mayProceed()) {
            return Outcome.awaitingApproval(pendingRequestId(approval));
        }
        approval.consume();

        UUID grantId = authority.grant(principalSubject, roleCode, reason, validUntil, granterSubject);
        return Outcome.granted(grantId);
    }

    @Transactional
    public Outcome revoke(UUID grantId, String revokerSubject, String reason) {
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.IAM_PLATFORM_GRANT_MANAGE.code(),
                parametersHash("revoke", grantId.toString(), null, reason, null),
                ResourceScope.platform(),
                ActorRef.user(revokerSubject, null),
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));

        if (!approval.mayProceed()) {
            return Outcome.awaitingApproval(pendingRequestId(approval));
        }
        approval.consume();

        boolean revoked = authority.revoke(grantId, revokerSubject, reason);
        return revoked ? Outcome.revoked(grantId) : Outcome.noChange(grantId);
    }

    public List<PlatformGrantAuthority.PlatformGrantView> list() {
        return authority.list();
    }

    private static String parametersHash(
            String action,
            String subjectOrGrantId,
            @Nullable String roleCode,
            String reason,
            @Nullable Instant validUntil) {
        return ApprovalParameters.none()
                .and("action", action)
                .and("subjectOrGrantId", subjectOrGrantId)
                .and("roleCode", roleCode)
                .and("reason", reason)
                .and("validUntil", validUntil)
                .hash();
    }

    private static @Nullable UUID pendingRequestId(ApprovalOutcome outcome) {
        return outcome instanceof ApprovalOutcome.Pending pending ? pending.requestId() : null;
    }

    public record Outcome(Status status, @Nullable UUID grantId, @Nullable UUID approvalRequestId) {

        public enum Status {
            GRANTED,
            REVOKED,
            NO_CHANGE,
            AWAITING_APPROVAL
        }

        static Outcome granted(UUID grantId) {
            return new Outcome(Status.GRANTED, grantId, null);
        }

        static Outcome revoked(UUID grantId) {
            return new Outcome(Status.REVOKED, grantId, null);
        }

        static Outcome noChange(UUID grantId) {
            return new Outcome(Status.NO_CHANGE, grantId, null);
        }

        static Outcome awaitingApproval(@Nullable UUID requestId) {
            return new Outcome(Status.AWAITING_APPROVAL, null, requestId);
        }
    }
}
