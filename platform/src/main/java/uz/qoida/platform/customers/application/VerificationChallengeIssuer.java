package uz.qoida.platform.customers.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.customers.application.CustomerProfileService.ContactType;
import uz.qoida.platform.customers.application.VerificationChallengeStore.IssuanceWindow;
import uz.qoida.platform.customers.application.VerificationChallengeStore.NewChallenge;
import uz.qoida.platform.iam.api.protection.DataClass;
import uz.qoida.platform.iam.api.protection.FieldProtection;
import uz.qoida.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * The database half of issuing a code: what this number has already been sent,
 * and the row that records the next one (ADR 0015, ADR 0033).
 *
 * <p>Its own bean rather than a method on {@link CustomerVerificationService},
 * because it is the part that must be one transaction and that class is the part
 * that must not be: the provider call happens between them, and a transaction held
 * open across somebody else's network is what
 * {@code ExternalCallTransactionBoundaryTests} exists to catch. A
 * {@code @Transactional} method called from a sibling method of the same class is
 * inert anyway — self-invocation never reaches the proxy — so the split is what
 * makes the annotation mean anything.
 *
 * <p><strong>The per-destination budget lives here, in PostgreSQL, and not in the
 * ADR 0033 rate limiter.</strong> That limiter is per replica: with three replicas
 * a budget of five is fifteen, and the two things being protected — an SMS bill
 * and a brute-force oracle — are both global to the phone number. The limiter is
 * still used, one layer out, for the thing it is good at: bounding one caller
 * walking a list of numbers, where no destination's budget is spent twice.
 */
@Service
public class VerificationChallengeIssuer {

    private static final Logger log = LoggerFactory.getLogger(VerificationChallengeIssuer.class);

    static final String CHALLENGE_TABLE = "customer.verification_challenges";
    static final String SIGN_IN_PURPOSE = "SIGN_IN";

    private final VerificationChallengeStore challenges;
    private final CodeProtection codes;
    private final FieldProtection protection;
    private final Clock clock;

    private final Duration codeTtl;
    private final int maxAttempts;
    private final Duration resendInterval;
    private final Duration destinationWindow;
    private final int destinationBudget;

    public VerificationChallengeIssuer(
            VerificationChallengeStore challenges,
            CodeProtection codes,
            FieldProtection protection,
            Clock clock,
            @Value("${qoida.customers.verification.code-ttl:PT5M}") Duration codeTtl,
            @Value("${qoida.customers.verification.max-attempts:5}") int maxAttempts,
            @Value("${qoida.customers.verification.resend-interval:PT1M}") Duration resendInterval,
            @Value("${qoida.customers.verification.destination-window:PT1H}") Duration destinationWindow,
            @Value("${qoida.customers.verification.destination-budget:5}") int destinationBudget) {

        this.challenges = challenges;
        this.codes = codes;
        this.protection = protection;
        this.clock = clock;
        this.codeTtl = codeTtl;
        this.maxAttempts = maxAttempts;
        this.resendInterval = resendInterval;
        this.destinationWindow = destinationWindow;
        this.destinationBudget = destinationBudget;
    }

    /**
     * Retires any live challenge for this number and writes a new one.
     *
     * <p>Both budget rules count every challenge whatever became of it. A rule
     * that only counted unspent ones would let an attacker refresh their own
     * budget by burning each challenge's attempts, which is exactly the traffic the
     * budget exists to stop.
     *
     * @param code drawn by the caller rather than here, so that exactly one place
     *             decides which code this destination gets and whether it travels.
     *             Generating it here as well would mean two draws per issuance and
     *             two chances for them to disagree
     */
    @Transactional
    public Opened open(UUID tenantId, UUID brandId, String destination, String destinationHash,
            VerificationCodeSource.Code code) {

        Instant now = clock.instant();

        // The budget applies to a code that will actually be sent, and not to one
        // that will not. What it bounds is a bill somebody pays per message and
        // the brute-force oracle a stream of fresh challenges would give, and a
        // preset code that never leaves the process creates neither: it costs
        // nothing, and it is already known to the one person a local profile
        // exists for. Charging it would mean the owner of this platform can sign
        // in five times an hour on their own laptop, which is the feature failing
        // at the point of use.
        //
        // This is safe only because the preset source cannot exist outside a local
        // profile and a non-local profile refuses to start with its configuration
        // present. See PresetVerificationCodeSource and its guard.
        if (code.requiresDelivery()) {
            IssuanceWindow window = challenges.issuanceWindow(
                    tenantId, destinationHash, now.minus(destinationWindow));

            window.lastIssuedAt()
                    .filter(last -> last.plus(resendInterval).isAfter(now))
                    .ifPresent(last -> {
                        throw tooManyRequests(
                                "A code was sent very recently. Wait a moment before asking for another.",
                                Duration.between(now, last.plus(resendInterval)));
                    });

            if (window.issuedInWindow() >= destinationBudget) {
                // Deliberately the same answer the caller-rate refusal gives. It does
                // admit that this number has been asked about recently, which is a
                // small oracle; the alternative — accepting the request and sending
                // nothing — lies to an honest customer standing at a till, and what is
                // actually being protected is a bill somebody pays per message.
                throw tooManyRequests(
                        "Too many codes have been requested for this number. Try again later.",
                        destinationWindow);
            }
        }

        // Any earlier live challenge dies here. Without this, three requests leave
        // three live challenges with five attempts each, and the attempt limit
        // becomes whatever an attacker is willing to pay for extra messages.
        int superseded = challenges.supersedePending(
                tenantId, ContactType.PHONE.name(), destinationHash, now);

        UUID challengeId = UUID.randomUUID();
        Instant expiresAt = now.plus(codeTtl);

        challenges.insert(new NewChallenge(
                challengeId,
                tenantId,
                brandId,
                SIGN_IN_PURPOSE,
                ContactType.PHONE.name(),
                destinationHash,
                protection.protect(tenantId, DataClass.PERSONAL,
                        new RecordRef(CHALLENGE_TABLE, "destination_encrypted", challengeId),
                        destination).serialize(),
                codes.hash(tenantId, challengeId, code.value()),
                maxAttempts,
                now,
                expiresAt));

        // The challenge id and the tenant, and nothing else. Not the number, not
        // its hash, not the code (ADR 0029, and ADR 0028 on one-time codes).
        log.info("Issued verification challenge {} for tenant {}; superseded {} live challenge(s)",
                challengeId, tenantId, superseded);

        return new Opened(challengeId, code.value(), code.requiresDelivery(), expiresAt,
                maxAttempts, codeTtl);
    }

    static ApiException tooManyRequests(String message, Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, message,
                Map.of("retryAfterSeconds", Math.max(1, retryAfter.toSeconds())));
    }

    /**
     * A committed challenge and the code that belongs to it.
     *
     * <p>The code is here only so the caller can hand it straight to the transport.
     * It is on no row, in no log, and in no response.
     */
    public record Opened(UUID challengeId, String code, boolean requiresDelivery,
            Instant expiresAt, int attemptsAllowed, Duration validFor) {

        @Override
        public String toString() {
            return "Opened[challengeId=%s, expiresAt=%s]".formatted(challengeId, expiresAt);
        }
    }
}
