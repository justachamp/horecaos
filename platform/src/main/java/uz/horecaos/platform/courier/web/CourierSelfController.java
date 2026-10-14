package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.courier.api.CourierSelfAuthorized;
import uz.horecaos.platform.courier.application.CourierSelfQueryService;
import uz.horecaos.platform.courier.application.CourierSelfQueryService.SelfView;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.BranchBindingRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The courier's own record (ADR 0179).
 *
 * <p>The first thing the courier app asks after signing in, because a signed-in account is not
 * necessarily a courier: Keycloak authenticates, and only a row in {@code fulfillment.couriers}
 * whose {@code principal_subject} is the caller's makes them one. Until this existed the courier's
 * shift endpoints had no read, so a client could not learn its branches or whether it had a shift
 * open.
 *
 * <p>The courier is the caller's own token subject, within the tenant in the path, and never a
 * parameter (see {@link CallingCourier}). The answer is {@code Cache-Control: no-store}: it is one
 * person's working state and must not sit in a shared cache.
 */
@RestController
@RequestMapping("/api/v1/courier/tenants/{tenantId}")
@Tag(name = "Courier self", description = "A courier's own record, branches and open shift")
public class CourierSelfController {

    private final CourierSelfQueryService self;
    private final CallingCourier caller;

    public CourierSelfController(CourierSelfQueryService self, CallingCourier caller) {
        this.self = self;
        this.caller = caller;
    }

    @GetMapping("/me")
    @CourierSelfAuthorized(Capability.COURIER_SELF_READ)
    @Operation(
            operationId = "courierGetMe",
            summary = "Who the platform says I am: my branches, my open shift, my duty state",
            description = "Answers 404 for an account that is not a courier of this tenant, exactly as for "
                    + "a tenant that does not exist. Carries no legal name, registration number or "
                    + "telephone number; the registration's validity date and warning state are "
                    + "there so the app can tell a courier why they cannot take work.")
    public ResponseEntity<CourierSelfResponse> me(@PathVariable UUID tenantId) {
        SelfView view = self.viewOf(tenantId, caller.subject())
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The caller is not a courier of this tenant"));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(CourierSelfResponse.of(view));
    }

    /**
     * @param displayReference the tenant's own label for this courier, not a name
     * @param status           the courier row's status ({@code ACTIVE}, ...); the engagement is the
     *                         registration side of whether they may work
     * @param engagement       absent for a courier with no live engagement
     * @param openShift        absent when the courier is not working
     */
    record CourierSelfResponse(
            UUID courierId,
            String displayReference,
            String status,
            @Nullable CourierEngagementView engagement,
            List<CourierBranchView> branches,
            @Nullable CourierOpenShiftView openShift) {

        static CourierSelfResponse of(SelfView view) {
            List<CourierBranchView> branches =
                    view.branches().stream().map(CourierBranchView::of).toList();
            return new CourierSelfResponse(
                    view.courier().id(),
                    view.courier().displayReference(),
                    view.courier().status(),
                    view.engagement() == null ? null : CourierEngagementView.of(view.engagement()),
                    branches,
                    view.openShift() == null ? null : CourierOpenShiftView.of(view.openShift(), view.branches()));
        }
    }

    /**
     * @param registrationValidUntil an ISO date, absent before the registration has been checked
     * @param dispatchable           whether dispatch may offer this courier work at all
     */
    record CourierEngagementView(
            String status, String warningState, @Nullable String registrationValidUntil, boolean dispatchable) {

        static CourierEngagementView of(EngagementRow engagement) {
            return new CourierEngagementView(
                    engagement.status().name(),
                    engagement.warningState().name(),
                    engagement.registrationValidUntil() == null
                            ? null
                            : engagement.registrationValidUntil().toString(),
                    engagement.status().dispatchable());
        }
    }

    record CourierBranchView(UUID brandId, UUID locationId, String displayName, boolean primary) {

        static CourierBranchView of(BranchBindingRow binding) {
            return new CourierBranchView(
                    binding.brandId(), binding.locationId(), binding.locationName(), binding.primary());
        }
    }

    /**
     * @param locationName absent when the shift is at a branch the courier is not bound to
     * @param version      the shift's version, for the {@code If-Match} the shift endpoints may ask for
     */
    record CourierOpenShiftView(
            UUID shiftId,
            UUID brandId,
            UUID locationId,
            @Nullable String locationName,
            String status,
            String dutyState,
            String enforcementMode,
            Instant openedAt,
            int version) {

        static CourierOpenShiftView of(ShiftRow shift, List<BranchBindingRow> bindings) {
            String name = bindings.stream()
                    .filter(binding -> binding.locationId().equals(shift.locationId()))
                    .map(BranchBindingRow::locationName)
                    .findFirst()
                    .orElse(null);
            return new CourierOpenShiftView(
                    shift.id(),
                    shift.brandId(),
                    shift.locationId(),
                    name,
                    shift.status().name(),
                    shift.dutyState().name(),
                    shift.enforcementMode().name(),
                    shift.openedAt(),
                    shift.version());
        }
    }
}
