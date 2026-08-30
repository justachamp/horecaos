package uz.horecaos.platform.pos.domain;

import java.time.Instant;

/**
 * An order at the provider that might be the one we may or may not have sent
 * (ADR 0011).
 *
 * <p>Every field here is evidence for a person. None of it is a decision, except
 * {@link #correlationEchoed()}, and that one is a decision precisely because it
 * is not a heuristic: it means the provider handed back the reference we
 * generated, so the candidate is our order by identity rather than by
 * resemblance.
 *
 * @param correlationEchoed whether the provider returned the reference we sent.
 *                          False everywhere today: the first real POS's order
 *                          response carries integration_uuid, integration_id and
 *                          a customer reference, all described as coming from the
 *                          integration source "if provided", and its create
 *                          request has no documented slot to provide any of them
 * @param timeDeltaSeconds  signed seconds from our request to the candidate's
 *                          creation. Kept as the number rather than as a boolean
 *                          somebody chose a threshold for, because the operator
 *                          making the call wants to see it
 */
public record ExportCandidate(
        String externalOrderId,
        String externalStatus,
        Instant externalCreatedAt,
        boolean correlationEchoed,
        boolean phoneMatches,
        boolean fingerprintMatches,
        Integer timeDeltaSeconds) {

    /**
     * Whether every heuristic signal agrees.
     *
     * <p>Explicitly <em>not</em> named "matches". All three agreeing is exactly
     * what a customer ordering the same basket from the same phone a minute later
     * also produces, which is the reason this predicate cannot resolve anything
     * on its own.
     */
    public boolean allHeuristicsAgree() {
        return phoneMatches && fingerprintMatches && timeDeltaSeconds != null;
    }
}
