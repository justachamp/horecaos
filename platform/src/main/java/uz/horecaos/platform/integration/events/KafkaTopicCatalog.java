package uz.horecaos.platform.integration.events;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The deliberately provisioned Kafka topics required by ADR 0032.
 *
 * <p>This is intentionally separate from the event catalogue: many event
 * contracts share one topic, while the broker needs one set of physical
 * properties for that topic. Keeping those properties here gives deployment,
 * tests, and documentation one code-owned answer rather than three lists that
 * can drift.
 */
public final class KafkaTopicCatalog {

    /** Business facts are retained for the seven-day replay window in ADR 0006. */
    private static final Duration BUSINESS_FACT_RETENTION = Duration.ofDays(7);

    /** Commands are durable in PostgreSQL; Kafka only has to survive a restart. */
    private static final Duration COMMAND_RETENTION = Duration.ofDays(1);

    /** Signals are live hints, never replayable state (ADR 0045). */
    private static final Duration SIGNAL_RETENTION = Duration.ofMinutes(1);

    public static final String TENANCY_EVENTS = "tenancy.events";
    public static final String ORDERING_EVENTS = "ordering.events";
    public static final String MEDIA_EVENTS = "media.events";
    public static final String FULFILLMENT_COMMANDS = "fulfillment.commands";
    public static final String FULFILLMENT_EVENTS = "fulfillment.events";
    public static final String REALTIME_SIGNALS = "realtime.signals";
    public static final String VOICE_EVENTS = "voice.events";
    public static final String INVENTORY_EVENTS = "inventory.events";
    public static final String PRICING_EVENTS = "pricing.events";

    /** ADR 0112: scenario decisions, a guest's run ending, and an offer being published. */
    public static final String MARKETING_EVENTS = "marketing.events";

    /**
     * ADR 0157: an item of brand-authored storefront content was published or withdrawn. Ids, kinds,
     * placements and instants only -- never text, never a URL.
     */
    public static final String CONTENT_EVENTS = "content.events";

    /**
     * ADR 0016 and ADR 0138: a brand's draft menu changed -- a product, a variant, a category, a classification,
     * a channel's images. Ids, entity kinds and change codes only: never a name, a price or a URL.
     */
    public static final String CATALOG_EVENTS = "catalog.events";

    /** ADR 0111 lead facts: a lead was registered, moved, handed to a branch or converted. */
    public static final String CUSTOMERS_EVENTS = "customers.events";

    /**
     * ADR 0159 review facts: a review was submitted, a comment moderated, a service-recovery case opened,
     * moved or passed a clock.
     */
    public static final String REVIEWS_EVENTS = "reviews.events";

    /**
     * ADR 0166 fiscal facts: a correction or a void of an issued receipt was requested, and the agent's
     * reference attached. Identifiers, the kind and the status only -- never a piece of receipt evidence.
     */
    public static final String FISCAL_EVENTS = "fiscal.events";

    /** ADR 0040 marketplace facts: a partner confirmed a new availability, a channel went stale. */
    public static final String INTEGRATION_EVENTS = "integration.events";

    /** ADR 0012's durable scheduler command. Durable state lives in PostgreSQL; Kafka only has to survive a restart. */
    public static final String POS_COMMANDS = "pos.commands";

    private static final Map<String, TopicSpecification> TOPICS = index(List.of(
            // The production topology has one broker (ADR 0034), so replication
            // factor one is explicit rather than an accidental broker default.
            new TopicSpecification(TENANCY_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            new TopicSpecification(ORDERING_EVENTS, 12, (short) 1, BUSINESS_FACT_RETENTION),
            new TopicSpecification(MEDIA_EVENTS, 6, (short) 1, BUSINESS_FACT_RETENTION),
            new TopicSpecification(FULFILLMENT_COMMANDS, 3, (short) 1, COMMAND_RETENTION),
            new TopicSpecification(FULFILLMENT_EVENTS, 6, (short) 1, BUSINESS_FACT_RETENTION),
            new TopicSpecification(REALTIME_SIGNALS, 3, (short) 1, SIGNAL_RETENTION),
            // ADR 0064. A pilot tenant's call volume is nowhere near ordering's,
            // so this shares ordering's partition-count reasoning at a smaller
            // scale rather than fulfillment's.
            new TopicSpecification(VOICE_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0017. One event type published so far (an 86 toggle), so this
            // shares media's partition count rather than ordering's — a stock-out
            // is rare next to an order, and over-partitioning a light topic buys
            // nothing but idle broker bookkeeping.
            new TopicSpecification(INVENTORY_EVENTS, 6, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0018. One event type published so far (a price book going
            // live), and activating a book is an operator decision made a
            // handful of times a day at most — the same cardinality tenancy's
            // control-plane creations have, so this shares tenancy's partition
            // count rather than a per-order topic's.
            new TopicSpecification(PRICING_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0112. A scenario decision is one record per guest per step, so the
            // volume is a campaign's audience times its steps and keyed by campaign,
            // which keeps one scenario's facts in order and a poison record holding
            // back that scenario alone. Shares tenancy's partition count: a pilot's
            // audiences are hundreds, not hundreds of thousands.
            new TopicSpecification(MARKETING_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0157. A brand publishes a handful of items a week, so this is the lightest
            // business-fact topic there is and shares tenancy's partition count; keyed by item, so
            // one item's facts stay in order. Nothing on it is text.
            new TopicSpecification(CONTENT_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0016. One record per distinct entity and change a write touches (identical facts in one
            // transaction are one; a bulk classification of N dishes is N plus one for the batch, ADR 0180),
            // keyed by brand so one brand's changes stay in order, and an import is suppressed rather than
            // announced row by row; an operator editing a menu produces a handful a minute and a bulk write
            // is a burst on one brand's partition, so this shares tenancy's partition count.
            new TopicSpecification(CATALOG_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0040. A confirmed availability push is one record per dish per binding and
            // a brand-wide stop across a pilot's bound venues is hundreds, not thousands a
            // minute, so this shares tenancy's partition count: keyed by binding, so one
            // venue's facts stay in order and a poison record holds back that venue only.
            new TopicSpecification(INTEGRATION_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0111. A lead is a call-centre work item, so the volume is a pilot tenant's phone
            // calls and form fills -- the same cardinality tenancy's control-plane creations have,
            // keyed by lead so one lead's registration, hand-off and conversion stay in order.
            new TopicSpecification(CUSTOMERS_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0159. A review is one per completed order and a case is staff work on a low one, so the
            // volume is a small fraction of orders; keyed by order so one order's review, moderation and
            // case facts stay in order and a poison record holds back that order alone. Shares tenancy's
            // partition count, seven days, replication one (ADR 0034).
            new TopicSpecification(REVIEWS_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0166. A correction request is staff work against an issued receipt, a small fraction of
            // orders, keyed by the document so a request and its attachment stay in order. Shares
            // tenancy's partition count, seven days, replication one (ADR 0034).
            new TopicSpecification(FISCAL_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            // ADR 0012. One schedule row per binding and a pilot's binding count
            // is small, so this shares fulfillment.commands' shape rather than
            // ordering's: a command topic, not a fact topic, and durable state
            // lives in pos_sync_schedules/pos_sync_runs, not here.
            new TopicSpecification(POS_COMMANDS, 3, (short) 1, COMMAND_RETENTION)));

    private KafkaTopicCatalog() {}

    public static Collection<TopicSpecification> all() {
        return TOPICS.values();
    }

    public static Optional<TopicSpecification> find(String name) {
        return Optional.ofNullable(TOPICS.get(name));
    }

    public static TopicSpecification require(String name) {
        return find(name)
                .orElseThrow(() ->
                        new IllegalArgumentException("No ADR 0032 topic specification is registered for " + name));
    }

    private static Map<String, TopicSpecification> index(List<TopicSpecification> topics) {
        Map<String, TopicSpecification> byName = new LinkedHashMap<>();
        for (TopicSpecification topic : topics) {
            TopicSpecification previous = byName.put(topic.name(), topic);
            if (previous != null) {
                throw new IllegalStateException("Duplicate Kafka topic specification: " + topic.name());
            }
        }
        return Map.copyOf(byName);
    }

    /** One broker-level topic policy, including its destructive-retention boundary. */
    public record TopicSpecification(String name, int partitions, short replicationFactor, Duration retention) {

        public TopicSpecification {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Topic name is required");
            }
            if (partitions < 1) {
                throw new IllegalArgumentException("Topic needs at least one partition");
            }
            if (replicationFactor < 1) {
                throw new IllegalArgumentException("Topic needs at least one replica");
            }
            if (retention == null || retention.isNegative() || retention.isZero()) {
                throw new IllegalArgumentException("Topic retention must be positive");
            }
        }

        /** Every ADR 0032 topic expires records; compacted state belongs in PostgreSQL. */
        public String cleanupPolicy() {
            return "delete";
        }
    }
}
