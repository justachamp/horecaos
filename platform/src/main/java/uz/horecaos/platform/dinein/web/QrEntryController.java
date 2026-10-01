package uz.horecaos.platform.dinein.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.dinein.application.QrEntryService;
import uz.horecaos.platform.dinein.application.QrEntryService.GuestAdmission;
import uz.horecaos.platform.dinein.application.QrEntryService.GuestContext;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.application.WalkInSeatingService;
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
 * below resolves it to a row before doing anything. {@code addRound} is the one
 * exception, and only for the second credential it checks: the guest token still
 * proves the table, but attaching a round also checks the caller's ordinary
 * customer session against the order's own owner (see that handler's {@code
 * requireOwnOrder}) -- because the table token alone names a table, never a
 * specific order, and a guest device that could attach any order at the branch
 * would reach another table's bill exactly as ADR 0047's exit criteria forbid.
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
    private final WalkInSeatingService walkIn;
    private final CurrentCustomer currentCustomer;

    public QrEntryController(
            QrEntryService qr,
            TableSessionService sessions,
            WalkInSeatingService walkIn,
            CurrentCustomer currentCustomer) {
        this.qr = qr;
        this.sessions = sessions;
        this.walkIn = walkIn;
        this.currentCustomer = currentCustomer;
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
                admission.openSessionId(),
                admission.channelCode(),
                admission.walkInAvailable()));
    }

    @PostMapping("/sessions")
    @Operation(
            summary = "Seat yourself at a free table",
            description = "A guest who has scanned a free table opens a provisional session -- a "
                    + "claim -- without waiting for staff (ADR 0143). It is an explicit action, "
                    + "never a side effect of scanning, and it needs both credentials a round "
                    + "does: the table's guest token (X-Dine-In-Token, the proof of where) and "
                    + "the customer's ordinary signed-in session (Authorization: Bearer, the "
                    + "proof of who). It never accepts a table, a location or a tenant. The claim "
                    + "becomes an ordinary session once a round the restaurant has accepted is "
                    + "on it, and lapses -- giving the table back to the room -- if nothing "
                    + "follows within its window. If somebody already sits at the table, the "
                    + "call answers with their session and created=false rather than a second "
                    + "one. Every reason the table cannot be taken (the branch has not turned "
                    + "this on, a booking holds the table, a cap is reached, the account is "
                    + "refused, the table is out of service) is one answer, 409 "
                    + "TABLE_NOT_AVAILABLE, with no reason.")
    public ResponseEntity<GuestSeatingResponse> seat(
            @RequestHeader(GUEST_TOKEN_HEADER) String guestToken, @Valid @RequestBody SeatRequest body) {

        // Both credentials are required before anything is attempted; the table comes
        // from the first, the person from the second, and neither from the request.
        GuestContext guest = qr.resolve(guestToken);
        requireOrdering(guest);
        CustomerAccountRef caller = requireSignedIn(guest);

        WalkInSeatingService.Seating seating = walkIn.seat(guestToken, caller.accountId(), body.partySize());
        GuestBillResponse bill = billResponse(guest, seating.session());
        return ResponseEntity.ok(GuestSeatingResponse.of(bill, seating.created()));
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

        return ResponseEntity.ok(GuestBillResponse.of(
                session,
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
        if (session.unconfirmedClaim()) {
            // There is nothing the restaurant has accepted to bill (ADR 0143, Decision
            // 4), and a claim moved out of OPEN is a claim the lapse has to reach by a
            // longer road. Refused, not hidden: the storefront does not offer the control
            // while a claim is unconfirmed, and a caller that does it anyway is told why.
            throw TableSessionService.claimUnconfirmed();
        }
        if (session.status() == SessionStatus.BILL_REQUESTED) {
            // Idempotent by observation rather than by an idempotency key. A guest
            // tapping twice is not an error and must not read as one; ADR 0031's
            // key belongs on operator mutations, and there is no operator here.
            return ResponseEntity.ok(billResponse(guest, session));
        }

        SessionRow moved = sessions.moveByGuest(
                guest.tenantId(),
                sessionId,
                SessionStatus.BILL_REQUESTED,
                session.version(),
                guest.tableId(),
                "Requested from the table");

        return ResponseEntity.ok(billResponse(guest, moved));
    }

    @PostMapping("/sessions/{sessionId}/rounds")
    @Operation(
            summary = "Attach a just-placed order to the table's bill",
            description = "A cart bound to the table (PUT .../carts/{cartId}/table) is put on "
                    + "this bill by checkout itself, in the transaction that creates the order, "
                    + "and needs no call here. This is the attach for a cart that was never "
                    + "bound: the order was already priced, reserved and confirmed by the "
                    + "ordinary checkout, and this only records that it belongs to this table's "
                    + "evening -- the exact write TableSessionController's operator endpoint "
                    + "makes, reached here through the guest's own token instead of a "
                    + "capability. Calling it "
                    + "twice with the same order is not an error: the second call finds the order "
                    + "already on this bill and answers with the bill unchanged. The guest token "
                    + "alone proves the caller is at this table, not that the order named in the "
                    + "body is theirs, so this also requires the ordinary signed-in session "
                    + "(Authorization: Bearer) the checkout that created the order was placed "
                    + "under -- see requireOwnOrder's own doc.")
    public ResponseEntity<GuestBillResponse> addRound(
            @PathVariable UUID sessionId,
            @RequestHeader(GUEST_TOKEN_HEADER) String guestToken,
            @Valid @RequestBody AddRoundRequest body) {

        GuestContext guest = qr.resolve(guestToken);
        requireOrdering(guest);

        SessionRow session = qr.requireSessionAtTable(guest, sessionId);
        CustomerAccountRef caller = requireOwnOrder(guest);
        try {
            sessions.addRound(
                    guest.tenantId(),
                    sessionId,
                    body.orderId(),
                    caller.accountId(),
                    "guest:" + guest.tableId(),
                    "Placed from the table via QR checkout");
        } catch (ApiException alreadyOnABill) {
            // Idempotent by observation, like requestBill above: a retry after a
            // dropped response must not read as a failure when the round already
            // landed. Any other refusal (wrong branch, wrong fulfilment mode, no
            // such order) is a real one and propagates.
            if (!"ORDER_ALREADY_BILLED".equals(alreadyOnABill.properties().get("conflict"))) {
                throw alreadyOnABill;
            }
        }

        return ResponseEntity.ok(billResponse(guest, session));
    }

    /**
     * The signed-in customer whose checkout {@code addRound} is allowed to
     * attach.
     *
     * <p>The guest token this class otherwise runs on proves "this device
     * scanned this table" and nothing about which order is the caller's own --
     * every DINE_IN order still needs a signed-in customer to have been placed
     * at all (there is no anonymous path through {@code POST .../carts}), so
     * that same session is the one fact available here to check a round
     * against. Without it, any device holding a valid guest token for table A
     * could attach any not-yet-billed DINE_IN order at the branch -- including
     * one a guest at table B just placed -- onto table A's bill, which is
     * exactly the reach across tables ADR 0047's exit criteria forbid.
     *
     * <p>{@link CurrentCustomer#account} answers empty for a caller with no
     * session at all, and the underlying {@code CurrentActor} throws for a
     * request carrying neither a customer session nor a realm token -- both
     * translate to the same refusal here, since from the caller's side they
     * are the same fact: nobody is signed in.
     */
    private CustomerAccountRef requireOwnOrder(GuestContext guest) {
        return resolveCaller(guest, QrEntryController::unownedRound);
    }

    /**
     * The signed-in customer a claim is opened for (ADR 0143). The same
     * two-credential shape {@code addRound} uses: nobody signed in is nobody to hold
     * the claim, and an account with no standing at this brand reads the same way.
     */
    private CustomerAccountRef requireSignedIn(GuestContext guest) {
        return resolveCaller(guest, QrEntryController::unseatedGuest);
    }

    private CustomerAccountRef resolveCaller(GuestContext guest, Supplier<ApiException> refusal) {
        Optional<CustomerAccountRef> account;
        try {
            account = currentCustomer.account(guest.tenantId(), guest.brandId());
        } catch (AccessDeniedException noSession) {
            throw refusal.get();
        }
        return account.orElseThrow(refusal);
    }

    private static ApiException unownedRound() {
        return new ApiException(
                ErrorCode.UNAUTHENTICATED, "Attaching a round needs the signed-in session the order was placed under");
    }

    private static ApiException unseatedGuest() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "Sit at this table once you have signed in");
    }

    private GuestBillResponse billResponse(GuestContext guest, SessionRow session) {
        SessionBill bill = sessions.bill(guest.tenantId(), session.id());
        return GuestBillResponse.of(
                session,
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

    record AddRoundRequest(@NotNull UUID orderId) {}

    /**
     * The guest's own word on how many they are. A boxed {@code Integer}: Jackson 3
     * refuses a missing primitive, and an absent party size is a 422, not a zero.
     */
    record SeatRequest(@NotNull @Min(1) @Max(200) Integer partySize) {}

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
            @Nullable UUID openSessionId,
            /** See {@link QrEntryService.GuestAdmission#channelCode()}. */
            @Nullable String channelCode,
            /** See {@link QrEntryService.GuestAdmission#walkInAvailable()}. */
            boolean walkInAvailable) {}

    /**
     * The guest's running bill and the standing of the session it belongs to.
     *
     * @param origin         {@code STAFF} or {@code GUEST_QR} (ADR 0143)
     * @param claimExpiresAt when an unconfirmed claim lapses and gives the table back;
     *                       null for a session that is not an unconfirmed claim
     * @param confirmed      whether this is an ordinary session: always true for a
     *                       staff-opened one, and for a claim once a round the
     *                       restaurant accepted is on it or staff took charge
     */
    record GuestBillResponse(
            UUID sessionId,
            String status,
            String currency,
            long totalMinor,
            int roundCount,
            List<UUID> orderIds,
            String origin,
            @Nullable Instant claimExpiresAt,
            boolean confirmed) {

        static GuestBillResponse of(
                SessionRow session, String currency, long totalMinor, int roundCount, List<UUID> orderIds) {
            return new GuestBillResponse(
                    session.id(),
                    session.status().name(),
                    currency,
                    totalMinor,
                    roundCount,
                    orderIds,
                    session.origin().name(),
                    session.unconfirmedClaim() ? session.claimExpiresAt() : null,
                    !session.unconfirmedClaim());
        }
    }

    /** {@link GuestBillResponse} plus whether this very call opened the session. */
    record GuestSeatingResponse(
            UUID sessionId,
            String status,
            String currency,
            long totalMinor,
            int roundCount,
            List<UUID> orderIds,
            String origin,
            boolean created,
            @Nullable Instant claimExpiresAt,
            boolean confirmed) {

        static GuestSeatingResponse of(GuestBillResponse bill, boolean created) {
            return new GuestSeatingResponse(
                    bill.sessionId(),
                    bill.status(),
                    bill.currency(),
                    bill.totalMinor(),
                    bill.roundCount(),
                    bill.orderIds(),
                    bill.origin(),
                    created,
                    bill.claimExpiresAt(),
                    bill.confirmed());
        }
    }
}
