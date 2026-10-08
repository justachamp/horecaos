package uz.horecaos.platform.iam.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import uz.horecaos.platform.iam.api.terminals.TerminalAttribution;

@FunctionalInterface
public interface CurrentActor {

    /** The authenticated principal of the request: for a request from a device, the device itself (ADR 0079). */
    AuthenticatedActor get();

    /**
     * Who is acting at a shared terminal for the request in flight, when a live terminal session was presented and
     * the person holds what the endpoint declares (ADR 0155). Empty outside a request and for every request not
     * carried by a session, which is every request that is not from a device a person has unlocked.
     */
    default Optional<TerminalAttribution> terminalAttribution() {
        return Optional.empty();
    }

    /**
     * The actor to <strong>record</strong>: the person whose PIN unlocked the terminal when a valid session is
     * present, otherwise {@link #get()}. Code that writes an actor into an audit fact, a ticket event or an order
     * attribution reads this one, so an action taken at a tablet carries the same subject string the person's
     * console actions carry. Code that asks what the caller <em>may do</em> keeps reading {@link #get()}, which is the
     * device: authorization is an intersection and never the person alone.
     *
     * <p>The attributed actor carries no roles of either kind -- it names, it does not authorize.
     */
    default AuthenticatedActor attributed() {
        return terminalAttribution()
                .map(attribution -> new AuthenticatedActor(attribution.personSubject(), Set.of(), Map.of()))
                .orElseGet(this::get);
    }
}
