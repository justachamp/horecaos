package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Resolves the number an operator actually has — the one printed on a
 * receipt and read back on a call — to the order(s) it names (ADR 0019).
 *
 * <p>Finance's payments and fiscal screens are the first callers
 * ({@code operations-spec/finance.md} §8.1, §8.2, both of which took a raw
 * order id in a text box until this wave), but the read belongs here rather
 * than in either of them: {@code public_order_number} is an ordering
 * concept, not a finance one, and a third screen with the same problem
 * should find this rather than growing its own copy.
 *
 * <p><strong>Why a list, never a single order.</strong> {@code
 * uq_order_number} is {@code (tenant_id, location_id, public_order_number)}
 * — not {@code (tenant_id, public_order_number)} — and the daily counter
 * behind it resets every business day per location (see {@code
 * JdbcOrderStore.findByPublicOrderNumber}'s own doc). A multi-location
 * tenant can have two live orders named "0001" on the same afternoon, and
 * any tenant will eventually reuse a number across days. Collapsing that to
 * "the first match" would hand an operator someone else's order silently;
 * this returns every candidate, newest first, and leaves picking the right
 * one to the person who knows which branch or which day the call was about.
 */
@RestController
@Validated
@RequestMapping("/api/v1/tenants/{tenantId}/orders")
@Tag(name = "Order lookup", description = "Resolving a public order number to the order(s) it names")
public class OrderNumberLookupController {

    private static final int MAX_MATCHES = 20;

    private final OrderQueryService orders;

    public OrderNumberLookupController(OrderQueryService orders) {
        this.orders = orders;
    }

    @GetMapping("/by-number")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every order at this tenant carrying this public order number",
            description = "Newest first. Usually one row; more than one names a genuine "
                    + "ambiguity -- the same number at two branches, or the same branch on two "
                    + "different days -- for the caller to resolve by branch or date, never by "
                    + "guessing the first.")
    public ResponseEntity<OrderNumberLookupResponse> byNumber(
            @PathVariable UUID tenantId, @RequestParam @NotBlank @Size(max = 24) String publicOrderNumber) {

        List<OrderRow> matches = orders.byPublicOrderNumber(tenantId, publicOrderNumber.strip(), MAX_MATCHES);
        return ResponseEntity.ok(new OrderNumberLookupResponse(
                matches.size(),
                matches.stream().map(OrderNumberMatchResponse::of).toList()));
    }

    public record OrderNumberLookupResponse(int count, List<OrderNumberMatchResponse> matches) {}

    /** One candidate order — enough to tell two apart, nothing an order-detail screen owns. */
    public record OrderNumberMatchResponse(
            UUID orderId,
            String publicOrderNumber,
            UUID brandId,
            UUID locationId,
            String status,
            ApiMoney total,
            Instant createdAt) {

        static OrderNumberMatchResponse of(OrderRow row) {
            return new OrderNumberMatchResponse(
                    row.orderId(),
                    row.publicOrderNumber(),
                    row.brandId(),
                    row.locationId(),
                    row.status().name(),
                    ApiMoney.of(row.totalMinor(), row.currency()),
                    row.createdAt());
        }
    }
}
