package uz.horecaos.platform.pos.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

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
 * @param externalStatus    the provider's own status label for the candidate.
 *                          Null when the provider's record does not carry one
 * @param externalCreatedAt when the provider says the candidate was created.
 *                          Null when the provider's own record does not carry
 *                          the field, which is distinct from a delta of zero and
 *                          must not be defaulted to "now"
 * @param correlationEchoed whether the provider returned the reference we sent.
 *                          False everywhere today: the first real POS's order
 *                          response carries integration_uuid, integration_id and
 *                          a customer reference, all described as coming from the
 *                          integration source "if provided", and its create
 *                          request has no documented slot to provide any of them
 * @param timeDeltaSeconds  signed seconds from our request to the candidate's
 *                          creation, or null when {@code externalCreatedAt} is
 *                          unknown. Kept as the number rather than as a boolean
 *                          somebody chose a threshold for, because the operator
 *                          making the call wants to see it
 */
public record ExportCandidate(
        String externalOrderId,
        @Nullable String externalStatus,
        @Nullable Instant externalCreatedAt,
        boolean correlationEchoed,
        boolean phoneMatches,
        boolean fingerprintMatches,
        @Nullable Integer timeDeltaSeconds) {

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
