package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OrderCrmLogQueryService;
import uz.horecaos.platform.ordering.application.OrderCrmLogQueryService.CrmLogEntry;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore.LogCursor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

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
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) List<UUID> locationId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant afterOccurredAt,
            @RequestParam(required = false) UUID afterOrderId) {

        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "to must not be before from", Map.of());
        }

        int pageSize = clampLimit(limit);
        List<CrmLogEntry> rows =
                log.log(tenantId, from, to, orEmpty(locationId), pageSize, cursor(afterOccurredAt, afterOrderId));

        List<CrmLogRowResponse> items = rows.stream().map(CrmLogRowResponse::of).toList();
        // A full page does not prove there is no next row, but it is enough to
        // tell the console "there may be more than this bounded read shows"
        // rather than implying the list is complete — the same rule GET
        // .../reporting/orders's own maybeMore states.
        boolean maybeMore = rows.size() >= pageSize;

        return ResponseEntity.ok(new CrmLogListResponse(items, maybeMore));
    }

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
}
