package uz.horecaos.platform.customers.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row 1.3a, over real HTTP: {@code LOCATION_STAFF}/{@code LOCATION_MANAGER}
 * can now open a customer account from a location-scoped call, where they
 * 403'd before this wave.
 *
 * <p>{@code CustomerControllerEndpointTests} already proves the same shape of
 * question — a capability declared, held by one fixture subject and withheld
 * from another — for {@code CustomerController} itself; this is the sibling
 * file for {@link OperationsCustomerController}, because a service-level test
 * of {@code CustomerIdentityService} would prove the write correct without
 * ever proving {@code CapabilityEnforcementInterceptor} and the {@code
 * ResourceScope#covers} scope check in front of it agree that a {@code
 * LOCATION}-scoped grant actually reaches this endpoint — the exact fact
 * {@code OperationsCustomerController}'s own doc explains was false for
 * {@code CustomerController#createManually}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsCustomerControllerHttpTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    /** {@code LOCATION_STAFF} at {@code LOCATION} — this wave's own grant (row 1.3a). */
    private static final String STAFF = "op-customer-http-staff";

    /** {@code LOCATION_MANAGER} at {@code LOCATION} — the New order screen's other persona. */
    private static final String MANAGER = "op-customer-http-manager";

    /** {@code TENANT_FINANCE} at {@code LOCATION} — holds neither {@code CUSTOMER_READ} nor {@code CUSTOMER_CREATE}. */
    private static final String NO_CAPABILITY = "op-customer-http-finance";

    /** {@code LOCATION_STAFF} again, under a name of its own so the rate-limit case spends nobody else's allowance. */
    private static final String RATE_LIMITED = "op-customer-http-rate-limited";

    private static final String CUSTOMERS_PATH =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/customers";

    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final tools.jackson.databind.ObjectMapper JSON =
            JsonMapper.builder().build();

    /**
     * The address the console sends from the New order pane (a pin the operator confirmed), with
     * every part a courier would knock on: the house in the street line, an entrance, a floor, a
     * flat, a landmark, a second line and a note for the door.
     */
    private static final String ADDRESS_WITH_PIN = """
            {"label":"Home",
             "fields":{"line1":"Amir Temur ko'chasi 15","line2":"Orient business centre","city":"Tashkent",
                       "district":"Yunusobod","postalCode":"100084","entrance":"3","floor":"4","apartment":"12",
                       "landmark":"blue gate behind the pharmacy"},
             "deliveryInstructions":"Ring the top bell twice",
             "latitude":41.311081,"longitude":69.240562,"coordinateSource":"OPERATOR_PIN"}""";

    /** The same address with no pin: saved honestly, refused for delivery until it has one. */
    private static final String ADDRESS_WITHOUT_PIN = """
            {"label":"Office",
             "fields":{"line1":"Navoi ko'chasi 7","city":"Tashkent","district":"Chilonzor","landmark":"opposite the bank"},
             "latitude":null,"longitude":null,"coordinateSource":"LANDMARK_ONLY"}""";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the operations customer endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // A contact point row is envelope-encrypted (ADR 0029); the default
        // Spring context carries no kek outside the "local" profile.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.addresses, "
                        + "customer.contact_points, customer.brand_profiles, customer.principal_links, "
                        + "customer.blacklist_entries, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        seedTenancy();
        grant(STAFF, PlatformRole.LOCATION_STAFF);
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(NO_CAPABILITY, PlatformRole.TENANT_FINANCE);
        grant(RATE_LIMITED, PlatformRole.LOCATION_STAFF);
    }

    @Test
    void locationStaffOpensAnAccountThroughTheLocationScopedEndpoint() throws Exception {
        MvcResult result = mvc.perform(post(CUSTOMERS_PATH)
                        .with(tokenFor(STAFF))
                        .header("Idempotency-Key", "op-customer-staff-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+998901112233\",\"displayName\":\"Aziz\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("row 1.3a: LOCATION_STAFF's own grant is at LOCATION scope, and this endpoint is declared there")
                .isEqualTo(201);
        String body = result.getResponse().getContentAsString(UTF_8);
        assertThat(body).contains("\"id\"");

        Map<String, Object> row = jdbc.sql(
                        "SELECT origin, created_by_actor_id FROM customer.customer_accounts WHERE tenant_id = :t")
                .param("t", TENANT)
                .query()
                .singleRow();
        assertThat(row.get("origin")).isEqualTo("OPERATOR");
        assertThat(row.get("created_by_actor_id")).isEqualTo(STAFF);
    }

    @Test
    void locationManagerOpensAnAccountThroughTheLocationScopedEndpointToo() throws Exception {
        MvcResult result = mvc.perform(post(CUSTOMERS_PATH)
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "op-customer-manager-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+998901112244\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void aSubjectWithoutCustomerCreateIsRefused() throws Exception {
        MvcResult result = mvc.perform(post(CUSTOMERS_PATH)
                        .with(tokenFor(NO_CAPABILITY))
                        .header("Idempotency-Key", "op-customer-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+998901112255\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        Long count = jdbc.sql("SELECT count(*) FROM customer.customer_accounts WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
        assertThat(count).isZero();
    }

    // ===================================================================== ADR 0177: the delivery address

    @Test
    void locationStaffGivesAnAddressWithAPinAndReadsItBackMasked() throws Exception {
        UUID account = openAccount(STAFF);

        MvcResult added = addAddress(STAFF, account, ADDRESS_WITH_PIN, "add-with-pin");
        assertThat(added.getResponse().getStatus())
                .as("the persona holds no CUSTOMER_MANAGE; the narrow capability is what this route asks for")
                .isEqualTo(201);
        UUID addressId = UUID.fromString(JSON.readTree(added.getResponse().getContentAsString(UTF_8))
                .path("id")
                .asText());

        MvcResult listed = list(STAFF, account);
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        String body = listed.getResponse().getContentAsString(UTF_8);
        JsonNode only = JSON.readTree(body).get(0);
        assertThat(JSON.readTree(body)).hasSize(1);
        assertThat(only.path("id").asText()).isEqualTo(addressId.toString());
        assertThat(only.path("label").asText()).isEqualTo("Home");
        assertThat(only.path("street").asText())
                .as("the street, without the house number")
                .isEqualTo("Amir Temur ko'chasi");
        assertThat(only.path("city").asText()).isEqualTo("Tashkent");
        assertThat(only.path("district").asText()).isEqualTo("Yunusobod");
        assertThat(only.path("coordinateSource").asText()).isEqualTo("OPERATOR_PIN");
        assertThat(only.path("latitude").asDouble()).isEqualTo(41.311081);
        assertThat(only.path("longitude").asDouble()).isEqualTo(69.240562);
        assertThat(only.path("version").asInt()).isPositive();
    }

    @Test
    void theMaskedListNeverCarriesAnythingTheEncryptedDocumentDoes() throws Exception {
        UUID account = openAccount(STAFF);
        addAddress(STAFF, account, ADDRESS_WITH_PIN, "mask-1");
        addAddress(STAFF, account, ADDRESS_WITHOUT_PIN, "mask-2");

        String body = list(STAFF, account).getResponse().getContentAsString(UTF_8);

        assertThat(JSON.readTree(body)).hasSize(2);
        for (String secret :
                List.of("Orient", "100084", "blue gate", "pharmacy", "Ring the top bell", "opposite the bank")) {
            assertThat(body)
                    .as("the masked list must not carry '" + secret + "'")
                    .doesNotContain(secret);
        }
        for (String field : List.of(
                "fields",
                "entrance",
                "floor",
                "apartment",
                "landmark",
                "line1",
                "line2",
                "postalCode",
                "deliveryInstructions")) {
            assertThat(byLabel(body, "Home").has(field))
                    .as("the masked projection has no '" + field + "' member")
                    .isFalse();
        }
        JsonNode home = byLabel(body, "Home");
        JsonNode office = byLabel(body, "Office");
        assertThat(home.path("street").asText())
                .as("the house number 15 is not part of what the operator reads")
                .isEqualTo("Amir Temur ko'chasi");
        assertThat(office.path("street").asText()).isEqualTo("Navoi ko'chasi");
        assertThat(office.path("latitude").isNull())
                .as("an address with no pin says so")
                .isTrue();
        assertThat(office.path("coordinateSource").asText()).isEqualTo("LANDMARK_ONLY");
    }

    @Test
    void theTenantAddressRoutesStayRefusedToTheNewOrderPersona() throws Exception {
        UUID account = openAccount(STAFF);
        String tenantAddresses = "/api/v1/tenants/" + TENANT + "/customers/" + account + "/addresses";

        MvcResult reveal = mvc.perform(
                        get(tenantAddresses).param("purpose", "peek").with(tokenFor(STAFF)))
                .andReturn();
        MvcResult add = mvc.perform(post(tenantAddresses)
                        .with(tokenFor(STAFF))
                        .header("Idempotency-Key", "tenant-add-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS_WITH_PIN))
                .andReturn();

        assertThat(reveal.getResponse().getStatus())
                .as("LOCATION_STAFF still cannot reveal an address: the mask is the only door")
                .isEqualTo(403);
        assertThat(add.getResponse().getStatus()).isEqualTo(403);
        assertThat(addressRows(account)).isZero();
    }

    @Test
    void settingAPinMovesTheSourceAndTheVersionAndLeavesTheEncryptedLinesAlone() throws Exception {
        UUID account = openAccount(STAFF);
        addAddress(STAFF, account, ADDRESS_WITHOUT_PIN, "pin-1");
        JsonNode before = JSON.readTree(list(STAFF, account).getResponse().getContentAsString(UTF_8))
                .get(0);
        UUID addressId = UUID.fromString(before.path("id").asText());
        int version = before.path("version").asInt();
        String linesBefore = jdbc.sql("SELECT encrypted_fields FROM customer.addresses WHERE id = :id")
                .param("id", addressId)
                .query(String.class)
                .single();

        MvcResult moved =
                setPin(STAFF, account, addressId, version, "{\"latitude\":41.3,\"longitude\":69.2}", "pin-set-1");

        assertThat(moved.getResponse().getStatus())
                .as(moved.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        JsonNode answer = JSON.readTree(moved.getResponse().getContentAsString(UTF_8));
        assertThat(answer.path("coordinateSource").asText()).isEqualTo("OPERATOR_PIN");
        assertThat(answer.path("latitude").asDouble()).isEqualTo(41.3);
        assertThat(answer.path("version").asInt()).isEqualTo(version + 1);
        assertThat(answer.path("street").asText()).isEqualTo("Navoi ko'chasi");
        Map<String, Object> row = jdbc.sql(
                        "SELECT encrypted_fields, coordinate_source, latitude, version FROM customer.addresses WHERE id = :id")
                .param("id", addressId)
                .query()
                .singleRow();
        assertThat(row.get("encrypted_fields"))
                .as("the pin is a write to two clear columns; the encrypted document is neither read nor rewritten")
                .isEqualTo(linesBefore);
        assertThat(row.get("coordinate_source")).isEqualTo("OPERATOR_PIN");
        assertThat(row.get("latitude")).isEqualTo(41.3);
        assertThat(row.get("version")).isEqualTo(version + 1);
    }

    @Test
    void aStaleVersionMovesNothing() throws Exception {
        UUID account = openAccount(STAFF);
        addAddress(STAFF, account, ADDRESS_WITHOUT_PIN, "stale-1");
        JsonNode before = JSON.readTree(list(STAFF, account).getResponse().getContentAsString(UTF_8))
                .get(0);
        UUID addressId = UUID.fromString(before.path("id").asText());
        int version = before.path("version").asInt();

        MvcResult first =
                setPin(STAFF, account, addressId, version, "{\"latitude\":41.3,\"longitude\":69.2}", "stale-a");
        MvcResult second =
                setPin(STAFF, account, addressId, version, "{\"latitude\":40.0,\"longitude\":68.0}", "stale-b");

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus())
                .as("the second operator read the same version and lost loudly")
                .isEqualTo(409);
        assertThat(jdbc.sql("SELECT latitude FROM customer.addresses WHERE id = :id")
                        .param("id", addressId)
                        .query(Double.class)
                        .single())
                .isEqualTo(41.3);
    }

    @Test
    void anOperatorCannotRecordAGeocodersAnswerOrACustomersPin() throws Exception {
        UUID account = openAccount(STAFF);

        for (String source : List.of("GEOCODER", "CUSTOMER_PIN", "LEGACY_UNSOURCED")) {
            MvcResult refused = addAddress(
                    STAFF,
                    account,
                    ADDRESS_WITH_PIN.replace("\"OPERATOR_PIN\"", "\"" + source + "\""),
                    "source-" + source);
            assertThat(refused.getResponse().getStatus())
                    .as(source + " is not what a person at a console can claim")
                    .isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("VALIDATION_FAILED");
        }
        MvcResult halfAPoint = addAddress(
                STAFF,
                account,
                ADDRESS_WITH_PIN.replace("\"longitude\":69.240562", "\"longitude\":null"),
                "source-half");
        assertThat(halfAPoint.getResponse().getStatus()).isEqualTo(400);
        assertThat(addressRows(account)).isZero();
    }

    @Test
    void aPinWithoutBothCoordinatesIsAFieldRefusal() throws Exception {
        UUID account = openAccount(STAFF);
        addAddress(STAFF, account, ADDRESS_WITHOUT_PIN, "field-1");
        JsonNode saved = JSON.readTree(list(STAFF, account).getResponse().getContentAsString(UTF_8))
                .get(0);

        MvcResult missing = setPin(
                STAFF,
                account,
                UUID.fromString(saved.path("id").asText()),
                saved.path("version").asInt(),
                "{\"longitude\":69.2}",
                "field-pin");

        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getContentAsString(UTF_8))
                .as("Jackson 3 would call a missing primitive a malformed body; both coordinates are boxed")
                .doesNotContain("MALFORMED_BODY");
    }

    @Test
    void anAccountThisBrandMayNotServeIsNotFoundOnAllThreeRoutes() throws Exception {
        UUID otherBrandsAccount = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, identity_partition_brand_id, status,
                    created_at, updated_at)
                VALUES (:id, :t, :b, 'ACTIVE', now(), now())
                """)
                .param("id", otherBrandsAccount)
                .param("t", TENANT)
                .param("b", OTHER_BRAND)
                .update();
        UUID otherTenantsAccount = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, created_at, updated_at)
                VALUES (:id, :t, 'ACTIVE', now(), now())
                """).param("id", otherTenantsAccount).param("t", OTHER_TENANT).update();

        for (UUID account : List.of(otherBrandsAccount, otherTenantsAccount, UUID.randomUUID())) {
            assertThat(list(STAFF, account).getResponse().getStatus())
                    .as("the masked list of " + account)
                    .isEqualTo(404);
            assertThat(addAddress(STAFF, account, ADDRESS_WITH_PIN, "nf-add-" + account)
                            .getResponse()
                            .getStatus())
                    .isEqualTo(404);
            assertThat(setPin(
                                    STAFF,
                                    account,
                                    UUID.randomUUID(),
                                    1,
                                    "{\"latitude\":41.3,\"longitude\":69.2}",
                                    "nf-pin-" + account)
                            .getResponse()
                            .getStatus())
                    .isEqualTo(404);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM customer.addresses")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void anAddressOfAnotherCustomerIsNotFoundWhenItsIdIsGivenForThisOne() throws Exception {
        UUID mine = openAccount(STAFF);
        UUID theirs = openAccount(STAFF);
        addAddress(STAFF, theirs, ADDRESS_WITHOUT_PIN, "cross-1");
        JsonNode their = JSON.readTree(list(STAFF, theirs).getResponse().getContentAsString(UTF_8))
                .get(0);

        MvcResult stolen = setPin(
                STAFF,
                mine,
                UUID.fromString(their.path("id").asText()),
                their.path("version").asInt(),
                "{\"latitude\":41.3,\"longitude\":69.2}",
                "cross-pin");

        assertThat(stolen.getResponse().getStatus())
                .as("the account is a predicate of the statement, never a comparison after the load")
                .isEqualTo(404);
        assertThat(jdbc.sql("SELECT latitude IS NULL FROM customer.addresses WHERE id = :id")
                        .param("id", UUID.fromString(their.path("id").asText()))
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void everyCallIsAnAuditFactThatNamesTheAccountAndCarriesNoAddress() throws Exception {
        UUID account = openAccount(STAFF);
        addAddress(STAFF, account, ADDRESS_WITH_PIN, "audit-add");
        JsonNode saved = JSON.readTree(list(STAFF, account).getResponse().getContentAsString(UTF_8))
                .get(0);
        setPin(
                STAFF,
                account,
                UUID.fromString(saved.path("id").asText()),
                saved.path("version").asInt(),
                "{\"latitude\":41.31,\"longitude\":69.25}",
                "audit-pin");

        List<Map<String, Object>> facts =
                jdbc.sql("""
                        SELECT action_code, audit_class, capability_used, change_document::text AS change
                        FROM audit.audit_events
                        WHERE tenant_id = :t AND action_code LIKE 'customer.address.%'
                        ORDER BY occurred_at, action_code
                        """).param("t", TENANT).query().listOfRows();

        assertThat(facts)
                .extracting(row -> row.get("action_code"))
                .containsExactlyInAnyOrder(
                        "customer.address.captured", "customer.address.listed_masked", "customer.address.pin_set");
        for (Map<String, Object> fact : facts) {
            assertThat(fact.get("capability_used")).isEqualTo("customer.address.capture");
            String change = String.valueOf(fact.get("change"));
            for (String secret :
                    List.of("Amir Temur", "Navoi", "blue gate", "41.31", "69.24", "Ring the top bell", "Home")) {
                assertThat(change)
                        .as(fact.get("action_code") + " must not carry '" + secret + "'")
                        .doesNotContain(secret);
            }
        }
        assertThat(facts.stream()
                        .filter(fact -> "customer.address.listed_masked".equals(fact.get("action_code")))
                        .findFirst()
                        .orElseThrow()
                        .get("audit_class"))
                .as("a read of the customer base's addresses is a SECURITY fact, like the phone lookup")
                .isEqualTo("SECURITY");
    }

    @Test
    void aSubjectWithoutTheCapabilityIsRefusedOnAllThreeRoutesAndNothingIsWritten() throws Exception {
        UUID account = openAccount(STAFF);

        assertThat(list(NO_CAPABILITY, account).getResponse().getStatus()).isEqualTo(403);
        assertThat(addAddress(NO_CAPABILITY, account, ADDRESS_WITH_PIN, "denied-add")
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(setPin(
                                NO_CAPABILITY,
                                account,
                                UUID.randomUUID(),
                                1,
                                "{\"latitude\":41.3,\"longitude\":69.2}",
                                "denied-pin")
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(addressRows(account)).isZero();
    }

    @Test
    void locationManagerCanUseThePaneToo() throws Exception {
        UUID account = openAccount(MANAGER);

        assertThat(addAddress(MANAGER, account, ADDRESS_WITH_PIN, "manager-add")
                        .getResponse()
                        .getStatus())
                .as(
                        "the gap map said only LOCATION_MANAGER and above could; it held PII_REVEAL but the route was TENANT-scoped")
                .isEqualTo(201);
        assertThat(list(MANAGER, account).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theMaskedListIsRateLimitedPerOperator() throws Exception {
        UUID account = openAccount(RATE_LIMITED);

        // A token bucket refills while the calls are made, so the 61st is not necessarily the first
        // refusal: the allowance is finite when a refusal arrives within a few calls of the budget.
        int budget = (int) OperationsCustomerController.LIST_LIMIT.permits();
        MvcResult refused = null;
        int served = 0;
        for (int call = 0; call < budget * 3 && refused == null; call++) {
            MvcResult answer = list(RATE_LIMITED, account);
            if (answer.getResponse().getStatus() == 429) {
                refused = answer;
            } else {
                assertThat(answer.getResponse().getStatus()).isEqualTo(200);
                served++;
            }
        }

        MvcResult refusal = java.util.Objects.requireNonNull(refused, "a flood of masked lists must be refused");
        assertThat(served).isGreaterThanOrEqualTo(budget).isLessThan(budget * 2);
        assertThat(refusal.getResponse().getContentAsString(UTF_8)).contains("RATE_LIMIT_EXCEEDED");
        assertThat(list(STAFF, account).getResponse().getStatus())
                .as("another operator's allowance is her own")
                .isEqualTo(200);
    }

    // ----------------------------------------------------------------------------------- helpers

    /** The account a phone order is for, opened through the production create route, as an operator does on a miss. */
    private UUID openAccount(String subject) throws Exception {
        MvcResult created = mvc.perform(post(CUSTOMERS_PATH)
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", "open-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+99890" + (1_000_000 + (int) (Math.random() * 8_999_999)) + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        return UUID.fromString(JSON.readTree(created.getResponse().getContentAsString(UTF_8))
                .path("id")
                .asText());
    }

    private MvcResult list(String subject, UUID account) throws Exception {
        return mvc.perform(get(CUSTOMERS_PATH + "/" + account + "/addresses").with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult addAddress(String subject, UUID account, String body, String key) throws Exception {
        return mvc.perform(post(CUSTOMERS_PATH + "/" + account + "/addresses")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult setPin(String subject, UUID account, UUID address, int version, String body, String key)
            throws Exception {
        return mvc.perform(put(CUSTOMERS_PATH + "/" + account + "/addresses/" + address + "/pin")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key)
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** The masked entry with this label; the list's own order is by creation time, which two quick saves can tie on. */
    private static JsonNode byLabel(String listBody, String label) {
        for (JsonNode entry : JSON.readTree(listBody)) {
            if (label.equals(entry.path("label").asText())) {
                return entry;
            }
        }
        throw new AssertionError("no address labelled " + label + " in " + listBody);
    }

    private long addressRows(UUID account) {
        return jdbc.sql("SELECT count(*) FROM customer.addresses WHERE customer_account_id = :a")
                .param("a", account)
                .query(Long.class)
                .single();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'op-customer-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'OTHER', 'other', 'Other', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'op-customer-other', 'Legal', 'Other tenant', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :locationId,
                        'ACTIVE', 'test-fixture', 'operations customer endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("locationId", LOCATION)
                // Backdated for the same reason the other real-HTTP capability
                // suites do: a grant compared against this JVM's own clock under
                // heavy concurrent fork load can momentarily skew against the
                // container's wall clock.
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
