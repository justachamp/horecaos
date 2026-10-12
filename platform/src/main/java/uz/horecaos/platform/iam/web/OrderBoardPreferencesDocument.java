package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.application.staff.OrderBoardColumn;
import uz.horecaos.platform.iam.application.staff.OrderBoardTab;
import uz.horecaos.platform.iam.application.staff.SavedViewDateRange;

/**
 * The {@code ORDER_BOARD} surface's document (ADR 0160): which optional columns the person turned on or off
 * and the views they saved.
 *
 * <p>Published so the generated client carries the column, tab and period enumerations as unions the
 * console's registries are typed against (a drift between the Java list and the console's fails its
 * build). The route reads and writes the document as a tree; {@code StaffPreferenceSchemas} decides what
 * is stored, and it is stricter than these types are: it refuses an unknown key, a column the board
 * shows or hides anyway, a label that is a number and a twenty-first view.
 */
@Schema(description = "The ORDER_BOARD surface: optional columns, as a change from the default, and saved views")
public record OrderBoardPreferencesDocument(
        @Nullable OrderBoardColumnsChange columns, @Nullable List<SavedView> savedViews) {

    /** A change from the default set: {@code show} turns on columns that are off by default, {@code hide} turns off columns that are on. */
    public record OrderBoardColumnsChange(
            @Nullable List<OrderBoardColumn> show, @Nullable List<OrderBoardColumn> hide) {}

    /**
     * One saved view: a tab and a closed list of filters, never a search term, a customer, an order, a
     * courier, a marketplace binding or an absolute date.
     */
    public record SavedView(UUID id, String label, OrderBoardTab tab, SavedViewFilters filters) {}

    /** Every field optional; a false switch is the same as an absent one. */
    public record SavedViewFilters(
            @Nullable Boolean allBranches,
            @Nullable String channelCode,
            @Nullable Origin origin,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable String paymentMethodCode,
            @Nullable PaymentStatus paymentStatus,
            @Nullable FiscalStatus fiscalStatus,
            @Nullable Boolean lateOnly,
            @Nullable Boolean problemOnly,
            @Nullable Boolean callbackRequested,
            @Nullable Boolean mineOnly,
            @Nullable SavedViewDateRange dateRange) {}

    /** {@code ordering.orders.origin}. */
    public enum Origin {
        HORECAOS,
        MARKETPLACE
    }

    public enum FulfillmentMode {
        DELIVERY,
        PICKUP,
        DINE_IN
    }

    /** {@code ordering.orders.payment_status_projection}. */
    public enum PaymentStatus {
        NOT_REQUIRED,
        PENDING,
        AUTHORIZED,
        CAPTURED,
        FAILED,
        VOIDED,
        REFUNDED
    }

    /** {@code fiscal.fiscal_documents.status}, and the board's «needs attention» pseudo-value. */
    public enum FiscalStatus {
        ATTENTION,
        NOT_APPLICABLE,
        PENDING,
        SUBMITTED,
        ISSUED,
        FAILED,
        BLOCKED
    }
}
