package uz.horecaos.platform.integration.marketplace;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the marketplace availability reconciler (ADR 0141, {@code V0463}/{@code V0464}).
 *
 * <p>Every statement carries {@code tenant_id}. The reconciler's two correctness-bearing
 * statements are written to be safe under overlapping runs: {@link #claimDue} takes a lease
 * with {@code FOR UPDATE SKIP LOCKED} so two workers claim disjoint rows, and every outcome
 * write is conditional on still holding the lease — a worker whose lease lapsed and was
 * re-claimed cannot overwrite what the new holder concluded.
 */
@Repository
public class JdbcMarketplaceAvailabilityStore {

    private static final String ITEM_COLUMNS = """
            tenant_id, binding_id, external_entity_id, variant_id, location_id,
            desired_available, desired_seq, desired_at, confirmed_available, confirmed_at,
            last_attempt_available, last_attempt_at, last_attempt_outcome,
            state, attempt_count, next_attempt_at, last_failure_code, pending_since
            """;

    private final JdbcClient jdbc;

    public JdbcMarketplaceAvailabilityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ bindings

    /**
     * Every active {@code MARKETPLACE} binding of a scheduled tenant, with what the
     * reconciler needs to turn it into a channel and a location. Cross-tenant by design: the
     * scheduler's worklist.
     */
    public List<BindingRow> activeMarketplaceBindings() {
        return jdbc.sql("""
                SELECT b.id AS binding_id, b.tenant_id, b.installation_id, b.brand_id, b.location_id,
                       i.provider_type, i.display_name
                FROM integration.bindings b
                JOIN integration.installations i
                  ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                WHERE i.provider_category = 'MARKETPLACE'
                  AND i.status = 'ACTIVE' AND b.status = 'ACTIVE'
                  AND b.location_id IS NOT NULL
                ORDER BY b.id
                """)
                .query((row, number) -> new BindingRow(
                        row.getObject("binding_id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("installation_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("display_name")))
                .list();
    }

    /** Active {@code MARKETPLACE} bindings of one brand, optionally of one location: what a marker must wake. */
    public List<UUID> bindingIdsOf(UUID tenantId, UUID brandId, @Nullable UUID locationId) {
        return jdbc.sql("""
                SELECT b.id
                FROM integration.bindings b
                JOIN integration.installations i
                  ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                WHERE b.tenant_id = :tenantId AND b.brand_id = :brandId
                  AND (CAST(:locationId AS uuid) IS NULL OR b.location_id = CAST(:locationId AS uuid))
                  AND i.provider_category = 'MARKETPLACE'
                  AND i.status = 'ACTIVE' AND b.status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query(UUID.class)
                .list();
    }

    /**
     * Active {@code MARKETPLACE} bindings of one installation: what a change to the sales channel
     * that installation backs must wake (ADR 0141 Decision 7's marker for a channel's installation).
     */
    public List<UUID> bindingIdsOfInstallation(UUID tenantId, UUID installationId) {
        return jdbc.sql("""
                SELECT b.id
                FROM integration.bindings b
                JOIN integration.installations i
                  ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                WHERE b.tenant_id = :tenantId AND b.installation_id = :installationId
                  AND i.provider_category = 'MARKETPLACE'
                  AND i.status = 'ACTIVE' AND b.status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query(UUID.class)
                .list();
    }

    /** The binding's mapped menu items: the partner's id for each HorecaOS variant. */
    public Map<UUID, String> mappedItems(UUID tenantId, UUID bindingId) {
        Map<UUID, String> byVariant = new HashMap<>();
        jdbc.sql("""
                SELECT horecaos_entity_id, external_entity_id
                FROM integration.provider_entity_mappings
                WHERE tenant_id = :tenantId AND binding_id = :bindingId
                  AND entity_type = 'MENU_ITEM' AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) ->
                        Map.entry(row.getObject("horecaos_entity_id", UUID.class), row.getString("external_entity_id")))
                .list()
                .forEach(entry -> byVariant.put(entry.getKey(), entry.getValue()));
        return byVariant;
    }

    // ------------------------------------------------------------------ sync state

    public Optional<SyncState> syncState(UUID bindingId) {
        return jdbc.sql("""
                SELECT binding_id, next_sweep_at, sweep_requested_at, reconcile_was_enabled, was_stale,
                       xmin::text AS row_version
                FROM integration.marketplace_availability_sync_state WHERE binding_id = :bindingId
                """)
                .param("bindingId", bindingId)
                .query((row, number) -> new SyncState(
                        row.getObject("binding_id", UUID.class),
                        instant(row.getObject("next_sweep_at", OffsetDateTime.class)),
                        instant(row.getObject("sweep_requested_at", OffsetDateTime.class)),
                        row.getBoolean("reconcile_was_enabled"),
                        row.getBoolean("was_stale"),
                        row.getString("row_version")))
                .optional();
    }

    /** Asks for an early sweep of these bindings (a marker). Idempotent. */
    public void requestSweep(UUID tenantId, Collection<UUID> bindingIds, Instant now) {
        for (UUID bindingId : bindingIds) {
            jdbc.sql("""
                    INSERT INTO integration.marketplace_availability_sync_state
                        (tenant_id, binding_id, sweep_requested_at, updated_at)
                    VALUES (:tenantId, :bindingId, :now, :now)
                    ON CONFLICT (binding_id) DO UPDATE
                    SET sweep_requested_at = COALESCE(
                            integration.marketplace_availability_sync_state.sweep_requested_at, EXCLUDED.sweep_requested_at),
                        updated_at = EXCLUDED.updated_at
                    """)
                    .param("tenantId", tenantId)
                    .param("bindingId", bindingId)
                    .param("now", timestamp(now))
                    .update();
        }
    }

    /**
     * Records that a full sweep ran and when the next one is due; clears the marker it honoured, and
     * only that one.
     *
     * <p>A marker is a promise that the next sweep will read an input that has just changed. A
     * marker written <em>after</em> this sweep looked -- a stop committed between its resolver read
     * and this statement -- has not been honoured, and wiping it would leave that change to wait for
     * the resync interval. So the marker is cleared only if the row is still the version the sweep
     * read before it began ({@code observedRowVersion}, {@link SyncState#rowVersion}): any writer of
     * the row since -- the listener, the mapping trigger (which coalesces into an older marker and so
     * leaves its value alone), another replica -- makes the sweep leave the marker for the next pass.
     * The worst a spurious difference costs is one more sweep.
     *
     * @param observedRowVersion the row version read before the sweep began, or null when there was no row
     */
    public void recordSweep(
            UUID tenantId,
            UUID bindingId,
            Instant now,
            Instant nextSweepAt,
            int itemCount,
            boolean reconcileEnabled,
            boolean stale,
            @Nullable String observedRowVersion) {
        jdbc.sql("""
                INSERT INTO integration.marketplace_availability_sync_state
                    (tenant_id, binding_id, next_sweep_at, last_sweep_at, last_sweep_item_count,
                     sweep_requested_at, reconcile_was_enabled, was_stale, updated_at)
                VALUES (:tenantId, :bindingId, :next, :now, :count, NULL, :enabled, :stale, :now)
                ON CONFLICT (binding_id) DO UPDATE
                SET next_sweep_at = EXCLUDED.next_sweep_at,
                    last_sweep_at = EXCLUDED.last_sweep_at,
                    last_sweep_item_count = EXCLUDED.last_sweep_item_count,
                    sweep_requested_at = CASE
                        WHEN integration.marketplace_availability_sync_state.xmin::text = CAST(:observedRowVersion AS text)
                        THEN NULL
                        ELSE integration.marketplace_availability_sync_state.sweep_requested_at END,
                    reconcile_was_enabled = EXCLUDED.reconcile_was_enabled,
                    was_stale = EXCLUDED.was_stale,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("next", timestamp(nextSweepAt))
                .param("now", timestamp(now))
                .param("count", itemCount)
                .param("enabled", reconcileEnabled)
                .param("stale", stale)
                .param("observedRowVersion", observedRowVersion)
                .update();
    }

    /** Remembers the switch and staleness flags without a sweep. */
    public void recordSweepState(UUID tenantId, UUID bindingId, boolean reconcileEnabled, boolean stale, Instant now) {
        jdbc.sql("""
                INSERT INTO integration.marketplace_availability_sync_state
                    (tenant_id, binding_id, reconcile_was_enabled, was_stale, updated_at)
                VALUES (:tenantId, :bindingId, :enabled, :stale, :now)
                ON CONFLICT (binding_id) DO UPDATE
                SET reconcile_was_enabled = EXCLUDED.reconcile_was_enabled,
                    was_stale = EXCLUDED.was_stale,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("enabled", reconcileEnabled)
                .param("stale", stale)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Remembers the switch state without a sweep: a suspended tick still has to note that it was suspended.
     * A suspended binding is not being pushed for, so nothing is overdue by its own fault: the stale
     * episode it was in ends here (see {@link #clearStaleReported}), and a resumption into a partner
     * that still refuses is reported as the new outage it is.
     */
    public void recordSuspended(UUID tenantId, UUID bindingId, Instant now) {
        jdbc.sql("""
                INSERT INTO integration.marketplace_availability_sync_state
                    (tenant_id, binding_id, reconcile_was_enabled, updated_at)
                VALUES (:tenantId, :bindingId, false, :now)
                ON CONFLICT (binding_id) DO UPDATE
                SET reconcile_was_enabled = false, stale_alerted_at = NULL, updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("now", timestamp(now))
                .update();
    }

    // ------------------------------------------------------------------ item rows

    public Map<String, ItemRow> itemsOf(UUID tenantId, UUID bindingId) {
        Map<String, ItemRow> byExternal = new HashMap<>();
        jdbc.sql("""
                SELECT %s FROM integration.marketplace_item_availability
                WHERE tenant_id = :tenantId AND binding_id = :bindingId
                """.formatted(ITEM_COLUMNS))
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query(JdbcMarketplaceAvailabilityStore::mapItem)
                .list()
                .forEach(item -> byExternal.put(item.externalEntityId(), item));
        return byExternal;
    }

    /**
     * Records a new desired value for a mapped item, creating the row on first sight.
     *
     * <p>A new row starts with {@code confirmed_available} NULL, which makes the first push of
     * any item unconditional. For an existing row the sequence advances only when the desired
     * value actually changes, and the state is re-derived in the same statement: a row whose
     * confirmed value is known and now equals the new desired value is {@code IN_SYNC}; a row
     * that is {@code UNCERTAIN} or {@code REJECTED_UNMAPPED} stays so (an unknown never becomes
     * "in sync" because a desired value moved).
     *
     * <p>The retry time follows the instruction that earned it. A restore that is backing off
     * keeps waiting, but a value that moves to {@code false} (a stop) is claimable at once on a
     * row in any state: an {@code UNCERTAIN} row may well be holding {@code true} on the partner,
     * and a never-confirmed row is waiting on a first push that is now the wrong instruction.
     * "A stop is pushed before a restore" (ADR 0141 Decision 7) has to hold for the timer as
     * well as for the claim order.
     */
    public void upsertDesired(
            UUID tenantId,
            UUID bindingId,
            String externalEntityId,
            UUID variantId,
            @Nullable UUID locationId,
            boolean desired,
            Instant now) {
        jdbc.sql("""
                INSERT INTO integration.marketplace_item_availability
                    (tenant_id, binding_id, external_entity_id, variant_id, location_id,
                     desired_available, desired_seq, desired_at, state, pending_since, next_attempt_at, updated_at)
                VALUES (:tenantId, :bindingId, :externalId, :variantId, :locationId,
                        :desired, 1, :now, 'PENDING', :now, :now, :now)
                ON CONFLICT (binding_id, external_entity_id) DO UPDATE
                SET variant_id = EXCLUDED.variant_id,
                    location_id = EXCLUDED.location_id,
                    desired_seq = CASE
                        WHEN integration.marketplace_item_availability.desired_available IS DISTINCT FROM EXCLUDED.desired_available
                        THEN integration.marketplace_item_availability.desired_seq + 1
                        ELSE integration.marketplace_item_availability.desired_seq END,
                    desired_at = CASE
                        WHEN integration.marketplace_item_availability.desired_available IS DISTINCT FROM EXCLUDED.desired_available
                        THEN EXCLUDED.desired_at
                        ELSE integration.marketplace_item_availability.desired_at END,
                    desired_available = EXCLUDED.desired_available,
                    state = CASE
                        WHEN integration.marketplace_item_availability.state IN ('UNCERTAIN', 'REJECTED_UNMAPPED', 'SUSPENDED')
                            THEN integration.marketplace_item_availability.state
                        WHEN integration.marketplace_item_availability.confirmed_available IS NOT NULL
                             AND integration.marketplace_item_availability.confirmed_available = EXCLUDED.desired_available
                            THEN 'IN_SYNC'
                        ELSE 'PENDING' END,
                    pending_since = CASE
                        WHEN integration.marketplace_item_availability.state IN ('UNCERTAIN', 'REJECTED_UNMAPPED', 'SUSPENDED')
                            THEN COALESCE(integration.marketplace_item_availability.pending_since, EXCLUDED.pending_since)
                        WHEN integration.marketplace_item_availability.confirmed_available IS NOT NULL
                             AND integration.marketplace_item_availability.confirmed_available = EXCLUDED.desired_available
                            THEN NULL
                        ELSE COALESCE(integration.marketplace_item_availability.pending_since, EXCLUDED.pending_since) END,
                    next_attempt_at = CASE
                        WHEN integration.marketplace_item_availability.state IN ('IN_SYNC')
                             AND integration.marketplace_item_availability.desired_available IS DISTINCT FROM EXCLUDED.desired_available
                            THEN EXCLUDED.desired_at
                        -- A stop on a row that is not in sync is claimable now, whatever retry time the
                        -- last attempt earned: that time was set for a different instruction (a restore,
                        -- or a first push), and a stop is pushed before anything else. A NULL stays NULL
                        -- (already claimable); a time already past is left alone.
                        WHEN integration.marketplace_item_availability.desired_available IS DISTINCT FROM EXCLUDED.desired_available
                             AND NOT EXCLUDED.desired_available
                             AND integration.marketplace_item_availability.next_attempt_at IS NOT NULL
                            THEN LEAST(integration.marketplace_item_availability.next_attempt_at, EXCLUDED.desired_at)
                        ELSE integration.marketplace_item_availability.next_attempt_at END,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("externalId", externalEntityId)
                .param("variantId", variantId)
                .param("locationId", locationId)
                .param("desired", desired)
                .param("now", timestamp(now))
                .update();
    }

    /** Drops the rows of items that are no longer mapped on this binding. */
    public int removeUnmapped(UUID tenantId, UUID bindingId, Collection<String> mappedExternalIds) {
        return jdbc.sql("""
                DELETE FROM integration.marketplace_item_availability
                WHERE tenant_id = :tenantId AND binding_id = :bindingId
                  AND NOT (external_entity_id = ANY(:mapped))
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("mapped", mappedExternalIds.toArray(String[]::new))
                .update();
    }

    /**
     * Lets a row the partner refused as unknown be tried again: the mapping changed (a new
     * external id replaces the old row), so a {@code REJECTED_UNMAPPED} row whose id is no
     * longer the mapped one is simply deleted by {@link #removeUnmapped}; this resets rows
     * whose id is unchanged but whose refusal may have been the partner's own lag.
     */
    public int retryRejected(UUID tenantId, UUID bindingId, Instant now) {
        return jdbc.sql("""
                UPDATE integration.marketplace_item_availability
                SET state = 'PENDING', next_attempt_at = :now, attempt_count = 0, updated_at = :now
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND state = 'REJECTED_UNMAPPED'
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Resumption: the partner portal may have been edited by hand while the reconciler could not
     * talk to it, so every belief about what the partner holds is withdrawn and the whole binding
     * is resent once.
     */
    public int forgetConfirmations(UUID tenantId, UUID bindingId, Instant now) {
        return jdbc.sql("""
                UPDATE integration.marketplace_item_availability
                SET confirmed_available = NULL, confirmed_at = NULL,
                    state = CASE WHEN state = 'UNCERTAIN' THEN 'UNCERTAIN' ELSE 'PENDING' END,
                    pending_since = COALESCE(pending_since, :now),
                    next_attempt_at = :now, attempt_count = 0, updated_at = :now
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND state <> 'REJECTED_UNMAPPED'
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Claims up to {@code limit} rows that need a call, stops first, under a lease.
     *
     * <p>A row needs a call when its confirmed value is not known to equal its desired value
     * ({@code IS DISTINCT FROM}, so a NULL always sends), it is not {@code REJECTED_UNMAPPED} or
     * {@code SUSPENDED}, its retry time has come and no live lease holds it. {@code FOR UPDATE
     * SKIP LOCKED} inside the claim is what lets two overlapping runs — two replicas, or a tick
     * that outlived its interval — take disjoint rows rather than the same ones twice.
     */
    public List<ItemRow> claimDue(UUID tenantId, UUID bindingId, String owner, Instant now, Duration lease, int limit) {
        return jdbc
                .sql("""
                UPDATE integration.marketplace_item_availability t
                SET lease_owner = :owner, lease_expires_at = :leaseUntil
                WHERE (t.binding_id, t.external_entity_id) IN (
                    SELECT binding_id, external_entity_id
                    FROM integration.marketplace_item_availability
                    WHERE tenant_id = :tenantId AND binding_id = :bindingId
                      AND state IN ('PENDING', 'UNCERTAIN')
                      AND confirmed_available IS DISTINCT FROM desired_available
                      AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                      AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
                    ORDER BY desired_available ASC, pending_since ASC NULLS LAST, external_entity_id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING %s
                """.formatted(prefixed("t.", ITEM_COLUMNS)))
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("owner", owner)
                .param("now", timestamp(now))
                .param("leaseUntil", timestamp(now.plus(lease)))
                .param("limit", limit)
                .query(JdbcMarketplaceAvailabilityStore::mapItem)
                .list()
                .stream()
                // RETURNING hands rows back in heap order, not in the subselect's order, and
                // "stops before restores" is a promise about the order calls are made in.
                .sorted(java.util.Comparator.comparing(ItemRow::desiredAvailable)
                        .thenComparing(
                                ItemRow::pendingSince,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(ItemRow::externalEntityId))
                .toList();
    }

    /**
     * Writes what one attempt concluded, if the worker still holds the lease.
     *
     * <p>A retry time earned by a failed attempt belongs to the instruction that was sent. If a
     * stop was recorded while that call was in flight (the row's desired value is now {@code
     * false} and differs from what was sent), the failed restore's backoff must not delay it:
     * the row is claimable at once, exactly as {@link #upsertDesired} leaves it when no call was
     * in flight.
     *
     * @param sent the value that was sent, which is what {@code CONFIRMED} records — not the
     *     row's current desired value, which may have moved while the call was in flight
     * @return false when the lease was lost, in which case nothing was written
     */
    public boolean recordOutcome(
            ItemRow claimed,
            String owner,
            boolean sent,
            Outcome outcome,
            @Nullable String failureCode,
            Instant now,
            @Nullable Instant retryAt) {
        String sql =
                switch (outcome) {
                    case CONFIRMED -> """
                            UPDATE integration.marketplace_item_availability
                            SET confirmed_available = :sent, confirmed_at = :now,
                                last_attempt_available = :sent, last_attempt_at = :now, last_attempt_outcome = 'CONFIRMED',
                                state = CASE WHEN desired_available = :sent THEN 'IN_SYNC' ELSE 'PENDING' END,
                                attempt_count = 0, last_failure_code = NULL,
                                pending_since = CASE WHEN desired_available = :sent THEN NULL ELSE COALESCE(pending_since, :now) END,
                                next_attempt_at = CASE WHEN desired_available = :sent THEN NULL ELSE :now END,
                                lease_owner = NULL, lease_expires_at = NULL, updated_at = :now
                            WHERE tenant_id = :tenantId AND binding_id = :bindingId AND external_entity_id = :externalId
                              AND lease_owner = :owner
                            """;
                    case NOT_APPLIED -> """
                            UPDATE integration.marketplace_item_availability
                            SET last_attempt_available = :sent, last_attempt_at = :now, last_attempt_outcome = 'NOT_APPLIED',
                                state = CASE WHEN state = 'UNCERTAIN' THEN 'UNCERTAIN' ELSE 'PENDING' END,
                                attempt_count = attempt_count + 1, last_failure_code = :failureCode,
                                pending_since = COALESCE(pending_since, :now),
                                next_attempt_at = CASE
                                    WHEN NOT desired_available AND desired_available IS DISTINCT FROM :sent THEN :now
                                    ELSE :retryAt END,
                                lease_owner = NULL, lease_expires_at = NULL, updated_at = :now
                            WHERE tenant_id = :tenantId AND binding_id = :bindingId AND external_entity_id = :externalId
                              AND lease_owner = :owner
                            """;
                    case UNKNOWN -> """
                            UPDATE integration.marketplace_item_availability
                            SET confirmed_available = NULL, confirmed_at = NULL,
                                last_attempt_available = :sent, last_attempt_at = :now, last_attempt_outcome = 'UNKNOWN',
                                state = 'UNCERTAIN',
                                attempt_count = attempt_count + 1, last_failure_code = :failureCode,
                                pending_since = COALESCE(pending_since, :now),
                                next_attempt_at = CASE
                                    WHEN NOT desired_available AND desired_available IS DISTINCT FROM :sent THEN :now
                                    ELSE :retryAt END,
                                lease_owner = NULL, lease_expires_at = NULL, updated_at = :now
                            WHERE tenant_id = :tenantId AND binding_id = :bindingId AND external_entity_id = :externalId
                              AND lease_owner = :owner
                            """;
                    case REJECTED_UNMAPPED -> """
                            UPDATE integration.marketplace_item_availability
                            SET last_attempt_available = :sent, last_attempt_at = :now, last_attempt_outcome = 'NOT_APPLIED',
                                state = 'REJECTED_UNMAPPED',
                                attempt_count = attempt_count + 1, last_failure_code = :failureCode,
                                pending_since = COALESCE(pending_since, :now),
                                next_attempt_at = NULL,
                                lease_owner = NULL, lease_expires_at = NULL, updated_at = :now
                            WHERE tenant_id = :tenantId AND binding_id = :bindingId AND external_entity_id = :externalId
                              AND lease_owner = :owner
                            """;
                };
        return jdbc.sql(sql)
                        .param("tenantId", claimed.tenantId())
                        .param("bindingId", claimed.bindingId())
                        .param("externalId", claimed.externalEntityId())
                        .param("owner", owner)
                        .param("sent", sent)
                        .param("failureCode", failureCode)
                        .param("retryAt", timestamp(retryAt))
                        .param("now", timestamp(now))
                        .update()
                == 1;
    }

    /** Releases a lease without concluding anything (the call was never made). */
    public void releaseLease(ItemRow claimed, String owner) {
        jdbc.sql("""
                UPDATE integration.marketplace_item_availability
                SET lease_owner = NULL, lease_expires_at = NULL
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND external_entity_id = :externalId
                  AND lease_owner = :owner
                """)
                .param("tenantId", claimed.tenantId())
                .param("bindingId", claimed.bindingId())
                .param("externalId", claimed.externalEntityId())
                .param("owner", owner)
                .update();
    }

    // ------------------------------------------------------------------ staleness

    /**
     * The dishes of one binding the platform has not been able to confirm since before {@code cutoff}:
     * pending or uncertain, with {@code pending_since} at or before it. A {@code REJECTED_UNMAPPED}
     * item is excluded on purpose -- the partner answered, and the fix is a mapping, not a channel
     * that went quiet.
     *
     * @return empty when nothing is that old
     */
    public Optional<OverdueSummary> overdueUnconfirmed(UUID tenantId, UUID bindingId, Instant cutoff) {
        OverdueSummary summary = jdbc.sql("""
                SELECT count(*) AS item_count, min(pending_since) AS oldest
                FROM integration.marketplace_item_availability
                WHERE tenant_id = :tenantId AND binding_id = :bindingId
                  AND state IN ('PENDING', 'UNCERTAIN')
                  AND pending_since IS NOT NULL AND pending_since <= :cutoff
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("cutoff", timestamp(cutoff))
                .query((row, number) -> new OverdueSummary(
                        row.getInt("item_count"), instant(row.getObject("oldest", OffsetDateTime.class))))
                .single();
        return summary.itemCount() == 0 ? Optional.empty() : Optional.of(summary);
    }

    /**
     * Records that this binding's staleness has been reported, once. The conditional upsert is the
     * "once": whoever finds {@code stale_alerted_at} empty sets it and wins; a second replica ticking
     * the same binding finds it set and reports nothing.
     *
     * @return whether this call is the one that reported it
     */
    public boolean markStaleReported(UUID tenantId, UUID bindingId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO integration.marketplace_availability_sync_state
                            (tenant_id, binding_id, stale_alerted_at, updated_at)
                        VALUES (:tenantId, :bindingId, :now, :now)
                        ON CONFLICT (binding_id) DO UPDATE
                        SET stale_alerted_at = EXCLUDED.stale_alerted_at, updated_at = EXCLUDED.updated_at
                        WHERE integration.marketplace_availability_sync_state.stale_alerted_at IS NULL
                        """)
                        .param("tenantId", tenantId)
                        .param("bindingId", bindingId)
                        .param("now", timestamp(now))
                        .update()
                == 1;
    }

    /** The binding has nothing unconfirmed past its bound: the next staleness is a new report. */
    public void clearStaleReported(UUID tenantId, UUID bindingId, Instant now) {
        jdbc.sql("""
                UPDATE integration.marketplace_availability_sync_state
                SET stale_alerted_at = NULL, updated_at = :now
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND stale_alerted_at IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Ends the stale episode of every binding the reconciler no longer works: an installation or a
     * binding suspended, a binding with no location, anything {@link #activeMarketplaceBindings} leaves
     * out. {@code evaluate} clears a mark only for a binding it is asked about, and a binding that left
     * the worklist is never asked, so without this the mark -- and the gauge an operator alerts on --
     * would outlive the outage, and a reactivation would find the old episode "already reported".
     *
     * @return how many marks were cleared
     */
    public int clearStaleReportedOfInactiveBindings(Instant now) {
        return jdbc.sql("""
                UPDATE integration.marketplace_availability_sync_state s
                SET stale_alerted_at = NULL, updated_at = :now
                WHERE s.stale_alerted_at IS NOT NULL
                  AND NOT %s
                """.formatted(ACTIVE_BINDING_OF_STATE))
                .param("now", timestamp(now))
                .update();
    }

    /**
     * How many bindings are inside a reported stale episode and are still ones the reconciler works.
     * Never a count of marks alone: a mark on a binding that is no longer pushed for is not an outage.
     */
    public long countStaleReportedActive() {
        return jdbc.sql("""
                SELECT count(*) FROM integration.marketplace_availability_sync_state s
                WHERE s.stale_alerted_at IS NOT NULL AND %s
                """.formatted(ACTIVE_BINDING_OF_STATE))
                .query(Long.class)
                .single();
    }

    /** The worklist's own predicate ({@link #activeMarketplaceBindings}), as a test on a sync-state row {@code s}. */
    private static final String ACTIVE_BINDING_OF_STATE = """
            EXISTS (SELECT 1 FROM integration.bindings b
                    JOIN integration.installations i ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                    WHERE b.id = s.binding_id AND b.tenant_id = s.tenant_id
                      AND i.provider_category = 'MARKETPLACE'
                      AND i.status = 'ACTIVE' AND b.status = 'ACTIVE'
                      AND b.location_id IS NOT NULL)""";

    // ------------------------------------------------------------------ the propagation read

    /** Counts and the oldest pending moment for each binding at one location. */
    public List<BindingSummary> summariesAtLocation(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT b.id AS binding_id, b.installation_id AS installation_id, i.status AS installation_status,
                       i.provider_type, i.display_name,
                       count(a.*) FILTER (WHERE a.state = 'IN_SYNC') AS in_sync,
                       count(a.*) FILTER (WHERE a.state = 'PENDING') AS pending,
                       count(a.*) FILTER (WHERE a.state = 'UNCERTAIN') AS uncertain,
                       count(a.*) FILTER (WHERE a.state = 'REJECTED_UNMAPPED') AS rejected,
                       min(a.pending_since) FILTER (WHERE a.state <> 'IN_SYNC') AS oldest_pending,
                       s.last_sweep_at AS last_sweep_at,
                       s.reconcile_was_enabled AS reconcile_was_enabled,
                       w.last_success_at AS last_success_at,
                       w.last_failure_at AS last_failure_at,
                       w.last_failure_code AS last_failure_code
                FROM integration.bindings b
                JOIN integration.installations i ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                LEFT JOIN integration.marketplace_item_availability a
                       ON a.tenant_id = b.tenant_id AND a.binding_id = b.id
                LEFT JOIN integration.marketplace_availability_sync_state s ON s.binding_id = b.id
                LEFT JOIN integration.provider_activity_watermarks w
                       ON w.tenant_id = b.tenant_id AND w.binding_id = b.id AND w.direction = 'OUTBOUND'
                WHERE b.tenant_id = :tenantId AND b.location_id = :locationId
                  AND i.provider_category = 'MARKETPLACE' AND b.status = 'ACTIVE'
                GROUP BY b.id, b.installation_id, i.status, i.provider_type, i.display_name, s.last_sweep_at,
                         s.reconcile_was_enabled, w.last_success_at, w.last_failure_at, w.last_failure_code
                ORDER BY i.display_name, b.id
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((row, number) -> new BindingSummary(
                        row.getObject("binding_id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("display_name"),
                        row.getInt("in_sync"),
                        row.getInt("pending"),
                        row.getInt("uncertain"),
                        row.getInt("rejected"),
                        instant(row.getObject("oldest_pending", OffsetDateTime.class)),
                        instant(row.getObject("last_sweep_at", OffsetDateTime.class)),
                        row.getObject("reconcile_was_enabled") == null || row.getBoolean("reconcile_was_enabled"),
                        instant(row.getObject("last_success_at", OffsetDateTime.class)),
                        instant(row.getObject("last_failure_at", OffsetDateTime.class)),
                        row.getString("last_failure_code"),
                        row.getObject("installation_id", UUID.class),
                        "ACTIVE".equals(row.getString("installation_status"))))
                .list();
    }

    /** The items of one binding that the platform has not been able to confirm, oldest first. */
    public List<ItemRow> unconfirmedItems(UUID tenantId, UUID bindingId, int limit) {
        return jdbc.sql("""
                SELECT %s FROM integration.marketplace_item_availability
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND state <> 'IN_SYNC'
                ORDER BY pending_since NULLS LAST, external_entity_id
                LIMIT :limit
                """.formatted(ITEM_COLUMNS))
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("limit", limit)
                .query(JdbcMarketplaceAvailabilityStore::mapItem)
                .list();
    }

    /** The outbound watermark of a binding, for the stale-recovery rule. */
    public Optional<Watermark> outboundWatermark(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                SELECT alert_state, last_success_at, stale_after_seconds
                FROM integration.provider_activity_watermarks
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND direction = 'OUTBOUND'
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) -> new Watermark(
                        row.getString("alert_state"),
                        instant(row.getObject("last_success_at", OffsetDateTime.class)),
                        row.getInt("stale_after_seconds")))
                .optional();
    }

    // ------------------------------------------------------------------ mapping

    private static ItemRow mapItem(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new ItemRow(
                row.getObject("tenant_id", UUID.class),
                row.getObject("binding_id", UUID.class),
                row.getString("external_entity_id"),
                row.getObject("variant_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getBoolean("desired_available"),
                row.getLong("desired_seq"),
                (Boolean) row.getObject("confirmed_available"),
                instant(row.getObject("confirmed_at", OffsetDateTime.class)),
                (Boolean) row.getObject("last_attempt_available"),
                instant(row.getObject("last_attempt_at", OffsetDateTime.class)),
                row.getString("last_attempt_outcome"),
                row.getString("state"),
                row.getInt("attempt_count"),
                instant(row.getObject("next_attempt_at", OffsetDateTime.class)),
                row.getString("last_failure_code"),
                instant(row.getObject("pending_since", OffsetDateTime.class)));
    }

    private static String prefixed(String prefix, String columns) {
        List<String> prefixedColumns = new ArrayList<>();
        for (String column : columns.split(",")) {
            prefixedColumns.add(prefix + column.strip());
        }
        return String.join(", ", prefixedColumns);
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    // ------------------------------------------------------------------ rows

    public enum Outcome {
        CONFIRMED,
        NOT_APPLIED,
        UNKNOWN,
        REJECTED_UNMAPPED
    }

    public record BindingRow(
            UUID bindingId,
            UUID tenantId,
            UUID installationId,
            UUID brandId,
            UUID locationId,
            String providerType,
            String displayName) {}

    /**
     * @param rowVersion the row's PostgreSQL {@code xmin}: changes with every write to the row, by anyone.
     *     What a sweep hands back to {@link #recordSweep} to prove nobody has written a marker since it looked.
     */
    public record SyncState(
            UUID bindingId,
            @Nullable Instant nextSweepAt,
            @Nullable Instant sweepRequestedAt,
            boolean reconcileWasEnabled,
            boolean wasStale,
            String rowVersion) {}

    public record ItemRow(
            UUID tenantId,
            UUID bindingId,
            String externalEntityId,
            UUID variantId,
            @Nullable UUID locationId,
            boolean desiredAvailable,
            long desiredSeq,
            @Nullable Boolean confirmedAvailable,
            @Nullable Instant confirmedAt,
            @Nullable Boolean lastAttemptAvailable,
            @Nullable Instant lastAttemptAt,
            @Nullable String lastAttemptOutcome,
            String state,
            int attemptCount,
            @Nullable Instant nextAttemptAt,
            @Nullable String lastFailureCode,
            @Nullable Instant pendingSince) {}

    public record BindingSummary(
            UUID bindingId,
            String providerType,
            String displayName,
            int inSync,
            int pending,
            int uncertain,
            int rejected,
            @Nullable Instant oldestPendingSince,
            @Nullable Instant lastSweepAt,
            boolean reconcileEnabled,
            @Nullable Instant lastSuccessAt,
            @Nullable Instant lastFailureAt,
            @Nullable String lastFailureCode,
            UUID installationId,
            boolean installationActive) {}

    public record Watermark(String alertState, @Nullable Instant lastSuccessAt, int staleAfterSeconds) {}

    /** How many dishes have been unconfirmed past a bound, and since when the longest-waiting has been. */
    public record OverdueSummary(int itemCount, @Nullable Instant oldestSince) {}

    /** Helper for tests and callers that want the variant set of a binding's rows. */
    public static Set<UUID> variantsOf(Collection<ItemRow> rows) {
        Set<UUID> variants = new java.util.HashSet<>();
        rows.forEach(row -> variants.add(row.variantId()));
        return variants;
    }
}
