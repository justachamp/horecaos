package uz.horecaos.platform.customers.infrastructure.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import uz.horecaos.platform.customers.application.CustomerSessionService;
import uz.horecaos.platform.customers.application.CustomerSessionService.Resolution;
import uz.horecaos.platform.customers.domain.CustomerSessionToken;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Turns a presented customer session token into the request's principal
 * (ADR 0051, ADR 0031).
 *
 * <p>Runs before the resource server's bearer filter and only ever looks at a
 * value carrying {@link CustomerSessionToken#PREFIX}. A staff token, an absent
 * header, and a request to the pre-account surface all fall straight through, so
 * this filter cannot change how anything that existed before it behaves.
 *
 * <p><strong>It answers its own failures rather than letting the chain answer
 * them.</strong> {@code GlobalApiErrorHandler} is a controller advice and a filter
 * is upstream of every controller, so an exception thrown here would leave the
 * container to render whatever it renders. More importantly, the whole point of
 * this filter's failure path is a distinction the generic entry point cannot
 * make: a session that ended is not the same as no session, and a customer whose
 * token expired mid-basket must be told to sign in again rather than shown the
 * screen a stranger sees. So the two are separate ADR 0031 problem codes, decided
 * here, where the difference is known.
 *
 * <p>A refused token stops the request. Continuing unauthenticated would reach
 * {@code .anyRequest().authenticated()} and produce a bare 401 with no code in it,
 * which is the generic answer this filter exists to avoid — and on a
 * {@code permitAll} path it would be worse, silently serving a customer who
 * believes they are signed in as though they were not.
 *
 * <p>Nothing here is logged. A rejected token is a credential, and the number
 * behind it is ADR 0029 personal data; the events worth keeping are the audit
 * facts the session service writes.
 */
@Component
public class CustomerSessionAuthenticationFilter extends OncePerRequestFilter {

    private final CustomerSessionService sessions;
    private final ObjectMapper json;

    public CustomerSessionAuthenticationFilter(CustomerSessionService sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String presented = CustomerSessionBearerTokenResolver.presentedBearer(request);
        if (!CustomerSessionToken.looksLikeOne(presented)) {
            chain.doFilter(request, response);
            return;
        }

        Resolution resolution = sessions.resolve(presented);
        switch (resolution.state()) {
            case ACTIVE -> {
                // A fresh context rather than mutating the one already held. The
                // held one can be a deferred or shared instance, and writing
                // through it is how an authentication ends up somewhere it was
                // never set.
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new CustomerSessionAuthentication(resolution.session()));
                SecurityContextHolder.setContext(context);
                try {
                    chain.doFilter(request, response);
                } finally {
                    // The context is a thread local and the container's threads
                    // are pooled. Spring's own SecurityContextHolderFilter clears
                    // it, but that filter sits further down the chain than this
                    // one for staff requests and there is no guarantee it clears a
                    // context this filter set. Leaking one would hand the next
                    // request on the same thread somebody else's account.
                    SecurityContextHolder.clearContext();
                }
            }
            // Both an expired session and a signed-out one. Real, and over: the
            // caller was signed in, and the storefront's answer is to sign them in
            // again rather than to treat them as a stranger.
            case ENDED -> refuse(response, ErrorCode.SESSION_EXPIRED,
                    "Your session has ended. Sign in again.");
            // Not a session this platform issued. Nothing here says whether it
            // ever was one, because there is nothing to say: the caller invented a
            // 256-bit value.
            case UNKNOWN -> refuse(response, ErrorCode.UNAUTHENTICATED,
                    "This session is not valid. Sign in again.");
        }
    }

    private void refuse(HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {

        ProblemDetail problem = ApiProblem.of(code, detail);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(), problem);
    }
}
