package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/** Exercises the same profile and unauthenticated requests documented for a local developer. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalFixtureStorefrontTests {

    private static final String LOCATION_PATH = "/api/v1/storefront/tenants/"
            + "10000000-0000-0000-0000-000000000001/brands/"
            + "10000000-0000-0000-0000-000000000002/locations/"
            + "10000000-0000-0000-0000-000000000003";

    // Populated by @DynamicPropertySource, a static hook Spring's test runner
    // guarantees runs before context startup and every test method -- earlier
    // than any field initializer NullAway would otherwise accept, and a
    // sequencing contract it has no visibility into.
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle database;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the local fixture storefront test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        database = TestDatabase.migrated();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void localProfileMakesTheDocumentedPublicRequestsUseful() throws Exception {
        mvc.perform(get("/api/v1/storefront/pickup-locations?lat=41.311341&lon=69.282722"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locations.length()").value(1))
                .andExpect(jsonPath("$.locations[0].locationId").value("10000000-0000-0000-0000-000000000003"))
                .andExpect(jsonPath("$.locations[0].available").value(true))
                .andExpect(jsonPath("$.locations[0].distanceMeters").isNumber());

        // The channel is required, like the serviceability call below it: ADR 0036
        // makes it supply both the publication and the price plane, and a menu
        // priced against another channel is a menu whose prices change at
        // checkout.
        mvc.perform(get(LOCATION_PATH + "/menu?locale=uz&channel=STOREFRONT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("uz"))
                .andExpect(jsonPath("$.products.length()").value(3))
                .andExpect(jsonPath("$.products[2].code").value("SHASHLIK"))
                .andExpect(jsonPath("$.products[2].variants[0].orderable").value(false))
                // The menu carries what a storefront needs to render a price. A
                // null here is the whole reason browse screens could not be built
                // against the published menu before.
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andExpect(jsonPath("$.products[0].variants[0].amountMinor").value(45000));

        mvc.perform(get(LOCATION_PATH + "/serviceability?channel=STOREFRONT&mode=PICKUP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.preparationMinutes").value(20));

        mvc.perform(get(LOCATION_PATH + "/delivery-fee?lat=41.3120&lon=69.2410&currency=UZS&subtotalMinor=100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.currency").value("UZS"));
    }

    @Test
    void anonymousErrorsKeepTheirRealStatus() throws Exception {
        // A public request that errors — the menu without its required channel —
        // must answer with its own status. In a servlet container the error body
        // is rendered by an internal forward to /error, and until
        // SecurityConfiguration permitted the ERROR dispatch, that forward was
        // denied and every anonymous error masqueraded as an empty 401. MockMvc
        // never performs the forward, so the masquerade itself cannot appear
        // here; what this pins is the handler half — the 400 the forward exists
        // to render. The dispatch half is the dispatcherTypeMatchers line in
        // SecurityConfiguration.
        mvc.perform(get(LOCATION_PATH + "/menu?locale=uz")).andExpect(status().isBadRequest());
    }

    @Test
    void missingChannelAnswersWithProblemDetailsNotTheContainerDefault() throws Exception {
        // MissingServletRequestParameterException is raised by Spring MVC's own
        // argument resolution, before any controller method runs, so no
        // @ExceptionHandler in this codebase used to see it. It fell all the way
        // to DefaultHandlerExceptionResolver, which renders the servlet
        // container's default error body — {"timestamp":...,"status":400,
        // "error":"Bad Request","path":...} — instead of this platform's ADR
        // 0031 Problem Details. GlobalApiErrorHandler now extends
        // ResponseEntityExceptionHandler and overrides
        // handleMissingServletRequestParameter to close exactly this gap.
        String responseBody = mvc.perform(get(LOCATION_PATH + "/menu?locale=uz"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://docs.horecaos.uz/problems/validation-failed"))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Required request parameter 'channel' is not present"))
                .andExpect(jsonPath("$.errors[0].field").value("channel"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The old container default shape must be gone, not merely unasserted.
        assertThat(responseBody).doesNotContain("\"timestamp\"").doesNotContain("\"error\":\"Bad Request\"");
    }

    @Test
    void nonUuidPathSegmentAnswersWithProblemDetails() throws Exception {
        // The other classic framework binding failure alongside a missing
        // parameter: a {tenantId} path segment that does not parse as a UUID.
        // MethodArgumentTypeMismatchException is a TypeMismatchException, caught
        // by the same override that handles conversion failures in general.
        String path = "/api/v1/storefront/tenants/not-a-uuid/brands/"
                + "10000000-0000-0000-0000-000000000002/locations/"
                + "10000000-0000-0000-0000-000000000003/menu?locale=uz&channel=STOREFRONT";

        mvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("tenantId"))
                .andExpect(jsonPath("$.errors[0].code").value("MALFORMED"));
    }

    @Test
    void fixturelessTenantWithValidParametersAnswersNotFoundAsProblemDetails() throws Exception {
        // Valid UUIDs, a valid channel and locale, but a tenant/brand/location
        // this fixture never published. StorefrontCatalogQuery.menuFor already
        // returns Optional.empty() for an unknown (tenantId, brandId, channel),
        // and the controller maps that to ApiException(RESOURCE_NOT_FOUND) —
        // this pins that the fixtureless path was already contract-shaped before
        // this change, distinct from the framework-level bug this change fixes.
        String path = "/api/v1/storefront/tenants/"
                + "20000000-0000-0000-0000-000000000001/brands/"
                + "20000000-0000-0000-0000-000000000002/locations/"
                + "20000000-0000-0000-0000-000000000003/menu?locale=uz&channel=STOREFRONT";

        mvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("This brand has no published menu"));
    }

    @Test
    void unmappedRouteAnswersWithProblemDetailsNotResourceNotFound() throws Exception {
        // ADR 0031's residual gap: no controller, no static resource, nothing at
        // this path at all. Spring resolves this through the resource handler
        // mapping's own NoResourceFoundException, which — before
        // GlobalApiErrorHandler.handleNoResourceFoundException existed — fell to
        // the base class's plain RFC 9457 shape: about:blank type, no `code`.
        // Authenticated on purpose: SecurityConfiguration's anyRequest().authenticated()
        // answers an anonymous hit to this same path with 401 before the request
        // ever reaches MVC dispatch, which would prove nothing about this handler.
        String responseBody = mvc.perform(get("/api/v1/operations/this-route-does-not-exist")
                        .with(jwt().jwt(builder -> builder.subject("some-staff-member"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://docs.horecaos.uz/problems/route-not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // ROUTE_NOT_FOUND, not RESOURCE_NOT_FOUND: this path never existed, and
        // conflating "no such route" with "no such entity at a real route" would
        // tell a client to check an id that was never the problem.
        assertThat(responseBody).doesNotContain("RESOURCE_NOT_FOUND").doesNotContain("\"timestamp\"");
    }

    @Test
    void unacceptableAcceptHeaderAnswersWithProblemDetails() throws Exception {
        // Every response this API returns is JSON (ADR 0031). A caller asking
        // for anything else finds out from Problem Details rather than the base
        // class's about:blank shape.
        mvc.perform(get("/api/v1/storefront/pickup-locations?lat=41.311341&lon=69.282722")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.type").value("https://docs.horecaos.uz/problems/not-acceptable"))
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void missingGuestTokenHeaderAnswersWithProblemDetails() throws Exception {
        // QrEntryController's bill read requires X-Dine-In-Token with no default
        // (ADR 0047) -- MissingRequestHeaderException, a ServletRequestBindingException
        // Spring MVC raises before the controller method ever runs. The path itself
        // is permitAll (SecurityConfiguration), so no principal is needed to reach it.
        mvc.perform(get("/api/v1/storefront/dine-in/sessions/" + UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("X-Dine-In-Token"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    /** Avoids contacting Keycloak; the requests above are deliberately public. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
