package uz.horecaos.platform.ordering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import uz.horecaos.platform.dinein.api.TableBindingPort;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A {@link TableBindingPort} whose room is a few fields, for the suite that owns the
 * checkout fixture (ADR 0047's cart-to-table binding).
 *
 * <p>Ordering asks the port three questions and this answers each from state the
 * test sets, and remembers what it was asked to write. The one thing it does that a
 * bare stub would not is look, at the moment it is asked to attach, at whether the
 * order row is already visible <em>on the caller's own connection</em>: a real
 * adapter that ran outside checkout's transaction would not see the uncommitted
 * order, and would attach nothing. That reading is what lets a test say "in the
 * same transaction" and mean it.
 *
 * <p>The real adapter, over the real tables and the real session, is proved against
 * PostgreSQL in {@code DineInTests} and {@code CartTableBindingHttpTests}.
 */
final class RecordingTableBinding implements TableBindingPort {

    /** One write ordering asked for. */
    record Attach(UUID tenantId, UUID tableId, UUID orderId, UUID ownerAccountId, boolean orderRowVisible) {}

    /** One write an operator's placement asked for: an order onto a named session's bill. */
    record SessionAttach(
            UUID tenantId,
            UUID locationId,
            UUID sessionId,
            UUID orderId,
            String actorSubject,
            boolean orderRowVisible) {}

    private final JdbcClient jdbc;

    /** The sessions {@link #requireLiveSession} accepts when no {@link #delegate} answers instead. */
    final Set<UUID> liveSessions = new HashSet<>();

    final List<SessionAttach> sessionAttaches = new ArrayList<>();

    final Map<String, GuestTable> guests = new HashMap<>();
    final Set<UUID> seated = new HashSet<>();
    final List<Attach> attaches = new ArrayList<>();

    /** When set, {@link #attachRound} records the call and then throws it. */
    @Nullable
    RuntimeException refuseAttach;

    /**
     * When set, {@link #isSeated} and {@link #attachRound} are answered by it (after
     * recording), so a suite can run a checkout against the real dine-in adapter and
     * still see what ordering asked it. {@link #resolveGuestTable} stays this class's:
     * the guest tokens are minted by the real scan in {@code CartTableBindingHttpTests}.
     * {@link #findGuestTable} is this class's too, until {@link #delegateGuestLookups}
     * is set, so a test can present a token the real store minted and then revoked.
     */
    @Nullable
    TableBindingPort delegate;

    /** When set (and a {@link #delegate} is), {@link #findGuestTable} asks the real adapter. */
    boolean delegateGuestLookups;

    RecordingTableBinding(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void reset() {
        guests.clear();
        seated.clear();
        attaches.clear();
        liveSessions.clear();
        sessionAttaches.clear();
        refuseAttach = null;
        delegate = null;
        delegateGuestLookups = false;
    }

    @Override
    public GuestTable resolveGuestTable(String guestToken) {
        GuestTable table = guests.get(guestToken);
        if (table == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "This table session has ended. Scan the code again.");
        }
        return table;
    }

    @Override
    public Optional<GuestTable> findGuestTable(String guestToken) {
        TableBindingPort real = delegate;
        if (real != null && delegateGuestLookups) {
            return real.findGuestTable(guestToken);
        }
        return Optional.ofNullable(guests.get(guestToken));
    }

    @Override
    public void requireLiveSession(UUID tenantId, UUID locationId, UUID sessionId) {
        TableBindingPort real = delegate;
        if (real != null) {
            real.requireLiveSession(tenantId, locationId, sessionId);
            return;
        }
        if (!liveSessions.contains(sessionId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "That party has left, so there is no bill to put the order on",
                    Map.of("conflict", "SESSION_NOT_LIVE", "reason", "SESSION_NOT_LIVE"));
        }
    }

    @Override
    public void attachRoundToSession(
            UUID tenantId, UUID locationId, UUID sessionId, UUID orderId, String actorSubject, String reason) {
        Long visible = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId)
                .param("id", orderId)
                .query(Long.class)
                .single();
        sessionAttaches.add(new SessionAttach(
                tenantId, locationId, sessionId, orderId, actorSubject, visible != null && visible == 1L));
        RuntimeException refusal = refuseAttach;
        if (refusal != null) {
            throw refusal;
        }
        TableBindingPort real = delegate;
        if (real != null) {
            real.attachRoundToSession(tenantId, locationId, sessionId, orderId, actorSubject, reason);
        }
    }

    @Override
    public boolean isSeated(UUID tenantId, UUID tableId) {
        TableBindingPort real = delegate;
        return real != null ? real.isSeated(tenantId, tableId) : seated.contains(tableId);
    }

    @Override
    public void attachRound(UUID tenantId, UUID tableId, UUID orderId, UUID ownerAccountId) {
        Long visible = jdbc.sql("SELECT count(*) FROM ordering.orders WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId)
                .param("id", orderId)
                .query(Long.class)
                .single();
        attaches.add(new Attach(tenantId, tableId, orderId, ownerAccountId, visible != null && visible == 1L));
        RuntimeException refusal = refuseAttach;
        if (refusal != null) {
            throw refusal;
        }
        TableBindingPort real = delegate;
        if (real != null) {
            real.attachRound(tenantId, tableId, orderId, ownerAccountId);
        }
    }
}
