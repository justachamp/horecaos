package uz.horecaos.platform.ordering.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The status of each order's fiscal document, for the order board's optional «Ф» chip (gap map row
 * {@code 1.1}, orders.md §2.5 «behind the picker», ADR 0144's last open item).
 *
 * <p>Declared in {@code ordering}'s {@code api} and implemented by {@code fiscal}, the direction every
 * other port of this kind takes: {@code fiscal} already depends on {@code ordering}, so a port the other
 * way round would be a cycle. One call answers a whole board page, asked only when the person turned the
 * column on.
 *
 * <p>What it carries is the <em>status word</em> and nothing else. A fiscal sign, a receipt URL and a
 * marking code are ADR 0029 protected evidence and are read through the fiscal evidence surface under a
 * recorded purpose, never through a list row.
 */
public interface OrderFiscalStatusPort {

    /**
     * The status of the order's sale document, or of its newest document when it has no sale one
     * ({@code fiscal.fiscal_documents.status}'s own vocabulary). An order with no fiscal document at
     * all -- which is a different fact from {@code NOT_APPLICABLE}, a recorded decision that no receipt
     * is owed -- is absent from the result, and the console draws no chip.
     */
    Map<UUID, String> fiscalStatusByOrders(UUID tenantId, Collection<UUID> orderIds);
}
