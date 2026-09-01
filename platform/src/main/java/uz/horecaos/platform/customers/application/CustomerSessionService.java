package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.application.CustomerSessionStore.NewSession;
import uz.horecaos.platform.customers.application.CustomerSessionStore.StoredSession;
import uz.horecaos.platform.customers.application.CustomerVerificationService.Redemption;
import uz.horecaos.platform.customers.domain.CustomerSessionToken;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * How a proven phone number becomes a session (ADR 0051).
 *
 * <p>This class is the seam the platform had been describing in comments for a
 * year and had not built. Verification proved control of a number and handed back
 * a single-use grant; every customer endpoint resolved its caller from a validated
 * JWT; and nothing anywhere turned the first into the second. The storefront's
 * only route to a token was the staff OAuth flow against Keycloak, which asks a
 * customer to become a member of the operator's directory in order to buy a plate
 * of osh.
 *
 * <p><strong>What is minted here is not a JWT and there is no second issuer.</strong>
 * It is 256 bits of CSPRNG output stored as a digest, and every binding — tenant,
 * brand, account, identity partition — is a column on the row it finds. That is
 * the answer to {@code PrincipalCustomer}'s argument for exactly one trusted
 * issuer: that argument is about a subject namespace, and a token that carries no
 * subject does not enter one. ADR 0047's dine-in guest token is the same
 * construction, already shipped, for the same reason.
 *
 * <p><strong>Establishing a session is one transaction on purpose.</strong> The
 * grant is spent by a conditional {@code UPDATE} and the session row is written in
 * the same unit of work, so there is no interval in which a customer's single-use
 * proof has been consumed and they hold nothing. The alternative fails in the
 * worst possible place — a customer who typed the right code, was told nothing
 * went wrong, and is signed out.
 */
@Service
public class CustomerSessionService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSessionService.class);

    private final CustomerVerificationService verification;
    private final CustomerSessionStore sessions;
    private final CustomerIdentityService identity;
    private final JdbcCustomerStore customers;
    private final AuditRecorder audit;
    private final Clock clock;
    private final Duration sessionTtl;

    public CustomerSessionService(
            CustomerVerificationService verification,
            CustomerSessionStore sessions,
            CustomerIdentityService identity,
            JdbcCustomerStore customers,
            AuditRecorder audit,
            Clock clock,
            @Value("${horecaos.customers.session.ttl:P30D}") Duration sessionTtl) {

        this.verification = verification;
        this.sessions = sessions;
        this.identity = identity;
        this.customers = customers;
        this.audit = audit;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
    }

    /**
     * Redeems a grant and mints the session it was proof for.
     *
     * <p>The partition is read off the account row rather than recomputed from the
     * tenant's policy. Those two agree today and would disagree across a governed
     * cutover, and the account's own column is the one that decides where the
     * account actually is — recomputing would stamp a session with a partitioning
     * the account is not in, which resolves to nothing for its whole lifetime.
     */
    @Transactional
    public Established establish(UUID tenantId, UUID brandId, String grantSecret) {
        Redemption redemption = verification.redeemAsProvenNumber(tenantId, brandId, grantSecret);
        return mint(tenantId, brandId, redemption.account().accountId(), redemption.created());
    }

    /**
     * Mints a session for an account this call already knows to be resolved —
     * ADR 0063's Telegram share-contact path, where the proof is a Telegram
     * {@code request_contact} share rather than a redeemed verification grant,
     * so there is no {@link Redemption} to read the account off; the caller
     * ({@code customers.api.CustomerTelegramSignIn}'s implementation) resolved
     * one moments earlier through {@code CustomerVerificationService#redeemAsTelegramContact}
     * and hands it straight in.
     *
     * <p>Mints exactly as {@link #establish} does from here on — same token
     * construction, same one-transaction session row, same audit fact — because
     * once an account is resolved, a session is a session regardless of which
     * proof produced it. What differs is entirely upstream of this method.
     *
     * <p>Called at most once per sign-in by contract, not by a check this method
     * makes itself: the caller's own single-claim guard
     * ({@code integration.telegram_pending_links.auth_session_claimed_at}) is
     * what makes that true, the same division of responsibility
     * {@link #establish}'s own grant-is-spent-once guarantee keeps one layer
     * down, in {@code VerificationChallengeStore.redeemGrant}.
     */
    @Transactional
    public Established establishForAccount(UUID tenantId, UUID brandId, UUID accountId, boolean accountCreated) {
        return mint(tenantId, brandId, accountId, accountCreated);
    }

    private Established mint(UUID tenantId, UUID brandId, UUID accountId, boolean accountCreated) {
        UUID partition = customers
                .account(tenantId, accountId)
                .orElseThrow(() ->
                        new IllegalStateException("The account just resolved is not readable in tenant " + tenantId))
                .partitionBrandId();

        Instant now = clock.instant();
        Instant expiresAt = now.plus(sessionTtl);
        CustomerSessionToken.Issued token = CustomerSessionToken.issue();
        UUID sessionId = UUID.randomUUID();

        sessions.insert(
                new NewSession(sessionId, tenantId, brandId, accountId, partition, token.hash(), now, expiresAt));

        audit.record(fact(
                "CUSTOMER_SESSION_ESTABLISHED",
                tenantId,
                brandId,
                sessionId,
                Map.of(
                        "accountId",
                        accountId.toString(),
                        "accountCreated",
                        accountCreated,
                        "expiresAt",
                        expiresAt.toString()),
                now));

        // The session and the account, and nothing about the person. Not the
        // number that proved it, not its hash, and not the token (ADR 0029,
        // ADR 0028).
        log.info("Established customer session {} for account {} in tenant {}", sessionId, accountId, tenantId);

        return new Established(token.plaintext(), expiresAt, accountId, accountCreated);
    }

    /**
     * What a presented token is, right now.
     *
     * <p>Answers with a state rather than an {@link Optional}, because the three
     * ways this can fail are not one failure. A customer whose session expired
     * mid-basket has to be told their session ended; telling them instead that
     * they are not signed in sends a person who <em>was</em> signed in back to the
     * front door with their basket apparently gone, and it is the difference
     * between an app that lost their place and an app that lost their order.
     */
    @Transactional(readOnly = true)
    public Resolution resolve(@Nullable String presentedToken) {
        // The explicit null check (rather than leaving it to looksLikeOne's own
        // null-safety) is what lets the compiler know presentedToken is non-null
        // for the rest of this method.
        if (presentedToken == null || !CustomerSessionToken.looksLikeOne(presentedToken)) {
            return Resolution.unknown();
        }

        Optional<StoredSession> found = sessions.find(CustomerSessionToken.hash(presentedToken));
        if (found.isEmpty()) {
            return Resolution.unknown();
        }

        StoredSession stored = found.get();
        Instant now = clock.instant();

        if (stored.revokedAt() != null) {
            return Resolution.ended();
        }
        if (!stored.expiresAt().isAfter(now)) {
            return Resolution.ended();
        }

        // Followed through its merge redirect here rather than at every call site.
        // A session names the account that existed when it was minted, and two
        // accounts can be joined while somebody is holding one.
        UUID effective =
                identity.effective(stored.tenantId(), stored.accountId()).accountId();

        return Resolution.active(new CustomerSession(
                stored.sessionId(),
                stored.tenantId(),
                stored.brandId(),
                effective,
                stored.identityPartitionBrandId(),
                stored.issuedAt(),
                stored.expiresAt()));
    }

    /**
     * Ends the session the caller is holding.
     *
     * <p>A second call is not an error. Sign-out is a thing people tap twice on a
     * slow connection, and answering the second one with a failure would teach a
     * client to retry until it saw something that looked like success.
     */
    @Transactional
    public void endCurrent(@Nullable String presentedToken) {
        if (presentedToken == null || !CustomerSessionToken.looksLikeOne(presentedToken)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "No customer session was presented.");
        }
        Instant now = clock.instant();
        if (sessions.revoke(CustomerSessionToken.hash(presentedToken), now)) {
            log.info("A customer session was ended by its holder");
        }
    }

    /** Ends every live session an account has. The answer to a lost handset. */
    @Transactional
    public int endAllFor(UUID tenantId, UUID accountId) {
        Instant now = clock.instant();
        int ended = sessions.revokeForAccount(tenantId, accountId, now);
        if (ended > 0) {
            audit.record(
                    fact("CUSTOMER_SESSIONS_REVOKED", tenantId, null, accountId, Map.of("sessionsEnded", ended), now));
        }
        return ended;
    }

    private AuditFact fact(
            String action,
            UUID tenantId,
            @Nullable UUID brandId,
            UUID targetId,
            Map<String, Object> changes,
            Instant now) {

        // A service actor, as on verification: there is no operator here, and
        // AuditFact demands a reason only of a USER actor.
        return AuditFact.of(action, AuditClass.SECURITY)
                .by(ActorRef.service("storefront-session"))
                .at(brandId == null ? ResourceScope.tenant(tenantId) : ResourceScope.brand(tenantId, brandId))
                .target("customer_session", targetId)
                // Identifiers and instants. A token or a number in a change
                // document would put a live credential and personal data into a
                // record designed to be kept for years.
                .changed(changes)
                .correlatedBy(Optional.ofNullable(MDC.get("correlationId")).orElse("customer-session"))
                .occurredAt(now)
                .build();
    }

    /**
     * A session freshly established.
     *
     * @param token returned once, in the response that established it, and never
     *              again. There is no endpoint that reissues it: the customer
     *              proves their number again
     */
    public record Established(String token, Instant expiresAt, UUID accountId, boolean created) {

        /** A record's generated {@code toString} would print the token. */
        @Override
        public String toString() {
            return "Established[accountId=%s, expiresAt=%s, created=%s]".formatted(accountId, expiresAt, created);
        }
    }

    /**
     * What a presented token resolved to.
     *
     * <p>{@link State#ENDED} covers both expiry and sign-out, deliberately: to the
     * customer they are the same event and the same remedy, and separating them in
     * the response would only tell somebody holding a stolen token whether it was
     * revoked deliberately.
     */
    public record Resolution(State state, @Nullable CustomerSession session) {

        public enum State {

            /** A live session. {@code session} is present. */
            ACTIVE,

            /** Real, and over — expired or signed out. The caller was signed in. */
            ENDED,

            /** Not a session this platform ever issued. */
            UNKNOWN
        }

        static Resolution active(CustomerSession session) {
            return new Resolution(State.ACTIVE, session);
        }

        static Resolution ended() {
            return new Resolution(State.ENDED, null);
        }

        static Resolution unknown() {
            return new Resolution(State.UNKNOWN, null);
        }
    }
}
