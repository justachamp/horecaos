package uz.horecaos.platform.web.authorization;

import org.springframework.context.annotation.Configuration;

import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the ADR 0025 and ADR 0031 cross-cutting interceptors. */
@Configuration(proxyBeanMethods = false)
public class WebAuthorizationConfiguration implements WebMvcConfigurer {

    private final CapabilityEnforcementInterceptor capabilityInterceptor;
    private final IdempotencyInterceptor idempotencyInterceptor;

    public WebAuthorizationConfiguration(
            CapabilityEnforcementInterceptor capabilityInterceptor,
            IdempotencyInterceptor idempotencyInterceptor) {
        this.capabilityInterceptor = capabilityInterceptor;
        this.idempotencyInterceptor = idempotencyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Authorization runs first, and the order matters now that a capability
        // can actually refuse. Claiming the idempotency key before deciding
        // whether the caller may act settles the key on the refusal: the
        // interceptor records any sub-500 status as the outcome, so a 403 taken
        // while a grant was still missing would be replayed to that key for the
        // whole retention window, and the client would keep seeing the refusal
        // after the grant was created. Refusing before the claim leaves no
        // record to replay, and stops a caller who may not act from writing rows
        // into the idempotency table at all.
        //
        // It does mean a replay is authorized again, which is what should
        // happen: the stored response is the tenant's data, and a principal
        // whose grant was revoked must not read it back. The handler still never
        // runs a second time — that is what the replay itself prevents.
        registry.addInterceptor(capabilityInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/**");
    }
}
