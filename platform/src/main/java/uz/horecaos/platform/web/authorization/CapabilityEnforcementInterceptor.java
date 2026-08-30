package uz.horecaos.platform.web.authorization;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScopeVerifier;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * Applies the ADR 0025 capability declared by the handler, and refuses the
 * request when the principal does not hold it.
 *
 * <p>This ran in shadow mode from the day ADR 0025 shipped: the decision was
 * computed, compared against the ADR 0003 rule that was actually applied, and
 * logged, and nothing was ever denied. Shadow mode existed so that narrowing an
 * already-live rule could not deny requests that legitimately succeeded before
 * the grants existed. It is now the opt-out rather than the resting state —
 * {@code horecaos.authorization.enforce} defaults to true — because a capability
 * that has never once refused anything is a decision nobody has tested. A
 * missing grant should surface as a 403 while the frontends are being built,
 * not on a restaurant's first trading day.
 *
 * <p>Setting {@code horecaos.authorization.enforce} to false restores the
 * comparison, which is what an operator would do to re-measure the divergence
 * against a live estate before a bundle change. The comparison runs in
 * {@code afterCompletion} because that is the only point where the applied
 * decision is known: a 403 means the live rule denied the request, anything
 * else means it allowed it.
 *
 * <p>The refusal is an {@link AuthorizationService.AccessDeniedException}, which
 * {@code GlobalApiErrorHandler} renders as ADR 0031's
 * {@code INSUFFICIENT_CAPABILITY}. It names the missing capability and the scope
 * level, never the grants or policy that produced the decision.
 */
@Component
public class CapabilityEnforcementInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityEnforcementInterceptor.class);

    private final AuthorizationService authorization;
    private final ResourceScopeVerifier scopes;
    private final CurrentActor currentActor;
    private final MeterRegistry meters;
    private final boolean enforce;

    public CapabilityEnforcementInterceptor(
            AuthorizationService authorization,
            ResourceScopeVerifier scopes,
            CurrentActor currentActor,
            MeterRegistry meters,
            @Value("${horecaos.authorization.enforce:true}") boolean enforce) {
        this.authorization = authorization;
        this.scopes = scopes;
        this.currentActor = currentActor;
        this.meters = meters;
        this.enforce = enforce;
    }

    /**
     * Refuses a scope whose identifiers do not name a real hierarchy.
     *
     * <p>The capability check cannot catch this on its own: the scope is
     * assembled from path variables, and {@code covers} deliberately lets a
     * tenant-scoped grant reach every brand beneath that tenant. Put those
     * together and a principal holding a capability in their own tenant can name
     * any brand identifier in the world and be authorised for it. This is what
     * makes "beneath that tenant" mean what it says.
     *
     * <p>It runs <em>after</em> the capability check, and the order is the whole
     * design. Ahead of it, the pair of answers becomes an oracle: 403 for a real
     * tenant the caller cannot reach, 404 for one that does not exist, and any
     * authenticated principal can enumerate the estate by watching which arrives.
     * Behind it, a caller only reaches this check for scopes their grant already
     * covers — so the only identifiers they can probe are ones inside a tenant
     * they are entitled to know about, while the foreign brand named under their
     * own tenant, which is the actual attack, still ends here.
     */
    private void requireRealScope(ResourceScope scope) {
        if (!scopes.exists(scope)) {
            throw new ScopeNotFoundException(scope);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enforce) {
            return true;
        }
        RequiresCapability declaration = declarationOf(handler);
        if (declaration == null) {
            return true;
        }
        ResourceScope scope = scopeOf(request, declaration.scope());
        authorization.require(subject(), declaration.value(), scope);
        requireRealScope(scope);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {

        if (enforce) {
            return;
        }
        RequiresCapability declaration = declarationOf(handler);
        if (declaration == null) {
            return;
        }

        try {
            ResourceScope scope = scopeOf(request, declaration.scope());
            boolean capabilityAllows = authorization.has(subject(), declaration.value(), scope);
            boolean liveRuleAllowed = response.getStatus() != HttpServletResponse.SC_FORBIDDEN;

            String outcome = capabilityAllows == liveRuleAllowed
                    ? "agree"
                    : (liveRuleAllowed ? "would_deny" : "would_allow");

            Counter.builder("horecaos.authorization.shadow")
                    .description("ADR 0025 shadow-mode comparison against the live ADR 0003 rule")
                    .tag("capability", declaration.value().code())
                    .tag("scope", declaration.scope().name())
                    .tag("outcome", outcome)
                    .register(meters)
                    .increment();

            if (!"agree".equals(outcome)) {
                // "would_deny" is the signal that a grant is missing. "would_allow"
                // is the serious one: the capability model is more permissive than
                // the live rule.
                log.warn("ADR 0025 shadow divergence: capability={} scope={} outcome={} status={}",
                        declaration.value().code(), declaration.scope(), outcome, response.getStatus());
            }
        } catch (RuntimeException evaluationFailure) {
            // Shadow evaluation must never affect the response it is observing.
            log.warn("ADR 0025 shadow evaluation failed for {}", request.getRequestURI(), evaluationFailure);
        }
    }

    private RequiresCapability declarationOf(Object handler) {
        return handler instanceof HandlerMethod method
                ? method.getMethodAnnotation(RequiresCapability.class)
                : null;
    }

    private String subject() {
        return currentActor.get().subject();
    }

    @SuppressWarnings("unchecked")
    private ResourceScope scopeOf(HttpServletRequest request, ScopeType scopeType) {
        Map<String, String> variables = (Map<String, String>)
                request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Map<String, String> pathVariables = variables == null ? Map.of() : variables;

        return switch (scopeType) {
            case PLATFORM -> ResourceScope.platform();
            case TENANT -> ResourceScope.tenant(uuid(pathVariables, "tenantId"));
            case BRAND -> ResourceScope.brand(
                    uuid(pathVariables, "tenantId"), uuid(pathVariables, "brandId"));
            case LOCATION -> ResourceScope.location(
                    uuid(pathVariables, "tenantId"),
                    uuid(pathVariables, "brandId"),
                    uuid(pathVariables, "locationId"));
        };
    }

    private static UUID uuid(Map<String, String> pathVariables, String name) {
        String value = pathVariables.get(name);
        if (value == null) {
            throw new IllegalStateException(
                    "Endpoint declares a scope requiring the %s path variable".formatted(name));
        }
        return UUID.fromString(value);
    }
}
