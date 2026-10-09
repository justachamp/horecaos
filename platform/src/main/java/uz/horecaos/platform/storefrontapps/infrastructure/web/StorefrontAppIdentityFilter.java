package uz.horecaos.platform.storefrontapps.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppHeaders;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppIdentity;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppGate;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppGate.Verdict;
import uz.horecaos.platform.web.api.ApiProblem;

/**
 * Puts every storefront request through the app tier (ADR 0070).
 *
 * <p>Runs for the whole {@code /api/v1/storefront/} surface, <strong>the {@code permitAll}
 * browse paths included</strong>: that is the point. Those paths stay anonymous for the
 * customer, who has no session before they have an account, but they no longer stay
 * unattributed. The filter sits ahead of the customer-session filter and ahead of the
 * authorisation decision, so an app that is not allowed is refused by name whether or not the
 * path would also have wanted a customer.
 *
 * <p>It answers its own refusals, for the reason {@code CustomerSessionAuthenticationFilter}
 * does: a filter is upstream of every controller advice, and the whole value of this one is a
 * refusal that says <em>which</em> thing is wrong, not the bare 401 the chain would produce.
 *
 * <p>Nothing here is logged. The presented secret is a credential, and an origin is
 * caller-chosen text; the events worth keeping are the counters the gate increments.
 */
@Component
public class StorefrontAppIdentityFilter extends OncePerRequestFilter {

    private static final String SURFACE = "/api/v1/storefront/";

    private static final String UUID_SEGMENT =
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";

    /** {@code /tenants/{tenantId}/brands/{brandId}/…}: a brand's own surface. */
    private static final Pattern BRAND_PATH =
            Pattern.compile("^" + SURFACE + "tenants/" + UUID_SEGMENT + "/brands/" + UUID_SEGMENT + "(?:/.*)?$");

    /** {@code /tenants/{tenantId}/…} with no brand: channels, media, availability. */
    private static final Pattern TENANT_PATH =
            Pattern.compile("^" + SURFACE + "(?:loyalty/)?tenants/" + UUID_SEGMENT + "(?:/.*)?$");

    private final StorefrontAppGate gate;
    private final ObjectMapper json;

    public StorefrontAppIdentityFilter(StorefrontAppGate gate, ObjectMapper json) {
        this.gate = gate;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // A CORS preflight carries none of the app's headers by design; the request it announces does.
        return !path(request).startsWith(SURFACE) || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = path(request);
        UUID tenantId = null;
        UUID brandId = null;
        Matcher brand = BRAND_PATH.matcher(path);
        if (brand.matches()) {
            tenantId = UUID.fromString(brand.group(1));
            brandId = UUID.fromString(brand.group(2));
        } else {
            Matcher tenant = TENANT_PATH.matcher(path);
            if (tenant.matches()) {
                tenantId = UUID.fromString(tenant.group(1));
            }
        }

        Verdict verdict = gate.check(new StorefrontAppGate.Request(
                request.getHeader(StorefrontAppHeaders.APP_ID),
                request.getHeader(StorefrontAppHeaders.APP_SECRET),
                request.getHeader(HttpHeaders.ORIGIN),
                request.getHeader(HttpHeaders.REFERER),
                tenantId,
                brandId));

        switch (verdict) {
            case Verdict.Attributed attributed -> {
                request.setAttribute(StorefrontAppIdentity.REQUEST_ATTRIBUTE, attributed.identity());
                chain.doFilter(request, response);
            }
            case Verdict.Unattributed unattributed -> chain.doFilter(request, response);
            case Verdict.Refused refused -> refuse(response, refused);
        }
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private void refuse(HttpServletResponse response, Verdict.Refused refused) throws IOException {
        ProblemDetail problem = ApiProblem.of(refused.code(), refused.detail());
        response.setStatus(refused.code().status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(), problem);
    }
}
