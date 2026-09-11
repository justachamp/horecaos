package uz.horecaos.platform.tenancy.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Gap B of the 2026-08-30 proving run: V0098's seeded {@code default}
 * template is real, versioned reference data, and this is the read surface
 * for it. No {@code @BeforeEach} truncation — the migration-seeded row is the
 * thing under test, and every method here only reads.
 */
class OnboardingTemplateServiceTests {

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;
    private static OnboardingTemplateService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
        service = new OnboardingTemplateService(jdbc, JsonMapper.builder().build());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void v0098SeedsTheDefaultTemplateAsActive() {
        var template = service.currentDefault();

        assertThat(template.code()).isEqualTo("default");
        assertThat(template.version()).isEqualTo(1);
        assertThat(template.status()).isEqualTo("ACTIVE");
        assertThat(template.requiredSteps())
                .as("reference data, in OnboardingStep's own order")
                .containsExactly(
                        "KEYCLOAK_ORGANIZATION_RECONCILE",
                        "TENANT_OWNER_LINK_OR_INVITE",
                        "DEFAULT_CONFIGURATION_APPLY",
                        "BRANDS_AND_LOCATIONS_VALIDATE",
                        "PAYMENT_CONFIGURATION_VALIDATE",
                        "DELIVERY_CONFIGURATION_VALIDATE",
                        "POS_BINDINGS_VALIDATE",
                        "CATALOG_READINESS_VALIDATE",
                        "MEDIA_READINESS_VALIDATE",
                        "FRONTEND_DOMAIN_VALIDATE",
                        "ACTIVATION_SMOKE_TEST",
                        "TENANT_ACTIVATE");
    }

    /**
     * The other half of Gap B's D-gap wiring: the default template carries
     * RESTAURANT_APPROVAL as its default acceptance policy, in exactly the
     * shape {@code OrderAcceptancePolicy} deserialises.
     */
    @Test
    void theDefaultTemplateCarriesTheRestaurantApprovalAcceptancePolicy() {
        var template = service.currentDefault();

        @SuppressWarnings("unchecked")
        var acceptancePolicy =
                (java.util.Map<String, Object>) template.defaultConfiguration().get("acceptancePolicy");

        assertThat(acceptancePolicy).isNotNull();
        java.util.Objects.requireNonNull(acceptancePolicy);
        assertThat(acceptancePolicy.get("mode")).isEqualTo("RESTAURANT_APPROVAL");
        assertThat(acceptancePolicy.get("approvalChannel")).isEqualTo("HORECAOS_OPERATIONS");
    }

    @Test
    void getReturnsTheSameRowByItsOwnId() {
        var byDefault = service.currentDefault();

        var byId = service.get(byDefault.id());

        assertThat(byId).isEqualTo(byDefault);
    }

    @Test
    void getRefusesAnUnknownTemplate() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(ApiException.class);
    }

    @Test
    void listIncludesTheSeededDefault() {
        assertThat(service.list())
                .extracting(OnboardingTemplateService.TemplateView::code)
                .contains("default");
    }

    /**
     * ADR 0090 as decided on 2026-09-11: a business type pre-selects the
     * template that names it, and every other type falls back to the default.
     * The default names no type, so without the second template every
     * suggestion here would be the default and the first assertion could not
     * tell a match from a fallback.
     */
    @Test
    void aBusinessTypeSuggestsTheTemplateThatNamesItAndOtherwiseTheDefault() {
        UUID darkKitchen = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.onboarding_templates
                    (id, code, version, status, description, required_steps, default_configuration,
                     business_types, created_by)
                VALUES (:id, 'dark-kitchen', 1, 'ACTIVE', 'delivery only', '[]'::jsonb, '{}'::jsonb,
                        ARRAY['DARK_KITCHEN'], 'test')
                """).param("id", darkKitchen).update();
        try {
            var suggested = service.suggestedFor("DARK_KITCHEN");
            assertThat(suggested.template().id()).isEqualTo(darkKitchen);
            assertThat(suggested.matched()).isTrue();
            assertThat(suggested.template().businessTypes()).containsExactly("DARK_KITCHEN");

            var fallback = service.suggestedFor("CAFE");
            assertThat(fallback.template().code()).isEqualTo("default");
            assertThat(fallback.matched()).isFalse();
            assertThat(fallback.template().businessTypes())
                    .as("the default is what every type falls back to, not a choice any type made")
                    .isEmpty();

            assertThat(service.suggestedFor(null).template().code()).isEqualTo("default");
        } finally {
            jdbc.sql("DELETE FROM tenant.onboarding_templates WHERE id = :id")
                    .param("id", darkKitchen)
                    .update();
        }
    }

    @Test
    void aTemplateCannotClaimABusinessTypeNoTenantCanHave() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO tenant.onboarding_templates
                            (id, code, version, status, description, required_steps, default_configuration,
                             business_types, created_by)
                        VALUES (:id, 'casino', 1, 'DRAFT', 'no', '[]'::jsonb, '{}'::jsonb, ARRAY['CASINO'], 'test')
                        """).param("id", UUID.randomUUID()).update())
                .hasMessageContaining("ck_onboarding_template_business_types");
    }
}
