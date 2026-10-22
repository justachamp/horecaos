package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.courier.api.CourierSelfAuthorized;
import uz.horecaos.platform.courier.application.CourierNoticeService;
import uz.horecaos.platform.courier.application.CourierSelfQueryService;
import uz.horecaos.platform.courier.application.CourierSelfQueryService.SelfView;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.BranchBindingRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

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
 *
 * <p>The same tenant-level path takes the courier's acknowledgement of the telemetry transparency
 * notice, which ADR 0045 makes the precondition for collecting a position: it is an audit fact and
 * nothing else ({@link CourierNoticeService}).
 */
@RestController
@RequestMapping("/api/v1/courier/tenants/{tenantId}")
@Tag(name = "Courier self", description = "A courier's own record, branches and open shift")
public class CourierSelfController {

    private final CourierSelfQueryService self;
    private final CourierNoticeService notices;
    private final CallingCourier caller;

    public CourierSelfController(CourierSelfQueryService self, CourierNoticeService notices, CallingCourier caller) {
        this.self = self;
        this.notices = notices;
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

    @PostMapping("/telemetry-notice-acknowledgements")
    @CourierSelfAuthorized(Capability.COURIER_TELEMETRY)
    @Idempotent
    @Operation(
            operationId = "courierAcknowledgeTelemetryNotice",
            summary = "Record that I was shown this version of the location transparency notice",
            description = "ADR 0045 makes the notice the precondition for collecting a courier's position. "
                    + "The app shows it, and on acceptance posts the version it showed; the platform "
                    + "writes an audit fact naming the courier, the version and the time. A new version "
                    + "is a new acknowledgement. Answers 404 for an account that is not a courier of "
                    + "this tenant. The version is a short identifier, never free text.")
    public ResponseEntity<NoticeAcknowledgementResponse> acknowledgeNotice(
            @PathVariable UUID tenantId, @Valid @RequestBody AcknowledgeNoticeRequest body) {
        CourierNoticeService.Acknowledgement recorded = notices.acknowledge(
                tenantId, caller.idIn(tenantId), body.version(), ActorRef.user(caller.subject(), null));
        return ResponseEntity.ok(new NoticeAcknowledgementResponse(recorded.version(), recorded.acknowledgedAt()));
    }

    /**
     * @param version the notice version the app showed ({@code 2026-10-draft-1}): letters, digits, dot,
     *                underscore and hyphen, so the field cannot carry a name, a number or a sentence
     */
    record AcknowledgeNoticeRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$")
            String version) {}

    record NoticeAcknowledgementResponse(String version, Instant acknowledgedAt) {}

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
