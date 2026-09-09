package uz.horecaos.platform.pos.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.inventory.api.StockAvailabilityPort;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.application.port.PosAdapter.AvailabilityRead;
import uz.horecaos.platform.pos.application.port.PosAdapter.PosContext;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore.Candidate;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore.ReplaceResult;

/**
 * ADR 0012's separate stop-list cadence, made real: {@code
 * integration.pos_live_availability} (V0190) had a table and nobody writing it.
 * This is the poller — the same defect the previous wave fixed one table over for
 * {@code pos_sync_schedules}, a timer nobody wound.
 *
 * <h2>Cadence</h2>
 *
 * <p>Every {@code horecaos.pos.availability.poll.interval} (default {@code
 * PT45S}, inside the ADR's stated thirty-to-sixty-second window), configurable —
 * never a hardcoded constant — the same way {@link PosSyncScheduler}'s own
 * interval is: a Spring property with a sensible default, overridable at the
 * control plane per deployment. Unlike that scheduler, there is no per-binding
 * {@code next_run_at} to compute or advance: V0190's own comment refuses a
 * schedule table for this feed ("no run_id, no history"), so every eligible
 * binding is polled on the same tick, every tick.
 *
 * <h2>Two replicas, and why this is not {@code FOR UPDATE SKIP LOCKED}</h2>
 *
 * <p>{@code JdbcPosScheduleStore#claimDue} takes a row lock so that two replicas
 * racing the same due catalog sync produce exactly one {@code PosSyncRequested}:
 * the loser does no work at all, which is right because a due occurrence that
 * somebody else already claimed is genuinely nothing for the loser to do.
 * Nothing here has an occurrence to skip past — V0190's own design refuses a
 * per-binding due time, so every eligible binding is polled by every replica on
 * every tick — and {@code SKIP LOCKED} would only mean a replica silently skips
 * polling a binding this tick, for no benefit to anyone.
 *
 * <p>That does not mean two replicas racing the same binding need no
 * coordination at all, and the first version of this class was wrong to assume
 * so. {@link JdbcPosLiveAvailabilityStore#replace}'s delete-then-upsert is two
 * statements, and without a lock a second replica's {@code DELETE} can run
 * against a snapshot that has not yet seen the first replica's still-uncommitted
 * inserts — it deletes nothing where it should have deleted a row the first
 * replica is about to write, and the surviving state is a mix of both replicas'
 * readings that neither of them actually reported. {@code SELECT ... FOR UPDATE}
 * cannot fix this either: it locks rows that already exist, and the row a
 * phantom insert is about to create is not there yet to lock. What fits is
 * {@code pg_advisory_xact_lock}, taken on the binding id as the first statement
 * inside {@link JdbcPosLiveAvailabilityStore#replace}: it locks the *binding*,
 * not a row, so it serialises two replicas' whole read-modify-write regardless
 * of what currently exists, and it is released automatically at commit or
 * rollback — held only across the short local write, never across the provider
 * HTTP call that already finished before this transaction opened. See {@code
 * JdbcPosLiveAvailabilityStoreTests
 * .twoReplicasRacingTheSameBindingConvergeToOneReplicasWholeReadingNeverAMix},
 * which reproduces the mixed-state race directly and fails without the lock.
 *
 * <h2>A failed poll: stale, never withdrawn</h2>
 *
 * <p>A provider timeout, rejection, or uncertain outcome leaves {@code
 * pos_live_availability} exactly where it was. It is never truncated and never
 * partially applied. The alternative — clearing a binding's rows because the
 * provider could not be reached — would not be a safe default; it would be
 * actively wrong, because an empty stop list reads as "everything is
 * unconstrained" (V0190's own rule) and would silently un-86 every product this
 * binding had marked out of stock. {@link uz.horecaos.platform.pos.infrastructure.storage.PosRawSnapshotWriter}'s
 * own tests name the analogous rule for a different write ("a diagnostic write
 * failing must never look like a run failure to the caller"); this is its mirror
 * for a read: a provider failure must never look like the menu emptying out. A
 * failure is never silent either — it is logged with the binding and a
 * classification, and counted, so an operator can tell a provider outage from a
 * quiet one.
 *
 * <h2>Reaching the customer</h2>
 *
 * <p>A reading nothing reads is the same defect again, so every entity that
 * crosses the out-of-stock line this poll is propagated to {@code inventory}
 * through {@link StockAvailabilityPort#toggle} — the identical seam ADR 0060's
 * bot {@code /86} command uses, not a second write path. Propagation only
 * reaches a variant that both has an ADR 0011 mapping ({@link
 * JdbcPosLiveAvailabilityStore#resolveDefaultVariants}) and is {@code
 * BINARY}-tracked at this binding's location; a product with neither is a
 * perfectly ordinary shape (nobody has mapped it yet, or this location tracks it
 * by quantity) and is skipped quietly rather than logged as a failure.
 */
@Component
@ConditionalOnProperty(name = "horecaos.pos.availability.poll.enabled", havingValue = "true", matchIfMissing = true)
public class PosAvailabilityPoll {

    private static final Logger log = LoggerFactory.getLogger(PosAvailabilityPoll.class);

    /** Recorded on every propagated toggle, never a person. */
    private static final String ACTOR_SUBJECT = "pos-availability-poll";

    private static final String REASON_CODE = "POS_STOP_LIST";

    private final JdbcPosLiveAvailabilityStore store;
    private final JdbcPosBindingConfiguration configuration;
    private final PosAdapterRegistry adapters;
    private final PosAvailabilityPollService pollService;
    private final StockAvailabilityPort stockAvailability;
    private final Clock clock;

    private final Counter pollSucceeded;
    private final Counter pollFailed;
    private final Counter propagated;

    /**
     * One tick at a time per process, matching {@code DeliverySourcingScheduler}'s
     * own guard: the interval can be configured shorter than a worst-case pass
     * over every eligible binding, and two overlapping passes in one JVM would
     * double the provider calls without adding a poller.
     */
    private final AtomicBoolean running = new AtomicBoolean();

    public PosAvailabilityPoll(
            JdbcPosLiveAvailabilityStore store,
            JdbcPosBindingConfiguration configuration,
            PosAdapterRegistry adapters,
            PosAvailabilityPollService pollService,
            StockAvailabilityPort stockAvailability,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.store = store;
        this.configuration = configuration;
        this.adapters = adapters;
        this.pollService = pollService;
        this.stockAvailability = stockAvailability;
        this.clock = clock;
        this.pollSucceeded = meterRegistry.counter("horecaos.pos.availability.poll", "outcome", "succeeded");
        this.pollFailed = meterRegistry.counter("horecaos.pos.availability.poll", "outcome", "failed");
        this.propagated = meterRegistry.counter("horecaos.pos.availability.propagated");
    }

    @Scheduled(
            initialDelayString = "${horecaos.pos.availability.poll.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.pos.availability.poll.interval:PT45S}")
    public void pollDueBindings() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Candidate candidate : store.eligibleBindings()) {
                try {
                    pollOne(candidate);
                } catch (RuntimeException failure) {
                    // One unreachable till, or one binding whose mapping is in a
                    // shape this poll did not expect, must not stop every other
                    // tenant's reading from being refreshed this tick.
                    pollFailed.increment();
                    log.warn("POS availability poll failed for binding {}", candidate.bindingId(), failure);
                }
            }
        } finally {
            running.set(false);
        }
    }

    private void pollOne(Candidate candidate) {
        Optional<PosAdapter> adapter = adapters.forProvider(candidate.providerType());
        if (adapter.isEmpty()) {
            log.debug(
                    "No POS adapter registered for provider {}; skipping binding {}",
                    candidate.providerType(),
                    candidate.bindingId());
            return;
        }

        // BindingRef requires a brand id on every binding -- see its own class
        // doc: a binding without one is refused when built, so any row this
        // query returned already has one. A row that somehow does not is a
        // malformed binding, not an ordinary poll outcome, and is caught by
        // this method's own caller like any other per-binding failure.
        BindingRef ref = new BindingRef(
                candidate.bindingId(),
                candidate.installationId(),
                candidate.tenantId(),
                ProviderCategory.POS,
                candidate.providerType(),
                Objects.requireNonNull(candidate.brandId(), "Binding " + candidate.bindingId() + " has no brand"),
                candidate.locationId());
        Map<String, String> config = configuration.resolve(ref).orElse(Map.of());
        PosContext context = new PosContext(
                candidate.tenantId(),
                candidate.installationId(),
                candidate.bindingId(),
                config.get("clopos.venueId"),
                config,
                "pos-availability-poll:" + candidate.bindingId());

        AvailabilityRead read = adapter.get().readAvailability(context);
        if (read.outcome().status() != ProviderOutcome.Status.SUCCESS) {
            pollFailed.increment();
            // Stale-but-served: nothing about pos_live_availability changes on a
            // failed read. See this class's own doc on why that, and not
            // clearing the binding's rows, is the safe reading.
            log.warn(
                    "POS availability poll for binding {} ({}) did not succeed: {} {}",
                    candidate.bindingId(),
                    candidate.providerType(),
                    read.outcome().status(),
                    read.outcome().errorCode());
            return;
        }
        pollSucceeded.increment();

        ReplaceResult diff =
                pollService.replace(candidate.tenantId(), candidate.bindingId(), read.entries(), clock.instant());
        propagate(candidate, diff);
    }

    private void propagate(Candidate candidate, ReplaceResult diff) {
        UUID locationId = candidate.locationId();
        if (locationId == null || diff.isEmpty()) {
            // A brand-scoped binding (no location at all) has nowhere in
            // inventory to point a toggle at. Every Clopos binding is a venue
            // (see CloposAdapter's own class doc), so this is provider-neutral
            // defensiveness rather than an expected path today.
            return;
        }

        Set<String> changed = new LinkedHashSet<>(diff.newlyOutOfStock());
        changed.addAll(diff.newlyBackInStock());
        Map<String, UUID> variants = store.resolveDefaultVariants(candidate.tenantId(), candidate.bindingId(), changed);

        for (String externalId : diff.newlyOutOfStock()) {
            toggle(candidate.tenantId(), locationId, variants.get(externalId), false);
        }
        for (String externalId : diff.newlyBackInStock()) {
            toggle(candidate.tenantId(), locationId, variants.get(externalId), true);
        }
    }

    private void toggle(UUID tenantId, UUID locationId, @Nullable UUID variantId, boolean available) {
        if (variantId == null) {
            // No ADR 0011 mapping to a HorecaOS product yet. An entirely
            // ordinary shape -- nobody has run a catalog sync for this binding,
            // or this product was never proposed as a draft -- and not a
            // failure.
            return;
        }
        try {
            stockAvailability.toggle(tenantId, locationId, variantId, available, REASON_CODE, ACTOR_SUBJECT);
            propagated.increment();
        } catch (IllegalArgumentException | IllegalStateException notEligible) {
            // Not stocked at this location, or tracked by quantity rather than
            // BINARY. Both are ordinary and frequent shapes for a mapped
            // product, not a reason to warn on every poll.
            log.debug(
                    "POS availability change for variant {} at location {} could not be applied: {}",
                    variantId,
                    locationId,
                    notEligible.getMessage());
        }
    }
}
