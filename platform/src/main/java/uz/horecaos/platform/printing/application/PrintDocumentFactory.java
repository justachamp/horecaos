package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.dinein.api.OrderTablesPort;
import uz.horecaos.platform.dinein.api.OrderTablesPort.OrderTable;
import uz.horecaos.platform.dinein.api.OrderTablesPort.TableRef;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.PrintableTicket;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderPrintSource;
import uz.horecaos.platform.ordering.api.OrderPrintSource.PrintableOrder;
import uz.horecaos.platform.payments.api.FiscalReceiptSource;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.FiscalReceipt;
import uz.horecaos.platform.payments.api.PaymentDirectory;
import uz.horecaos.platform.payments.api.PaymentDirectory.PaymentSummary;
import uz.horecaos.platform.printing.application.documents.BuildContext;
import uz.horecaos.platform.printing.application.documents.FiscalReceiptDocument;
import uz.horecaos.platform.printing.application.documents.KitchenTicketDocument;
import uz.horecaos.platform.printing.application.documents.OrderSlipDocument;
import uz.horecaos.platform.printing.application.documents.TestPageDocument;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.TapeWidth;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.ProfileRow;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.tenancy.api.BranchDirectory.Branch;
import uz.horecaos.platform.tenancy.api.BrandLogoLookup;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Builds a document from the facts other modules own (ADR 0154, Decision 1): the one place the printing
 * module reads an order, a ticket, a fiscal document, a branch and a seller, each through the owning
 * module's {@code api} port, and the one place a refusal for a document that may not be printed is made.
 *
 * <p>Nothing here computes a figure or invents a word. A fact that is missing is a refusal that names it,
 * not a default; a document that is not a thing a receipt may be printed for is refused naming its state.
 */
@Component
public class PrintDocumentFactory {

    private static final Logger log = LoggerFactory.getLogger(PrintDocumentFactory.class);

    /** The reveal purposes recorded against the decryption of a line note (ADR 0029). */
    static final String KITCHEN_TICKET_PURPOSE = "PRINT_KITCHEN_TICKET";

    /** A document and the version of the receipt profile it was made under. */
    public record Built(PrintDocument document, @Nullable Integer profileVersion) {}

    private final BranchDirectory branches;
    private final LegalEntityDirectory legalEntities;
    private final BrandLogoLookup logos;
    private final PrintingPolicy policy;
    private final JdbcPrintingStore store;
    private final OrderPrintSource orders;
    private final OrderDirectory orderDirectory;
    private final KitchenTicketPrintSource tickets;
    private final FiscalReceiptSource receipts;
    private final PaymentDirectory payments;
    private final OrderTablesPort tables;
    private final Clock clock;

    public PrintDocumentFactory(
            BranchDirectory branches,
            LegalEntityDirectory legalEntities,
            BrandLogoLookup logos,
            PrintingPolicy policy,
            JdbcPrintingStore store,
            OrderPrintSource orders,
            OrderDirectory orderDirectory,
            KitchenTicketPrintSource tickets,
            FiscalReceiptSource receipts,
            PaymentDirectory payments,
            OrderTablesPort tables,
            Clock clock) {
        this.branches = branches;
        this.legalEntities = legalEntities;
        this.logos = logos;
        this.policy = policy;
        this.store = store;
        this.orders = orders;
        this.orderDirectory = orderDirectory;
        this.tickets = tickets;
        this.receipts = receipts;
        this.payments = payments;
        this.tables = tables;
        this.clock = clock;
    }

    // ------------------------------------------------------------ documents

    /**
     * One station's ticket. {@code amendmentRevision} is 0 for the ticket as released and the kitchen's own
     * amendment count for an amendment ticket.
     */
    public Built kitchenTicket(PrinterRow printer, UUID ticketId, UUID stationId, boolean copy, int amendmentRevision) {
        PrintableTicket ticket = tickets.find(printer.tenantId(), ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such kitchen ticket"));
        if (!ticket.locationId().equals(printer.locationId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such kitchen ticket");
        }
        PrintableOrder order = orders.find(printer.tenantId(), ticket.orderId(), KITCHEN_TICKET_PURPOSE)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
        BuildContext context = context(printer, copy, false);
        PrintDocument document = KitchenTicketDocument.build(
                context, ticket, order, stationId, tableName(printer.tenantId(), order.orderId()), amendmentRevision);
        return new Built(document, null);
    }

    public Built orderSlip(PrinterRow printer, UUID orderId, boolean copy) {
        PrintableOrder order = orders.find(printer.tenantId(), orderId, null)
                .filter(found -> found.locationId().equals(printer.locationId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
        BuildContext context = context(printer, copy, false);
        Instant when = order.confirmedAt() != null ? order.confirmedAt() : order.placedAt();
        LocalDate businessDate = when.atZone(ZoneId.of(context.zone())).toLocalDate();
        FiscalSeller seller = legalEntities
                .sellerFor(printer.tenantId(), printer.locationId(), businessDate)
                .orElse(null);
        PaymentSummary payment = payments.summary(printer.tenantId(), orderId).orElse(null);
        PrintDocument document =
                OrderSlipDocument.build(context, order, seller, payment, tableName(printer.tenantId(), orderId));
        return new Built(document, null);
    }

    /**
     * A fiscal receipt, refused for any document that is not {@code ISSUED} or not a {@code SALE}, naming the
     * state. The manual request and the automatic listener both come through here, so the rule is held once.
     */
    public Built fiscalReceipt(PrinterRow printer, UUID documentId, boolean copy) {
        FiscalReceipt receipt = receipts.find(printer.tenantId(), documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such fiscal document"));
        try {
            FiscalReceiptDocument.requirePrintable(receipt);
        } catch (FiscalReceiptDocument.NotPrintableException refusal) {
            throw notPrintable(refusal);
        }
        UUID orderLocation = orderDirectory
                .summary(printer.tenantId(), receipt.orderId())
                .map(OrderDirectory.OrderSummary::locationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
        if (!orderLocation.equals(printer.locationId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such fiscal document");
        }
        BuildContext context = context(printer, copy, true);
        LocalDate businessDate =
                receipt.createdAt().atZone(ZoneId.of(context.zone())).toLocalDate();
        FiscalSeller seller = legalEntities
                .sellerFor(printer.tenantId(), printer.locationId(), businessDate)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "No company is assigned as the seller at this branch on the document's date",
                        Map.of("reason", "NO_SELLER")));
        PrintDocument document;
        try {
            document = FiscalReceiptDocument.build(context, receipt, seller);
        } catch (FiscalReceiptDocument.SellerMismatchException mismatch) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, mismatch.getMessage(), Map.of("reason", "SELLER_MISMATCH"));
        }
        checkTotalAgainstTheOrder(receipt);
        return new Built(document, profileVersion(printer));
    }

    public Built testPage(PrinterRow printer, @Nullable String agentBuild) {
        BuildContext context = context(printer, false, false);
        return new Built(TestPageDocument.build(context, printer.displayName(), agentBuild), null);
    }

    /** Whether a fiscal document is one a receipt prints for, without building it. */
    public static boolean isReceiptDocument(FiscalReceipt receipt) {
        return "SALE".equals(receipt.type()) && "ISSUED".equals(receipt.status());
    }

    // -------------------------------------------------------------- context

    /** The language, roll, branch and brand presentation a document at this printer is built with. */
    BuildContext context(PrinterRow printer, boolean copy, boolean receipt) {
        Branch branch = branch(printer);
        String locale = policy.documentLocale(printer.tenantId(), printer.brandId(), printer.locationId());
        Optional<ProfileRow> profile =
                receipt ? store.findProfile(printer.tenantId(), printer.brandId()) : Optional.empty();
        String logo = profile.filter(ProfileRow::printLogo)
                .flatMap(found -> logos.logoAssetId(printer.tenantId(), printer.brandId()))
                .map(UUID::toString)
                .orElse(null);
        List<String> footers = profile.map(found -> BuildContext.footerFor(found.footerTexts(), locale))
                .orElse(List.of());
        return new BuildContext(
                locale,
                TapeWidth.require(printer.tapeWidthMm()),
                branch.timezone(),
                copy,
                new BuildContext.Branch(branch.name(), address(branch), blankToNull(branch.contactPhone())),
                logo,
                footers);
    }

    private @Nullable Integer profileVersion(PrinterRow printer) {
        return store.findProfile(printer.tenantId(), printer.brandId())
                .map(ProfileRow::version)
                .orElse(null);
    }

    private Branch branch(PrinterRow printer) {
        return branches.activeBranches(printer.tenantId(), printer.brandId()).stream()
                .filter(candidate -> candidate.locationId().equals(printer.locationId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "The branch is not active, so nothing is printed for it",
                        Map.of("reason", "LOCATION_NOT_ACTIVE")));
    }

    private static @Nullable String address(Branch branch) {
        String joined = java.util.stream.Stream.of(branch.addressLine(), branch.district(), branch.city())
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private @Nullable String tableName(UUID tenantId, UUID orderId) {
        OrderTable table = tables.tablesByOrders(tenantId, List.of(orderId)).get(orderId);
        if (table == null || table.tables().isEmpty()) {
            return null;
        }
        return table.tables().stream().map(TableRef::displayName).collect(Collectors.joining(", "));
    }

    /**
     * The render-time check that the receipt's total is the order's: logged and never corrected (ADR 0154,
     * Specification). No amount is written to the log -- the order and document ids are enough to find it.
     */
    private void checkTotalAgainstTheOrder(FiscalReceipt receipt) {
        orderDirectory.summary(receipt.tenantId(), receipt.orderId()).ifPresent(order -> {
            if (order.totalMinor() != receipt.totalMinor()) {
                log.warn(
                        "The fiscal document {} total differs from the total of order {}; the receipt prints"
                                + " the document's own figures",
                        receipt.documentId(),
                        receipt.orderId());
            }
        });
    }

    private static ApiException notPrintable(FiscalReceiptDocument.NotPrintableException refusal) {
        return new ApiException(
                ErrorCode.UNPROCESSABLE_STATE,
                "A fiscal receipt prints only for an ISSUED SALE document; this one is a " + refusal.type()
                        + " that is " + refusal.state(),
                Map.of("reason", "FISCAL_DOCUMENT_NOT_PRINTABLE", "type", refusal.type(), "state", refusal.state()));
    }

    Clock clock() {
        return clock;
    }
}
