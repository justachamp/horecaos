package uz.horecaos.platform.dinein.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.dinein.domain.BearerToken;
import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.GuestSessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SettingsRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The QR entry point (ADR 0047).
 *
 * <p>A QR code is a bearer token pointing at a table. Anybody who photographs it
 * can order, which is how the category works; what this class guarantees is the
 * three things ADR 0047 asks for around that. The printed token is unguessable —
 * 128 uniform bits, stored only as a digest, encoding nothing. It is revocable per
 * table — rotation writes a new digest and kills the guest tokens minted from the
 * old one in the same transaction. And it carries nothing a guest can tamper with
 * to reach another table or another tenant, because it carries nothing at all:
 * every binding is a column on a row the digest finds, never a claim in the token.
 *
 * <p>Scanning authorises nothing by itself. It exchanges the printed token for a
 * short-lived, table-scoped guest token, and that is what the storefront accepts
 * afterwards. The separation is what makes the printed code revocable: a design
 * that accepted the table token on every request would have nothing to invalidate
 * short of a printer.
 *
 * <p>Failure is uniform. An unknown digest, a rotated one, an archived table and a
 * branch that takes no QR orders all produce the same refusal, because a response
 * that distinguished them would tell somebody holding a guessed token which half
 * of the guess was right.
 */
@Service
public class QrEntryService {

    /**
     * Per-token, not per-source. Enumerating 2^128 is not a threat model, so this
     * is not an anti-guessing control and does not pretend to be; it is the limit
     * on one photographed code being hammered, and the coarse per-address limit
     * that catches volumetric abuse belongs to the edge, per ADR 0033's own
     * division of labour.
     */
    private static final RateLimiter.Policy EXCHANGE_LIMIT = RateLimiter.Policy.strictPerMinute(20);

    private static final String EXCHANGE_OPERATION = "dinein.qr.exchange";

    private final JdbcDineInStore store;
    private final FloorPlanService floorPlan;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public QrEntryService(JdbcDineInStore store, FloorPlanService floorPlan, RateLimiter rateLimiter, Clock clock) {
        this.store = store;
        this.floorPlan = floorPlan;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * The result of a successful scan: a short-lived guest token and everything
     * the storefront needs to render the table it points at.
     *
     * @param guestToken returned once and never again. The client holds it for the
     *                   evening and presents it on every subsequent call
     */
    public record GuestAdmission(
            String guestToken,
            Instant expiresAt,
            QrMode mode,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID tableId,
            String tableCode,
            @Nullable UUID openSessionId) {}

    /** What a resolved guest token is allowed to see, with no token in it. */
    public record GuestContext(
            UUID tenantId, UUID brandId, UUID locationId, UUID tableId, QrMode mode, Instant expiresAt) {}

    /**
     * Exchanges a printed table token for a guest token.
     *
     * <p>The rate limit is applied before the lookup and keyed on the digest rather
     * than on anything about the caller. That is deliberate on both counts: no
     * network address is taken, because ADR 0029 keeps that kind of data out of
     * this module entirely, and limiting before the lookup means a flood of
     * requests carrying one token costs one index probe rather than one per
     * request.
     */
    @Transactional
    public GuestAdmission exchange(String printedToken) {
        String hash = BearerToken.hash(requireToken(printedToken));

        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(EXCHANGE_OPERATION, null, hash), EXCHANGE_LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many scans of this code. Try again shortly.");
        }

        TableRow table = store.findTableByQrToken(hash).orElseThrow(QrEntryService::refuse);

        SettingsRow settings = floorPlan.settings(table.tenantId(), table.brandId(), table.locationId());

        if (settings.qrMode() == null || !settings.qrMode().selectable()) {
            throw refuse();
        }

        Instant now = clock.instant();
        BearerToken.Issued guest = BearerToken.issue();
        Instant expiresAt = now.plus(Duration.ofMinutes(settings.guestSessionTtlMinutes()));

        store.insertGuestSession(new GuestSessionRow(
                UUID.randomUUID(),
                table.tenantId(),
                table.brandId(),
                table.locationId(),
                table.id(),
                guest.hash(),
                settings.qrMode(),
                now,
                expiresAt,
                null,
                null));

        // The live session at this table, if any, so the guest sees the running
        // bill their own party has already built rather than starting an evening
        // the waiter already started for them. Null in VIEW_ONLY, where HorecaOS
        // creates nothing at all.
        UUID openSession = settings.qrMode() == QrMode.ORDER_AND_PAY
                ? store.findLiveSessionAtTable(table.tenantId(), table.id())
                        .map(SessionRow::id)
                        .orElse(null)
                : null;

        return new GuestAdmission(
                guest.plaintext(),
                expiresAt,
                settings.qrMode(),
                table.tenantId(),
                table.brandId(),
                table.locationId(),
                table.id(),
                table.code(),
                openSession);
    }

    /**
     * Resolves a presented guest token to the table it was minted for.
     *
     * <p>The table is never taken from the request. A guest token that named its
     * own table would be a claim a client could edit, and editing it would reach
     * the next table's bill — which ADR 0047's exit criteria forbid in as many
     * words.
     */
    public GuestContext resolve(String guestToken) {
        Instant now = clock.instant();
        GuestSessionRow guest = store.findLiveGuestSession(BearerToken.hash(requireToken(guestToken)), now)
                .orElseThrow(QrEntryService::refuseGuest);

        return new GuestContext(
                guest.tenantId(),
                guest.brandId(),
                guest.locationId(),
                guest.tableId(),
                guest.qrMode(),
                guest.expiresAt());
    }

    /**
     * Asserts that a guest token may act on a session.
     *
     * <p>The check is that the session is live at the guest's own table, resolved
     * from the token rather than compared against a session id the client supplied
     * alone. Reversing that — trusting the session id and checking it looks
     * plausible — is how a guest reaches a neighbouring party's bill.
     */
    public SessionRow requireSessionAtTable(GuestContext guest, UUID sessionId) {
        Optional<SessionRow> live = store.findLiveSessionAtTable(guest.tenantId(), guest.tableId());

        return live.filter(session -> session.id().equals(sessionId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No open bill at this table"));
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw refuse();
        }
        return token;
    }

    /**
     * One refusal for every reason a table token can fail.
     *
     * <p>Not found, rotated, archived, branch not configured: all the same
     * response. Distinguishing them would let somebody holding a token learn
     * whether it was ever valid, and a 404 that means "this used to work" is a
     * confirmation oracle on a value printed in a public room.
     */
    private static ApiException refuse() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This code is not in service. Ask a member of staff.");
    }

    private static ApiException refuseGuest() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "This table session has ended. Scan the code again.");
    }
}
