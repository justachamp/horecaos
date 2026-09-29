package uz.horecaos.platform.dinein.api;

import java.util.UUID;

/**
 * What ordering needs from a table to bind a guest's cart to it and to put the
 * order on the table's bill in the same transaction that creates it (ADR 0047,
 * "Extensions to existing decisions": a cart bound to the table).
 *
 * <p>The reverse of {@link OrderTablesPort}, and it runs the same way at the Java
 * level: ordering asks, dine-in answers from its own tables, and dine-in imports
 * nothing from ordering. Three questions, and nothing of the room, the guest or
 * the bill crosses back:
 *
 * <ol>
 *   <li>{@link #resolveGuestTable} -- which table a scanned table's guest token
 *       was minted for. The table is never a request field, so a guest cannot
 *       bind a cart to the next table by editing a body;
 *   <li>{@link #isSeated} -- whether somebody is sitting there now, asked by
 *       checkout's read-only validation before anything is written;
 *   <li>{@link #attachRound} -- the write, made inside checkout's own
 *       transaction, so the order exists on the bill or does not exist at all.
 * </ol>
 */
public interface TableBindingPort {

    /**
     * The table behind a guest token.
     *
     * <p>Refuses with the same not-found a mistyped code gets when the branch is not
     * taking QR orders (a {@code VIEW_ONLY} code has no cart to bind), and with the
     * unauthenticated a token that has ended or been revoked gets -- the guest's cue
     * to scan again.
     */
    GuestTable resolveGuestTable(String guestToken);

    /** Whether a live session sits at the table right now. Tenant is a predicate. */
    boolean isSeated(UUID tenantId, UUID tableId);

    /**
     * Puts an order on the bill of the session sitting at the table.
     *
     * <p>Joins the caller's transaction. Refuses, rolling the caller back with it,
     * when nobody is seated ({@code conflict: TABLE_NOT_SEATED}), when the order is
     * not a DINE_IN order of this branch in the session's currency, or when the
     * order is not {@code ownerAccountId}'s own -- the ownership fact checkout
     * already holds and the table token alone can never prove.
     */
    void attachRound(UUID tenantId, UUID tableId, UUID orderId, UUID ownerAccountId);

    /** A table a guest token resolved to. Facts about the room, never about the guest. */
    record GuestTable(UUID tenantId, UUID brandId, UUID locationId, UUID tableId, String tableCode) {}
}
