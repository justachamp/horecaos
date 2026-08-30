package uz.horecaos.platform.customers.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.customers.domain.CustomerSessionToken;

/**
 * Keeps a customer session token away from the JWT decoder (ADR 0051).
 *
 * <p>Both principal models arrive in the same header, which is deliberate: a
 * customer session is a bearer credential and pretending otherwise would give the
 * storefront a second, bespoke header to remember, when {@code Authorization} is
 * what every HTTP client already knows how to attach. ADR 0047's dine-in guest
 * token went the other way for a reason that does not apply here — it is not a
 * platform session at all, and it authorises paths that are {@code permitAll}.
 *
 * <p>What this class prevents is the resource server picking one up. Spring's
 * {@link DefaultBearerTokenResolver} hands anything after {@code Bearer } to the
 * JWT decoder, which fails, and the 401 that comes back describes a malformed
 * token rather than the situation the caller is in. Worse, it fails
 * <em>first</em>: the customer filter never runs, so a perfectly good session
 * would be refused with an explanation about signature validation. It would also
 * reject one outright before reaching a decoder at all — its own header pattern
 * excludes {@code _}, which is in the Base64url alphabet a session token is drawn
 * from — so the failure is not even consistent.
 *
 * <p>So the prefix is checked here, before delegation, and a customer token
 * resolves to no bearer token at all as far as the resource server is concerned.
 * Every other value is passed through untouched, which keeps staff authentication
 * exactly as it was.
 */
@Component
public class CustomerSessionBearerTokenResolver implements BearerTokenResolver {

    private static final String BEARER = "Bearer ";

    private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        if (CustomerSessionToken.looksLikeOne(presentedBearer(request))) {
            return null;
        }
        return delegate.resolve(request);
    }

    /**
     * The raw value after {@code Bearer }, or null.
     *
     * <p>Read from the header directly rather than through the delegate, because
     * the delegate is the thing being kept away from it.
     */
    public static String presentedBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }
}
