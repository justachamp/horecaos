package uz.horecaos.platform.customers.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0111's lead queue over HTTP, with real grants, a real schema and the real order and
 * reservation tables behind a conversion.
 *
 * <p>Every principal holds the platform's own {@link PlatformRole} bundle through a real {@code
 * iam.grants} row, so the suite fails if a bundle loses {@code customer.lead.*}, if a route's
 * declared scope stops being the one a grant covers, or if the queue starts returning a row outside
 * a reader's reach. What each test would still pass on if the code were broken is named where it
 * matters: the audit assertions count facts rather than checking one exists; the privacy assertions
 * look for the guest's digits and name in the audit trail and the outbox, where a bug would put them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LeadControllerEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018f9f10-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-5000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-5000-7000-8000-0000000000b2");
    private static final UUID CHILONZOR = UUID.fromString("018f9f10-5000-7000-8000-0000000000c1");
    private static final UUID YUNUSOBOD = UUID.fromString("018f9f10-5000-7000-8000-0000000000c2");
    private static final UUID OTHER_BRAND_BRANCH = UUID.fromString("018f9f10-5000-7000-8000-0000000000c3");

    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-5000-7000-8000-0000000000e1");
    private static final UUID OTHER_TENANT_BRAND = UUID.fromString("018f9f10-5000-7000-8000-0000000000e2");
    private static final UUID OTHER_TENANT_BRANCH = UUID.fromString("018f9f10-5000-7000-8000-0000000000e3");

    private static final String OWNER = "leads-owner";
    /** {@code brand-manager}: the call centre's queue at the brand, no PII reveal. */
    private static final String CALL_CENTRE = "leads-call-centre";
    /** {@code location-manager} of Chilonzor: leads handed to her branch, and the number to ring. */
    private static final String CHI_MANAGER = "leads-chilonzor-manager";

    private static final String YUN_MANAGER = "leads-yunusobod-manager";
    /** {@code location-staff}: no lead capability at all. */
    private static final String CHI_COOK = "leads-chilonzor-cook";
    /** {@code support-agent}: reads the queue, never works it. */
    private static final String SUPPORT = "leads-support";

    private static final String OTHER_OWNER = "leads-other-tenant-owner";

    /** A distinctive number and name, so a substring assertion means something. */
    private static final String PHONE = "+998 90 123 45 67";

    private static final String PHONE_DIGITS = "998901234567";
    private static final String NAME = "Aziza Karimova";
    private static final String NOTES = "Wants a buffet for forty on the fifteenth";

    private static final AtomicInteger KEYS = new AtomicInteger();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the lead endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // A lead's phone, name and notes are envelope-encrypted (ADR 0029); the default Spring
        // context carries no key-encryption key outside the "local" profile.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private JdbcAuthorizationService authorization;

    private OrderBoardFixtures fixtures;

    @BeforeEach
    void reset() {
        fixtures = new OrderBoardFixtures(jdbc);
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        fixtures.clean();
        roleRegistry.synchronize();

        fixtures.tenant(TENANT, "leads-endpoint", BRAND, CHILONZOR);
        fixtures.location(TENANT, BRAND, YUNUSOBOD, "YUN", "Yunusobod");
        fixtures.brand(TENANT, "leads-endpoint-second", OTHER_BRAND, OTHER_BRAND_BRANCH);
        fixtures.tenant(OTHER_TENANT, "leads-other-tenant", OTHER_TENANT_BRAND, OTHER_TENANT_BRANCH);

        grant(TENANT, OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(TENANT, CALL_CENTRE, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(TENANT, CHI_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", CHILONZOR);
        grant(TENANT, YUN_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", YUNUSOBOD);
        grant(TENANT, CHI_COOK, PlatformRole.LOCATION_STAFF, "LOCATION", CHILONZOR);
        grant(TENANT, SUPPORT, PlatformRole.SUPPORT_AGENT, "TENANT", TENANT);
        grant(OTHER_TENANT, OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT);
        List.of(OWNER, CALL_CENTRE, CHI_MANAGER, YUN_MANAGER, CHI_COOK, SUPPORT)
                .forEach(subject -> authorization.evictGrants(subject, TENANT));
        authorization.evictGrants(OTHER_OWNER, OTHER_TENANT);
    }

    // ===================================================================== registering

    @Test
    @DisplayName("a lead is registered by the call centre and the queue shows a masked number, never the guest")
    void theQueueShowsAMaskedNumberAndNeverTheGuest() throws Exception {
        MvcResult registered = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, NAME, NOTES, null);

        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        String created = registered.getResponse().getContentAsString();
        assertThat(created)
                .contains("\"phoneMasked\":\"+998 ** *** 45 67\"")
                .contains("\"status\":\"NEW\"")
                .contains("\"hasName\":true")
                .contains("\"hasNotes\":true")
                .doesNotContain(PHONE_DIGITS)
                .doesNotContain("Aziza")
                .doesNotContain("buffet");
        assertThat(etag(registered)).isEqualTo("W/\"1\"");

        String list = mvc.perform(get(leads()).with(token(CALL_CENTRE)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(list)
                .contains(idOf(registered).toString())
                .doesNotContain(PHONE_DIGITS)
                .doesNotContain("Aziza")
                .doesNotContain("buffet");

        String stored = jdbc.sql("SELECT phone_encrypted || coalesce(display_name_encrypted, '') "
                        + "|| coalesce(notes_encrypted, '') FROM customer.leads WHERE id = :id")
                .param("id", idOf(registered))
                .query(String.class)
                .single();
        assertThat(stored)
                .as("at rest the number, the name and the notes are ciphertext")
                .doesNotContain(PHONE_DIGITS)
                .doesNotContain("Aziza")
                .doesNotContain("buffet");
    }

    @Test
    @DisplayName("a catering enquiry is a lead in the same queue, owned by the call centre")
    void aCateringEnquiryIsALeadInTheCallCentresQueue() throws Exception {
        MvcResult catering = register(CALL_CENTRE, "B2B_CATERING_ENQUIRY", PHONE, NAME, NOTES, null);
        MvcResult callback = register(CALL_CENTRE, "CALLBACK_REQUEST", "+998 91 555 00 11", null, null, null);

        assertThat(catering.getResponse().getStatus()).isEqualTo(201);

        String all = mvc.perform(get(leads()).with(token(CALL_CENTRE)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(all)
                .contains(idOf(catering).toString())
                .contains(idOf(callback).toString());

        String onlyCatering = mvc.perform(
                        get(leads()).param("source", "B2B_CATERING_ENQUIRY").with(token(CALL_CENTRE)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(onlyCatering)
                .as("the same queue, narrowed by source -- no second pipeline and no second role")
                .contains(idOf(catering).toString())
                .doesNotContain(idOf(callback).toString());

        assertThat(mvc.perform(get(leads()).with(token(SUPPORT)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("support reads the same queue; owning it is the call centre's, not a sales function's")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a campaign scenario's call task is never registered by hand")
    void aScenarioLeadCannotBeRegisteredByHand() throws Exception {
        MvcResult refused = register(CALL_CENTRE, "CAMPAIGN_SCENARIO", PHONE, null, null, null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM customer.leads")).isZero();
    }

    @Test
    @DisplayName("an unusable number is refused and the message never repeats it")
    void anUnusableNumberIsRefusedWithoutEchoingIt() throws Exception {
        MvcResult refused = register(CALL_CENTRE, "CALLBACK_REQUEST", "12ab", null, null, null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("12ab");
    }

    @Test
    @DisplayName("registering needs customer.lead.manage and reading needs customer.lead.read")
    void thePairOfCapabilitiesIsEnforced() throws Exception {
        MvcResult supportRegisters = register(SUPPORT, "CALLBACK_REQUEST", PHONE, null, null, null);
        assertRefused(supportRegisters, Capability.CUSTOMER_LEAD_MANAGE);

        MvcResult cookReads = mvc.perform(get(location(CHILONZOR) + "/leads").with(token(CHI_COOK)))
                .andReturn();
        assertRefused(cookReads, Capability.CUSTOMER_LEAD_READ);

        assertThat(count("SELECT count(*) FROM customer.leads"))
                .as("a refused caller leaves nothing behind")
                .isZero();
    }

    // ================================================================ the status machine

    @Test
    @DisplayName("a lead walks to a conversion against a real order, and every step leaves an audit fact and an event")
    void aLeadWalksToAConversionAgainstARealOrder() throws Exception {
        MvcResult registered = register(CALL_CENTRE, "B2B_CATERING_ENQUIRY", PHONE, NAME, NOTES, null);
        UUID leadId = idOf(registered);
        String version = etag(registered);

        MvcResult contacted = transition(CALL_CENTRE, leadId, version, """
                {"target":"CONTACTED"}
                """);
        assertThat(contacted.getResponse().getStatus()).isEqualTo(200);
        assertThat(contacted.getResponse().getContentAsString()).contains("\"status\":\"CONTACTED\"");

        String due = Instant.now().plus(Duration.ofHours(3)).toString();
        MvcResult scheduled = transition(CALL_CENTRE, leadId, etag(contacted), """
                {"target":"CALLBACK_SCHEDULED","callbackDueAt":"%s"}
                """.formatted(due));
        assertThat(scheduled.getResponse().getStatus()).isEqualTo(200);

        UUID orderId =
                fixtures.insertOrder(OrderBoardFixtures.order("lead-conversion").at(TENANT, BRAND, CHILONZOR));
        MvcResult converted = transition(CALL_CENTRE, leadId, etag(scheduled), """
                {"target":"CONVERTED","convertedOrderId":"%s"}
                """.formatted(orderId));
        assertThat(converted.getResponse().getStatus()).isEqualTo(200);
        assertThat(converted.getResponse().getContentAsString())
                .contains("\"status\":\"CONVERTED\"")
                .contains("\"convertedOrderId\":\"" + orderId + "\"");

        assertThat(auditActions())
                .as("one registration and one fact per transition, by name and in order")
                .containsExactly(
                        "customer.lead.registered",
                        "customer.lead.status_changed",
                        "customer.lead.status_changed",
                        "customer.lead.status_changed");
        assertThat(outboxEvents())
                .containsExactly(
                        "LeadRegistered",
                        "LeadStatusChanged",
                        "LeadStatusChanged",
                        "LeadStatusChanged",
                        "LeadConverted");
    }

    @Test
    @DisplayName("neither the audit trail nor the outbox ever holds the guest's number, name or notes")
    void noPersonalDataReachesTheAuditTrailOrTheOutbox() throws Exception {
        MvcResult registered = register(CALL_CENTRE, "B2B_CATERING_ENQUIRY", PHONE, NAME, NOTES, CHILONZOR);
        UUID leadId = idOf(registered);
        transition(CALL_CENTRE, leadId, etag(registered), """
                {"target":"DECLINED","closedReason":"OUT_OF_CAPACITY","reason":"Branch fully booked that day"}
                """);

        String audit = jdbc.sql("SELECT string_agg(coalesce(change_document::text, '') || coalesce(reason, ''), ' ') "
                        + "FROM audit.audit_events")
                .query(String.class)
                .single();
        String outbox = jdbc.sql("SELECT string_agg(payload::text, ' ') FROM integration.outbox_events")
                .query(String.class)
                .single();

        assertThat(audit + outbox)
                .doesNotContain(PHONE_DIGITS)
                .doesNotContain("901234567")
                .doesNotContain("Aziza")
                .doesNotContain("Karimova")
                .doesNotContain("buffet");
        assertThat(outbox)
                .as("and the events do carry what a subscriber needs: the lead, its status, its branch")
                .contains(leadId.toString())
                .contains("DECLINED")
                .contains(CHILONZOR.toString());
    }

    @Test
    @DisplayName("a conversion needs exactly one real order or reservation of the lead's own brand")
    void aConversionNeedsARealTargetOfTheLeadsOwnBrand() throws Exception {
        MvcResult registered = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);
        UUID leadId = idOf(registered);
        String version = etag(registered);
        UUID orderOfAnotherBrand = fixtures.insertOrder(
                OrderBoardFixtures.order("other-brand").at(TENANT, OTHER_BRAND, OTHER_BRAND_BRANCH));
        UUID orderOfThisBrand =
                fixtures.insertOrder(OrderBoardFixtures.order("this-brand").at(TENANT, BRAND, CHILONZOR));

        assertThat(transition(CALL_CENTRE, leadId, version, "{\"target\":\"CONVERTED\"}")
                        .getResponse()
                        .getStatus())
                .as("converted into nothing")
                .isEqualTo(400);
        assertThat(transition(CALL_CENTRE, leadId, version, """
                        {"target":"CONVERTED","convertedOrderId":"%s","convertedReservationId":"%s"}
                        """.formatted(orderOfThisBrand, UUID.randomUUID()))
                        .getResponse()
                        .getStatus())
                .as("converted into two things at once")
                .isEqualTo(400);
        assertThat(transition(CALL_CENTRE, leadId, version, """
                        {"target":"CONVERTED","convertedOrderId":"%s"}
                        """.formatted(UUID.randomUUID()))
                        .getResponse()
                        .getStatus())
                .as("an order that does not exist")
                .isEqualTo(400);
        assertThat(transition(CALL_CENTRE, leadId, version, """
                        {"target":"CONVERTED","convertedOrderId":"%s"}
                        """.formatted(orderOfAnotherBrand))
                        .getResponse()
                        .getStatus())
                .as("an order of another brand of the same tenant")
                .isEqualTo(400);

        assertThat(statusOfLead(leadId)).as("none of the four moved it").isEqualTo("NEW");
        assertThat(count("SELECT count(*) FROM integration.outbox_events WHERE event_type = 'LeadConverted'"))
                .isZero();

        assertThat(transition(CALL_CENTRE, leadId, version, """
                        {"target":"CONVERTED","convertedOrderId":"%s"}
                        """.formatted(orderOfThisBrand))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a reservation is a conversion target too")
    void aReservationIsAConversionTarget() throws Exception {
        MvcResult registered = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);
        UUID reservationId = seedReservation(BRAND, CHILONZOR);

        MvcResult converted = transition(CALL_CENTRE, idOf(registered), etag(registered), """
                {"target":"CONVERTED","convertedReservationId":"%s"}
                """.formatted(reservationId));

        assertThat(converted.getResponse().getStatus()).isEqualTo(200);
        assertThat(converted.getResponse().getContentAsString())
                .contains("\"convertedReservationId\":\"" + reservationId + "\"")
                .contains("\"convertedOrderId\":null");
    }

    @Test
    @DisplayName("the machine refuses an edge it does not have, and a decline without a reason")
    void theMachineRefusesWhatItDoesNotHave() throws Exception {
        MvcResult registered = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);
        UUID leadId = idOf(registered);

        MvcResult noReason = transition(CALL_CENTRE, leadId, etag(registered), """
                {"target":"DECLINED"}
                """);
        assertThat(noReason.getResponse().getStatus())
                .as("a decline needs a coded reason")
                .isEqualTo(400);

        MvcResult pastCallback = transition(CALL_CENTRE, leadId, etag(registered), """
                {"target":"CALLBACK_SCHEDULED","callbackDueAt":"%s"}
                """.formatted(
                        Instant.now().minus(Duration.ofDays(1))));
        assertThat(pastCallback.getResponse().getStatus())
                .as("a callback for yesterday is not a callback")
                .isEqualTo(400);

        MvcResult declined = transition(CALL_CENTRE, leadId, etag(registered), """
                {"target":"DECLINED","closedReason":"NOT_INTERESTED"}
                """);
        assertThat(declined.getResponse().getStatus()).isEqualTo(200);

        MvcResult revived = transition(CALL_CENTRE, leadId, etag(declined), """
                {"target":"CONTACTED"}
                """);
        assertThat(revived.getResponse().getStatus())
                .as("a declined lead is finished; the next contact with that guest is a new lead")
                .isEqualTo(422);
        assertThat(revived.getResponse().getContentAsString()).contains("UNPROCESSABLE_STATE");

        MvcResult backToNew =
                transition(CALL_CENTRE, register(CALL_CENTRE, "SITE", PHONE, null, null, null), "{\"target\":\"NEW\"}");
        assertThat(backToNew.getResponse().getStatus())
                .as("nothing moves into NEW")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("a second tab that read an older version loses loudly, and no version at all is refused")
    void aStaleOrMissingVersionIsRefused() throws Exception {
        MvcResult registered = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);
        UUID leadId = idOf(registered);
        String first = etag(registered);

        assertThat(transition(CALL_CENTRE, leadId, first, "{\"target\":\"CONTACTED\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult stale = transition(
                CALL_CENTRE, leadId, first, """
                {"target":"CALLBACK_SCHEDULED","callbackDueAt":"%s"}
                """.formatted(Instant.now().plus(Duration.ofHours(1))));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");

        MvcResult missing = mvc.perform(post(leadPath(leadId) + "/transitions")
                        .with(token(CALL_CENTRE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"CONTACTED\"}"))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
    }

    // ============================================================== branches and reach

    @Test
    @DisplayName("a brand hands a lead to a branch and the branch sees its own leads only")
    void aBranchSeesOnlyTheLeadsHandedToIt() throws Exception {
        MvcResult handed = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, NAME, null, null);
        MvcResult other = register(CALL_CENTRE, "CALLBACK_REQUEST", "+998 91 555 00 11", null, null, null);
        MvcResult unassigned = register(CALL_CENTRE, "SITE", "+998 93 777 66 55", null, null, null);

        MvcResult assigned = mvc.perform(post(leadPath(idOf(handed)) + "/assignment")
                        .with(token(CALL_CENTRE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(handed))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"%s\"}".formatted(CHILONZOR)))
                .andReturn();
        assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
        assertThat(assigned.getResponse().getContentAsString())
                .contains("\"assignedLocationId\":\"" + CHILONZOR + "\"");
        mvc.perform(post(leadPath(idOf(other)) + "/assignment")
                .with(token(CALL_CENTRE))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                .header("If-Match", etag(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locationId\":\"%s\"}".formatted(YUNUSOBOD)));

        String chilonzor = mvc.perform(get(location(CHILONZOR) + "/leads").with(token(CHI_MANAGER)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(chilonzor)
                .as("Chilonzor's callbacks, and nobody else's")
                .contains(idOf(handed).toString())
                .doesNotContain(idOf(other).toString())
                .doesNotContain(idOf(unassigned).toString());

        assertThat(mvc.perform(get(location(CHILONZOR) + "/leads/" + idOf(other))
                                .with(token(CHI_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("another branch's lead, and an unassigned one, are 'no such lead' to this branch")
                .isEqualTo(404);
        assertThat(mvc.perform(get(location(CHILONZOR) + "/leads/" + idOf(unassigned))
                                .with(token(CHI_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get(location(CHILONZOR) + "/leads").with(token(YUN_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("Yunusobod's manager has no grant at Chilonzor")
                .isEqualTo(403);
        assertThat(outboxEvents()).contains("LeadAssignedToLocation");
    }

    @Test
    @DisplayName("a lead can only be handed to a branch of its own brand")
    void aHandOffNamesABranchOfTheLeadsBrand() throws Exception {
        MvcResult lead = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);

        MvcResult refused = mvc.perform(post(leadPath(idOf(lead)) + "/assignment")
                        .with(token(CALL_CENTRE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(lead))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"%s\"}".formatted(OTHER_BRAND_BRANCH)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(statusOfLead(idOf(lead))).as("unassigned still").isEqualTo("NEW");
        assertThat(jdbc.sql("SELECT assigned_location_id FROM customer.leads WHERE id = :id")
                        .param("id", idOf(lead))
                        .query(UUID.class)
                        .optional())
                .isEmpty();
    }

    @Test
    @DisplayName("a branch works the lead it was handed, declines what it cannot serve, and cannot hand it on")
    void aBranchWorksItsLeadButCannotHandItOn() throws Exception {
        MvcResult lead = registerAssigned(CHILONZOR);
        UUID leadId = idOf(lead);

        MvcResult contacted = mvc.perform(post(location(CHILONZOR) + "/leads/" + leadId + "/transitions")
                        .with(token(CHI_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(lead))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"CONTACTED\"}"))
                .andReturn();
        assertThat(contacted.getResponse().getStatus()).isEqualTo(200);

        MvcResult declined = mvc.perform(post(location(CHILONZOR) + "/leads/" + leadId + "/transitions")
                        .with(token(CHI_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(contacted))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"DECLINED\",\"closedReason\":\"OUT_OF_CAPACITY\"}"))
                .andReturn();
        assertThat(declined.getResponse().getStatus())
                .as("the branch declines with a reason; it does not pass the lead to another branch")
                .isEqualTo(200);
        assertThat(declined.getResponse().getContentAsString()).contains("\"closedReason\":\"OUT_OF_CAPACITY\"");

        MvcResult assignThroughTheBrand = mvc.perform(post(leadPath(leadId) + "/assignment")
                        .with(token(CHI_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(declined))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"%s\"}".formatted(YUNUSOBOD)))
                .andReturn();
        assertRefused(assignThroughTheBrand, Capability.CUSTOMER_LEAD_MANAGE);

        assertThat(mvc.perform(post(location(CHILONZOR) + "/leads/" + leadId + "/assignment")
                                .with(token(CHI_MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"locationId\":\"%s\"}".formatted(YUNUSOBOD)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the branch routes have no hand-off at all")
                .isIn(404, 405);
    }

    @Test
    @DisplayName(
            "the attention view holds new leads and callbacks that are due, and the reminder window is the tenant's")
    void theAttentionViewHoldsWhatSomebodyOwesACall() throws Exception {
        MvcResult fresh = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);
        MvcResult soon = register(CALL_CENTRE, "CALLBACK_REQUEST", "+998 91 555 00 11", null, null, null);
        MvcResult later = register(CALL_CENTRE, "CALLBACK_REQUEST", "+998 93 777 66 55", null, null, null);
        scheduleCallback(soon, Duration.ofMinutes(30));
        scheduleCallback(later, Duration.ofHours(30));

        String attention = mvc.perform(get(leads()).param("view", "attention").with(token(CALL_CENTRE)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(attention)
                .as("new, and a callback inside the sixty-minute window -- not one a day away")
                .contains(idOf(fresh).toString())
                .contains(idOf(soon).toString())
                .doesNotContain(idOf(later).toString());
        assertThat(mvc.perform(get(leads()).param("view", "everything").with(token(CALL_CENTRE)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("another tenant sees nothing, and a lead id from this tenant is not a lead in theirs")
    void aLeadNeverCrossesATenant() throws Exception {
        MvcResult lead = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, NAME, null, null);

        MvcResult theirOwnPathWithOurLead = mvc.perform(get("/api/v1/tenants/" + OTHER_TENANT + "/brands/"
                                + OTHER_TENANT_BRAND + "/leads/" + idOf(lead))
                        .with(token(OTHER_OWNER)))
                .andReturn();
        assertThat(theirOwnPathWithOurLead.getResponse().getStatus()).isEqualTo(404);

        MvcResult ourPathWithTheirGrant =
                mvc.perform(get(leads()).with(token(OTHER_OWNER))).andReturn();
        assertRefused(ourPathWithTheirGrant, Capability.CUSTOMER_LEAD_READ);

        MvcResult theirTransition = mvc.perform(post("/api/v1/tenants/" + OTHER_TENANT + "/brands/" + OTHER_TENANT_BRAND
                                + "/leads/" + idOf(lead) + "/transitions")
                        .with(token(OTHER_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", etag(lead))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"LOST\",\"closedReason\":\"SPAM_OR_WRONG_NUMBER\"}"))
                .andReturn();
        assertThat(theirTransition.getResponse().getStatus()).isEqualTo(404);
        assertThat(statusOfLead(idOf(lead))).isEqualTo("NEW");
    }

    @Test
    @DisplayName(
            "a lead of another brand is not in this brand's queue, and a brand's route cannot name another brand's lead")
    void aLeadNeverCrossesABrand() throws Exception {
        MvcResult lead = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);
        grant(TENANT, "leads-other-brand-manager", PlatformRole.BRAND_MANAGER, "BRAND", OTHER_BRAND);
        authorization.evictGrants("leads-other-brand-manager", TENANT);

        String otherBrandsQueue = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/leads")
                        .with(token("leads-other-brand-manager")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(otherBrandsQueue).doesNotContain(idOf(lead).toString());

        assertThat(mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/leads/" + idOf(lead))
                                .with(token("leads-other-brand-manager")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertRefused(
                mvc.perform(get(leads()).with(token("leads-other-brand-manager")))
                        .andReturn(),
                Capability.CUSTOMER_LEAD_READ);
    }

    // ====================================================================== the reveal

    @Test
    @DisplayName("the number is one purpose-stamped, audited read that needs customer.pii.reveal as well")
    void theNumberIsOneAuditedReveal() throws Exception {
        MvcResult lead = registerAssigned(CHILONZOR);
        UUID leadId = idOf(lead);

        MvcResult noPurpose = mvc.perform(get(location(CHILONZOR) + "/leads/" + leadId + "/contact")
                        .with(token(CHI_MANAGER)))
                .andReturn();
        assertThat(noPurpose.getResponse().getStatus()).isEqualTo(400);

        MvcResult revealed = mvc.perform(get(location(CHILONZOR) + "/leads/" + leadId + "/contact")
                        .param("purpose", "Calling the guest back about the catering enquiry")
                        .with(token(CHI_MANAGER)))
                .andReturn();
        assertThat(revealed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(revealed.getResponse().getContentAsString());
        assertThat(body.path("phone").asText()).isEqualTo("+998901234567");
        assertThat(body.path("displayName").asText()).isEqualTo(NAME);
        assertThat(body.path("notes").asText()).isEqualTo(NOTES);

        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'customer.lead.revealed'"))
                .as("one fact for the call, not one per field")
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT reason FROM audit.audit_events WHERE action_code = 'customer.lead.revealed'")
                        .query(String.class)
                        .single())
                .isEqualTo("Calling the guest back about the catering enquiry");

        MvcResult refused = mvc.perform(get(leadPath(leadId) + "/contact")
                        .param("purpose", "Looking")
                        .with(token(CALL_CENTRE)))
                .andReturn();
        assertRefused(refused, Capability.CUSTOMER_PII_REVEAL);
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'customer.lead.revealed'"))
                .as("a refused reveal writes no reveal")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a number that is already an account's is a hint on the lead, never a link")
    void aSharedNumberIsAHintAndNeverALink() throws Exception {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, "
                        + "identity_policy_version, version) VALUES (:id, :t, 'ACTIVE', 'Regular', 1, 1)")
                .param("id", accountId)
                .param("t", TENANT)
                .update();
        mvc.perform(post("/api/v1/tenants/" + TENANT + "/customers/" + accountId + "/contact-points")
                        .with(token(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PHONE\",\"value\":\"+998901234567\",\"primary\":true}"))
                .andReturn();
        MvcResult first = register(CALL_CENTRE, "CALLBACK_REQUEST", "+998 (90) 123-45-67", null, null, null);
        MvcResult second = register(CALL_CENTRE, "SITE", "998901234567", null, null, null);

        MvcResult detail = mvc.perform(get(leadPath(idOf(second))).with(token(CALL_CENTRE)))
                .andReturn();

        JsonNode body = JSON.readTree(detail.getResponse().getContentAsString());
        assertThat(body.path("possibleAccountIds").toString())
                .as("every spelling of one number is one number")
                .contains(accountId.toString());
        assertThat(body.path("otherOpenLeadIds").toString())
                .contains(idOf(first).toString());
        assertThat(body.path("customerAccountId").isNull())
                .as("a phone match is a hint an operator confirms, never an automatic link (ADR 0015)")
                .isTrue();
    }

    // ============================================================== the contact journal

    @Test
    @DisplayName("a call is recorded against a lead, appears in its journal, and there is no way to change it")
    void aCallIsRecordedAndNeverChanged() throws Exception {
        MvcResult lead = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);
        UUID leadId = idOf(lead);

        MvcResult recorded = mvc.perform(post(leadPath(leadId) + "/contact-attempts")
                        .with(token(CALL_CENTRE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction":"OUTBOUND","outcome":"NO_ANSWER","nextAction":"CALL_AGAIN",
                                 "nextActionAt":"%s"}
                                """.formatted(Instant.now().plus(Duration.ofHours(2)))))
                .andReturn();
        assertThat(recorded.getResponse().getStatus()).isEqualTo(201);
        UUID attemptRow = UUID.fromString(JSON.readTree(recorded.getResponse().getContentAsString())
                .path("id")
                .asText());

        String journal = mvc.perform(get(leadPath(leadId) + "/contact-attempts").with(token(CALL_CENTRE)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(journal)
                .contains(attemptRow.toString())
                .contains("\"outcome\":\"NO_ANSWER\"")
                .contains("\"operatorActorId\":\"" + CALL_CENTRE + "\"")
                .doesNotContain(PHONE_DIGITS);

        assertThat(mvc.perform(put(leadPath(leadId) + "/contact-attempts/" + attemptRow)
                                .with(token(CALL_CENTRE))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"outcome\":\"CONNECTED\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("there is no route that rewrites a call")
                .isIn(404, 405);
        assertThat(mvc.perform(delete(leadPath(leadId) + "/contact-attempts/" + attemptRow)
                                .with(token(CALL_CENTRE))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isIn(404, 405);
        assertThat(
                        count(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'customer.contact_attempt.recorded'"))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a refused call says why, and the journal does not record a refusal the blacklist does not bear out")
    void aRefusedCallNamesWhyAndTheJournalDoesNotLie() throws Exception {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, "
                        + "identity_policy_version, version) VALUES (:id, :t, 'ACTIVE', 'Banned', 1, 1)")
                .param("id", accountId)
                .param("t", TENANT)
                .update();
        MvcResult linked = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null, accountId);
        UUID leadId = idOf(linked);

        assertThat(attempt(CALL_CENTRE, leadId, "{\"direction\":\"OUTBOUND\",\"outcome\":\"BLOCKED\"}")
                        .getResponse()
                        .getStatus())
                .as("a refusal without a reason")
                .isEqualTo(400);
        assertThat(attempt(CALL_CENTRE, leadId, """
                        {"direction":"OUTBOUND","outcome":"NO_ANSWER","blockingReason":"WRONG_NUMBER"}
                        """).getResponse().getStatus())
                .as("a reason without a refusal")
                .isEqualTo(400);
        assertThat(attempt(CALL_CENTRE, leadId, """
                        {"direction":"OUTBOUND","outcome":"BLOCKED","blockingReason":"BLACKLISTED"}
                        """).getResponse().getStatus())
                .as("blocked for a blacklist the guest is not on")
                .isEqualTo(400);

        mvc.perform(post("/api/v1/tenants/" + TENANT + "/customers/" + accountId + "/blacklist-entries")
                .with(token(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Abusive on the phone\"}"));

        MvcResult blocked = attempt(CALL_CENTRE, leadId, """
                {"direction":"OUTBOUND","outcome":"BLOCKED","blockingReason":"BLACKLISTED"}
                """);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(201);
        assertThat(blocked.getResponse().getContentAsString())
                .contains("\"outcome\":\"BLOCKED\"")
                .contains("\"blockingReason\":\"BLACKLISTED\"");
    }

    @Test
    @DisplayName(
            "a retried submit under one attempt id is one attempt, and the id cannot be borrowed by another contact")
    void aRetriedSubmitIsOneAttempt() throws Exception {
        UUID attemptId = UUID.randomUUID();
        MvcResult one = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, null, null, null);
        MvcResult two = register(CALL_CENTRE, "CALLBACK_REQUEST", "+998 91 555 00 11", null, null, null);
        String body = """
                {"attemptId":"%s","direction":"INBOUND","outcome":"CONNECTED"}
                """.formatted(attemptId);

        MvcResult first = attempt(CALL_CENTRE, idOf(one), body);
        MvcResult retried = attempt(CALL_CENTRE, idOf(one), body);
        MvcResult borrowed = attempt(CALL_CENTRE, idOf(two), body);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        assertThat(retried.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("SELECT count(*) FROM customer.contact_attempts")).isEqualTo(1L);
        assertThat(borrowed.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("a call cannot have happened in the future, and a branch records calls only on its own leads")
    void callsAreRecordedInTheirOwnTimeAndReach() throws Exception {
        MvcResult handed = registerAssigned(CHILONZOR);
        MvcResult notHanded = register(CALL_CENTRE, "CALLBACK_REQUEST", "+998 91 555 00 11", null, null, null);

        assertThat(attempt(CALL_CENTRE, idOf(handed), """
                        {"direction":"OUTBOUND","outcome":"CONNECTED","occurredAt":"%s"}
                        """.formatted(
                                        Instant.now().plus(Duration.ofDays(2))))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        assertThat(mvc.perform(post(location(CHILONZOR) + "/leads/" + idOf(handed) + "/contact-attempts")
                                .with(token(CHI_MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"direction\":\"OUTBOUND\",\"outcome\":\"CONNECTED\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
        assertThat(mvc.perform(post(location(CHILONZOR) + "/leads/" + idOf(notHanded) + "/contact-attempts")
                                .with(token(CHI_MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"direction\":\"OUTBOUND\",\"outcome\":\"CONNECTED\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a lead nobody handed to this branch is not hers to ring")
                .isEqualTo(404);
    }

    // =============================================================== the customer card

    @Test
    @DisplayName("opening the card writes exactly one audit fact each time, and a plain profile read writes none")
    void everyCardOpenWritesExactlyOneAuditFact() throws Exception {
        UUID accountId = account("Regular Guest");

        for (int opens = 1; opens <= 3; opens++) {
            MvcResult card = mvc.perform(get(card(accountId))
                            .param("purpose", "Operations console: open customer card")
                            .with(token(OWNER)))
                    .andReturn();
            assertThat(card.getResponse().getStatus()).isEqualTo(200);
            assertThat(cardFacts(accountId)).as("after %d opens", opens).isEqualTo(opens);
        }

        mvc.perform(get("/api/v1/tenants/" + TENANT + "/customers/" + accountId).with(token(OWNER)))
                .andReturn();
        assertThat(cardFacts(accountId))
                .as("the plain profile read is the header a screen renders a name from, not the card")
                .isEqualTo(3);

        assertThat(jdbc.sql("SELECT actor_subject || '|' || scope_type || '|' || audit_class || '|' || reason "
                                + "FROM audit.audit_events WHERE action_code = 'customer.card.viewed' LIMIT 1")
                        .query(String.class)
                        .single())
                .isEqualTo(OWNER + "|TENANT|BUSINESS|Operations console: open customer card");
        assertThat(jdbc.sql("SELECT coalesce(change_document::text, '{}') FROM audit.audit_events "
                                + "WHERE action_code = 'customer.card.viewed' LIMIT 1")
                        .query(String.class)
                        .single())
                .as("an id and a purpose, and no personal data")
                .isEqualTo("{}");
    }

    @Test
    @DisplayName("a card that was not opened leaves no fact: unknown account, another tenant's account, no capability")
    void aRefusedOrEmptyOpenLeavesNoFact() throws Exception {
        UUID accountId = account("Regular Guest");

        assertThat(mvc.perform(get(card(UUID.randomUUID())).with(token(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/tenants/" + OTHER_TENANT + "/customers/" + accountId + "/card")
                                .with(token(OTHER_OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("this tenant's account id is not an account in theirs")
                .isEqualTo(404);
        assertRefused(mvc.perform(get(card(accountId)).with(token(CHI_COOK))).andReturn(), Capability.CUSTOMER_READ);

        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'customer.card.viewed'"))
                .isZero();
    }

    @Test
    @DisplayName("the card merges what each module holds, newest first, and carries no personal data of its own")
    void theCardMergesEveryModulesHistoryByTime() throws Exception {
        UUID accountId = account("Regular Guest");
        Instant base = Instant.now().minus(Duration.ofHours(10));
        UUID orderId =
                fixtures.insertOrder(OrderBoardFixtures.order("card-order").at(TENANT, BRAND, CHILONZOR));
        seedNotification(accountId, orderId, "SMS", "DELIVERED", "order.confirmation", base.plusSeconds(60));
        seedNotification(accountId, orderId, "PUSH", "SUPPRESSED", "order.ready", base.plusSeconds(120));
        seedReview(accountId, orderId, 4, base.plusSeconds(180));
        MvcResult lead = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, NAME, NOTES, null, accountId);
        attempt(CALL_CENTRE, idOf(lead), """
                {"direction":"OUTBOUND","outcome":"CONNECTED","occurredAt":"%s"}
                """.formatted(base.plusSeconds(240)));

        MvcResult opened = mvc.perform(get(card(accountId)).with(token(OWNER))).andReturn();

        assertThat(opened.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(opened.getResponse().getContentAsString());
        List<String> kinds = new java.util.ArrayList<>();
        body.path("history").forEach(entry -> kinds.add(entry.path("kind").asText()));
        assertThat(kinds)
                .as("one entry from each of notifications (twice), reviews and the voice journal, newest first")
                .containsExactly("VOICE_CONTACT", "REVIEW", "NOTIFICATION", "NOTIFICATION");
        assertThat(body.path("leads").size()).isEqualTo(1);
        assertThat(body.path("blacklisted").asBoolean()).isFalse();
        assertThat(body.path("history").get(2).path("statusCode").asText())
                .as("the refused push says so, and says why")
                .isEqualTo("SUPPRESSED");
        assertThat(opened.getResponse().getContentAsString())
                .doesNotContain(PHONE_DIGITS)
                .doesNotContain("Aziza")
                .doesNotContain("buffet");
    }

    @Test
    @DisplayName("the card pages backwards with the nextBefore it hands out")
    void theCardPagesBackwards() throws Exception {
        UUID accountId = account("Regular Guest");
        Instant base = Instant.now().minus(Duration.ofHours(5));
        UUID orderId =
                fixtures.insertOrder(OrderBoardFixtures.order("paging-order").at(TENANT, BRAND, CHILONZOR));
        for (int i = 0; i < 5; i++) {
            seedNotification(accountId, orderId, "SMS", "DELIVERED", "order.status." + i, base.plusSeconds(i * 60L));
        }

        JsonNode firstPage = JSON.readTree(
                mvc.perform(get(card(accountId)).param("limit", "2").with(token(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(firstPage.path("history").size()).isEqualTo(2);
        String nextBefore = firstPage.path("nextBefore").asText();
        assertThat(nextBefore).isNotBlank();

        JsonNode secondPage = JSON.readTree(mvc.perform(get(card(accountId))
                        .param("limit", "2")
                        .param("before", nextBefore)
                        .with(token(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(secondPage.path("history").size()).isEqualTo(2);
        assertThat(secondPage.path("history").get(0).path("detailCode").asText())
                .as("older than the first page's last entry, and not the same one")
                .isEqualTo("order.status.2");
        assertThat(cardFacts(accountId)).as("each page is its own open").isEqualTo(2);
    }

    @Test
    @DisplayName("a call about an account holder is recorded and read from the card, tenant-wide")
    void aCallAboutAnAccountHolderIsPartOfTheirCard() throws Exception {
        UUID accountId = account("Regular Guest");

        MvcResult recorded = mvc.perform(
                        post("/api/v1/tenants/" + TENANT + "/customers/" + accountId + "/contact-attempts")
                                .with(token(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {"brandId":"%s","direction":"INBOUND","outcome":"CONNECTED"}
                                """.formatted(BRAND)))
                .andReturn();
        assertThat(recorded.getResponse().getStatus()).isEqualTo(201);

        String listed = mvc.perform(get("/api/v1/tenants/" + TENANT + "/customers/" + accountId + "/contact-attempts")
                        .with(token(SUPPORT)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listed).contains("\"direction\":\"INBOUND\"").contains(accountId.toString());

        MvcResult otherTenantsBrand = mvc.perform(
                        post("/api/v1/tenants/" + TENANT + "/customers/" + accountId + "/contact-attempts")
                                .with(token(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {"brandId":"%s","direction":"INBOUND","outcome":"CONNECTED"}
                                """.formatted(OTHER_TENANT_BRAND)))
                .andReturn();
        assertThat(otherTenantsBrand.getResponse().getStatus())
                .as("a brand of another tenant is no brand of this one")
                .isEqualTo(400);
    }

    // =============================================================================== erasure

    @Test
    @DisplayName("erasing a customer overwrites the number, the name and the notes of every lead linked to them")
    void erasingACustomerOverwritesTheirLeads(
            @Autowired uz.horecaos.platform.customers.application.CustomerErasureService erasure) throws Exception {
        UUID accountId = account("Erasable Guest");
        MvcResult linked = register(CALL_CENTRE, "CALLBACK_REQUEST", PHONE, NAME, NOTES, null, accountId);
        MvcResult unlinked = register(CALL_CENTRE, "SITE", "+998 91 555 00 11", "Someone Else", null, null);
        String hashBefore = hashOf(idOf(linked));

        var request = erasure.request(
                TENANT,
                accountId,
                uz.horecaos.platform.customers.application.CustomerErasureService.RequestedVia.OPERATIONS,
                uz.horecaos.platform.audit.api.ActorRef.user(OWNER, null));
        erasure.execute(TENANT, accountId, request.id(), uz.horecaos.platform.audit.api.ActorRef.user(OWNER, null));

        assertThat(jdbc.sql("SELECT phone_masked || '|' || coalesce(display_name_encrypted, 'none') || '|' "
                                + "|| coalesce(notes_encrypted, 'none') FROM customer.leads WHERE id = :id")
                        .param("id", idOf(linked))
                        .query(String.class)
                        .single())
                .as("the masked number is gone, the name and the notes are dropped")
                .isEqualTo("[erased]|none|none");
        assertThat(hashOf(idOf(linked))).isNotEqualTo(hashBefore);
        assertThat(jdbc.sql("SELECT phone_masked FROM customer.leads WHERE id = :id")
                        .param("id", idOf(unlinked))
                        .query(String.class)
                        .single())
                .as("a lead nobody linked to the account is not this erasure's to touch")
                .isEqualTo("+998 ** *** 00 11");
        assertThat(statusOfLead(idOf(linked)))
                .as("the lead's own facts stay: they say nothing about who the guest was")
                .isEqualTo("NEW");
    }

    @Test
    @DisplayName("erasing a customer also reaches a phoned-in lead nobody linked, by the number she held")
    void erasingACustomerReachesAnUnlinkedLeadHoldingHerNumber(
            @Autowired uz.horecaos.platform.customers.application.CustomerErasureService erasure) throws Exception {
        UUID accountId = account("Erasable Guest");
        addPhone(accountId, "+998901234567");
        UUID otherAccount = account("Other Guest");
        MvcResult phonedIn = register(CALL_CENTRE, "CALLBACK_REQUEST", "998 (90) 123-45-67", NAME, NOTES, null);
        MvcResult someoneElse = register(CALL_CENTRE, "SITE", "+998 91 555 00 11", "Someone Else", null, null);
        MvcResult linkedElsewhere = register(CALL_CENTRE, "SITE", PHONE, "Household Member", null, null, otherAccount);
        String hashBefore = hashOf(idOf(phonedIn));

        var request = erasure.request(
                TENANT,
                accountId,
                uz.horecaos.platform.customers.application.CustomerErasureService.RequestedVia.OPERATIONS,
                uz.horecaos.platform.audit.api.ActorRef.user(OWNER, null));
        erasure.execute(TENANT, accountId, request.id(), uz.horecaos.platform.audit.api.ActorRef.user(OWNER, null));

        assertThat(jdbc.sql("SELECT phone_masked || '|' || coalesce(display_name_encrypted, 'none') || '|' "
                                + "|| coalesce(notes_encrypted, 'none') FROM customer.leads WHERE id = :id")
                        .param("id", idOf(phonedIn))
                        .query(String.class)
                        .single())
                .as("the lead she phoned in as a guest holds her number: it is erased with her")
                .isEqualTo("[erased]|none|none");
        assertThat(hashOf(idOf(phonedIn)))
                .as("and its lookup hash no longer equals the hash of her former number")
                .isNotEqualTo(hashBefore);
        assertThat(jdbc.sql("SELECT phone_masked FROM customer.leads WHERE id = :id")
                        .param("id", idOf(someoneElse))
                        .query(String.class)
                        .single())
                .as("a lead on a different number is not hers")
                .isEqualTo("+998 ** *** 00 11");
        assertThat(jdbc.sql("SELECT phone_masked FROM customer.leads WHERE id = :id")
                        .param("id", idOf(linkedElsewhere))
                        .query(String.class)
                        .single())
                .as("a lead an operator linked to another account is that account's, whatever number it holds")
                .isEqualTo("+998 ** *** 45 67");
    }

    // ===================================================================== helpers

    private MvcResult register(
            String subject,
            String source,
            String phone,
            @Nullable String name,
            @Nullable String notes,
            @Nullable UUID assignedLocation)
            throws Exception {
        return register(subject, source, phone, name, notes, assignedLocation, null);
    }

    private MvcResult register(
            String subject,
            String source,
            String phone,
            @Nullable String name,
            @Nullable String notes,
            @Nullable UUID assignedLocation,
            @Nullable UUID accountId)
            throws Exception {
        StringBuilder body = new StringBuilder("{\"source\":\"" + source + "\",\"phone\":\"" + phone + "\"");
        if (name != null) {
            body.append(",\"displayName\":\"").append(name).append('"');
        }
        if (notes != null) {
            body.append(",\"notes\":\"").append(notes).append('"');
        }
        if (assignedLocation != null) {
            body.append(",\"assignedLocationId\":\"").append(assignedLocation).append('"');
        }
        if (accountId != null) {
            body.append(",\"customerAccountId\":\"").append(accountId).append('"');
        }
        body.append('}');
        return mvc.perform(post(leads())
                        .with(token(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andReturn();
    }

    private MvcResult registerAssigned(UUID locationId) throws Exception {
        return register(CALL_CENTRE, "B2B_CATERING_ENQUIRY", PHONE, NAME, NOTES, locationId);
    }

    private MvcResult transition(String subject, UUID leadId, String version, String body) throws Exception {
        return mvc.perform(post(leadPath(leadId) + "/transitions")
                        .with(token(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.strip()))
                .andReturn();
    }

    /** A transition on a lead just registered, for a target the machine refuses outright. */
    private MvcResult transition(String subject, MvcResult registered, String body) throws Exception {
        return transition(subject, idOf(registered), etag(registered), body);
    }

    private void scheduleCallback(MvcResult registered, Duration fromNow) throws Exception {
        MvcResult scheduled = transition(CALL_CENTRE, idOf(registered), etag(registered), """
                {"target":"CALLBACK_SCHEDULED","callbackDueAt":"%s"}
                """.formatted(
                        Instant.now().plus(fromNow)));
        assertThat(scheduled.getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult attempt(String subject, UUID leadId, String body) throws Exception {
        return mvc.perform(post(leadPath(leadId) + "/contact-attempts")
                        .with(token(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.strip()))
                .andReturn();
    }

    private static String etag(MvcResult result) {
        return Objects.requireNonNull(result.getResponse().getHeader("ETag"), "the response carries its version");
    }

    private static UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asText());
    }

    private static String leads() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/leads";
    }

    private static String leadPath(UUID leadId) {
        return leads() + "/" + leadId;
    }

    private static String location(UUID locationId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId;
    }

    private static String card(UUID accountId) {
        return "/api/v1/tenants/" + TENANT + "/customers/" + accountId + "/card";
    }

    private static String key() {
        return "leads-endpoint-" + KEYS.incrementAndGet();
    }

    private String statusOfLead(UUID leadId) {
        return jdbc.sql("SELECT status FROM customer.leads WHERE id = :id")
                .param("id", leadId)
                .query(String.class)
                .single();
    }

    private String hashOf(UUID leadId) {
        return jdbc.sql("SELECT phone_lookup_hash FROM customer.leads WHERE id = :id")
                .param("id", leadId)
                .query(String.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long cardFacts(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'customer.card.viewed' "
                        + "AND target_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    private List<String> auditActions() {
        return jdbc.sql("SELECT action_code FROM audit.audit_events ORDER BY recorded_at, occurred_at, id")
                .query(String.class)
                .list();
    }

    private List<String> outboxEvents() {
        return jdbc.sql("SELECT event_type FROM integration.outbox_events ORDER BY occurred_at, event_id")
                .query(String.class)
                .list();
    }

    private void addPhone(UUID accountId, String value) throws Exception {
        MvcResult added = mvc.perform(post("/api/v1/tenants/" + TENANT + "/customers/" + accountId + "/contact-points")
                        .with(token(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PHONE\",\"value\":\"" + value + "\",\"primary\":true}"))
                .andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(201);
    }

    private UUID account(String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, "
                        + "identity_policy_version, version) VALUES (:id, :t, 'ACTIVE', :name, 1, 1)")
                .param("id", id)
                .param("t", TENANT)
                .param("name", displayName)
                .update();
        return id;
    }

    private void seedNotification(
            UUID accountId, UUID orderId, String channel, String status, String template, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO notifications.notifications (
                    id, tenant_id, brand_id, notification_class, channel, template_key, subject_type, subject_id,
                    recipient_account_id, idempotency_key, status, suppression_reason, created_at)
                VALUES (:id, :t, :brand, 'TRANSACTIONAL_REQUIRED', :channel, :template, 'Order', :orderId,
                        :accountId, :key, :status, :suppression, :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("brand", BRAND)
                .param("channel", channel)
                .param("template", template)
                .param("orderId", orderId)
                .param("accountId", accountId)
                .param("key", UUID.randomUUID().toString())
                .param("status", status)
                .param("suppression", "SUPPRESSED".equals(status) ? "NO_REACHABLE_ENDPOINT" : null)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedReview(UUID accountId, UUID orderId, int rating, Instant submittedAt) {
        jdbc.sql("""
                INSERT INTO reviews.order_reviews (
                    id, tenant_id, brand_id, location_id, order_id, customer_account_id, rating, submitted_at)
                VALUES (:id, :t, :brand, :location, :orderId, :accountId, :rating, :submittedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("brand", BRAND)
                .param("location", CHILONZOR)
                .param("orderId", orderId)
                .param("accountId", accountId)
                .param("rating", rating)
                .param("submittedAt", submittedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private UUID seedReservation(UUID brandId, UUID locationId) {
        UUID reservationId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-20T18:00:00Z");
        jdbc.sql("""
                INSERT INTO dinein.reservations (
                    id, tenant_id, brand_id, location_id, guest_name_encrypted,
                    guest_phone_encrypted, guest_phone_lookup_hash, note_encrypted,
                    party_size, requested_from, requested_to, turnaround_minutes_snapshot,
                    status, source_channel_id, created_by, version)
                VALUES (:id, :tenantId, :brandId, :locationId, 'unused', 'unused', 'unused-hash', 'unused',
                    4, :from, :to, 15, 'REQUESTED', :channelId, 'test-fixture', 1)
                """)
                .param("id", reservationId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("from", from.atOffset(ZoneOffset.UTC))
                .param("to", from.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC))
                .param("channelId", OrderBoardFixtures.channelOf(TENANT))
                .update();
        return reservationId;
    }

    private void grant(UUID tenantId, String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'lead endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void assertRefused(MvcResult result, Capability missing) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(missing.code());
    }

    /** Carries no realm role, so a refusal proves the ADR 0025 grant decided it. */
    private static RequestPostProcessor token(String subject) {
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
