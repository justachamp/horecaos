package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OrderCrmLogQueryService;
import uz.horecaos.platform.ordering.application.OrderCrmLogQueryService.CrmLogEntry;
import uz.horecaos.platform.ordering.application.OrderCrmLogQueryService.CustomerLabelEntry;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore.LogCursor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * Wave 9 w4-reports-distance-crm (7.2a): the console order log's («Заказы»)
 * CRM columns — customer, operator, courier — the half {@code
 * ReportingController#orders} cannot answer because reporting carries no
 * PERSONAL field at all (ADR 0029, {@code SubjectPseudonym}). Its own
 * capability ({@code ORDER_READ}, not {@code REPORTING_READ}) and its own
 * module, so the console joins this read to {@code GET
 * .../reporting/orders} by {@code orderId} in the browser rather than
 * either read joining the other in SQL.
 *
 * <p>The cursor shape — {@code afterOccurredAt} plus {@code afterOrderId} —
 * matches {@code ReportingController#orders} exactly rather than the opaque
 * signed token {@code OperationsOrderController#board} uses: the console
 * pages both reads in lockstep from one {@code fact_order}/{@code orders}
 * row's own {@code occurredAt}/{@code orderId}, and two different cursor
 * schemes for what is, on screen, one table would cost the caller a second
 * pagination state for no reason.
 *
 * <p>{@code from}/{@code to} are business dates, the same shape {@code
 * ReportingController#orders} takes, converted to a UTC midnight-to-midnight
 * instant range here: unlike {@code reporting}, {@code ordering} has no
 * {@code BusinessDayService} to convert against the tenant's own boundary
 * (that class lives in the reporting module, which this one may not
 * depend on), so a business day that starts mid-morning reads a few hours
 * off UTC midnight for this endpoint specifically — a known, narrow
 * imprecision rather than a silent one.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/orders/crm-log")
@Tag(name = "Order CRM log", description = "7.2a: customer, operator and courier for the console order log")
public class OrderCrmLogController {

    private final OrderCrmLogQueryService log;

    public OrderCrmLogController(OrderCrmLogQueryService log) {
        this.log = log;
    }

    @GetMapping
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Customer, operator and courier for orders in range — the console log's CRM half (7.2a)",
            description = "Never reads reporting.fact_order and is never read by it (ADR 0029's "
                    + "SubjectPseudonym exists precisely to stop that link). The customer's name "
                    + "renders in full and the phone masked, the same ordinary-read rule "
                    + "orders.md §1.5 states for the order board and detail screens — pass the "
                    + "same afterOccurredAt/afterOrderId pair GET .../reporting/orders's own "
                    + "response carries to page both reads together.")
    public ResponseEntity<CrmLogListResponse> log(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> locationId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant afterOccurredAt,
            @RequestParam(required = false) UUID afterOrderId) {

        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "to must not be before from", Map.of());
        }
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        int pageSize = clampLimit(limit);
        List<CrmLogEntry> rows = log.log(
                tenantId, fromInstant, toInstant, orEmpty(locationId), pageSize, cursor(afterOccurredAt, afterOrderId));

        List<CrmLogRowResponse> items = rows.stream().map(CrmLogRowResponse::of).toList();
        // A full page does not prove there is no next row, but it is enough to
        // tell the console "there may be more than this bounded read shows"
        // rather than implying the list is complete — the same rule GET
        // .../reporting/orders's own maybeMore states.
        boolean maybeMore = rows.size() >= pageSize;

        return ResponseEntity.ok(new CrmLogListResponse(items, maybeMore));
    }

    /**
     * Gap map row 1.1: customer name/phone for one already-fetched page of
     * board rows — a {@code POST}, body-only, never a query string (ADR
     * 0029), the same reason {@code OperationsOrderController
     * .customerLookup} is {@code POST} rather than {@code GET}. Writes
     * nothing and decides nothing, but the caller is an authenticated staff
     * subject (unlike {@code DeliveryFeeController.quote}'s unauthenticated
     * storefront visitor, {@code EndpointCapabilityDeclarationTests}' own
     * exemption doc explains why that one alone skips this), so there is a
     * subject to scope a replay guard by — {@code @Idempotent} rather than
     * {@code @RequiresCapability(mutating = true)}, the same choice {@code
     * customerLookup} makes, since this never itself decides the
     * authorization question that flag is for.
     *
     * <p>Bounded to {@link #MAX_LABEL_BATCH} — the board's own page size cap
     * ({@code FETCH_LIMIT} on the console) — rather than left open to
     * whatever list a caller sends.
     */
    @PostMapping("/labels")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Idempotent
    @Operation(
            summary = "Customer name and masked phone for a batch of orders — the board's Клиент column (row 1.1)",
            description = "Never reads reporting.fact_order (ADR 0029's SubjectPseudonym). The name "
                    + "renders in full and the phone masked, the same ordinary-read rule orders.md "
                    + "§1.5 states — identical decrypt path to GET .../orders/crm-log, batched by "
                    + "orderId instead of a date range because the board pages by cursor, not by "
                    + "time. An orderId absent from this tenant, or with no order at all, is simply "
                    + "missing from the response rather than refused.")
    public ResponseEntity<List<CustomerLabelResponse>> labels(
            @PathVariable UUID tenantId, @Valid @RequestBody CustomerLabelsRequest request) {

        // The batch size cap lives on the request record itself (@Size) --
        // @Valid refuses an oversized body with 400 before this method body
        // ever runs.
        List<CustomerLabelEntry> entries = log.customerLabels(tenantId, Set.copyOf(request.orderIds()));
        return ResponseEntity.ok(entries.stream().map(CustomerLabelResponse::of).toList());
    }

    private static final int MAX_LABEL_BATCH = 500;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private static int clampLimit(@Nullable Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be at least 1", Map.of());
        }
        return Math.min(requested, MAX_LIMIT);
    }

    private static List<UUID> orEmpty(@Nullable List<UUID> values) {
        return values == null ? List.of() : values;
    }

    private static @Nullable LogCursor cursor(@Nullable Instant afterOccurredAt, @Nullable UUID afterOrderId) {
        if (afterOccurredAt == null && afterOrderId == null) {
            return null;
        }
        if (afterOccurredAt == null || afterOrderId == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "afterOccurredAt and afterOrderId are both required to page, or both omitted",
                    Map.of());
        }
        return new LogCursor(afterOccurredAt, afterOrderId);
    }

    /** @param maybeMore true when the bounded read came back full — there may be rows beyond it. */
    public record CrmLogListResponse(List<CrmLogRowResponse> rows, boolean maybeMore) {}

    /**
     * @param customerName  null for a guest order or one with no snapshot at all
     * @param customerPhone masked (orders.md §1.5); null when the order carries no phone
     */
    public record CrmLogRowResponse(
            UUID orderId,
            Instant occurredAt,
            UUID locationId,
            String customerType,
            boolean anonymized,
            @Nullable String customerName,
            @Nullable String customerPhone,
            String operatorPrincipalId,
            @Nullable String courierDisplayReference) {

        static CrmLogRowResponse of(CrmLogEntry entry) {
            return new CrmLogRowResponse(
                    entry.orderId(),
                    entry.occurredAt(),
                    entry.locationId(),
                    entry.customerType(),
                    entry.anonymized(),
                    entry.customerName(),
                    PhoneMasking.mask(entry.customerPhone()),
                    entry.operatorPrincipalId(),
                    entry.courierDisplayReference());
        }
    }

    /** Gap map row 1.1's own request body — {@code orderIds} only, never a query string. */
    public record CustomerLabelsRequest(
            @NotEmpty @Size(max = MAX_LABEL_BATCH) List<UUID> orderIds) {}

    /**
     * @param customerName  null for a guest order or one with no snapshot at all
     * @param customerPhone masked (orders.md §1.5); null when the order carries no phone
     */
    public record CustomerLabelResponse(
            UUID orderId,
            String customerType,
            boolean anonymized,
            @Nullable String customerName,
            @Nullable String customerPhone) {

        static CustomerLabelResponse of(CustomerLabelEntry entry) {
            return new CustomerLabelResponse(
                    entry.orderId(),
                    entry.customerType(),
                    entry.anonymized(),
                    entry.customerName(),
                    PhoneMasking.mask(entry.customerPhone()));
        }
    }
}
