package uz.horecaos.platform.configuration.rls;

import java.util.UUID;

/**
 * ADR 0056's "transaction template": the seam every service method that touches
 * a row-level-security-protected table calls at the start of its transaction,
 * before it does anything else.
 *
 * <p>PostgreSQL row-level security is the backstop beneath this platform's
 * application-enforced tenant isolation (ADR 0056). A policy on a protected
 * table reads one session-scoped setting to decide which rows exist for the
 * current statement; nothing in a JDBC connection sets that by itself, so
 * something has to, on every transaction, for every tenant that ever touches
 * the connection a pool hands out next. This interface is that something.
 *
 * <p>Two operations, not one, because "cross-tenant" is not a single case:
 *
 * <ul>
 *   <li>{@link #bindTenant(UUID)} is the ordinary path — a request, or a
 *       background step already scoped to one tenant, states which tenant it
 *       is and gets exactly that tenant's rows for the rest of the
 *       transaction.
 *   <li>{@link #bindPlatform()} is the exception, named as one: a legitimate
 *       cross-tenant reader or writer (a scheduled sweep, the outbox relay, a
 *       reporting rollup, a control-plane operation) says so explicitly and
 *       gets every tenant's rows, because PostgreSQL's {@code BYPASSRLS} is an
 *       all-or-nothing attribute with no notion of "bypass this table but not
 *       that one" — see V0161's role comment for why the narrowing lives in
 *       who may ask for this and for how long, not in what the role is
 *       granted on.
 * </ul>
 *
 * <p>Both bindings are transaction-scoped by construction (see the JDBC
 * implementation): PostgreSQL itself reverts them at {@code COMMIT} or
 * {@code ROLLBACK}, which is what makes calling either of these safe on a
 * pooled connection. A binding that outlived its transaction would be a
 * tenant, or a bypass, leaking onto whatever request the pool hands the same
 * physical connection to next — worse than having no row-level security at
 * all, because it would look like isolation while providing none.
 */
public interface TenantRlsSession {

    /** Every visible and writable row for the rest of this transaction is this tenant's. */
    void bindTenant(UUID tenantId);

    /**
     * Every tenant's rows are visible and writable for the rest of this
     * transaction, through {@code horecaos_platform_bypass} (V0161).
     *
     * <p>Reach for this only for a reader or writer that is cross-tenant by
     * design — {@link uz.horecaos.platform.inventory.application.InventoryService#expireStaleReservations()}
     * is the first one wired to it. A missing {@code WHERE tenant_id = ?} is
     * not this case; that is exactly the bug row-level security exists to
     * catch, and calling this from that bug would defeat the backstop the
     * same way a forgotten predicate defeats the application-level one.
     */
    void bindPlatform();
}
