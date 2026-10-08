package uz.horecaos.platform.iam.api.accounts;

import java.time.Instant;
import java.util.List;

/**
 * A staff account's live sessions at the identity provider (ADR 0176).
 *
 * <p>Keycloak is the authority for whether a session is alive; the platform's own sign-in record
 * only says what a session was. Two operations, on the same provisioning credential that already
 * ends sessions for a password reset ({@link StaffAccounts#logoutEverywhere}), and no new role:
 * the list and the single delete were probed live to work on {@code manage-users} and {@code
 * view-users} alone (infra/keycloak/spikes/staff-sessions-probe.py).
 *
 * <p>Deliberately exposes no network address. Keycloak's session representation carries one, and
 * the adapter drops it before anything is returned: a session here is an id and two times.
 */
public interface StaffSessions {

    /**
     * The account's sessions Keycloak holds right now: the offline sessions of the staff sign-in
     * client (every staff refresh token is an offline token) and any online session, merged by id.
     *
     * @throws SessionsUnavailableException when Keycloak cannot be asked, or when the sign-in
     *     client's id is not configured; callers show the platform's own record instead
     */
    List<LiveSession> list(String subjectId);

    /**
     * Ends one session.
     *
     * @return false when Keycloak no longer holds it (already gone is the state the caller wanted)
     * @throws SessionsUnavailableException when Keycloak could not be asked, so nothing is known
     *     to have ended
     */
    boolean end(String sessionId);

    /** One session as far as the screen needs it: an id and two times. */
    record LiveSession(String id, Instant startedAt, Instant lastAccessAt) {}

    /** Keycloak could not be asked, or refused. Carries no response body: it can hold an address. */
    final class SessionsUnavailableException extends RuntimeException {

        public SessionsUnavailableException(String message) {
            super(message);
        }

        public SessionsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
