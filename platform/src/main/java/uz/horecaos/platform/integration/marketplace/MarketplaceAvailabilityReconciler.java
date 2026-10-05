package uz.horecaos.platform.integration.marketplace;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.integration.api.MarketplaceConfigurationKeys;
import uz.horecaos.platform.integration.api.marketplace.AvailabilityPush;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiTransport;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceAvailabilityAdapter;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceAvailabilityPushedPayload;
import uz.horecaos.platform.integration.api.marketplace.PushConclusion;
import uz.horecaos.platform.integration.api.provider.ProviderActivityRecorder;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.BindingRow;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.ItemRow;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.Outcome;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.SyncState;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.Watermark;
import uz.horecaos.platform.integration.outbox.MarketplaceOutbox;
import uz.horecaos.platform.integration.retry.RetryBackoff;
import uz.horecaos.platform.inventory.api.ChannelAvailabilityPort;
import uz.horecaos.platform.inventory.api.ChannelAvailabilityPort.ChannelAvailability;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * Makes each marketplace agree with what the platform currently believes about a dish (ADR
 * 0141 Decision 7, ADR 0040's {@code marketplace.availability.push}).
 *
 * <h2>Level-triggered, not edge-triggered</h2>
 *
 * <p>Each {@code (binding, mapped item)} row keeps the <em>desired</em> availability,
 * recomputed from inventory's one resolver, and the <em>confirmed</em> availability — what the
 * partner is <em>known</em> to hold, NULL when that is not known. The worker sends whenever the
 * two differ or the partner's state is unknown. So an outage replays no backlog, a lost event
 * leaves nothing wrong for longer than one resync interval, and a stop and a lift that both
 * happen while no push could have reached the partner collapse into nothing to send.
 *
 * <h2>Two ways to recompute desired, and only one is the guarantee</h2>
 *
 * <p><strong>Markers</strong> ({@link MarketplaceDirtyMarkerListener}) ask for an early sweep when
 * an input the resolver reads changes, so a stop reaches the partner in seconds. They are an
 * accelerator: the list of inputs cannot be the correctness argument, because an input the
 * resolver gains next year has no marker on the day it ships. A sweep clears only the marker it
 * read before it began: one written while it ran (a stop committed after its resolver read) stays
 * for the next pass, so that stop reaches the partner in seconds too and not a resync interval later
 * ({@link JdbcMarketplaceAvailabilityStore#recordSweep}). <strong>The resync sweep</strong>
 * recomputes every mapped item of every active binding through the resolver at least every
 * {@code resync_interval}, at <em>its own</em> {@code now}, so a branch rebound to a menu that
 * carries a {@code MENU} stop, an offering switched off, or a stop whose end passed while the
 * expiry sweeper was down all converge within one interval whether or not anything marked them.
 *
 * <h2>What the platform believes after an attempt</h2>
 *
 * <p>The route concludes {@code CONFIRMED}, {@code NOT_APPLIED} or {@code UNKNOWN} ({@link
 * PushConclusion}); the reconciler acts on the conclusion and never on a failure category. On
 * {@code UNKNOWN} the row stops believing it knows what the partner holds — a timed-out restore
 * the partner <em>did</em> apply, followed by a stop, would otherwise diff {@code false} against
 * a stale confirmed {@code false}, mark the row in sync and send nothing while the partner sells
 * the stopped dish.
 *
 * <h2>Safe under overlapping runs</h2>
 *
 * <p>The recompute of one binding runs under a transaction-scoped advisory lock (a second
 * replica skips it rather than racing it with a staler answer), and the send phase claims rows
 * under a lease with {@code FOR UPDATE SKIP LOCKED}, so two overlapping runs send disjoint
 * rows. A state-set call keyed {@code (binding, item, desired_seq)} is idempotent, which makes
 * the rare duplicate harmless rather than merely unlikely.
 *
 * <h2>Facts and the stale-channel alert</h2>
 *
 * <p>A confirmed push whose answer moved what the platform knows the partner holds publishes
 * {@code MarketplaceAvailabilityPushed} (ADR 0040) through the outbox, in the same transaction
 * as the row that records it and only while the worker still holds its lease, so a change is
 * announced once and an unknown outcome — which withdraws the belief — announces nothing. A
 * binding with a dish unconfirmed for longer than its bound is reported once per episode by
 * {@link MarketplaceStaleChannelMonitor}: the {@code MarketplaceChannelWentStale} fact and the
 * ADR 0058 operations alert, so the manager who stopped the plov hears that the aggregator
 * still sells it.
 *
 * <h2>Honest about what it cannot do</h2>
 *
 * <p>A binding whose provider has no registered adapter is never touched: no rows are kept, no
 * call is made, and the propagation read says {@code MANUAL}. Whether any of Uzum Tezkor, Yandex
 * Eda, Wolt or Express24 exposes an availability write API to a third party is a commercial
 * question this build does not pretend to have answered.
 */
@Service
public class MarketplaceAvailabilityReconciler {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceAvailabilityReconciler.class);

    /** Items sent per claim. Small enough that a lease covers a batch, large enough that a brand-wide stop is not slow. */
    static final int CLAIM_BATCH = 25;

    /** A claimed batch must finish within this, or another worker may take the rows. */
    static final Duration LEASE = Duration.ofMinutes(2);

    /**
     * How long one pass may keep calling one binding's partner. It is checked before every call, not
     * before every batch, so a pass lasts at most this plus the one call in flight (15 s by default,
     * {@code MarketplaceGateway}) -- far inside {@link #LEASE}, which is what keeps a claimed row's
     * desired value from being sent long after the kitchen changed its mind.
     */
    private static final Duration TICK_BUDGET_PER_BINDING = Duration.ofSeconds(20);

    /**
     * Unknown outcomes in a row (a timeout after the request was written) that close the door for the
     * rest of a pass. The breaker deliberately does not count an uncertain outcome as the partner
     * being down, so a partner that accepts the connection and never answers would otherwise cost
     * every claimed row its full timeout; two in a row is a hang, not bad luck.
     */
    static final int UNKNOWN_STREAK_LIMIT = 2;

    /**
     * The longest a binding whose partner just hung is left alone before it is probed again. The
     * rows' own backoffs are per item, so without this a hung binding would be probed on every tick
     * by whichever of its rows had come due.
     */
    static final Duration HANG_COOLDOWN_CAP = Duration.ofMinutes(2);

    private final JdbcMarketplaceAvailabilityStore store;
    private final MarketplaceAdapterRegistry adapters;
    private final MarketplaceApiTransport transport;
    private final ChannelAvailabilityPort inventory;
    private final SalesChannelLookup channels;
    private final ConfigurationResolver configuration;
    private final ProviderActivityRecorder activity;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final RetryBackoff backoff;
    private final MarketplaceOutbox outbox;
    private final MarketplaceStaleChannelMonitor staleMonitor;

    /** Bindings whose partner hung, until when they are not called and how many hangs in a row. In memory: a restart simply probes once. */
    private final Map<UUID, HangCooldown> hangs = new ConcurrentHashMap<>();

    private record HangCooldown(Instant until, int streak) {}

    private final AtomicLong pendingItems = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicLong staleChannels = new AtomicLong();

    @Autowired
    public MarketplaceAvailabilityReconciler(
            JdbcMarketplaceAvailabilityStore store,
            MarketplaceAdapterRegistry adapters,
            MarketplaceApiTransport transport,
            ChannelAvailabilityPort inventory,
            SalesChannelLookup channels,
            ConfigurationResolver configuration,
            ProviderActivityRecorder activity,
            Clock clock,
            MeterRegistry meters,
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            MarketplaceOutbox outbox,
            MarketplaceStaleChannelMonitor staleMonitor) {
        this(
                store,
                adapters,
                transport,
                inventory,
                channels,
                configuration,
                activity,
                clock,
                meters,
                transactionManager,
                jdbc,
                RetryBackoff.of(Duration.ofSeconds(5), Duration.ofMinutes(10)),
                outbox,
                staleMonitor);
    }

    /** The injectable backoff is how a test seeds the jitter. */
    MarketplaceAvailabilityReconciler(
            JdbcMarketplaceAvailabilityStore store,
            MarketplaceAdapterRegistry adapters,
            MarketplaceApiTransport transport,
            ChannelAvailabilityPort inventory,
            SalesChannelLookup channels,
            ConfigurationResolver configuration,
            ProviderActivityRecorder activity,
            Clock clock,
            MeterRegistry meters,
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            RetryBackoff backoff,
            MarketplaceOutbox outbox,
            MarketplaceStaleChannelMonitor staleMonitor) {
        this.store = store;
        this.adapters = adapters;
        this.transport = transport;
        this.inventory = inventory;
        this.channels = channels;
        this.configuration = configuration;
        this.activity = activity;
        this.clock = clock;
        this.meters = meters;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.backoff = backoff;
        this.outbox = outbox;
        this.staleMonitor = staleMonitor;
        // Aggregate gauges only: a binding id as a tag would be unbounded (ADR 0029).
        meters.gauge("horecaos.marketplace.availability.pending_items", pendingItems);
        meters.gauge("horecaos.marketplace.availability.oldest_pending_seconds", oldestPendingSeconds);
        // How many bindings are inside a reported stale episode right now (see
        // MarketplaceStaleChannelMonitor): the one number an operator-level alert needs, with no
        // tenant, binding or dish in it.
        meters.gauge("horecaos.marketplace.availability.stale_channels", staleChannels);
    }

    /** What one pass over every binding did. */
    public record TickReport(int bindings, int sweeps, int pushes) {}

    /** What one pass over one binding did. */
    public record BindingReport(boolean swept, int pushed, boolean suspended, boolean manual) {}

    /** One pass over every active marketplace binding: recompute if due, then send what differs. */
    public TickReport tick() {
        int bindings = 0;
        int sweeps = 0;
        int pushes = 0;
        for (BindingRow binding : store.activeMarketplaceBindings()) {
            bindings++;
            try {
                BindingReport report = reconcile(binding);
                sweeps += report.swept() ? 1 : 0;
                pushes += report.pushed();
            } catch (RuntimeException failure) {
                // One venue's fault must never stop every other venue's stop list.
                log.warn("Marketplace availability reconcile failed for a {} binding", binding.providerType(), failure);
            }
        }
        try {
            // A binding that left the worklist is never evaluated, so its stale mark is ended here.
            int ended = store.clearStaleReportedOfInactiveBindings(clock.instant());
            if (ended > 0) {
                log.info("Ended the stale episode of {} marketplace bindings no longer worked", ended);
            }
        } catch (RuntimeException failure) {
            log.warn("Could not end the stale episodes of marketplace bindings no longer worked", failure);
        }
        refreshGauges();
        return new TickReport(bindings, sweeps, pushes);
    }

    /** The reconcile of one binding. Public so a test drives it directly at an instant of its choosing. */
    public BindingReport reconcile(BindingRow binding) {
        Instant now = clock.instant();
        boolean enabled = reconcileEnabled(binding);
        Optional<SyncState> sync = store.syncState(binding.bindingId());
        if (!enabled) {
            // Suspended: no call reaches any partner and no row is touched. Remembering that it
            // was suspended is what makes the next enabled tick a resumption.
            store.recordSuspended(binding.tenantId(), binding.bindingId(), now);
            return new BindingReport(false, 0, true, false);
        }
        Optional<MarketplaceAvailabilityAdapter> adapter = adapters.forProvider(binding.providerType());
        if (adapter.isEmpty()) {
            // MANUAL: this provider has no availability write API this build can call. Nothing is
            // pushing for the binding, so an episode it was reported stale in has ended.
            store.clearStaleReported(binding.tenantId(), binding.bindingId(), now);
            return new BindingReport(false, 0, false, true);
        }

        boolean stale = isStale(binding, now);
        boolean resumed = sync.isPresent() && !sync.get().reconcileWasEnabled();
        boolean recovered = sync.isPresent() && sync.get().wasStale() && !stale;
        if (resumed || recovered) {
            // The partner portal may have been edited by hand while we could not reach it, so
            // withdraw every belief about what it holds and resend everything once.
            int withdrawn = store.forgetConfirmations(binding.tenantId(), binding.bindingId(), now);
            log.info(
                    "Marketplace availability {} for a {} binding: {} item confirmations withdrawn",
                    resumed ? "resumed" : "recovered from a stale watermark",
                    binding.providerType(),
                    withdrawn);
        }

        boolean due = resumed
                || recovered
                || sync.isEmpty()
                || sync.get().sweepRequestedAt() != null
                || sync.get().nextSweepAt() == null
                || !sync.get().nextSweepAt().isAfter(now);
        boolean swept = false;
        if (due) {
            swept = sweep(binding, now, stale, sync.map(SyncState::rowVersion).orElse(null));
        } else if (stale != sync.map(SyncState::wasStale).orElse(false)) {
            store.recordSweepState(binding.tenantId(), binding.bindingId(), true, stale, now);
        }

        int pushed = push(binding, adapter.get());
        // After the pass, judged at the instant the pass ended: a binding whose pushes have gone
        // unconfirmed for longer than its bound is reported once, and cleared when it recovers.
        staleMonitor.evaluate(binding, staleAfterSeconds(binding), clock.instant());
        return new BindingReport(swept, pushed, false, false);
    }

    // ------------------------------------------------------------------ recompute

    /**
     * Recomputes every mapped item of the binding through the resolver and writes the desired
     * values. Under a transaction-scoped advisory lock so a second replica skips rather than
     * racing it with a staler answer.
     *
     * @param observedRowVersion the sync-state row as this pass read it before anything below did; a
     *     marker written since is not the one this sweep honours and survives it
     * @return whether this call did the sweep (false when another run held the lock)
     */
    private boolean sweep(BindingRow binding, Instant now, boolean stale, @Nullable String observedRowVersion) {
        Boolean done = transactions.execute(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "marketplace-availability:" + binding.bindingId())
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            Optional<SalesChannel> channel =
                    channels.byProviderInstallation(binding.tenantId(), binding.installationId());
            if (channel.isEmpty()) {
                // No unambiguous channel backs this installation (none, or several): nothing can
                // be resolved, and a push that guessed would tell the partner about the wrong one.
                store.recordSweep(
                        binding.tenantId(),
                        binding.bindingId(),
                        now,
                        nextSweepAt(binding, now),
                        0,
                        true,
                        stale,
                        observedRowVersion);
                return true;
            }
            Map<UUID, String> mapped = store.mappedItems(binding.tenantId(), binding.bindingId());
            Map<UUID, ChannelAvailability> resolved = mapped.isEmpty()
                    ? Map.of()
                    : inventory.resolve(
                            binding.tenantId(),
                            binding.brandId(),
                            binding.locationId(),
                            channel.get().id(),
                            mapped.keySet(),
                            now);
            for (Map.Entry<UUID, String> item : mapped.entrySet()) {
                ChannelAvailability availability = resolved.get(item.getKey());
                // An item the resolver returned nothing for is not sellable: fail toward under-selling.
                boolean desired = availability != null && availability.sellable();
                store.upsertDesired(
                        binding.tenantId(),
                        binding.bindingId(),
                        item.getValue(),
                        item.getKey(),
                        binding.locationId(),
                        desired,
                        now);
            }
            store.removeUnmapped(binding.tenantId(), binding.bindingId(), mapped.values());
            store.recordSweep(
                    binding.tenantId(),
                    binding.bindingId(),
                    now,
                    nextSweepAt(binding, now),
                    mapped.size(),
                    true,
                    stale,
                    observedRowVersion);
            meters.counter("horecaos.marketplace.availability.sweep").increment();
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private Instant nextSweepAt(BindingRow binding, Instant now) {
        Integer seconds = configuration.value(
                MarketplaceConfigurationKeys.RESYNC_INTERVAL_SECONDS, ResourceScope.tenant(binding.tenantId()));
        long interval = seconds == null || seconds < 1 ? 300 : seconds;
        // Jittered per binding, deterministically (±10%), so forty venues do not tick together
        // and a given binding's schedule is the same on every replica.
        double fraction = ((binding.bindingId().hashCode() & 0x7fffffff) % 2001) / 1000.0 - 1.0; // [-1, 1]
        long jittered = Math.max(1, Math.round(interval * (1.0 + 0.1 * fraction)));
        return now.plusSeconds(jittered);
    }

    // ------------------------------------------------------------------ send

    private int push(BindingRow binding, MarketplaceAvailabilityAdapter adapter) {
        HangCooldown cooling = hangs.get(binding.bindingId());
        if (cooling != null && clock.instant().isBefore(cooling.until())) {
            // The partner hung on the last pass. The sweep above still ran (it touches only our own
            // database), so the desired values are current; the call waits for the cooldown.
            return 0;
        }
        String owner = UUID.randomUUID().toString();
        int pushed = 0;
        Instant deadline = clock.instant().plus(TICK_BUDGET_PER_BINDING);
        boolean doorClosed = false;
        int unknownInARow = 0;
        while (!doorClosed && clock.instant().isBefore(deadline)) {
            List<ItemRow> claimed =
                    store.claimDue(binding.tenantId(), binding.bindingId(), owner, clock.instant(), LEASE, CLAIM_BATCH);
            if (claimed.isEmpty()) {
                break;
            }
            for (ItemRow item : claimed) {
                if (!doorClosed && !clock.instant().isBefore(deadline)) {
                    // The budget ran out part-way through a batch: a slow partner must not hold
                    // the tick, or the lease, for the rest of the batch.
                    doorClosed = true;
                    meters.counter("horecaos.marketplace.availability.door_closed", "reason", "BUDGET")
                            .increment();
                }
                if (doorClosed) {
                    // The partner is refusing us at the door (rate limit, open circuit), is hung, or
                    // the budget is spent: the rest of the batch would fare no better. Leave the rows
                    // for the next tick -- an outage costs probes, not a storm.
                    store.releaseLease(item, owner);
                    continue;
                }
                Sent result = send(binding, adapter, item, owner);
                pushed++;
                unknownInARow = result.conclusion() == PushConclusion.UNKNOWN ? unknownInARow + 1 : 0;
                if (result.conclusion() == PushConclusion.CONFIRMED) {
                    hangs.remove(binding.bindingId());
                }
                boolean refused = result.conclusion() == PushConclusion.NOT_APPLIED
                        && ("CIRCUIT_OPEN".equals(result.code()) || "RATE_LIMITED".equals(result.code()));
                boolean hung = unknownInARow >= UNKNOWN_STREAK_LIMIT;
                if (hung) {
                    coolDown(binding);
                    meters.counter("horecaos.marketplace.availability.door_closed", "reason", "HUNG")
                            .increment();
                } else if (refused) {
                    meters.counter("horecaos.marketplace.availability.door_closed", "reason", "REFUSED")
                            .increment();
                }
                doorClosed = refused || hung;
            }
        }
        return pushed;
    }

    /** Leaves a binding whose partner just hung alone for a while, longer each time it happens in a row. */
    private void coolDown(BindingRow binding) {
        Instant now = clock.instant();
        HangCooldown next = hangs.merge(
                binding.bindingId(),
                new HangCooldown(now.plus(cooldownAfter(1)), 1),
                (previous, fresh) ->
                        new HangCooldown(now.plus(cooldownAfter(previous.streak() + 1)), previous.streak() + 1));
        log.warn(
                "Marketplace availability push to a {} binding stopped after {} unknown outcomes in a row; not called again until {}",
                binding.providerType(),
                UNKNOWN_STREAK_LIMIT,
                next.until());
    }

    private Duration cooldownAfter(int streak) {
        Duration delay = backoff.delayAfter(streak);
        return delay.compareTo(HANG_COOLDOWN_CAP) > 0 ? HANG_COOLDOWN_CAP : delay;
    }

    /** What one attempt concluded, and the stable code behind it. */
    private record Sent(PushConclusion conclusion, @Nullable String code) {}

    private Sent send(BindingRow binding, MarketplaceAvailabilityAdapter adapter, ItemRow item, String owner) {
        Instant sentAt = clock.instant();
        boolean sent = item.desiredAvailable();
        String correlation =
                "mkt-avail:%s:%s:%d".formatted(binding.bindingId(), item.externalEntityId(), item.desiredSeq());

        ProviderOutcome outcome;
        try {
            MarketplaceApiCall call = adapter.availabilityCall(new AvailabilityPush(
                    binding.tenantId(),
                    binding.bindingId(),
                    binding.installationId(),
                    binding.providerType(),
                    item.externalEntityId(),
                    sent,
                    item.desiredSeq(),
                    correlation));
            outcome = adapter.interpret(transport.exchange(call));
        } catch (RuntimeException adapterFailure) {
            // An adapter that threw while building or reading the call: whether it threw before
            // or after anything left the process is unknowable, so the conclusion is the
            // pessimistic one -- unknown -- and the next tick sends the current truth again.
            outcome = ProviderOutcome.retryable(
                    "ADAPTER_FAILURE", adapterFailure.getClass().getSimpleName(), null);
        }
        PushConclusion conclusion = PushConclusion.of(outcome);
        String code = outcome.errorCode() == null ? null : safeCode(outcome.errorCode());
        Instant now = clock.instant();

        switch (conclusion) {
            case CONFIRMED -> {
                // The row that records the confirmation and the fact that announces it commit
                // together, and only if this worker still holds the lease: a worker whose lease
                // lapsed writes neither, so a change is announced once. Only a success is
                // announced: a refusal changes nothing the platform believes, and an unknown
                // outcome withdraws the belief rather than confirming anything.
                transactions.executeWithoutResult(transaction -> {
                    boolean recorded = store.recordOutcome(item, owner, sent, Outcome.CONFIRMED, null, now, null);
                    if (recorded) {
                        outbox.availabilityPushed(
                                binding.tenantId(),
                                new MarketplaceAvailabilityPushedPayload(
                                        binding.bindingId(),
                                        binding.locationId(),
                                        item.variantId(),
                                        binding.providerType(),
                                        item.externalEntityId(),
                                        sent,
                                        item.desiredSeq(),
                                        now));
                    }
                });
                activity.recordSuccess(
                        binding.tenantId(),
                        binding.bindingId(),
                        binding.locationId(),
                        "OUTBOUND",
                        "availability",
                        staleAfterSeconds(binding),
                        now);
            }
            case NOT_APPLIED -> {
                store.recordOutcome(
                        item, owner, sent, Outcome.NOT_APPLIED, code, now, now.plus(retryDelay(item, outcome)));
                activity.recordFailure(
                        binding.tenantId(),
                        binding.bindingId(),
                        binding.locationId(),
                        "OUTBOUND",
                        code == null ? "NOT_APPLIED" : code,
                        staleAfterSeconds(binding),
                        now);
            }
            case UNKNOWN -> {
                store.recordOutcome(item, owner, sent, Outcome.UNKNOWN, code, now, now.plus(retryDelay(item, outcome)));
                activity.recordFailure(
                        binding.tenantId(),
                        binding.bindingId(),
                        binding.locationId(),
                        "OUTBOUND",
                        code == null ? "UNKNOWN_OUTCOME" : code,
                        staleAfterSeconds(binding),
                        now);
            }
            case REJECTED_UNMAPPED ->
                store.recordOutcome(item, owner, sent, Outcome.REJECTED_UNMAPPED, "UNKNOWN_ITEM", now, null);
        }
        meters.counter("horecaos.marketplace.availability.push", "conclusion", conclusion.name())
                .increment();
        log.debug(
                "Marketplace availability push for a {} binding concluded {} in {}ms",
                binding.providerType(),
                conclusion,
                Duration.between(sentAt, now).toMillis());
        return new Sent(conclusion, code);
    }

    private Duration retryDelay(ItemRow item, ProviderOutcome outcome) {
        return outcome.retryDelay().orElseGet(() -> backoff.delayAfter(item.attemptCount() + 1));
    }

    /** A code is stored as a stable identifier, never a provider's own words (ADR 0029). */
    private static String safeCode(String code) {
        String upper = code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        return upper.length() > 48 ? upper.substring(0, 48) : upper;
    }

    // ------------------------------------------------------------------ switches and health

    private boolean reconcileEnabled(BindingRow binding) {
        Boolean enabled = configuration.value(
                MarketplaceConfigurationKeys.RECONCILE_ENABLED, ResourceScope.tenant(binding.tenantId()));
        return !Boolean.FALSE.equals(enabled);
    }

    private int staleAfterSeconds(BindingRow binding) {
        Integer seconds = configuration.value(
                MarketplaceConfigurationKeys.STALE_AFTER_SECONDS, ResourceScope.tenant(binding.tenantId()));
        return seconds == null || seconds < 1 ? 1800 : seconds;
    }

    /**
     * Whether the binding's outbound pushes have gone unconfirmed for longer than its bound: the
     * watermark says {@code STALE} <em>and</em> the last success is older than {@code
     * stale_after_seconds}. A single failed push marks the watermark stale at once, so the alert
     * state alone would withdraw every confirmation after one refused connection.
     */
    private boolean isStale(BindingRow binding, Instant now) {
        Optional<Watermark> watermark = store.outboundWatermark(binding.tenantId(), binding.bindingId());
        if (watermark.isEmpty() || !"STALE".equals(watermark.get().alertState())) {
            return false;
        }
        Instant lastSuccess = watermark.get().lastSuccessAt();
        return lastSuccess == null
                || lastSuccess.plusSeconds(watermark.get().staleAfterSeconds()).isBefore(now);
    }

    private void refreshGauges() {
        // Aggregate over every binding at the end of the tick; cheap, and never labelled by binding.
        Instant now = clock.instant();
        long pending = jdbc.sql("""
                SELECT count(*) FROM integration.marketplace_item_availability WHERE state <> 'IN_SYNC'
                """).query(Long.class).single();
        Instant oldest = jdbc.sql("""
                SELECT min(pending_since) FROM integration.marketplace_item_availability WHERE state <> 'IN_SYNC'
                """)
                .query(java.time.OffsetDateTime.class)
                .optional()
                .map(java.time.OffsetDateTime::toInstant)
                .orElse(null);
        staleChannels.set(store.countStaleReportedActive());
        pendingItems.set(pending);
        oldestPendingSeconds.set(
                oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds()));
    }
}
