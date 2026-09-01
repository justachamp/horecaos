package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What {@link StaffDirectGrantClient} learned from one call to Keycloak's
 * token endpoint (ADR 0062): a fresh token pair, or a refusal classified into
 * exactly the two outcomes the ADR lets a caller tell apart.
 */
public sealed interface TokenOutcome {

    static TokenOutcome issued(Issued tokens) {
        return tokens;
    }

    static TokenOutcome refused(FailureReason reason) {
        return new Refused(reason);
    }

    /**
     * A live access/refresh token pair, straight from Keycloak.
     *
     * <p>Both expiry instants are computed here, once, from the response's
     * relative {@code expires_in}/{@code refresh_expires_in} seconds against
     * the injected {@link java.time.Clock} — never recomputed later against
     * wall-clock time, for the reason {@code AGENTS.md} gives: a duration read
     * once and turned into an instant survives a clock the caller does not
     * control; a duration held and re-added to "now" at every read does not.
     *
     * @param refreshTokenExpiresAt null when Keycloak reported {@code
     *                              refresh_expires_in: 0}, verified live
     *                              against the dev realm: the {@code
     *                              offline_access} scope this client requests
     *                              (so the refresh endpoint keeps working
     *                              across a browser restart) turns the refresh
     *                              token into an <em>offline</em> token, and
     *                              Keycloak reports zero rather than a real
     *                              instant for one — it does not expire on a
     *                              schedule, only on revocation or the
     *                              realm's offline-session idle timeout.
     *                              Treating zero as "already expired" would
     *                              have told every freshly signed-in caller
     *                              their session was over before the response
     *                              finished arriving.
     */
    record Issued(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            @Nullable Instant refreshTokenExpiresAt,
            String tokenType)
            implements TokenOutcome {

        /** A record's generated {@code toString} would print both tokens. */
        @Override
        public String toString() {
            return "TokenOutcome.Issued[accessTokenExpiresAt=%s, refreshTokenExpiresAt=%s]"
                    .formatted(accessTokenExpiresAt, refreshTokenExpiresAt);
        }
    }

    /** Keycloak refused the grant. {@link #reason()} is one of exactly two distinguishable outcomes. */
    record Refused(FailureReason reason) implements TokenOutcome {}

    enum FailureReason {
        /**
         * Wrong password, unknown username, a locked or disabled account --
         * every credential failure Keycloak can report, folded into one
         * outcome so a caller cannot learn which of them happened.
         */
        INVALID_CREDENTIALS,

        /** The credentials were correct; a required action stands in the way of a session. */
        ACCOUNT_ACTION_REQUIRED
    }
}
