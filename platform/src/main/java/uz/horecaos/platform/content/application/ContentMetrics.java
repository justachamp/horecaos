package uz.horecaos.platform.content.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The content module's meters (ADR 0157, Observability).
 *
 * <p>They carry <strong>no tenant and no text</strong>: {@code content.read} by outcome,
 * {@code content.publish} by kind and outcome, {@code content.publish.refused} by reason code,
 * {@code content.media.released} by outcome, {@code content.events.accepted}. A log
 * line names an item id and a code, never a title.
 */
@Component
public class ContentMetrics {

    private final MeterRegistry registry;

    public ContentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void read(String outcome) {
        registry.counter("content.read", "outcome", outcome).increment();
    }

    public void published(String kind, String outcome) {
        registry.counter("content.publish", "kind", kind, "outcome", outcome).increment();
    }

    public void refused(String reasonCode) {
        registry.counter("content.publish.refused", "reason", reasonCode).increment();
    }

    public void mediaRelease(String outcome) {
        registry.counter("content.media.released", "outcome", outcome).increment();
    }

    public void eventsAccepted(int count) {
        registry.counter("content.events.accepted").increment(count);
    }
}
