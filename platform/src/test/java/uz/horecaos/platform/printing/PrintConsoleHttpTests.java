package uz.horecaos.platform.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.printing.PrintingWorld.LineSpec;
import uz.horecaos.platform.printing.PrintingWorld.SeededOrder;
import uz.horecaos.platform.printing.application.PrintDocumentCodec;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The operations console's side of printing (ADR 0154, "Console endpoints"): printers, routes, the brand's
 * receipt profile, the queue, a test page, a reprint, the preview and the agents -- over HTTP, with the
 * capability each declares and the refusal each makes, and the ADR 0027 fact each leaves.
 */
class PrintConsoleHttpTests extends PrintingSpringTest {

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcPrintingStore store;

    @Autowired
    @SuppressWarnings("NullAway")
    private PrintDocumentCodec codec;

    @Autowired
    @SuppressWarnings("NullAway")
    private ApplicationEventPublisher events;

    @Autowired
    @SuppressWarnings("NullAway")
    private PlatformTransactionManager transactions;

    private Agent agent;
    private FakePrintAgent fake;

    @BeforeEach
    void anAgentHasAdvertised() throws Exception {
        printingOn();
        agent = enrolAgent();
        fake = new FakePrintAgent(mvc, tokenFor(agent.subject()), PRINT);
        fake.reaches("counter", new uz.horecaos.platform.support.printing.FakePrinter());
        fake.claimed(1);
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder putJson(String path, String subject, long version, String body) {
        return put(path)
                .with(tokenFor(subject))
                .header("If-Match", "W/\"" + version + "\"")
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private JsonNode browserPrinter(String name, int width) throws Exception {
        return created(postJson(
                PRINT + "/printers", MANAGER, "{\"displayName\":\"" + name + "\",\"tapeWidthMm\":" + width + "}"));
    }

    private JsonNode agentPrinter(String name, String alias) throws Exception {
        return created(postJson(
                PRINT + "/printers",
                MANAGER,
                "{\"displayName\":\"" + name + "\",\"tapeWidthMm\":80,\"agentDeviceId\":\"" + agent.deviceId()
                        + "\",\"agentPrinterKey\":\"" + alias + "\"}"));
    }

    private JsonNode route(String type, @Nullable UUID station, String printerId, boolean auto) throws Exception {
        return created(postJson(
                PRINT + "/routes",
                MANAGER,
                "{\"documentType\":\"" + type + "\"," + (station == null ? "" : "\"stationId\":\"" + station + "\",")
                        + "\"printerId\":\"" + printerId + "\",\"autoPrint\":" + auto + "}"));
    }

    private long auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }

    // ---------------------------------------------------------------- printers

    @Test
    @DisplayName("a manager registers an agent printer bound to an alias the agent advertised, and sees it in the list")
    void registerAnAgentPrinter() throws Exception {
        JsonNode printer = agentPrinter("Counter 80", "counter");

        assertThat(printer.get("connectionKind").asString()).isEqualTo("AGENT");
        assertThat(printer.get("agentDeviceId").asString())
                .isEqualTo(agent.deviceId().toString());
        assertThat(printer.get("agentPrinterKey").asString()).isEqualTo("counter");
        assertThat(printer.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(printer.get("version").asInt()).isEqualTo(1);
        assertThat(printer.get("lastStatus").isNull()).isTrue();

        JsonNode listed = ok(getAs(PRINT + "/printers", MANAGER));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("id").asString())
                .isEqualTo(printer.get("id").asString());
        assertThat(auditCount("printing.printer.registered")).isEqualTo(1);
    }

    @Test
    @DisplayName("a printer cannot be bound to an alias nobody advertised, or an agent that is not this branch's")
    void anAliasMustBeAdvertised() throws Exception {
        MvcResult unadvertised = call(postJson(
                PRINT + "/printers",
                MANAGER,
                "{\"displayName\":\"Ghost\",\"tapeWidthMm\":80,\"agentDeviceId\":\"" + agent.deviceId()
                        + "\",\"agentPrinterKey\":\"ghost\"}"));
        assertThat(unadvertised.getResponse().getStatus()).isEqualTo(400);
        assertThat(unadvertised.getResponse().getContentAsString()).contains("ALIAS_NOT_ADVERTISED");

        MvcResult stranger = call(postJson(
                PRINT + "/printers",
                MANAGER,
                "{\"displayName\":\"Stranger\",\"tapeWidthMm\":80,\"agentDeviceId\":\"" + UUID.randomUUID()
                        + "\",\"agentPrinterKey\":\"counter\"}"));
        assertThat(stranger.getResponse().getStatus()).isEqualTo(400);
        assertThat(stranger.getResponse().getContentAsString()).contains("AGENT_NOT_AT_BRANCH");

        MvcResult half = call(postJson(
                PRINT + "/printers",
                MANAGER,
                "{\"displayName\":\"Half\",\"tapeWidthMm\":80,\"agentDeviceId\":\"" + agent.deviceId() + "\"}"));
        assertThat(half.getResponse().getStatus())
                .as("an agent without an alias")
                .isEqualTo(400);
    }

    @Test
    @DisplayName(
            "a roll is 58 or 80 mm, a name is required and unique at the branch, and two printers cannot share one alias")
    void printerValidation() throws Exception {
        assertThat(status(postJson(PRINT + "/printers", MANAGER, "{\"displayName\":\"Odd\",\"tapeWidthMm\":70}")))
                .isEqualTo(400);
        assertThat(status(postJson(PRINT + "/printers", MANAGER, "{\"displayName\":\"  \",\"tapeWidthMm\":80}")))
                .isEqualTo(400);

        browserPrinter("Front desk", 80);
        MvcResult duplicate =
                call(postJson(PRINT + "/printers", MANAGER, "{\"displayName\":\"Front desk\",\"tapeWidthMm\":58}"));
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);

        agentPrinter("Counter", "counter");
        MvcResult sameAlias = call(postJson(
                PRINT + "/printers",
                MANAGER,
                "{\"displayName\":\"Counter again\",\"tapeWidthMm\":80,\"agentDeviceId\":\"" + agent.deviceId()
                        + "\",\"agentPrinterKey\":\"counter\"}"));
        assertThat(sameAlias.getResponse().getStatus())
                .as("one live printer per alias")
                .isEqualTo(409);
    }

    @Test
    @DisplayName(
            "an edit is made under the version the form was opened at; a stale one is refused; retiring leaves a fact of its own")
    void editAndRetire() throws Exception {
        JsonNode printer = browserPrinter("Front desk", 80);
        String path = PRINT + "/printers/" + printer.get("id").asString();

        JsonNode renamed = ok(putJson(path, MANAGER, 1, "{\"displayName\":\"Front desk 58\",\"tapeWidthMm\":58}"));
        assertThat(renamed.get("displayName").asString()).isEqualTo("Front desk 58");
        assertThat(renamed.get("tapeWidthMm").asInt()).isEqualTo(58);
        assertThat(renamed.get("version").asInt()).isEqualTo(2);

        MvcResult stale = call(putJson(path, MANAGER, 1, "{\"displayName\":\"Late edit\",\"tapeWidthMm\":80}"));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(status(put(path)
                        .with(tokenFor(MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\",\"tapeWidthMm\":80}")))
                .as("no If-Match, no edit")
                .isEqualTo(400);

        JsonNode retired = ok(putJson(
                path, MANAGER, 2, "{\"displayName\":\"Front desk 58\",\"tapeWidthMm\":58,\"status\":\"RETIRED\"}"));
        assertThat(retired.get("status").asString()).isEqualTo("RETIRED");
        assertThat(auditCount("printing.printer.changed")).isEqualTo(1);
        assertThat(auditCount("printing.printer.retired")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "who may do what: a cashier only reprints, a brand manager reads, another branch's manager reaches nothing here")
    void printerCapabilities() throws Exception {
        JsonNode printer = browserPrinter("Front desk", 80);
        String body = "{\"displayName\":\"Another\",\"tapeWidthMm\":80}";

        assertThat(status(getAs(PRINT + "/printers", CASHIER))).isEqualTo(403);
        assertThat(status(postJson(PRINT + "/printers", CASHIER, body))).isEqualTo(403);
        assertThat(status(getAs(PRINT + "/printers", SIBLING_MANAGER))).isEqualTo(403);
        assertThat(status(postJson(PRINT + "/printers", SIBLING_MANAGER, body))).isEqualTo(403);
        assertThat(status(getAs(PRINT + "/printers", BRAND_MANAGER))).isEqualTo(200);
        assertThat(status(postJson(PRINT + "/printers", BRAND_MANAGER, body))).isEqualTo(403);
        assertThat(status(getAs(PRINT + "/printers", OTHER_OWNER))).isEqualTo(403);
        assertThat(status(postJson(PRINT + "/printers/" + printer.get("id").asString() + "/test-page", CASHIER, null)))
                .as("a cashier prints a test page: print.reprint")
                .isEqualTo(201);
        assertThat(status(postJson(PRINT + "/printers", ADMIN, "{\"displayName\":\"Admin's\",\"tapeWidthMm\":80}")))
                .isEqualTo(201);
    }

    @Test
    @DisplayName("with printing off for the tenant every write is refused PRINTING_OFF and the reads still answer")
    void printingOffRefusesWrites() throws Exception {
        JsonNode printer = browserPrinter("Front desk", 80);
        printingOff();

        MvcResult refused = call(postJson(PRINT + "/printers", MANAGER, "{\"displayName\":\"x\",\"tapeWidthMm\":80}"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("PRINTING_OFF");
        assertThat(status(postJson(PRINT + "/printers/" + printer.get("id").asString() + "/test-page", MANAGER, null)))
                .isEqualTo(422);
        assertThat(status(postJson(
                        PRINT + "/routes",
                        MANAGER,
                        "{\"documentType\":\"ORDER_SLIP\",\"printerId\":\""
                                + printer.get("id").asString() + "\"}")))
                .isEqualTo(422);

        assertThat(ok(getAs(PRINT + "/printers", MANAGER))).hasSize(1);
        assertThat(ok(getAs(PRINT + "/jobs", MANAGER).param("limit", "5")).get("items"))
                .isEmpty();
    }

    // ------------------------------------------------------------------ routes

    @Test
    @DisplayName(
            "a route maps a document, optionally a station, to a printer; it can be changed under its version and deleted")
    void routesLifecycle() throws Exception {
        JsonNode printer = agentPrinter("Counter", "counter");
        JsonNode ticket =
                route("KITCHEN_TICKET", PrintingWorld.GRILL, printer.get("id").asString(), true);
        assertThat(ticket.get("stationId").asString()).isEqualTo(PrintingWorld.GRILL.toString());
        assertThat(ticket.get("copies").asInt()).isEqualTo(1);
        assertThat(ticket.get("autoPrint").asBoolean()).isTrue();

        String path = PRINT + "/routes/" + ticket.get("id").asString();
        JsonNode changed = ok(putJson(
                path,
                MANAGER,
                1,
                "{\"documentType\":\"KITCHEN_TICKET\",\"stationId\":\"" + PrintingWorld.GRILL + "\",\"printerId\":\""
                        + printer.get("id").asString() + "\",\"copies\":2,\"autoPrint\":false}"));
        assertThat(changed.get("copies").asInt()).isEqualTo(2);
        assertThat(changed.get("autoPrint").asBoolean()).isFalse();
        assertThat(changed.get("version").asInt()).isEqualTo(2);

        assertThat(status(putJson(
                        path,
                        MANAGER,
                        1,
                        "{\"documentType\":\"KITCHEN_TICKET\",\"printerId\":\""
                                + printer.get("id").asString() + "\"}")))
                .as("a stale form")
                .isEqualTo(409);
        assertThat(status(putJson(
                        path,
                        MANAGER,
                        2,
                        "{\"documentType\":\"ORDER_SLIP\",\"printerId\":\""
                                + printer.get("id").asString() + "\"}")))
                .as("a route keeps its document type")
                .isEqualTo(400);

        assertThat(status(delete(path)
                        .with(tokenFor(MANAGER))
                        .header("If-Match", "W/\"2\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "key-" + UUID.randomUUID())))
                .isEqualTo(204);
        assertThat(ok(getAs(PRINT + "/routes", MANAGER))).isEmpty();
        assertThat(auditCount("printing.route.changed"))
                .as("created, changed, deleted")
                .isEqualTo(3);
    }

    @Test
    @DisplayName(
            "a route is refused when it asks for what cannot work: a browser on auto-print, a foreign station or printer, a test page, four copies")
    void routeValidation() throws Exception {
        JsonNode browser = browserPrinter("Front desk", 80);
        String printerId = browser.get("id").asString();

        MvcResult auto = call(postJson(
                PRINT + "/routes",
                MANAGER,
                "{\"documentType\":\"ORDER_SLIP\",\"printerId\":\"" + printerId + "\",\"autoPrint\":true}"));
        assertThat(auto.getResponse().getStatus()).isEqualTo(422);
        assertThat(auto.getResponse().getContentAsString()).contains("AUTO_PRINT_NEEDS_AN_AGENT");
        route("ORDER_SLIP", null, printerId, false);

        MvcResult foreignStation = call(postJson(
                PRINT + "/routes",
                MANAGER,
                "{\"documentType\":\"KITCHEN_TICKET\",\"stationId\":\"" + PrintingWorld.SIBLINGS_STATION
                        + "\",\"printerId\":\"" + printerId + "\"}"));
        assertThat(foreignStation.getResponse().getStatus()).isEqualTo(400);
        assertThat(foreignStation.getResponse().getContentAsString()).contains("STATION_NOT_AT_BRANCH");

        assertThat(status(postJson(
                        PRINT + "/routes",
                        MANAGER,
                        "{\"documentType\":\"ORDER_SLIP\",\"stationId\":\"" + PrintingWorld.GRILL
                                + "\",\"printerId\":\"" + printerId + "\"}")))
                .as("a station is for a kitchen ticket only")
                .isEqualTo(400);
        assertThat(status(postJson(
                        PRINT + "/routes",
                        MANAGER,
                        "{\"documentType\":\"TEST_PAGE\",\"printerId\":\"" + printerId + "\"}")))
                .as("a test page is asked for, never routed")
                .isEqualTo(400);
        assertThat(status(postJson(
                        PRINT + "/routes",
                        MANAGER,
                        "{\"documentType\":\"ORDER_SLIP\",\"printerId\":\"" + printerId + "\",\"copies\":4}")))
                .isEqualTo(400);
        assertThat(status(postJson(
                        PRINT + "/routes",
                        MANAGER,
                        "{\"documentType\":\"FISCAL_RECEIPT\",\"printerId\":\"" + UUID.randomUUID() + "\"}")))
                .as("a printer that is not there")
                .isEqualTo(400);
        assertThat(status(postJson(
                        PRINT + "/routes",
                        MANAGER,
                        "{\"documentType\":\"ORDER_SLIP\",\"printerId\":\"" + printerId + "\"}")))
                .as("the same route twice")
                .isEqualTo(409);
    }

    @Test
    @DisplayName("a route cannot name a printer of another branch, and the database says why")
    void aRouteCannotNameAnotherBranchsPrinter() throws Exception {
        String sibling = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + SIBLING + "/print";
        JsonNode siblingsPrinter = created(postJson(
                sibling + "/printers", SIBLING_MANAGER, "{\"displayName\":\"Sibling desk\",\"tapeWidthMm\":80}"));

        MvcResult refused = call(postJson(
                PRINT + "/routes",
                MANAGER,
                "{\"documentType\":\"ORDER_SLIP\",\"printerId\":\""
                        + siblingsPrinter.get("id").asString() + "\"}"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("PRINTER_NOT_AT_BRANCH");
        assertThat(ok(getAs(PRINT + "/printers", MANAGER)))
                .as("the sibling's printer is not listed here")
                .isEmpty();
    }

    // ----------------------------------------------------------------- profile

    @Test
    @DisplayName(
            "the brand's receipt profile: default at version 0, saved under If-Match by the administrator, never by a branch manager")
    void theProfile() throws Exception {
        String path = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/print/receipt-profile";

        JsonNode fresh = ok(getAs(path, BRAND_MANAGER));
        assertThat(fresh.get("version").asInt()).isZero();
        assertThat(fresh.get("printLogo").asBoolean()).isFalse();
        assertThat(fresh.get("footerTexts")).isEmpty();
        assertThat(status(getAs(path, MANAGER)))
                .as("a location manager does not hold brand scope")
                .isEqualTo(403);
        assertThat(status(putJson(path, MANAGER, 0, "{\"printLogo\":true}"))).isEqualTo(403);
        assertThat(status(putJson(path, BRAND_MANAGER, 0, "{\"printLogo\":true}")))
                .as("print.manage is the administrator's")
                .isEqualTo(403);

        JsonNode saved = ok(
                putJson(
                        path,
                        ADMIN,
                        0,
                        "{\"printLogo\":true,\"footerTexts\":{\"ru\":[\"Спасибо за визит\",\"Приходите ещё\"],\"en\":[\"Thank you\"]}}"));
        assertThat(saved.get("version").asInt()).isEqualTo(1);
        assertThat(saved.get("printLogo").asBoolean()).isTrue();
        assertThat(saved.get("footerTexts").get("ru")).hasSize(2);

        assertThat(status(putJson(path, ADMIN, 0, "{\"printLogo\":false}")))
                .as("stale")
                .isEqualTo(409);
        assertThat(ok(putJson(path, ADMIN, 1, "{\"printLogo\":false,\"footerTexts\":{}}"))
                        .get("version")
                        .asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "a footer is up to three lines of 48 characters in a language the platform prints, and its words never reach the audit log")
    void footerValidationAndAudit() throws Exception {
        String path = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/print/receipt-profile";
        String fourLines = "{\"printLogo\":false,\"footerTexts\":{\"ru\":[\"a\",\"b\",\"c\",\"d\"]}}";
        String tooWide = "{\"printLogo\":false,\"footerTexts\":{\"ru\":[\"" + "x".repeat(49) + "\"]}}";
        String otherLanguage = "{\"printLogo\":false,\"footerTexts\":{\"kk\":[\"hi\"]}}";
        for (String bad : List.of(fourLines, tooWide, otherLanguage)) {
            assertThat(status(putJson(path, ADMIN, 0, bad))).as(bad).isEqualTo(400);
        }
        assertThat(status(putJson(
                        path, ADMIN, 0, "{\"printLogo\":false,\"footerTexts\":{\"ru\":[\"" + "x".repeat(48) + "\"]}}")))
                .as("48 is exactly an 80 mm roll")
                .isEqualTo(200);

        ok(putJson(path, ADMIN, 1, "{\"printLogo\":true,\"footerTexts\":{\"ru\":[\"Секретная скидка завтра\"]}}"));

        String audited = jdbc.sql(
                        "SELECT string_agg(change_document::text, ' ') FROM audit.audit_events WHERE action_code = 'printing.profile.changed'")
                .query(String.class)
                .single();
        assertThat(audited)
                .contains("footerLengthru")
                .doesNotContain("Секретная")
                .doesNotContain("скидка");
        assertThat(auditCount("printing.profile.changed")).isEqualTo(2);
    }

    // ------------------------------------------------------------- jobs, reprint

    private SeededOrder anOrder() {
        return world.order(
                LOCATION, "A-014", 150_000L, true, LineSpec.of("Lagman", 2, 90_000), LineSpec.of("Cola", 1, 8_000));
    }

    private void confirm(SeededOrder order) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> events.publishEvent(
                        PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant())));
    }

    @Test
    @DisplayName(
            "a reprint is a new job, a marked copy, linked to the first print and recorded with the person and the reason; a cashier may ask")
    void aReprint() throws Exception {
        JsonNode counter = agentPrinter("Counter", "counter");
        JsonNode desk = browserPrinter("Front desk", 80);
        route("ORDER_SLIP", null, counter.get("id").asString(), true);
        SeededOrder order = anOrder();
        confirm(order);
        JobRow original =
                store.listJobs(TENANT, LOCATION, List.of(), null, null, 5).getFirst();

        JsonNode made = created(postJson(
                "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/"
                        + order.orderId() + "/print-jobs",
                CASHIER,
                "{\"documentType\":\"ORDER_SLIP\",\"printerId\":\""
                        + desk.get("id").asString() + "\",\"reasonCode\":\"CUSTOMER_REQUEST\"}"));

        assertThat(made).hasSize(1);
        JsonNode job = made.get(0);
        assertThat(job.get("status").asString())
                .as("a browser printer is handed off")
                .isEqualTo("HANDED_OFF");
        assertThat(job.get("origin").asString()).isEqualTo("MANUAL");
        assertThat(job.get("reasonCode").asString()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(job.get("requestedBy").asString()).isEqualTo(CASHIER);
        assertThat(job.get("reprintOf").asString()).isEqualTo(original.id().toString());
        JobRow stored =
                store.findJob(TENANT, UUID.fromString(job.get("id").asString())).orElseThrow();
        assertThat(PrintingSteps.documentOf(stored, protection, codec).copyMarker())
                .isTrue();
        assertThat(PrintingSteps.documentOf(
                                original.protectedDocument() == null
                                        ? store.findJob(TENANT, original.id()).orElseThrow()
                                        : original,
                                protection,
                                codec)
                        .copyMarker())
                .isFalse();

        assertThat(jdbc.sql("SELECT actor_subject FROM audit.audit_events WHERE action_code = 'printing.job.reprinted'")
                        .query(String.class)
                        .list())
                .containsExactly(CASHIER);
        assertThat(jdbc.sql("SELECT audit_class FROM audit.audit_events WHERE action_code = 'printing.job.reprinted'")
                        .query(String.class)
                        .single())
                .isEqualTo("BUSINESS");
        assertThat(jdbc.sql("SELECT reason FROM audit.audit_events WHERE action_code = 'printing.job.reprinted'")
                        .query(String.class)
                        .single())
                .isEqualTo("CUSTOMER_REQUEST");
    }

    @Test
    @DisplayName("a document with no route prints nowhere, and the order action is told so")
    void noRouteNoPaper() throws Exception {
        SeededOrder order = anOrder();

        MvcResult refused = call(postJson(
                "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/"
                        + order.orderId() + "/print-jobs",
                MANAGER,
                "{\"documentType\":\"ORDER_SLIP\",\"reasonCode\":\"OTHER\"}"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("NO_ROUTE");
        assertThat(status(postJson(
                        "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/"
                                + order.orderId() + "/print-jobs",
                        MANAGER,
                        "{\"documentType\":\"ORDER_SLIP\",\"reasonCode\":\"NOT_A_REASON\"}")))
                .isEqualTo(400);
    }

    @Test
    @DisplayName(
            "a fiscal receipt is reprinted for an issued sale and refused, naming the state or the type, for anything else")
    void receiptReprintRefusals() throws Exception {
        JsonNode desk = browserPrinter("Front desk", 80);
        SeededOrder order = anOrder();
        String reprint = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/"
                + order.orderId() + "/print-jobs";
        String body = "{\"documentType\":\"FISCAL_RECEIPT\",\"printerId\":\""
                + desk.get("id").asString() + "\",\"reasonCode\":\"CUSTOMER_REQUEST\"}";

        MvcResult none = call(postJson(reprint, MANAGER, body));
        assertThat(none.getResponse().getStatus()).isEqualTo(422);
        assertThat(none.getResponse().getContentAsString()).contains("NO_FISCAL_DOCUMENT");

        UUID sale = world.fiscalDocument(order, "SALE", "SUBMITTED");
        MvcResult submitted = call(postJson(reprint, MANAGER, body));
        assertThat(submitted.getResponse().getStatus()).isEqualTo(422);
        assertThat(submitted.getResponse().getContentAsString())
                .contains("SUBMITTED")
                .contains("SALE");

        // The provider answers: the same sale is now issued.
        world.issue(sale);
        UUID issued = sale;
        JsonNode receipt = created(postJson(reprint, MANAGER, body)).get(0);
        assertThat(receipt.get("documentType").asString()).isEqualTo("FISCAL_RECEIPT");
        assertThat(receipt.get("sourceId").asString()).isEqualTo(issued.toString());

        world.fiscalDocument(order, "REFUND", "ISSUED");
        jdbc.sql("UPDATE fiscal.fiscal_documents SET status = 'FAILED', issued_at = NULL, fiscal_sign = NULL,"
                        + " receipt_url = NULL, receipt_reference = NULL, terminal_id = NULL, registered_at = NULL,"
                        + " external_receipt_id = NULL WHERE id = :id")
                .param("id", issued)
                .update();
        MvcResult refund = call(postJson(reprint, MANAGER, body));
        assertThat(refund.getResponse().getStatus()).isEqualTo(422);
        assertThat(refund.getResponse().getContentAsString()).contains("FISCAL_DOCUMENT_NOT_PRINTABLE");
    }

    @Test
    @DisplayName("a test page is a job of its own, previewable, and a browser printer's is handed off")
    void testPageAndPreview() throws Exception {
        JsonNode desk = browserPrinter("Front desk", 58);
        JsonNode counter = agentPrinter("Counter", "counter");

        JsonNode handed =
                created(postJson(PRINT + "/printers/" + desk.get("id").asString() + "/test-page", CASHIER, null));
        JsonNode queued =
                created(postJson(PRINT + "/printers/" + counter.get("id").asString() + "/test-page", MANAGER, null));

        assertThat(handed.get("documentType").asString()).isEqualTo("TEST_PAGE");
        assertThat(handed.get("status").asString()).isEqualTo("HANDED_OFF");
        assertThat(handed.get("tapeWidthMm").asInt()).isEqualTo(58);
        assertThat(queued.get("status").asString()).isEqualTo("QUEUED");

        MvcResult png = mvc.perform(postJson(
                        PRINT + "/documents/preview",
                        MANAGER,
                        "{\"jobId\":\"" + handed.get("id").asString() + "\"}"))
                .andReturn();
        assertThat(png.getResponse().getStatus()).isEqualTo(200);
        byte[] bytes = png.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertThat(image.getWidth()).as("the 58 mm roll").isEqualTo(384);

        MvcResult unknown = mvc.perform(
                        postJson(PRINT + "/documents/preview", MANAGER, "{\"jobId\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(status(postJson(PRINT + "/documents/preview", CASHIER, "{\"documentType\":\"TEST_PAGE\"}")))
                .as("a cashier may not preview: print.read")
                .isEqualTo(403);
        MvcResult byWidth = mvc.perform(postJson(
                        PRINT + "/documents/preview", MANAGER, "{\"documentType\":\"TEST_PAGE\",\"tapeWidthMm\":80}"))
                .andReturn();
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                                byWidth.getResponse().getContentAsByteArray()))
                        .getWidth())
                .isEqualTo(576);
    }

    @Test
    @DisplayName(
            "the queue pages newest first by cursor, filters by state, and a queued job can be cancelled but a handed-off one cannot")
    void queueCancelAndPaging() throws Exception {
        JsonNode counter = agentPrinter("Counter", "counter");
        JsonNode desk = browserPrinter("Front desk", 80);
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            clock.advance(Duration.ofSeconds(1));
            ids.add(created(postJson(PRINT + "/printers/" + counter.get("id").asString() + "/test-page", MANAGER, null))
                    .get("id")
                    .asString());
        }
        String handedOff = created(
                        postJson(PRINT + "/printers/" + desk.get("id").asString() + "/test-page", MANAGER, null))
                .get("id")
                .asString();

        JsonNode firstPage = ok(getAs(PRINT + "/jobs", MANAGER).param("limit", "2"));
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("nextCursor").isNull()).isFalse();
        JsonNode secondPage = ok(getAs(PRINT + "/jobs", MANAGER)
                .param("limit", "2")
                .param("cursor", firstPage.get("nextCursor").asString()));
        assertThat(secondPage.get("items")).hasSize(2);
        List<String> seen = new ArrayList<>();
        firstPage.get("items").forEach(item -> seen.add(item.get("id").asString()));
        secondPage.get("items").forEach(item -> seen.add(item.get("id").asString()));
        assertThat(seen).doesNotHaveDuplicates().hasSize(4).contains(handedOff);
        assertThat(secondPage.get("nextCursor").isNull()).isTrue();
        assertThat(status(getAs(PRINT + "/jobs", MANAGER)
                        .param("cursor", firstPage.get("nextCursor").asString())
                        .param("status", "QUEUED")))
                .as("a cursor belongs to its filters")
                .isEqualTo(400);

        assertThat(ok(getAs(PRINT + "/jobs", MANAGER).param("status", "QUEUED")).get("items"))
                .hasSize(3);
        assertThat(status(getAs(PRINT + "/jobs", MANAGER).param("status", "SOMETHING")))
                .isEqualTo(400);

        JsonNode cancelled = ok(postJson(PRINT + "/jobs/" + ids.getFirst() + "/cancel", MANAGER, null));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        MvcResult tooLate = call(postJson(PRINT + "/jobs/" + handedOff + "/cancel", MANAGER, null));
        assertThat(tooLate.getResponse().getStatus()).isEqualTo(422);
        assertThat(tooLate.getResponse().getContentAsString()).contains("JOB_NOT_QUEUED");
        assertThat(status(postJson(PRINT + "/jobs/" + ids.get(1) + "/cancel", CASHIER, null)))
                .isEqualTo(403);
        assertThat(ok(getAs(PRINT + "/jobs/" + ids.getFirst(), BRAND_MANAGER))
                        .get("status")
                        .asString())
                .isEqualTo("CANCELLED");
        assertThat(Map.of("shown", "never the document")).doesNotContainKey("protectedDocument");
    }

    @Test
    @DisplayName("the agents list shows what each advertises; a cashier cannot approve or revoke one")
    void agentsScreen() throws Exception {
        JsonNode agents = ok(getAs(PRINT + "/agents", MANAGER));
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).get("device").get("deviceClass").asString()).isEqualTo("PRINT_AGENT");
        assertThat(agents.get(0).get("device").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(status(getAs(PRINT + "/agents/enrolments/NOPE-0000", MANAGER)))
                .isEqualTo(404);
        assertThat(status(getAs(PRINT + "/agents", CASHIER))).isEqualTo(403);
        assertThat(status(postJson(PRINT + "/agents/" + agent.deviceId() + "/revoke", CASHIER, "{\"reason\":\"x\"}")))
                .isEqualTo(403);
        assertThat(status(postJson(PRINT + "/agents/" + UUID.randomUUID() + "/revoke", MANAGER, "{\"reason\":\"x\"}")))
                .as("a device that is not an agent of this branch")
                .isEqualTo(404);
        assertThat(auditCount("printing.agent.enrolled")).isEqualTo(1);
    }

    // ------------------------------------------------- the queue's own reprint

    private String jobReprint(String jobId) {
        return PRINT + "/jobs/" + jobId + "/reprint";
    }

    @Test
    @DisplayName(
            "a finished job is printed again from the queue: a new marked copy linked to the first, with the person and the reason; one still on its way, a test page and a retired printer are refused")
    void reprintFromTheQueue() throws Exception {
        JsonNode counter = agentPrinter("Counter", "counter");
        route("ORDER_SLIP", null, counter.get("id").asString(), true);
        SeededOrder order = anOrder();
        confirm(order);
        JobRow original =
                store.listJobs(TENANT, LOCATION, List.of(), null, null, 5).getFirst();
        String reason = "{\"reasonCode\":\"PAPER_JAM\"}";

        MvcResult early = call(postJson(jobReprint(original.id().toString()), MANAGER, reason));
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(early.getResponse().getContentAsString())
                .as("a job still on its way to paper is not printed twice by pressing a button")
                .contains("JOB_NOT_FINISHED");

        assertThat(fake.handleOnce()).hasSize(1);
        assertThat(store.findJob(TENANT, original.id()).orElseThrow().status().name())
                .isEqualTo("PRINTED");

        JsonNode again = created(postJson(jobReprint(original.id().toString()), CASHIER, reason));
        assertThat(again.get("origin").asString()).isEqualTo("MANUAL");
        assertThat(again.get("status").asString()).isEqualTo("QUEUED");
        assertThat(again.get("documentType").asString()).isEqualTo("ORDER_SLIP");
        assertThat(again.get("printerId").asString())
                .as("without a printer it goes where the old job went")
                .isEqualTo(counter.get("id").asString());
        assertThat(again.get("reasonCode").asString()).isEqualTo("PAPER_JAM");
        assertThat(again.get("requestedBy").asString()).isEqualTo(CASHIER);
        assertThat(again.get("reprintOf").asString()).isEqualTo(original.id().toString());
        assertThat(again.get("sourceId").asString()).isEqualTo(order.orderId().toString());
        JobRow stored = store.findJob(TENANT, UUID.fromString(again.get("id").asString()))
                .orElseThrow();
        assertThat(PrintingSteps.documentOf(stored, protection, codec).copyMarker())
                .as("every print after the first says it is a copy")
                .isTrue();
        assertThat(jdbc.sql("SELECT actor_subject FROM audit.audit_events WHERE action_code = 'printing.job.reprinted'")
                        .query(String.class)
                        .list())
                .containsExactly(CASHIER);
        assertThat(jdbc.sql("SELECT reason FROM audit.audit_events WHERE action_code = 'printing.job.reprinted'")
                        .query(String.class)
                        .single())
                .isEqualTo("PAPER_JAM");

        String testPageId = created(
                        postJson(PRINT + "/printers/" + counter.get("id").asString() + "/test-page", MANAGER, null))
                .get("id")
                .asString();
        // A printer is served one job at a time, in order: drain the copy and the test page.
        while (!fake.handleOnce().isEmpty()) {
            clock.advance(Duration.ofSeconds(1));
        }
        MvcResult testPage = call(postJson(jobReprint(testPageId), MANAGER, reason));
        assertThat(testPage.getResponse().getStatus()).isEqualTo(422);
        assertThat(testPage.getResponse().getContentAsString()).contains("JOB_NOT_REPRINTABLE");

        ok(putJson(
                PRINT + "/printers/" + counter.get("id").asString(),
                MANAGER,
                counter.get("version").asLong(),
                "{\"displayName\":\"Counter\",\"tapeWidthMm\":80,\"agentDeviceId\":\"" + agent.deviceId()
                        + "\",\"agentPrinterKey\":\"counter\",\"status\":\"RETIRED\"}"));
        MvcResult retired = call(postJson(jobReprint(original.id().toString()), MANAGER, reason));
        assertThat(retired.getResponse().getStatus()).isEqualTo(422);
        assertThat(retired.getResponse().getContentAsString()).contains("PRINTER_RETIRED");
        JsonNode desk = browserPrinter("Front desk", 80);
        assertThat(created(postJson(
                                jobReprint(original.id().toString()),
                                MANAGER,
                                "{\"printerId\":\"" + desk.get("id").asString() + "\",\"reasonCode\":\"OTHER\"}"))
                        .get("status")
                        .asString())
                .as("a chosen browser printer is handed off")
                .isEqualTo("HANDED_OFF");

        assertThat(status(postJson(jobReprint(original.id().toString()), SIBLING_MANAGER, reason)))
                .as("a manager of another branch")
                .isIn(403, 404);
        assertThat(status(postJson(jobReprint(UUID.randomUUID().toString()), MANAGER, reason)))
                .isEqualTo(404);
        assertThat(status(postJson(jobReprint(original.id().toString()), MANAGER, "{\"reasonCode\":\"NOT_A_REASON\"}")))
                .isEqualTo(400);
    }

    @Test
    @DisplayName(
            "a kitchen ticket that was printed is reprinted for its own station from the queue, with the amendment as it is now")
    void reprintAKitchenTicketFromTheQueue() throws Exception {
        JsonNode counter = agentPrinter("Counter", "counter");
        route("KITCHEN_TICKET", PrintingWorld.GRILL, counter.get("id").asString(), true);
        SeededOrder order = world.order(LOCATION, "K-007", null, false, LineSpec.of("Plov", 1, 50_000));
        UUID ticket = world.releasedTicket(order, LOCATION, "K-007", Map.of(0, PrintingWorld.GRILL), clock.instant());
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> events.publishEvent(new uz.horecaos.platform.kitchen.api.KitchenTicketReleased(
                                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant())));
        JobRow original =
                store.listJobs(TENANT, LOCATION, List.of(), null, null, 5).getFirst();
        assertThat(fake.handleOnce()).hasSize(1);

        JsonNode again = created(
                postJson(jobReprint(original.id().toString()), MANAGER, "{\"reasonCode\":\"KITCHEN_REQUEST\"}"));

        assertThat(again.get("documentType").asString()).isEqualTo("KITCHEN_TICKET");
        assertThat(again.get("sourceType").asString()).isEqualTo("KITCHEN_TICKET");
        assertThat(again.get("sourceId").asString()).isEqualTo(ticket.toString());
        assertThat(again.get("stationId").asString()).isEqualTo(PrintingWorld.GRILL.toString());
        assertThat(again.get("reprintOf").asString()).isEqualTo(original.id().toString());
        assertThat(PrintingSteps.documentOf(
                                store.findJob(
                                                TENANT,
                                                UUID.fromString(again.get("id").asString()))
                                        .orElseThrow(),
                                protection,
                                codec)
                        .copyMarker())
                .isTrue();
    }

    // ----------------------------------------------- whether the till may print

    @Test
    @DisplayName(
            "the routes screen is told whether the branch has an active order-export binding, which is the only fact the platform holds about its till printing")
    void theTillMayAlreadyPrint() throws Exception {
        assertThat(ok(getAs(PRINT + "/pos-export", MANAGER))
                        .get("orderExportActive")
                        .asBoolean())
                .as("a branch with no POS binding")
                .isFalse();

        UUID installation = UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'POS', 'clopos', 'clopos-open-api-v2', 'The till', 'ACTIVE', :secret)
                """)
                .param("id", installation)
                .param("tenantId", TENANT)
                .param("secret", "horecaos:local:provider_pos:" + installation + ":key")
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, location_id, status, priority, effective_from)
                VALUES (:id, :tenantId, :installation, :brandId, :locationId, 'ACTIVE', 1,
                        now() - interval '1 hour')
                """)
                .param("id", binding)
                .param("tenantId", TENANT)
                .param("installation", installation)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .update();
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities
                    (binding_id, tenant_id, capability_code, enabled, is_primary, verified_at)
                VALUES (:binding, :tenantId, 'ORDER_EXPORT', true, true, now())
                """).param("binding", binding).param("tenantId", TENANT).update();

        assertThat(ok(getAs(PRINT + "/pos-export", MANAGER))
                        .get("orderExportActive")
                        .asBoolean())
                .as("an active binding that provides ORDER_EXPORT here")
                .isTrue();
        assertThat(ok(getAs(
                                "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + PrintingWorld.SIBLING
                                        + "/print/pos-export",
                                SIBLING_MANAGER))
                        .get("orderExportActive")
                        .asBoolean())
                .as("another branch of the same brand has no binding of its own")
                .isFalse();
        assertThat(status(getAs(PRINT + "/pos-export", SIBLING_MANAGER)))
                .as("a manager of another branch")
                .isIn(403, 404);
    }
}
