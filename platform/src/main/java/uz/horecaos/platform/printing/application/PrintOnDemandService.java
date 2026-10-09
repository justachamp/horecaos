package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.PrintableTicket;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.Station;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.OrderSummary;
import uz.horecaos.platform.payments.api.FiscalReceiptSource;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.FiscalDocumentRef;
import uz.horecaos.platform.printing.application.PrintAudit.Subject;
import uz.horecaos.platform.printing.application.PrintDocumentFactory.Built;
import uz.horecaos.platform.printing.application.PrintJobFactory.Spec;
import uz.horecaos.platform.printing.application.documents.KitchenTicketDocument;
import uz.horecaos.platform.printing.domain.JobStatus;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.RouteRow;
import uz.horecaos.platform.printing.render.DocumentRenderer;
import uz.horecaos.platform.printing.render.RenderContext;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Paper on request (ADR 0154, Decisions 7 and 10): a test page, a reprint of an order's slip, receipt or
 * kitchen ticket, and the preview bitmap of what a job would print.
 *
 * <p><strong>A reprint is a new job, regenerated from the current facts and marked as a copy</strong> -- not
 * replayed from stored bytes -- linked to the first print of the same document, naming the person who asked
 * and a reason code, and written to the activity log as a {@code BUSINESS} fact. A reprinted kitchen ticket
 * after an amendment shows the order as it is now.
 *
 * <p>A browser printer's job is recorded as {@code HANDED_OFF}, which is all the platform can know about
 * paper it did not send: the workstation prints the preview bitmap at the roll's width. An agent printer's is
 * queued like any other. A fiscal receipt is refused for any document but an {@code ISSUED} {@code SALE},
 * naming the state; a refund's is refused naming the type, never silently.
 */
@Service
public class PrintOnDemandService {

    /** The closed set of reasons a person gives for asking for paper again. */
    public enum Reason {
        CUSTOMER_REQUEST,
        PAPER_JAM,
        PRINTER_FAULT,
        KITCHEN_REQUEST,
        AMENDED_ORDER,
        TEST,
        OTHER
    }

    private final TenantRlsSession rls;
    private final JdbcPrintingStore store;
    private final PrintingGuard guard;
    private final PrintDocumentFactory documents;
    private final PrintDocumentCodec codec;
    private final PrintJobFactory jobs;
    private final PrintingPolicy policy;
    private final KitchenTicketPrintSource tickets;
    private final FiscalReceiptSource receipts;
    private final OrderDirectory orders;
    private final FieldProtection protection;
    private final LogoBitmaps logos;
    private final PrintAudit audit;
    private final PrintingMetrics metrics;
    private final Clock clock;
    private final DocumentRenderer renderer = new DocumentRenderer();

    public PrintOnDemandService(
            TenantRlsSession rls,
            JdbcPrintingStore store,
            PrintingGuard guard,
            PrintDocumentFactory documents,
            PrintDocumentCodec codec,
            PrintJobFactory jobs,
            PrintingPolicy policy,
            KitchenTicketPrintSource tickets,
            FiscalReceiptSource receipts,
            OrderDirectory orders,
            FieldProtection protection,
            LogoBitmaps logos,
            PrintAudit audit,
            PrintingMetrics metrics,
            Clock clock) {
        this.rls = rls;
        this.store = store;
        this.guard = guard;
        this.documents = documents;
        this.codec = codec;
        this.jobs = jobs;
        this.policy = policy;
        this.tickets = tickets;
        this.receipts = receipts;
        this.orders = orders;
        this.protection = protection;
        this.logos = logos;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ================================================================ reprint

    /**
     * Reprints one document of an order. {@code printerId} chooses the printer; without it the document goes
     * where its route says, and a document with no route is refused saying so -- it prints nowhere by default.
     * A kitchen ticket makes one job per station that has items.
     */
    @Transactional
    public List<JobRow> reprint(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            PrintDocumentType type,
            @Nullable UUID printerId,
            Reason reason,
            String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabled(tenantId, brandId, locationId);
        if (!type.routable()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Ask for a kitchen ticket, an order slip or a fiscal receipt");
        }
        OrderSummary order = orders.summary(tenantId, orderId)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
        PrinterRow chosen = printerId == null ? null : activePrinter(tenantId, locationId, printerId);
        Instant now = clock.instant();
        List<JobRow> made = new ArrayList<>();

        switch (type) {
            case ORDER_SLIP -> {
                PrinterRow printer = chosen != null ? chosen : routePrinter(tenantId, locationId, type, null);
                Built built = documents.orderSlip(printer, orderId, true);
                made.add(manual(
                        printer, type, "ORDER", orderId, order.version(), null, built, reason, actorSubject, now));
            }
            case FISCAL_RECEIPT -> {
                UUID documentId = receiptDocumentOf(tenantId, orderId);
                PrinterRow printer = chosen != null ? chosen : routePrinter(tenantId, locationId, type, null);
                Built built = documents.fiscalReceipt(printer, documentId, true);
                made.add(manual(
                        printer, type, "FISCAL_DOCUMENT", documentId, 0, null, built, reason, actorSubject, now));
            }
            case KITCHEN_TICKET -> {
                PrintableTicket ticket = tickets.findByOrder(tenantId, orderId)
                        .filter(found -> found.locationId().equals(locationId))
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.UNPROCESSABLE_STATE,
                                "This order has no kitchen ticket",
                                Map.of("reason", "NO_KITCHEN_TICKET")));
                if (ticket.releasedAt() == null) {
                    throw new ApiException(
                            ErrorCode.UNPROCESSABLE_STATE,
                            "The kitchen ticket has not been released yet",
                            Map.of("reason", "TICKET_NOT_RELEASED"));
                }
                for (Station station : KitchenTicketDocument.stationsWithItems(ticket)) {
                    PrinterRow printer =
                            chosen != null ? chosen : routePrinter(tenantId, locationId, type, station.id());
                    Built built = documents.kitchenTicket(
                            printer, ticket.ticketId(), station.id(), true, ticket.amendmentRevision());
                    made.add(manual(
                            printer,
                            type,
                            "KITCHEN_TICKET",
                            ticket.ticketId(),
                            ticket.amendmentRevision(),
                            station.id(),
                            built,
                            reason,
                            actorSubject,
                            now));
                }
            }
            case TEST_PAGE -> throw new IllegalStateException("a test page is not an order's document");
        }

        for (JobRow job : made) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("orderId", orderId.toString());
            after.put("documentType", job.documentType().name());
            after.put("printerId", job.printerId().toString());
            after.put("reasonCode", reason.name());
            after.put("status", job.status().name());
            audit.created(
                    "printing.job.reprinted",
                    AuditClass.BUSINESS,
                    new Subject(
                            tenantId, brandId, locationId, "printing.print_job", job.id(), actorSubject, reason.name()),
                    after);
        }
        return made;
    }

    /**
     * Prints the document of a job in the queue again (ADR 0154, "Print queue ... with reprint and cancel"): a dead
     * job, an expired kitchen ticket, a paper jam. It is a new job, regenerated from the facts as they are now,
     * marked as a copy, linked to the first print of that document and recorded with the person and the reason; it
     * never resurrects the old job. Without {@code printerId} the document goes to the printer the old job was
     * for, which must still be active.
     *
     * <p>The job names its source (an order, a kitchen ticket and station, a fiscal document), so the queue can
     * reprint a document whose order the queue does not know. A fiscal receipt is rebuilt through the same
     * builder as the first print and so is refused, naming the state, if its document is no longer an
     * {@code ISSUED} {@code SALE}.
     */
    @Transactional
    public JobRow reprintJob(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID jobId,
            @Nullable UUID printerId,
            Reason reason,
            String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabled(tenantId, brandId, locationId);
        JobRow old = store.findJob(tenantId, jobId)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such print job"));
        if (old.documentType() == PrintDocumentType.TEST_PAGE || old.sourceId() == null) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A test page has no document to print again; ask for a new test page on the printer",
                    Map.of(
                            "reason",
                            "JOB_NOT_REPRINTABLE",
                            "documentType",
                            old.documentType().name()));
        }
        if (old.status() == JobStatus.QUEUED || old.status() == JobStatus.CLAIMED) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "That job is still on its way to paper; wait for it, or cancel it first",
                    Map.of("reason", "JOB_NOT_FINISHED", "state", old.status().name()));
        }
        PrinterRow printer = activePrinter(tenantId, locationId, printerId != null ? printerId : old.printerId());
        UUID sourceId = old.sourceId();
        Instant now = clock.instant();
        JobRow made;
        switch (old.documentType()) {
            case ORDER_SLIP -> {
                OrderSummary order = orders.summary(tenantId, sourceId)
                        .filter(found -> found.locationId().equals(locationId))
                        .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
                Built built = documents.orderSlip(printer, sourceId, true);
                made = manual(
                        printer,
                        old.documentType(),
                        "ORDER",
                        sourceId,
                        order.version(),
                        null,
                        built,
                        reason,
                        actorSubject,
                        now);
            }
            case FISCAL_RECEIPT -> {
                Built built = documents.fiscalReceipt(printer, sourceId, true);
                made = manual(
                        printer,
                        old.documentType(),
                        "FISCAL_DOCUMENT",
                        sourceId,
                        0,
                        null,
                        built,
                        reason,
                        actorSubject,
                        now);
            }
            case KITCHEN_TICKET -> {
                PrintableTicket ticket = tickets.find(tenantId, sourceId)
                        .filter(found -> found.locationId().equals(locationId))
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.UNPROCESSABLE_STATE,
                                "This order has no kitchen ticket",
                                Map.of("reason", "NO_KITCHEN_TICKET")));
                UUID stationId = old.stationId();
                if (stationId == null) {
                    throw new ApiException(
                            ErrorCode.UNPROCESSABLE_STATE,
                            "That kitchen job names no station",
                            Map.of("reason", "JOB_NOT_REPRINTABLE"));
                }
                Built built = documents.kitchenTicket(printer, sourceId, stationId, true, ticket.amendmentRevision());
                made = manual(
                        printer,
                        old.documentType(),
                        "KITCHEN_TICKET",
                        sourceId,
                        ticket.amendmentRevision(),
                        stationId,
                        built,
                        reason,
                        actorSubject,
                        now);
            }
            default -> throw new IllegalStateException("not a reprintable document: " + old.documentType());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reprintOfJobId", old.id().toString());
        after.put("sourceType", old.sourceType());
        after.put("sourceId", sourceId.toString());
        after.put("documentType", made.documentType().name());
        after.put("printerId", made.printerId().toString());
        after.put("reasonCode", reason.name());
        after.put("status", made.status().name());
        audit.created(
                "printing.job.reprinted",
                AuditClass.BUSINESS,
                new Subject(
                        tenantId, brandId, locationId, "printing.print_job", made.id(), actorSubject, reason.name()),
                after);
        return made;
    }

    /** Asks for a test page on one printer: the roll's width, a ruler, every glyph, a QR and the printer's name. */
    @Transactional
    public JobRow testPage(UUID tenantId, UUID brandId, UUID locationId, UUID printerId, String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabled(tenantId, brandId, locationId);
        PrinterRow printer = activePrinter(tenantId, locationId, printerId);
        UUID agentDevice = printer.agentDeviceId();
        String agentKey = printer.agentPrinterKey();
        String build = agentDevice == null || agentKey == null
                ? null
                : store.findAdvert(tenantId, agentDevice, agentKey)
                        .map(advert -> advert.agentBuild())
                        .orElse(null);
        Built built = documents.testPage(printer, build);
        Instant now = clock.instant();
        JobRow job = manual(
                printer, PrintDocumentType.TEST_PAGE, "NONE", null, 0, null, built, Reason.TEST, actorSubject, now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("documentType", PrintDocumentType.TEST_PAGE.name());
        after.put("printerId", printer.id().toString());
        after.put("reasonCode", Reason.TEST.name());
        audit.created(
                "printing.job.reprinted",
                AuditClass.BUSINESS,
                new Subject(
                        tenantId,
                        brandId,
                        locationId,
                        "printing.print_job",
                        job.id(),
                        actorSubject,
                        Reason.TEST.name()),
                after);
        return job;
    }

    private JobRow manual(
            PrinterRow printer,
            PrintDocumentType type,
            String sourceType,
            @Nullable UUID sourceId,
            int revision,
            @Nullable UUID stationId,
            Built built,
            Reason reason,
            String actorSubject,
            Instant now) {
        UUID firstPrint = sourceId == null
                ? null
                : store.jobsOfSource(printer.tenantId(), sourceType, sourceId).stream()
                        .filter(job ->
                                job.documentType() == type && java.util.Objects.equals(job.stationId(), stationId))
                        .min(Comparator.comparing(JobRow::queuedAt))
                        .map(JobRow::id)
                        .orElse(null);
        Duration expiry = type == PrintDocumentType.KITCHEN_TICKET
                ? Duration.ofMinutes(
                        policy.kitchenTicketExpireMinutes(printer.tenantId(), printer.brandId(), printer.locationId()))
                : PrintJobFactory.DEFAULT_EXPIRY;
        JobRow job = jobs.create(
                new Spec(
                        printer,
                        null,
                        type,
                        sourceType,
                        sourceId,
                        revision,
                        stationId,
                        1,
                        "MANUAL",
                        actorSubject,
                        reason.name(),
                        firstPrint,
                        printer.browser() ? JobStatus.HANDED_OFF : JobStatus.QUEUED,
                        expiry),
                built,
                now);
        if (!store.insertJob(job)) {
            throw new IllegalStateException("A manual job has no idempotency key and always inserts");
        }
        if (job.status() == JobStatus.HANDED_OFF) {
            metrics.jobEnded(JobStatus.HANDED_OFF, type);
        }
        return job;
    }

    private PrinterRow activePrinter(UUID tenantId, UUID locationId, UUID printerId) {
        PrinterRow printer = store.findPrinter(tenantId, printerId)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such printer"));
        if (!printer.active()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "That printer is retired", Map.of("reason", "PRINTER_RETIRED"));
        }
        return printer;
    }

    /**
     * The printer a document goes to by route: a station's own route first, then the route with no station.
     * A document with no route prints nowhere, and the refusal says so.
     */
    private PrinterRow routePrinter(UUID tenantId, UUID locationId, PrintDocumentType type, @Nullable UUID stationId) {
        List<RouteRow> routes = store.activeRoutes(tenantId, locationId, type);
        Optional<RouteRow> route = routes.stream()
                .filter(candidate -> stationId != null && stationId.equals(candidate.stationId()))
                .findFirst();
        if (route.isEmpty()) {
            route = routes.stream()
                    .filter(candidate -> candidate.stationId() == null)
                    .findFirst();
        }
        return route.flatMap(found -> store.findPrinter(tenantId, found.printerId()))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "No printer is routed for this document at this branch; choose a printer",
                        Map.of("reason", "NO_ROUTE", "documentType", type.name())));
    }

    /**
     * The fiscal document a receipt reprint is made from: the order's newest {@code ISSUED} {@code SALE}.
     * When there is none the refusal names what there is -- a refund's receipt is not printed in this version
     * and says so, and a document still on its way says what state it is in.
     */
    private UUID receiptDocumentOf(UUID tenantId, UUID orderId) {
        List<FiscalDocumentRef> all = receipts.forOrder(tenantId, orderId);
        Optional<FiscalDocumentRef> issuedSale = all.stream()
                .filter(doc -> "SALE".equals(doc.type()) && "ISSUED".equals(doc.status()))
                .reduce((older, newer) -> newer);
        if (issuedSale.isPresent()) {
            return issuedSale.get().documentId();
        }
        if (all.isEmpty()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This order has no fiscal document, so there is no receipt to print",
                    Map.of("reason", "NO_FISCAL_DOCUMENT"));
        }
        FiscalDocumentRef newest = all.getLast();
        throw new ApiException(
                ErrorCode.UNPROCESSABLE_STATE,
                "A fiscal receipt prints only for an ISSUED SALE document; the newest is a " + newest.type()
                        + " that is " + newest.status(),
                Map.of("reason", "FISCAL_DOCUMENT_NOT_PRINTABLE", "type", newest.type(), "state", newest.status()));
    }

    // =============================================================== preview

    /** What the preview is asked about: a job, or a document to be built the way a job would. */
    public record PreviewRequest(
            @Nullable UUID jobId,
            @Nullable PrintDocumentType documentType,
            @Nullable UUID orderId,
            @Nullable UUID stationId,
            @Nullable UUID printerId,
            @Nullable Integer tapeWidthMm) {}

    /**
     * The PNG of the document a job would print -- the same bitmap the browser prints and the raster band of an
     * agent's bytes -- so what a manager sees on the screen is what comes out in all three languages.
     */
    @Transactional(readOnly = true)
    public byte[] preview(UUID tenantId, UUID brandId, UUID locationId, PreviewRequest request) {
        rls.bindTenant(tenantId);
        Instant now = clock.instant();
        PrintDocument document;
        RenderContext context;
        if (request.jobId() != null) {
            JobRow job = store.findJob(tenantId, request.jobId())
                    .filter(found -> found.locationId().equals(locationId))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such print job"));
            if (job.protectedDocument() == null) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "The document of this job has been erased",
                        Map.of("reason", "DOCUMENT_ERASED"));
            }
            document = codec.fromJson(protection.reveal(
                    tenantId,
                    ProtectedValue.deserialize(job.protectedDocument()),
                    new FieldProtection.RecordRef(PrintJobFactory.TABLE, PrintJobFactory.COLUMN, job.id()),
                    "PRINT_PREVIEW"));
            context = new RenderContext(
                    Math.max(1, job.attempt()),
                    job.queuedAt(),
                    now,
                    policy.lateMarkerMinutes(tenantId, brandId, locationId),
                    (asset, width) -> logos.forAsset(tenantId, asset, width));
        } else {
            document = build(tenantId, brandId, locationId, request, now);
            context = RenderContext.fresh(now).withLogos((asset, width) -> logos.forAsset(tenantId, asset, width));
        }
        long started = System.nanoTime();
        try {
            return renderer.render(document, context).png();
        } catch (RuntimeException cannotDraw) {
            metrics.rendered(Duration.ofNanos(System.nanoTime() - started), document.type(), false);
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "That document cannot be drawn: " + cannotDraw.getMessage(),
                    Map.of("reason", "RENDER_FAILED"));
        }
    }

    private PrintDocument build(UUID tenantId, UUID brandId, UUID locationId, PreviewRequest request, Instant now) {
        PrintDocumentType type = request.documentType();
        if (type == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Name a document type, or a job");
        }
        PrinterRow printer = request.printerId() != null
                ? store.findPrinter(tenantId, request.printerId())
                        .filter(found -> found.locationId().equals(locationId))
                        .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such printer"))
                : standIn(tenantId, brandId, locationId, request.tapeWidthMm(), now);
        return switch (type) {
            case TEST_PAGE -> documents.testPage(printer, null).document();
            case ORDER_SLIP ->
                documents.orderSlip(printer, required(request.orderId()), false).document();
            case FISCAL_RECEIPT ->
                documents
                        .fiscalReceipt(printer, receiptDocumentOf(tenantId, required(request.orderId())), false)
                        .document();
            case KITCHEN_TICKET -> {
                PrintableTicket ticket = tickets.findByOrder(tenantId, required(request.orderId()))
                        .filter(found -> found.locationId().equals(locationId))
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.UNPROCESSABLE_STATE,
                                "This order has no kitchen ticket",
                                Map.of("reason", "NO_KITCHEN_TICKET")));
                UUID station = request.stationId() != null
                        ? request.stationId()
                        : KitchenTicketDocument.stationsWithItems(ticket).stream()
                                .map(Station::id)
                                .findFirst()
                                .orElseThrow(() -> new ApiException(
                                        ErrorCode.UNPROCESSABLE_STATE, "The ticket has no items to print"));
                yield documents
                        .kitchenTicket(printer, ticket.ticketId(), station, false, ticket.amendmentRevision())
                        .document();
            }
        };
    }

    private static UUID required(@Nullable UUID id) {
        if (id == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That document needs an orderId");
        }
        return id;
    }

    /** A roll with no row behind it, for a preview asked for by width alone. */
    private static PrinterRow standIn(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable Integer width, Instant now) {
        int millimetres = width == null ? 80 : width;
        if (millimetres != 58 && millimetres != 80) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A roll is 58 or 80 mm wide");
        }
        return new PrinterRow(
                Ids.newId(),
                tenantId,
                brandId,
                locationId,
                "Preview",
                millimetres,
                null,
                null,
                "BROWSER",
                "ACTIVE",
                null,
                null,
                1,
                now,
                now);
    }
}
