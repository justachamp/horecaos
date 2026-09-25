package uz.horecaos.platform.inventory.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.AvailabilityDecision.Unavailable;
import uz.horecaos.platform.inventory.api.BusinessDayWindows;
import uz.horecaos.platform.inventory.api.InventoryConfigurationKeys;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.inventory.api.ReservationResult;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.domain.ReservationExpiry;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore.ChannelStopThresholdRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore.QuantityReservationLineRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore.StockItemListingRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore.StockItemRow;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * Availability and the reservation path, for every tracking mode ADR 0017
 * names (BINARY and UNTRACKED from the first cutover slice; QUANTITY from
 * gap map row 4.4c).
 *
 * <p>A BINARY hold means "this was available when the cart was priced and
 * nobody has marked it out since", not "one portion is set aside for you". A
 * QUANTITY hold is the real thing: {@link #reserveForQuote} atomically moves
 * {@code inventory.positions.reserved_quantity} for a QUANTITY item, refusing
 * where {@code on_hand - reserved} cannot cover the request, and {@link
 * #commit}/{@link #release}/{@link #expireStaleReservations} move it back —
 * but only while {@code catalog.use_stock_logic} is on for the tenant. Off,
 * a QUANTITY item behaves exactly like UNTRACKED: see {@link
 * #evaluateAvailability} and {@link TrackingMode#QUANTITY}'s own doc.
 *
 * <p>Two of ADR 0017's own open inputs are taken conservatively here rather
 * than decided, and say so: negative-stock policy is "never go negative:
 * refuse" — {@link JdbcInventoryStore#tryReserveQuantity} is the one
 * authoritative gate, a conditional {@code UPDATE} that can never leave
 * {@code reserved_quantity} above {@code on_hand_quantity} — and cancellation
 * restock is "a cancellation releases HELD stock only; committed stock is
 * not restocked" — {@link #release} only ever transitions a {@code HELD}
 * reservation (its own {@code WHERE status = 'HELD'} predicate), and nothing
 * in this class reopens a {@code COMMITTED} one. A fourth primitive for
 * returning committed stock is gap map row 1.2c's own open question, not
 * this wave's to answer.
 */
@Service
public class InventoryService implements InventoryReservationPort {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    /**
     * The platform default: matches the ADR 0018 quote TTL, so a hold does not
     * outlive its quote and keep stock back for a price nobody can still
     * accept. {@code InventoryConfigurationKeyTests} keeps this literal equal
     * to {@link InventoryConfigurationKeys#RESERVATION_TTL_SECONDS}'s own
     * default so the two cannot drift apart.
     *
     * <p>Wired 2026-09-10 to {@code inventory.reservation_ttl_seconds}
     * (ADR 0030): {@link #reserveForQuote} resolves the live TTL per
     * tenant/brand/location rather than using this constant directly, and
     * then never returns an expiry earlier than the specific quote's own
     * stored {@code expiresAt} — see {@link ReservationExpiry} for why that
     * floor exists and is not merely "resolve pricing's key too and compare".
     */
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private static final String OWNER_QUOTE = "QUOTE";

    /**
     * Every existing caller that builds this service by hand (test fixtures
     * that predate ADR 0060, none of which cares whether a sold-out toggle is
     * audited) gets this rather than a constructor signature change that
     * would touch all of them. Production wiring uses the six-argument,
     * {@code @Autowired} constructor below and gets the real recorder.
     */
    private static final AuditRecorder NO_OP_AUDIT = fact -> {};

    /**
     * The same story as {@link #NO_OP_AUDIT}, for the ADR 0056 backstop:
     * every fixture built by hand here runs against {@code TestDatabase}'s
     * migrator connection, which owns every table row-level security could
     * ever apply to and so is exempt from it regardless of what this binds.
     * Production wiring uses the six-argument, {@code @Autowired}
     * constructor below and gets {@link uz.horecaos.platform.configuration.rls.JdbcTenantRlsSession},
     * the one that actually talks to PostgreSQL.
     */
    private static final TenantRlsSession NO_OP_RLS = new TenantRlsSession() {
        @Override
        public void bindTenant(UUID tenantId) {}

        @Override
        public void bindPlatform() {}
    };

    /**
     * The same story again, for ADR 0030: a fixture built by hand here has
     * nothing configured, and "nothing configured" is exactly what a key's own
     * code default means. Every value this returns is {@link
     * ConfigurationKey#defaultValue()} regardless of scope, which for {@link
     * InventoryConfigurationKeys#RESERVATION_TTL_SECONDS} is the same 900
     * seconds {@link #RESERVATION_TTL} already names — so wiring the real
     * resolver in changes nothing an existing fixture asserts. Production
     * wiring uses the six-argument, {@code @Autowired} constructor below and
     * gets {@link uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver}.
     */
    private static final ConfigurationResolver NO_OP_CONFIGURATION = new ConfigurationResolver() {
        @Override
        public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
            return new Resolved<>(
                    key.defaultValue(),
                    new ResolutionTrace(key.code(), ResolutionTrace.Source.CODE_DEFAULT, null, List.of()));
        }

        @Override
        public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
            return resolve(key, scope).trace();
        }
    };

    /**
     * A fixture built by hand here has no tenant business calendar to resolve
     * against, so this answers plain UTC-midnight — {@link
     * uz.horecaos.platform.reporting.domain.BusinessDayBoundary#midnight}'s own
     * default — which is exactly today's previously-nonexistent behaviour for
     * every fixture that does not specifically test the daily reset. Production
     * wiring uses the seven-argument, {@code @Autowired} constructor below and
     * gets {@link uz.horecaos.platform.reporting.application.InventoryBusinessDayWindowsAdapter},
     * the one that actually resolves a tenant's own boundary and timezone.
     */
    private static final BusinessDayWindows NO_OP_BUSINESS_DAY_WINDOWS =
            (tenantId, at) -> at.atZone(ZoneOffset.UTC).toLocalDate();

    private final JdbcInventoryStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final AuditRecorder audit;
    private final TenantRlsSession rls;
    private final ConfigurationResolver configuration;
    private final BusinessDayWindows businessDays;

    public InventoryService(JdbcInventoryStore store, ApplicationEventPublisher events, Clock clock) {
        this(store, events, clock, NO_OP_AUDIT);
    }

    public InventoryService(
            JdbcInventoryStore store, ApplicationEventPublisher events, Clock clock, AuditRecorder audit) {
        this(store, events, clock, audit, NO_OP_RLS);
    }

    public InventoryService(
            JdbcInventoryStore store,
            ApplicationEventPublisher events,
            Clock clock,
            AuditRecorder audit,
            TenantRlsSession rls) {
        this(store, events, clock, audit, rls, NO_OP_CONFIGURATION);
    }

    public InventoryService(
            JdbcInventoryStore store,
            ApplicationEventPublisher events,
            Clock clock,
            AuditRecorder audit,
            TenantRlsSession rls,
            ConfigurationResolver configuration) {
        this(store, events, clock, audit, rls, configuration, NO_OP_BUSINESS_DAY_WINDOWS);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InventoryService(
            JdbcInventoryStore store,
            ApplicationEventPublisher events,
            Clock clock,
            AuditRecorder audit,
            TenantRlsSession rls,
            ConfigurationResolver configuration,
            BusinessDayWindows businessDays) {
        this.store = store;
        this.events = events;
        this.clock = clock;
        this.audit = audit;
        this.rls = rls;
        this.configuration = configuration;
        this.businessDays = businessDays;
    }

    @Transactional
    public UUID listVariantAtLocation(UUID tenantId, UUID brandId, UUID locationId, UUID variantId, TrackingMode mode) {
        rls.bindTenant(tenantId);
        // QUANTITY may be listed regardless of catalog.use_stock_logic: the
        // schema has always accommodated it (V0019), and an operator counting
        // and reconciling a variant's stock before turning the tenant-wide
        // switch on is ADR 0017's own rollout phase ("quantity tracking for
        // explicitly reconciled variants") — enforcement is gated at
        // availability/reservation time by evaluateAvailability instead, not
        // here.
        return store.createStockItem(tenantId, brandId, locationId, variantId, mode, clock.instant());
    }

    /**
     * Whether every item in a cart can be fulfilled here, at quantity one each
     * — the shape every caller of this port method actually wants (a bare
     * "can I sell one of this", including the ADR 0008 {@code
     * ACTIVATION_SMOKE_TEST}). {@link #reserveForQuote} calls the real,
     * quantity-aware {@link #evaluateAvailability} directly instead, since it
     * already holds each line's actual requested quantity.
     *
     * <p>An item with no stock record is unavailable rather than available. The
     * opposite default would let a location sell anything on the brand's menu
     * simply because nobody had listed it, which is how a kitchen receives an
     * order for a dish it does not make.
     */
    @Override
    @Transactional(readOnly = true)
    public AvailabilityDecision checkAvailability(UUID tenantId, UUID locationId, Set<UUID> variantIds) {
        rls.bindTenant(tenantId);
        Map<UUID, Integer> quantityOfOneEach = new java.util.HashMap<>();
        variantIds.forEach(variantId -> quantityOfOneEach.put(variantId, 1));
        return evaluateAvailability(tenantId, locationId, quantityOfOneEach, null);
    }

    /**
     * The channel-aware read gap map row 4.4c asks for: the same check as
     * {@link #checkAvailability}, at quantity one each, plus the per-channel-
     * type stop threshold ({@code inventory.channel_stop_thresholds}) a
     * QUANTITY item may carry. Never used by the reservation/hold path — a
     * hold is refused only by true stock exhaustion, never by this
     * projection-only cutoff (see {@code AvailabilityDecision.Unavailable
     * #channelStopped}'s own doc).
     *
     * @param channelSystemType {@code tenant.sales_channels.system_type}
     *                          (e.g. {@code AGGREGATOR}), or null for no
     *                          channel context at all — equivalent to {@link
     *                          #checkAvailability}
     */
    @Transactional(readOnly = true)
    public AvailabilityDecision checkAvailabilityForChannel(
            UUID tenantId, UUID locationId, Set<UUID> variantIds, @Nullable String channelSystemType) {
        rls.bindTenant(tenantId);
        Map<UUID, Integer> quantityOfOneEach = new java.util.HashMap<>();
        variantIds.forEach(variantId -> quantityOfOneEach.put(variantId, 1));
        return evaluateAvailability(tenantId, locationId, quantityOfOneEach, channelSystemType);
    }

    /**
     * The one place every tracking mode's own rule is applied. Both public
     * availability reads and {@link #reserveForQuote} go through this rather
     * than each re-stating the per-mode switch — the exact duplication that
     * let {@code checkAvailability} and {@code reserveForQuote} disagree about
     * QUANTITY before this wave (the first only ever checked presence, never
     * quantity).
     *
     * <p>Does not mutate anything — this is the read half. {@link
     * #reserveForQuote} calls this first and then, for each QUANTITY item it
     * finds available, atomically attempts the real hold through {@link
     * JdbcInventoryStore#tryReserveQuantity}, which is the authoritative gate
     * under concurrency; this method's own read of {@code on_hand -
     * reserved} is a snapshot that can go stale between here and that
     * attempt, by design (see that method's own doc).
     */
    private AvailabilityDecision evaluateAvailability(
            UUID tenantId,
            UUID locationId,
            Map<UUID, Integer> quantitiesByVariant,
            @Nullable String channelSystemType) {
        Map<UUID, StockItemRow> items = store.findStockItems(tenantId, locationId, quantitiesByVariant.keySet());
        List<Unavailable> blocked = new ArrayList<>();
        Boolean quantityLogicOn = null;

        for (Map.Entry<UUID, Integer> entry : quantitiesByVariant.entrySet()) {
            UUID variantId = entry.getKey();
            StockItemRow item = items.get(variantId);
            if (item == null) {
                blocked.add(Unavailable.notStocked(variantId));
                continue;
            }
            switch (item.trackingMode()) {
                case UNTRACKED -> {
                    // Unlimited. The catalog offering still decides whether it is
                    // shown at all, so untracked is not the same as always visible.
                }
                case BINARY -> {
                    if (!Boolean.TRUE.equals(item.binaryAvailable())) {
                        blocked.add(Unavailable.soldOut(variantId));
                    }
                }
                case QUANTITY -> {
                    // Resolved at most once per call, not once per item: every
                    // QUANTITY item in one request shares the same tenant, so the
                    // flag can only ever answer the same way for all of them.
                    if (quantityLogicOn == null) {
                        quantityLogicOn = useStockLogicEnabled(tenantId);
                    }
                    if (!quantityLogicOn) {
                        // catalog.use_stock_logic is off: this item behaves exactly
                        // like UNTRACKED (TrackingMode.QUANTITY's own doc).
                        continue;
                    }
                    BigDecimal requested = BigDecimal.valueOf(entry.getValue());
                    BigDecimal remaining = item.remainingQuantity();
                    if (remaining.compareTo(requested) < 0) {
                        blocked.add(Unavailable.soldOut(variantId));
                        continue;
                    }
                    if (channelSystemType != null) {
                        store.findChannelStopThreshold(tenantId, item.stockItemId(), channelSystemType)
                                .filter(threshold -> remaining.compareTo(threshold) <= 0)
                                .ifPresent(threshold -> blocked.add(Unavailable.channelStopped(variantId)));
                    }
                }
            }
        }

        return blocked.isEmpty() ? AvailabilityDecision.allAvailable() : AvailabilityDecision.blockedBy(blocked);
    }

    /** A kitchen marking a dish sold out, or back on. */
    @Transactional
    public void setAvailability(
            UUID tenantId,
            UUID locationId,
            UUID variantId,
            boolean available,
            String reasonCode,
            @Nullable UUID actorId) {
        rls.bindTenant(tenantId);

        StockItemRow item = store.findStockItem(tenantId, locationId, variantId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Variant " + variantId + " is not stocked at this location"));

        if (item.trackingMode() != TrackingMode.BINARY) {
            throw new IllegalStateException(
                    "Availability can only be set on a BINARY item; this one is " + item.trackingMode());
        }

        if (Boolean.valueOf(available).equals(item.binaryAvailable())) {
            // Already in this state, so nothing happened and nothing is recorded.
            // A movement per repeated tap would fill the ledger with events that
            // changed nothing and bury the ones that did.
            log.debug(
                    "Variant {} at location {} is already {}",
                    variantId,
                    locationId,
                    available ? "available" : "unavailable");
            return;
        }

        // Unique per real transition: the position sequence advances only when a
        // state actually changes, so a genuine flip back is never swallowed as a
        // duplicate while a concurrent repeat of this same transition is.
        String idempotencyKey = "avail:%s:%s:%d".formatted(variantId, available, item.positionSequence());

        store.setBinaryAvailability(
                tenantId,
                item.stockItemId(),
                available,
                idempotencyKey,
                reasonCode,
                actorId == null ? "SERVICE" : "USER",
                actorId,
                clock.instant());

        log.info("Variant {} at location {} marked {}", variantId, locationId, available ? "available" : "unavailable");

        // ADR 0058's operations trigger, gated in notifications on the
        // available->false direction alone ("an item 86'd") — this event
        // fires for both directions of the toggle because the fact itself
        // ("availability changed") is symmetric, and item.brandId() is
        // already in hand from the findStockItem read above, so there is
        // nothing to gain by publishing only one of the two.
        events.publishEvent(new ItemAvailabilityChanged(
                UUID.randomUUID(),
                tenantId,
                item.brandId(),
                locationId,
                variantId,
                available,
                reasonCode,
                clock.instant()));
    }

    /**
     * The same 86 toggle as {@link #setAvailability}, plus the ADR 0027 audit
     * fact it never wrote on any channel: "a kitchen marking a dish sold out,
     * or back on" changed {@code inventory.movements} and nothing else, so an
     * operator asking "who 86'd the plov at 19:00" had no answer outside the
     * ledger's own actor column — not an audit trail an investigator, a
     * dispute, or a support ticket could search the way every other mutation
     * in this platform can. One call site, both callers: {@code
     * InventoryController.setAvailability} (web) and the bot's typed
     * {@code /86} command, mirroring {@code CatalogAuthoringService}'s own
     * audited {@code setOffering} overload for the parallel gap on that
     * mutation.
     *
     * @return whether the item actually changed state — false when it was
     *         already there, matching {@link #setAvailability}'s own no-op
     *         rule, so a caller can render "already X" without a second query
     */
    @Transactional
    public boolean setAvailabilityAudited(
            UUID tenantId, UUID locationId, UUID variantId, boolean available, String reasonCode, String actorSubject) {
        rls.bindTenant(tenantId);
        StockItemRow before = store.findStockItem(tenantId, locationId, variantId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Variant " + variantId + " is not stocked at this location"));

        setAvailability(tenantId, locationId, variantId, available, reasonCode, parseActorId(actorSubject));

        if (Boolean.valueOf(available).equals(before.binaryAvailable())) {
            return false;
        }

        audit.record(AuditFact.of("inventory.availability.set", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, before.brandId(), locationId))
                // The variant, not the internal stock_items row: "what changed"
                // has to be the catalog item an operator or an auditor would
                // actually search for.
                .target("Variant", variantId)
                .because(reasonCode)
                .usingCapability(Capability.INVENTORY_ADJUST.code())
                .changed(Map.of(
                        "available",
                        available,
                        "stockItemId",
                        before.stockItemId().toString()))
                .correlatedBy(variantId.toString())
                .occurredAt(clock.instant())
                .build());
        return true;
    }

    /**
     * The Keycloak subject as the {@code UUID} {@link #setAvailability}'s own
     * actor column expects — see {@code InventoryController.actorId()}'s
     * identical parse and its doc comment on why this deployment's subjects
     * are UUIDs. A subject that fails to parse is recorded as no actor at all
     * rather than refused outright: the audit fact above still names the real
     * subject string regardless, so nothing about "who" is lost even when the
     * ledger's own UUID column cannot hold it.
     */
    private static @Nullable UUID parseActorId(String actorSubject) {
        try {
            return UUID.fromString(actorSubject);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * Holds a cart's items against a quote.
     *
     * <p>Availability is checked and the hold taken in one transaction, so a dish
     * marked sold out between the check and the hold cannot slip through.
     *
     * <p>The expiry actually stored is never earlier than {@code
     * quoteExpiresAt}: see {@link ReservationExpiry} for why a reservation
     * that expired before the quote it backs is how stock gets sold twice at
     * two different prices.
     *
     * @return a hold, or the reason it was refused
     */
    @Override
    @Transactional
    public ReservationResult reserveForQuote(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID quoteId,
            Instant quoteExpiresAt,
            Map<UUID, Integer> quantitiesByVariant) {
        rls.bindTenant(tenantId);

        // ADR 0024 forbids a historical import from changing inventory, and this
        // is the movement it means: an order from 2021 holding stock a branch is
        // selling today.
        //
        // Refused, not skipped, and the two available no-ops are why. Returning a
        // hold would hand back a reservation id for nothing, and the commit that
        // follows would record a sale against stock that was never held; returning
        // a refusal would tell the import the dish was sold out and quarantine a
        // perfectly good order for a reason that is not true. Neither is a
        // truthful answer, so there is none — an import that reaches here has run
        // the live checkout path instead of importing a snapshot.
        ImportSuppression.refuse(ExternalEffect.INVENTORY_MOVEMENT, "reserve stock for a quote");

        AvailabilityDecision decision = evaluateAvailability(tenantId, locationId, quantitiesByVariant, null);
        if (!decision.available()) {
            return ReservationResult.refused(decision);
        }

        Instant now = clock.instant();
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = reservationExpiry(tenantId, brandId, locationId, now, quoteExpiresAt);

        boolean created = store.insertReservation(
                reservationId, tenantId, brandId, locationId, OWNER_QUOTE, quoteId, expiresAt, now);

        if (!created) {
            // A reservation row already exists for this quote. The uniqueness
            // constraint is on the owner regardless of status, so this row may be
            // a live hold, or one that was released, committed, or swept as
            // expired.
            var existing = store.findReservation(tenantId, OWNER_QUOTE, quoteId)
                    .orElseThrow(() -> new IllegalStateException("Reservation vanished mid-transaction"));

            if (!"HELD".equals(existing.status()) || !existing.expiresAt().isAfter(now)) {
                // Reporting this as a hold was the original behaviour and it was
                // wrong in the way that matters: checkout would believe it held
                // stock, create an order, and the later commit would quietly
                // return false with nothing reserved. A lapsed hold is a refusal,
                // and the customer re-prices rather than being sold a promise
                // inventory never made.
                log.info("Reservation for quote {} is {} and cannot be reused", quoteId, existing.status());
                return ReservationResult.refused(AvailabilityDecision.blockedBy(quantitiesByVariant.keySet().stream()
                        .map(Unavailable::holdExpired)
                        .toList()));
            }

            // A live hold for this quote. Returning it rather than failing keeps a
            // retried checkout idempotent instead of leaving the customer unable
            // to proceed.
            return ReservationResult.held(existing.id(), existing.expiresAt());
        }

        Map<UUID, StockItemRow> items = store.findStockItems(tenantId, locationId, quantitiesByVariant.keySet());
        boolean quantityLogicOn = items.values().stream().anyMatch(item -> item.trackingMode() == TrackingMode.QUANTITY)
                && useStockLogicEnabled(tenantId);

        // Deterministic stock-item order, not map iteration order: ADR 0017's
        // own atomic reservation algorithm names this explicitly ("for a
        // deterministic, sorted set of stock-item IDs... lock positions in
        // stock-item order") as what keeps two concurrent reservations naming
        // an overlapping set of items from deadlocking against each other —
        // each targets the same row first, in the same order, so one simply
        // waits for the other's row lock rather than each holding one row the
        // other wants.
        List<Map.Entry<UUID, Integer>> sortedLines = quantitiesByVariant.entrySet().stream()
                .sorted(Comparator.comparing(entry -> {
                    StockItemRow item = items.get(entry.getKey());
                    if (item == null) {
                        // evaluateAvailability just confirmed every one of these
                        // variants has a stock item at this location, inside the
                        // same transaction; a null here means the two reads
                        // disagreed, which is a bug in the store or a genuine
                        // race, not an ordinary refusal to swallow.
                        throw new IllegalStateException(
                                "Stock item vanished mid-transaction for variant " + entry.getKey());
                    }
                    return item.stockItemId();
                }))
                .toList();

        for (Map.Entry<UUID, Integer> entry : sortedLines) {
            UUID variantId = entry.getKey();
            StockItemRow item = Objects.requireNonNull(
                    items.get(variantId), () -> "Stock item vanished mid-transaction for variant " + variantId);
            BigDecimal requested = BigDecimal.valueOf(entry.getValue());
            store.insertReservationLine(reservationId, tenantId, item.stockItemId(), requested);

            if (item.trackingMode() != TrackingMode.QUANTITY || !quantityLogicOn) {
                continue;
            }
            if (!store.tryReserveQuantity(tenantId, item.stockItemId(), requested, now)) {
                // Stock moved between evaluateAvailability's read above and this
                // atomic attempt -- the conditional UPDATE is the authoritative
                // gate, not that earlier snapshot (see tryReserveQuantity's own
                // doc). Roll back everything this call has done so far: the
                // reservation row, every line inserted above, and every earlier
                // sorted item's own successful reserved-quantity bump. ADR 0017:
                // "on any failed item, roll back the entire reservation."
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return ReservationResult.refused(
                        AvailabilityDecision.blockedBy(List.of(Unavailable.soldOut(variantId))));
            }
        }

        return ReservationResult.held(reservationId, expiresAt);
    }

    /**
     * The configured reservation TTL from {@code now}, floored so it never
     * expires before the quote this reservation is being taken for.
     *
     * <p>Deliberately not "resolve {@code pricing.quote_ttl_seconds} here too
     * and take the max of two resolutions" — see {@link ReservationExpiry}'s
     * own doc for why that would not actually guarantee the invariant it
     * looks like it guarantees. {@code quoteExpiresAt} is the one fact that
     * cannot drift: the caller already holds the specific quote it is
     * reserving stock for, and that quote's {@code expiresAt} was fixed the
     * instant the quote was priced.
     */
    private Instant reservationExpiry(
            UUID tenantId, UUID brandId, UUID locationId, Instant now, Instant quoteExpiresAt) {
        Integer seconds = configuration.value(
                InventoryConfigurationKeys.RESERVATION_TTL_SECONDS,
                ResourceScope.location(tenantId, brandId, locationId));
        Duration configuredTtl = Duration.ofSeconds(Objects.requireNonNull(
                seconds,
                "inventory.reservation_ttl_seconds declares a code default and never terminates on explicit null"));
        return ReservationExpiry.notBefore(now.plus(configuredTtl), quoteExpiresAt);
    }

    /**
     * Turns a hold into a committed sale when an order is confirmed.
     *
     * <p>For every QUANTITY line the reservation carries, this also reduces
     * both {@code on_hand_quantity} and {@code reserved_quantity} together and
     * writes a {@code SALE_COMMITMENT} movement (ADR 0017: "committing...
     * reduces both on-hand and reserved in one transaction") — the actual
     * stock movement a bare status flip on {@code inventory.reservations}
     * would not record anywhere.
     */
    @Override
    @Transactional
    public boolean commit(UUID tenantId, UUID quoteId) {
        rls.bindTenant(tenantId);
        // Unreachable while reserveForQuote refuses, and guarded anyway: false
        // here means "there was no hold to commit", which an import would read as
        // an ordinary lapsed reservation rather than as a suppression.
        ImportSuppression.refuse(ExternalEffect.INVENTORY_MOVEMENT, "commit a stock reservation");

        var reservation = store.findReservation(tenantId, OWNER_QUOTE, quoteId);
        if (reservation.isEmpty()) {
            return false;
        }
        boolean transitioned =
                store.transitionReservation(tenantId, reservation.get().id(), "COMMITTED", clock.instant());
        if (transitioned) {
            applyQuantityCommit(tenantId, reservation.get().id());
        }
        return transitioned;
    }

    private void applyQuantityCommit(UUID tenantId, UUID reservationId) {
        Instant now = clock.instant();
        for (QuantityReservationLineRow line : store.findQuantityReservationLines(tenantId, reservationId)) {
            store.commitQuantitySale(
                    tenantId,
                    line.stockItemId(),
                    line.quantity(),
                    "commit:%s:%s".formatted(reservationId, line.stockItemId()),
                    reservationId,
                    "SERVICE",
                    null,
                    now);
        }
    }

    /**
     * Frees a hold when a cart is abandoned or a checkout fails.
     *
     * <p>For every QUANTITY line, this also gives {@code reserved_quantity}
     * back — never {@code on_hand_quantity}, which a release/expiry never
     * touches (ADR 0017: "releasing/expiring reduces reserved only"). Only a
     * {@code HELD} reservation can reach this at all ({@link
     * JdbcInventoryStore#transitionReservation}'s own {@code WHERE status =
     * 'HELD'} predicate): a {@code COMMITTED} one is unreachable here, which is
     * this wave's conservative reading of ADR 0017's open cancellation-restock
     * input — a cancellation after commit is a different, undecided primitive
     * (gap map row 1.2c), not this method silently reopening a sale.
     */
    @Override
    @Transactional
    public boolean release(UUID tenantId, UUID quoteId) {
        rls.bindTenant(tenantId);
        ImportSuppression.refuse(ExternalEffect.INVENTORY_MOVEMENT, "release a stock reservation");

        var reservation = store.findReservation(tenantId, OWNER_QUOTE, quoteId);
        if (reservation.isEmpty()) {
            return false;
        }
        boolean transitioned =
                store.transitionReservation(tenantId, reservation.get().id(), "RELEASED", clock.instant());
        if (transitioned) {
            applyQuantityRelease(tenantId, reservation.get().id());
        }
        return transitioned;
    }

    private void applyQuantityRelease(UUID tenantId, UUID reservationId) {
        Instant now = clock.instant();
        for (QuantityReservationLineRow line : store.findQuantityReservationLines(tenantId, reservationId)) {
            store.releaseReservedQuantity(tenantId, line.stockItemId(), line.quantity(), now);
        }
    }

    /**
     * Sweeps abandoned holds so they stop reserving stock.
     *
     * <p>Genuinely cross-tenant by design, not by omission: one UPDATE reaches
     * every tenant's expired holds in a single round trip. ADR 0056 makes that
     * an explicit choice rather than a forgotten predicate — {@link
     * TenantRlsSession#bindPlatform()} assumes {@code horecaos_platform_bypass}
     * for the rest of this transaction so the sweep keeps seeing every tenant
     * once {@code inventory.reservations} enforces row-level security (V0162),
     * exactly as it did before that migration.
     */
    @Transactional
    public int expireStaleReservations() {
        rls.bindPlatform();
        List<UUID> expired = store.expireReservations(clock.instant());
        if (!expired.isEmpty()) {
            // Same "reserved only, never on-hand" rule as an ordinary release
            // (see that method's own doc) — an expired hold is exactly a
            // release the customer never asked for, not a different movement.
            // Cross-tenant like the sweep itself: the platform-bypass session
            // bound above stays in effect for the rest of this transaction.
            Instant now = clock.instant();
            for (QuantityReservationLineRow line : store.findQuantityReservationLinesForReservations(expired)) {
                store.releaseReservedQuantity(line.tenantId(), line.stockItemId(), line.quantity(), now);
            }
            log.debug("Expired {} stale reservations", expired.size());
        }
        return expired.size();
    }

    /**
     * The {@code catalog.use_stock_logic} state that {@link
     * #evaluateAvailability} gates every {@link TrackingMode#QUANTITY} item's
     * enforcement on (gap map row {@code 4.4d}): off, a QUANTITY item behaves
     * like UNTRACKED; on, it is enforced for real.
     */
    private boolean useStockLogicEnabled(UUID tenantId) {
        Boolean enabled =
                configuration.value(InventoryConfigurationKeys.CATALOG_USE_STOCK_LOGIC, ResourceScope.tenant(tenantId));
        return Boolean.TRUE.equals(enabled);
    }

    // ======================================================================
    // QUANTITY: operator writes (gap map row 4.4c) — on-hand, the daily
    // default, and per-channel-type stop thresholds. Each mirrors {@link
    // #setAvailabilityAudited}'s own shape: a movement or config write, then
    // an ADR 0027 audit fact, with the same no-op-on-no-change rule.
    // ======================================================================

    /**
     * An operator's manual on-hand count for a QUANTITY item — a physical
     * recount, a delivery received, breakage found — recorded as a {@code
     * CORRECTION} movement and audited (ADR 0017's own API list: {@code POST
     * .../inventory/{variantId}/adjustments}).
     *
     * <p>Never refuses for going below {@code reserved_quantity}: on-hand is
     * a fact about physical stock an operator is reporting, and the
     * reservation path — not this write — is what keeps {@code reserved}
     * from ever exceeding it going forward (ADR 0017's "never go negative:
     * refuse" applies to a new hold, not to a correction that discovers less
     * stock than was believed reserved).
     *
     * @return whether on-hand actually changed
     * @throws IllegalArgumentException if the variant is not stocked at this location
     * @throws IllegalStateException if the variant is not QUANTITY-tracked
     */
    @Transactional
    public boolean setOnHandQuantity(
            UUID tenantId,
            UUID locationId,
            UUID variantId,
            BigDecimal newOnHand,
            String reasonCode,
            String actorSubject) {
        rls.bindTenant(tenantId);
        if (newOnHand.signum() < 0) {
            throw new IllegalArgumentException("An on-hand quantity cannot be negative");
        }
        StockItemRow item = requireQuantityItem(tenantId, locationId, variantId);

        if (newOnHand.compareTo(item.onHandQuantity()) == 0) {
            log.debug("Variant {} at location {} is already at on-hand {}", variantId, locationId, newOnHand);
            return false;
        }

        BigDecimal delta = newOnHand.subtract(item.onHandQuantity());
        String idempotencyKey =
                "onhand:%s:%s:%d".formatted(variantId, newOnHand.toPlainString(), item.positionSequence());
        Instant now = clock.instant();
        store.recordOnHandCorrection(
                tenantId,
                item.stockItemId(),
                newOnHand,
                delta,
                idempotencyKey,
                reasonCode,
                "USER",
                parseActorId(actorSubject),
                now);

        audit.record(AuditFact.of("inventory.on_hand.set", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, item.brandId(), locationId))
                .target("Variant", variantId)
                .because(reasonCode)
                .usingCapability(Capability.INVENTORY_ADJUST.code())
                .changed(Map.of(
                        "onHandQuantity",
                        newOnHand.toPlainString(),
                        "stockItemId",
                        item.stockItemId().toString()))
                .correlatedBy(variantId.toString())
                .occurredAt(now)
                .build());
        return true;
    }

    /**
     * The QUANTITY item's daily reset target (gap map row 4.4c) — null
     * disables the scheduled reset for this item, leaving on-hand exactly
     * where an operator last set it.
     *
     * @return whether the default actually changed
     * @throws IllegalArgumentException if the variant is not stocked at this location
     * @throws IllegalStateException if the variant is not QUANTITY-tracked
     */
    @Transactional
    public boolean setDefaultQuantity(
            UUID tenantId,
            UUID locationId,
            UUID variantId,
            @Nullable BigDecimal defaultQuantity,
            String reasonCode,
            String actorSubject) {
        rls.bindTenant(tenantId);
        if (defaultQuantity != null && defaultQuantity.signum() < 0) {
            throw new IllegalArgumentException("A default quantity cannot be negative");
        }
        StockItemRow item = requireQuantityItem(tenantId, locationId, variantId);

        boolean unchanged = defaultQuantity == null
                ? item.defaultQuantity() == null
                : defaultQuantity.equals(item.defaultQuantity())
                        || (item.defaultQuantity() != null && defaultQuantity.compareTo(item.defaultQuantity()) == 0);
        if (unchanged) {
            return false;
        }

        Instant now = clock.instant();
        store.setDefaultQuantity(tenantId, item.stockItemId(), defaultQuantity, now);

        audit.record(AuditFact.of("inventory.default_quantity.set", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, item.brandId(), locationId))
                .target("Variant", variantId)
                .because(reasonCode)
                .usingCapability(Capability.INVENTORY_ADJUST.code())
                .changed(Map.of(
                        "defaultQuantity",
                        defaultQuantity == null ? "null" : defaultQuantity.toPlainString(),
                        "stockItemId",
                        item.stockItemId().toString()))
                .correlatedBy(variantId.toString())
                .occurredAt(now)
                .build());
        return true;
    }

    /**
     * Names the remaining quantity at which one channel type stops selling
     * this QUANTITY item early (gap map row 4.4c), e.g. an {@code AGGREGATOR}
     * channel stopping at 3 while the storefront keeps selling to zero.
     *
     * @throws IllegalArgumentException if the variant is not stocked at this location
     * @throws IllegalStateException if the variant is not QUANTITY-tracked
     */
    @Transactional
    public void setChannelStopThreshold(
            UUID tenantId,
            UUID locationId,
            UUID variantId,
            String channelSystemType,
            BigDecimal stopAtOrBelow,
            String reasonCode,
            String actorSubject) {
        rls.bindTenant(tenantId);
        if (stopAtOrBelow.signum() < 0) {
            throw new IllegalArgumentException("A stop threshold cannot be negative");
        }
        StockItemRow item = requireQuantityItem(tenantId, locationId, variantId);
        Instant now = clock.instant();
        store.upsertChannelStopThreshold(
                tenantId, item.brandId(), locationId, item.stockItemId(), channelSystemType, stopAtOrBelow, now);

        audit.record(AuditFact.of("inventory.channel_stop_threshold.set", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, item.brandId(), locationId))
                .target("Variant", variantId)
                .because(reasonCode)
                .usingCapability(Capability.INVENTORY_ADJUST.code())
                .changed(Map.of(
                        "channelSystemType",
                        channelSystemType,
                        "stopAtOrBelow",
                        stopAtOrBelow.toPlainString(),
                        "stockItemId",
                        item.stockItemId().toString()))
                .correlatedBy(variantId.toString())
                .occurredAt(now)
                .build());
    }

    /**
     * Removes a channel type's stop threshold, so that channel goes back to
     * selling to zero like every channel with no threshold at all.
     *
     * @return whether a threshold existed and was removed
     * @throws IllegalArgumentException if the variant is not stocked at this location
     * @throws IllegalStateException if the variant is not QUANTITY-tracked
     */
    @Transactional
    public boolean clearChannelStopThreshold(
            UUID tenantId,
            UUID locationId,
            UUID variantId,
            String channelSystemType,
            String reasonCode,
            String actorSubject) {
        rls.bindTenant(tenantId);
        StockItemRow item = requireQuantityItem(tenantId, locationId, variantId);
        boolean removed = store.deleteChannelStopThreshold(tenantId, item.stockItemId(), channelSystemType);
        if (!removed) {
            return false;
        }
        audit.record(AuditFact.of("inventory.channel_stop_threshold.clear", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, item.brandId(), locationId))
                .target("Variant", variantId)
                .because(reasonCode)
                .usingCapability(Capability.INVENTORY_ADJUST.code())
                .changed(Map.of(
                        "channelSystemType",
                        channelSystemType,
                        "stockItemId",
                        item.stockItemId().toString()))
                .correlatedBy(variantId.toString())
                .occurredAt(clock.instant())
                .build());
        return true;
    }

    private StockItemRow requireQuantityItem(UUID tenantId, UUID locationId, UUID variantId) {
        StockItemRow item = store.findStockItem(tenantId, locationId, variantId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Variant " + variantId + " is not stocked at this location"));
        if (item.trackingMode() != TrackingMode.QUANTITY) {
            throw new IllegalStateException(
                    "This write applies only to a QUANTITY item; this one is " + item.trackingMode());
        }
        return item;
    }

    // ======================================================================
    // QUANTITY: the console's per-location stock page read (gap map row 4.4c)
    // ======================================================================

    /**
     * One variant's own position at a location — the storefront's single-item
     * read, which has no reason to pull every other stock item at the
     * location the way the console's {@link #listStockPositions} page does.
     */
    @Transactional(readOnly = true)
    public Optional<StockPositionView> findStockPosition(UUID tenantId, UUID locationId, UUID variantId) {
        rls.bindTenant(tenantId);
        return store.findStockItem(tenantId, locationId, variantId).map(item -> toView(item, variantId, List.of()));
    }

    /** Every stock item listed at a location, with its position and any channel stop thresholds. */
    @Transactional(readOnly = true)
    public List<StockPositionView> listStockPositions(UUID tenantId, UUID locationId) {
        rls.bindTenant(tenantId);
        List<StockItemListingRow> items = store.listStockItems(tenantId, locationId);
        Map<UUID, List<ChannelStopThresholdView>> thresholdsByItem = new java.util.HashMap<>();
        for (ChannelStopThresholdRow row : store.listChannelStopThresholds(tenantId, locationId)) {
            thresholdsByItem
                    .computeIfAbsent(row.stockItemId(), key -> new ArrayList<>())
                    .add(new ChannelStopThresholdView(row.channelSystemType(), row.stopAtOrBelow()));
        }
        return items.stream()
                .map(row -> toView(
                        row.stockItem(),
                        row.variantId(),
                        thresholdsByItem.getOrDefault(row.stockItem().stockItemId(), List.of())))
                .toList();
    }

    private static StockPositionView toView(
            StockItemRow item, UUID variantId, List<ChannelStopThresholdView> thresholds) {
        return new StockPositionView(
                item.stockItemId(),
                variantId,
                item.trackingMode(),
                item.binaryAvailable(),
                item.onHandQuantity(),
                item.reservedQuantity(),
                item.trackingMode() == TrackingMode.QUANTITY ? item.remainingQuantity() : null,
                item.defaultQuantity(),
                item.lastResetBusinessDate(),
                thresholds);
    }

    public record StockPositionView(
            UUID stockItemId,
            UUID variantId,
            TrackingMode trackingMode,
            @Nullable Boolean binaryAvailable,
            BigDecimal onHandQuantity,
            BigDecimal reservedQuantity,
            @Nullable BigDecimal remainingQuantity,
            @Nullable BigDecimal defaultQuantity,
            @Nullable LocalDate lastResetBusinessDate,
            List<ChannelStopThresholdView> channelStopThresholds) {}

    public record ChannelStopThresholdView(String channelSystemType, BigDecimal stopAtOrBelow) {}

    // ======================================================================
    // QUANTITY: the daily default/auto-reset job (gap map row 4.4c). Called
    // by InventoryQuantityResetScheduler, never directly from a controller.
    // ======================================================================

    /**
     * Every tenant with at least one active QUANTITY item that has a daily
     * default configured — the scheduler's own per-tick worklist, read under
     * the platform-bypass role the same way {@link #expireStaleReservations}
     * reads its own cross-tenant worklist.
     */
    @Transactional(readOnly = true)
    public List<UUID> tenantsWithQuantityDefaults() {
        rls.bindPlatform();
        return store.tenantIdsWithQuantityDefaults();
    }

    /**
     * Resets every one tenant's QUANTITY items that are due for their daily
     * reset, on that tenant's own business day (ADR 0043) as of {@code now}.
     *
     * @return how many items were actually reset
     */
    @Transactional
    public int resetDueQuantityItems(UUID tenantId, Instant now) {
        rls.bindTenant(tenantId);
        LocalDate businessDate = businessDays.businessDateOf(tenantId, now);
        int resetCount = 0;
        for (UUID stockItemId : store.dueQuantityResetStockItems(tenantId, businessDate)) {
            if (store.resetIfDue(tenantId, stockItemId, businessDate, now)) {
                resetCount++;
            }
        }
        return resetCount;
    }
}
