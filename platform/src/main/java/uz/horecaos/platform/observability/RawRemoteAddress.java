package uz.horecaos.platform.observability;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Records the address a request actually arrived from, before anything is
 * allowed to rewrite it.
 *
 * <p>This exists because {@code server.forward-headers-strategy: framework}
 * installs Spring Boot's {@code ForwardedHeaderFilter} at {@code
 * Ordered.HIGHEST_PRECEDENCE}, and that filter does two things a later check
 * cannot undo: it rewrites {@code getRemoteAddr()} from {@code X-Forwarded-For},
 * and it then <em>hides the forwarding headers from the wrapped request</em> so
 * that downstream code cannot apply them twice. By the time Spring Security runs,
 * a caller who set {@code X-Forwarded-For: 127.0.0.1} looks exactly like a call
 * from inside the container, and the header that would have given them away
 * reads as absent.
 *
 * <p>No filter can run earlier than {@code HIGHEST_PRECEDENCE}, so this is a
 * {@link ServletRequestListener} instead. The container fires {@code
 * requestInitialized} before the first filter in the chain, which is the only
 * point at which the raw address is still visible.
 *
 * <p>It is used by {@link LocalMetricsScrapeMatcher} and by nothing else. The
 * rest of the platform should keep using {@code getRemoteAddr()}: attributing a
 * request to the client that made it is exactly what the forwarded headers are
 * for, and the reverse proxy is trusted for that. What is different here is that
 * the address is being used as an <em>authorisation</em> input, and an
 * authorisation input a caller can set is not one.
 */
@Configuration(proxyBeanMethods = false)
public class RawRemoteAddress {

    static final String ATTRIBUTE = RawRemoteAddress.class.getName() + ".remoteAddr";

    @Bean
    ServletListenerRegistrationBean<ServletRequestListener> rawRemoteAddressListener() {
        return new ServletListenerRegistrationBean<>(new ServletRequestListener() {

            @Override
            public void requestInitialized(ServletRequestEvent event) {
                ServletRequest request = event.getServletRequest();
                if (request instanceof HttpServletRequest http) {
                    http.setAttribute(ATTRIBUTE, http.getRemoteAddr());
                }
            }
        });
    }
}
