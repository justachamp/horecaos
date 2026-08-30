package uz.qoida.platform.customers.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.qoida.platform.customers.api.CustomerIdentityPolicy;
import uz.qoida.platform.customers.application.CustomerPolicyLookup;

/**
 * Reads a tenant's identity policy (ADR 0015).
 *
 * <p><strong>From {@code tenant.customer_identity_policies}, the versioned table
 * the control plane writes.</strong> This used to read a denormalised
 * {@code tenant.tenants.customer_identity_policy} column, which no code in the
 * repository ever wrote: it kept its {@code NOT NULL DEFAULT 'TENANT_SHARED'}
 * forever, so a tenant that configured {@code BRAND_ISOLATED} got shared
 * partitioning and one person's profile, addresses and order history were
 * visible across brands meant to be separate businesses. The control plane
 * answered success and the row it wrote was real — just not the row this class
 * read. V0060 backfilled that column and had a trigger mirror it; V0072 dropped
 * it, because a stored copy of the mode cannot be correct: a trigger fires on a
 * write, and which policy row governs changes at a scheduled cutover, an instant
 * at which nothing writes. There is now one place the mode is, and this is the
 * class that reads it.
 *
 * <p>The versioned table is the honest authority for a second reason: it is the
 * only one that can answer "when did this tenant change mode", which is the
 * first question asked when two accounts turn out to have merged.
 *
 * <p><strong>Not cached, deliberately.</strong> This runs per customer resolution
 * and the temptation is obvious, but ADR 0033 puts PostgreSQL as the authority
 * and keeps correctness decisions out of cache state — and which partition a
 * customer is looked up in is a data-boundary decision, not a display detail. A
 * stale entry on one replica after a governed mode change is the same silent
 * cross-brand exposure this class was fixed for. The read is a handful of rows
 * for one tenant — a tenant's whole policy history is the number of governed
 * mode changes it has ever made — inside the transaction that is about to write
 * the account anyway. If it ever measures as a problem, ADR 0033 wants it
 * registered in {@code CacheRegistry} with a tenant-scoped key and a declared
 * invalidation source, not a bare {@code @Cacheable}.
 *
 * <p>Defaults to {@link CustomerIdentityPolicy#TENANT_SHARED} when a tenant has
 * not chosen. That is the safer default of the two: a shared account can be split
 * by a governed migration, whereas isolated accounts that should have been shared
 * leave a customer unable to see their own history at a sibling brand and no way
 * to prove which accounts belong together. It reports that default with a null
 * version, because no decision was made and naming one would invent a history.
 *
 * <p><strong>The version travels with the mode.</strong> Both come out of the
 * same row, and the account being written records both. They were split before:
 * the mode was read here and the version was manufactured downstream from the
 * enum's ordinal, which agreed with reality only for as long as every tenant
 * resolved TENANT_SHARED.
 *
 * <p>"Which row is current" is not decided here. It is
 * {@code tenant.current_customer_identity_policy} (V0063), the one definition
 * this class and {@code JdbcTenantControlPlaneStore} both read through — they
 * used to carry a copy each, and both copies matched on {@code superseded_at IS
 * NULL} alone, so a policy row dated for a future cutover took effect the moment
 * it was inserted. A third copy outlived them inside V0060's mirror trigger,
 * with the same defect; V0072 removed it by removing what it maintained. Ask the
 * function, at the instant you mean.
 */
@Component
public class ConfiguredCustomerPolicyLookup implements CustomerPolicyLookup {

    private final JdbcClient jdbc;

    public ConfiguredCustomerPolicyLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResolvedIdentityPolicy policyFor(UUID tenantId, Instant at) {
        return jdbc.sql("""
                SELECT policy_version, identity_mode
                FROM tenant.current_customer_identity_policy(:tenantId, :at)
                """)
                .param("tenantId", tenantId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((rs, rowNum) -> new ResolvedIdentityPolicy(
                        CustomerIdentityPolicy.valueOf(rs.getString("identity_mode")),
                        rs.getInt("policy_version")))
                .optional()
                .orElseGet(ResolvedIdentityPolicy::unconfigured);
    }
}
