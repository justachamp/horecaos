package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OperatorTodayCountsService;
import uz.horecaos.platform.ordering.application.OperatorTodayCountsService.OperatorTodayCounts;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Staff 9.2d — "how many orders did this person take today", read from
 * their own card. See {@link OperatorTodayCountsService}'s own doc for why
 * this is a separate, manager-facing read rather than an overload of {@code
 * MyWorkQueryService}.
 *
 * <p>Tenant-scoped {@code ORDER_READ}: a staff person's card has no single
 * location, and a manager who can see the tenant's own people directory
 * already holds tenant-wide order visibility on every other screen this
 * console gives them.
 */
@RestController
@Validated
@RequestMapping("/api/v1/tenants/{tenantId}/orders/operators/{subject}")
@Tag(name = "Operator order counts", description = "How many orders one operator has taken today (Staff 9.2d)")
public class OperatorTodayCountsController {

    private final OperatorTodayCountsService counts;

    public OperatorTodayCountsController(OperatorTodayCountsService counts) {
        this.counts = counts;
    }

    @GetMapping("/today-counts")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "One operator's created/accepted order counts for today",
            description = "Today is the tenant's own business day (ADR 0043), not the UTC "
                    + "calendar date. The two counts can both include the same order -- an "
                    + "operator who both created and approved it -- and are never added "
                    + "together server-side; see OperatorTodayCountsService's own doc.")
    public ResponseEntity<OperatorTodayCountsResponse> today(
            @PathVariable UUID tenantId, @PathVariable @NotBlank @Size(max = 255) String subject) {
        OperatorTodayCounts result = counts.today(tenantId, subject);
        return ResponseEntity.ok(OperatorTodayCountsResponse.of(result));
    }

    public record OperatorTodayCountsResponse(
            long createdCount, long acceptedCount, Instant businessDayFrom, Instant businessDayTo) {

        static OperatorTodayCountsResponse of(OperatorTodayCounts result) {
            return new OperatorTodayCountsResponse(
                    result.created(),
                    result.accepted(),
                    result.window().from(),
                    result.window().to());
        }
    }
}
