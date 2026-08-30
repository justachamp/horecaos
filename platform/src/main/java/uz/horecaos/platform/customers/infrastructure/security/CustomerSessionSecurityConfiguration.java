package uz.horecaos.platform.customers.infrastructure.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the customer session filter out of the servlet container's own chain
 * (ADR 0051).
 *
 * <p>Spring Boot registers every {@code Filter} bean with the container
 * automatically. That default is right for a correlation-id filter and wrong for
 * this one: it would run <em>before</em> the Spring Security chain, so the
 * security context it set would be replaced by {@code SecurityContextHolderFilter}
 * a moment later and every signed-in customer would arrive at their handler
 * anonymous. The symptom is the hardest kind to read — authentication that
 * demonstrably worked, followed by an authorization failure with no cause.
 *
 * <p>So the automatic registration is switched off and the filter is placed
 * deliberately, at one position, inside the platform's filter chain. There is
 * exactly one place that decides where it runs, and it is
 * {@code SecurityConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class CustomerSessionSecurityConfiguration {

    @Bean
    FilterRegistrationBean<CustomerSessionAuthenticationFilter> customerSessionFilterRegistration(
            CustomerSessionAuthenticationFilter filter) {

        FilterRegistrationBean<CustomerSessionAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
