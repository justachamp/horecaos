package uz.horecaos.platform.observability;

import java.util.Set;

/**
 * Which event domains carry money, and therefore which dead letters may not
 * wait until morning (ADR 0023).
 *
 * <p>The distinction is the whole of the dead-letter alert tiering. ADR 0006
 * dead-letters {@code PAYLOAD_INVALID} and {@code CONTRACT_UNSUPPORTED}
 * immediately by design, so alerting on every dead letter would page on correct
 * behaviour. What cannot wait is a dead letter on a topic where waiting costs a
 * customer their money: an order that never reached the kitchen, or a payment
 * whose outcome was never applied.
 *
 * <p>Deliberately an allow list of domains rather than a deny list. A new topic
 * is treated as non-monetary until someone names it here, which is the safe
 * direction for a night's sleep and the wrong direction for a payment — so the
 * list is checked whenever a topic is added, and the event-contract review in
 * ADR 0032 is where that happens.
 *
 * <p>Matched on the first dot-separated segment of the topic, which is the
 * domain in the ADR 0032 naming scheme ({@code ordering.events},
 * {@code payments.events}). Matching whole topic names would need editing here
 * every time a version or a stream is added.
 */
final class MonetaryTopics {

    private static final Set<String> MONETARY_DOMAINS = Set.of("ordering", "payments");

    private MonetaryTopics() {}

    static boolean isMonetary(String topicDomain) {
        return topicDomain != null && MONETARY_DOMAINS.contains(topicDomain);
    }
}
