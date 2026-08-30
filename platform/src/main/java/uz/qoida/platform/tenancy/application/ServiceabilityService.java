package uz.qoida.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.tenancy.api.FulfillmentMode;
import uz.qoida.platform.tenancy.api.LocationCapacityPort;
import uz.qoida.platform.tenancy.api.Serviceability;
import uz.qoida.platform.tenancy.api.ServiceabilityReason;
import uz.qoida.platform.tenancy.api.ServiceabilityResolver;
import uz.qoida.platform.tenancy.domain.channel.PreparationPromise;
import uz.qoida.platform.tenancy.domain.channel.ServiceMode;
import uz.qoida.platform.tenancy.domain.channel.WeeklySchedule;
import uz.qoida.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;

/**
 * The eight-rule serviceability resolver (ADR 0036).
 *
 * <p>Rules run in a fixed order so the reason returned is the most fundamental
 * one rather than whichever check happened to run last. "This channel does not
 * sell here" and "we are shut" send an operator to two different screens, and
 * answering with the second when the first is true costs an afternoon.
 *
 * <pre>
 * 1. Channel active for the tenant           -&gt; CHANNEL_NOT_ENABLED
 * 2. Channel enabled at this location        -&gt; CHANNEL_NOT_ENABLED
 * 3. Fulfilment mode enabled on the channel  -&gt; FULFILMENT_MODE_UNAVAILABLE
 * 4. Service state not FORCE_CLOSED          -&gt; MANUALLY_CLOSED
 * 5. No dated exception closing today        -&gt; CLOSED_BY_EXCEPTION
 * 6. Inside a weekly window for this mode    -&gt; OUTSIDE_SERVICE_HOURS
 * 7. Live publication for brand + channel    -&gt; NO_LIVE_MENU
 * 8. Concurrent orders below the cap         -&gt; AT_CAPACITY
 * </pre>
 *
 * <p>{@code FORCE_OPEN} skips rules 5 and 6 and nothing else: a manager
 * overriding hours does not thereby override an entitlement, an empty menu, or
 * the kitchen ceiling.
 */
@Service
public class ServiceabilityService implements ServiceabilityResolver, LocationCapacityPort {

    private final JdbcServiceabilityStore store;
    private final Clock clock;

    public ServiceabilityService(JdbcServiceabilityStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Serviceability resolve(UUID tenantId, UUID brandId, UUID locationId,
            UUID channelId, FulfillmentMode mode, Instant at) {

        ZoneId zone = store.timezoneOf(tenantId, locationId)
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "No location %s for this tenant".formatted(locationId)));

        // Rules 1 and 2. A channel that does not exist for this tenant answers the
        // same way as one that is archived: not enabled. Both are "you cannot order
        // through this route", and inventing a third answer would only give the
        // storefront a case it has no wording for.
        var channel = store.channelAtLocation(tenantId, channelId, locationId);
        if (!channel.exists() || !channel.active() || !channel.enabledAtLocation()) {
            return Serviceability.refused(ServiceabilityReason.CHANNEL_NOT_ENABLED, null, false);
        }

        // Rule 3.
        if (!store.fulfillmentModeEnabled(tenantId, channelId, mode)) {
            return Serviceability.refused(
                    ServiceabilityReason.FULFILMENT_MODE_UNAVAILABLE, null, false);
        }

        Optional<JdbcServiceabilityStore.BoundSchedule> bound = store.scheduleFor(tenantId, locationId, mode);
        boolean acceptsScheduledOrders = bound
                .map(schedule -> schedule.schedule().acceptsScheduledOrders())
                .orElse(false);

        // Local wall-clock, resolved through the location's IANA zone rather than a
        // hardcoded +05:00. Uzbekistan has observed no daylight saving since 1995,
        // so this never changes an answer here — and the first tenant outside
        // Uzbekistan does not inherit a silent one-hour error at every boundary.
        LocalDateTime local = at.atZone(zone).toLocalDateTime();

        var state = store.serviceState(tenantId, locationId);
        ServiceMode serviceMode = state.effectiveMode(at);

        // Rule 4.
        if (serviceMode == ServiceMode.FORCE_CLOSED) {
            return Serviceability.refused(ServiceabilityReason.MANUALLY_CLOSED,
                    reopeningInstant(state.effectiveUntil(), bound, zone),
                    acceptsScheduledOrders);
        }

        if (serviceMode != ServiceMode.FORCE_OPEN) {
            // Rule 5. A dated exception beats the weekly rule; FORCE_CLOSED already
            // beat both above.
            if (bound.isPresent()
                    && bound.get().schedule().closedByExceptionOn(local.toLocalDate())) {
                return Serviceability.refused(ServiceabilityReason.CLOSED_BY_EXCEPTION,
                        nextOpening(bound.get().schedule(), local, zone).orElse(null),
                        acceptsScheduledOrders);
            }

            // Rule 6. A location with no binding at all is closed for that mode:
            // defaulting an unbound mode to "always open" would let a branch that
            // has never configured delivery hours take delivery orders at 04:00.
            if (bound.isEmpty() || !bound.get().schedule().isOpenAt(local)) {
                return Serviceability.refused(ServiceabilityReason.OUTSIDE_SERVICE_HOURS,
                        bound.map(schedule -> nextOpening(schedule.schedule(), local, zone).orElse(null))
                                .orElse(null),
                        acceptsScheduledOrders);
            }
        }

        // Rule 7. Read by channel code, which the ADR 0016 correction in V0020 makes
        // a reference to a registered channel rather than free text.
        if (!store.hasLivePublication(tenantId, brandId, channel.channelCode())) {
            return Serviceability.refused(ServiceabilityReason.NO_LIVE_MENU, null,
                    acceptsScheduledOrders);
        }

        // Rule 8, advisory here. The authoritative decision is claimCapacity below,
        // taken inside the checkout transaction — this read is a number that was
        // true a moment ago, which is fine for browse and never enough to commit on.
        if (state.maxConcurrentOrders() != null
                && store.openCapacityHolds(tenantId, locationId) >= state.maxConcurrentOrders()) {
            return Serviceability.refused(ServiceabilityReason.AT_CAPACITY, null,
                    acceptsScheduledOrders);
        }

        return Serviceability.available(acceptsScheduledOrders,
                store.preparationMinutes(tenantId, locationId, mode,
                        local.getDayOfWeek().getValue(), local.toLocalTime()).orElse(null));
    }

    /**
     * The promised preparation time: the band, then the longest line override.
     *
     * <p>The order is fixed and the winner is the longest, because a pizza that
     * takes 40 minutes does not become 20 because the quiet-hours band says so.
     * Line overrides come from {@code catalog.location_offerings} and are passed in
     * rather than read here, so tenancy does not reach into the catalog.
     */
    @Transactional(readOnly = true)
    public Integer preparationMinutes(UUID tenantId, UUID locationId, FulfillmentMode mode,
            Instant at, Collection<Integer> lineOverrideMinutes) {
        ZoneId zone = store.timezoneOf(tenantId, locationId)
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "No location %s for this tenant".formatted(locationId)));
        LocalDateTime local = at.atZone(zone).toLocalDateTime();
        Integer band = store.preparationMinutes(tenantId, locationId, mode,
                local.getDayOfWeek().getValue(), local.toLocalTime()).orElse(null);
        return PreparationPromise.minutes(band, lineOverrideMinutes);
    }

    /**
     * Claims one concurrent-order slot, or refuses.
     *
     * <p>{@link Propagation#MANDATORY}: this must run inside the caller's checkout
     * transaction and never open one of its own. A slot claimed in a transaction of
     * its own would survive a checkout that then rolled back, and the kitchen would
     * report itself full of orders that were never placed.
     *
     * <p>Two customers racing for the last slot are settled by the row lock the
     * store takes, not by a number either read a second earlier.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CapacityOutcome claimCapacity(UUID tenantId, UUID brandId, UUID locationId, UUID holdId) {
        if (store.holdsCapacity(holdId, tenantId)) {
            // A retried checkout re-claims the slot it already holds rather than
            // consuming a second and reporting the kitchen busier than it is.
            return CapacityOutcome.CLAIMED;
        }
        Optional<Integer> ceiling = store.lockCapacityCeiling(tenantId, locationId);
        if (ceiling.isPresent() && store.openCapacityHolds(tenantId, locationId) >= ceiling.get()) {
            return CapacityOutcome.AT_CAPACITY;
        }
        store.claimCapacity(holdId, tenantId, brandId, locationId, clock.instant());
        return CapacityOutcome.CLAIMED;
    }

    @Override
    @Transactional
    public boolean releaseCapacity(UUID tenantId, UUID holdId) {
        return store.releaseCapacity(holdId, tenantId, clock.instant());
    }

    /**
     * When a manually closed branch reopens.
     *
     * <p>An expiry alone is not the answer: a branch closed until 20:00 whose
     * schedule shuts at 19:00 does not reopen at 20:00. The later of the two is the
     * first moment both the override and the timetable agree.
     */
    private Instant reopeningInstant(Instant effectiveUntil,
            Optional<JdbcServiceabilityStore.BoundSchedule> bound, ZoneId zone) {

        if (effectiveUntil == null) {
            // "Until I reopen it" has no computable next-available instant, and
            // guessing one would be worse than none: the storefront would promise a
            // time the manager never agreed to.
            return null;
        }
        if (bound.isEmpty()) {
            return effectiveUntil;
        }
        WeeklySchedule schedule = bound.get().schedule();
        LocalDateTime expiry = effectiveUntil.atZone(zone).toLocalDateTime();

        // Inside opening hours at the moment the override lapses, so the override
        // is the only thing holding the branch shut and its expiry is the answer.
        if (schedule.isOpenAt(expiry)) {
            return effectiveUntil;
        }
        // Otherwise the branch is also outside its hours then — a close until 20:00
        // on a branch that shuts at 19:00 does not reopen at 20:00 — so the answer
        // is the first opening at or after the expiry.
        return nextOpening(schedule, expiry, zone).orElse(effectiveUntil);
    }

    private Optional<Instant> nextOpening(WeeklySchedule schedule, LocalDateTime from, ZoneId zone) {
        return schedule.nextOpeningAtOrAfter(from).map(opening -> opening.atZone(zone).toInstant());
    }
}
