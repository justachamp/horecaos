package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry;
import uz.horecaos.platform.customers.api.CustomerHistorySource;
import uz.horecaos.platform.customers.api.HistoryCursor;
import uz.horecaos.platform.customers.application.ContactAttemptService.ContactAttemptView;
import uz.horecaos.platform.customers.application.LeadService.LeadView;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * One guest's card (ADR 0111 §2, §7, §8): a read-through composition, never a second master.
 *
 * <p>The card is identity from {@code customers}, the guest's leads and voice contacts from
 * {@code customers}' own journals, and everything else -- messages the platform sent, campaign
 * receipts, promotions redeemed, reviews left -- from the module that owns it, through the {@link
 * CustomerHistorySource} that module implements. Nothing here copies another module's data into a
 * {@code customers} table, and nothing here queries another module's table: a source that is not
 * installed is a source that contributes nothing, and the card says so by not listing it.
 *
 * <p><strong>Every open is audited.</strong> Opening the card writes one {@code BUSINESS} fact
 * ({@code customer.card.viewed}: actor, account, purpose) in the same transaction as the read, and
 * the fact is written <em>before</em> the history is gathered, so a read that fails halfway still
 * leaves its trace and a trace is never missing for a read that returned. Until now only the decrypt
 * paths left a record; a plain open left none, which made "who looked at this customer" unanswerable
 * for a view that revealed nothing. The fact carries an id and a purpose and no personal data (ADR
 * 0029).
 *
 * <p><strong>There is no switch.</strong> Whether a compliance-relevant record may ever be turned off
 * by a tenant is the question ADR 0111's first open input puts to counsel; shipping an escape hatch
 * now would presuppose the answer, and a record that proves legally mandatory must never have had
 * one. So the audit is unconditional, and the one test that matters here is that every open writes
 * exactly one fact whatever else is configured.
 */
@Service
public class CustomerCardAssemblyService {

    /** How many of a guest's leads the card lists; the lead queue is the place for the rest. */
    private static final int LEAD_LIMIT = 20;

    private final CustomerProfileService profiles;
    private final CustomerBlacklistService blacklist;
    private final LeadService leads;
    private final ContactAttemptService attempts;
    private final List<CustomerHistorySource> sources;
    private final AuditRecorder audit;
    private final Clock clock;

    public CustomerCardAssemblyService(
            CustomerProfileService profiles,
            CustomerBlacklistService blacklist,
            LeadService leads,
            ContactAttemptService attempts,
            List<CustomerHistorySource> sources,
            AuditRecorder audit,
            Clock clock) {
        this.profiles = profiles;
        this.blacklist = blacklist;
        this.leads = leads;
        this.attempts = attempts;
        this.sources = sources;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Opens one guest's card.
     *
     * @param before  the last entry of the page already shown, to read further back; null for the newest
     * @param purpose why the card was opened, recorded on the fact
     * @throws ApiException {@code RESOURCE_NOT_FOUND} for an account that is not this tenant's -- and
     *                      no fact is written for it, because nothing was read
     */
    @Transactional
    public CustomerCard open(
            UUID tenantId, UUID accountId, @Nullable HistoryCursor before, int limit, String purpose, ActorRef actor) {
        var account = profiles.profile(tenantId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer"));

        Instant now = clock.instant();
        AuditFact.Builder fact = AuditFact.of("customer.card.viewed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("customer_account", accountId)
                .because(purpose)
                .correlatedBy(accountId.toString())
                .occurredAt(now);
        if (actor.type() == ActorRef.Type.USER) {
            fact.usingCapability(Capability.CUSTOMER_READ.code());
        }
        audit.record(fact.build());

        List<ContactAttemptView> voice = attempts.forCustomer(tenantId, accountId, before, limit + 1);
        List<CustomerHistoryEntry> merged = new ArrayList<>();
        for (ContactAttemptView attempt : voice) {
            merged.add(new CustomerHistoryEntry(
                    CustomerHistoryEntry.Kind.VOICE_CONTACT,
                    attempt.occurredAt(),
                    "PHONE",
                    attempt.outcome().name(),
                    attempt.blockingReason() == null
                            ? null
                            : attempt.blockingReason().name(),
                    attempt.id(),
                    null,
                    null,
                    attempt.direction().name()));
        }
        for (CustomerHistorySource source : sources) {
            merged.addAll(source.history(tenantId, accountId, before, limit + 1));
        }
        // By instant and then by id, the order every source answers in, so a page boundary that falls
        // between two entries of one instant is a boundary the next page continues from.
        merged.sort(HistoryCursor.newestFirst());

        boolean more = merged.size() > limit;
        List<CustomerHistoryEntry> page = more ? List.copyOf(merged.subList(0, limit)) : List.copyOf(merged);
        HistoryCursor next = more ? HistoryCursor.after(page.getLast()) : null;

        return new CustomerCard(
                accountId,
                account.status(),
                account.displayName(),
                account.preferredLocale(),
                account.version(),
                blacklist.isCurrentlyBlacklisted(tenantId, accountId),
                leads.forAccount(tenantId, accountId, LEAD_LIMIT),
                page,
                next == null ? null : next.occurredAt(),
                next == null ? null : next.referenceId());
    }

    /**
     * @param blacklisted whether an entry is in force right now, so an operator is warned before she calls
     * @param nextBefore  the instant to pass as {@code before} for the next older page, or null at the end
     * @param nextBeforeId the id to pass as {@code beforeId} with it: the last entry's id, so entries that share
     *                     its instant and did not fit are on the next page rather than lost
     */
    public record CustomerCard(
            UUID customerAccountId,
            String status,
            @Nullable String displayName,
            @Nullable String preferredLocale,
            int version,
            boolean blacklisted,
            List<LeadView> leads,
            List<CustomerHistoryEntry> history,
            @Nullable Instant nextBefore,
            @Nullable UUID nextBeforeId) {}
}
