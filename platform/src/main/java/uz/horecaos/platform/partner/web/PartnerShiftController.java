package uz.horecaos.platform.partner.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.partner.api.PartnerBound;
import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.application.MarketplaceShiftNotificationService;
import uz.horecaos.platform.partner.application.MarketplaceShiftNotificationService.Outcome;
import uz.horecaos.platform.partner.application.MarketplaceShiftNotificationService.ShiftEventPush;
import uz.horecaos.platform.partner.application.PartnerAuthenticationService;
import uz.horecaos.platform.partner.domain.MarketplaceShiftEventType;
import uz.horecaos.platform.partner.domain.RejectionCode;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;
import uz.horecaos.platform.web.idempotency.NaturallyIdempotent;

/**
 * An aggregator's own shift open/close ping (ADR 0040, gap map row {@code
 * 10.9d}), the second inbound partner shape beside {@link PartnerOrderController}'s
 * order push.
 *
 * <p><strong>Idempotency.</strong> {@link NaturallyIdempotent}, the same
 * exception {@code PartnerOrderController} documents and for a stronger
 * reason here: this endpoint writes no row of its own at all, only an
 * operations alert whose own idempotency key —
 * {@code (templateKey, binding, business date)} — is enforced by {@code
 * notifications.notification_intents}' unique constraint. A repeated ping
 * for the same shift on the same local day is therefore already exactly
 * once downstream, with or without a client-supplied header a partner will
 * not send.
 *
 * <p>Rate-limited per partner, the identical policy and reasoning {@code
 * PartnerOrderController}'s own push carries: a tight limit here fails
 * closed onto a partner's own retry storm, and the cost of that is the
 * aggregator marking the branch offline over a burst HorecaOS could have
 * absorbed.
 */
@RestController
@RequestMapping("/api/v1/partner/tenants/{tenantId}")
@Tag(name = "Partner API", description = "Inbound aggregator shift events (ADR 0040)")
public class PartnerShiftController {

    private static final RateLimiter.Policy SHIFT_PUSH_LIMIT =
            new RateLimiter.Policy(120, Duration.ofMinutes(1), false);

    private final PartnerAuthenticationService authentication;
    private final MarketplaceShiftNotificationService shifts;
    private final PartnerTokenReader tokens;
    private final RateLimiter rateLimiter;

    public PartnerShiftController(
            PartnerAuthenticationService authentication,
            MarketplaceShiftNotificationService shifts,
            PartnerTokenReader tokens,
            RateLimiter rateLimiter) {
        this.authentication = authentication;
        this.shifts = shifts;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/marketplace/shift-events")
    @PartnerBound(Capability.MARKETPLACE_SHIFT_RECEIVE)
    @NaturallyIdempotent
    @Operation(
            summary = "Report that this aggregator's own shift opened or closed for a venue",
            description = "Acknowledged whether or not the tenant's own "
                    + "notifications.aggregator_shift_notifications_enabled switch is on; the "
                    + "response says whether an alert was actually raised.")
    public ResponseEntity<ShiftEventResponse> push(
            @PathVariable UUID tenantId, @Valid @RequestBody ShiftEventRequest body) {

        PartnerPrincipal principal = authentication.authenticate(tokens.clientId(), tenantId);

        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key("partner.shift.push", tenantId.toString(), principal.rateLimitSubject()),
                SHIFT_PUSH_LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many shift pushes on this partner credential",
                    java.util.Map.of("retryAfterSeconds", decision.retryAfter().toSeconds()));
        }

        Outcome outcome = shifts.receive(principal, body.toPush());

        if (!outcome.accepted()) {
            RejectionCode rejectionCode = outcome.rejectionCode();
            if (rejectionCode == null) {
                throw new IllegalStateException("A rejected shift outcome must name a rejection code");
            }
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(ShiftEventResponse.rejected(rejectionCode.name()));
        }
        return ResponseEntity.ok(ShiftEventResponse.accepted(outcome.notified()));
    }

    public record ShiftEventRequest(
            @NotBlank @Size(max = 128) String venueReference,
            @NotNull String event,
            @Nullable Instant occurredAt) {

        ShiftEventPush toPush() {
            return new ShiftEventPush(venueReference, MarketplaceShiftEventType.valueOf(event), occurredAt);
        }
    }

    public record ShiftEventResponse(
            String status, boolean notified, @Nullable String rejectionCode) {

        static ShiftEventResponse accepted(boolean notified) {
            return new ShiftEventResponse("ACCEPTED", notified, null);
        }

        static ShiftEventResponse rejected(String code) {
            return new ShiftEventResponse("REJECTED", false, code);
        }
    }
}
