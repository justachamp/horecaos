package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link StaffDirectGrantClient} learned from one call to Keycloak's
 * token endpoint (ADR 0062): a fresh token pair, or a refusal classified into
 * exactly the two outcomes the ADR lets a caller tell apart.
 */
public sealed interface TokenOutcome {

    JsonMapper JSON = JsonMapper.builder().build();

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

        /**
         * Whether the access token Keycloak just issued carries this realm role (ADR 0148).
         *
         * <p>Read from the payload with no signature check, because the token came straight from
         * Keycloak over the back channel a moment ago and nothing here trusts it for anything but
         * this question: whether the bootstrap platform administrator, who may hold no grant row,
         * is the account that just signed in. A token that cannot be read answers {@code false},
         * which only ever means "ask the grant tables instead".
         */
        public boolean hasRealmRole(String role) {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return false;
            }
            try {
                Map<?, ?> claims = JSON.readValue(
                        new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), Map.class);
                return claims.get("realm_access") instanceof Map<?, ?> access
                        && access.get("roles") instanceof Collection<?> roles
                        && roles.contains(role);
            } catch (RuntimeException unreadable) {
                return false;
            }
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
