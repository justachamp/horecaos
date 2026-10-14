package uz.horecaos.platform.notifications.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap-map row {@code 10.9a}: the template editor's routes, over the real security and MVC stack.
 *
 * <p>For a release every call of the console went to {@code /api/v1/operations/tenants/{t}/brands/{b}/
 * notification-templates}, a path the platform serves nothing at, and every suite was green:
 * {@code NotificationTemplateControllerTests} instantiates the controller directly and so never sees a
 * URL. This class is the one that names the route. It also sends the console's real JSON, because
 * Jackson 3 refuses a body that omits a primitive and a direct call cannot show that.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationTemplateControllerHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9200-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018f9b20-9200-7000-8000-0000000000c2");

    // TENANT_ADMIN holds notification.template.author and .activate; BRAND_MANAGER holds author and not
    // activate; LOCATION_STAFF holds neither.
    private static final String ADMIN = "templates-admin";
    private static final String BRAND_MANAGER = "templates-brand-manager";
    private static final String STAFF = "templates-staff";

    private static final String BASE = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/notification-templates";
    private static final String OLD_PREFIX =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/notification-templates";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE notifications.templates CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'template-routes', 'Templates', 'Templates', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'ROUTES', 'template-routes-brand', 'Routes', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
        grant(ADMIN, PlatformRole.TENANT_ADMIN);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER);
        grant(STAFF, PlatformRole.LOCATION_STAFF);
    }

    // ------------------------------------------------------------------ the route

    @Test
    @DisplayName("the controller is served at /api/v1/tenants/.../notification-templates")
    void theControllersRouteIsServed() throws Exception {
        MvcResult ok = mvc.perform(get(BASE).with(tokenFor(ADMIN))).andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    @DisplayName("the /api/v1/operations prefix the console used to call serves nothing for this controller")
    void theOldPrefixIsNotServed() throws Exception {
        // A caller who is allowed on the real route, so the answer cannot be a refusal of the caller.
        MvcResult list = mvc.perform(get(OLD_PREFIX).with(tokenFor(ADMIN))).andReturn();
        MvcResult create = mvc.perform(post(OLD_PREFIX)
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(list.getResponse().getStatus()).as("GET on the old prefix").isEqualTo(404);
        assertThat(create.getResponse().getStatus())
                .as("POST on the old prefix")
                .isEqualTo(404);
    }

    // -------------------------------------------------- the editor's whole journey

    @Test
    @DisplayName("create, save a draft, activate and list: the console's real JSON end to end")
    void theWholeJourneyWithTheConsolesOwnBodies() throws Exception {
        String templateId = createTemplate(ADMIN, "ORDER_CONFIRMED", "TRANSACTIONAL_REQUIRED", "SMS");

        // Exactly the body template-editor.ts sends: no showItemPrices key at all.
        MvcResult saved = mvc.perform(post(BASE + "/" + templateId + "/versions")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wordings":{
                                  "ru":{"body":"Заказ {{orderNumber}} принят"},
                                  "uz-Latn":{"body":"{{orderNumber}}-buyurtma qabul qilindi"},
                                  "en":{"body":"Order {{orderNumber}} is confirmed"}},
                                 "variablesSchema":{"orderNumber":"string"}}
                                """))
                .andReturn();
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(saved.getResponse().getContentAsString(), "$.versionNumber"))
                .isEqualTo(1);

        MvcResult activated = mvc.perform(post(BASE + "/" + templateId + "/versions/1/activate")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();
        assertThat(activated.getResponse().getStatus()).isEqualTo(204);

        MvcResult listed = mvc.perform(get(BASE).with(tokenFor(ADMIN))).andReturn();
        String body = listed.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$[0].templateKey")).isEqualTo("ORDER_CONFIRMED");
        assertThat(JsonPath.<String>read(body, "$[0].origin")).isEqualTo("BRAND");
        assertThat(JsonPath.<Integer>read(body, "$[0].shownVersion")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(body, "$[0].versionLocales")).containsExactly("en", "ru", "uz-Latn");
        assertThat(JsonPath.<List<String>>read(body, "$[0].requiredLocales"))
                .containsExactlyInAnyOrder("ru", "uz-Latn", "en");
        assertThat(JsonPath.<String>read(body, "$[0].providerReview")).isNotBlank();

        MvcResult versions = mvc.perform(
                        get(BASE + "/" + templateId + "/versions").with(tokenFor(ADMIN)))
                .andReturn();
        assertThat(versions.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<List<Boolean>>read(versions.getResponse().getContentAsString(), "$[*].showItemPrices"))
                .containsOnly(false);
    }

    @Test
    @DisplayName("a draft that switches item prices on is saved with the switch on every language")
    void thePriceSwitchTravels() throws Exception {
        String templateId = createTemplate(ADMIN, "ORDER_CONFIRMED", "TRANSACTIONAL_REQUIRED", "SMS");

        MvcResult saved = mvc.perform(post(BASE + "/" + templateId + "/versions")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"showItemPrices":true,
                                 "wordings":{"ru":{"body":"{{productList}}"},"uz-Latn":{"body":"{{productList}}"},
                                             "en":{"body":"{{productList}}"}},
                                 "variablesSchema":{"productList":"string"}}
                                """))
                .andReturn();

        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        MvcResult read = mvc.perform(
                        get(BASE + "/" + templateId + "/versions/1").with(tokenFor(ADMIN)))
                .andReturn();
        assertThat(JsonPath.<List<Boolean>>read(read.getResponse().getContentAsString(), "$[*].showItemPrices"))
                .containsExactly(true, true, true);
    }

    @Test
    @DisplayName("a wording that names a customer's phone is refused with the variable named, not saved")
    void aDeclinedVariableIsRefused() throws Exception {
        String templateId = createTemplate(ADMIN, "ORDER_CONFIRMED", "TRANSACTIONAL_REQUIRED", "SMS");

        MvcResult refused = mvc.perform(post(BASE + "/" + templateId + "/versions")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wordings":{"ru":{"body":"{{customerPhone}}"},"uz-Latn":{"body":"{{customerPhone}}"},
                                             "en":{"body":"{{customerPhone}}"}},
                                 "variablesSchema":{"customerPhone":"string"}}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("customerPhone");
    }

    @Test
    @DisplayName("D19: a SECURITY template is refused at creation")
    void aSecurityTemplateIsRefused() throws Exception {
        MvcResult refused = mvc.perform(post(BASE)
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateKey":"OTP_CODE","notificationClass":"SECURITY","channel":"SMS"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("SECURITY");
    }

    // ------------------------------------------------------- the read-only layers

    @Test
    @DisplayName("the platform's default wording is read-only: eight events, three languages, the rating prompt absent")
    void theDefaultsAreServed() throws Exception {
        MvcResult ok = mvc.perform(get(BASE + "/platform-defaults").with(tokenFor(ADMIN)))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$[*].templateKey"))
                .containsExactly(
                        "ORDER_CONFIRMED",
                        "ORDER_PREPARING",
                        "ORDER_READY",
                        "ORDER_FULFILLING",
                        "ORDER_REJECTED",
                        "ORDER_CANCELLED",
                        "ORDER_EXPIRED",
                        "ORDER_PAYMENT_LAPSED");
        assertThat(JsonPath.<Map<String, String>>read(body, "$[0].bodies")).containsKeys("ru", "uz-Latn", "en");
        assertThat(JsonPath.<List<String>>read(body, "$[0].channels")).containsExactly("SMS", "TELEGRAM");
        assertThat(JsonPath.<String>read(body, "$[1].consentPurpose")).isEqualTo("ORDER_PROGRESS_UPDATES");
    }

    @Test
    @DisplayName("D19: the one-time-code sentence is served read-only, per language, with the gateway's state")
    void theOtpWordingIsServed() throws Exception {
        MvcResult ok =
                mvc.perform(get(BASE + "/otp-wording").with(tokenFor(ADMIN))).andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.sentences[*].locale")).containsExactly("ru", "uz-Latn", "en");
        assertThat(JsonPath.<String>read(body, "$.sentences[2].text"))
                .contains("{{code}}")
                .contains("{{minutes}}");
        assertThat(JsonPath.<String>read(body, "$.gatewayApproval")).isIn("GATEWAY_MODERATES", "NOT_MODERATED");
    }

    @Test
    @DisplayName("the variable catalogue offers the courier's first name for the on-the-way key only")
    void theCatalogueFollowsTheKey() throws Exception {
        MvcResult plain = mvc.perform(get(BASE + "/variable-catalogue").with(tokenFor(ADMIN)))
                .andReturn();
        MvcResult onTheWay = mvc.perform(get(BASE + "/variable-catalogue")
                        .queryParam("templateKey", "ORDER_FULFILLING")
                        .with(tokenFor(ADMIN)))
                .andReturn();

        String path = "$[?(@.notificationClass=='TRANSACTIONAL_OPTIONAL')].variables[*].name";
        assertThat(JsonPath.<List<String>>read(plain.getResponse().getContentAsString(), path))
                .contains("restaurantName", "productList")
                .doesNotContain("courierFirstName", "courierPhone", "courierSurname", "customerPhone");
        assertThat(JsonPath.<List<String>>read(onTheWay.getResponse().getContentAsString(), path))
                .contains("courierFirstName")
                .doesNotContain("courierPhone", "courierSurname");
        assertThat(JsonPath.<List<String>>read(plain.getResponse().getContentAsString(), "$[*].notificationClass"))
                .doesNotContain("SECURITY");
    }

    @Test
    @DisplayName("the test-send route is reached: an unknown template is a 404, not a missing route")
    void theTestSendRouteExists() throws Exception {
        MvcResult notFound = mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/versions/1/test-send")
                        .with(tokenFor(ADMIN))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locale":"ru","destination":"+998901234567"}
                                """))
                .andReturn();

        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(notFound.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------- capabilities

    @Test
    @DisplayName("authoring and activating are different capabilities: a brand manager writes, only an admin activates")
    void authoringAndActivatingAreSeparate() throws Exception {
        String templateId = createTemplate(BRAND_MANAGER, "ORDER_CANCELLED", "TRANSACTIONAL_REQUIRED", "SMS");
        mvc.perform(post(BASE + "/" + templateId + "/versions")
                        .with(tokenFor(BRAND_MANAGER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wordings":{"ru":{"body":"Отменён"},"uz-Latn":{"body":"Bekor"},"en":{"body":"Cancelled"}},
                                 "variablesSchema":{}}
                                """))
                .andReturn();

        MvcResult refused = mvc.perform(post(BASE + "/" + templateId + "/versions/1/activate")
                        .with(tokenFor(BRAND_MANAGER))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.NOTIFICATION_TEMPLATE_ACTIVATE.code());
    }

    @Test
    @DisplayName("a caller with neither capability is refused every read and write on the route")
    void aCallerWithNoCapabilityIsRefused() throws Exception {
        for (String path : List.of(BASE, BASE + "/platform-defaults", BASE + "/otp-wording")) {
            MvcResult refused = mvc.perform(get(path).with(tokenFor(STAFF))).andReturn();
            assertThat(refused.getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("INSUFFICIENT_CAPABILITY")
                    .contains(Capability.NOTIFICATION_TEMPLATE_AUTHOR.code());
        }
        MvcResult create = mvc.perform(post(BASE)
                        .with(tokenFor(STAFF))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateKey":"X","notificationClass":"TRANSACTIONAL_REQUIRED","channel":"SMS"}
                                """))
                .andReturn();
        assertThat(create.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("another tenant's brand route is refused to this tenant's admin")
    void anotherTenantsRouteIsRefused() throws Exception {
        UUID otherTenant = UUID.fromString("018f9b20-9200-7000-8000-0000000000c9");

        MvcResult refused = mvc.perform(
                        get("/api/v1/tenants/" + otherTenant + "/brands/" + BRAND + "/notification-templates")
                                .with(tokenFor(ADMIN)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ fixtures

    private String createTemplate(String subject, String key, String notificationClass, String channel)
            throws Exception {
        MvcResult created = mvc.perform(post(BASE)
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateKey":"%s","notificationClass":"%s","channel":"%s"}
                                """.formatted(key, notificationClass, channel)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'notification template route test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

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
