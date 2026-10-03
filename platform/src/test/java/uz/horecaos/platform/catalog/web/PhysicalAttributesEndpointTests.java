package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0137's authoring endpoints over HTTP (ADR 0025, ADR 0031):
 * {@code GET} and {@code PUT .../catalog/variants/{variantId}/physical-attributes}.
 *
 * <p>The bodies below are the JSON the product editor sends, not a record built in
 * Java: the editor omits every field the author left alone, including the two
 * booleans, so a primitive the server insisted on (Jackson 3 refuses a body that
 * omits one) would show up here as a 400 and not in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class PhysicalAttributesEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9f30-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f30-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f30-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f30-4000-7000-8000-0000000000b2");

    private static final UUID CAKE = UUID.fromString("018f9f30-4000-7000-8000-000000000101");
    private static final UUID SODA = UUID.fromString("018f9f30-4000-7000-8000-000000000102");
    /** Another tenant's dish: a variant id this brand must not be able to write to. */
    private static final UUID FOREIGN = UUID.fromString("018f9f30-4000-7000-8000-000000000199");

    private static final String OWNER = "physical-owner";
    private static final String NO_GRANT = "physical-no-grant";

    private static final String IDEMPOTENCY_HEADER = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

    /** What the editor sends for a cake sold by weight and by the half portion. */
    private static final String CAKE_BODY = """
            {"netWeightGrams":1500,"catchweight":true,"catchweightQuantumGrams":100,
             "catchweightNominalGrams":1200,"splittable":true,"portionSize":0.5,
             "caloriesKcalPer100":350.5,"proteinGramsPer100":4.5,"fatGramsPer100":18,
             "carbohydratesGramsPer100":52.25}""";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private int requests;

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
        requests = 0;
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE catalog.variant_physical_attributes, catalog.fiscal_classifications, "
                        + "catalog.fees, catalog.translations, catalog.variants, catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        insertTenant(TENANT, "physical-endpoint");
        insertBrand(TENANT, BRAND, "physical-endpoint-brand");
        insertTenant(OTHER_TENANT, "physical-endpoint-other");
        insertBrand(OTHER_TENANT, OTHER_BRAND, "physical-endpoint-other-brand");
        insertVariant(TENANT, BRAND, CAKE, "CAKE");
        insertVariant(TENANT, BRAND, SODA, "SODA");
        insertVariant(OTHER_TENANT, OTHER_BRAND, FOREIGN, "FOREIGN");

        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    @Test
    @DisplayName("a variant with no attributes reads as version 0 with every field empty, and an ETag to quote")
    void aVariantWithNoRowReadsEmpty() throws Exception {
        MvcResult result = mvc.perform(get(path(TENANT, BRAND, CAKE)).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.version")).isZero();
        assertThat(JsonPath.<Boolean>read(body, "$.catchweight")).isFalse();
        assertThat(JsonPath.<Boolean>read(body, "$.splittable")).isFalse();
        assertThat(JsonPath.<Object>read(body, "$.netWeightGrams")).isNull();
        assertThat(result.getResponse().getHeader("ETag")).isEqualTo("W/\"0\"");
    }

    @Test
    @DisplayName("the editor's body creates the row, and the read returns it with the version to quote")
    void thePutCreatesTheRowAndTheReadReturnsIt() throws Exception {
        MvcResult written = write(CAKE, "0", CAKE_BODY);

        assertThat(written.getResponse().getStatus())
                .as(written.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(written.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");

        String body = mvc.perform(get(path(TENANT, BRAND, CAKE)).with(tokenFor(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.version")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(body, "$.catchweight")).isTrue();
        assertThat(JsonPath.<Integer>read(body, "$.catchweightQuantumGrams")).isEqualTo(100);
        assertThat(JsonPath.<Integer>read(body, "$.catchweightNominalGrams")).isEqualTo(1200);
        assertThat(JsonPath.<Number>read(body, "$.portionSize").doubleValue()).isEqualTo(0.5);
        assertThat(JsonPath.<Number>read(body, "$.caloriesKcalPer100").doubleValue())
                .isEqualTo(350.5);

        Map<String, Object> row = row(CAKE);
        assertThat(row.get("tenant_id")).isEqualTo(TENANT);
        assertThat(row.get("brand_id")).isEqualTo(BRAND);
        assertThat(row.get("is_catchweight")).isEqualTo(true);
        assertThat(row.get("catchweight_quantum_grams")).isEqualTo(100);
    }

    @Test
    @DisplayName("a body that omits both booleans is accepted: Jackson 3 would refuse a primitive")
    void aBodyWithoutTheBooleansIsAccepted() throws Exception {
        MvcResult written = write(SODA, "0", "{\"netVolumeMillilitres\":330}");

        assertThat(written.getResponse().getStatus())
                .as(written.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(row(SODA).get("net_volume_millilitres")).isEqualTo(330);
        assertThat(row(SODA).get("is_catchweight")).isEqualTo(false);
    }

    @Test
    @DisplayName("a write with no If-Match, or no Idempotency-Key, is refused before anything is written")
    void thePreconditionsAreRequired() throws Exception {
        MvcResult noIfMatch = mvc.perform(put(path(TENANT, BRAND, CAKE))
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "physical-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAKE_BODY))
                .andReturn();
        assertThat(noIfMatch.getResponse().getStatus()).isEqualTo(400);
        assertThat(noIfMatch.getResponse().getContentAsString()).contains("INVALID_REQUEST");

        MvcResult noKey = mvc.perform(put(path(TENANT, BRAND, CAKE))
                        .with(tokenFor(OWNER))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAKE_BODY))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");

        assertThat(count("catalog.variant_physical_attributes")).isZero();
    }

    @Test
    @DisplayName("a stale version is a 409 naming both versions, and the stored row is untouched")
    void aStaleWriteIsRefused() throws Exception {
        write(CAKE, "0", CAKE_BODY);

        MvcResult stale = write(CAKE, "0", "{\"netWeightGrams\":999}");

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        String body = stale.getResponse().getContentAsString();
        assertThat(body).contains("STALE_VERSION");
        assertThat(JsonPath.<Integer>read(body, "$.expectedVersion")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.currentVersion")).isEqualTo(1);
        assertThat(row(CAKE).get("net_weight_grams")).isEqualTo(1500);

        MvcResult current = write(CAKE, "1", "{\"netWeightGrams\":999}");
        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        assertThat(row(CAKE).get("version")).isEqualTo(2);
    }

    @Test
    @DisplayName("a contradiction is a 400 carrying a stable reason code a client can branch on")
    void aContradictionIsRefusedWithAReason() throws Exception {
        assertRefused("{\"netWeightGrams\":250,\"netVolumeMillilitres\":330}", "WEIGHT_AND_VOLUME_EXCLUSIVE");
        assertRefused("{\"netWeightGrams\":1200,\"catchweight\":true}", "CATCHWEIGHT_NEEDS_QUANTUM");
        assertRefused("{\"catchweight\":true,\"catchweightQuantumGrams\":100}", "CATCHWEIGHT_NEEDS_WEIGHT");
        assertRefused("{\"portionSize\":0.5}", "PORTION_SIZE_NEEDS_SPLITTABLE");
        assertRefused("{\"splittable\":true,\"portionSize\":0.0001}", "PORTION_SIZE_TOO_PRECISE");
        assertRefused("{\"proteinGramsPer100\":120}", "PROTEIN_OUT_OF_RANGE");
        assertThat(count("catalog.variant_physical_attributes")).isZero();
    }

    @Test
    @DisplayName("a non-positive weight is refused by bean validation, not stored")
    void aNonPositiveWeightIsRefused() throws Exception {
        MvcResult zero = write(CAKE, "0", "{\"netWeightGrams\":0}");

        assertThat(zero.getResponse().getStatus()).isEqualTo(400);
        assertThat(count("catalog.variant_physical_attributes")).isZero();
    }

    @Test
    @DisplayName("an empty body clears the row, back to a fixed unit sold whole")
    void anEmptyBodyClearsTheRow() throws Exception {
        write(CAKE, "0", CAKE_BODY);

        MvcResult cleared = write(CAKE, "1", "{}");

        assertThat(cleared.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(cleared.getResponse().getContentAsString(), "$.version"))
                .isZero();
        assertThat(count("catalog.variant_physical_attributes")).isZero();
    }

    @Test
    @DisplayName("an unknown variant, and another tenant's variant id, are a 404 that writes nothing")
    void aVariantOfAnotherBrandIsNotFound() throws Exception {
        UUID unknown = UUID.fromString("018f9f30-4000-7000-8000-0000000009ff");

        assertThat(write(unknown, "0", CAKE_BODY).getResponse().getStatus()).isEqualTo(404);
        assertThat(write(FOREIGN, "0", CAKE_BODY).getResponse().getStatus())
                .as("a node id of another tenant, sent through this tenant's own brand")
                .isEqualTo(404);
        assertThat(mvc.perform(get(path(TENANT, BRAND, FOREIGN)).with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(count("catalog.variant_physical_attributes")).isZero();
    }

    @Test
    @DisplayName("a caller with no grant is refused both the read and the write, naming the capability")
    void bothEndpointsRefuseACallerWithNoGrant() throws Exception {
        MvcResult read = mvc.perform(get(path(TENANT, BRAND, CAKE)).with(tokenFor(NO_GRANT)))
                .andReturn();
        MvcResult write = mvc.perform(put(path(TENANT, BRAND, CAKE))
                        .with(tokenFor(NO_GRANT))
                        .header(IDEMPOTENCY_HEADER, "physical-refused")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAKE_BODY))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(read.getResponse().getContentAsString()).contains(Capability.CATALOG_READ.code());
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(write.getResponse().getContentAsString()).contains(Capability.CATALOG_AUTHOR.code());
        assertThat(count("catalog.variant_physical_attributes")).isZero();
    }

    @Test
    @DisplayName("a tenant owner cannot read or write another tenant's brand")
    void anotherTenantsBrandIsOutOfScope() throws Exception {
        MvcResult read = mvc.perform(
                        get(path(OTHER_TENANT, OTHER_BRAND, FOREIGN)).with(tokenFor(OWNER)))
                .andReturn();
        MvcResult write = mvc.perform(put(path(OTHER_TENANT, OTHER_BRAND, FOREIGN))
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "physical-cross-tenant")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAKE_BODY))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("catalog.variant_physical_attributes WHERE variant_id = '" + FOREIGN + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a write is audited under catalog.author at brand scope, with the figures as a diff")
    void theWriteIsAudited() throws Exception {
        write(CAKE, "0", CAKE_BODY);

        Map<String, Object> fact = jdbc.sql("""
                        SELECT capability_used, scope_type, scope_id, change_document::text AS document
                          FROM audit.audit_events
                         WHERE action_code = 'catalog.variantPhysicalAttributes.set'
                        """).query().singleRow();
        assertThat(fact.get("capability_used")).isEqualTo(Capability.CATALOG_AUTHOR.code());
        assertThat(fact.get("scope_type")).isEqualTo("BRAND");
        assertThat(fact.get("scope_id")).isEqualTo(BRAND);
        String document = (String) fact.get("document");
        assertThat(JsonPath.<Object>read(document, "$.catchweightQuantumGrams.before"))
                .isNull();
        assertThat(JsonPath.<Integer>read(document, "$.catchweightQuantumGrams.after"))
                .isEqualTo(100);
    }

    // ------------------------------------------------------------------ helpers

    private void assertRefused(String body, String reason) throws Exception {
        MvcResult refused = write(CAKE, "0", body);
        assertThat(refused.getResponse().getStatus()).as(body).isEqualTo(400);
        assertThat(JsonPath.<String>read(refused.getResponse().getContentAsString(), "$.reason"))
                .as(body)
                .isEqualTo(reason);
    }

    private static String path(UUID tenantId, UUID brandId, UUID variantId) {
        return "/api/v1/control-plane/tenants/" + tenantId + "/brands/" + brandId + "/catalog/variants/" + variantId
                + "/physical-attributes";
    }

    private MvcResult write(UUID variantId, String ifMatch, String body) throws Exception {
        return mvc.perform(put(path(TENANT, BRAND, variantId))
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "physical-" + requests++)
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private Map<String, Object> row(UUID variantId) {
        return jdbc.sql("SELECT * FROM catalog.variant_physical_attributes WHERE variant_id = :id")
                .param("id", variantId)
                .query()
                .singleRow();
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Long.class)
                .single();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID tenantId, UUID brandId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", slug)
                .update();
    }

    private void insertVariant(UUID tenantId, UUID brandId, UUID variantId, String code) {
        UUID productId = UUID.nameUUIDFromBytes(("product-" + variantId).getBytes(UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'physical attributes endpoint test', :validFrom)
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
}
