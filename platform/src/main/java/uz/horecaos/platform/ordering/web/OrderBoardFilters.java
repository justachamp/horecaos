package uz.horecaos.platform.ordering.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.Cursor;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;

/**
 * The order board's filter parameters and paging, shared by the branch board
 * ({@link OperationsOrderController}) and the brand-scoped board ({@link
 * OperationsBrandOrderController}) so the two cannot drift (gap map rows
 * {@code 1.1}/{@code 1.1c}, wave 16).
 *
 * <p>Validation and the page assembly live here once, and the query itself
 * still lives in {@code JdbcOrderStore.listForLocation} once: the brand board
 * is the branch board's statement with a wider set of branches, not a second
 * implementation of it. What differs between the two endpoints is only who may
 * call them (a capability at {@code LOCATION} versus {@code BRAND}) and which
 * branches the query names.
 */
final class OrderBoardFilters {

    /**
     * {@code ordering.orders.payment_status_projection}'s own seven values
     * (V0022, {@code ck_order_payment_projection}, gap map row 1.1c) — the
     * board's {@code paymentStatus} filter parameter refuses anything else
     * rather than silently answering "no orders" for a typo.
     */
    private static final Set<String> KNOWN_PAYMENT_STATUS_PROJECTIONS =
            Set.of("NOT_REQUIRED", "PENDING", "AUTHORIZED", "CAPTURED", "FAILED", "VOIDED", "REFUNDED");

    /**
     * {@code fiscal.fiscal_documents.status}'s own six values (V0027 widened by
     * V0039, {@code ck_fiscal_document_status}) — the board's {@code
     * fiscalStatus} filter refuses anything else for the same reason.
     */
    private static final Set<String> KNOWN_FISCAL_STATUSES =
            Set.of("NOT_APPLICABLE", "PENDING", "SUBMITTED", "ISSUED", "FAILED", "BLOCKED");

    private OrderBoardFilters() {}

    /**
     * Validates the board's filter parameters and assembles the query both boards
     * (and the frozen {@code GET .../orders}) share.
     *
     * @param locationIds the branches the query names: exactly one for the branch
     *                    board (its path variable), the operator's chosen branches
     *                    — or empty for every branch of the brand — for the brand
     *                    board
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    static JdbcOrderStore.OrderListQuery query(
            UUID tenantId,
            UUID brandId,
            List<UUID> locationIds,
            @Nullable List<String> status,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable String channelCode,
            @Nullable String fulfillmentMode,
            @Nullable UUID courierId,
            @Nullable String paymentMethodCode,
            @Nullable String createdByActorId,
            @Nullable String reference,
            @Nullable String origin,
            @Nullable String paymentStatus,
            @Nullable UUID marketplaceBindingId,
            @Nullable Boolean lateOnly,
            @Nullable Boolean problemOnly,
            @Nullable Boolean callbackRequested,
            @Nullable List<String> fiscalStatus) {

        List<String> statuses = status == null ? List.of() : status;
        statuses.forEach(OrderBoardFilters::requireKnownStatus);
        requireKnownFulfillmentMode(fulfillmentMode);
        requireSearchableReference(reference);
        requireKnownOrigin(origin);
        requireKnownPaymentStatus(paymentStatus);
        List<String> fiscalStatuses = normalisedFiscalStatuses(fiscalStatus);

        return new JdbcOrderStore.OrderListQuery(
                tenantId,
                brandId,
                locationIds,
                statuses,
                from,
                to,
                channelCode,
                fulfillmentMode == null ? null : fulfillmentMode.toUpperCase(Locale.ROOT),
                courierId,
                paymentMethodCode,
                createdByActorId,
                reference,
                origin == null ? null : origin.toUpperCase(Locale.ROOT),
                paymentStatus == null ? null : paymentStatus.toUpperCase(Locale.ROOT),
                marketplaceBindingId,
                Boolean.TRUE.equals(lateOnly),
                Boolean.TRUE.equals(problemOnly),
                Boolean.TRUE.equals(callbackRequested),
                fiscalStatuses,
                null);
    }

    /**
     * One page of the board: decode the cursor against this exact filter set, read
     * the rows, map each to the wire shape with the capabilities its own branch
     * grants, and mint the next cursor.
     *
     * @param grantedFor the action capabilities the caller holds at a given
     *                   branch (ADR 0025). Asked once per distinct branch on the
     *                   page, never per row — a brand-wide page can span many
     *                   branches and a principal's grants may differ between them
     */
    static Page<OperationsOrderController.OrderSummaryResponse> page(
            OrderQueryService orderQuery,
            JdbcOrderStore.OrderListQuery query,
            @Nullable String cursor,
            @Nullable Integer limit,
            Function<UUID, Set<Capability>> grantedFor) {

        String filterHash = filterHashOf(query);
        @Nullable UUID cursorOrderId = null;
        if (cursor != null && !cursor.isBlank()) {
            Cursor decoded = Cursor.decodeUnsigned(cursor, filterHash)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INVALID_REQUEST,
                            "This cursor was issued for a different filter set; start the list again"));
            cursorOrderId = parseCursorOrderId(decoded.sortKey());
        }

        int pageSize = Page.limitOrDefault(limit);
        List<JdbcOrderStore.OrderBoardRow> rows;
        try {
            rows = orderQuery.forLocation(query, cursorOrderId, pageSize);
        } catch (OrderQueryService.UnknownCursorException unknown) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "This cursor does not name an order of this board");
        }

        Map<UUID, Set<Capability>> grantsByLocation = new java.util.HashMap<>();
        List<OperationsOrderController.OrderSummaryResponse> items = rows.stream()
                .map(row -> OperationsOrderController.OrderSummaryResponse.of(
                        row,
                        grantsByLocation.computeIfAbsent(
                                row.order().locationId(), locationId -> grantedFor.apply(locationId))))
                .toList();

        // A short page is the end of the collection. A full one may or may not
        // be, and answering "maybe" with a cursor costs the caller one empty
        // request, where answering "no" wrongly loses them every order after it.
        String nextCursor = items.size() < pageSize
                ? null
                : new Cursor(rows.getLast().order().orderId().toString(), filterHash).encodeUnsigned();

        return new Page<>(items, nextCursor);
    }

    /**
     * The cursor's filter fingerprint, hashed so the token stays short and does
     * not restate the caller's own query back to them in a readable form.
     */
    static String filterHashOf(JdbcOrderStore.OrderListQuery query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(query.fingerprint().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 12));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static UUID parseCursorOrderId(String sortKey) {
        try {
            return UUID.fromString(sortKey);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "This cursor is not usable; start the list again");
        }
    }

    private static void requireKnownStatus(String status) {
        try {
            OrderStatus.valueOf(status);
        } catch (IllegalArgumentException unknown) {
            // Silently dropping an unknown status would return "no orders" for a
            // typo, which reads to an operator as a quiet shift.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown order status \"%s\"".formatted(status));
        }
    }

    private static void requireKnownFulfillmentMode(@Nullable String mode) {
        if (mode == null) {
            return;
        }
        try {
            FulfillmentMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // Dropping an unknown mode would answer "no orders" for a typo, which
            // reads to an operator as a branch that has stopped taking delivery.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown fulfillment mode \"%s\"".formatted(mode));
        }
    }

    /**
     * `ordering.orders.origin`'s own two values (V0038, ADR 0040) — dropping an
     * unknown one would silently answer "no orders" for a typo, exactly the
     * failure {@link #requireKnownFulfillmentMode} already refuses for its own
     * parameter.
     */
    private static void requireKnownOrigin(@Nullable String origin) {
        if (origin == null) {
            return;
        }
        String normalised = origin.toUpperCase(Locale.ROOT);
        if (!normalised.equals("HORECAOS") && !normalised.equals("MARKETPLACE")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown order origin \"%s\"".formatted(origin));
        }
    }

    /**
     * {@code ordering.orders.payment_status_projection}'s own seven values
     * (V0022, {@code ck_order_payment_projection}, gap map row 1.1c) —
     * dropping an unknown one would silently answer "no orders" for a typo,
     * exactly the failure {@link #requireKnownOrigin} already refuses for its
     * own parameter.
     */
    private static void requireKnownPaymentStatus(@Nullable String paymentStatus) {
        if (paymentStatus == null) {
            return;
        }
        if (!KNOWN_PAYMENT_STATUS_PROJECTIONS.contains(paymentStatus.toUpperCase(Locale.ROOT))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Unknown payment status \"%s\"".formatted(paymentStatus));
        }
    }

    /**
     * The fiscal statuses upper-cased and de-duplicated, each refused if it is not
     * one the table can hold — a typo is a validation failure, not a board that
     * quietly reads as "nothing to fiscalize".
     */
    private static List<String> normalisedFiscalStatuses(@Nullable List<String> fiscalStatus) {
        if (fiscalStatus == null || fiscalStatus.isEmpty()) {
            return List.of();
        }
        return fiscalStatus.stream()
                .map(raw -> raw.toUpperCase(Locale.ROOT))
                .peek(value -> {
                    if (!KNOWN_FISCAL_STATUSES.contains(value)) {
                        throw new ApiException(
                                ErrorCode.VALIDATION_FAILED, "Unknown fiscal status \"%s\"".formatted(value));
                    }
                })
                .distinct()
                .toList();
    }

    /**
     * A reference that normalises to nothing must not fall through to "no
     * filter applied" ({@link JdbcOrderStore.OrderListQuery#normalisedReference()}
     * turns it into {@code null}, indistinguishable from the caller never
     * having supplied {@code reference} at all). Left unguarded, a search for
     * {@code "#"} or {@code " - "} would answer with the location's entire
     * board instead of the empty result a nonsense reference search should
     * return — the opposite of what a filter parameter promises.
     */
    private static void requireSearchableReference(@Nullable String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        if (JdbcOrderStore.normalisedExternalReference(reference) == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "This reference has nothing searchable in it");
        }
    }
}
