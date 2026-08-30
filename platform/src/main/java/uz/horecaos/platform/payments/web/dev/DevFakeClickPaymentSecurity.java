package uz.horecaos.platform.payments.web.dev;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Lets a developer call {@link DevFakeClickPaymentController} with no bearer token
 * (ADR 0007, ADR 0013, ADR 0025).
 *
 * <p>The same shape {@code PaymeEndpointSecurity} uses for the same reason: a
 * dedicated chain with its own {@code securityMatcher}, rather than one more line
 * on the platform's {@code permitAll} list, so the exemption is scoped to one path
 * and cannot grow by accident. {@code @Profile("local")} on top of that is this
 * chain's own addition — Payme's callback is a real production surface with a real
 * bypass reason; this one only exists at all when {@code local} is active, so
 * outside a developer's laptop there is no bean, no matcher, and no path to permit.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
class DevFakeClickPaymentSecurity {

    /** Same band as {@code PaymeEndpointSecurity}'s {@code @Order(10)}; the two never overlap. */
    @Bean
    @Order(11)
    SecurityFilterChain devFakeClickPaymentFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/dev/fake-providers/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
