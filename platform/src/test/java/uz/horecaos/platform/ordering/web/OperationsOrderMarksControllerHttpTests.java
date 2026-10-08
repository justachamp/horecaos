package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.api.FeatureFlagKeys;

/**
 * ADR 0161's three marking endpoints through the real HTTP stack: the capability each needs, the
 * {@code Idempotency-Key} each requires, the problem bodies a console reads its {@code reason} from, and --
 * the point of the exercise -- that a scanned code is nowhere in any response.
 *
 * <p>The scan rules themselves (duplicates, product match, the lock, the guard) are
 * {@code OrderMarkingIntegrationTests}' business, against hand-wired services; this class owns the edge.
 * The rows are seeded the way {@code OperationsOrderCompletionHttpTests} seeds them: raw SQL, with foreign
 * keys switched off for the connection so the quote and cart an order would reference need not exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsOrderMarksControllerHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc400-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc400-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fc400-4000-7000-8000-0000000000c1");
    private static final UUID CHANNEL = UUID.fromString("018fc400-4000-7000-8000-0000000000d1");
    private static final UUID VODKA = UUID.fromString("018fc400-4000-7000-8000-0000000000f1");

    /** Holds {@code ORDER_ADVANCE} and {@code ORDER_READ} at {@code LOCATION}. */
    private static final String SCANNER = "marks-http-scanner";

    /** Holds {@code FISCAL_DOCUMENT_READ} at tenant scope, which the fiscal worklist requires. */
    private static final String FINANCE = "marks-http-finance";

    /** Holds neither {@code ORDER_ADVANCE} nor {@code ORDER_READ}. */
    private static final String OUTSIDER = "marks-http-outsider";

    /** The product part is the EAN-13 4600000000015 the classification states. */
    private static final String CODE_1 = "010460000000001521HttpSerial0001";

    private static final String CODE_2 = "010460000000001521HttpSerial0002";
    private static final String OTHER_PRODUCT = "010460000000002221HttpSerial0003";
    private static final String TAIL = "HttpSerial0001";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the marking HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        // The fixture inserts an order without its quote, cart and publication: the marks are what is under test.
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET session_replication_role = replica");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
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

    @Autowired
    @SuppressWarnings("NullAway")
    private ConfigurationValueAuthor configuration;

    private UUID orderId;
    private UUID lineId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE ordering.order_line_marks, ordering.order_lines, ordering.order_revisions, "
                        + "ordering.orders CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.fiscal_classifications CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("DELETE FROM fiscal.fiscal_documents").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(SCANNER, PlatformRole.LOCATION_STAFF, LOCATION);
        classifyVodka("4600000000015");
        turnFlag(true);
        orderId = UUID.randomUUID();
        lineId = UUID.randomUUID();
        seedOrder("PREPARING", 2);
    }

    // ------------------------------------------------------------------ capability and idempotency

    @Test
    @DisplayName("a caller without ORDER_ADVANCE cannot scan or take back, and one without ORDER_READ cannot read")
    void capabilitiesAreRequired() throws Exception {
        grant(OUTSIDER, PlatformRole.TENANT_FINANCE, LOCATION);

        MvcResult scan = mvc.perform(capture(OUTSIDER, CODE_1, "SCANNER")).andReturn();
        MvcResult removal =
                mvc.perform(removal(OUTSIDER, UUID.randomUUID(), "MISSCAN")).andReturn();
        MvcResult read =
                mvc.perform(get(marksPath()).with(tokenFor("nobody-at-all"))).andReturn();

        assertThat(scan.getResponse().getStatus()).isEqualTo(403);
        assertThat(scan.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_ADVANCE.code());
        assertThat(removal.getResponse().getStatus()).isEqualTo(403);
        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(liveMarks()).as("nothing was written for a refused caller").isZero();
    }

    @Test
    @DisplayName("a scan without an Idempotency-Key is refused before the code is looked at")
    void idempotencyKeyIsRequired() throws Exception {
        MvcResult result = mvc.perform(post(linePath() + "/marks")
                        .with(tokenFor(SCANNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CODE_1, "SCANNER")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("IDEMPOTENCY_KEY_REQUIRED")
                .doesNotContain(TAIL);
        assertThat(liveMarks()).isZero();
    }

    // ------------------------------------------------------------------ capture

    @Test
    @DisplayName("a scan is stored, counted and answered with the product identifier -- never the code")
    void captureAnswersWithCountsAndNoCode() throws Exception {
        MvcResult result = mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String response = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(response, "$.gtin")).isEqualTo("04600000000015");
        assertThat((Integer) JsonPath.read(response, "$.captured")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(response, "$.required")).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(response, "$.orderMarksComplete")).isFalse();
        assertThat((Boolean) JsonPath.read(response, "$.gtinChecked")).isTrue();
        assertThat(response)
                .as("the response is counts and a product identifier")
                .doesNotContain(TAIL);
        assertThat(liveMarks()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_line_marks WHERE code_encrypted LIKE :tail")
                        .param("tail", "%" + TAIL + "%")
                        .query(Long.class)
                        .single())
                .as("the stored value is the protected envelope and not the code")
                .isZero();
    }

    @Test
    @DisplayName("the last unit says the order's marks are complete")
    void theLastUnitCompletesTheOrderMarks() throws Exception {
        mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();

        MvcResult last = mvc.perform(capture(SCANNER, CODE_2, "CAMERA")).andReturn();

        assertThat(last.getResponse().getStatus()).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(last.getResponse().getContentAsString(), "$.orderMarksComplete"))
                .isTrue();
        assertThat((Integer) JsonPath.read(last.getResponse().getContentAsString(), "$.captured"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("replaying the same Idempotency-Key answers the first scan again and stores nothing more")
    void aReplayedScanIsNotCountedTwice() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult first = mvc.perform(capture(SCANNER, CODE_1, "SCANNER", key)).andReturn();
        MvcResult replay = mvc.perform(capture(SCANNER, CODE_1, "SCANNER", key)).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(liveMarks()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same unit scanned twice on one line is a conflict that says it is already on this line")
    void aDuplicateNamesTheLine() throws Exception {
        mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();

        MvcResult again = mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        String response = again.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(response, "$.reason")).isEqualTo("MARKING_CODE_ALREADY_CAPTURED");
        assertThat((Boolean) JsonPath.read(response, "$.onThisLine")).isTrue();
        assertThat(response).doesNotContain(TAIL);
        assertThat(liveMarks()).isEqualTo(1);
    }

    @Test
    @DisplayName("a code for another product is refused with a fixed reason and does not echo what was scanned")
    void aWrongProductIsRefusedWithoutEchoingIt() throws Exception {
        MvcResult result =
                mvc.perform(capture(SCANNER, OTHER_PRODUCT, "SCANNER")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        String response = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(response, "$.reason")).isEqualTo("MARKING_CODE_WRONG_PRODUCT");
        assertThat(response).doesNotContain("HttpSerial0003").doesNotContain("4600000000022");
        assertThat(liveMarks()).isZero();
    }

    @Test
    @DisplayName("a scan that is not a whole marking code is a validation problem with a stable reason")
    void aMalformedScanIsAValidationProblem() throws Exception {
        MvcResult result = mvc.perform(capture(SCANNER, "garbage with spaces!!", "SCANNER"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String response = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(response, "$.reason")).isEqualTo("MARKING_CODE_MALFORMED");
        assertThat(response).doesNotContain("garbage");
    }

    @Test
    @DisplayName("an unknown entry method is a validation problem, and a hand-keyed code is refused by default")
    void entryMethodRules() throws Exception {
        MvcResult unknown = mvc.perform(capture(SCANNER, CODE_1, "TELEPATHY")).andReturn();
        MvcResult keyed = mvc.perform(capture(SCANNER, CODE_1, "KEYED")).andReturn();

        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(keyed.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(keyed.getResponse().getContentAsString(), "$.reason"))
                .isEqualTo("MARKING_KEYED_ENTRY_REFUSED");
        assertThat(liveMarks()).isZero();
    }

    @Test
    @DisplayName("a missing code is a validation problem and a body is never required to carry anything else")
    void aBlankCodeIsRefused() throws Exception {
        MvcResult result = mvc.perform(post(linePath() + "/marks")
                        .with(tokenFor(SCANNER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"entryMethod\":\"SCANNER\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName(
            "while the flag is off the endpoints answer as if they did not exist, and the order carries no marking")
    void theFlagOffHidesEverything() throws Exception {
        turnFlag(false);

        MvcResult scan = mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();
        MvcResult read = mvc.perform(get(marksPath()).with(tokenFor(SCANNER))).andReturn();
        MvcResult detail = mvc.perform(get(orderPath()).with(tokenFor(SCANNER))).andReturn();

        assertThat(scan.getResponse().getStatus()).isEqualTo(404);
        assertThat((String) JsonPath.read(scan.getResponse().getContentAsString(), "$.reason"))
                .isEqualTo("FEATURE_DISABLED");
        assertThat(read.getResponse().getStatus()).isEqualTo(404);
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(detail.getResponse().getContentAsString())
                .as("a tenant with the flag off sees no marking on its lines")
                .doesNotContain("\"marking\":{");
        assertThat(liveMarks()).isZero();
    }

    @Test
    @DisplayName("an order of another location answers not-found, however valid the ids")
    void anotherLocationsOrderIsNotFound() throws Exception {
        UUID elsewhere = UUID.randomUUID();
        MockHttpServletRequestBuilder request = post("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/"
                        + elsewhere + "/orders/" + orderId + "/lines/" + lineId + "/marks")
                .with(tokenFor(SCANNER))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(CODE_1, "SCANNER"));

        MvcResult result = mvc.perform(request).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(403, 404);
        assertThat(liveMarks()).isZero();
    }

    // ------------------------------------------------------------------ read

    @Test
    @DisplayName("the read lists each scanned unit with its sequence, product identifier and stage -- and no code")
    void theReadNeverReturnsACode() throws Exception {
        mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();

        MvcResult result = mvc.perform(get(marksPath()).with(tokenFor(SCANNER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String response = result.getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(response, "$.lines[0].required")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(response, "$.lines[0].captured")).isEqualTo(1);
        assertThat((String) JsonPath.read(response, "$.lines[0].marks[0].gtin")).isEqualTo("04600000000015");
        assertThat((String) JsonPath.read(response, "$.lines[0].marks[0].stage"))
                .isEqualTo("PICK");
        assertThat((Integer) JsonPath.read(response, "$.lines[0].marks[0].sequence"))
                .isEqualTo(1);
        assertThat(response).doesNotContain(TAIL).doesNotContain("code");
    }

    @Test
    @DisplayName("the order detail carries the marking counts of a marked line and moves as units are scanned")
    void theOrderDetailCarriesTheCounts() throws Exception {
        MvcResult before = mvc.perform(get(orderPath()).with(tokenFor(SCANNER))).andReturn();
        mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();
        MvcResult after = mvc.perform(get(orderPath()).with(tokenFor(SCANNER))).andReturn();

        assertThat(before.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(before.getResponse().getContentAsString(), "$.lines[0].marking.required"))
                .isEqualTo(2);
        assertThat((Integer) JsonPath.read(before.getResponse().getContentAsString(), "$.lines[0].marking.captured"))
                .isZero();
        assertThat((Integer) JsonPath.read(after.getResponse().getContentAsString(), "$.lines[0].marking.captured"))
                .isEqualTo(1);
        assertThat(after.getResponse().getContentAsString()).doesNotContain(TAIL);
    }

    // ------------------------------------------------------------------ removal

    @Test
    @DisplayName("a scan is taken back with a coded reason; a free-text or unknown reason is a validation problem")
    void removalNeedsACodedReason() throws Exception {
        MvcResult scanned = mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();
        UUID markId = UUID.fromString(JsonPath.read(scanned.getResponse().getContentAsString(), "$.markId"));

        MvcResult free = mvc.perform(removal(SCANNER, markId, "the customer Aziza returned it"))
                .andReturn();
        MvcResult coded = mvc.perform(removal(SCANNER, markId, "UNIT_DAMAGED")).andReturn();

        assertThat(free.getResponse().getStatus()).isEqualTo(400);
        assertThat(free.getResponse().getContentAsString()).doesNotContain("Aziza");
        assertThat(liveMarks())
                .as("the refused removal changed nothing before the coded one")
                .isZero();
        assertThat(coded.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(coded.getResponse().getContentAsString(), "$.captured"))
                .isZero();
        assertThat((Integer) JsonPath.read(coded.getResponse().getContentAsString(), "$.required"))
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_line_marks WHERE id = :id")
                        .param("id", markId)
                        .query(Long.class)
                        .single())
                .as("a mark is released, never deleted")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("once the order has left the pass its units cannot be taken back")
    void removalIsRefusedAfterThePass() throws Exception {
        MvcResult scanned = mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();
        UUID markId = UUID.fromString(JsonPath.read(scanned.getResponse().getContentAsString(), "$.markId"));
        jdbc.sql("UPDATE ordering.orders SET status = 'FULFILLING' WHERE id = :id")
                .param("id", orderId)
                .update();

        MvcResult result = mvc.perform(removal(SCANNER, markId, "MISSCAN")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.reason"))
                .isEqualTo("ORDER_NOT_MARKABLE");
        assertThat(liveMarks()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the fiscal worklist

    @Test
    @DisplayName("the blocked fiscal worklist says how many units of a short marked order are scanned, "
            + "and says nothing of the kind for a document blocked for another reason")
    void theFiscalWorklistCarriesTheCounts() throws Exception {
        grantTenant(FINANCE, PlatformRole.TENANT_FINANCE);
        mvc.perform(capture(SCANNER, CODE_1, "SCANNER")).andReturn();
        blockedDocument(orderId, "MARKS_INCOMPLETE");
        UUID plainOrder = UUID.randomUUID();
        insertOrderRow(plainOrder, "COMPLETED");
        blockedDocument(plainOrder, "NO_FISCAL_PATH");

        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/fiscal/documents/blocked")
                        .with(tokenFor(FINANCE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String response = result.getResponse().getContentAsString();
        List<Integer> captured =
                JsonPath.read(response, "$.documents[?(@.reasonCode=='MARKS_INCOMPLETE')].marks.captured");
        List<Integer> required =
                JsonPath.read(response, "$.documents[?(@.reasonCode=='MARKS_INCOMPLETE')].marks.required");
        assertThat(captured).containsExactly(1);
        assertThat(required).containsExactly(2);
        List<Object> other = JsonPath.read(response, "$.documents[?(@.reasonCode=='NO_FISCAL_PATH')].marks");
        assertThat(other)
                .as("no marks block on a document that is not waiting for scans")
                .containsExactly((Object) null);
        assertThat(response).doesNotContain(TAIL);
    }

    // ------------------------------------------------------------------ fixtures

    private MockHttpServletRequestBuilder capture(String subject, String code, String method) {
        return capture(subject, code, method, UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder capture(String subject, String code, String method, String key) {
        return post(linePath() + "/marks")
                .with(tokenFor(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, method));
    }

    private MockHttpServletRequestBuilder removal(String subject, UUID markId, String reason) {
        return post(linePath() + "/marks/" + markId + "/removals")
                .with(tokenFor(subject))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"" + reason + "\"}");
    }

    private static String body(String code, String method) {
        return "{\"code\":\"" + code + "\",\"entryMethod\":\"" + method + "\"}";
    }

    private String orderPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/" + orderId;
    }

    private String marksPath() {
        return orderPath() + "/marks";
    }

    private String linePath() {
        return orderPath() + "/lines/" + lineId;
    }

    private long liveMarks() {
        return jdbc.sql("SELECT count(*) FROM ordering.order_line_marks WHERE released_at IS NULL")
                .query(Long.class)
                .single();
    }

    private void turnFlag(boolean on) {
        // The configuration author is optimistic: a value that exists is changed against its version.
        Long version = jdbc.sql("""
                        SELECT version FROM tenant.configuration_values
                        WHERE key_code = :key AND scope_type = 'TENANT' AND tenant_id = :t
                        """)
                .param("key", FeatureFlagKeys.MARKED_GOODS.code())
                .param("t", TENANT)
                .query(Long.class)
                .optional()
                .orElse(null);
        configuration.set(
                FeatureFlagKeys.MARKED_GOODS,
                ResourceScope.tenant(TENANT),
                on,
                false,
                version,
                ActorRef.user("test-admin", null),
                "marking http test");
    }

    private void classifyVodka(String barcode) {
        jdbc.sql("""
                INSERT INTO catalog.fiscal_classifications (id, tenant_id, brand_id, variant_id, mxik_code,
                    package_code, fiscal_unit_code, fiscal_name, barcode, marking_required, marking_scheme, source)
                VALUES (:id, :t, :b, :variant, '22030000000000000', '123456', 796, 'Vodka', :barcode,
                    true, 'DATA_MATRIX', 'MANUAL')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("variant", VODKA)
                .param("barcode", barcode)
                .update();
    }

    private void seedOrder(String status, int quantity) {
        insertOrderRow(orderId, status);
        jdbc.sql("""
                INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number, source_product_id,
                    source_variant_id, product_name_snapshot, quantity, unit_amount_minor, base_amount_minor,
                    final_amount_minor, tax_amount_minor)
                VALUES (:id, :t, :o, 1, :product, :variant, 'Vodka', :q, 50000, 50000, :total, 0)
                """)
                .param("id", lineId)
                .param("t", TENANT)
                .param("o", orderId)
                .param("product", UUID.randomUUID())
                .param("variant", VODKA)
                .param("q", BigDecimal.valueOf(quantity))
                .param("total", 50000L * quantity)
                .update();
    }

    private void insertOrderRow(UUID id, String status) {
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, guest_reference_hash, fulfillment_mode, acceptance_mode_snapshot,
                    acceptance_policy_id, acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    promise_basis, version, confirmed_at)
                VALUES (:id, :number, :t, :b, :l, :ch, 'STOREFRONT', :guest, 'PICKUP', 'AUTO_CONFIRM', NULL, 0,
                    'NONE', NULL, :status, 'UZS', 100000, 0, 100000, :quote, 'hash', :pub, :cart, :guest,
                    'NOT_PROMISED', 1, now())
                """)
                .param("id", id)
                .param("number", "MH-" + id.toString().substring(0, 6))
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("ch", CHANNEL)
                .param("guest", "guest-" + id)
                .param("status", status)
                .param("quote", UUID.randomUUID())
                .param("pub", UUID.randomUUID())
                .param("cart", UUID.randomUUID())
                .update();
        jdbc.sql("""
                INSERT INTO ordering.order_revisions (order_id, revision, tenant_id, source, pricing_quote_id,
                    pricing_context_hash, currency, subtotal_minor, tax_minor, total_minor)
                VALUES (:o, 1, :t, 'CHECKOUT', :quote, 'hash', 'UZS', 100000, 0, 100000)
                """)
                .param("o", id)
                .param("t", TENANT)
                .param("quote", UUID.randomUUID())
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'marks-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :t, 'STOREFRONT', 'WEB', 'Web', 'ACTIVE')
                """).param("id", CHANNEL).param("t", TENANT).update();
    }

    private void grantTenant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :scopeId,
                        'ACTIVE', 'test-fixture', 'marking http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + TENANT).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", TENANT)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void blockedDocument(UUID forOrder, String reasonCode) {
        Instant at = Instant.parse("2026-10-08T09:00:00Z");
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, legal_entity_id,
                    provider_type, document_type, status, reason_code, reason_note,
                    submitted_at, blocked_at, reporting_deadline_at, created_at, updated_at)
                VALUES (:id, :t, :o, :e, 'CLICK', 'SALE', 'BLOCKED', :reason,
                    'awaiting a person', :at, :at, :at, :at, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("o", forOrder)
                .param("e", UUID.randomUUID())
                .param("reason", reasonCode)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'marking http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", locationId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
