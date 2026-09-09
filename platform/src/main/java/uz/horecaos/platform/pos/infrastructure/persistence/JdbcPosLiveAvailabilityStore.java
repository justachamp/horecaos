package uz.horecaos.platform.pos.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;

/**
 * {@code integration.pos_live_availability} (ADR 0012, V0190).
 *
 * <p>Persistence only, matching every other store in this package: no
 * {@code @Transactional} and no outbox call lives here. {@link #replace} must run
 * inside a transaction its caller opens — {@link
 * uz.horecaos.platform.pos.application.PosAvailabilityPollService} does, on its own
 * bean, for the exact reason {@code JdbcPosScheduleStore}'s own class doc gives.
 *
 * <p>V0190's own comment settles this table's shape and it is not reopened here:
 * no {@code run_id}, no history, current provider-stated availability replaced
 * wholesale per binding on every poll. {@link #replace} is that replacement made
 * real.
 */
@Repository
public class JdbcPosLiveAvailabilityStore {

    private final JdbcClient jdbc;

    public JdbcPosLiveAvailabilityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every active binding this build should poll for availability, across every
     * tenant.
     *
     * <p>Cross-tenant and unbounded, deliberately: unlike {@code
     * JdbcPosScheduleStore#dueScheduleIds}, this feed has no per-binding due time to
     * page through (V0190's own comment refuses one) and the pilot's binding count
     * is small enough that scanning all of them every tick needs no batching yet —
     * see {@code PosAvailabilityPoll}'s own doc on why that is an accepted limit
     * and not an oversight.
     */
    public List<Candidate> eligibleBindings() {
        return jdbc.sql("""
                SELECT b.id AS binding_id, b.tenant_id, b.installation_id, b.brand_id,
                       b.location_id, i.provider_type
                  FROM integration.bindings b
                  JOIN integration.installations i
                    ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                  JOIN integration.binding_capabilities bc
                    ON bc.tenant_id = b.tenant_id AND bc.binding_id = b.id
                 WHERE b.status = 'ACTIVE'
                   AND i.status = 'ACTIVE'
                   AND bc.capability_code = 'AVAILABILITY_READ'
                   AND bc.enabled
                 ORDER BY b.tenant_id, b.id
                """)
                .query((row, number) -> new Candidate(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("binding_id", UUID.class),
                        row.getObject("installation_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("provider_type")))
                .list();
    }

    /**
     * Replaces one binding's whole reading, and reports which entities crossed
     * the out-of-stock line so the caller can propagate exactly those.
     *
     * <p><b>The wholesale part is the point.</b> An entity absent from {@code
     * entries} is deleted here, not left in place — leaving it would turn a
     * product that came back into stock into one that stays 86'd forever, which
     * is the exact inversion of "absence means unconstrained" this table's own
     * comment and {@code pos_staged_availability}'s both warn against. The
     * {@code DELETE ... RETURNING} below is what makes that safe to compute
     * without a second read: it names, in the same statement that removes them,
     * exactly which stale rows were out of stock before they were removed.
     *
     * <p>Must run inside a transaction the caller opened. The delete and the
     * upserts are one unit: a failure between them must not leave some entities
     * removed and others not, which a reader would misinterpret as a set of
     * spurious "back in stock" signals.
     *
     * <p><b>Two replicas polling the same binding, and why this takes an
     * advisory lock rather than {@code FOR UPDATE SKIP LOCKED}.</b> {@code
     * JdbcPosScheduleStore#claimDue} locks a row so a loser does no work at all
     * — right for a due occurrence, where the loser genuinely has nothing to do.
     * Here every binding is due every tick, so there is no row to skip past and
     * {@code SKIP LOCKED} would just mean the loser silently does not poll this
     * tick, for no benefit. The read-modify-write below still needs mutual
     * exclusion for a different reason, and it is a real bug this class's own
     * two-replica test caught before this lock existed: the delete and the
     * upserts are two separate statements, and without a lock a second
     * replica's {@code DELETE} can run against a snapshot that has not yet seen
     * the first replica's still-uncommitted inserts, deleting nothing where it
     * should have deleted a row the first replica is about to write — the
     * result is a row from each replica's reading surviving together, which is
     * not "the reading is a poll late", it is a reading that was never reported
     * by anybody. {@code SELECT ... FOR UPDATE} cannot fix this either: it locks
     * rows that exist, and the row a phantom insert is about to create does not
     * exist yet to be locked. {@code pg_advisory_xact_lock} locks the *binding*,
     * not a row, so it serialises two replicas' whole read-modify-write against
     * each other regardless of what rows currently exist — released
     * automatically at commit or rollback, and taken only across this short
     * local transaction, never across the provider HTTP call that already
     * finished before the caller opened it.
     */
    public ReplaceResult replace(
            UUID tenantId, UUID bindingId, List<CatalogSnapshot.Availability> entries, Instant now) {
        // The row mapper never reads a column: pg_advisory_xact_lock's own
        // result type is void, and all this statement needs is to block until
        // the lock is granted -- consuming the single (empty) row is enough to
        // do that through the JDBC driver.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:bindingId))")
                .param("bindingId", bindingId.toString())
                .query((row, number) -> Boolean.TRUE)
                .list();

        Set<String> currentIds = new LinkedHashSet<>();
        for (CatalogSnapshot.Availability entry : entries) {
            currentIds.add(entry.externalId());
        }

        Set<String> previouslyOutOfStock = new HashSet<>();

        // Rows this poll still reports (so they will be upserted, not deleted)
        // but that were already at zero before this poll touched them. Read
        // before the upsert below overwrites stock_limit.
        if (!currentIds.isEmpty()) {
            previouslyOutOfStock.addAll(jdbc.sql("""
                    SELECT external_entity_id
                      FROM integration.pos_live_availability
                     WHERE tenant_id = :tenantId AND binding_id = :bindingId
                       AND stock_limit = 0
                       AND external_entity_id = ANY(:ids)
                    """)
                    .param("tenantId", tenantId)
                    .param("bindingId", bindingId)
                    .param("ids", currentIds.toArray(String[]::new))
                    .query(String.class)
                    .list());
        }

        // Rows this poll no longer reports at all -- deleted here, which is the
        // "absence means unconstrained" rule made real. RETURNING names what
        // each deleted row's own stock_limit was, so a formerly-zero one can be
        // told from a formerly-nonzero one without a second SELECT.
        List<DeletedRow> deleted = currentIds.isEmpty()
                ? jdbc.sql("""
                        DELETE FROM integration.pos_live_availability
                         WHERE tenant_id = :tenantId AND binding_id = :bindingId
                        RETURNING external_entity_id, stock_limit
                        """)
                        .param("tenantId", tenantId)
                        .param("bindingId", bindingId)
                        .query((row, number) -> new DeletedRow(
                                row.getString("external_entity_id"), row.getObject("stock_limit", BigDecimal.class)))
                        .list()
                : jdbc.sql("""
                        DELETE FROM integration.pos_live_availability
                         WHERE tenant_id = :tenantId AND binding_id = :bindingId
                           AND NOT (external_entity_id = ANY(:ids))
                        RETURNING external_entity_id, stock_limit
                        """)
                        .param("tenantId", tenantId)
                        .param("bindingId", bindingId)
                        .param("ids", currentIds.toArray(String[]::new))
                        .query((row, number) -> new DeletedRow(
                                row.getString("external_entity_id"), row.getObject("stock_limit", BigDecimal.class)))
                        .list();

        for (DeletedRow row : deleted) {
            if (row.stockLimit() != null && row.stockLimit().signum() == 0) {
                previouslyOutOfStock.add(row.externalId());
            }
        }

        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        Set<String> currentlyOutOfStock = new HashSet<>();
        for (CatalogSnapshot.Availability entry : entries) {
            if (entry.stockLimit() != null && entry.stockLimit().signum() == 0) {
                currentlyOutOfStock.add(entry.externalId());
            }
            jdbc.sql("""
                    INSERT INTO integration.pos_live_availability
                        (tenant_id, binding_id, external_entity_id, stock_limit, observed_at, updated_at)
                    VALUES (:tenantId, :bindingId, :externalId, :stockLimit, :observedAt, :now)
                    ON CONFLICT (binding_id, external_entity_id) DO UPDATE
                       SET stock_limit = EXCLUDED.stock_limit,
                           observed_at = EXCLUDED.observed_at,
                           updated_at = EXCLUDED.updated_at
                    """)
                    .param("tenantId", tenantId)
                    .param("bindingId", bindingId)
                    .param("externalId", entry.externalId())
                    .param("stockLimit", entry.stockLimit())
                    .param(
                            "observedAt",
                            entry.observedAt() == null
                                    ? null
                                    : OffsetDateTime.ofInstant(entry.observedAt(), ZoneOffset.UTC))
                    .param("now", nowUtc)
                    .update();
        }

        Set<String> newlyOutOfStock = new LinkedHashSet<>(currentlyOutOfStock);
        newlyOutOfStock.removeAll(previouslyOutOfStock);

        Set<String> newlyBackInStock = new LinkedHashSet<>(previouslyOutOfStock);
        newlyBackInStock.removeAll(currentlyOutOfStock);

        return new ReplaceResult(Set.copyOf(newlyOutOfStock), Set.copyOf(newlyBackInStock));
    }

    /**
     * The default variant a set of Clopos product ids resolves to, for the
     * bindings that have an ADR 0011 mapping.
     *
     * <p>The same join {@code JdbcPosTargetCatalog#products} uses for the daily
     * run: a mapping to {@code VARIANT_PARENT} names a product, and this feed
     * needs the sellable node inventory tracks, which is that product's default
     * variant. An external id absent from the map either has no mapping yet
     * (nobody has run a catalog sync for it) or maps to a product with no
     * default variant; either way the caller has nothing to toggle and treats it
     * as such.
     */
    public Map<String, UUID> resolveDefaultVariants(UUID tenantId, UUID bindingId, Set<String> externalProductIds) {
        if (externalProductIds.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> resolved = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT m.external_entity_id, dv.id AS variant_id
                  FROM integration.provider_entity_mappings m
                  JOIN catalog.products p
                    ON p.id = m.horecaos_entity_id AND p.tenant_id = m.tenant_id
                  JOIN catalog.variants dv
                    ON dv.product_id = p.id AND dv.tenant_id = p.tenant_id AND dv.is_default
                 WHERE m.tenant_id = :tenantId
                   AND m.binding_id = :bindingId
                   AND m.entity_type = 'VARIANT_PARENT'
                   AND m.status = 'ACTIVE'
                   AND m.external_entity_id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("ids", externalProductIds.toArray(String[]::new))
                .query((row, number) ->
                        Map.entry(row.getString("external_entity_id"), row.getObject("variant_id", UUID.class)))
                .list()
                .forEach(entry -> resolved.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(resolved);
    }

    /** Current rows for a binding, for tests and for an operator's read. */
    public List<LiveRow> currentReading(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                SELECT external_entity_id, stock_limit, observed_at
                  FROM integration.pos_live_availability
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId
                 ORDER BY external_entity_id
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) -> new LiveRow(
                        row.getString("external_entity_id"), row.getObject("stock_limit", BigDecimal.class)))
                .list();
    }

    private record DeletedRow(String externalId, @Nullable BigDecimal stockLimit) {}

    /** One binding worth polling for availability, resolved once per tick. */
    public record Candidate(
            UUID tenantId,
            UUID bindingId,
            UUID installationId,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String providerType) {}

    /**
     * Which entities changed which side of the out-of-stock line this poll,
     * i.e. exactly what {@code PosAvailabilityPoll} needs to propagate and
     * nothing it does not: an entity whose limit changed from one positive
     * number to another positive number is still sellable before and after, so
     * it is absent from both sets.
     */
    public record ReplaceResult(Set<String> newlyOutOfStock, Set<String> newlyBackInStock) {

        public boolean isEmpty() {
            return newlyOutOfStock.isEmpty() && newlyBackInStock.isEmpty();
        }
    }

    public record LiveRow(String externalId, @Nullable BigDecimal stockLimit) {}
}
