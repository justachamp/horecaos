package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.PrintableTicket;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.Station;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.OrderSummary;
import uz.horecaos.platform.payments.api.FiscalReceiptSource;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.FiscalReceipt;
import uz.horecaos.platform.printing.application.PrintDocumentFactory.Built;
import uz.horecaos.platform.printing.application.PrintJobFactory.Spec;
import uz.horecaos.platform.printing.application.documents.KitchenTicketDocument;
import uz.horecaos.platform.printing.domain.JobStatus;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.RouteRow;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Turns a fact that happened -- an order confirmed, a ticket released or amended, a fiscal document issued
 * -- into the jobs its routes ask for (ADR 0154, Decision 8).
 *
 * <p><strong>Created after commit, idempotently, and never inside the business transaction.</strong> Every
 * entry point runs in a transaction of its own ({@code REQUIRES_NEW}) because its callers are {@code
 * AFTER_COMMIT} listeners and the reconciling sweeper, and a fault here must never roll back an order
 * confirmation or a fiscal issuance -- which is why this does not copy {@code KitchenTicketOpener}'s
 * {@code BEFORE_COMMIT}. A job is keyed on its printer, its document type, its source, the source's revision,
 * its station and its copy: the same {@code OrderConfirmed} delivered twice, and the sweeper after a lost
 * listener, each find the job the first made.
 *
 * <p>What prints is explicit: only a route with {@code auto_print} on, at a location whose tenant switched
 * {@code feature.printing} on. A document with no route prints nowhere. A fiscal receipt is made only for an
 * {@code ISSUED} {@code SALE} document; the automatic path creates nothing for any other state.
 */
@Service
public class PrintJobPlanner {

    private static final Logger log = LoggerFactory.getLogger(PrintJobPlanner.class);

    private final TenantRlsSession rls;
    private final JdbcPrintingStore store;
    private final PrintingPolicy policy;
    private final PrintDocumentFactory documents;
    private final PrintJobFactory jobs;
    private final KitchenTicketPrintSource tickets;
    private final FiscalReceiptSource receipts;
    private final OrderDirectory orders;
    private final Clock clock;

    public PrintJobPlanner(
            TenantRlsSession rls,
            JdbcPrintingStore store,
            PrintingPolicy policy,
            PrintDocumentFactory documents,
            PrintJobFactory jobs,
            KitchenTicketPrintSource tickets,
            FiscalReceiptSource receipts,
            OrderDirectory orders,
            Clock clock) {
        this.rls = rls;
        this.store = store;
        this.policy = policy;
        this.documents = documents;
        this.jobs = jobs;
        this.tickets = tickets;
        this.receipts = receipts;
        this.orders = orders;
        this.clock = clock;
    }

    /** An order slip for each automatic slip route of the order's branch. Returns the jobs this call made. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int enqueueOrderSlip(UUID tenantId, UUID brandId, UUID locationId, UUID orderId, int orderVersion) {
        rls.bindTenant(tenantId);
        if (!policy.enabled(tenantId, brandId, locationId)) {
            return 0;
        }
        Instant now = clock.instant();
        int created = 0;
        for (RouteRow route : autoRoutes(tenantId, locationId, PrintDocumentType.ORDER_SLIP)) {
            PrinterRow printer = printerOf(route);
            if (store.autoJobExists(
                    tenantId,
                    locationId,
                    printer.id(),
                    PrintDocumentType.ORDER_SLIP,
                    "ORDER",
                    orderId,
                    orderVersion,
                    null,
                    1)) {
                continue;
            }
            Built built = documents.orderSlip(printer, orderId, false);
            created += insertCopies(
                    route, printer, built, "ORDER", orderId, orderVersion, null, PrintJobFactory.DEFAULT_EXPIRY, now);
        }
        return created;
    }

    /**
     * The tickets of each station that has items, to the station's route (or the station-less route as a
     * fallback). {@code requestedRevision} is the kitchen's amendment count, or null for a release, which
     * prints the ticket as the kitchen holds it: 0 prints the ticket as released and anything above prints an
     * amendment ticket, which says so.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int enqueueKitchenTicket(
            UUID tenantId, UUID brandId, UUID locationId, UUID ticketId, @Nullable Integer requestedRevision) {
        rls.bindTenant(tenantId);
        if (!policy.enabled(tenantId, brandId, locationId)) {
            return 0;
        }
        Optional<PrintableTicket> found = tickets.find(tenantId, ticketId);
        if (found.isEmpty() || !found.get().locationId().equals(locationId)) {
            return 0;
        }
        PrintableTicket ticket = found.get();
        // A release names no revision of its own: it prints the ticket as the kitchen holds it now.
        int revision = requestedRevision != null ? requestedRevision : ticket.amendmentRevision();
        List<RouteRow> routes = autoRoutes(tenantId, locationId, PrintDocumentType.KITCHEN_TICKET);
        if (routes.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        Duration expiry = Duration.ofMinutes(policy.kitchenTicketExpireMinutes(tenantId, brandId, locationId));
        int created = 0;
        for (Station station : KitchenTicketDocument.stationsWithItems(ticket)) {
            // A station with a route of its own prints there; a station with none falls back to the
            // route with no station.
            List<RouteRow> own = routes.stream()
                    .filter(route -> station.id().equals(route.stationId()))
                    .toList();
            List<RouteRow> chosen = own.isEmpty()
                    ? routes.stream().filter(route -> route.stationId() == null).toList()
                    : own;
            for (RouteRow route : chosen) {
                PrinterRow printer = printerOf(route);
                if (store.autoJobExists(
                        tenantId,
                        locationId,
                        printer.id(),
                        PrintDocumentType.KITCHEN_TICKET,
                        "KITCHEN_TICKET",
                        ticketId,
                        revision,
                        station.id(),
                        1)) {
                    continue;
                }
                Built built = documents.kitchenTicket(printer, ticketId, station.id(), false, revision);
                created += insertCopies(
                        route, printer, built, "KITCHEN_TICKET", ticketId, revision, station.id(), expiry, now);
            }
        }
        return created;
    }

    /**
     * A fiscal receipt for each automatic receipt route of the order's branch -- and only for a document that
     * is {@code ISSUED} and a {@code SALE}. Any other state creates nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int enqueueFiscalReceipt(UUID tenantId, UUID orderId, UUID documentId) {
        rls.bindTenant(tenantId);
        Optional<OrderSummary> order = orders.summary(tenantId, orderId);
        if (order.isEmpty()) {
            return 0;
        }
        UUID brandId = order.get().brandId();
        UUID locationId = order.get().locationId();
        if (!policy.enabled(tenantId, brandId, locationId)) {
            return 0;
        }
        Optional<FiscalReceipt> receipt = receipts.find(tenantId, documentId);
        if (receipt.isEmpty() || !PrintDocumentFactory.isReceiptDocument(receipt.get())) {
            return 0;
        }
        Instant now = clock.instant();
        int created = 0;
        for (RouteRow route : autoRoutes(tenantId, locationId, PrintDocumentType.FISCAL_RECEIPT)) {
            PrinterRow printer = printerOf(route);
            if (store.autoJobExists(
                    tenantId,
                    locationId,
                    printer.id(),
                    PrintDocumentType.FISCAL_RECEIPT,
                    "FISCAL_DOCUMENT",
                    documentId,
                    0,
                    null,
                    1)) {
                continue;
            }
            Built built;
            try {
                built = documents.fiscalReceipt(printer, documentId, false);
            } catch (ApiException refused) {
                // No seller assigned, or a seller that is not the document's: nothing is printed that the
                // fiscal operator did not record. Not an error of the issuance, and the sweeper will say
                // the same thing again until a person fixes the assignment.
                log.warn("No receipt was made for fiscal document {}: {}", documentId, refused.getMessage());
                continue;
            }
            created += insertCopies(
                    route, printer, built, "FISCAL_DOCUMENT", documentId, 0, null, PrintJobFactory.DEFAULT_EXPIRY, now);
        }
        return created;
    }

    /** The location an order belongs to, for a caller that holds only a fact about the order. */
    public Optional<UUID> locationOfOrder(UUID tenantId, UUID orderId) {
        return orders.summary(tenantId, orderId).map(OrderSummary::locationId);
    }

    // ------------------------------------------------------------- helpers

    private List<RouteRow> autoRoutes(UUID tenantId, UUID locationId, PrintDocumentType type) {
        return store.activeRoutes(tenantId, locationId, type).stream()
                .filter(RouteRow::autoPrint)
                .toList();
    }

    private PrinterRow printerOf(RouteRow route) {
        return store.findPrinter(route.tenantId(), route.printerId())
                .orElseThrow(() -> new IllegalStateException("A route names a printer that does not exist"));
    }

    /** One job per copy of the route, the second and later marked as copies. Returns how many were new. */
    private int insertCopies(
            RouteRow route,
            PrinterRow printer,
            Built built,
            String sourceType,
            UUID sourceId,
            int revision,
            @Nullable UUID stationId,
            Duration expiry,
            Instant now) {
        int created = 0;
        for (int copy = 1; copy <= route.copies(); copy++) {
            Built forCopy = copy == 1 ? built : new Built(built.document().asCopy(), built.profileVersion());
            JobRow job = jobs.create(
                    new Spec(
                            printer,
                            route.id(),
                            built.document().type(),
                            sourceType,
                            sourceId,
                            revision,
                            stationId,
                            copy,
                            "AUTO",
                            null,
                            null,
                            null,
                            JobStatus.QUEUED,
                            expiry),
                    forCopy,
                    now);
            if (store.insertJob(job)) {
                created++;
            }
        }
        return created;
    }
}
