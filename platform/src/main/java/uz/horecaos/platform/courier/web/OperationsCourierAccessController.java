package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
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
import uz.horecaos.platform.courier.application.CourierAccessService;
import uz.horecaos.platform.courier.application.CourierAccessService.AccessState;
import uz.horecaos.platform.courier.application.CourierAccessService.Issued;
import uz.horecaos.platform.courier.application.CourierAccessService.Kind;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The console's «Send invite» and «Reset access» for a courier (couriers.md §6.1, §18, ADR 0178).
 *
 * <p>The console asks; it never sees, sets or derives a credential. The courier is texted a one-time link by
 * the relay, and this controller answers only whether an invitation was queued and where the courier's access
 * stands. Neither response carries a phone number or a token.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Courier access", description = "Invite a courier to the app, or reset their access, by text message")
public class OperationsCourierAccessController {

    private final CourierAccessService access;
    private final CurrentActor currentActor;

    public OperationsCourierAccessController(CourierAccessService access, CurrentActor currentActor) {
        this.access = access;
        this.currentActor = currentActor;
    }

    @GetMapping("/couriers/{courierId}/access")
    @RequiresCapability(Capability.COURIER_READ)
    @Operation(
            operationId = "courierAccessState",
            summary = "Where a courier's app access stands",
            description = "NONE, QUEUED, SENT, EXPIRED, ACCEPTED, VOIDED or FAILED, with when it was asked for, "
                    + "sent and spent and the stable code of the last failure. smsConnected is false while "
                    + "no SMS gateway carries courier messages: an invitation then waits and says "
                    + "SMS_NOT_CONNECTED. Never a phone number, never a link.")
    public ResponseEntity<AccessStateResponse> state(@PathVariable UUID tenantId, @PathVariable UUID courierId) {
        return ResponseEntity.ok(AccessStateResponse.of(access.state(tenantId, courierId)));
    }

    @PostMapping("/couriers/{courierId}/access-invitations")
    @RequiresCapability(value = Capability.COURIER_ENGAGEMENT_MANAGE, mutating = true)
    @Operation(
            operationId = "inviteCourier",
            summary = "Text a courier a link to set their own password (first sign-in or reset)",
            description = "kind is INVITE (a first sign-in) or RESET. The link is texted to the courier's phone "
                    + "and works once, for 72 hours; asking again voids the one before. A courier who is "
                    + "archived or has no engagement is refused (RESOURCE_CONFLICT); a second request within "
                    + "a minute is refused (RATE_LIMIT_EXCEEDED, retryAfterSeconds). Answers 202: the text is "
                    + "sent by a relay, not by this call.")
    public ResponseEntity<IssuedResponse> invite(
            @PathVariable UUID tenantId, @PathVariable UUID courierId, @Valid @RequestBody InviteCourierRequest body) {

        Issued issued = access.issue(tenantId, courierId, body.kind(), body.locale(), actor());
        return ResponseEntity.accepted().body(IssuedResponse.of(issued));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    record InviteCourierRequest(
            @NotNull Kind kind,
            @Pattern(regexp = "uz-Latn|uz|ru|en") @Nullable String locale) {}

    record IssuedResponse(UUID invitationId, String kind, String status, boolean smsConnected) {

        static IssuedResponse of(Issued issued) {
            return new IssuedResponse(issued.invitationId(), issued.kind(), issued.status(), issued.smsConnected());
        }
    }

    record AccessStateResponse(
            String state,
            @Nullable String kind,
            @Nullable Instant requestedAt,
            @Nullable Instant sentAt,
            @Nullable Instant expiresAt,
            @Nullable Instant acceptedAt,
            @Nullable String lastErrorCode,
            boolean smsConnected) {

        static AccessStateResponse of(AccessState state) {
            return new AccessStateResponse(
                    state.state(),
                    state.kind(),
                    state.requestedAt(),
                    state.sentAt(),
                    state.expiresAt(),
                    state.acceptedAt(),
                    state.lastErrorCode(),
                    state.smsConnected());
        }
    }
}
