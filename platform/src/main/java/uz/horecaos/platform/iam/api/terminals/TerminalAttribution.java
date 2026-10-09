package uz.horecaos.platform.iam.api.terminals;

import java.util.UUID;

/**
 * Who is acting at a shared terminal for the request in flight (ADR 0155): the person whose PIN opened the live
 * terminal session the device presented, and the screen they are at.
 *
 * <p>Published by the capability interceptor after it has run ADR 0025's checks for the device, validated the
 * session in the database and asked {@code AuthorizationService} whether the <em>person</em> holds the same
 * capability at the same scope. A request with no attribution is attributed to the device, exactly as before.
 *
 * <p>An attribution confers nothing. It names; the authorization already happened, as an intersection of what
 * the device may do and what the person may do.
 *
 * @param sessionId       the {@code iam.terminal_sessions} row, for the {@code terminal:<sessionId>} evidence reference
 * @param deviceId        the device the person is unlocked at
 * @param memberId        the staff member row (never the Keycloak subject, which {@code personSubject} carries)
 * @param personSubject   the subject string the person's own console actions carry, so an audit fact, a ticket event
 *                        and an order attribution read as one person whichever screen they used
 */
public record TerminalAttribution(UUID sessionId, UUID deviceId, UUID memberId, String personSubject) {

    /** The evidence reference an audit fact carries for an action taken through this session. */
    public String evidenceReference() {
        return "terminal:" + sessionId;
    }
}
