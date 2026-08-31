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
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerDirectory;
import uz.horecaos.platform.customers.api.CustomerIdentityPolicy;
import uz.horecaos.platform.customers.application.CustomerPolicyLookup.ResolvedIdentityPolicy;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;

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

    public CustomerIdentityService(JdbcCustomerStore store, CustomerPolicyLookup policies, Clock clock) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * Finds the account for this principal, creating one on first sign-in.
     *
     * @param brandId the brand the customer is signing in at. Required even under
     *                {@code TENANT_SHARED}, because a brand profile is created
     *                either way
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
            ensureBrandProfile(tenantId, brandId, effective);
            return new Resolution(new CustomerAccountRef(effective, tenantId), false, policy);
        }

        return new Resolution(create(tenantId, brandId, issuer, subject, resolved, partition, now), true, policy);
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
     * @param created true on first sign-in, which is when a storefront should ask
     *                for consent rather than assume it
     */
    public record Resolution(CustomerAccountRef account, boolean created, CustomerIdentityPolicy policy) {}
}
