package uz.qoida.platform.customers.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.customers.api.CustomerAccountRef;
import uz.qoida.platform.customers.application.CustomerProfileService.ContactType;
import uz.qoida.platform.customers.application.VerificationChallengeIssuer.Opened;
import uz.qoida.platform.customers.application.VerificationChallengeStore.Attempt;
import uz.qoida.platform.customers.application.VerificationChallengeStore.RedeemedGrant;
import uz.qoida.platform.customers.domain.PhoneNumber;
import uz.qoida.platform.customers.domain.VerificationCode;
import uz.qoida.platform.customers.domain.VerificationGrantSecret;
import uz.qoida.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.qoida.platform.customers.spi.VerificationCodeTransport;
import uz.qoida.platform.customers.spi.VerificationCodeTransport.ContactChannel;
import uz.qoida.platform.customers.spi.VerificationCodeTransport.Outcome;
import uz.qoida.platform.customers.spi.VerificationCodeTransport.VerificationMessage;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.iam.api.protection.DataClass;
import uz.qoida.platform.iam.api.protection.FieldProtection;
import uz.qoida.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.qoida.platform.iam.api.protection.ProtectedValue;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;
import uz.qoida.platform.web.cache.RateLimiter;

/**
 * How somebody holding a phone becomes somebody holding an account (ADR 0015,
 * ADR 0003, ADR 0029).
 *
 * <p>Three steps, and the split between them is the design.
 *
 * <ol>
 *   <li><strong>Issue.</strong> A code is generated, sent, and never written down.
 *       What the row keeps is a keyed MAC — see {@link CodeProtection} for why
 *       that construction and not a slow KDF.</li>
 *   <li><strong>Verify.</strong> A code plus its challenge yields a single-use
 *       grant: proof that the bearer controls that number, for that brand, at
 *       that moment.</li>
 *   <li><strong>Redeem.</strong> An authenticated principal exchanges the grant
 *       for an account. The customer record and the principal link come into
 *       existence here, and this is deliberately the only step that needs a
 *       token.</li>
 * </ol>
 *
 * <p><strong>Why verification does not simply create the account.</strong> It
 * would have to find the account by phone number, and ADR 0015 calls matching on
 * a phone the single most dangerous option available: recycled numbers change
 * owner and households share a handset, so a phone-keyed sign-in eventually hands
 * one person another person's order history, addresses and benefits. Resolution
 * stays on {@code (issuer, subject)} and nothing else, exactly as
 * {@link CustomerIdentityService} already does it.
 *
 * <p>Which human a number currently belongs to is therefore a question somebody
 * has to own, because it is where an operator has to be able to say "this number
 * changed hands". ADR 0003 put that with Keycloak; ADR 0051 moved it here, into
 * a {@code customer.principal_links} row an operator can set to {@code UNLINKED},
 * because that lever belongs in the same database as the order history it
 * protects. {@link #redeemAsProvenNumber} is where a proven number becomes a
 * principal, and it is the path the storefront takes.
 *
 * <p>The grant is still the seam, and it is still not a session: Qoida proves
 * control of a number, {@code CustomerSessionService} exchanges the grant for a
 * session, and the redemption links that principal to an account and attaches the
 * number as a verified contact. {@link #redeem} keeps the realm-token variant for
 * a caller who already holds one.
 *
 * <p><strong>Nothing here can reveal whether a number already has an account.</strong>
 * Issuance never reads the account or contact tables at all, so enumeration
 * resistance is a property of the shape of this class rather than a rule somebody
 * has to remember.
 */
@Service
public class CustomerVerificationService {

    private static final Logger log = LoggerFactory.getLogger(CustomerVerificationService.class);

    /** Recorded on every reveal, so an attach is distinguishable from an export. */
    private static final String REVEAL_PURPOSE = "CUSTOMER_VERIFICATION_ATTACH";

    private static final String CONTACT_TABLE = "customer.contact_points";

    /**
     * Per caller rather than per destination, and strict.
     *
     * <p>The per-destination budget in {@link VerificationChallengeIssuer} is the
     * authoritative one and lives in PostgreSQL. This is the cheap outer wall: it
     * stops one source walking a list of numbers, which the per-destination budget
     * cannot see, because every request in such a walk names a different
     * destination and spends nobody's budget twice.
     *
     * <p>{@code strictPerMinute}, so a saturated limiter denies rather than allows
     * (ADR 0033 makes that choice per limit). An SMS costs money: failing closed
     * sends nothing and is recoverable, failing open sends everything and is a
     * bill.
     *
     * <p>Not applied to a preset code (ADR 0051). What this bounds is one source
     * walking a <em>list</em> of numbers, and a preset is one fixed number on a
     * local profile that sends nothing and costs nothing — there is no list to
     * walk and no bill to run up. Charging it would put a six-per-minute ceiling
     * on developing against this platform at all.
     */
    private static final RateLimiter.Policy ISSUE_PER_CALLER = RateLimiter.Policy.strictPerMinute(6);

    /**
     * Verification is limited per caller too.
     *
     * <p>The five-attempt limit is per challenge, so on its own it bounds nothing
     * for somebody holding many challenge ids — a hundred challenges is five
     * hundred guesses. This is what makes spraying cost time as well as messages.
     */
    private static final RateLimiter.Policy VERIFY_PER_CALLER = RateLimiter.Policy.strictPerMinute(15);

    private static final String ISSUE_OPERATION = "customers.verification.issue";
    private static final String VERIFY_OPERATION = "customers.verification.verify";

    private final VerificationChallengeIssuer issuer;
    private final VerificationChallengeStore challenges;
    private final CodeProtection codes;
    private final JdbcCustomerStore customers;
    private final CustomerIdentityService identity;
    private final FieldProtection protection;
    private final RateLimiter rateLimiter;
    private final VerificationCodeSource codeSource;
    private final ObjectProvider<VerificationCodeTransport> transports;
    private final AuditRecorder audit;
    private final Clock clock;
    private final Duration grantTtl;

    public CustomerVerificationService(
            VerificationChallengeIssuer issuer,
            VerificationChallengeStore challenges,
            CodeProtection codes,
            JdbcCustomerStore customers,
            CustomerIdentityService identity,
            FieldProtection protection,
            RateLimiter rateLimiter,
            VerificationCodeSource codeSource,
            ObjectProvider<VerificationCodeTransport> transports,
            AuditRecorder audit,
            Clock clock,
            @Value("${qoida.customers.verification.grant-ttl:PT10M}") Duration grantTtl) {

        this.issuer = issuer;
        this.challenges = challenges;
        this.codes = codes;
        this.customers = customers;
        this.identity = identity;
        this.protection = protection;
        this.rateLimiter = rateLimiter;
        this.codeSource = codeSource;
        this.transports = transports;
        this.audit = audit;
        this.clock = clock;
        this.grantTtl = grantTtl;
    }

    /**
     * Sends a code to a phone number.
     *
     * <p>Not transactional, which is why {@link VerificationChallengeIssuer} is a
     * separate bean: the row is committed first, the provider is called second, and
     * a pooled connection is never held open across somebody else's network.
     *
     * <p>The row is written <em>before</em> the send rather than after, so that
     * concurrent requests for one number contend on the issuance budget instead of
     * all passing a budget check and all sending.
     *
     * @param callerKey an opaque, already-hashed handle for the caller.
     *                  Deliberately not a network address: ADR 0029 keeps those out
     *                  of this module, and the module has no use for the address
     *                  itself
     */
    public Challenge issue(UUID tenantId, UUID brandId, String rawPhone, String callerKey) {
        String destination = deliverableNumber(rawPhone);

        // Asked before the limiter, because the answer decides whether the limiter
        // applies. This is the one place a code is drawn, and the answer travels
        // down into the issuer rather than being recomputed there.
        VerificationCodeSource.Code code = codeSource.codeFor(destination);

        if (code.requiresDelivery()) {
            RateLimiter.Decision caller = rateLimiter.check(
                    new RateLimiter.Key(ISSUE_OPERATION, String.valueOf(tenantId), callerKey),
                    ISSUE_PER_CALLER);
            if (!caller.allowed()) {
                throw VerificationChallengeIssuer.tooManyRequests(
                        "Too many verification requests. Try again shortly.", caller.retryAfter());
            }
        }

        String destinationHash = protection.lookupHash(
                tenantId, ContactType.PHONE.lookupDomain(), destination);

        Opened opened = issuer.open(tenantId, brandId, destination, destinationHash, code);
        deliver(tenantId, brandId, destination, opened);

        return new Challenge(opened.challengeId(), opened.expiresAt(), opened.attemptsAllowed(),
                VerificationCode.LENGTH);
    }

    /**
     * Hands the code to the transport, and withdraws the challenge if nobody took
     * it.
     *
     * <p>The code exists in memory from {@link VerificationChallengeIssuer#open} to
     * here and nowhere else. It is not returned to the caller, not logged, and not
     * on the row.
     */
    private void deliver(UUID tenantId, UUID brandId, String destination, Opened opened) {
        if (!opened.requiresDelivery()) {
            // A preset code, on a local profile, for the one number configured
            // there. There is nothing to send — the code is already known to the
            // person who configured it — and no transport to send it with. This
            // returns before the refusal below, which is the only reason a laptop
            // with no SMS gateway can sign in at all.
            //
            // It cannot widen: VerificationChallengeIssuer only ever sets this
            // flag from PresetVerificationCodeSource, which does not exist outside
            // a local profile and whose configuration a non-local profile refuses
            // to start with.
            return;
        }

        VerificationCodeTransport transport = transports.getIfAvailable();
        if (transport == null) {
            // No SMS adapter is wired. Refusing loudly is the whole point: a
            // stand-in that swallowed the message would make an unconfigured
            // deployment indistinguishable from a working one until a customer
            // complained, and one that logged the code would put a live credential
            // in a log file for its whole retention period.
            withdraw(tenantId, opened, "NO_TRANSPORT");
            throw unsendable("NO_TRANSPORT");
        }

        Outcome outcome = transport.send(new VerificationMessage(
                tenantId, brandId, opened.challengeId(), ContactChannel.SMS, destination,
                opened.code(), opened.validFor(), null, clock.instant()));

        if (outcome.status() != Outcome.Status.ACCEPTED) {
            withdraw(tenantId, opened, outcome.reasonCode());
            throw unsendable(outcome.reasonCode());
        }
    }

    /**
     * Removes a challenge whose code never left the building.
     *
     * <p>Keeping it would charge the customer's issuance budget for our outage and
     * leave a live challenge whose code nobody can know. One statement, so it needs
     * no transaction of its own.
     */
    private void withdraw(UUID tenantId, Opened opened, String reason) {
        challenges.deleteUnsent(tenantId, opened.challengeId());
        log.warn("Withdrew verification challenge {}: the transport answered {}",
                opened.challengeId(), reason);
    }

    /**
     * Spends one attempt against a challenge.
     *
     * <p>The attempt is spent by the store's conditional {@code UPDATE} before the
     * comparison, so a guess costs an attempt however the comparison turns out, and
     * five concurrent guesses cost five attempts rather than one.
     */
    @Transactional
    public Grant verify(UUID tenantId, UUID challengeId, String submittedCode, String callerKey) {
        RateLimiter.Decision caller = rateLimiter.check(
                new RateLimiter.Key(VERIFY_OPERATION, String.valueOf(tenantId), callerKey),
                VERIFY_PER_CALLER);
        if (!caller.allowed()) {
            throw VerificationChallengeIssuer.tooManyRequests(
                    "Too many attempts. Try again shortly.", caller.retryAfter());
        }

        if (!VerificationCode.isWellFormed(submittedCode)) {
            // Refused without spending an attempt, deliberately. A client sending
            // an empty field or a pasted paragraph has not guessed anything, and
            // spending the customer's attempts on their own typo is a denial of
            // service we would be inflicting on ourselves.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A verification code is %d digits.".formatted(VerificationCode.LENGTH));
        }

        Instant now = clock.instant();
        Attempt attempt = challenges.consumeAttempt(tenantId, challengeId, now)
                .orElseThrow(CustomerVerificationService::challengeOver);

        if (!codes.matches(tenantId, challengeId, submittedCode, attempt.codeHash())) {
            if (attempt.attemptsRemaining() <= 0) {
                challenges.markExhausted(tenantId, challengeId, now);
                log.info("Verification challenge {} in tenant {} spent its last attempt",
                        challengeId, tenantId);
                throw challengeOver();
            }
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "That code is not correct.",
                    Map.of("attemptsRemaining", attempt.attemptsRemaining()));
        }

        VerificationGrantSecret.Issued grant = VerificationGrantSecret.issue();
        Instant grantExpiresAt = now.plus(grantTtl);

        if (!challenges.markVerified(tenantId, challengeId, grant.hash(), grantExpiresAt, now)) {
            // Two requests carried the same correct code and the other won. The
            // code is single-use, so the loser gets the answer anyone presenting a
            // spent challenge gets, rather than a second grant.
            throw challengeOver();
        }

        audit.record(fact("CUSTOMER_CONTACT_VERIFIED", tenantId, null, challengeId,
                Map.of("contactType", ContactType.PHONE.name(),
                        "purpose", VerificationChallengeIssuer.SIGN_IN_PURPOSE),
                now));

        return new Grant(grant.plaintext(), grantExpiresAt);
    }

    /**
     * Exchanges a grant for an account, on behalf of an authenticated principal.
     *
     * <p><strong>This is no longer the storefront's path.</strong> It used to
     * describe a Keycloak authentication flow a deployment still had to build, and
     * ADR 0051 decided not to build one: a customer signs in through
     * {@link #redeemAsProvenNumber} and holds a platform-issued session. What is
     * left here is the variant for a caller who <em>already</em> holds a realm
     * token — an operator acting on somebody's behalf, or a federated customer if
     * one ever exists — and it is kept because the two differ only in which
     * principal the grant is redeemed into.
     *
     * <p>A customer who did arrive with a realm token would still be an ordinary
     * realm user and <em>not</em> an organization member: ADR 0003 maps one
     * organization to one tenant for staff, and a customer carrying an organization
     * claim would read as staff.
     *
     * <p>The tenant and brand come off the redeemed row and are then checked
     * against the path rather than taken from it. A grant proved control of a
     * number for one brand; honouring it at another would create the account in the
     * wrong identity partition under {@code BRAND_ISOLATED}, which is a different
     * person in every sense that matters.
     */
    @Transactional
    public Redemption redeem(UUID tenantId, UUID brandId, String grantSecret, String issuerUri,
            String subject) {

        return redeem(tenantId, brandId, grantSecret,
                redeemed -> new PrincipalKey(issuerUri, subject));
    }

    /**
     * Exchanges a grant for an account on the strength of the proven number alone
     * (ADR 0051).
     *
     * <p>This is the path a storefront customer actually takes, and it is the half
     * {@link #redeem} above says a deployment must still configure — now decided
     * rather than deferred. There is no token and there will not be one: the
     * caller is somebody who has just typed a code, and requiring a session in
     * order to create one is the circle this seam was stuck in for a year.
     *
     * <p><strong>The subject is the proven number's keyed hash, and the issuer is
     * a constant this platform owns.</strong> That is phone-derived identity, and
     * ADR 0015 argues against phone-derived identity, so the difference has to be
     * stated rather than glossed. What ADR 0015 forbids is resolving an account
     * from a number a request <em>asserts</em> — {@code accountsWithContact} over a
     * contact table full of unverified, imported and stale rows, where a recycled
     * number silently hands one person another's history. Here the number was
     * proved seconds ago by a one-time code, and what it resolves through is a
     * durable {@code customer.principal_links} row an operator can set to
     * {@code UNLINKED} — after which the same number proves the same control and
     * reaches a <em>new</em> account, while the old one keeps its orders and
     * becomes unreachable. That lever is the thing ADR 0015 wanted from an
     * identity provider, and ADR 0051 records why it is better held here: it is in
     * the same database, and the same transaction, as the history it protects.
     *
     * <p>The hash rather than the number, and not only for storage hygiene. It is
     * per-tenant and keyed (ADR 0029), so the same person at two tenants is two
     * unrelated subjects, and a database dump yields no number to look anybody up
     * by. It is also exactly the value {@code customer.contact_points} already
     * stores, so nothing new about a customer is written down.
     */
    @Transactional
    public Redemption redeemAsProvenNumber(UUID tenantId, UUID brandId, String grantSecret) {
        return redeem(tenantId, brandId, grantSecret,
                redeemed -> new PrincipalKey(
                        CustomerIdentityService.PROVEN_NUMBER_ISSUER, redeemed.destinationHash()));
    }

    /**
     * @param principalOf which principal the redeemed grant belongs to. A function
     *                    of the redeemed row rather than two parameters, because
     *                    the proven-number path can only answer once the row has
     *                    been read, and reading it is what makes the grant spent
     */
    private Redemption redeem(UUID tenantId, UUID brandId, String grantSecret,
            java.util.function.Function<RedeemedGrant, PrincipalKey> principalOf) {

        Instant now = clock.instant();
        RedeemedGrant redeemed = challenges
                .redeemGrant(VerificationGrantSecret.hash(requireSecret(grantSecret)), now)
                .orElseThrow(CustomerVerificationService::grantRefused);

        if (!redeemed.tenantId().equals(tenantId) || !redeemed.brandId().equals(brandId)) {
            throw grantRefused();
        }

        PrincipalKey principal = principalOf.apply(redeemed);
        CustomerIdentityService.Resolution resolution =
                identity.resolve(tenantId, brandId, principal.issuer(), principal.subject());
        UUID accountId = resolution.account().accountId();

        attachVerifiedContact(tenantId, accountId, redeemed, now);

        audit.record(fact("CUSTOMER_PRINCIPAL_VERIFIED", tenantId, brandId, accountId,
                Map.of("challengeId", redeemed.challengeId().toString(),
                        "accountCreated", resolution.created()),
                now));

        return new Redemption(new CustomerAccountRef(accountId, tenantId), resolution.created());
    }

    /**
     * Records the proven number against the account.
     *
     * <p>An existing row for the same number is promoted rather than duplicated,
     * and a number another account already holds is still stored here — households
     * share a handset, and ADR 0015 forbids reading anything about identity out of
     * that coincidence.
     */
    private void attachVerifiedContact(UUID tenantId, UUID accountId, RedeemedGrant redeemed,
            Instant now) {

        int promoted = customers.markContactVerified(
                tenantId, accountId, redeemed.contactType(), redeemed.destinationHash(), now);
        if (promoted > 0) {
            return;
        }

        UUID contactId = UUID.randomUUID();
        // Decrypted here and re-protected under the contact point's own record
        // reference. Associated data binds a ciphertext to one row, so the
        // challenge's ciphertext would not open where it is about to be stored.
        String destination = protection.reveal(tenantId,
                ProtectedValue.deserialize(redeemed.destinationValue()),
                new RecordRef(VerificationChallengeIssuer.CHALLENGE_TABLE, "destination_encrypted",
                        redeemed.challengeId()),
                REVEAL_PURPOSE);

        customers.insertVerifiedContactPoint(
                contactId,
                tenantId,
                accountId,
                redeemed.contactType(),
                redeemed.destinationHash(),
                protection.protect(tenantId, DataClass.PERSONAL,
                        new RecordRef(CONTACT_TABLE, "encrypted_value", contactId),
                        destination).serialize(),
                !customers.hasPrimaryContact(tenantId, accountId, redeemed.contactType()),
                now);
    }

    private String deliverableNumber(String rawPhone) {
        try {
            return PhoneNumber.requireDeliverableMobile(rawPhone);
        } catch (IllegalArgumentException rejected) {
            // The message is written not to contain the number. Echoing the input
            // back would put a phone number in an ADR 0031 problem document, which
            // is a response body, an access log and a browser console at once.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rejected.getMessage());
        }
    }

    private static String requireSecret(String grantSecret) {
        if (grantSecret == null || grantSecret.isBlank()) {
            throw grantRefused();
        }
        return grantSecret;
    }

    private AuditFact fact(String action, UUID tenantId, UUID brandId, UUID targetId,
            Map<String, Object> changes, Instant now) {

        // No reason string, because there is no operator to have one: the actor is
        // the storefront acting for somebody who is not yet anybody. AuditFact
        // demands a reason only of a USER actor, for exactly this distinction.
        return AuditFact.of(action, AuditClass.SECURITY)
                .by(ActorRef.service("storefront-verification"))
                .at(brandId == null ? ResourceScope.tenant(tenantId)
                        : ResourceScope.brand(tenantId, brandId))
                .target("customer_verification", targetId)
                // Identifiers and states only. A number, a code, or a grant in a
                // change document would put personal data and a live credential
                // into a record designed to be kept for years.
                .changed(changes)
                .correlatedBy(Optional.ofNullable(MDC.get("correlationId"))
                        .orElse("customer-verification"))
                .occurredAt(now)
                .build();
    }

    private static ApiException unsendable(String reason) {
        return new ApiException(ErrorCode.INTERNAL_ERROR,
                "The code could not be sent. Try again.",
                Map.of("reason", String.valueOf(reason)));
    }

    /**
     * One answer for every way a challenge can be over.
     *
     * <p>Unknown, expired, superseded, exhausted and already used all produce this.
     * The client holds an unguessable challenge id it was handed, so nothing is
     * hidden from its rightful owner; what the single answer avoids is a client —
     * or a support script — coming to depend on a distinction that would then have
     * to stay stable forever.
     */
    private static ApiException challengeOver() {
        return new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                "This verification has ended. Request a new code.");
    }

    private static ApiException grantRefused() {
        return new ApiException(ErrorCode.UNAUTHENTICATED,
                "This verification can no longer be used. Start again.");
    }

    /**
     * Which principal a redeemed grant belongs to.
     *
     * <p>Both halves together, always, because a subject is unique only within the
     * issuer that minted it. Passing a subject without its issuer is the mistake
     * {@code PrincipalCustomer} spends a paragraph on, and a record makes it
     * impossible to make here.
     */
    private record PrincipalKey(String issuer, String subject) {
    }

    /** Everything the caller may know about a challenge. Never the code. */
    public record Challenge(UUID challengeId, Instant expiresAt, int attemptsAllowed,
            int codeLength) {
    }

    /** Returned once. The plaintext exists in that response and nowhere else. */
    public record Grant(String secret, Instant expiresAt) {

        /** A record's generated {@code toString} would print the secret. */
        @Override
        public String toString() {
            return "Grant[expiresAt=%s]".formatted(expiresAt);
        }
    }

    /**
     * @param created true when this sign-in brought the account into existence,
     *                which is when a storefront should ask for consent rather than
     *                assume it
     */
    public record Redemption(CustomerAccountRef account, boolean created) {
    }
}
