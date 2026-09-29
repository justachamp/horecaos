package uz.horecaos.platform.ordering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private final JdbcClient jdbc;

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
     */
    @Nullable
    TableBindingPort delegate;

    RecordingTableBinding(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void reset() {
        guests.clear();
        seated.clear();
        attaches.clear();
        refuseAttach = null;
        delegate = null;
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
