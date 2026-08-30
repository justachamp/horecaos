package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.commercial.api.UsagePeriod;
import uz.horecaos.platform.commercial.domain.UsageTotals;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore.PeriodRef;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;

/**
 * The meter (ADR 0021).
 *
 * <p>Two writes in one transaction: the movement, then the total. The order
 * matters. If the aggregate write fails, the transaction rolls back and the
 * movement is not recorded either, so the ledger never contains a fact the cache
 * has not seen. If the process dies between them there is no between — and a
 * cache that has drifted anyway is repaired by {@link #rebuild}, because the
 * ledger is the definition and the cache is only ever an opinion about it.
 *
 * <p>Nothing here consults an entitlement. Measuring is not enforcing, and
 * keeping them apart is what makes it safe to switch enforcement on later: the
 * evidence gathered before the switch was gathered by the same code that
 * gathers it afterwards.
 */
@Service
public class UsageMeteringService implements UsageMeter {

    private final JdbcUsageStore usage;
    private final EntitlementQueryService entitlements;
    private final Clock clock;

    public UsageMeteringService(JdbcUsageStore usage, EntitlementQueryService entitlements, Clock clock) {
        this.usage = usage;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean record(UsageMovement movement) {
        // ADR 0024's "consumes benefits". Skipped, and false is already this
        // method's word for "not counted" — it is what a redelivery gets. Metering
        // an imported estate would charge a tenant's current period for five years
        // of history and, where enforcement is on, exhaust the entitlement that
        // their live orders are about to need.
        if (ImportSuppression.suppress(
                ExternalEffect.BENEFIT_CONSUMPTION, movement.sourceType(), movement.sourceEventId())) {
            return false;
        }

        Instant now = clock.instant();
        UsagePeriod period = periodOf(movement);

        boolean appended = usage.appendEvent(
                UUID.randomUUID(),
                movement.tenantId(),
                movement.key().code(),
                movement.quantity(),
                movement.key().unit(),
                period.key(),
                movement.sourceType(),
                movement.sourceEventId(),
                movement.occurredAt(),
                movement.dimensions(),
                now);

        if (!appended) {
            // A redelivery. Deliberately not an error and deliberately not a
            // second increment: at-least-once delivery is the contract every
            // consumer of ADR 0005's inbox works under.
            return false;
        }

        usage.storeTotals(
                movement.tenantId(),
                usage.recompute(movement.tenantId(), movement.key().code(), period),
                now);
        return true;
    }

    /**
     * Records a correction as a new fact.
     *
     * <p>Never an edit. A goodwill credit, a duplicate that got through a
     * consumer bug, and a manually reconciled figure all land here with a reason
     * and two names on them, and the original movements stay exactly as they
     * were recorded.
     */
    @Transactional
    public UUID adjust(
            UUID tenantId,
            EntitlementKey<Long> key,
            String periodKey,
            long delta,
            String reason,
            String sourceReference,
            String createdBy,
            String approvedBy) {

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        usage.insertAdjustment(
                id, tenantId, key.code(), periodKey, delta, reason, sourceReference, createdBy, approvedBy, now);

        rebuildPeriod(tenantId, key.code(), periodKey, now);
        return id;
    }

    /**
     * Recomputes every cached total for a tenant from the ledger.
     *
     * @return the periods whose cached figure disagreed with the ledger, which
     *         should be empty and is the number a reconciliation alerts on
     */
    @Transactional
    public List<Divergence> rebuild(UUID tenantId) {
        Instant now = clock.instant();
        List<Divergence> divergences = new ArrayList<>();

        for (PeriodRef ref : usage.recordedPeriods(tenantId)) {
            UsagePeriod period = periodOf(ref.entitlementKey(), ref.periodKey(), tenantId);
            UsageTotals recomputed = usage.recompute(tenantId, ref.entitlementKey(), period);
            long stored = usage.storedTotals(tenantId, ref.entitlementKey(), period)
                    .map(UsageTotals::consumed)
                    .orElse(Long.MIN_VALUE);

            if (stored != recomputed.consumed()) {
                divergences.add(new Divergence(
                        ref.entitlementKey(),
                        ref.periodKey(),
                        stored == Long.MIN_VALUE ? null : stored,
                        recomputed.consumed()));
            }
            usage.storeTotals(tenantId, recomputed, now);
        }
        return List.copyOf(divergences);
    }

    /** Every cached total for a tenant, for the usage screen. */
    public List<JdbcUsageStore.StoredPeriodTotal> totals(UUID tenantId) {
        return usage.listStoredTotals(tenantId);
    }

    private void rebuildPeriod(UUID tenantId, String entitlementKey, String periodKey, Instant now) {
        UsagePeriod period = periodOf(entitlementKey, periodKey, tenantId);
        usage.storeTotals(tenantId, usage.recompute(tenantId, entitlementKey, period), now);
    }

    /**
     * The period a movement belongs to, decided at record time.
     *
     * <p>From the movement's {@code occurredAt}, not from the clock. A late event
     * counts against the period it happened in — that is the whole reason both
     * timestamps are stored — and an event that arrives on the second of the
     * month for something that happened on the thirty-first belongs to the month
     * the tenant was invoiced for.
     */
    private UsagePeriod periodOf(UsageMovement movement) {
        var context = entitlements.contextOf(movement.tenantId(), movement.occurredAt());
        var value = context.resolve(movement.key(), movement.occurredAt());
        return context.periodFor(value, movement.occurredAt());
    }

    /**
     * Recovers the window a stored partition key stands for.
     *
     * <p>The key is authoritative — it is what the movements were filed under and
     * what a recompute filters on — so this only ever recovers the bounds the
     * aggregate row needs. It takes them from the cached row where one exists,
     * and otherwise recomputes them from the earliest movement in the period,
     * which is by construction an instant inside it. Nothing is ever re-filed,
     * so a tenant that changes timezone does not move its own history.
     */
    private UsagePeriod periodOf(String entitlementKey, String periodKey, UUID tenantId) {
        Optional<UsagePeriod> stored = usage.storedWindow(tenantId, entitlementKey, periodKey);
        if (stored.isPresent()) {
            return stored.get();
        }

        EntitlementKey<?> key = EntitlementKeys.require(entitlementKey);
        Instant reference = usage.earliestOccurrence(tenantId, entitlementKey, periodKey)
                // An adjustment against a period with no movements at all. Noon
                // UTC inside the named day or month is inside the period for
                // every timezone the platform serves.
                .orElseGet(() -> referenceInstant(periodKey));

        var context = entitlements.contextOf(tenantId, reference);
        UsagePeriod computed = context.periodFor(context.resolve(key, reference), reference);

        return computed.key().equals(periodKey)
                ? computed
                : new UsagePeriod(periodKey, computed.start(), computed.end());
    }

    /** An instant inside the period a stored key names. */
    private static Instant referenceInstant(String periodKey) {
        if (UsagePeriod.LIFETIME.equals(periodKey)) {
            return Instant.EPOCH.plusSeconds(1);
        }
        String iso = periodKey.length() == 7 ? periodKey + "-15" : periodKey;
        return Instant.parse(iso + "T12:00:00Z");
    }

    /** A cached figure that disagreed with the ledger it is supposed to summarise. */
    public record Divergence(String entitlementKey, String periodKey, Long stored, long recomputed) {}
}
