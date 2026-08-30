package uz.qoida.platform.integration.inbox;

import uz.qoida.platform.integration.api.InboxHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import uz.qoida.platform.integration.events.EventCatalog;

/**
 * Explicit registration of inbox handlers by consumer, type, and version
 * (ADR 0005).
 *
 * <p>Two handlers claiming the same key fail at startup. That collision is
 * otherwise invisible until production, where whichever bean happened to be
 * registered last would silently win.
 */
@Component
public class InboxHandlerRegistry {

    private final Map<String, InboxHandler<?>> handlers = new LinkedHashMap<>();

    public InboxHandlerRegistry(List<InboxHandler<?>> registered) {
        for (InboxHandler<?> handler : registered) {
            String key = key(handler.consumerName(), handler.eventType(), handler.eventVersion());
            InboxHandler<?> previous = handlers.put(key, handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two inbox handlers registered for %s: %s and %s".formatted(
                                key, previous.getClass().getName(), handler.getClass().getName()));
            }
        }
    }

    public Optional<InboxHandler<?>> find(String consumerName, String eventType, int eventVersion) {
        return Optional.ofNullable(handlers.get(key(consumerName, eventType, eventVersion)));
    }

    public List<InboxHandler<?>> all() {
        return List.copyOf(handlers.values());
    }

    /** Consumer names currently registered, used to fan one record out per consumer. */
    public List<String> consumerNames() {
        return handlers.values().stream().map(InboxHandler::consumerName).distinct().toList();
    }

    /**
     * The consumers a record on this topic should be offered to.
     *
     * <p>Needed as soon as there is more than one topic. A listener that offered
     * every record to every consumer would hand a {@code fulfillment.commands}
     * record to the tenancy projection, which has no handler for it — and
     * {@code drive} treats a missing handler as a permanent contract failure and
     * dead-letters the row. Every record on every topic would become a dead
     * letter for every consumer that does not care about it, which is both a
     * false alarm and a growing table.
     *
     * <p>The topic is read from the ADR 0032 catalogue rather than declared a
     * second time on the handler, because the catalogue is already the authority
     * on which topic an event type lives on and a second declaration is a second
     * thing to keep in step. A handler whose event type is not catalogued — the
     * controlled route's, deliberately — keeps the previous behaviour of being
     * offered every record, so nothing that worked before this method existed
     * stops working.
     */
    public List<String> consumerNamesFor(String topic) {
        return handlers.values().stream()
                .filter(handler -> EventCatalog.find(handler.eventType(), handler.eventVersion())
                        .map(contract -> contract.topic().equals(topic))
                        .orElse(true))
                .map(InboxHandler::consumerName)
                .distinct()
                .toList();
    }

    private static String key(String consumerName, String eventType, int eventVersion) {
        return "%s|%s|v%d".formatted(consumerName, eventType, eventVersion);
    }
}
