package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Two claims of a token Keycloak itself just issued over the back channel (ADR 0176): the
 * session id ({@code sid}) and the ids of the organizations the account belongs to.
 *
 * <p>Read from the payload with no signature check, for the reason {@link
 * TokenOutcome.Issued#hasRealmRole} gives: the token came straight from Keycloak a moment ago and
 * nothing here trusts it for anything but recording a device label and a time. A token that
 * cannot be read answers "no session id" and "no organizations", which only ever means the
 * sign-in is not recorded -- it is never a reason to fail the sign-in. Nothing here is logged.
 */
public final class TokenClaims {

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private TokenClaims() {}

    /** The Keycloak session the token belongs to, or null when the token carries none. */
    public static @Nullable String sessionId(String jwt) {
        Map<?, ?> claims = payload(jwt);
        if (claims.get("sid") instanceof String sid && !sid.isBlank() && sid.length() <= 64) {
            return sid;
        }
        // An offline refresh token names its session as session_state in older realms.
        if (claims.get("session_state") instanceof String state && !state.isBlank() && state.length() <= 64) {
            return state;
        }
        return null;
    }

    /**
     * The Keycloak organization ids in the {@code organization} claim, the map form {@code
     * JwtCurrentActor} reads (alias to a representation carrying {@code id}).
     */
    public static Set<String> organizationIds(String jwt) {
        Set<String> ids = new LinkedHashSet<>();
        if (payload(jwt).get("organization") instanceof Map<?, ?> organizations) {
            for (Object organization : organizations.values()) {
                if (organization instanceof Map<?, ?> representation
                        && representation.get("id") instanceof String id
                        && !id.isBlank()) {
                    ids.add(id.strip());
                }
            }
        }
        return Set.copyOf(ids);
    }

    private static Map<?, ?> payload(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return Map.of();
        }
        try {
            Map<?, ?> claims = TokenOutcome.JSON.readValue(
                    new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8), Map.class);
            return claims == null ? Map.of() : claims;
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }
}
