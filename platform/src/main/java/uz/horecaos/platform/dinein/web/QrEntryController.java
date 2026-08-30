package uz.horecaos.platform.dinein.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.dinein.application.QrEntryService;
import uz.horecaos.platform.dinein.application.QrEntryService.GuestAdmission;
import uz.horecaos.platform.dinein.application.QrEntryService.GuestContext;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.application.port.SessionOrderSource.SessionBill;
import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.domain.SessionStatus;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What a guest with a phone can reach (ADR 0047).
 *
 * <p>Unauthenticated in the Keycloak sense and carrying no {@link
 * uz.horecaos.platform.web.authorization.RequiresCapability} declaration, because
 * there is no principal to hold a capability: the caller is somebody who pointed a
 * camera at a table. Authorization here is the token itself, and every endpoint
 * below resolves it to a row before doing anything.
 *
 * <p><strong>The printed token travels in the request body, not in the path.</strong>
 * ADR 0047's API sketch writes {@code POST /api/v1/storefront/qr/{tableToken}/sessions},
 * and this deviates deliberately. A URL path is written to every access log, every
 * reverse proxy, and every {@code Referer} header the page emits afterwards; a
 * permanent bearer credential printed on card and left in a public room is the one
 * value that must not end up in all three. The QR code still encodes a storefront
 * URL — that is unavoidable and is the guest's browser, not our logs — and the page
 * it opens posts the token here once. Nothing else in this module accepts the
 * printed token at all.
 *
 * <p>No endpoint here accepts a table id, and none accepts a tenant. Both are read
 * from the row the token finds, which is what stops a guest editing a request to
 * reach the next table's bill.
 */
@RestController
@RequestMapping("/api/v1/storefront/dine-in")
@Tag(name = "QR dine-in", description = "Scanning a table code, and the bill it leads to")
public class QrEntryController {

    /**
     * Deliberately not {@code Authorization: Bearer}. These paths are outside the
     * resource server's principal model, and a bearer header on them would be
     * offered to the JWT decoder, fail, and produce a 401 that says nothing about
     * the actual problem.
     */
    private static final String GUEST_TOKEN_HEADER = "X-Dine-In-Token";

    private final QrEntryService qr;
    private final TableSessionService sessions;

    public QrEntryController(QrEntryService qr, TableSessionService sessions) {
        this.qr = qr;
        this.sessions = sessions;
    }

    @PostMapping("/qr/token-exchanges")
    @Operation(
            summary = "Exchange a scanned table code for a guest token",
            description = "Scanning authorises nothing by itself. The printed token is exchanged "
                    + "once for a short-lived, table-scoped token, and that is what every "
                    + "subsequent call carries. Rotating the table's code revokes every token "
                    + "minted from it in the same transaction. Every failure — unknown, rotated, "
                    + "archived, branch not configured — answers identically, so a caller "
                    + "holding a guessed value learns nothing from the difference.")
    public ResponseEntity<AdmissionResponse> exchange(@Valid @RequestBody ExchangeRequest body) {
        GuestAdmission admission = qr.exchange(body.tableToken());

        return ResponseEntity.ok(new AdmissionResponse(
                admission.guestToken(),
                admission.expiresAt(),
                admission.mode().name(),
                admission.tenantId(),
                admission.brandId(),
                admission.locationId(),
                admission.tableCode(),
                admission.openSessionId()));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(
            summary = "The running bill at the guest's own table",
            description = "The session id is checked against the table the guest's token was "
                    + "minted for, not merely parsed. A guest who edits it reaches nothing.")
    public ResponseEntity<GuestBillResponse> bill(
            @PathVariable UUID sessionId, @RequestHeader(GUEST_TOKEN_HEADER) String guestToken) {

        GuestContext guest = qr.resolve(guestToken);
        requireOrdering(guest);

        SessionRow session = qr.requireSessionAtTable(guest, sessionId);
        SessionBill bill = sessions.bill(guest.tenantId(), sessionId);

        return ResponseEntity.ok(new GuestBillResponse(
                session.id(),
                session.status().name(),
                bill.currency() == null ? session.currency() : bill.currency(),
                bill.totalMinor(),
                bill.roundCount(),
                sessions.rounds(guest.tenantId(), sessionId)));
    }

    @PostMapping("/sessions/{sessionId}/bill-requests")
    @Operation(
            summary = "Ask for the bill",
            description = "Moves the session to BILL_REQUESTED, which is a request rather than a "
                    + "payment: nothing is captured here, and a party that then orders one more "
                    + "round moves it back.")
    public ResponseEntity<GuestBillResponse> requestBill(
            @PathVariable UUID sessionId, @RequestHeader(GUEST_TOKEN_HEADER) String guestToken) {

        GuestContext guest = qr.resolve(guestToken);
        requireOrdering(guest);

        SessionRow session = qr.requireSessionAtTable(guest, sessionId);
        if (session.status() == SessionStatus.BILL_REQUESTED) {
            // Idempotent by observation rather than by an idempotency key. A guest
            // tapping twice is not an error and must not read as one; ADR 0031's
            // key belongs on operator mutations, and there is no operator here.
            return ResponseEntity.ok(billResponse(guest, session));
        }

        SessionRow moved = sessions.move(
                guest.tenantId(),
                sessionId,
                SessionStatus.BILL_REQUESTED,
                session.version(),
                null,
                "guest:" + guest.tableId(),
                "Requested from the table");

        return ResponseEntity.ok(billResponse(guest, moved));
    }

    private GuestBillResponse billResponse(GuestContext guest, SessionRow session) {
        SessionBill bill = sessions.bill(guest.tenantId(), session.id());
        return new GuestBillResponse(
                session.id(),
                session.status().name(),
                bill.currency() == null ? session.currency() : bill.currency(),
                bill.totalMinor(),
                bill.roundCount(),
                sessions.rounds(guest.tenantId(), session.id()));
    }

    /**
     * A VIEW_ONLY branch publishes a menu and nothing else.
     *
     * <p>Refused with the same 404 an unknown table gets, because a guest whose
     * branch does not take QR orders should not be told that a bill exists here at
     * all.
     */
    private static void requireOrdering(GuestContext guest) {
        if (guest.mode() != QrMode.ORDER_AND_PAY) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This code is not in service. Ask a member of staff.");
        }
    }

    // -------------------------------------------------------------- contracts

    record ExchangeRequest(@NotBlank @Size(max = 64) String tableToken) {}

    /**
     * @param guestToken returned once. There is no endpoint that reissues it: the
     *                   guest scans again
     */
    record AdmissionResponse(
            String guestToken,
            Instant expiresAt,
            String mode,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String tableCode,
            UUID openSessionId) {}

    record GuestBillResponse(
            UUID sessionId, String status, String currency, long totalMinor, int roundCount, List<UUID> orderIds) {}
}
