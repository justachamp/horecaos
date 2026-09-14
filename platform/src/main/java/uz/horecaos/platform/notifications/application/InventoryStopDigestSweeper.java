package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.ItemDisplayLookup;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcInventoryStopDigestStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcInventoryStopDigestStore.PendingEntry;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcInventoryStopDigestStore.PendingLocation;

/**
 * Raises {@code InventoryOperationsAlertTrigger}'s queued entries as one
 * grouped alert per location per tick (gap map row 2.5c, wave P16) —
 * Delever's own «Позиции на стопе / Вышли со стопа» shape, which this build
 * never had: fifteen items 86'd in one rush used to fan out as fifteen
 * separate messages, and a return to sale was never announced at all.
 *
 * <p>Same shape as {@link uz.horecaos.platform.inventory.application.InventoryReservationSweeper}:
 * a frequent, best-effort, log-and-continue pass with its own on/off switch
 * for a one-shot process. Unlike that sweeper, this one loops over the
 * distinct locations the queue names — there is no single cross-tenant
 * `UPDATE` that could raise a per-location grouped message the way a
 * cross-tenant expiry can.
 *
 * <p>Each location's flush is its own short transaction ({@link #flushOne}):
 * the alert rows {@link OperationsAlertPort#fanOut} writes and the {@code
 * consumed_at} stamp on the entries it summarises commit together, so a
 * crash between the two can never either lose a queued toggle or repeat an
 * alert already raised — the next tick simply finds the same unconsumed rows
 * again, exactly the {@code FOR UPDATE SKIP LOCKED}-free safety {@code
 * PosAvailabilityPoll}'s own doc argues for a feed with no due-occurrence
 * claim. Nothing here makes an external call — {@link OperationsAlertPort}
 * only ever writes {@code notifications.*} rows for the existing relay to
 * deliver later, the same boundary {@code InventoryOperationsAlertTrigger}
 * itself relied on before this wave.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.notifications.inventory-stop-digest.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class InventoryStopDigestSweeper {

    /** The semantic template key a tenant authors this digest's wording against. */
    static final String DIGEST_EVENT_CLASS = "INVENTORY_STOP_DIGEST";

    static final String SUBJECT_TYPE = "Location";

    /**
     * Names listed inline before the message switches to a count-only "+N
     * more" tail — the same honest truncation orders.md §2.11 already asks
     * of the live board's own "Показаны N из M", applied here so a 300-item
     * stop (the scenario gap map row 2.5 itself names) produces one readable
     * message rather than one enormous one.
     */
    static final int MAX_NAMES_LISTED = 15;

    private static final Logger log = LoggerFactory.getLogger(InventoryStopDigestSweeper.class);

    private final JdbcInventoryStopDigestStore digestQueue;
    private final ItemDisplayLookup itemNames;
    private final OperationsAlertPort operationsAlerts;
    private final Clock clock;
    private final Duration expiry;

    public InventoryStopDigestSweeper(
            JdbcInventoryStopDigestStore digestQueue,
            ItemDisplayLookup itemNames,
            OperationsAlertPort operationsAlerts,
            Clock clock,
            // Same short-lived reasoning `InventoryOperationsAlertTrigger` gave
            // its own expiry before this wave: a stop-list notice is stale the
            // moment the next digest supersedes it.
            @Value("${horecaos.notifications.inventory-stop-digest.alert-expiry:PT30M}") Duration expiry) {
        this.digestQueue = digestQueue;
        this.itemNames = itemNames;
        this.operationsAlerts = operationsAlerts;
        this.clock = clock;
        this.expiry = expiry;
    }

    /**
     * The window itself: whatever accumulated in the queue since the last
     * tick is one digest. Two minutes matches {@code InventoryReservationSweeper}'s
     * own cadence for the same reason — frequent enough that a single 86 is
     * still timely, coarse enough that a rush of them coalesces.
     */
    @Scheduled(
            initialDelayString = "${horecaos.notifications.inventory-stop-digest.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.notifications.inventory-stop-digest.interval:PT2M}")
    public void flushDue() {
        for (PendingLocation location : digestQueue.pendingLocations()) {
            try {
                flushOne(location);
            } catch (RuntimeException failure) {
                // Logged and swallowed, not rethrown: one location's bad row must
                // not stop every other location's digest, or the outbox relay and
                // every other module's timer sharing this pool
                // (SchedulingConfiguration's own error handler covers a fatal
                // failure; this is the ordinary-failure half of that contract).
                log.error("Inventory stop digest failed for location {}", location.locationId(), failure);
            }
        }
    }

    @Transactional
    void flushOne(PendingLocation location) {
        List<PendingEntry> pending = digestQueue.pendingFor(location.tenantId(), location.locationId());
        if (pending.isEmpty()) {
            return;
        }

        Set<UUID> stoppedIds = new LinkedHashSet<>();
        Set<UUID> restoredIds = new LinkedHashSet<>();
        Set<UUID> everyVariant = new LinkedHashSet<>();
        for (PendingEntry entry : pending) {
            everyVariant.add(entry.variantId());
            if (entry.available()) {
                restoredIds.add(entry.variantId());
            } else {
                stoppedIds.add(entry.variantId());
            }
        }

        Map<UUID, String> names = itemNames.displayNames(location.tenantId(), everyVariant);
        List<String> stoppedNames = names(stoppedIds, names);
        List<String> restoredNames = names(restoredIds, names);

        Instant now = clock.instant();
        operationsAlerts.fanOut(
                location.tenantId(),
                location.brandId(),
                location.locationId(),
                DIGEST_EVENT_CLASS,
                DIGEST_EVENT_CLASS,
                SUBJECT_TYPE,
                location.locationId(),
                null,
                // Unique per digest run: the claimed entries never repeat once
                // marked consumed below, so there is no earlier run this key
                // could collide with and nothing for a replay to deduplicate
                // against — unlike ITEM_86D's own per-event key, which had to
                // survive the same event being redelivered.
                "%s:%s:%s".formatted(DIGEST_EVENT_CLASS, location.locationId(), now),
                digestVariables(stoppedNames, restoredNames),
                expiry);

        digestQueue.markConsumed(
                location.tenantId(), pending.stream().map(PendingEntry::id).toList(), now);
    }

    private static List<String> names(Set<UUID> variantIds, Map<UUID, String> resolved) {
        return variantIds.stream()
                .map(id -> resolved.getOrDefault(id, id.toString()))
                .toList();
    }

    /**
     * The entire variable set this digest ever renders with — item counts
     * and a bounded, comma-joined name list per direction, nothing about who
     * toggled which item or which order triggered it. Package-visible so
     * {@code TelegramOperationsMessageClassificationTests} asserts that
     * directly, the same discipline {@code InventoryOperationsAlertTrigger}'s
     * own (now-removed) single-item variables carried.
     */
    static Map<String, String> digestVariables(List<String> stoppedNames, List<String> restoredNames) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("stoppedCount", String.valueOf(stoppedNames.size()));
        variables.put("stoppedItems", joinTruncated(stoppedNames));
        variables.put("restoredCount", String.valueOf(restoredNames.size()));
        variables.put("restoredItems", joinTruncated(restoredNames));
        return variables;
    }

    private static String joinTruncated(List<String> names) {
        if (names.size() <= MAX_NAMES_LISTED) {
            return String.join(", ", names);
        }
        String listed = String.join(", ", names.subList(0, MAX_NAMES_LISTED));
        return listed + ", +" + (names.size() - MAX_NAMES_LISTED);
    }
}
