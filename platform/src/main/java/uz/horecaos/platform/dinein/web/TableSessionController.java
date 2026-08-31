package uz.horecaos.platform.dinein.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
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
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.application.port.SessionOrderSource.SessionBill;
import uz.horecaos.platform.dinein.domain.SessionStatus;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The waiter's and the manager's side of a table visit (ADR 0047).
 *
 * <p>Force-close is the one transition here that needs a different grant from the
 * rest, and the declaration says so. Closing a table that still owes money is a
 * shift's cash shortfall being signed for; folding it into the ordinary manage
 * grant would leave the audit record intact and the accountability meaningless.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/dine-in/sessions")
@Tag(name = "Dine-in sessions", description = "Seating, rounds, the running bill, and settlement")
public class TableSessionController {

    private final TableSessionService sessions;
    private final CurrentActor currentActor;

    public TableSessionController(TableSessionService sessions, CurrentActor currentActor) {
        this.sessions = sessions;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.DINEIN_SESSION_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "What is live in this room right now")
    public ResponseEntity<List<SessionResponse>> live(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        return ResponseEntity.ok(sessions.live(tenantId, locationId).stream()
                .map(SessionResponse::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.DINEIN_SESSION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Seat a party, with or without a booking",
            description = "A walk-in names no booking and is most covers. Naming one moves it to "
                    + "SEATED in the same transaction, which is where a claim on the future "
                    + "becomes an occupancy in the present.")
    public ResponseEntity<SessionResponse> open(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody OpenRequest body) {

        SessionRow opened = sessions.open(
                new TableSessionService.OpenSession(
                        tenantId,
                        brandId,
                        locationId,
                        body.reservationId(),
                        body.tableIds(),
                        body.partySize(),
                        body.currency(),
                        currentActor.get().subject()),
                body.reason());

        return ResponseEntity.ok(SessionResponse.of(opened));
    }

    @GetMapping("/{sessionId}")
    @RequiresCapability(value = Capability.DINEIN_SESSION_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "One session and its running bill",
            description = "The bill is summed over the session's orders on every read and is "
                    + "never a stored column, so an amended round changes the bill rather than "
                    + "disagreeing with it.")
    public ResponseEntity<SessionDetailResponse> find(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID sessionId) {

        SessionRow session = sessions.find(tenantId, sessionId);
        SessionBill bill = sessions.bill(tenantId, sessionId);

        return ResponseEntity.ok(new SessionDetailResponse(
                SessionResponse.of(session),
                sessions.rounds(tenantId, sessionId),
                bill.currency() == null ? session.currency() : bill.currency(),
                bill.totalMinor(),
                bill.roundCount(),
                bill.openRoundCount()));
    }

    @PostMapping("/{sessionId}/rounds")
    @RequiresCapability(value = Capability.DINEIN_SESSION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Attach a placed order to the bill",
            description = "The order is not created or modified here. Each round is a normal "
                    + "immutable ADR 0019 order; this records that it belongs to this evening. "
                    + "An order can be on exactly one bill.")
    public ResponseEntity<RoundResponse> addRound(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody RoundRequest body) {

        int sequence = sessions.addRound(
                tenantId, sessionId, body.orderId(), currentActor.get().subject(), body.reason());

        return ResponseEntity.ok(new RoundResponse(sessionId, body.orderId(), sequence));
    }

    @PostMapping("/{sessionId}/state-actions")
    @RequiresCapability(value = Capability.DINEIN_SESSION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Ask for the bill, start settling, return to open, or close",
            description = "SETTLING back to OPEN is not an error path — it is a card that "
                    + "declined, or a party that ordered one more round after asking to pay. "
                    + "Force-close is refused here and has its own endpoint.")
    public ResponseEntity<SessionResponse> stateAction(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody StateActionRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        SessionStatus target = parse(body.targetStatus());

        if (target == SessionStatus.FORCE_CLOSED) {
            throw new ApiException(
                    ErrorCode.INSUFFICIENT_CAPABILITY,
                    "Closing a table that still owes money is its own endpoint and its own "
                            + "capability (dinein.session.force_close)");
        }

        return ResponseEntity.ok(SessionResponse.of(sessions.move(
                tenantId,
                sessionId,
                target,
                (int) expected,
                null,
                currentActor.get().subject(),
                body.reason())));
    }

    @PostMapping("/{sessionId}/force-closures")
    @RequiresCapability(value = Capability.DINEIN_SESSION_FORCE_CLOSE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Close a table that has not paid",
            description = "The walkout. Needs a reason code and writes an ADR 0027 audit record "
                    + "carrying the unsettled amount, because an unpaid table that quietly "
                    + "disappears is how a shift's cash shortfall becomes unattributable.")
    public ResponseEntity<SessionResponse> forceClose(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody ForceCloseRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        return ResponseEntity.ok(SessionResponse.of(sessions.move(
                tenantId,
                sessionId,
                SessionStatus.FORCE_CLOSED,
                (int) expected,
                body.reasonCode(),
                currentActor.get().subject(),
                body.reason())));
    }

    private static SessionStatus parse(String value) {
        try {
            return SessionStatus.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown session status " + value);
        }
    }

    // -------------------------------------------------------------- contracts

    record OpenRequest(
            UUID reservationId,
            @NotEmpty List<UUID> tableIds,
            @Min(1) @Max(200) Integer partySize,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(max = 500) String reason) {}

    record SessionResponse(
            UUID sessionId,
            @Nullable UUID reservationId,
            Integer partySize,
            LocalDate businessDate,
            Instant openedAt,
            String status,
            Integer serviceChargeRateBp,
            String currency,
            @Nullable Long settledTotalMinor,
            @Nullable Instant closedAt,
            @Nullable String closeReasonCode,
            int version) {

        static SessionResponse of(SessionRow row) {
            return new SessionResponse(
                    row.id(),
                    row.reservationId(),
                    row.partySize(),
                    row.businessDate(),
                    row.openedAt(),
                    row.status().name(),
                    row.serviceChargeRateBpSnapshot(),
                    row.currency(),
                    row.settledTotalMinor(),
                    row.closedAt(),
                    row.closeReasonCode(),
                    row.version());
        }
    }

    /**
     * @param totalMinor minor units, and for UZS a minor unit is a whole som.
     *                   Rendered by dividing by nothing.
     */
    record SessionDetailResponse(
            SessionResponse session,
            List<UUID> orderIds,
            String currency,
            long totalMinor,
            int roundCount,
            int openRoundCount) {}

    record RoundRequest(
            @NotNull UUID orderId,
            @NotBlank @Size(max = 500) String reason) {}

    record RoundResponse(UUID sessionId, UUID orderId, int sequence) {}

    record StateActionRequest(
            @NotBlank @Size(max = 20) String targetStatus,
            @NotBlank @Size(max = 500) String reason) {}

    record ForceCloseRequest(
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reason) {}
}
