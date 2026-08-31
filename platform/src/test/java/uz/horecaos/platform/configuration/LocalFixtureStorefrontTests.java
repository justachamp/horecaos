package uz.horecaos.platform.configuration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
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
