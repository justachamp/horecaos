package uz.horecaos.platform.iam.api.terminals;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * The seam the capability interceptor calls for every request that carries a staff or device JWT (ADR 0155), so the
 * interceptor stays the single place a capability is checked and {@code web} depends on this contract and not on
 * the terminals' internals.
 */
public interface TerminalSessionGate {

    /** The header a device sends its session token in. The token is accepted only with the device's own bearer. */
    String SESSION_HEADER = "X-Terminal-Session";

    /** The header a device reports its build in, stamped beside {@code last_seen_at}. */
    String BUILD_HEADER = "X-Device-Build";

    /**
     * Notes that the principal made an authenticated request, for the registry's «last seen» (at most once a minute
     * per device per node). Does nothing for a principal that is not an enrolled device, and never fails a request.
     */
    void observe(String principalSubject, @Nullable String build);

    /**
     * What the interceptor knows about the request when it asks.
     *
     * @param principalSubject the caller's Keycloak subject
     * @param sessionToken     the {@code X-Terminal-Session} header, null when absent
     * @param build            the {@code X-Device-Build} header, null when absent
     * @param mutating         {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}: the only requests that extend a session
     * @param sessionEndpoint  the request is one of the {@code terminal-session} family, which opens, touches and ends
     *                         sessions itself and is neither required to carry one nor attributed to one
     * @param capability       what the endpoint declares
     * @param scope            where it declares it
     */
    record Admission(
            String principalSubject,
            @Nullable String sessionToken,
            @Nullable String build,
            boolean mutating,
            boolean sessionEndpoint,
            Capability capability,
            ResourceScope scope) {}

    /**
     * After ADR 0025's checks for the principal itself: resolves who is acting.
     *
     * <ul>
     *   <li>a principal that is not an enrolled device, with a request that nonetheless carries the session header, is
     *       refused: a session belongs to the device it was issued to;
     *   <li>a device of a class nobody unlocks, or in {@code DEVICE_ONLY}, ignores the header and is the actor;
     *   <li>{@code PERSON_OPTIONAL} attributes to the person when a live session is presented;
     *   <li>{@code PERSON_REQUIRED} refuses a mutating request that carries no live session.
     * </ul>
     *
     * Also stamps the device's «last seen».
     *
     * @return the attribution, or empty when the device (or a staff principal) is the actor
     * @throws uz.horecaos.platform.web.api.ApiException {@code TERMINAL_UNLOCK_REQUIRED}, {@code
     *     TERMINAL_SESSION_ENDED} or {@code TERMINAL_PERSON_NOT_AUTHORIZED}
     */
    Optional<TerminalAttribution> admit(Admission request);
}
