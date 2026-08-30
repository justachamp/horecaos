package uz.horecaos.platform.payments.web.payme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The filter chain that lets the Payme endpoint answer its own authentication
 * failures (ADR 0013, ADR 0028).
 *
 * <p><strong>This is the deliberate bypass, and here is why it is deliberate.</strong>
 * Payme requires <em>every</em> response to carry HTTP 200 and reads any other
 * status as {@code -32400}. Its very first sandbox test — "Неверная авторизация" —
 * sends a bad credential and expects HTTP 200 with a JSON-RPC body containing
 * {@code -32504} and the request's own {@code id} echoed back. Spring Security's
 * stock {@code httpBasic()} answers a bad credential with a bodyless HTTP 401,
 * which fails that test before a single som has moved. Payme's own Java template
 * makes exactly this mistake, and it is the first of the eleven defects the
 * provider notes record against it.
 *
 * <p>So the path is {@code permitAll} at the filter chain and authenticated inside
 * {@link uz.horecaos.platform.payments.infrastructure.payme.PaymeCredentials}, before
 * any method is dispatched and for every method including {@code GetStatement}.
 * The endpoint is not unauthenticated; it is authenticated somewhere that can
 * choose the status code.
 *
 * <p>It is a chain of its own rather than an entry added to the platform's
 * {@code permitAll} list, for two reasons. A chain carries its own
 * {@code securityMatcher}, so the exemption is one line and is scoped to one path
 * with no wildcard that could grow; and the platform chain's bearer-token resource
 * server is simply absent here rather than configured and then bypassed, which is
 * the difference between "no token is expected" and "a token was expected and
 * something waived it".
 *
 * <p>The credential is per cashbox, so there is an endpoint per binding and the
 * binding segment is in the path. That segment is <strong>not</strong> a secret and
 * must not be treated as one — it is guessable by design, and the Basic credential
 * is the whole of the authentication. Payme also publishes the fifteen source
 * addresses it calls from, {@code 185.234.113.1–15}; allowlisting them belongs to
 * the ingress, as defence in depth and never as the only check.
 */
@Configuration(proxyBeanMethods = false)
class PaymeEndpointSecurity {

    /**
     * Ordered ahead of the platform chain, which declares no order and therefore
     * sorts last.
     *
     * <p>Ten rather than one, so that the other provider callbacks — Click's SHOP
     * API, which has no authentication header at all and authenticates by an MD5
     * over a secret-prefixed concatenation — can take their own numbers in the same
     * band without either of them having to be renumbered.
     */
    @Bean
    @Order(10)
    SecurityFilterChain paymeMerchantApiSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(PaymeMerchantApiController.PATH_PATTERN)
                // Payme is a server-to-server caller with no session and no browser,
                // so there is no cookie for a cross-site request to ride on and
                // nothing for a CSRF token to protect. A token would simply make
                // every genuine call fail.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
