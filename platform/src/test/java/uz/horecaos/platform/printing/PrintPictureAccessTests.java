package uz.horecaos.platform.printing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.printing.PrintingWorld.LineSpec;
import uz.horecaos.platform.printing.PrintingWorld.SeededOrder;
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
 * Who may fetch a picture (ADR 0154, Decision 10): a workstation prints the preview bitmap through the browser,
 * so the person who hands a job to the browser has to be able to fetch the picture of that job -- and a cashier,
 * who holds {@code print.reprint} and not {@code print.read}, is exactly the person who does. What she may not do
 * is what {@code print.read} withholds from the floor: a picture of any document of the branch.
 */
class PrintPictureAccessTests extends PrintingSpringTest {

    private static final String ORDERS =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/";
    private static final String PREVIEW = PRINT + "/documents/preview";

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
    private ApplicationEventPublisher events;

    @Autowired
    @SuppressWarnings("NullAway")
    private PlatformTransactionManager transactions;

    /** The front desk's browser. */
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

    private SeededOrder anOrder() {
        return world.order(
                LOCATION, "A-030", 150_000L, false, LineSpec.of("Lagman", 2, 90_000), LineSpec.of("Cola", 1, 8_000));
    }

    /** The order action: a slip handed to the front desk's browser by {@code subject}. */
    private String handOff(String subject, SeededOrder order) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        JsonNode made = created(postJson(
                ORDERS + order.orderId() + "/print-jobs",
                subject,
                "{\"documentType\":\"ORDER_SLIP\",\"printerId\":\"" + desk.id()
                        + "\",\"reasonCode\":\"CUSTOMER_REQUEST\"}"));
        assertThat(made.get(0).get("status").asString()).isEqualTo("HANDED_OFF");
        return made.get(0).get("id").asString();
    }

    /** What the order panel asks for after the job is handed off: the picture of that job. */
    private MvcResult pictureOf(String subject, String jobId) throws Exception {
        return call(getAs(PRINT + "/jobs/" + jobId + "/picture", subject));
    }

    /** The manager's preview of a stored job, which print.read gives and print.reprint does not. */
    private MvcResult previewOf(String subject, String jobId) throws Exception {
        return call(postJson(PREVIEW, subject, "{\"jobId\":\"" + jobId + "\"}"));
    }

    private static boolean isPng(MvcResult result) {
        byte[] bytes = result.getResponse().getContentAsByteArray();
        return bytes.length > 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    @Test
    @DisplayName(
            "a cashier fetches the picture of the job she handed to the browser, the same picture a manager's preview gives of it")
    void aCashierFetchesThePictureOfHerOwnHandOff() throws Exception {
        String job = handOff(CASHIER, anOrder());

        MvcResult hers = pictureOf(CASHIER, job);
        assertThat(hers.getResponse().getStatus()).isEqualTo(200);
        assertThat(isPng(hers)).isTrue();
        assertThat(ImageIO.read(new ByteArrayInputStream(hers.getResponse().getContentAsByteArray()))
                        .getWidth())
                .as("the 80 mm roll")
                .isEqualTo(576);
        assertThat(previewOf(MANAGER, job).getResponse().getContentAsByteArray())
                .as("one bitmap serves the preview, the browser and the printer")
                .isEqualTo(hers.getResponse().getContentAsByteArray());
    }

    @Test
    @DisplayName(
            "her access reaches no further than her own hand-off: another person's job, a queued job and a job that does not exist are not found, and the preview is still print.read")
    void andNoFurther() throws Exception {
        SeededOrder order = anOrder();
        String mine = handOff(CASHIER, order);
        String managers = handOff(MANAGER, order);
        routes.create(
                TENANT,
                BRAND,
                LOCATION,
                new RouteDraft(PrintDocumentType.ORDER_SLIP, null, counter.id(), 1, true, true),
                MANAGER);
        SeededOrder another = world.order(LOCATION, "A-031", null, false, LineSpec.of("Plov", 1, 50_000));
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> events.publishEvent(
                        PrintingSteps.confirmed(TENANT, BRAND, LOCATION, another.orderId(), 3, clock.instant())));
        List<JobRow> queued = store.listJobs(TENANT, LOCATION, List.of("QUEUED"), null, null, 10);
        assertThat(queued).as("an automatic slip waits in the queue").hasSize(1);

        assertThat(pictureOf(CASHIER, mine).getResponse().getStatus()).isEqualTo(200);
        assertThat(pictureOf(CASHIER, managers).getResponse().getStatus())
                .as("somebody else's hand-off")
                .isEqualTo(404);
        assertThat(pictureOf(CASHIER, queued.getFirst().id().toString())
                        .getResponse()
                        .getStatus())
                .as("a job still in the queue")
                .isEqualTo(404);
        assertThat(pictureOf(CASHIER, UUID.randomUUID().toString())
                        .getResponse()
                        .getStatus())
                .as("a job that does not exist answers like one that is not hers: no way to ask which exist")
                .isEqualTo(404);

        assertThat(previewOf(CASHIER, mine).getResponse().getStatus())
                .as("the preview of any job is print.read, which the floor does not hold")
                .isEqualTo(403);
        assertThat(status(postJson(
                        PREVIEW, CASHIER, "{\"documentType\":\"ORDER_SLIP\",\"orderId\":\"" + order.orderId() + "\"}")))
                .isEqualTo(403);
        assertThat(previewOf(MANAGER, mine).getResponse().getStatus())
                .as("and a manager still reads any job's picture")
                .isEqualTo(200);
        assertThat(pictureOf(SIBLING_MANAGER, mine).getResponse().getStatus())
                .as("a manager of another branch has none of this branch's")
                .isIn(403, 404);
    }

    @Test
    @DisplayName("a cashier's test page on the browser printer is hers to fetch too")
    void aTestPageSheAskedFor() throws Exception {
        String testPage = created(postJson(PRINT + "/printers/" + desk.id() + "/test-page", CASHIER, null))
                .get("id")
                .asString();

        assertThat(pictureOf(CASHIER, testPage).getResponse().getStatus()).isEqualTo(200);
    }
}
