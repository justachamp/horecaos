package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0136's authoring surface over HTTP: combo groups, components, and the
 * visibility and overrides of a modifier attachment.
 *
 * <p>Every rule the record names is proved at the layer a client meets it, and the
 * two it says the database enforces are proved at the database as well: a test that
 * only went through the service would stay green if the trigger were dropped, and
 * a test that only went through the trigger would not show a client a usable
 * refusal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class CompositeProductAuthoringEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-4000-7000-8000-0000000000b2");

    private static final UUID COMBO_PRODUCT = UUID.fromString("018f9f10-4000-7000-8000-0000000000d1");
    private static final UUID COMBO_VARIANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000d2");
    private static final UUID BURGER_VARIANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000d4");
    private static final UUID COLA_VARIANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000d6");
    private static final UUID SECOND_COMBO_VARIANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000d8");
    private static final UUID ARCHIVED_VARIANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000da");
    private static final UUID FOREIGN_VARIANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000dc");

    private static final UUID BURGER_PRODUCT = UUID.fromString("018f9f10-4000-7000-8000-0000000000d3");
    private static final UUID BOX_GROUP = UUID.fromString("018f9f10-4000-7000-8000-0000000000e1");
    private static final UUID SAUCE_GROUP = UUID.fromString("018f9f10-4000-7000-8000-0000000000e2");

    private static final String OWNER = "composite-owner";
    private static final String OTHER_OWNER = "composite-other-tenant-owner";
    private static final String NO_GRANT = "composite-no-grant";

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

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE catalog.combo_components, catalog.combo_groups, "
                        + "catalog.product_modifier_groups, catalog.variant_modifier_groups, "
                        + "catalog.modifier_options, catalog.modifier_groups, catalog.translations, "
                        + "catalog.variants, catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertFixtures();
        grant(OWNER, TENANT, PlatformRole.TENANT_OWNER);
        grant(OTHER_OWNER, OTHER_TENANT, PlatformRole.TENANT_OWNER);
    }

    // ------------------------------------------------------------ capabilities

    @Test
    void everyMutationIsRefusedWithoutCatalogAuthor() throws Exception {
        UUID group = createGroup("lunch-box", 1, 1);
        UUID component = addComponent(group, BURGER_VARIANT);

        assertRefused(
                post(catalogPath() + "/combo-groups")
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(COMBO_VARIANT, "x", 1, 1)),
                Capability.CATALOG_AUTHOR);
        assertRefused(
                put(catalogPath() + "/combo-groups/" + group)
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateGroupBody(1, 1, "ACTIVE")),
                Capability.CATALOG_AUTHOR);
        assertRefused(
                post(catalogPath() + "/combo-groups/" + group + "/components")
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"componentVariantId\":\"%s\"}".formatted(COLA_VARIANT)),
                Capability.CATALOG_AUTHOR);
        assertRefused(
                put(catalogPath() + "/combo-components/" + component)
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultQuantity\":2,\"status\":\"ACTIVE\"}"),
                Capability.CATALOG_AUTHOR);
        assertRefused(
                put(catalogPath() + "/variants/" + COMBO_VARIANT + "/modifier-groups/" + BOX_GROUP)
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                Capability.CATALOG_AUTHOR);
        assertRefused(
                put(catalogPath() + "/products/" + BURGER_PRODUCT + "/modifier-groups/" + BOX_GROUP + "/overrides")
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"VISIBLE\"}"),
                Capability.CATALOG_AUTHOR);
        assertRefused(
                put(catalogPath() + "/variants/" + BURGER_VARIANT + "/modifier-groups/" + BOX_GROUP + "/overrides")
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"VISIBLE\"}"),
                Capability.CATALOG_AUTHOR);
    }

    @Test
    void everyReadIsRefusedWithoutCatalogRead() throws Exception {
        UUID group = createGroup("lunch-box", 1, 1);

        assertRefused(get(catalogPath() + "/combo-groups/" + group).with(tokenFor(NO_GRANT)), Capability.CATALOG_READ);
        assertRefused(
                get(catalogPath() + "/variants/" + COMBO_VARIANT + "/combo-groups")
                        .with(tokenFor(NO_GRANT)),
                Capability.CATALOG_READ);
        assertRefused(
                get(catalogPath() + "/variants/" + COMBO_VARIANT + "/modifier-groups")
                        .with(tokenFor(NO_GRANT)),
                Capability.CATALOG_READ);
    }

    // --------------------------------------------------------- tenant isolation

    @Test
    void anotherTenantsOwnerCannotReachThisBrandsComboGroups() throws Exception {
        UUID group = createGroup("lunch-box", 1, 1);

        MvcResult read = mvc.perform(
                        get(catalogPath() + "/combo-groups/" + group).with(tokenFor(OTHER_OWNER)))
                .andReturn();
        assertThat(read.getResponse().getStatus()).as("not their tenant").isEqualTo(403);

        MvcResult write = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OTHER_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "intruder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(SECOND_COMBO_VARIANT, "stolen", 1, 1)))
                .andReturn();
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("catalog.combo_groups")).as("nothing was written").isEqualTo(1L);
    }

    @Test
    void aVariantFromAnotherBrandCannotBeAContainerOrAComponent() throws Exception {
        MvcResult asContainer = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "foreign-container")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(FOREIGN_VARIANT, "foreign", 1, 1)))
                .andReturn();
        assertThat(asContainer.getResponse().getStatus())
                .as(asContainer.getResponse().getContentAsString(UTF_8))
                .isEqualTo(404);

        UUID group = createGroup("lunch-box", 1, 1);
        MvcResult asComponent = mvc.perform(post(catalogPath() + "/combo-groups/" + group + "/components")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "foreign-component")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"componentVariantId\":\"%s\"}".formatted(FOREIGN_VARIANT)))
                .andReturn();
        assertThat(asComponent.getResponse().getStatus()).isEqualTo(404);
        assertThat(count("catalog.combo_components")).isZero();
    }

    @Test
    void aComboGroupOfAnotherBrandReadsAsNotFound() throws Exception {
        UUID group = createGroup("lunch-box", 1, 1);

        MvcResult read = mvc.perform(get("/api/v1/control-plane/tenants/" + OTHER_TENANT + "/brands/" + OTHER_BRAND
                                + "/catalog/combo-groups/" + group)
                        .with(tokenFor(OTHER_OWNER)))
                .andReturn();

        assertThat(read.getResponse().getStatus())
                .as("their own brand, a group id from ours")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------ combo groups

    @Test
    void aGroupRoundTripsWithItsVersionAndAName() throws Exception {
        MvcResult created = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(COMBO_VARIANT, "main", 1, 2)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        JsonNode body = json.readTree(created.getResponse().getContentAsString(UTF_8));
        assertThat(body.get("containerVariantId").asString()).isEqualTo(COMBO_VARIANT.toString());
        assertThat(body.get("minimumSelections").asInt()).isEqualTo(1);
        assertThat(body.get("maximumSelections").asInt()).isEqualTo(2);
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        UUID group = UUID.fromString(body.get("comboGroupId").asString());

        assertThat(jdbc.sql("SELECT name FROM catalog.translations WHERE entity_type = 'COMBO_GROUP' "
                                + "AND entity_id = :id AND locale = 'ru'")
                        .param("id", group)
                        .query(String.class)
                        .single())
                .as("the heading the customer reads lives in translations")
                .isEqualTo("Выберите бургер");

        MvcResult read = mvc.perform(
                        get(catalogPath() + "/combo-groups/" + group).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        MvcResult list = mvc.perform(get(catalogPath() + "/variants/" + COMBO_VARIANT + "/combo-groups")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(json.readTree(list.getResponse().getContentAsString(UTF_8)).size())
                .isEqualTo(1);
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.comboGroup.created'"))
                .as("ADR 0027: creating a group is audited")
                .isEqualTo(1L);
    }

    @Test
    void creationRequiresAnIdempotencyKeyAndAReplayCreatesNothingNew() throws Exception {
        MvcResult keyless = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(COMBO_VARIANT, "main", 1, 1)))
                .andReturn();
        assertThat(keyless.getResponse().getStatus()).isEqualTo(400);
        assertThat(keyless.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");

        MockHttpServletRequestBuilder first = post(catalogPath() + "/combo-groups")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "same-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(COMBO_VARIANT, "main", 1, 1));
        MvcResult one = mvc.perform(first).andReturn();
        MvcResult replay = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(COMBO_VARIANT, "main", 1, 1)))
                .andReturn();

        assertThat(one.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(replay.getResponse().getContentAsString(UTF_8))
                        .get("comboGroupId")
                        .asString())
                .as("the replay answers the first result")
                .isEqualTo(json.readTree(one.getResponse().getContentAsString(UTF_8))
                        .get("comboGroupId")
                        .asString());
        assertThat(count("catalog.combo_groups")).isEqualTo(1L);
    }

    @Test
    void aRangeThatCannotBeCompletedIsRefusedWithItsFindingCode() throws Exception {
        MvcResult inverted = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "inverted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(COMBO_VARIANT, "main", 3, 2)))
                .andReturn();

        assertThat(inverted.getResponse().getStatus()).isEqualTo(422);
        assertThat(inverted.getResponse().getContentAsString()).contains("COMBO_GROUP_RANGE_INVALID");
        assertThat(count("catalog.combo_groups")).isZero();
    }

    @Test
    void aMissingRangeIsAValidationErrorNotASilentZero() throws Exception {
        // Jackson 3 refuses a missing primitive, and a range the client forgot to
        // send must not become a minimum of zero.
        MvcResult missing = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "no-range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"containerVariantId":"%s","code":"main","name":"Main","locale":"ru"}
                                """.formatted(COMBO_VARIANT)))
                .andReturn();

        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    void updatingAGroupNeedsTheVersionItWasReadAt() throws Exception {
        UUID group = createGroup("main", 1, 1);

        MvcResult headerless = mvc.perform(put(catalogPath() + "/combo-groups/" + group)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "update-no-match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateGroupBody(1, 2, "ACTIVE")))
                .andReturn();
        assertThat(headerless.getResponse().getStatus())
                .as("If-Match is required")
                .isEqualTo(400);

        MvcResult stale = mvc.perform(put(catalogPath() + "/combo-groups/" + group)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "update-stale")
                        .header("If-Match", "W/\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateGroupBody(1, 2, "ACTIVE")))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(jdbc.sql("SELECT maximum_selections FROM catalog.combo_groups WHERE id = :id")
                        .param("id", group)
                        .query(Integer.class)
                        .single())
                .as("a stale write changed nothing")
                .isEqualTo(1);

        MvcResult current = mvc.perform(put(catalogPath() + "/combo-groups/" + group)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "update-current")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateGroupBody(1, 2, "ARCHIVED")))
                .andReturn();
        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        assertThat(current.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        assertThat(json.readTree(current.getResponse().getContentAsString(UTF_8))
                        .get("status")
                        .asString())
                .isEqualTo("ARCHIVED");
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.comboGroup.updated'"))
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------ no nesting

    @Test
    void aContainerCannotBecomeAComponent() throws Exception {
        UUID group = createGroup("main", 1, 1);
        UUID other = createGroup(SECOND_COMBO_VARIANT, "second", 1, 1);

        MvcResult nested = mvc.perform(post(catalogPath() + "/combo-groups/" + group + "/components")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "nest-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"componentVariantId\":\"%s\"}".formatted(SECOND_COMBO_VARIANT)))
                .andReturn();

        assertThat(nested.getResponse().getStatus()).isEqualTo(422);
        assertThat(nested.getResponse().getContentAsString()).contains("COMBO_NESTING_FORBIDDEN");
        assertThat(count("catalog.combo_components")).isZero();
        assertThat(other).isNotNull();
    }

    @Test
    void aComponentCannotBecomeAContainer() throws Exception {
        UUID group = createGroup("main", 1, 1);
        addComponent(group, BURGER_VARIANT);

        MvcResult nested = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "nest-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(BURGER_VARIANT, "burger-combo", 1, 1)))
                .andReturn();

        assertThat(nested.getResponse().getStatus()).isEqualTo(422);
        assertThat(nested.getResponse().getContentAsString()).contains("COMBO_NESTING_FORBIDDEN");
        assertThat(count("catalog.combo_groups")).isEqualTo(1L);
    }

    @Test
    void theDatabaseRefusesNestingEvenWhenTheServiceIsBypassed() {
        UUID group = insertGroupDirectly(COMBO_VARIANT, "direct");
        insertComponentDirectly(group, BURGER_VARIANT);

        assertThatThrownBy(() -> insertComponentDirectly(group, COMBO_VARIANT))
                .as("a component that is a container of this very group")
                .hasMessageContaining("is a combo container and cannot be a component");
        assertThatThrownBy(() -> insertGroupDirectly(BURGER_VARIANT, "burger-combo"))
                .as("a container that is already somebody's component")
                .hasMessageContaining("is a component of a combo and cannot be a combo container");
        assertThat(count("catalog.combo_components")).isEqualTo(1L);
        assertThat(count("catalog.combo_groups")).isEqualTo(1L);
    }

    // ------------------------------------------------------------- components

    @Test
    void aComponentRoundTripsAndLeavesByBeingArchived() throws Exception {
        UUID group = createGroup("main", 1, 1);

        MvcResult added = mvc.perform(post(catalogPath() + "/combo-groups/" + group + "/components")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "add-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"componentVariantId\":\"%s\",\"defaultQuantity\":2}".formatted(BURGER_VARIANT)))
                .andReturn();
        assertThat(added.getResponse().getStatus())
                .as(added.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        JsonNode component = json.readTree(added.getResponse().getContentAsString(UTF_8));
        assertThat(component.get("defaultQuantity").asInt()).isEqualTo(2);
        UUID componentId = UUID.fromString(component.get("componentId").asString());

        MvcResult archived = mvc.perform(put(catalogPath() + "/combo-components/" + componentId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-1")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultQuantity\":2,\"status\":\"ARCHIVED\"}"))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        assertThat(archived.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");

        MvcResult stale = mvc.perform(put(catalogPath() + "/combo-components/" + componentId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-2")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultQuantity\":3,\"status\":\"ACTIVE\"}"))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT status FROM catalog.combo_components WHERE id = :id")
                        .param("id", componentId)
                        .query(String.class)
                        .single())
                .isEqualTo("ARCHIVED");
        assertThat(count("audit.audit_events WHERE action_code LIKE 'catalog.comboComponent.%'"))
                .isEqualTo(2L);
    }

    @Test
    void theSameVariantCannotBeOfferedTwiceInOneGroup() throws Exception {
        UUID group = createGroup("main", 1, 1);
        addComponent(group, BURGER_VARIANT);

        MvcResult twice = mvc.perform(post(catalogPath() + "/combo-groups/" + group + "/components")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "twice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"componentVariantId\":\"%s\"}".formatted(BURGER_VARIANT)))
                .andReturn();

        assertThat(twice.getResponse().getStatus()).isEqualTo(422);
        assertThat(twice.getResponse().getContentAsString()).contains("COMBO_COMPONENT_ALREADY_IN_GROUP");
    }

    @Test
    void anArchivedVariantCannotBeOfferedInACombo() throws Exception {
        UUID group = createGroup("main", 1, 1);

        MvcResult archived = mvc.perform(post(catalogPath() + "/combo-groups/" + group + "/components")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archived-variant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"componentVariantId\":\"%s\"}".formatted(ARCHIVED_VARIANT)))
                .andReturn();

        assertThat(archived.getResponse().getStatus()).isEqualTo(422);
        assertThat(archived.getResponse().getContentAsString()).contains("COMBO_COMPONENT_VARIANT_NOT_ACTIVE");
    }

    @Test
    void theSameDrinkCanSitInTwoCombosBecauseAComponentIsAPairing() throws Exception {
        UUID lunch = createGroup(COMBO_VARIANT, "lunch", 1, 1);
        UUID family = createGroup(SECOND_COMBO_VARIANT, "family", 1, 1);

        UUID inLunch = addComponent(lunch, COLA_VARIANT);
        UUID inFamily = addComponent(family, COLA_VARIANT);

        assertThat(inLunch)
                .as("two pairings, so two price keys: free with the family box, +3,000 in the lunch box")
                .isNotEqualTo(inFamily);
    }

    // ----------------------------------------------------- modifier attachments

    @Test
    void aHiddenGroupWithFulfilmentModesRoundTripsOnAProduct() throws Exception {
        attachToProduct(BURGER_PRODUCT, BOX_GROUP);

        MvcResult set = mvc.perform(put(productOverridesPath(BURGER_PRODUCT, BOX_GROUP))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "hidden-1")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"HIDDEN_AUTO_SELECT","applicableFulfillmentModes":["DELIVERY","PICKUP"]}
                                """))
                .andReturn();

        assertThat(set.getResponse().getStatus())
                .as(set.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(set.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        JsonNode body = json.readTree(set.getResponse().getContentAsString(UTF_8));
        assertThat(body.get("visibility").asString()).isEqualTo("HIDDEN_AUTO_SELECT");
        assertThat(body.get("applicableFulfillmentModes").size()).isEqualTo(2);

        MvcResult detail = mvc.perform(
                        get(catalogPath() + "/products/" + BURGER_PRODUCT).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        JsonNode attached = json.readTree(detail.getResponse().getContentAsString(UTF_8))
                .get("modifierGroups")
                .get(0);
        assertThat(attached.get("visibility").asString())
                .as("the product detail shows the policy and the version an edit must send")
                .isEqualTo("HIDDEN_AUTO_SELECT");
        assertThat(attached.get("version").asInt()).isEqualTo(2);
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.modifierAttachment.policySet'"))
                .isEqualTo(1L);
    }

    @Test
    void anOverrideOnOneProductLeavesTheSharedGroupAndAnotherProductAlone() throws Exception {
        UUID otherProduct = insertProductAndVariant("PIZZA", UUID.randomUUID(), false);
        attachToProduct(BURGER_PRODUCT, SAUCE_GROUP);
        attachToProduct(otherProduct, SAUCE_GROUP);

        MvcResult set = mvc.perform(put(productOverridesPath(BURGER_PRODUCT, SAUCE_GROUP))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "override-1")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"VISIBLE","requiredOverride":true,"minimumSelectionsOverride":1,"maximumSelectionsOverride":2}
                                """))
                .andReturn();
        assertThat(set.getResponse().getStatus())
                .as(set.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);

        assertThat(jdbc.sql("SELECT is_required || '/' || minimum_selections || '/' || maximum_selections "
                                + "FROM catalog.modifier_groups WHERE id = :id")
                        .param("id", SAUCE_GROUP)
                        .query(String.class)
                        .single())
                .as("catalog.md: editing a shared group from one product's screen is the mistake to design out")
                .isEqualTo("false/0/3");
        assertThat(jdbc.sql("SELECT required_override IS NULL AND minimum_selections_override IS NULL "
                                + "AND maximum_selections_override IS NULL FROM catalog.product_modifier_groups "
                                + "WHERE product_id = :id AND modifier_group_id = :group")
                        .param("id", otherProduct)
                        .param("group", SAUCE_GROUP)
                        .query(Boolean.class)
                        .single())
                .as("a second product attaching the same group falls back to the group's own values")
                .isTrue();
    }

    @Test
    void anOverrideSendsNullToFallBackToTheGroup() throws Exception {
        attachToProduct(BURGER_PRODUCT, SAUCE_GROUP);
        setPolicy(BURGER_PRODUCT, SAUCE_GROUP, 1, "{\"visibility\":\"VISIBLE\",\"maximumSelectionsOverride\":1}");

        MvcResult cleared = mvc.perform(put(productOverridesPath(BURGER_PRODUCT, SAUCE_GROUP))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "clear-1")
                        .header("If-Match", "W/\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"VISIBLE\"}"))
                .andReturn();

        assertThat(cleared.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(cleared.getResponse().getContentAsString(UTF_8));
        assertThat(body.get("maximumSelectionsOverride").isNull())
                .as("null means 'use the group's own value' -- the fallback")
                .isTrue();
    }

    @Test
    void aContradictoryPolicyIsRefusedWithItsFindingCode() throws Exception {
        attachToProduct(BURGER_PRODUCT, SAUCE_GROUP);

        // Modes mean nothing on a visible group.
        assertPolicyRefused("{\"visibility\":\"VISIBLE\",\"applicableFulfillmentModes\":[\"DELIVERY\"]}");
        // A hidden group is always exactly one auto-selected option.
        assertPolicyRefused("{\"visibility\":\"HIDDEN_AUTO_SELECT\",\"minimumSelectionsOverride\":1}");
        // The effective minimum (the group's own 0, overridden to 3) is above the group's maximum of 3? no:
        // override the maximum to 1 while a minimum override says 2.
        assertPolicyRefused(
                "{\"visibility\":\"VISIBLE\",\"minimumSelectionsOverride\":2,\"maximumSelectionsOverride\":1}");
        // required with a minimum of zero.
        assertPolicyRefused("{\"visibility\":\"VISIBLE\",\"requiredOverride\":true,\"minimumSelectionsOverride\":0}");
        // An empty mode set would never apply.
        assertPolicyRefused("{\"visibility\":\"HIDDEN_AUTO_SELECT\",\"applicableFulfillmentModes\":[]}");

        assertThat(jdbc.sql("SELECT version FROM catalog.product_modifier_groups WHERE product_id = :id")
                        .param("id", BURGER_PRODUCT)
                        .query(Integer.class)
                        .single())
                .as("a refused policy changed nothing")
                .isEqualTo(1);
    }

    @Test
    void anOverrideThatContradictsTheSharedGroupIsRefused() throws Exception {
        attachToProduct(BURGER_PRODUCT, SAUCE_GROUP);
        // The group's own minimum is 0 and maximum 3; a maximum override below an existing
        // minimum override is caught by the row, and one that leaves the effective range
        // empty is caught against the group. Raise the group's minimum above the override.
        jdbc.sql("UPDATE catalog.modifier_groups SET minimum_selections = 2 WHERE id = :id")
                .param("id", SAUCE_GROUP)
                .update();

        assertPolicyRefused("{\"visibility\":\"VISIBLE\",\"maximumSelectionsOverride\":1}");
    }

    @Test
    void anAttachmentThatDoesNotExistCannotHaveAPolicy() throws Exception {
        MvcResult missing = mvc.perform(put(productOverridesPath(BURGER_PRODUCT, BOX_GROUP))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "no-attachment")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"HIDDEN_AUTO_SELECT\"}"))
                .andReturn();

        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aStalePolicyWriteIsRefusedAndChangesNothing() throws Exception {
        attachToProduct(BURGER_PRODUCT, BOX_GROUP);
        setPolicy(BURGER_PRODUCT, BOX_GROUP, 1, "{\"visibility\":\"HIDDEN_AUTO_SELECT\"}");

        MvcResult stale = mvc.perform(put(productOverridesPath(BURGER_PRODUCT, BOX_GROUP))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "stale-policy")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"VISIBLE\"}"))
                .andReturn();

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(jdbc.sql("SELECT visibility FROM catalog.product_modifier_groups WHERE product_id = :id")
                        .param("id", BURGER_PRODUCT)
                        .query(String.class)
                        .single())
                .isEqualTo("HIDDEN_AUTO_SELECT");
    }

    @Test
    void aVariantCarriesGroupsOfItsOwnAndAVariantPolicyRoundTrips() throws Exception {
        MvcResult attached = mvc.perform(
                        put(catalogPath() + "/variants/" + BURGER_VARIANT + "/modifier-groups/" + SAUCE_GROUP)
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "variant-attach")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sortOrder\":4}"))
                .andReturn();
        assertThat(attached.getResponse().getStatus())
                .as(attached.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);

        MvcResult set = mvc.perform(
                        put(catalogPath() + "/variants/" + BURGER_VARIANT + "/modifier-groups/" + SAUCE_GROUP
                                        + "/overrides")
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "variant-policy")
                                .header("If-Match", "W/\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"visibility\":\"VISIBLE\",\"requiredOverride\":true,\"minimumSelectionsOverride\":1}"))
                .andReturn();
        assertThat(set.getResponse().getStatus())
                .as(set.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);

        MvcResult listed = mvc.perform(get(catalogPath() + "/variants/" + BURGER_VARIANT + "/modifier-groups")
                        .with(tokenFor(OWNER)))
                .andReturn();
        JsonNode row =
                json.readTree(listed.getResponse().getContentAsString(UTF_8)).get(0);
        assertThat(row.get("ownerType").asString()).isEqualTo("VARIANT");
        assertThat(row.get("sortOrder").asInt()).isEqualTo(4);
        assertThat(row.get("requiredOverride").asBoolean()).isTrue();
        assertThat(row.get("version").asInt()).isEqualTo(2);
    }

    @Test
    void aVariantOfAnotherBrandCannotCarryThisBrandsGroup() throws Exception {
        MvcResult foreign = mvc.perform(
                        put(catalogPath() + "/variants/" + FOREIGN_VARIANT + "/modifier-groups/" + SAUCE_GROUP)
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "foreign-attach")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andReturn();

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(count("catalog.variant_modifier_groups")).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private void assertPolicyRefused(String body) throws Exception {
        MvcResult refused = mvc.perform(put(productOverridesPath(BURGER_PRODUCT, SAUCE_GROUP))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "policy-" + UUID.randomUUID())
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(refused.getResponse().getStatus())
                .as(body + " -> " + refused.getResponse().getContentAsString(UTF_8))
                .isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("MODIFIER_ATTACHMENT_POLICY_INVALID");
    }

    private void setPolicy(UUID productId, UUID groupId, int version, String body) throws Exception {
        MvcResult result = mvc.perform(put(productOverridesPath(productId, groupId))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "set-" + UUID.randomUUID())
                        .header("If-Match", "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
    }

    private UUID createGroup(String code, int minimum, int maximum) throws Exception {
        return createGroup(COMBO_VARIANT, code, minimum, maximum);
    }

    private UUID createGroup(UUID container, String code, int minimum, int maximum) throws Exception {
        MvcResult created = mvc.perform(post(catalogPath() + "/combo-groups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(container, code, minimum, maximum)))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return UUID.fromString(json.readTree(created.getResponse().getContentAsString(UTF_8))
                .get("comboGroupId")
                .asString());
    }

    private UUID addComponent(UUID group, UUID variant) throws Exception {
        MvcResult added = mvc.perform(post(catalogPath() + "/combo-groups/" + group + "/components")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "add-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"componentVariantId\":\"%s\"}".formatted(variant)))
                .andReturn();
        assertThat(added.getResponse().getStatus())
                .as(added.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return UUID.fromString(json.readTree(added.getResponse().getContentAsString(UTF_8))
                .get("componentId")
                .asString());
    }

    private void attachToProduct(UUID productId, UUID groupId) throws Exception {
        MvcResult attached = mvc.perform(put(catalogPath() + "/products/" + productId + "/modifier-groups/" + groupId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "attach-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":0}"))
                .andReturn();
        assertThat(attached.getResponse().getStatus()).isEqualTo(204);
    }

    private UUID insertGroupDirectly(UUID container, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.combo_groups (id, tenant_id, brand_id, container_variant_id, code)
                VALUES (:id, :tenantId, :brandId, :container, :code)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("container", container)
                .param("code", code)
                .update();
        return id;
    }

    private void insertComponentDirectly(UUID group, UUID variant) {
        jdbc.sql("""
                INSERT INTO catalog.combo_components (id, tenant_id, brand_id, combo_group_id, component_variant_id)
                VALUES (:id, :tenantId, :brandId, :group, :variant)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("group", group)
                .param("variant", variant)
                .update();
    }

    private long count(String fromClause) {
        return jdbc.sql("SELECT count(*) FROM " + fromClause).query(Long.class).single();
    }

    private static String createBody(UUID container, String code, int minimum, int maximum) {
        return """
                {"containerVariantId":"%s","code":"%s","name":"Выберите бургер","locale":"ru",
                 "minimumSelections":%d,"maximumSelections":%d}
                """.formatted(container, code, minimum, maximum);
    }

    private static String updateGroupBody(int minimum, int maximum, String status) {
        return """
                {"minimumSelections":%d,"maximumSelections":%d,"status":"%s"}
                """.formatted(minimum, maximum, status);
    }

    private static String catalogPath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog";
    }

    private static String productOverridesPath(UUID productId, UUID groupId) {
        return catalogPath() + "/products/" + productId + "/modifier-groups/" + groupId + "/overrides";
    }

    private void assertRefused(MockHttpServletRequestBuilder request, Capability expected) throws Exception {
        MvcResult refused = mvc.perform(request).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(expected.code());
    }

    private void insertFixtures() {
        for (UUID[] pair : new UUID[][] {{TENANT, BRAND}, {OTHER_TENANT, OTHER_BRAND}}) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", pair[0])
                    .param("slug", "composite-" + pair[0].toString().substring(30))
                    .update();
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                    """).param("id", pair[1]).param("tenantId", pair[0]).update();
        }

        insertProduct(COMBO_PRODUCT, "LUNCH-BOX", TENANT, BRAND);
        insertVariant(COMBO_VARIANT, COMBO_PRODUCT, "ACTIVE", TENANT, BRAND);
        insertVariant(SECOND_COMBO_VARIANT, COMBO_PRODUCT, "ACTIVE", TENANT, BRAND);
        insertProduct(BURGER_PRODUCT, "BURGER", TENANT, BRAND);
        insertVariant(BURGER_VARIANT, BURGER_PRODUCT, "ACTIVE", TENANT, BRAND);
        insertVariant(ARCHIVED_VARIANT, BURGER_PRODUCT, "ARCHIVED", TENANT, BRAND);
        UUID colaProduct = UUID.fromString("018f9f10-4000-7000-8000-0000000000d5");
        insertProduct(colaProduct, "COLA", TENANT, BRAND);
        insertVariant(COLA_VARIANT, colaProduct, "ACTIVE", TENANT, BRAND);

        UUID foreignProduct = UUID.fromString("018f9f10-4000-7000-8000-0000000000db");
        insertProduct(foreignProduct, "FOREIGN", OTHER_TENANT, OTHER_BRAND);
        insertVariant(FOREIGN_VARIANT, foreignProduct, "ACTIVE", OTHER_TENANT, OTHER_BRAND);

        insertModifierGroup(BOX_GROUP, "DELIVERY-BOX", true, 1, 1);
        jdbc.sql("""
                INSERT INTO catalog.modifier_options (id, tenant_id, brand_id, modifier_group_id, code, status)
                VALUES (:id, :tenantId, :brandId, :groupId, 'BOX', 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("groupId", BOX_GROUP)
                .update();
        insertModifierGroup(SAUCE_GROUP, "SAUCE", false, 0, 3);
    }

    private void insertModifierGroup(UUID id, String code, boolean required, int minimum, int maximum) {
        jdbc.sql("""
                INSERT INTO catalog.modifier_groups (id, tenant_id, brand_id, code, is_required,
                    minimum_selections, maximum_selections, status)
                VALUES (:id, :tenantId, :brandId, :code, :required, :minimum, :maximum, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("required", required)
                .param("minimum", minimum)
                .param("maximum", maximum)
                .update();
    }

    private UUID insertProductAndVariant(String code, UUID variantId, boolean unused) {
        UUID productId = UUID.randomUUID();
        insertProduct(productId, code, TENANT, BRAND);
        insertVariant(variantId, productId, "ACTIVE", TENANT, BRAND);
        return productId;
    }

    private void insertProduct(UUID id, String code, UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
    }

    private void insertVariant(UUID id, UUID productId, String status, UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, :isDefault, :status)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("isDefault", !"ARCHIVED".equals(status) && !SECOND_COMBO_VARIANT.equals(id))
                .param("status", status)
                .update();
    }

    private void grant(String subject, UUID tenantId, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'composite authoring endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
