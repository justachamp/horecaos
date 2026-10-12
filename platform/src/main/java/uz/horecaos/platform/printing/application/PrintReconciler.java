package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.ReleasedTicket;
import uz.horecaos.platform.ordering.api.OrderPrintSource;
import uz.horecaos.platform.ordering.api.OrderPrintSource.ConfirmedOrder;
import uz.horecaos.platform.payments.api.FiscalReceiptSource;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.IssuedDocument;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.RouteRow;

/**
 * The reconciling sweeper (ADR 0154, Decision 8): every minute it re-derives what should have been queued in
 * a bounded window -- kitchen tickets released, orders confirmed and fiscal documents issued in the last two
 * hours -- and for each route lacking a job inserts the missing one under the unique key.
 *
 * <p>A lost listener is a crash between the commit and the {@code AFTER_COMMIT} callback, a database that
 * was briefly unreachable, or a deploy at the wrong second. The listener is the fast path and this is the
 * guarantee: a job created twice is one row, and a job lost is found within a minute. The sweep reads only
 * through the owning modules' ports, builds a document only for a job that is missing, and skips every
 * location whose tenant has not switched printing on.
 */
@Component
public class PrintReconciler {

    private static final Logger log = LoggerFactory.getLogger(PrintReconciler.class);

    /** The window the sweep looks back over. */
    static final Duration WINDOW = Duration.ofHours(2);

    private static final int LIMIT = 500;

    private final TenantRlsSession rls;
    private final JdbcPrintingStore store;
    private final PrintingPolicy policy;
    private final PrintJobPlanner planner;
    private final KitchenTicketPrintSource tickets;
    private final OrderPrintSource orders;
    private final FiscalReceiptSource receipts;
    private final PrintingMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PrintReconciler(
            TenantRlsSession rls,
            JdbcPrintingStore store,
            PrintingPolicy policy,
            PrintJobPlanner planner,
            KitchenTicketPrintSource tickets,
            OrderPrintSource orders,
            FiscalReceiptSource receipts,
            PrintingMetrics metrics,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.rls = rls;
        this.store = store;
        this.policy = policy;
        this.planner = planner;
        this.tickets = tickets;
        this.orders = orders;
        this.receipts = receipts;
        this.metrics = metrics;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** What a sweep found missing and made. */
    public record Sweep(int locations, int jobsCreated) {}

    public Sweep sweepOnce() {
        Instant now = clock.instant();
        Instant from = now.minus(WINDOW);
        List<UUID[]> locations = transaction.execute(status -> {
            rls.bindPlatform();
            return store.locationsWithAutoRoutes();
        });
        int created = 0;
        int visited = 0;
        for (UUID[] place : locations == null ? List.<UUID[]>of() : locations) {
            UUID tenantId = place[0];
            UUID brandId = place[1];
            UUID locationId = place[2];
            try {
                int made = Objects.requireNonNull(transaction.execute(status -> {
                    rls.bindTenant(tenantId);
                    return policy.enabled(tenantId, brandId, locationId)
                            ? reconcile(tenantId, brandId, locationId, from, now)
                            : -1;
                }));
                if (made >= 0) {
                    visited++;
                    created += made;
                }
            } catch (RuntimeException failure) {
                // One branch's fault must not stop the sweep of the rest.
                log.error("The print reconciliation of location {} failed", locationId, failure);
            }
        }
        if (created > 0) {
            log.info("The print reconciler made {} job(s) the listeners had not", created);
        }
        return new Sweep(visited, created);
    }

    private int reconcile(UUID tenantId, UUID brandId, UUID locationId, Instant from, Instant to) {
        int created = 0;
        List<RouteRow> routes = routesOf(tenantId, locationId);
        if (has(routes, PrintDocumentType.KITCHEN_TICKET)) {
            for (ReleasedTicket released : tickets.releasedBetween(tenantId, locationId, from, to, LIMIT)) {
                created += attempt(
                        PrintDocumentType.KITCHEN_TICKET,
                        () -> planner.enqueueKitchenTicket(
                                tenantId, brandId, locationId, released.ticketId(), released.amendmentRevision()));
            }
        }
        if (has(routes, PrintDocumentType.ORDER_SLIP)) {
            for (ConfirmedOrder confirmed : orders.confirmedBetween(tenantId, locationId, from, to, LIMIT)) {
                created += attempt(
                        PrintDocumentType.ORDER_SLIP,
                        () -> planner.enqueueOrderSlip(
                                tenantId, brandId, locationId, confirmed.orderId(), confirmed.version()));
            }
        }
        if (has(routes, PrintDocumentType.FISCAL_RECEIPT)) {
            for (IssuedDocument issued : receipts.issuedBetween(tenantId, from, to, LIMIT)) {
                // Issued documents are listed per tenant; the planner resolves the order's own branch and
                // makes a job only if that is this one's.
                created += attempt(
                        PrintDocumentType.FISCAL_RECEIPT,
                        () -> issuedHere(tenantId, locationId, issued)
                                ? planner.enqueueFiscalReceipt(tenantId, issued.orderId(), issued.documentId())
                                : 0);
            }
        }
        return created;
    }

    private boolean issuedHere(UUID tenantId, UUID locationId, IssuedDocument issued) {
        return planner.locationOfOrder(tenantId, issued.orderId())
                .map(locationId::equals)
                .orElse(false);
    }

    private int attempt(PrintDocumentType type, java.util.function.IntSupplier action) {
        try {
            return action.getAsInt();
        } catch (RuntimeException failure) {
            metrics.enqueueFailed(type);
            log.warn("The print reconciler could not make a {} job: {}", type, failure.getMessage());
            return 0;
        }
    }

    private List<RouteRow> routesOf(UUID tenantId, UUID locationId) {
        return store.listRoutes(tenantId, locationId).stream()
                .filter(route -> route.active() && route.autoPrint())
                .toList();
    }

    private static boolean has(List<RouteRow> routes, PrintDocumentType type) {
        return routes.stream().anyMatch(route -> route.documentType() == type);
    }

    /** The scheduled entry, once a minute. */
    @Component
    @ConditionalOnProperty(name = "horecaos.printing.workers.enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final PrintReconciler reconciler;

        Schedule(PrintReconciler reconciler) {
            this.reconciler = reconciler;
        }

        @Scheduled(
                initialDelayString = "${horecaos.printing.reconciler.initial-delay:PT45S}",
                fixedDelayString = "${horecaos.printing.reconciler.interval:PT1M}")
        void tick() {
            try {
                reconciler.sweepOnce();
            } catch (RuntimeException failure) {
                log.error("The print reconciler could not run", failure);
            }
        }
    }
}
