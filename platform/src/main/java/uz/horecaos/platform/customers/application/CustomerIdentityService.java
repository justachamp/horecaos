package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerDirectory;
import uz.horecaos.platform.customers.api.CustomerIdentityPolicy;
import uz.horecaos.platform.customers.application.CustomerPolicyLookup.ResolvedIdentityPolicy;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Resolves an authenticated principal to a durable customer account (ADR 0015).
 *
 * <p>Keycloak authenticates; it does not own the commercial identity. A JWT says
 * who signed in and nothing about their addresses, consent, or history, so a
 * token is never used as a customer record. This service is the one place a
 * subject becomes an account.
 *
 * <p>It resolves on {@code (issuer, subject)} only. Email and phone are contact
 * methods, never identity keys: two people share a household phone, a recycled
 * number changes owner, and imported data is often stale — so matching on one
 * would silently merge two customers into a single account with someone else's
 * order history.
 */
@Service
public class CustomerIdentityService implements CustomerDirectory {

    private static final Logger log = LoggerFactory.getLogger(CustomerIdentityService.class);

    /**
     * The issuer of a customer who signed in by proving a phone number
     * (ADR 0051).
     *
     * <p><strong>A constant and not a property.</strong> Every other issuer on
     * this platform is configuration, and this one is deliberately not: it is
     * written into {@code customer.principal_links.issuer} on every row a
     * phone-proven sign-in creates, so changing it would orphan every one of those
     * accounts at once — every returning customer would look like a first-time
     * customer and get a second, empty account. A value a deployment can set is a
     * value a deployment can set wrongly, and there is no environment in which a
     * different one would be correct.
     *
     * <p>Not a URL, on purpose. The resource server validates exactly one issuer
     * and that one is an HTTPS realm URL; a URN cannot be mistaken for it, cannot
     * be fetched, and cannot appear in a token. So the two subject namespaces
     * cannot collide, which is the property {@code PrincipalCustomer} insists on.
     */
    public static final String PROVEN_NUMBER_ISSUER = "urn:horecaos:customer-identity:proven-phone";

    private final JdbcCustomerStore store;
    private final CustomerPolicyLookup policies;
    private final Clock clock;
    private final CustomerBlacklistService blacklist;
    private final AuditRecorder audit;

    public CustomerIdentityService(
            JdbcCustomerStore store,
            CustomerPolicyLookup policies,
            Clock clock,
            CustomerBlacklistService blacklist,
            AuditRecorder audit) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
        this.blacklist = blacklist;
        this.audit = audit;
    }

    /**
     * Finds the account for this principal, creating one on first sign-in.
     *
     * <p>The one enforcement point for {@link CustomerBlacklistService}
     * (frontend information architecture §5.2): a blacklisted principal is
     * refused resolution rather than handed an account, so nothing downstream —
     * checkout included — ever has to remember to ask. See that service's own
     * doc for why this is the live checkout-adjacent path and ordering's own
     * {@code CustomerDirectory} is not.
     *
     * @param brandId the brand the customer is signing in at. Required even under
     *                {@code TENANT_SHARED}, because a brand profile is created
     *                either way
     * @throws BlacklistedAccountException when the account this principal
     *                resolves to currently carries an active, unexpired
     *                blacklist entry
     */
    @Transactional
    public Resolution resolve(UUID tenantId, UUID brandId, String issuer, String subject) {
        // One clock read for the whole resolution: the policy that governs the
        // account is the policy in effect at the instant the account is created,
        // and reading the clock twice could straddle a governed cutover and write
        // an account stamped with a version that did not decide its partition.
        Instant now = clock.instant();
        ResolvedIdentityPolicy resolved = policies.policyFor(tenantId, now);
        CustomerIdentityPolicy policy = resolved.mode();
        UUID partition = policy.partitionFor(brandId);

        Optional<UUID> existing = store.findLinkedAccount(tenantId, partition, issuer, subject);
        if (existing.isPresent()) {
            UUID accountId = existing.get();
            // A merged account redirects to its target. The source row stays
            // because immutable order snapshots point at it.
            UUID effective = store.resolveMergeTarget(tenantId, accountId);
            requireNotBlacklisted(tenantId, effective);
            ensureBrandProfile(tenantId, brandId, effective);
            return new Resolution(new CustomerAccountRef(effective, tenantId), false, policy);
        }

        // A brand-new account cannot carry a blacklist entry yet — nothing has
        // had the chance to write one — so the check is not repeated here.
        return new Resolution(create(tenantId, brandId, issuer, subject, resolved, partition, now), true, policy);
    }

    private void requireNotBlacklisted(UUID tenantId, UUID accountId) {
        if (blacklist.isCurrentlyBlacklisted(tenantId, accountId)) {
            throw new BlacklistedAccountException();
        }
    }

    /** Refused resolution: the account this principal owns is currently blacklisted. */
    public static class BlacklistedAccountException extends RuntimeException {
        public BlacklistedAccountException() {
            super("This customer account is currently blacklisted");
        }
    }

    private CustomerAccountRef create(
            UUID tenantId,
            UUID brandId,
            String issuer,
            String subject,
            ResolvedIdentityPolicy resolved,
            @Nullable UUID partition,
            Instant now) {

        UUID accountId = UUID.randomUUID();
        CustomerIdentityPolicy policy = resolved.mode();

        // The version comes from the policy row that was read, not from the mode
        // it carried. Deriving it from the mode records which rule applied, which
        // the account's stored partition already says; the column is there to say
        // which governed decision that rule came from, so a later mode change is
        // a migration from a known starting point rather than a reinterpretation.
        store.insertAccount(accountId, tenantId, partition, resolved.version(), now);
        try {
            store.insertPrincipalLink(UUID.randomUUID(), tenantId, partition, accountId, issuer, subject, now);
        } catch (DuplicateKeyException raced) {
            // Two concurrent first sign-ins for the same subject. The partial
            // unique index caught the loser, which is the point of having it:
            // the alternative is one person quietly owning two accounts. Re-read
            // rather than fail, so the user simply signs in.
            log.info("Concurrent first sign-in for a subject in tenant {}; using the winning account", tenantId);
            UUID winner = store.findLinkedAccount(tenantId, partition, issuer, subject)
                    .orElseThrow(() -> raced);
            ensureBrandProfile(tenantId, brandId, winner);
            return new CustomerAccountRef(winner, tenantId);
        }

        ensureBrandProfile(tenantId, brandId, accountId);
        log.info("Created customer account {} in tenant {} under {}", accountId, tenantId, policy);
        return new CustomerAccountRef(accountId, tenantId);
    }

    /**
     * A profile exists for every brand the customer has visited.
     *
     * <p>Created on sign-in rather than on first order, so a brand's preferences
     * and consent have somewhere to live before the customer buys anything.
     */
    private void ensureBrandProfile(UUID tenantId, UUID brandId, UUID accountId) {
        store.upsertBrandProfile(UUID.randomUUID(), tenantId, brandId, accountId, clock.instant());
    }

    /**
     * Creates an account with no Keycloak principal link at all (ADR 0059
     * stage 3: the SendPulse contact-export import).
     *
     * <p>Every other creation path in this class exists because a subject
     * signed in; an imported contact never does, and a contact whose export
     * row carries no phone number gives {@link CustomerImportDirectoryService}
     * nothing an ADR 0015 identity resolution could ever match on either. The
     * account this method creates is reachable only through the ADR 0058
     * Telegram binding the import creates alongside it — the same
     * "channel-identity-only" account the record's own Decision section
     * names as the deliberate alternative to reporting a phone-less contact
     * as needs-attention.
     *
     * <p>Still governed by the tenant's identity policy, for the same reason
     * {@link #create} is: the partition an account is created in must not
     * silently change later, whether the account came from a sign-in or an
     * import.
     */
    @Transactional
    public CustomerAccountRef createAccountWithoutPrincipal(UUID tenantId, UUID brandId) {
        Instant now = clock.instant();
        ResolvedIdentityPolicy resolved = policies.policyFor(tenantId, now);
        UUID partition = resolved.mode().partitionFor(brandId);

        UUID accountId = UUID.randomUUID();
        store.insertAccount(accountId, tenantId, partition, resolved.version(), now);
        ensureBrandProfile(tenantId, brandId, accountId);
        log.info("Created channel-only customer account {} in tenant {} (no principal link)", accountId, tenantId);
        return new CustomerAccountRef(accountId, tenantId);
    }

    /** Reads an account without creating one. */
    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerAccountRef> findAccount(UUID tenantId, UUID brandId, String issuer, String subject) {
        return find(tenantId, brandId, issuer, subject);
    }

    /** Reads an account without creating one. */
    @Transactional(readOnly = true)
    public Optional<CustomerAccountRef> find(UUID tenantId, UUID brandId, String issuer, String subject) {
        UUID partition = policies.policyFor(tenantId, clock.instant()).mode().partitionFor(brandId);
        return store.findLinkedAccount(tenantId, partition, issuer, subject)
                .map(accountId -> new CustomerAccountRef(store.resolveMergeTarget(tenantId, accountId), tenantId));
    }

    /**
     * Whether this principal is the customer that account belongs to.
     *
     * <p>Every link the principal has inside the tenant is considered rather than
     * the one in a single partition, because the callers that ask this have no
     * brand to partition by and a BRAND_ISOLATED tenant gives one person an
     * account per brand.
     *
     * <p>Each candidate is followed through its merge redirect before it is
     * compared. A merged account keeps its link row — the immutable order
     * snapshots point at it — so a caller naming the surviving id would otherwise
     * be told it is not theirs, on the day their two accounts were joined.
     */
    @Transactional(readOnly = true)
    public boolean owns(UUID tenantId, String issuer, String subject, UUID accountId) {
        return store.linkedAccounts(tenantId, issuer, subject).stream()
                .map(linked -> store.resolveMergeTarget(tenantId, linked))
                .anyMatch(accountId::equals);
    }

    /**
     * Follows an account through its merge redirect (ADR 0051).
     *
     * <p>A session names the account that existed when it was minted, and two
     * accounts can be joined while somebody is still holding one. Without this a
     * customer whose accounts were merged on Tuesday would spend the rest of their
     * session addressing a row that no longer owns anything — which reads to them
     * as their order history vanishing.
     */
    @Transactional(readOnly = true)
    public CustomerAccountRef effective(UUID tenantId, UUID accountId) {
        return new CustomerAccountRef(store.resolveMergeTarget(tenantId, accountId), tenantId);
    }

    /**
     * Whether a session's account and a named account are the same account.
     *
     * <p>Both sides are followed through their merge redirects before they are
     * compared, for the reason {@link #owns} gives: on the day two accounts are
     * joined, a caller naming either id is naming their own.
     */
    @Transactional(readOnly = true)
    public boolean sameAccount(UUID tenantId, UUID sessionAccountId, UUID namedAccountId) {
        return store.resolveMergeTarget(tenantId, sessionAccountId)
                .equals(store.resolveMergeTarget(tenantId, namedAccountId));
    }

    /**
     * Merges one account into another, by staff hand (frontend information
     * architecture §5.2: "identity merge for aggregator-masked identities").
     *
     * <p>A redirect, never a data move: {@code source} keeps every row it ever
     * wrote — addresses, consent decisions, order snapshots — and only its own
     * {@code status}/{@code merged_into_account_id} change, the same columns
     * {@code JdbcCustomerStore#resolveMergeTarget} already knew how to follow
     * before this method could write them (V0017 declared the column; nothing
     * wrote it until now).
     * A future sign-in, order lookup, or loyalty balance read for the source
     * resolves to {@code target} from the moment this commits.
     *
     * <p>What this does not do: no automatic duplicate detection. A masked
     * aggregator phone (Yandex, Wolt) surfacing the same person under two
     * accounts is found by an operator noticing two order histories that read
     * like one customer, and named here explicitly — this call is the write, not
     * the search.
     *
     * @param expectedVersion {@code source}'s version, so two operators racing to
     *                        merge the same duplicate settle at one outcome
     * @throws SelfMergeException            {@code source} and {@code target} are
     *                        the same account
     * @throws MergeTargetInvalidException   {@code target} does not exist in this
     *                        tenant, or is itself already merged away — chaining
     *                        merges is refused rather than silently followed, so
     *                        an operator always names the account that will
     *                        actually end up owning the history
     * @throws MergeConflictException        {@code source} has moved on from
     *                        {@code expectedVersion}, or was merged by a
     *                        concurrent call before this one committed
     */
    @Transactional
    public void merge(UUID tenantId, UUID sourceAccountId, UUID targetAccountId, int expectedVersion, ActorRef actor) {
        if (sourceAccountId.equals(targetAccountId)) {
            throw new SelfMergeException();
        }
        var target = store.account(tenantId, targetAccountId).orElseThrow(MergeTargetInvalidException::new);
        if ("MERGED".equals(target.status())) {
            throw new MergeTargetInvalidException();
        }
        var source = store.account(tenantId, sourceAccountId).orElseThrow(MergeTargetInvalidException::new);

        Instant now = clock.instant();
        int written = store.mergeAccount(tenantId, sourceAccountId, targetAccountId, expectedVersion, now);
        if (written == 0) {
            throw new MergeConflictException(expectedVersion, source.version());
        }

        audit.record(AuditFact.of("customer.identity.merged", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("customer_account", sourceAccountId)
                .because("Operator merged a duplicate customer identity")
                .changed(java.util.Map.of("mergedIntoAccountId", targetAccountId.toString()))
                .correlatedBy(sourceAccountId.toString())
                .occurredAt(now)
                .build());

        log.info("Merged customer account {} into {} in tenant {}", sourceAccountId, targetAccountId, tenantId);
    }

    /** {@code source} and {@code target} named the same account. */
    public static class SelfMergeException extends RuntimeException {
        public SelfMergeException() {
            super("An account cannot be merged into itself");
        }
    }

    /** The named merge target does not exist in this tenant, or is itself merged away. */
    public static class MergeTargetInvalidException extends RuntimeException {
        public MergeTargetInvalidException() {
            super("No such target account, or the target is itself already merged");
        }
    }

    /** The source account moved on from the caller's {@code expectedVersion}. */
    public static class MergeConflictException extends RuntimeException {
        private final int expected;
        private final int actual;

        public MergeConflictException(int expected, int actual) {
            super("Expected version %d but the account is at %d".formatted(expected, actual));
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }

    /**
     * The account a sign-in resolved to.
     *
     * @param created true on first sign-in, which is when a storefront should ask
     *                for consent rather than assume it
     */
    public record Resolution(CustomerAccountRef account, boolean created, CustomerIdentityPolicy policy) {}
}
