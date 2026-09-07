package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AccountRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AddressRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ContactPointRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ErasureRequestRow;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;

/**
 * The data-subject erasure request ADR 0029 named as missing everywhere in this
 * codebase, and ADR 0044 hit from the marketing side the same day: "no
 * data-subject erasure request table, endpoint, or account-status transition
 * anywhere in this codebase". This service is that request's whole lifecycle —
 * raised, executed, or cancelled — and the one place {@code
 * customer.customer_accounts.status} is ever set to {@code ANONYMIZED}.
 *
 * <p><strong>Raising and executing are deliberately two different acts.</strong>
 * ADR 0044's own words for why: until a sweep exists to consume a worklist of
 * requests, execution "stays a manual, tested operation". A customer or an
 * operator raising a request records intent; a second, capability-gated,
 * explicitly audited call is what actually anonymises an account. This is not
 * an oversight to close later — a real erasure needs to check things this wave
 * does not build (an open dispute, a legal hold, a pending delivery), and a
 * button that fired instantly would be doing that checking nowhere at all.
 *
 * <p><strong>What execution erases, and why not more.</strong> ADR 0029's own
 * decision is anonymisation, not key destruction: "protected values are
 * overwritten while order totals and settlement facts stay reconcilable, so
 * financial history survives and the person does not". This service overwrites
 * exactly the protected fields the {@code customer} schema owns —
 * {@link JdbcCustomerStore#anonymizeAccount the account's own display name and
 * date of birth}, {@link JdbcCustomerStore#eraseContactPoint every contact
 * point}, and {@link JdbcCustomerStore#eraseAddress every address, active or
 * archived} — and severs {@link JdbcCustomerStore#unlinkPrincipalLinks every
 * principal link}, so a later sign-in cannot reattach to the account it just
 * anonymised. Nothing outside the {@code customer} schema is touched here:
 * {@code brand_profiles} (order-count and spend aggregates, first/last order
 * timestamps) and {@code consent_decisions} (the append-only evidence of what
 * someone agreed to and when) are left exactly as they were, because a
 * financial or evidentiary record surviving the person is the point, not a
 * gap. {@code blacklist_entries.reason_encrypted} is a known, named exception
 * left untouched by this wave — see this record's own ADR 0029 status update.
 *
 * <p><strong>Other modules reach in through {@link CustomerErasureParticipant}.</strong>
 * Nothing implements it yet; see that interface's own doc for why that is
 * expected rather than a bug, and for how {@code marketing}'s two already-built
 * and already-tested erase operations are meant to arrive.
 */
@Service
public class CustomerErasureService {

    private static final String ACCOUNT_TABLE = "customer.customer_accounts";
    private static final String CONTACT_TABLE = "customer.contact_points";
    private static final String ADDRESS_TABLE = "customer.addresses";

    /**
     * What a tombstoned protected value decrypts to, if anything ever reveals one
     * again. Not blank: an empty string is indistinguishable from "this field was
     * always empty", and the whole point of a distinct marker is that a reveal
     * after erasure should read as exactly what it is rather than as a data
     * quality bug somebody investigates.
     */
    private static final String TOMBSTONE = "ERASED";

    private final JdbcCustomerStore store;
    private final FieldProtection protection;
    private final Clock clock;
    private final AuditRecorder audit;
    private final List<CustomerErasureParticipant> participants;

    public CustomerErasureService(
            JdbcCustomerStore store,
            FieldProtection protection,
            Clock clock,
            AuditRecorder audit,
            List<CustomerErasureParticipant> participants) {
        this.store = store;
        this.protection = protection;
        this.clock = clock;
        this.audit = audit;
        this.participants = participants;
    }

    /**
     * Raises a request, or returns the one already outstanding.
     *
     * <p>Idempotent two ways over. A caller that retries an identical HTTP call
     * gets the ADR 0031 {@code Idempotency-Key} replay before this method is ever
     * reached; a caller that raises a second, distinct request while the first is
     * still {@code PENDING} lands here and gets the same row back rather than a
     * second worklist entry — {@code ux_erasure_request_pending} is what makes
     * that safe under a race, and a losing insert is read back rather than
     * treated as a failure. An account already {@code ANONYMIZED} answers with
     * its own completed request: there is nothing left to erase, and creating a
     * new {@code PENDING} row over an account with no protected data left would
     * be a request that can never be honestly executed.
     *
     * @throws AccountNotFoundException  when the account is not this tenant's
     * @throws NoCompletedRequestException when the account is anonymised but no
     *                                    completed request explains it — only
     *                                    reachable if something outside this
     *                                    service ever writes {@code ANONYMIZED},
     *                                    which nothing does
     */
    @Transactional
    public ErasureRequestRow request(UUID tenantId, UUID accountId, RequestedVia via, ActorRef actor) {
        AccountRow account = store.account(tenantId, accountId).orElseThrow(AccountNotFoundException::new);

        if ("ANONYMIZED".equals(account.status())) {
            return store.latestCompletedErasureRequest(tenantId, accountId)
                    .orElseThrow(NoCompletedRequestException::new);
        }

        var existing = store.pendingErasureRequest(tenantId, accountId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID requestId = UUID.randomUUID();
        Instant now = clock.instant();
        boolean created = store.insertErasureRequestIfNonePending(
                requestId, tenantId, accountId, via.name(), actor.type().name(), actor.subject(), now);
        if (!created) {
            // Raced against a concurrent raise that won first: its row is what
            // the caller should see, not an error about a race they cannot do
            // anything about — the same idempotent-under-retry contract as
            // finding one already PENDING above.
            return store.pendingErasureRequest(tenantId, accountId).orElseThrow(NoCompletedRequestException::new);
        }

        recordFact(
                "customer.erasure.requested",
                requestReasonFor(actor),
                tenantId,
                accountId,
                actor,
                Map.of("requestId", requestId.toString(), "requestedVia", via.name()));

        return store.erasureRequest(tenantId, requestId).orElseThrow(NoCompletedRequestException::new);
    }

    /**
     * One account's request history, newest first.
     *
     * @throws AccountNotFoundException when the account is not this tenant's
     */
    @Transactional(readOnly = true)
    public List<ErasureRequestRow> history(UUID tenantId, UUID accountId) {
        if (!store.accountExists(tenantId, accountId)) {
            throw new AccountNotFoundException();
        }
        return store.erasureRequestHistory(tenantId, accountId);
    }

    /**
     * The account's current request — the outstanding {@code PENDING} one if
     * there is one, else the most recent request of any status.
     *
     * <p>What the storefront's own settings screen reads to show "erasure
     * pending since ..." or nothing at all.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ErasureRequestRow> current(UUID tenantId, UUID accountId) {
        var pending = store.pendingErasureRequest(tenantId, accountId);
        if (pending.isPresent()) {
            return pending;
        }
        return store.erasureRequestHistory(tenantId, accountId).stream().findFirst();
    }

    /**
     * Performs the erasure: anonymises the account, overwrites its protected
     * fields, severs its principal links, calls every registered {@link
     * CustomerErasureParticipant}, and marks the request {@code COMPLETED}.
     *
     * <p>Idempotent under retry. A request already {@code COMPLETED} returns its
     * own row unchanged rather than erasing a second time — there is nothing
     * left to overwrite, and re-running the participant calls against an
     * already-erased account is exactly the kind of "erase twice" ADR 0044 warns
     * a scheduled sweep must not do blindly, so this service does not do it
     * either. A concurrent second execution of the same {@code PENDING} request
     * races on {@link JdbcCustomerStore#completeErasureRequest}: the loser sees
     * zero rows written, re-reads, and returns the winner's completed row
     * without touching anything twice.
     *
     * @throws NoSuchErasureRequestException when the id is not this account's
     * @throws ErasureRequestCancelledException when the request was withdrawn
     * @throws MergedAccountException  when the account has been merged away —
     *                                 erasing a redirect row erases nothing;
     *                                 {@link MergedAccountException#survivingAccountId()}
     *                                 names the account a new request should
     *                                 target instead
     */
    @Transactional
    public ErasureRequestRow execute(UUID tenantId, UUID accountId, UUID requestId, ActorRef actor) {
        ErasureRequestRow request = store.erasureRequest(tenantId, requestId)
                .filter(row -> row.customerAccountId().equals(accountId))
                .orElseThrow(NoSuchErasureRequestException::new);

        if ("COMPLETED".equals(request.status())) {
            return request;
        }
        if ("CANCELLED".equals(request.status())) {
            throw new ErasureRequestCancelledException();
        }

        AccountRow account = store.account(tenantId, accountId).orElseThrow(AccountNotFoundException::new);
        if ("MERGED".equals(account.status())) {
            throw new MergedAccountException(store.resolveMergeTarget(tenantId, accountId));
        }

        Instant now = clock.instant();
        int completed =
                store.completeErasureRequest(tenantId, requestId, actor.type().name(), actor.subject(), now);
        if (completed == 0) {
            // Raced: another execution already moved this request off PENDING.
            // Its result is authoritative, not this call's own view of failure.
            return store.erasureRequest(tenantId, requestId).orElseThrow(NoSuchErasureRequestException::new);
        }

        if (!"ANONYMIZED".equals(account.status())) {
            anonymize(tenantId, accountId, now);
        }
        for (CustomerErasureParticipant participant : participants) {
            participant.erase(tenantId, accountId);
        }

        recordFact(
                "customer.erasure.completed",
                "Erasure executed: the account was anonymised and its protected fields overwritten",
                tenantId,
                accountId,
                actor,
                Map.of("requestId", requestId.toString()));

        return store.erasureRequest(tenantId, requestId).orElseThrow(NoSuchErasureRequestException::new);
    }

    private void anonymize(UUID tenantId, UUID accountId, Instant now) {
        store.anonymizeAccount(tenantId, accountId, now);

        for (ContactPointRow contact : store.contactPoints(tenantId, accountId)) {
            String tombstonedValue = protection
                    .protect(
                            tenantId,
                            DataClass.PERSONAL,
                            new RecordRef(CONTACT_TABLE, "encrypted_value", contact.id()),
                            TOMBSTONE)
                    .serialize();
            // A fresh random value, not a re-hash of TOMBSTONE: a deterministic
            // hash of a fixed marker would let every erased contact of the same
            // type in the same tenant collide on one lookup value, which is a
            // smaller but real version of the same leak the keyed hash exists to
            // prevent — a lookup would confirm "these rows both used to hold a
            // phone number" even with the ciphertext gone.
            store.eraseContactPoint(
                    tenantId, contact.id(), tombstonedValue, UUID.randomUUID().toString());
        }

        for (AddressRow address : store.allAddresses(tenantId, accountId)) {
            String tombstonedFields = protection
                    .protect(
                            tenantId,
                            DataClass.PERSONAL,
                            new RecordRef(ADDRESS_TABLE, "encrypted_fields", address.id()),
                            TOMBSTONE)
                    .serialize();
            String tombstonedInstructions = address.encryptedInstructions() == null
                    ? null
                    : protection
                            .protect(
                                    tenantId,
                                    DataClass.PERSONAL,
                                    new RecordRef(ADDRESS_TABLE, "delivery_instructions_encrypted", address.id()),
                                    TOMBSTONE)
                            .serialize();
            store.eraseAddress(tenantId, address.id(), TOMBSTONE, tombstonedFields, tombstonedInstructions);
        }

        store.unlinkPrincipalLinks(tenantId, accountId, now);
    }

    /**
     * Withdraws a {@code PENDING} request.
     *
     * <p>Idempotent for a request already {@code CANCELLED}; refused for one
     * already {@code COMPLETED}, because there is no undoing an erasure that has
     * already happened and reporting success would misstate what this call did.
     *
     * @throws NoSuchErasureRequestException when the id is not this account's
     * @throws ErasureRequestCompletedException when the request already executed
     */
    @Transactional
    public ErasureRequestRow cancel(UUID tenantId, UUID accountId, UUID requestId, ActorRef actor) {
        ErasureRequestRow request = store.erasureRequest(tenantId, requestId)
                .filter(row -> row.customerAccountId().equals(accountId))
                .orElseThrow(NoSuchErasureRequestException::new);

        if ("CANCELLED".equals(request.status())) {
            return request;
        }
        if ("COMPLETED".equals(request.status())) {
            throw new ErasureRequestCompletedException();
        }

        Instant now = clock.instant();
        int cancelled =
                store.cancelErasureRequest(tenantId, requestId, actor.type().name(), actor.subject(), now);
        if (cancelled == 0) {
            // Raced against a concurrent execute() or cancel(): whichever won is
            // authoritative.
            return store.erasureRequest(tenantId, requestId).orElseThrow(NoSuchErasureRequestException::new);
        }

        recordFact(
                "customer.erasure.cancelled",
                cancelReasonFor(actor),
                tenantId,
                accountId,
                actor,
                Map.of("requestId", requestId.toString()));

        return store.erasureRequest(tenantId, requestId).orElseThrow(NoSuchErasureRequestException::new);
    }

    /**
     * @param auditReason a fixed, non-personal description, never anything a
     *                    person typed — see {@code CustomerBlacklistService}'s
     *                    own identical note. {@link AuditFact}'s canonical
     *                    constructor requires one for a {@code USER} actor.
     */
    private void recordFact(
            String actionCode,
            String auditReason,
            UUID tenantId,
            UUID accountId,
            ActorRef actor,
            Map<String, Object> changed) {
        audit.record(AuditFact.of(actionCode, AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because(auditReason)
                .changed(changed)
                .correlatedBy(accountId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    private static String requestReasonFor(ActorRef actor) {
        return actor.type() == ActorRef.Type.USER
                ? "Operator recorded a data-subject erasure request on the customer's behalf"
                : "Customer requested erasure of their own account";
    }

    private static String cancelReasonFor(ActorRef actor) {
        return actor.type() == ActorRef.Type.USER
                ? "Operator withdrew a data-subject erasure request"
                : "Customer withdrew their own erasure request";
    }

    /** Where a request was raised — a report's own "how many did staff file" split. */
    public enum RequestedVia {
        STOREFRONT,
        OPERATIONS
    }

    /** No such account in this tenant. */
    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException() {
            super("No such customer account");
        }
    }

    /** No such erasure request for this account in this tenant. */
    public static class NoSuchErasureRequestException extends RuntimeException {
        public NoSuchErasureRequestException() {
            super("No such erasure request");
        }
    }

    /** The request was withdrawn and cannot be executed. */
    public static class ErasureRequestCancelledException extends RuntimeException {
        public ErasureRequestCancelledException() {
            super("This erasure request was cancelled and cannot be executed");
        }
    }

    /** The request already executed and cannot be cancelled. */
    public static class ErasureRequestCompletedException extends RuntimeException {
        public ErasureRequestCompletedException() {
            super("This erasure request already completed and cannot be cancelled");
        }
    }

    /** An account already anonymised carries no completed request to point to — a programming error, not a caller mistake. */
    public static class NoCompletedRequestException extends RuntimeException {
        public NoCompletedRequestException() {
            super("This account is anonymised but no completed erasure request explains it");
        }
    }

    /** The account is a merge redirect; erase {@link #survivingAccountId()} instead. */
    public static class MergedAccountException extends RuntimeException {
        private final UUID survivingAccountId;

        public MergedAccountException(UUID survivingAccountId) {
            super("This account was merged into another; erase the surviving account instead");
            this.survivingAccountId = survivingAccountId;
        }

        public UUID survivingAccountId() {
            return survivingAccountId;
        }
    }
}
