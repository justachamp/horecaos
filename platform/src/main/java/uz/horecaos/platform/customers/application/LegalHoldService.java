package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLegalHoldStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLegalHoldStore.HoldRow;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Legal holds on named customer accounts (ADR 0029: "legal hold"; ADR 0027 for the second signature).
 *
 * <p>A hold says that a customer's data has to be kept because the tenant is, or may be, obliged to produce it. While
 * one stands, {@code CustomerErasureService.execute} refuses the account and the retention jobs that expire a
 * customer's data (carts, conversation history, audience memberships) leave her alone.
 *
 * <p><strong>Both directions are a second person's decision.</strong> Placing a hold is a way to refuse a data
 * subject's request without it being seen, and lifting one is a way to destroy what a claim needed; neither is
 * safe on one person's word, so each asks the ADR 0027 approval model first and does nothing until it says yes.
 * The first call answers {@link Result.Awaiting} and names the request; once a second person has approved it the
 * identical call goes through, and the approval is spent in the same transaction as the change (the model's
 * single-use rule).
 *
 * <p>No free text is stored on the hold. The note the maker typed belongs to the approval request, which is the
 * record a checker reads and an auditor asks for; the hold row carries the account, a closed reason code and the
 * people involved.
 */
@Service
public class LegalHoldService {

    /** Why a hold is placed; a closed set so the console can offer it and a report can count it. */
    public enum Reason {
        LITIGATION,
        REGULATORY_REQUEST,
        INVESTIGATION,
        OTHER
    }

    /** The hold that was placed or lifted, or the approval it is waiting for. */
    public sealed interface Result {

        /** The change was made. */
        record Done(HoldRow hold) implements Result {}

        /** A second person has to decide first, or said no. Nothing else happened. */
        record Awaiting(ApprovalOutcome outcome) implements Result {}
    }

    /** What the second person signs when a hold is placed. Public so the hash can read it. */
    public record PlaceProposal(UUID tenantId, UUID customerAccountId, String reasonCode) {}

    /** What the second person signs when a hold is lifted. Public so the hash can read it. */
    public record LiftProposal(UUID tenantId, UUID customerAccountId, UUID holdId) {}

    private static final String ACCOUNT_TABLE = "customer.customer_accounts";

    private final JdbcLegalHoldStore holds;
    private final JdbcCustomerStore accounts;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;

    public LegalHoldService(
            JdbcLegalHoldStore holds,
            JdbcCustomerStore accounts,
            ApprovalService approvals,
            AuditRecorder audit,
            Clock clock) {
        this.holds = holds;
        this.accounts = accounts;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Places a hold, or returns the one that already stands.
     *
     * @throws CustomerErasureService.AccountNotFoundException when the account is not this tenant's
     */
    @Transactional
    public Result place(UUID tenantId, UUID accountId, Reason reason, String note, ActorRef actor) {
        if (!accounts.accountExists(tenantId, accountId)) {
            throw new CustomerErasureService.AccountNotFoundException();
        }

        var standing = holds.active(tenantId, accountId);
        if (standing.isPresent()) {
            // The same hold, not a second one, and nothing to approve: it is already in force.
            return new Result.Done(standing.get());
        }

        ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.CUSTOMER_LEGAL_HOLD_PLACE.code(),
                ApprovalParameters.of(new PlaceProposal(tenantId, accountId, reason.name()))
                        .excluding()
                        .hash(),
                ResourceScope.tenant(tenantId),
                actor,
                note,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        if (!outcome.mayProceed()) {
            return new Result.Awaiting(outcome);
        }
        // Spent before the hold is written, so one signature is one hold.
        outcome.consume();
        UUID approvalId = approvalIdOf(outcome);

        Instant now = clock.instant();
        UUID holdId = Ids.newId();
        if (!holds.insertIfNoneActive(
                holdId, tenantId, accountId, reason.name(), actor.type().name(), actor.subject(), approvalId, now)) {
            // Lost a race to a concurrent placement: its hold is the hold.
            return new Result.Done(holds.active(tenantId, accountId).orElseThrow());
        }

        audit.record(AuditFact.of("customer.legal-hold.placed", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because(note)
                .underApproval(approvalId)
                // Staff 9.3a: a brand-new hold, no prior state to diff against.
                .changed(ChangeDocuments.created(
                        Map.of("holdId", holdId.toString(), "reasonCode", reason.name(), "status", "ACTIVE")))
                .correlatedBy(accountId.toString())
                .occurredAt(now)
                .build());
        return new Result.Done(holds.find(tenantId, holdId).orElseThrow());
    }

    /**
     * Lifts a hold. Idempotent for a hold already lifted.
     *
     * @throws NoSuchHoldException when the hold is not this account's
     */
    @Transactional
    public Result lift(UUID tenantId, UUID accountId, UUID holdId, String note, ActorRef actor) {
        HoldRow hold = holds.find(tenantId, holdId)
                .filter(row -> row.customerAccountId().equals(accountId))
                .orElseThrow(NoSuchHoldException::new);
        if ("LIFTED".equals(hold.status())) {
            return new Result.Done(hold);
        }

        ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.CUSTOMER_LEGAL_HOLD_LIFT.code(),
                ApprovalParameters.of(new LiftProposal(tenantId, accountId, holdId))
                        .excluding()
                        .hash(),
                ResourceScope.tenant(tenantId),
                actor,
                note,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        if (!outcome.mayProceed()) {
            return new Result.Awaiting(outcome);
        }
        outcome.consume();
        UUID approvalId = approvalIdOf(outcome);

        Instant now = clock.instant();
        if (holds.lift(tenantId, holdId, actor.type().name(), actor.subject(), approvalId, now) == 0) {
            return new Result.Done(holds.find(tenantId, holdId).orElseThrow());
        }

        audit.record(AuditFact.of("customer.legal-hold.lifted", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because(note)
                .underApproval(approvalId)
                .changed(ChangeDocuments.diff(
                        Map.of("holdId", holdId.toString(), "status", "ACTIVE"),
                        Map.of("holdId", holdId.toString(), "status", "LIFTED")))
                .correlatedBy(accountId.toString())
                .occurredAt(now)
                .build());
        return new Result.Done(holds.find(tenantId, holdId).orElseThrow());
    }

    /**
     * One account's holds, newest first.
     *
     * @throws CustomerErasureService.AccountNotFoundException when the account is not this tenant's
     */
    @Transactional(readOnly = true)
    public List<HoldRow> history(UUID tenantId, UUID accountId) {
        if (!accounts.accountExists(tenantId, accountId)) {
            throw new CustomerErasureService.AccountNotFoundException();
        }
        return holds.history(tenantId, accountId);
    }

    /** Whether a hold stands on the account right now. */
    @Transactional(readOnly = true)
    public boolean isHeld(UUID tenantId, UUID accountId) {
        return holds.isHeld(tenantId, accountId);
    }

    private static @Nullable UUID approvalIdOf(ApprovalOutcome outcome) {
        return outcome instanceof ApprovalOutcome.Approved approved ? approved.requestId() : null;
    }

    /** No such hold for this account in this tenant. */
    public static class NoSuchHoldException extends RuntimeException {
        public NoSuchHoldException() {
            super("No such legal hold");
        }
    }
}
