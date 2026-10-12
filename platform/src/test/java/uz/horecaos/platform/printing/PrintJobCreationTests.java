package uz.horecaos.platform.printing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.kitchen.api.KitchenTicketAmended;
import uz.horecaos.platform.kitchen.api.KitchenTicketReleased;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.payments.api.FiscalDocumentIssued;
import uz.horecaos.platform.printing.PrintingWorld.LineSpec;
import uz.horecaos.platform.printing.PrintingWorld.SeededOrder;
import uz.horecaos.platform.printing.application.PrintDocumentCodec;
import uz.horecaos.platform.printing.application.PrintReconciler;
import uz.horecaos.platform.printing.application.PrinterService;
import uz.horecaos.platform.printing.application.PrinterService.PrinterDraft;
import uz.horecaos.platform.printing.application.RouteService;
import uz.horecaos.platform.printing.application.RouteService.RouteDraft;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;

/**
 * The facts that put paper in a queue (ADR 0154, Decision 8): created after commit, idempotently by a unique
 * key, reconciled by the sweeper, and only where a route, a printer and a tenant's switch say so. The real
 * listeners, the real ports and a real PostgreSQL; the order, its lines, its encrypted notes and its customer
 * are seeded the way a checkout leaves them.
 */
class PrintJobCreationTests extends PrintingSpringTest {

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
    private PrintReconciler reconciler;

    @Autowired
    @SuppressWarnings("NullAway")
    private ApplicationEventPublisher events;

    @Autowired
    @SuppressWarnings("NullAway")
    private PlatformTransactionManager transactions;

    @Autowired
    @SuppressWarnings("NullAway")
    private PrintDocumentCodec codec;

    @Autowired
    @SuppressWarnings("NullAway")
    private KitchenTicketService kitchenTickets;

    @Autowired
    @SuppressWarnings("NullAway")
    private MeterRegistry meters;

    private Agent agent;
    private PrinterRow counter;
    private PrinterRow grillPrinter;
    private PrinterRow barPrinter;

    @BeforeEach
    void printersExist() throws Exception {
        printingOn();
        agent = enrolAgent();
        counter = agentPrinter("counter", "Counter 80", 80);
        grillPrinter = agentPrinter("grill", "Grill pass 80", 80);
        barPrinter = agentPrinter("bar", "Bar 58", 58);
    }

    private PrinterRow agentPrinter(String alias, String name, int width) {
        store.upsertAdvert(
                TENANT,
                LOCATION,
                agent.deviceId(),
                alias,
                uz.horecaos.platform.printing.domain.PrinterStatus.ONLINE,
                null,
                clock.instant());
        return printers.register(
                TENANT, BRAND, LOCATION, new PrinterDraft(name, width, agent.deviceId(), alias), MANAGER);
    }

    private void route(PrintDocumentType type, @Nullable UUID station, PrinterRow printer, int copies, boolean auto) {
        routes.create(
                TENANT, BRAND, LOCATION, new RouteDraft(type, station, printer.id(), copies, auto, true), MANAGER);
    }

    /** The sweeper runs minutes after the fact it is looking for, as it does in service. */
    private PrintReconciler.Sweep sweep() {
        clock.advance(Duration.ofMinutes(5));
        return reconciler.sweepOnce();
    }

    private SeededOrder anOrder() {
        return world.order(
                LOCATION,
                "A-014",
                150_000L,
                true,
                LineSpec.of("Lagman", 2, 90_000)
                        .withModifier("Extra beef")
                        .withPreset("NO_ONION")
                        .withNote("allergic to cilantro"),
                LineSpec.of("Cola", 1, 8_000),
                LineSpec.of("Burger", 1, 40_000)
                        .inCombo(UUID.fromString("018fc154-0000-7000-8000-0000000000aa"), "Combo No.1"));
    }

    private void commitAndPublish(Object event) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(event));
    }

    private List<JobRow> jobs() {
        return store.listJobs(TENANT, LOCATION, List.of(), null, null, 100);
    }

    private List<JobRow> full(List<JobRow> listed) {
        return listed.stream()
                .map(job -> store.findJob(TENANT, job.id()).orElseThrow())
                .toList();
    }

    private UUID releasedTicket(SeededOrder order) {
        return world.releasedTicket(
                order,
                LOCATION,
                "A-014",
                Map.of(0, PrintingWorld.GRILL, 1, PrintingWorld.BAR, 2, PrintingWorld.GRILL),
                clock.instant());
    }

    // ------------------------------------------------------------- order slips

    @Test
    @DisplayName(
            "an order confirmation makes one slip per automatic slip route, and the same confirmation twice makes no more")
    void aConfirmationMakesOneSlipPerRouteAndOnlyOnce() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, true);
        route(PrintDocumentType.ORDER_SLIP, null, grillPrinter, 1, false);
        SeededOrder order = anOrder();
        // Two deliveries of the same confirmation: distinct signals (each has an id of its own) naming one order
        // at one version.
        commitAndPublish(PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant()));
        commitAndPublish(PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant()));

        List<JobRow> made = jobs();
        assertThat(made).as("one automatic route, one job, delivered twice").hasSize(1);
        JobRow job = made.getFirst();
        assertThat(job.documentType()).isEqualTo(PrintDocumentType.ORDER_SLIP);
        assertThat(job.printerId()).isEqualTo(counter.id());
        assertThat(job.origin()).isEqualTo("AUTO");
        assertThat(job.sourceType()).isEqualTo("ORDER");
        assertThat(job.sourceId()).isEqualTo(order.orderId());
        assertThat(job.sourceRevision()).isEqualTo(3);
        assertThat(job.status().name()).isEqualTo("QUEUED");
        assertThat(job.locale()).isEqualTo("ru");
        assertThat(job.tapeWidthMm()).isEqualTo(80);
    }

    @Test
    @DisplayName("a lost listener is found by the sweeper within the window, once, and the sweeper twice makes no more")
    void theSweeperFindsWhatTheListenerLost() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, true);
        anOrder();
        assertThat(jobs()).as("no event was delivered: the listener was lost").isEmpty();

        PrintReconciler.Sweep first = sweep();
        PrintReconciler.Sweep second = sweep();

        assertThat(first.jobsCreated()).isEqualTo(1);
        assertThat(second.jobsCreated()).as("the key already exists").isZero();
        assertThat(jobs()).hasSize(1);
    }

    @Test
    @DisplayName("an order confirmed and re-confirmed through the sweeper and the listener is still one job")
    void theListenerAndTheSweeperAgree() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, true);
        SeededOrder order = anOrder();

        commitAndPublish(PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant()));
        sweep();

        assertThat(jobs()).hasSize(1);
    }

    @Test
    @DisplayName(
            "an order that moves on after its slip was printed is not printed again: the key is the confirmation, not the order's live version")
    void aLaterTransitionDoesNotMakeAnotherSlip() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, true);
        SeededOrder order = anOrder();
        commitAndPublish(PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant()));
        assertThat(jobs())
                .as("the listener printed the slip at the confirmation")
                .hasSize(1);

        // The kitchen sets PREPARING, then READY: each bumps the order's version exactly as
        // JdbcOrderStore.transition does, and neither is a confirmation.
        for (String status : List.of("PREPARING", "READY")) {
            jdbc.sql("""
                            UPDATE ordering.orders SET status = :status, version = version + 1
                             WHERE tenant_id = :tenantId AND id = :orderId
                            """)
                    .param("status", status)
                    .param("tenantId", TENANT)
                    .param("orderId", order.orderId())
                    .update();
            assertThat(sweep().jobsCreated())
                    .as("the order is %s; the sweeper finds the slip of its confirmation", status)
                    .isZero();
        }

        assertThat(jobs()).as("one slip for one confirmation").hasSize(1);
    }

    @Test
    @DisplayName(
            "an order born confirmed (no transition, no event) is found by the sweeper once, and not again however far it moves on")
    void anOrderBornConfirmedIsSweptOnce() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, true);
        SeededOrder order = world.bornConfirmedOrder(LOCATION, "D-001", LineSpec.of("Plov", 1, 50_000));

        assertThat(sweep().jobsCreated())
                .as("nothing else ever prints it: no event was published")
                .isEqualTo(1);
        assertThat(jobs().getFirst().sourceRevision())
                .as("its confirmation is its creation")
                .isEqualTo(1);

        for (String status : List.of("PREPARING", "READY", "FULFILLING")) {
            jdbc.sql("""
                            UPDATE ordering.orders SET status = :status, version = version + 1
                             WHERE tenant_id = :tenantId AND id = :orderId
                            """)
                    .param("status", status)
                    .param("tenantId", TENANT)
                    .param("orderId", order.orderId())
                    .update();
            assertThat(sweep().jobsCreated()).as("the order is %s", status).isZero();
        }
        assertThat(jobs()).hasSize(1);
    }

    @Test
    @DisplayName("a route with auto-print off makes no job, and a document with no route prints nowhere")
    void nothingPrintsByDefault() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, false);
        SeededOrder order = anOrder();

        commitAndPublish(PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant()));
        UUID ticket = releasedTicket(order);
        commitAndPublish(new KitchenTicketReleased(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant()));

        assertThat(jobs()).isEmpty();
    }

    @Test
    @DisplayName("with feature.printing off for the tenant nothing is queued, however the routes are set")
    void theFlagIsTheFirstSwitch() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, true);
        printingOff();
        SeededOrder order = anOrder();

        commitAndPublish(PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant()));
        sweep();

        assertThat(jobs()).isEmpty();
    }

    @Test
    @DisplayName("a second copy is a second job, marked as a copy; the first is not")
    void copiesAreMarked() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 2, true);
        SeededOrder order = anOrder();

        commitAndPublish(PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant()));

        List<JobRow> made = full(jobs()).stream()
                .sorted(java.util.Comparator.comparingInt(JobRow::copyNo))
                .toList();
        assertThat(made).extracting(JobRow::copyNo).containsExactly(1, 2);
        assertThat(PrintingSteps.documentOf(made.get(0), protection, codec).copyMarker())
                .isFalse();
        assertThat(PrintingSteps.documentOf(made.get(1), protection, codec).copyMarker())
                .isTrue();
    }

    // -------------------------------------------------------------- tickets

    @Test
    @DisplayName(
            "a release makes a ticket per station with items, each on its station's printer; none carries another station's lines")
    void aReleaseMakesATicketPerStation() {
        route(PrintDocumentType.KITCHEN_TICKET, PrintingWorld.GRILL, grillPrinter, 1, true);
        route(PrintDocumentType.KITCHEN_TICKET, PrintingWorld.BAR, barPrinter, 1, true);
        SeededOrder order = anOrder();
        UUID ticket = releasedTicket(order);

        commitAndPublish(new KitchenTicketReleased(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant()));

        List<JobRow> made = full(jobs());
        assertThat(made).hasSize(2);
        JobRow grill = made.stream()
                .filter(job -> job.printerId().equals(grillPrinter.id()))
                .findFirst()
                .orElseThrow();
        JobRow bar = made.stream()
                .filter(job -> job.printerId().equals(barPrinter.id()))
                .findFirst()
                .orElseThrow();
        assertThat(grill.stationId()).isEqualTo(PrintingWorld.GRILL);
        assertThat(bar.stationId()).isEqualTo(PrintingWorld.BAR);
        assertThat(bar.tapeWidthMm()).isEqualTo(58);

        String grillWords = PrintingSteps.jsonOf(grill, protection, codec);
        String barWords = PrintingSteps.jsonOf(bar, protection, codec);
        assertThat(grillWords).contains("Lagman").contains("Burger").doesNotContain("Cola");
        assertThat(barWords).contains("Cola").doesNotContain("Lagman").doesNotContain("Burger");
        assertThat(grill.expiresAt())
                .as("a ticket waits 30 minutes, the default, and no longer")
                .isEqualTo(grill.queuedAt().plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("a station with no route of its own prints on the route with no station")
    void aStationFallsBackToTheStationlessRoute() {
        route(PrintDocumentType.KITCHEN_TICKET, PrintingWorld.GRILL, grillPrinter, 1, true);
        route(PrintDocumentType.KITCHEN_TICKET, null, counter, 1, true);
        SeededOrder order = anOrder();
        UUID ticket = releasedTicket(order);

        commitAndPublish(new KitchenTicketReleased(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant()));

        Map<UUID, UUID> printerOfStation = new java.util.HashMap<>();
        jobs().forEach(job -> printerOfStation.put(job.stationId(), job.printerId()));
        assertThat(printerOfStation)
                .containsEntry(PrintingWorld.GRILL, grillPrinter.id())
                .containsEntry(PrintingWorld.BAR, counter.id());
    }

    @Test
    @DisplayName("the same release twice, and the sweeper after it, make a ticket once")
    void aReleaseIsIdempotent() {
        route(PrintDocumentType.KITCHEN_TICKET, null, grillPrinter, 1, true);
        SeededOrder order = anOrder();
        UUID ticket = releasedTicket(order);
        var released = new KitchenTicketReleased(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant());

        commitAndPublish(released);
        commitAndPublish(released);
        sweep();

        assertThat(jobs())
                .as("two stations on one printer: one ticket each, never a third")
                .hasSize(2);
    }

    @Test
    @DisplayName(
            "an amendment after release makes an amendment ticket once; a replay of it makes none; the sweeper agrees")
    void anAmendmentPrintsOnce() {
        route(PrintDocumentType.KITCHEN_TICKET, null, grillPrinter, 1, true);
        SeededOrder order = anOrder();
        UUID ticket = releasedTicket(order);
        commitAndPublish(new KitchenTicketReleased(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant()));
        int afterRelease = jobs().size();

        jdbc.sql("UPDATE kitchen.tickets SET amendment_revision = 1 WHERE id = :id")
                .param("id", ticket)
                .update();
        var amended = new KitchenTicketAmended(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), 1, clock.instant());
        commitAndPublish(amended);
        commitAndPublish(amended);
        sweep();

        List<JobRow> all = full(jobs());
        assertThat(all).hasSize(afterRelease * 2);
        List<JobRow> amendments =
                all.stream().filter(job -> job.sourceRevision() == 1).toList();
        assertThat(amendments).hasSize(afterRelease);
        assertThat(String.join(
                        "\n",
                        amendments.stream()
                                .map(job -> PrintingSteps.jsonOf(job, protection, codec))
                                .toList()))
                .contains("ИЗМЕНЕНИЕ ЗАКАЗА");
        assertThat(all.stream().filter(job -> job.sourceRevision() == 0))
                .allSatisfy(job ->
                        assertThat(PrintingSteps.jsonOf(job, protection, codec)).doesNotContain("ИЗМЕНЕНИЕ"));
    }

    @Test
    @DisplayName("the kitchen's own release and amendment signals reach printing: a real ticket, a real amendment")
    void theKitchenPublishesTheSignals() {
        route(PrintDocumentType.KITCHEN_TICKET, null, grillPrinter, 1, true);
        world.fallbackStation(UUID.fromString("018fc154-1000-7000-8000-0000000000d9"), LOCATION);
        // The two stations the world seeds hold no fallback, so the real open() routes everything to the one
        // fallback station added here.
        SeededOrder order = world.order(LOCATION, "B-201", null, false, LineSpec.of("Plov", 1, 50_000));

        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> kitchenTickets.open(TENANT, order.orderId(), ReleaseMode.AUTO_ON_CONFIRM));

        assertThat(jobs())
                .as("a real release was published and heard after commit")
                .hasSize(1);
        UUID ticketId = jdbc.sql("SELECT id FROM kitchen.tickets WHERE order_id = :id")
                .param("id", order.orderId())
                .query(UUID.class)
                .single();

        // An amendment appends a line the ticket has no item for.
        jdbc.sql("""
                INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number, source_product_id,
                    source_variant_id, product_name_snapshot, quantity, unit_amount_minor, base_amount_minor,
                    final_amount_minor, tax_amount_minor)
                VALUES (:id, :tenantId, :orderId, 2, :product, :variant, 'Tea', 1, 5000, 5000, 5000, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("orderId", order.orderId())
                .param("product", UUID.randomUUID())
                .param("variant", UUID.randomUUID())
                .update();
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> kitchenTickets.syncAmendedLines(TENANT, order.orderId()));

        assertThat(jdbc.sql("SELECT amendment_revision FROM kitchen.tickets WHERE id = :id")
                        .param("id", ticketId)
                        .query(Integer.class)
                        .single())
                .as("the kitchen counted the amendment")
                .isEqualTo(1);
        List<JobRow> all = jobs();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(JobRow::sourceRevision).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    @DisplayName(
            "an amendment to a ticket still on hold is not an amendment ticket: nothing was printed from the old one")
    void anAmendmentOnHoldCountsForNothing() {
        world.fallbackStation(UUID.fromString("018fc154-1000-7000-8000-0000000000d9"), LOCATION);
        SeededOrder order = world.order(LOCATION, "B-202", null, false, LineSpec.of("Plov", 1, 50_000));
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> kitchenTickets.open(TENANT, order.orderId(), ReleaseMode.MANUAL_HOLD));
        jdbc.sql("""
                INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number, source_product_id,
                    source_variant_id, product_name_snapshot, quantity, unit_amount_minor, base_amount_minor,
                    final_amount_minor, tax_amount_minor)
                VALUES (:id, :tenantId, :orderId, 2, :product, :variant, 'Tea', 1, 5000, 5000, 5000, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("orderId", order.orderId())
                .param("product", UUID.randomUUID())
                .param("variant", UUID.randomUUID())
                .update();

        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> kitchenTickets.syncAmendedLines(TENANT, order.orderId()));

        assertThat(jdbc.sql("SELECT amendment_revision FROM kitchen.tickets WHERE order_id = :id")
                        .param("id", order.orderId())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    // -------------------------------------------------------------- receipts

    @Test
    @DisplayName(
            "an issued sale makes a fiscal receipt per automatic receipt route; any other state, or a refund, makes none")
    void onlyAnIssuedSaleMakesAReceipt() {
        route(PrintDocumentType.FISCAL_RECEIPT, null, counter, 1, true);
        SeededOrder order = anOrder();
        // A second order whose sale the provider has not yet answered: one sale per order and tender is a rule.
        SeededOrder pending = world.order(LOCATION, "A-015", 50_000L, false, LineSpec.of("Tea", 1, 50_000));
        UUID issued = world.fiscalDocument(order, "SALE", "ISSUED");
        UUID submitted = world.fiscalDocument(pending, "SALE", "SUBMITTED");
        UUID refund = world.fiscalDocument(order, "REFUND", "ISSUED");

        commitAndPublish(
                new FiscalDocumentIssued(UUID.randomUUID(), TENANT, pending.orderId(), submitted, clock.instant()));
        commitAndPublish(new FiscalDocumentIssued(UUID.randomUUID(), TENANT, order.orderId(), refund, clock.instant()));
        assertThat(jobs())
                .as("the automatic path creates nothing for any other state")
                .isEmpty();

        commitAndPublish(new FiscalDocumentIssued(UUID.randomUUID(), TENANT, order.orderId(), issued, clock.instant()));
        commitAndPublish(new FiscalDocumentIssued(UUID.randomUUID(), TENANT, order.orderId(), issued, clock.instant()));
        sweep();

        List<JobRow> made = full(jobs());
        assertThat(made).hasSize(1);
        JobRow receipt = made.getFirst();
        assertThat(receipt.documentType()).isEqualTo(PrintDocumentType.FISCAL_RECEIPT);
        assertThat(receipt.sourceType()).isEqualTo("FISCAL_DOCUMENT");
        assertThat(receipt.sourceId()).isEqualTo(issued);
        String words = PrintingSteps.jsonOf(receipt, protection, codec);
        assertThat(words)
                .contains("1234567")
                .contains("987654321098")
                .contains("Sinov MCHJ")
                .contains("123456789");
    }

    // ------------------------------------------------------------- privacy

    @Test
    @DisplayName(
            "no stored document names the order's customer: the order is seeded with a name, a phone and an address")
    void noDocumentNamesTheCustomer() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, true);
        route(PrintDocumentType.KITCHEN_TICKET, null, grillPrinter, 1, true);
        route(PrintDocumentType.FISCAL_RECEIPT, null, counter, 1, true);
        SeededOrder order = anOrder();
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_customer_snapshots WHERE order_id = :id")
                        .param("id", order.orderId())
                        .query(Long.class)
                        .single())
                .as("the order really has a customer to leak")
                .isEqualTo(1);
        UUID ticket = releasedTicket(order);
        UUID receipt = world.fiscalDocument(order, "SALE", "ISSUED");

        commitAndPublish(PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant()));
        commitAndPublish(new KitchenTicketReleased(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant()));
        commitAndPublish(
                new FiscalDocumentIssued(UUID.randomUUID(), TENANT, order.orderId(), receipt, clock.instant()));

        List<JobRow> made = full(jobs());
        assertThat(made)
                .extracting(JobRow::documentType)
                .contains(
                        PrintDocumentType.ORDER_SLIP,
                        PrintDocumentType.KITCHEN_TICKET,
                        PrintDocumentType.FISCAL_RECEIPT);
        for (JobRow job : made) {
            String stored = job.protectedDocument();
            String json = PrintingSteps.jsonOf(job, protection, codec);
            assertThat(json)
                    .as(job.documentType() + " carries no customer")
                    .doesNotContain("Azizbek")
                    .doesNotContain("Karimov")
                    .doesNotContain("998901234567")
                    .doesNotContain("Mustaqillik");
            assertThat(stored)
                    .as("and what is stored is ciphertext, not the document")
                    .doesNotContain("Lagman");
        }
        // The note a customer typed is on a kitchen ticket (the grill's) and on nothing else.
        List<JobRow> carryingTheNote = made.stream()
                .filter(job -> PrintingSteps.jsonOf(job, protection, codec).contains("allergic to cilantro"))
                .toList();
        assertThat(carryingTheNote).as("the note reaches the kitchen").isNotEmpty();
        assertThat(carryingTheNote)
                .as("and only the kitchen")
                .allSatisfy(job -> assertThat(job.documentType()).isEqualTo(PrintDocumentType.KITCHEN_TICKET));
    }

    @Test
    @DisplayName("a fault in printing never rolls back the business transaction, and is counted")
    void aFaultInPrintingNeverRollsBackTheBusiness() {
        route(PrintDocumentType.ORDER_SLIP, null, counter, 1, true);
        SeededOrder order = anOrder();
        double before = meters.counter("horecaos.printing.enqueue.failures", "type", "ORDER_SLIP")
                .count();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("UPDATE ordering.orders SET status = 'PREPARING' WHERE id = :id")
                    .param("id", order.orderId())
                    .update();
            // The confirmation names an order the database does not hold, so building its slip throws: a fault in
            // printing and nothing else.
            events.publishEvent(
                    PrintingSteps.confirmed(TENANT, BRAND, LOCATION, UUID.randomUUID(), 3, clock.instant()));
        });

        assertThat(jdbc.sql("SELECT status FROM ordering.orders WHERE id = :id")
                        .param("id", order.orderId())
                        .query(String.class)
                        .single())
                .as("the order's own change committed")
                .isEqualTo("PREPARING");
        assertThat(jobs()).isEmpty();
        assertThat(meters.counter("horecaos.printing.enqueue.failures", "type", "ORDER_SLIP")
                        .count())
                .isGreaterThan(before);
    }
}
