package uz.horecaos.platform.printing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.printing.PrintingWorld.LineSpec;
import uz.horecaos.platform.printing.PrintingWorld.SeededOrder;
import uz.horecaos.platform.printing.application.PrintDocumentCodec;
import uz.horecaos.platform.printing.application.PrinterService;
import uz.horecaos.platform.printing.application.PrinterService.PrinterDraft;
import uz.horecaos.platform.printing.application.RouteService;
import uz.horecaos.platform.printing.application.RouteService.RouteDraft;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.domain.PrinterStatus;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;

/**
 * Which paper says it is a copy (ADR 0154, Decision 5): "every print after the first of one document carries a
 * copy marker". The first paper made of a document -- the default of a cashier who presses «Печать» at a branch
 * where nothing prints by itself -- is the original, and a receipt that says «КОПИЯ» on the only one a customer
 * will ever hold is wrong. Paper that never came out (a job cancelled, dead or expired) does not turn the next
 * into a copy; paper that is on its way, or is out, does.
 */
class PrintCopyMarkerTests extends PrintingSpringTest {

    private static final String ORDERS =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/";

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcPrintingStore store;

    @Autowired
    @SuppressWarnings("NullAway")
    private PrinterService printers;

    @Autowired
    @SuppressWarnings("NullAway")
    private RouteService routes;

    @Autowired
    @SuppressWarnings("NullAway")
    private PrintDocumentCodec codec;

    @Autowired
    @SuppressWarnings("NullAway")
    private ApplicationEventPublisher events;

    @Autowired
    @SuppressWarnings("NullAway")
    private PlatformTransactionManager transactions;

    /** The front desk's browser, where a cashier's presses go. */
    private PrinterRow desk;

    /** The counter's printer behind a print agent, which is the only kind a route may print on by itself. */
    private PrinterRow counter;

    @BeforeEach
    void twoPrinters() throws Exception {
        printingOn();
        desk = printers.register(TENANT, BRAND, LOCATION, new PrinterDraft("Front desk", 80, null, null), MANAGER);
        Agent agent = enrolAgent();
        store.upsertAdvert(TENANT, LOCATION, agent.deviceId(), "counter", PrinterStatus.ONLINE, null, clock.instant());
        counter = printers.register(
                TENANT, BRAND, LOCATION, new PrinterDraft("Counter 80", 80, agent.deviceId(), "counter"), MANAGER);
    }

    private SeededOrder anOrder(String number) {
        return world.order(
                LOCATION,
                number,
                150_000L,
                false,
                LineSpec.of("Lagman", 2, 90_000),
                LineSpec.of("Cola", 1, 8_000),
                LineSpec.of("Burger", 1, 40_000));
    }

    /** What a cashier's press of «Печать» sends, to the front desk's browser. */
    private JsonNode print(String subject, SeededOrder order, String documentType) throws Exception {
        // Each press is a moment of its own: the first print of a document is the earliest job made of it.
        clock.advance(Duration.ofSeconds(1));
        return created(postJson(
                ORDERS + order.orderId() + "/print-jobs",
                subject,
                "{\"documentType\":\"" + documentType + "\",\"printerId\":\"" + desk.id()
                        + "\",\"reasonCode\":\"CUSTOMER_REQUEST\"}"));
    }

    private boolean marked(JsonNode job) {
        JobRow stored =
                store.findJob(TENANT, UUID.fromString(job.get("id").asString())).orElseThrow();
        return PrintingSteps.documentOf(stored, protection, codec).copyMarker();
    }

    private static boolean linked(JsonNode job) {
        return job.has("reprintOf") && !job.get("reprintOf").isNull();
    }

    private void confirm(SeededOrder order) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> events.publishEvent(
                        PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant())));
    }

    private void autoSlipsTo(PrinterRow printer) {
        routes.create(
                TENANT,
                BRAND,
                LOCATION,
                new RouteDraft(PrintDocumentType.ORDER_SLIP, null, printer.id(), 1, true, true),
                MANAGER);
    }

    @Test
    @DisplayName(
            "the first slip and the first receipt a cashier asks for are originals; the next of each says it is a copy and names the first")
    void theFirstPrintOnRequestIsNotACopy() throws Exception {
        SeededOrder order = anOrder("A-020");
        world.fiscalDocument(order, "SALE", "ISSUED");

        for (String type : List.of("ORDER_SLIP", "FISCAL_RECEIPT")) {
            JsonNode first = print(CASHIER, order, type).get(0);
            assertThat(first.get("status").asString()).isEqualTo("HANDED_OFF");
            assertThat(marked(first))
                    .as("the %s has never been printed: this is the original", type)
                    .isFalse();
            assertThat(linked(first)).as("there is no earlier print to link to").isFalse();

            JsonNode second = print(CASHIER, order, type).get(0);
            assertThat(marked(second))
                    .as("the customer may already hold the first %s", type)
                    .isTrue();
            assertThat(second.get("reprintOf").asString())
                    .isEqualTo(first.get("id").asString());

            assertThat(marked(print(CASHIER, order, type).get(0)))
                    .as("and so is every one after")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a kitchen ticket asked for by hand is an original per station the first time, and a copy the second")
    void aKitchenTicketIsAnOriginalPerStationTheFirstTime() throws Exception {
        SeededOrder order = anOrder("A-021");
        world.releasedTicket(
                order,
                LOCATION,
                "A-021",
                Map.of(0, PrintingWorld.GRILL, 1, PrintingWorld.BAR, 2, PrintingWorld.GRILL),
                clock.instant());

        JsonNode first = print(MANAGER, order, "KITCHEN_TICKET");
        assertThat(first).as("one ticket per station with items").hasSize(2);
        first.forEach(job -> assertThat(marked(job))
                .as("no station's ticket was printed before")
                .isFalse());

        JsonNode second = print(MANAGER, order, "KITCHEN_TICKET");
        second.forEach(job -> assertThat(marked(job))
                .as("each station's ticket was printed once already")
                .isTrue());
    }

    @Test
    @DisplayName(
            "a slip that is queued or printed makes the next a copy; one that was cancelled never reached paper and does not")
    void paperThatNeverCameOutDoesNotMakeACopy() throws Exception {
        autoSlipsTo(counter);

        SeededOrder waiting = anOrder("A-022");
        confirm(waiting);
        assertThat(store.listJobs(TENANT, LOCATION, List.of("QUEUED"), null, null, 10))
                .as("the automatic slip is on its way")
                .hasSize(1);
        assertThat(marked(print(CASHIER, waiting, "ORDER_SLIP").get(0)))
                .as("a slip is queued already: a second one is a copy")
                .isTrue();

        SeededOrder cancelled = anOrder("A-023");
        confirm(cancelled);
        JobRow automatic = store.listJobs(TENANT, LOCATION, List.of("QUEUED"), null, null, 10).stream()
                .filter(job -> cancelled.orderId().equals(job.sourceId()))
                .findFirst()
                .orElseThrow();
        ok(postJson(PRINT + "/jobs/" + automatic.id() + "/cancel", MANAGER, null));

        JsonNode original = print(CASHIER, cancelled, "ORDER_SLIP").get(0);
        assertThat(marked(original))
                .as("the only earlier slip was cancelled: nobody holds one")
                .isFalse();
        assertThat(original.get("reprintOf").asString())
                .as("it is still linked to the first job made for the document")
                .isEqualTo(automatic.id().toString());

        JsonNode again = print(CASHIER, cancelled, "ORDER_SLIP").get(0);
        assertThat(marked(again)).as("now one is out").isTrue();
    }

    @Test
    @DisplayName("the queue's reprint of a job that never printed is an original, and of one that did, a copy")
    void theQueueReprintFollowsTheSameRule() throws Exception {
        autoSlipsTo(counter);
        SeededOrder order = anOrder("A-024");
        confirm(order);
        JobRow automatic = store.listJobs(TENANT, LOCATION, List.of("QUEUED"), null, null, 10)
                .getFirst();
        ok(postJson(PRINT + "/jobs/" + automatic.id() + "/cancel", MANAGER, null));
        String reprint = PRINT + "/jobs/" + automatic.id() + "/reprint";
        String reason = "{\"reasonCode\":\"PAPER_JAM\"}";

        JsonNode first = created(postJson(reprint, CASHIER, reason));
        assertThat(marked(first)).as("the job it repeats never reached paper").isFalse();

        JsonNode second = created(postJson(reprint, CASHIER, reason));
        assertThat(marked(second)).as("the first reprint is out").isTrue();
    }
}
