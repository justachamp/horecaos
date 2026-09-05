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
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.api.CustomerBlacklistPort;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.BlacklistEntryRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * Blacklist/suppression, with a reason, an actor, and an expiry (frontend
 * information architecture §5.2).
 *
 * <p>At most one {@code ACTIVE} entry per account. Adding a second while one is
 * already active is refused rather than silently superseding it: the previous
 * entry's actor and reason are evidence of a decision somebody made, and letting
 * a later insert bury them would make the history lie about what was ever true
 * at once.
 *
 * <p>Two enforcement points read this. {@link CustomerIdentityService#resolve}
 * refuses a blacklisted principal a session at sign-in — the one place in this
 * codebase where a returning customer's principal becomes a durable account
 * (see that method's own doc). That does not cover a principal already holding
 * a session minted before the entry was added, so {@link
 * uz.horecaos.platform.customers.api.CustomerBlacklistPort} exposes the same
 * read to ordering's cart-creation and checkout paths — the actual moments an
 * order is filled and taken. Neither caller sees a reason or an actor: both
 * are checking a gate, not looking at a customer's record, and {@link
 * #isCurrentlyBlacklisted} is deliberately audit-free for the same reason its
 * own doc gives.
 */
@Service
public class CustomerBlacklistService implements CustomerBlacklistPort {

    private static final String TABLE = "customer.blacklist_entries";

    private final JdbcCustomerStore store;
    private final FieldProtection protection;
    private final Clock clock;
    private final AuditRecorder audit;

    public CustomerBlacklistService(
            JdbcCustomerStore store, FieldProtection protection, Clock clock, AuditRecorder audit) {
        this.store = store;
        this.protection = protection;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Adds a blacklist entry, refusing a second one while one is already active.
     *
     * @param actor who is blacklisting the account — always a {@code USER} in
     *              practice, since {@code CustomerController} gates this on
     *              {@code CUSTOMER_MANAGE}, but the actor is taken rather than
     *              assumed for the same reason {@code CustomerProfileService}
     *              takes one
     * @throws AlreadyBlacklistedException when an {@code ACTIVE} entry already
     *              exists, expired or not — an expired-but-unlifted entry is
     *              still the record of a decision nobody has revisited, and
     *              {@link #lift} is how an operator revisits it
     */
    @Transactional
    public UUID add(UUID tenantId, UUID accountId, String reason, @Nullable Instant expiresAt, ActorRef actor) {
        if (!store.accountExists(tenantId, accountId)) {
            throw new IllegalArgumentException("No such customer account in this tenant");
        }
        boolean alreadyActive =
                store.blacklistHistory(tenantId, accountId).stream().anyMatch(row -> "ACTIVE".equals(row.status()));
        if (alreadyActive) {
            throw new AlreadyBlacklistedException();
        }

        UUID entryId = UUID.randomUUID();
        Instant now = clock.instant();
        ProtectedValue encrypted = protection.protect(
                tenantId, DataClass.PERSONAL, new RecordRef(TABLE, "reason_encrypted", entryId), reason);

        store.insertBlacklistEntry(
                entryId,
                tenantId,
                accountId,
                encrypted.serialize(),
                actor.type().name(),
                actor.subject(),
                expiresAt,
                now);

        recordFact(
                "customer.blacklist.added",
                "Operator recorded a customer blacklist entry",
                tenantId,
                accountId,
                actor,
                Map.of("entryId", entryId.toString()));
        return entryId;
    }

    /**
     * Lifts the account's one active entry.
     *
     * @throws NoActiveEntryException when there is nothing to lift
     */
    @Transactional
    public void lift(UUID tenantId, UUID accountId, @Nullable String liftReason, ActorRef actor) {
        BlacklistEntryRow active = store.blacklistHistory(tenantId, accountId).stream()
                .filter(row -> "ACTIVE".equals(row.status()))
                .findFirst()
                .orElseThrow(NoActiveEntryException::new);

        String encryptedLiftReason = liftReason == null
                ? null
                : protection
                        .protect(
                                tenantId,
                                DataClass.PERSONAL,
                                new RecordRef(TABLE, "lift_reason_encrypted", active.id()),
                                liftReason)
                        .serialize();

        int written = store.liftActiveBlacklistEntry(
                tenantId, accountId, actor.type().name(), actor.subject(), encryptedLiftReason, clock.instant());
        if (written == 0) {
            // Raced: another lift (or the entry's own natural conditions) won
            // between the read above and this write. Reported the same way as
            // never having found one — there is nothing left to lift either way.
            throw new NoActiveEntryException();
        }

        recordFact(
                "customer.blacklist.lifted",
                "Operator lifted a customer blacklist entry",
                tenantId,
                accountId,
                actor,
                Map.of("entryId", active.id().toString()));
    }

    /**
     * Whether checkout should refuse this account right now, and until when.
     *
     * <p>No reason, no reveal, no audit fact: this is the enforcement read, called
     * on every sign-in resolution, and a purpose-audited decrypt on a path with no
     * stated purpose would misrepresent what the caller is doing — the caller is
     * not choosing to look at a customer's record, it is checking a gate.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isCurrentlyBlacklisted(UUID tenantId, UUID accountId) {
        return store.isCurrentlyBlacklisted(tenantId, accountId, clock.instant());
    }

    /**
     * The current state without decrypting anything — whether an entry is
     * active right now, and when it expires — for a screen that has not asked
     * to reveal the reason. Mirrors {@code CustomerProfileService#contactPointSummaries}'s
     * existence-without-reveal split.
     */
    @Transactional(readOnly = true)
    public BlacklistStatus status(UUID tenantId, UUID accountId) {
        Instant now = clock.instant();
        return store.blacklistHistory(tenantId, accountId).stream()
                .filter(row -> "ACTIVE".equals(row.status()))
                .findFirst()
                .map(row -> new BlacklistStatus(
                        true,
                        row.expiresAt() != null && !row.expiresAt().isAfter(now),
                        row.expiresAt(),
                        row.createdAt()))
                .orElseGet(() -> new BlacklistStatus(false, false, null, null));
    }

    /**
     * The decrypted history — every entry, oldest reason and lift reason
     * revealed together. One audit fact for the whole call, per ADR 0027 and
     * the same reasoning {@code CustomerProfileService#revealAddresses} gives:
     * this is one customer's own history, not a tenant-wide list, so revealing
     * all of it in one call is not the "list-wide decrypt" this section refuses.
     */
    @Transactional
    public List<RevealedEntry> revealHistory(UUID tenantId, UUID accountId, String purpose, ActorRef actor) {
        List<BlacklistEntryRow> rows = store.blacklistHistory(tenantId, accountId);
        AuditFact.Builder fact = AuditFact.of("customer.blacklist.revealed", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("customer_account", accountId)
                .because(purpose)
                .changed(Map.of("revealedCount", rows.size()))
                .correlatedBy(accountId.toString())
                .occurredAt(clock.instant());
        audit.record(fact.build());

        return rows.stream()
                .map(row -> new RevealedEntry(
                        row.id(),
                        protection.reveal(
                                tenantId,
                                ProtectedValue.deserialize(row.reasonEncrypted()),
                                new RecordRef(TABLE, "reason_encrypted", row.id()),
                                purpose),
                        row.status(),
                        row.actorType(),
                        row.actorId(),
                        row.createdAt(),
                        row.expiresAt(),
                        row.liftedAt(),
                        row.liftedByActorId(),
                        row.liftReasonEncrypted() == null
                                ? null
                                : protection.reveal(
                                        tenantId,
                                        ProtectedValue.deserialize(row.liftReasonEncrypted()),
                                        new RecordRef(TABLE, "lift_reason_encrypted", row.id()),
                                        purpose)))
                .toList();
    }

    /**
     * @param auditReason a fixed, non-personal description — never the operator's
     *                    own blacklist reason, which stays inside {@code
     *                    reason_encrypted} and never reaches an unencrypted audit
     *                    row (ADR 0029). {@link AuditFact}'s own canonical
     *                    constructor requires a reason for a {@code USER} actor,
     *                    which is what this satisfies.
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
                .target("customer_account", accountId)
                .because(auditReason)
                .changed(changed)
                .correlatedBy(accountId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    public record BlacklistStatus(
            boolean active,
            boolean expired,
            @Nullable Instant expiresAt,
            @Nullable Instant since) {}

    public record RevealedEntry(
            UUID id,
            String reason,
            String status,
            String actorType,
            String actorId,
            Instant createdAt,
            @Nullable Instant expiresAt,
            @Nullable Instant liftedAt,
            @Nullable String liftedByActorId,
            @Nullable String liftReason) {}

    public static class AlreadyBlacklistedException extends RuntimeException {
        public AlreadyBlacklistedException() {
            super("This account already has an active blacklist entry; lift it before adding another");
        }
    }

    public static class NoActiveEntryException extends RuntimeException {
        public NoActiveEntryException() {
            super("This account has no active blacklist entry to lift");
        }
    }
}
