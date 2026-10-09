package uz.horecaos.platform.web.authorization;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import uz.horecaos.platform.iam.api.terminals.TerminalAttribution;

/**
 * Where the capability interceptor leaves who is acting at a shared terminal for the rest of the request (ADR 0155).
 *
 * <p>A request attribute and nothing wider: it is gone with the request, it is never inherited by another thread
 * and it cannot outlive the session check that produced it. Readers -- {@code CurrentActor#attributed}, the audit
 * recorder's evidence reference, the kitchen's ticket events -- ask {@link #current()} and get an empty answer
 * outside a request, which is how a scheduler or a test that never went through the interceptor keeps attributing to
 * whoever it always did.
 */
public final class TerminalAttributionContext {

    private static final String ATTRIBUTE = "horecaos.terminalAttribution";

    private TerminalAttributionContext() {}

    public static void bind(HttpServletRequest request, TerminalAttribution attribution) {
        request.setAttribute(ATTRIBUTE, attribution);
    }

    public static Optional<TerminalAttribution> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof TerminalAttribution attribution ? Optional.of(attribution) : Optional.empty();
    }
}
