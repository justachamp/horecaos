package uz.horecaos.platform.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * A stub {@link JwtDecoder} bean for an HTTP-layer {@code @SpringBootTest}
 * suite that authenticates requests through Spring Security Test's own
 * {@code jwt()} request post-processor rather than a real token — the
 * resource-server filter chain still needs a {@link JwtDecoder} bean to wire
 * up at startup, but nothing here ever decodes a real one; {@code jwt()}
 * injects an already-authenticated principal directly.
 *
 * <p><strong>Shared on purpose, not just to avoid repeating six lines.</strong>
 * Spring's test context cache keys an application context on the exact
 * configuration classes it was built from, and a {@code static class
 * StubIssuer} nested inside a test class is a distinct {@code Class<?>} in
 * every file even when its body is byte-for-byte identical — so upwards of
 * eighty {@code @SpringBootTest} suites each declaring their own copy meant
 * upwards of eighty separate application contexts, each paying the full
 * Spring Boot startup cost the cache exists to avoid paying more than once.
 * A suite that otherwise declares nothing but {@code @SpringBootTest} and
 * {@code @AutoConfigureMockMvc} and imports this one class instead shares a
 * single cached context with every other suite that does the same.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubJwtIssuer {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "unused")
                .build();
    }
}
