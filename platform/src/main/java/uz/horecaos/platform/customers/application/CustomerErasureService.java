package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerErasureStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AccountRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AddressRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ContactPointRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ErasureRequestRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLegalHoldStore;
import uz.horecaos.platform.customers.spi.CustomerErasureGuard;
import uz.horecaos.platform.customers.spi.CustomerErasureGuard.Block;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant.ErasedContacts;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
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
 * explicitly audited call is what actually anonymises an account. A real erasure
 * has to check things a button that fired instantly would be checking nowhere:
 * {@link #execute} refuses while a legal hold stands ({@link LegalHoldService}),
 * while an order the customer still expects is on its way, and while a
 * service-recovery case (the platform's dispute) is open. The refusal changes
 * nothing, and the request stays {@code PENDING} until what was in the way is gone.
 *
 * <p><strong>What execution erases, and why not more.</strong> ADR 0029's own
 * decision is anonymisation, not key destruction: "protected values are
 * overwritten while order totals and settlement facts stay reconcilable, so
 * financial history survives and the person does not". This service overwrites
 * exactly the protected fields the {@code customer} schema owns -- the account's own
 * display name and date of birth, every contact point, every address (active or
 * archived) -- and severs every principal link, so a later sign-in cannot reattach
 * to the account it just anonymised. {@code brand_profiles} (order-count and spend
 * aggregates, first/last order timestamps) and {@code consent_decisions} (the
 * append-only evidence of what someone agreed to and when) are left exactly as
 * they were, because a financial or evidentiary record surviving the person is the
 * point, not a gap.
 *
 * <p><strong>Everything else reaches in through {@link CustomerErasureParticipant}.</strong>
 * A module that keeps a copy of the customer registers a participant, called in this
 * transaction, and the counts each one reports are stored on the request as its proof
 * ({@link #proof}). {@code ErasureInventoryTests} reads the database catalog for every
 * table that carries a customer key or a PERSONAL column and requires a participant
 * or a reasoned exemption for each, so a module that starts storing a phone number
 * cannot do it silently. The blacklist reason, once a named exception here, is
 * erased by {@code CustomerRecordsErasureParticipant}.
 */
@Service
public class CustomerErasureService {

    /**
     * The tables this service erases itself, as {@code schema.table}: the account, its contact points and addresses,
     * and the principal links that let a sign-in reattach. The inventory guard reads them as the customers module's
     * own participant.
     */
    public static final List<String> OWN_TABLES = List.of(
            "customer.customer_accounts", "customer.contact_points", "customer.addresses", "customer.principal_links");

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

    /** The code the legal-hold guard reports under; the console maps it to words. */
    public static final String LEGAL_HOLD_BLOCK = "LEGAL_HOLD";

    private static final int PROOF_VERSION = 1;

    private final JdbcCustomerStore store;
    private final JdbcCustomerErasureStore erasures;
    private final JdbcLegalHoldStore holds;
    private final FieldProtection protection;
    private final Clock clock;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final List<DeclaredParticipant> participants;
    private final List<CustomerErasureGuard> guards;

    public CustomerErasureService(
            JdbcCustomerStore store,
            JdbcCustomerErasureStore erasures,
            JdbcLegalHoldStore holds,
            FieldProtection protection,
            Clock clock,
            AuditRecorder audit,
            ObjectMapper objectMapper,
            List<CustomerErasureParticipant> participants,
            List<CustomerErasureGuard> guards) {
        this.store = store;
        this.erasures = erasures;
        this.holds = holds;
        this.protection = protection;
        this.clock = clock;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.participants =
                participants.stream().map(CustomerErasureService::declared).toList();
        this.guards = List.copyOf(guards);
    }

    /**
     * A participant with the name and tables it declared. A participant that declares nothing cannot be named in
     * the proof, so the service refuses to start with one rather than erase on its behalf and prove nothing.
     */
    private static DeclaredParticipant declared(CustomerErasureParticipant participant) {
        Class<?> type = AopUtils.getTargetClass(participant);
        ErasesCustomerData declaration = AnnotatedElementUtils.findMergedAnnotation(type, ErasesCustomerData.class);
        if (declaration == null) {
            throw new IllegalStateException(
                    type.getName() + " is a CustomerErasureParticipant with no @ErasesCustomerData, so an erasure "
                            + "could not say what it covered (ADR 0029)");
        }
        return new DeclaredParticipant(participant, declaration.name(), List.of(declaration.tables()));
    }

    private record DeclaredParticipant(CustomerErasureParticipant participant, String name, List<String> tables) {}

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

        // Staff 9.3a: a freshly raised request has no prior state.
        recordFact(
                "customer.erasure.requested",
                requestReasonFor(actor),
                tenantId,
                accountId,
                actor,
                Map.of(),
                Map.of("requestId", requestId.toString(), "requestedVia", via.name(), "status", "PENDING"));

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
     * Every erasure request across the whole tenant, newest first — Settings
     * 10.11's tenant-wide worklist. The tenant-scoped backend behind it (raise,
     * history, execute, cancel) has existed since {@code V0178}; the only
     * screen that could show any of it before this wave was the control
     * plane's, which a tenant's own staff cannot reach.
     *
     * @param status one of {@code PENDING}, {@code COMPLETED}, {@code CANCELLED},
     *               or null for every status
     */
    public List<ErasureRequestRow> worklist(UUID tenantId, @Nullable String status, int limit) {
        return store.erasureRequestsForTenant(tenantId, status, limit);
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

        // ADR 0029: refuse while a legal hold stands or a module reports something open (an order on its way, a
        // service-recovery case). Checked before the request leaves PENDING, so a refusal changes nothing and the
        // same request can be executed once whatever was in the way is gone.
        //
        // The check and the erasure are one decision, so a hold being placed must not slip in between them: the lock
        // makes a placement that started first commit first (and be seen below), and one that starts now wait until
        // this transaction ends (JdbcLegalHoldStore#lockHoldDecision). Taken after the cheap refusals above, which
        // need no lock, and before the first read that decides.
        holds.lockHoldDecision(tenantId, accountId);
        List<Block> blocks = collectBlocks(tenantId, accountId);
        if (!blocks.isEmpty()) {
            throw new ErasureBlockedException(blocks);
        }

        Instant now = clock.instant();
        int completed =
                store.completeErasureRequest(tenantId, requestId, actor.type().name(), actor.subject(), now);
        if (completed == 0) {
            // Raced: another execution already moved this request off PENDING.
            // Its result is authoritative, not this call's own view of failure.
            return store.erasureRequest(tenantId, requestId).orElseThrow(NoSuchErasureRequestException::new);
        }

        // Read before anonymize() overwrites them: after it no number is left to match another
        // module's rows against.
        ErasedContacts held = heldContacts(tenantId, accountId);
        List<ParticipantProof> proof = new java.util.ArrayList<>();
        if (!"ANONYMIZED".equals(account.status())) {
            proof.add(new ParticipantProof("customers", anonymize(tenantId, accountId, now)));
        } else {
            proof.add(new ParticipantProof("customers", ownCounts(0, 0, 0, 0)));
        }
        for (DeclaredParticipant declared : participants) {
            ErasureCounts counts = declared.participant().erase(tenantId, accountId, held);
            proof.add(new ParticipantProof(declared.name(), tableCounts(declared.tables(), counts)));
        }
        erasures.recordProof(
                tenantId, requestId, objectMapper.writeValueAsString(new ProofDocument(PROOF_VERSION, proof)));

        // Staff 9.3a: "status" genuinely moves from PENDING to COMPLETED.
        recordFact(
                "customer.erasure.completed",
                "Erasure executed: the account was anonymised and its protected fields overwritten",
                tenantId,
                accountId,
                actor,
                Map.of("requestId", requestId.toString(), "status", "PENDING"),
                Map.of("requestId", requestId.toString(), "status", "COMPLETED"));

        return store.erasureRequest(tenantId, requestId).orElseThrow(NoSuchErasureRequestException::new);
    }

    private ErasedContacts heldContacts(UUID tenantId, UUID accountId) {
        return new ErasedContacts(store.contactPoints(tenantId, accountId).stream()
                .filter(contact -> "PHONE".equals(contact.type()))
                .map(ContactPointRow::normalizedHash)
                .collect(Collectors.toSet()));
    }

    private List<TableCount> anonymize(UUID tenantId, UUID accountId, Instant now) {
        int accounts = store.anonymizeAccount(tenantId, accountId, now);
        int contactPoints = 0;
        int addresses = 0;

        for (ContactPointRow contact : store.contactPoints(tenantId, accountId)) {
            contactPoints++;
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
            addresses++;
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

        int links = store.unlinkPrincipalLinks(tenantId, accountId, now);
        return ownCounts(accounts, contactPoints, addresses, links);
    }

    private static List<TableCount> ownCounts(int accounts, int contactPoints, int addresses, int links) {
        return List.of(
                new TableCount("customer.customer_accounts", accounts),
                new TableCount("customer.contact_points", contactPoints),
                new TableCount("customer.addresses", addresses),
                new TableCount("customer.principal_links", links));
    }

    /**
     * Every table the participant declared, in declaration order, with the rows it reported (0 when it reported
     * none): the proof says "looked, found nothing", which is different from not looking. A table it reported and
     * never declared is added too, so the proof never hides work the declaration forgot.
     */
    private static List<TableCount> tableCounts(List<String> declared, ErasureCounts counts) {
        List<TableCount> rows = new java.util.ArrayList<>();
        for (String table : declared) {
            rows.add(new TableCount(table, counts.rowsByTable().getOrDefault(table, 0)));
        }
        counts.rowsByTable().forEach((table, count) -> {
            if (!declared.contains(table)) {
                rows.add(new TableCount(table, count));
            }
        });
        return rows;
    }

    /**
     * What stands between this account and an erasure: a legal hold, and whatever the registered guards report.
     * Codes and counts only.
     */
    @Transactional(readOnly = true)
    public List<Block> blocksFor(UUID tenantId, UUID accountId) {
        if (!store.accountExists(tenantId, accountId)) {
            throw new AccountNotFoundException();
        }
        return collectBlocks(tenantId, accountId);
    }

    private List<Block> collectBlocks(UUID tenantId, UUID accountId) {
        List<Block> blocks = new java.util.ArrayList<>();
        if (holds.isHeld(tenantId, accountId)) {
            blocks.add(new Block(LEGAL_HOLD_BLOCK, 1));
        }
        for (CustomerErasureGuard guard : guards) {
            blocks.addAll(guard.blocksFor(tenantId, accountId));
        }
        return List.copyOf(blocks);
    }

    /**
     * The evidence an executed request kept: which participants ran and how many rows each changed per table
     * (ADR 0029: "proof: produce evidence of what was erased, when, and by whom"). The when and by whom are the
     * request's own completion columns, returned beside it.
     *
     * @throws NoSuchErasureRequestException when the id is not this account's
     * @throws ProofNotAvailableException    when the request has not completed, or completed before proofs were kept
     */
    @Transactional(readOnly = true)
    public ErasureProof proof(UUID tenantId, UUID accountId, UUID requestId) {
        ErasureRequestRow request = store.erasureRequest(tenantId, requestId)
                .filter(row -> row.customerAccountId().equals(accountId))
                .orElseThrow(NoSuchErasureRequestException::new);
        String json = erasures.proofOf(tenantId, requestId).orElseThrow(ProofNotAvailableException::new);
        ProofDocument document = objectMapper.readValue(json, ProofDocument.class);
        return new ErasureProof(request, document.participants());
    }

    /** One participant's line in the proof. */
    public record ParticipantProof(String name, List<TableCount> tables) {}

    /** Rows changed in one table. */
    public record TableCount(String table, int rows) {}

    /** What is stored on the request. */
    record ProofDocument(int version, List<ParticipantProof> participants) {}

    /** The proof with the request it belongs to. */
    public record ErasureProof(ErasureRequestRow request, List<ParticipantProof> participants) {}

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

        // Staff 9.3a: "status" genuinely moves from PENDING to CANCELLED.
        recordFact(
                "customer.erasure.cancelled",
                cancelReasonFor(actor),
                tenantId,
                accountId,
                actor,
                Map.of("requestId", requestId.toString(), "status", "PENDING"),
                Map.of("requestId", requestId.toString(), "status", "CANCELLED"));

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
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(AuditFact.of(actionCode, AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because(auditReason)
                .changed(ChangeDocuments.diff(before, after))
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

    /** The request has not completed, or completed before proofs were kept, so it has no proof to show. */
    public static class ProofNotAvailableException extends RuntimeException {
        public ProofNotAvailableException() {
            super("This erasure request has no proof: it has not completed, or it completed before proofs were kept");
        }
    }

    /**
     * Something stands between the account and its erasure: a legal hold, an order still to be delivered, a
     * service-recovery case still open. Carries codes and counts only, never a name, a number or an id.
     */
    public static class ErasureBlockedException extends RuntimeException {
        private final List<Block> blocks;

        public ErasureBlockedException(List<Block> blocks) {
            super("This erasure cannot be executed yet: "
                    + blocks.stream().map(Block::code).collect(Collectors.joining(", ")));
            this.blocks = List.copyOf(blocks);
        }

        public List<Block> blocks() {
            return blocks;
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
