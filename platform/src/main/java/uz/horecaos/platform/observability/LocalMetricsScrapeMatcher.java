package uz.horecaos.platform.observability;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * Matches a metrics scrape that arrived on the container's own loopback (ADR
 * 0023, ADR 0034).
 *
 * <p>The on-box probe reads {@code /actuator/prometheus} through
 * {@code docker compose exec}, which runs inside the container, so the request
 * genuinely originates on {@code 127.0.0.1}. It cannot present a bearer token:
 * minting one needs Keycloak, and the alert path must not depend on the things
 * it is monitoring — a probe that goes quiet because the identity provider is
 * down is a probe that fails at the only moment it matters.
 *
 * <p><strong>The address is read from before the proxy headers were applied.</strong>
 * {@code server.forward-headers-strategy: framework} installs {@code
 * ForwardedHeaderFilter} at the highest precedence there is, and that filter
 * rewrites {@code getRemoteAddr()} from {@code X-Forwarded-For} and then hides
 * the header from everything downstream. Checking either the address or the
 * header from inside the security chain would therefore permit exactly the
 * request this refuses: a caller who claims to be {@code 127.0.0.1} looks like
 * one. {@link RawRemoteAddress} captures the real peer address in a servlet
 * request listener, which the container fires before the first filter, and this
 * matcher reads that.
 *
 * <p>This is the third of three independent controls rather than the only one.
 * The application port is not published on the host, and the edge answers 404
 * for every {@code /actuator/*} path except the two health probes, so nothing on
 * the internet can reach this endpoint at all. This matcher is what stands
 * behind both of those if either is ever misconfigured — which is ADR 0023's own
 * argument about the Payme source allowlist: defence in depth, never the only
 * check.
 *
 * <p>What is being protected is worth stating, because it is not a credential.
 * A scrape describes the platform's internals — route names, provider names,
 * queue depths — to whoever reads it. Under ADR 0029 it contains no personal
 * data and no tenant identifier by construction, so the exposure is
 * reconnaissance rather than disclosure. That is why loopback is a proportionate
 * control here and would not be for anything that returned a customer's data.
 */
@Component
public class LocalMetricsScrapeMatcher implements RequestMatcher {

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    @Override
    public boolean matches(HttpServletRequest request) {
        if (!PROMETHEUS_PATH.equals(request.getRequestURI())) {
            return false;
        }
        Object raw = request.getAttribute(RawRemoteAddress.ATTRIBUTE);
        if (!(raw instanceof String remote)) {
            // No listener ran, so the address cannot be trusted. Refusing is the
            // only safe reading: the alternative is that a misconfiguration turns
            // this into a rule that permits any caller.
            return false;
        }
        return "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
    }
}
