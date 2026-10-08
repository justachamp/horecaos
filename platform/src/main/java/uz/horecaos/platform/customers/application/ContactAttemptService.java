package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.domain.BlockingReason;
import uz.horecaos.platform.customers.domain.ContactDirection;
import uz.horecaos.platform.customers.domain.ContactOutcome;
import uz.horecaos.platform.customers.domain.NextAction;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcContactAttemptStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcContactAttemptStore.AttemptRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcContactAttemptStore.NewAttempt;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.LeadRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.Reach;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The voice contact journal (ADR 0111 §8): what an operator did on the telephone, written once.
 *
 * <p>ADR 0064 refused a softphone and a telephony integration, so no provider tells the platform a
 * call happened; an operator does, after the call, and this is where it goes. Both attempt
 * journals -- {@code notifications}' delivery attempts for SMS, push and Telegram, and this one for
 * the voice channel -- carry a blocking reason when an attempt was refused, and neither is mastered
 * twice. This one is voice-only: an in-person conversation at a branch leaves no digital trace to
 * journal truthfully, and a fabricated row would be worse than none.
 *
 * <p><strong>Append-only.</strong> There is no update and no delete anywhere in the path, and none
 * in the grant either: the application role holds {@code INSERT} and {@code SELECT} on the table
 * (V0507). A correction is a new attempt that says what really happened; the first stays, which is
 * the point of a journal. A retried submit under the same {@code attemptId} is one attempt, not two.
 *
 * <p>A row carries ids, codes and instants and never a number, a name or a note, so it is safe on a
 * customer card without a reveal.
 */
@Service
public class ContactAttemptService {

    /** An operator records a call she has just finished: not tomorrow's, and not last quarter's. */
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

    private static final Duration BACKFILL = Duration.ofDays(31);

    private final JdbcContactAttemptStore attempts;
    private final JdbcLeadStore leads;
    private final JdbcCustomerStore customers;
    private final CustomerBlacklistService blacklist;
    private final AuditRecorder audit;
    private final Clock clock;

    public ContactAttemptService(
            JdbcContactAttemptStore attempts,
            JdbcLeadStore leads,
            JdbcCustomerStore customers,
            CustomerBlacklistService blacklist,
            AuditRecorder audit,
            Clock clock) {
        this.attempts = attempts;
        this.leads = leads;
        this.customers = customers;
        this.blacklist = blacklist;
        this.audit = audit;
        this.clock = clock;
    }

    /** A call about a lead, inside the caller's reach. */
    @Transactional
    public ContactAttemptView recordForLead(
            UUID tenantId, Reach reach, UUID leadId, Recording recording, ActorRef actor) {
        LeadRow lead = leads.find(tenantId, leadId)
                .filter(reach::admits)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such lead"));
        return record(tenantId, lead.brandId(), leadId, null, lead.customerAccountId(), recording, actor);
    }

    /** A call about a customer who has no lead of their own -- an account holder phoned, or was phoned. */
    @Transactional
    public ContactAttemptView recordForCustomer(
            UUID tenantId, UUID brandId, UUID accountId, Recording recording, ActorRef actor) {
        if (customers.account(tenantId, accountId).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        }
        if (!leads.brandExists(tenantId, brandId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "No such brand in this company");
        }
        return record(tenantId, brandId, null, accountId, accountId, recording, actor);
    }

    @Transactional(readOnly = true)
    public List<ContactAttemptView> forLead(UUID tenantId, Reach reach, UUID leadId, int limit) {
        if (leads.find(tenantId, leadId).filter(reach::admits).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such lead");
        }
        return attempts.forLead(tenantId, leadId, limit).stream()
                .map(ContactAttemptService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ContactAttemptView> forCustomer(UUID tenantId, UUID accountId, @Nullable Instant before, int limit) {
        return attempts.forAccount(tenantId, accountId, before, limit).stream()
                .map(ContactAttemptService::view)
                .toList();
    }

    private ContactAttemptView record(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID leadId,
            @Nullable UUID subjectAccountId,
            @Nullable UUID blacklistAccountId,
            Recording recording,
            ActorRef actor) {
        Instant now = clock.instant();
        Instant occurredAt = recording.occurredAt() == null ? now : recording.occurredAt();
        if (occurredAt.isAfter(now.plus(FUTURE_SKEW))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A call cannot have happened in the future");
        }
        if (occurredAt.isBefore(now.minus(BACKFILL))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A call older than a month is not something to record by hand");
        }
        boolean blocked = recording.outcome() == ContactOutcome.BLOCKED;
        if (blocked != (recording.blockingReason() != null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A refused attempt names why, and an attempt that was not refused names no reason");
        }
        if (recording.nextActionAt() != null && recording.nextAction() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A next-action time needs a next action");
        }
        if (recording.blockingReason() == BlockingReason.BLACKLISTED
                && (blacklistAccountId == null || !blacklist.isCurrentlyBlacklisted(tenantId, blacklistAccountId))) {
            // The journal is evidence: it does not record a refusal that the platform's own
            // blacklist does not bear out.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "That guest is not blacklisted, so the call was not blocked for it");
        }

        UUID attemptId = recording.attemptId() == null ? Ids.newId() : recording.attemptId();
        NewAttempt attempt = new NewAttempt(
                Ids.newId(),
                tenantId,
                brandId,
                leadId,
                leadId == null ? subjectAccountId : null,
                recording.direction().name(),
                attemptId,
                recording.outcome().name(),
                recording.blockingReason() == null
                        ? null
                        : recording.blockingReason().name(),
                actor.subject(),
                occurredAt,
                recording.nextAction() == null ? null : recording.nextAction().name(),
                recording.nextActionAt());

        if (!attempts.insert(attempt, now)) {
            // The same attempt id again: a retried submit. It is the same attempt only if it is
            // about the same guest; otherwise somebody is reusing an id.
            AttemptRow existing = attempts.byAttemptId(tenantId, attemptId).orElseThrow();
            boolean same = Objects.equals(existing.leadId(), leadId)
                    && Objects.equals(existing.customerAccountId(), attempt.customerAccountId());
            if (!same) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That attempt id belongs to another contact");
            }
            return view(existing);
        }

        UUID targetId = leadId != null ? leadId : Objects.requireNonNull(subjectAccountId);
        AuditFact.Builder fact = AuditFact.of("customer.contact_attempt.recorded", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target(leadId != null ? "customer_lead" : "customer_account", targetId)
                .because("Voice contact recorded: " + recording.outcome().name())
                .changed(ChangeDocuments.created(Map.of(
                        "direction",
                        recording.direction().name(),
                        "outcome",
                        recording.outcome().name())))
                .correlatedBy(attemptId.toString())
                .occurredAt(now);
        if (actor.type() == ActorRef.Type.USER) {
            fact.usingCapability(Capability.CUSTOMER_LEAD_MANAGE.code());
        }
        audit.record(fact.build());
        return view(attempts.byAttemptId(tenantId, attemptId).orElseThrow());
    }

    static ContactAttemptView view(AttemptRow row) {
        return new ContactAttemptView(
                row.id(),
                row.brandId(),
                row.leadId(),
                row.customerAccountId(),
                ContactDirection.valueOf(row.direction()),
                row.attemptId(),
                ContactOutcome.valueOf(row.outcome()),
                row.blockingReason() == null ? null : BlockingReason.valueOf(row.blockingReason()),
                row.operatorActorId(),
                row.occurredAt(),
                row.recordedAt(),
                row.nextAction() == null ? null : NextAction.valueOf(row.nextAction()),
                row.nextActionAt());
    }

    /** What an operator tells the journal. */
    public record Recording(
            ContactDirection direction,
            ContactOutcome outcome,
            @Nullable BlockingReason blockingReason,
            @Nullable UUID attemptId,
            @Nullable Instant occurredAt,
            @Nullable NextAction nextAction,
            @Nullable Instant nextActionAt) {}

    /** One recorded attempt. */
    public record ContactAttemptView(
            UUID id,
            UUID brandId,
            @Nullable UUID leadId,
            @Nullable UUID customerAccountId,
            ContactDirection direction,
            UUID attemptId,
            ContactOutcome outcome,
            @Nullable BlockingReason blockingReason,
            String operatorActorId,
            Instant occurredAt,
            Instant recordedAt,
            @Nullable NextAction nextAction,
            @Nullable Instant nextActionAt) {}
}
