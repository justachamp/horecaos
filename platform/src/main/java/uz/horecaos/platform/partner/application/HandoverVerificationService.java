package uz.horecaos.platform.partner.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.partner.domain.HandoverChallengeStatus;
import uz.horecaos.platform.partner.domain.HandoverChallengeType;
import uz.horecaos.platform.partner.domain.HandoverCodeHasher;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Proving that a bag went to the right person (ADR 0040).
 *
 * <p>One model for every handover, aggregator or not. Handing a bag to a courier
 * and handing it to a customer at the pass are the same physical act with the
 * same failure — the wrong person leaves with the food — and two tables would
 * mean two hash schemes, two attempt counters and two answers to "was this order
 * proven handed over". ADR 0041's expo station verifies against this; it does
 * not create a handover model of its own.
 *
 * <p>The capability split is deliberate and stands on a difference in frequency.
 * {@code kitchen.handover.complete} closes a challenge and is a daily act at the
 * pass, held by everyone who works it. {@code marketplace.handover.bypass}
 * overrides verification, is not daily, and one capability covering both would
 * put the override in every expo bundle in the country.
 *
 * <p>Nothing here ever returns, logs, or traces the expected value. The response
 * to a wrong code says how many attempts remain and nothing else, because a
 * response that says how wrong the guess was is a response that can be searched.
 */
@Service
public class HandoverVerificationService {

    private final JdbcPartnerStore store;
    private final HandoverCodeHasher hasher;
    private final AuditRecorder audit;
    private final Clock clock;

    public HandoverVerificationService(
            JdbcPartnerStore store, HandoverCodeHasher hasher, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Verifies a courier's code and, on success, settles the challenge.
     *
     * <p>An attempt is consumed before the comparison and by a conditional
     * UPDATE carrying the current count in its predicate, so two devices trying
     * codes against one challenge in the same second consume two attempts. Read
     * the row, compare, then write {@code attempts + 1} and a brute force gets
     * free tries by racing itself, which is the only way five attempts is not
     * five attempts.
     */
    @Transactional
    public Verification verify(UUID tenantId, UUID orderId, String attempt, String actorSubject) {
        JdbcPartnerStore.Challenge challenge = store.findOpenChallenge(tenantId, orderId)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No open handover challenge for this order"));

        if (challenge.type() == HandoverChallengeType.NONE) {
            // Configured in advance as needing no proof. Settled rather than
            // skipped, so "was this handover verified" has an answer either way
            // and the absence of a challenge never doubles as a pass.
            store.settleChallenge(
                    tenantId, challenge.id(), HandoverChallengeStatus.VERIFIED, actorSubject, null, clock.instant());
            return new Verification(true, HandoverChallengeStatus.VERIFIED, 0);
        }

        int consumed = store.consumeAttempt(tenantId, challenge.id(), challenge.attempts())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "The handover challenge moved while this attempt was being made"));

        if (!hasher.matches(orderId, challenge.expectedValueHash(), attempt)) {
            boolean exhausted = consumed >= challenge.maxAttempts();
            return new Verification(
                    false,
                    exhausted ? HandoverChallengeStatus.FAILED : HandoverChallengeStatus.PENDING,
                    Math.max(0, challenge.maxAttempts() - consumed));
        }

        store.settleChallenge(
                tenantId, challenge.id(), HandoverChallengeStatus.VERIFIED, actorSubject, null, clock.instant());
        return new Verification(
                true, HandoverChallengeStatus.VERIFIED, Math.max(0, challenge.maxAttempts() - consumed));
    }

    /**
     * The audited override.
     *
     * <p>Available after exhaustion as well as before it, because a courier whose
     * app will not show the code is a real situation and a branch with no way
     * past it invents one — usually by handing over the food and typing nothing.
     * The reason code and the supervisor's name are what make the override a fact
     * somebody can be asked about rather than a gap in the record.
     */
    @Transactional
    public void bypass(
            UUID tenantId,
            ResourceScope scope,
            UUID orderId,
            String reasonCode,
            String actorSubject,
            String actorName,
            @Nullable String correlationId) {

        if (reasonCode == null || reasonCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A bypass requires a reason code");
        }

        JdbcPartnerStore.Challenge challenge = store.findChallengeForOrder(tenantId, orderId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No handover challenge for this order"));

        Instant now = clock.instant();
        boolean settled = store.settleChallenge(
                tenantId, challenge.id(), HandoverChallengeStatus.BYPASSED, actorSubject, reasonCode, now);
        if (!settled) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The handover challenge is already settled");
        }

        // ADR 0027, in the same transaction as the override it describes. An
        // override that succeeded without a record is indistinguishable from one
        // that never happened, and this is the record somebody reads when a
        // 420,000 som order went to the wrong courier.
        audit.record(AuditFact.of("marketplace.handover.bypassed", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, actorName))
                .at(scope)
                .target("order_handover_challenge", challenge.id())
                .because(reasonCode)
                .changed(Map.of(
                        "orderId", orderId.toString(),
                        "attemptsConsumed", challenge.attempts(),
                        "challengeType", challenge.type().name()))
                .usingCapability(Capability.MARKETPLACE_HANDOVER_BYPASS.code())
                .correlatedBy(correlationId == null ? challenge.id().toString() : correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * The outcome of one verification attempt.
     *
     * @param attemptsRemaining what the branch is told. Never how close the guess
     *                          was, and never the expected value.
     */
    public record Verification(boolean verified, HandoverChallengeStatus status, int attemptsRemaining) {}
}
