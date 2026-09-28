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
 * <p><strong>Removes duplicated beans, not duplicated Spring contexts.</strong>
 * A {@code static class StubIssuer} nested inside a test class is a
 * distinct {@code Class<?>} in every file even when its body is
 * byte-for-byte identical, so roughly ninety {@code @SpringBootTest} suites
 * each declaring their own copy meant roughly ninety duplicated six-line
 * {@code @TestConfiguration}s. Importing this one class instead removes
 * that duplication for every suite that switches to it — nothing more.
 *
 * <p>It deliberately does <strong>not</strong> make those suites share a
 * cached {@code ApplicationContext}. Spring's test-context cache keys a
 * context on (among other things) the exact {@code @DynamicPropertySource}
 * {@code Method} objects it finds on the test class, and {@code
 * java.lang.reflect.Method.equals} treats two methods as different the
 * moment their <em>declaring class</em> differs — regardless of how
 * identical their bodies read. Every suite that imports this class still
 * declares its own {@code properties(DynamicPropertyRegistry)} method,
 * opening its own {@link uz.horecaos.platform.support.TestDatabase}, so
 * every one of them keys to its own context and its own database; an
 * earlier version of this javadoc claimed otherwise ("shares a single
 * cached context with every other suite that does the same") and it was
 * wrong — verified empirically (two importing suites run back to back in
 * one fork each print their own "Starting/Started" Spring Boot banner,
 * open their own {@code HikariPool}, and clone their own database) as well
 * as by {@link StubJwtIssuerContextSharingClaimTests}, which fails the
 * moment any importer stops owning its method.
 *
 * <p>Real sharing would need every importer to <em>inherit</em> one
 * {@code @DynamicPropertySource} method, unmodified, from a common base
 * class — and this repository deliberately does not do that here:
 * {@link uz.horecaos.platform.support.TestDatabase}'s own class doc calls a
 * database shared across test classes "not an optimization to take back",
 * because several suites reset state more narrowly than a full TRUNCATE
 * and would corrupt each other silently if they ever shared one.
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
