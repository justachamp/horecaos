package uz.horecaos.platform.ordering;

import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.dinein.api.TableBindingPort;

/**
 * The {@link TableBindingPort} for the ordering suites that hand-wire a {@code
 * CartService} and never bind a cart to a table (ADR 0047).
 *
 * <p>Refuses every call rather than answering "nobody is seated": a suite that
 * reaches it has started exercising the binding without wiring the real port,
 * and a silent {@code false} would let it pass on a lie. The binding itself is
 * tested end to end, over HTTP and against the real dine-in adapter, in {@code
 * CartTableBindingHttpTests}.
 */
final class UnusedTableBinding implements TableBindingPort {

    @Override
    public GuestTable resolveGuestTable(String guestToken) {
        throw new UnsupportedOperationException("This suite does not bind carts to tables");
    }

    @Override
    public Optional<GuestTable> findGuestTable(String guestToken) {
        throw new UnsupportedOperationException("This suite does not bind carts to tables");
    }

    @Override
    public boolean isSeated(UUID tenantId, UUID tableId) {
        throw new UnsupportedOperationException("This suite does not bind carts to tables");
    }

    @Override
    public void attachRound(UUID tenantId, UUID tableId, UUID orderId, UUID ownerAccountId) {
        throw new UnsupportedOperationException("This suite does not bind carts to tables");
    }
}
