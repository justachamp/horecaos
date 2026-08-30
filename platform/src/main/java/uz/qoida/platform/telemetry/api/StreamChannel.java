package uz.qoida.platform.telemetry.api;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;

/**
 * The code-owned catalogue of streaming channels (ADR 0045).
 *
 * <p>The same shape as ADR 0033's cache registry and ADR 0032's event catalogue,
 * and for the same reason: a channel that exists only as a string a client
 * happened to send is a channel with no declared authorization, no declared
 * payload class, and no declared cost. An unregistered name is a {@code 400}, and
 * a registered one that names no capability fails startup — which is why
 * {@link #capability()} is non-null by construction rather than by convention.
 *
 * <p>Every channel declares five things, and each of them is a decision somebody
 * has to be able to disagree with:
 *
 * <ul>
 * <li>the <strong>scope types</strong> a subscription may be taken at, which is
 *     also the scope the capability is required at;
 * <li>the <strong>capability</strong>, which is checked at connect and again
 *     whenever grants change, never once at connect and then forgotten;
 * <li>the <strong>frame class</strong> — a signal says "something in your scope
 *     changed, re-read it", a snapshot carries a bounded payload inline and is a
 *     registered exception with a declared classification;
 * <li>the <strong>source</strong>, so a reader can find what produces it;
 * <li>the <strong>cadence cap</strong>, which is not an optimisation: a bulk
 *     assignment of forty orders emits forty domain events, and uncoalesced that
 *     is forty frames and forty authenticated re-reads per connected operator,
 *     against a primary that has no read replica.
 * </ul>
 *
 * <p>There is deliberately no customer channel. Customers poll a token-scoped
 * tracking endpoint and see ADR 0019 milestones; the streaming fleet is bounded
 * by staff count, which is the single property that makes this transport
 * affordable on ADR 0034's one machine.
 */
public enum StreamChannel {

    /** New and changed orders on a branch's queue. The highest-value channel. */
    ORDER_QUEUE(EnumSet.of(ScopeType.LOCATION), Capability.ORDER_READ,
            FrameClass.SIGNAL, "ordering.events", Duration.ofMillis(250)),

    /**
     * One order's detail. Subscribed with a resource filter, because an operator
     * watching a single order does not want the branch's whole queue.
     */
    ORDER_DETAIL(EnumSet.of(ScopeType.LOCATION), Capability.ORDER_READ,
            FrameClass.SIGNAL, "ordering.events", Duration.ofMillis(250)),

    /** The dispatcher board: in-house and partner shipments in one list. */
    DISPATCH_BOARD(EnumSet.of(ScopeType.LOCATION), Capability.DELIVERY_PLAN_READ,
            FrameClass.SIGNAL, "ordering + fulfillment", Duration.ofMillis(250)),

    /** An item went on stop in the kitchen and an operator is still selling it. */
    STOP_LIST(EnumSet.of(ScopeType.LOCATION), Capability.CATALOG_READ,
            FrameClass.SIGNAL, "catalog.events", Duration.ofMillis(250)),

    /** Inbox and provider failures, which span a tenant's branches. */
    INTEGRATION_ALERTS(EnumSet.of(ScopeType.TENANT), Capability.INTEGRATION_FAILURE_READ,
            FrameClass.SIGNAL, "integration.failures", Duration.ofSeconds(1)),

    /**
     * The wall-board's integers, carried inline. A signal saying "a number
     * changed" followed by a fetch is two round trips for one integer.
     */
    COUNTERS(EnumSet.of(ScopeType.LOCATION, ScopeType.BRAND), Capability.ORDER_READ,
            FrameClass.SNAPSHOT, "derived, recomputed", Duration.ofSeconds(2)),

    /**
     * The live positions of a branch's on-duty couriers, carried inline because a
     * signal per courier per tick would produce N fetches per tick for N
     * couriers.
     *
     * <p>The payload is an authorized HTTP response under ADR 0025 and never a
     * Kafka payload, which is the only reason it may carry values ADR 0032
     * forbids on a topic. The {@code realtime.signals} record that triggers it
     * carries a courier id, a time, and a scope key, and nothing else.
     */
    COURIER_POSITIONS(EnumSet.of(ScopeType.LOCATION), Capability.COURIER_POSITION_READ,
            FrameClass.SNAPSHOT, "realtime.signals", Duration.ofSeconds(5));

    /** What a frame carries. */
    public enum FrameClass {

        /** An identifier and a version. The client re-reads through the ordinary API. */
        SIGNAL,

        /**
         * A bounded payload inline. A registered exception, not a general rule:
         * every snapshot channel duplicates a read model onto a second contract
         * that has to be versioned, tested, and classification-checked
         * separately, and re-authorizes nothing on its own.
         */
        SNAPSHOT
    }

    private static final Map<String, StreamChannel> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    channel -> channel.name().toLowerCase(Locale.ROOT), Function.identity()));

    private final Set<ScopeType> scopeTypes;
    private final Capability capability;
    private final FrameClass frameClass;
    private final String source;
    private final Duration cadenceCap;

    StreamChannel(Set<ScopeType> scopeTypes, Capability capability, FrameClass frameClass,
            String source, Duration cadenceCap) {

        // A channel with no capability is an unauthorized broadcast wearing a
        // registry entry, and it must not be possible to ship one. Enum
        // construction is the earliest point that can refuse it; the startup
        // check and its test exist because a reviewer should not have to know
        // that a constructor argument is load-bearing.
        if (capability == null) {
            throw new IllegalStateException(
                    "Channel " + name() + " declares no capability (ADR 0045)");
        }
        if (scopeTypes.isEmpty()) {
            throw new IllegalStateException("Channel " + name() + " declares no scope type");
        }
        if (cadenceCap.isNegative() || cadenceCap.isZero()) {
            throw new IllegalStateException("Channel " + name() + " declares no cadence cap");
        }
        this.scopeTypes = Set.copyOf(scopeTypes);
        this.capability = capability;
        this.frameClass = frameClass;
        this.source = source;
        this.cadenceCap = cadenceCap;
    }

    public Set<ScopeType> scopeTypes() {
        return scopeTypes;
    }

    public boolean isSubscribableAt(ScopeType scopeType) {
        return scopeTypes.contains(scopeType);
    }

    public Capability capability() {
        return capability;
    }

    public FrameClass frameClass() {
        return frameClass;
    }

    public String source() {
        return source;
    }

    public Duration cadenceCap() {
        return cadenceCap;
    }

    /** The wire name, which is the lower-case enum name. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<StreamChannel> find(String code) {
        return code == null
                ? Optional.empty()
                : Optional.ofNullable(BY_CODE.get(code.strip().toLowerCase(Locale.ROOT)));
    }

    /** Thrown when a client subscribes to a name this registry does not declare. */
    public static final class UnknownChannelException extends IllegalArgumentException {
        public UnknownChannelException(String message) {
            super(message);
        }
    }

    public static StreamChannel require(String code) {
        return find(code).orElseThrow(() -> new UnknownChannelException(
                "Unknown stream channel \"%s\". Declare it in StreamChannel (ADR 0045)."
                        .formatted(code)));
    }
}
