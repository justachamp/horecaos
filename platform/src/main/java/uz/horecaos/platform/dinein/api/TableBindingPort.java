package uz.horecaos.platform.dinein.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What ordering needs from a table to bind a guest's cart to it and to put the
 * order on the table's bill in the same transaction that creates it (ADR 0047,
 * "Extensions to existing decisions": a cart bound to the table).
 *
 * <p>The reverse of {@link OrderTablesPort}, and it runs the same way at the Java
 * level: ordering asks, dine-in answers from its own tables, and dine-in imports
 * nothing from ordering. Four questions about a guest's table, two more for an
 * operator who names the session, and nothing of the room, the guest or the bill
 * crosses back:
 *
 * <ol>
 *   <li>{@link #resolveGuestTable} -- which table a scanned table's guest token
 *       was minted for. The table is never a request field, so a guest cannot
 *       bind a cart to the next table by editing a body;
 *   <li>{@link #findGuestTable} -- the same question asked again at checkout, by a
 *       caller that must not throw: the binding is remembered state, and a token
 *       that has since ended or moved to another table is a reason to refuse the
 *       order, not a fault;
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

    /**
     * The table behind a guest token, or empty when the token cannot act now: it
     * has expired, was revoked (a party closed, a code rotated), was never minted,
     * or belongs to a branch that no longer takes QR orders.
     *
     * <p>{@link #resolveGuestTable} for a caller that has to keep going. It never
     * throws for a token, so it can be asked inside checkout's read-only validation
     * without marking that transaction rollback-only, and every reason a token
     * cannot act reads the same to the caller.
     */
    Optional<GuestTable> findGuestTable(String guestToken);

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

    /**
     * Refuses unless the session is a live one at this branch. The operator's
     * placement asks it before it creates anything, so a party that left while
     * the basket was being built costs the operator a screen and not a cooked
     * order that is on no bill.
     *
     * <p>Answers a session that is not at the branch exactly like one that does not
     * exist ({@code RESOURCE_NOT_FOUND}), so a session id cannot be probed across
     * branches, and a session that has ended with {@code RESOURCE_CONFLICT}
     * ({@code conflict}/{@code reason}: {@code SESSION_NOT_LIVE}).
     */
    void requireLiveSession(UUID tenantId, UUID locationId, UUID sessionId);

    /**
     * Puts an order on a named session's bill, for the operator who chose the
     * session (the New Order screen's table picker) rather than a guest whose table
     * decides it.
     *
     * <p>Joins the caller's transaction and refuses exactly as {@link
     * #requireLiveSession} does, then as {@link #attachRound} does for an order that
     * is not a DINE_IN order of this branch in the session's currency. There is no
     * owner check: the caller holds {@code dinein.session.manage} at the branch,
     * which is what lets a manager put a phone order on a table's bill, and it is
     * the operator who placed the order in this same transaction.
     */
    void attachRoundToSession(
            UUID tenantId, UUID locationId, UUID sessionId, UUID orderId, String actorSubject, String reason);

    /** A table a guest token resolved to. Facts about the room, never about the guest. */
    record GuestTable(UUID tenantId, UUID brandId, UUID locationId, UUID tableId, String tableCode) {}
}
