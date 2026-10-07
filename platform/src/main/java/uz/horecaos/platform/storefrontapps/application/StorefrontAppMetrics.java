package uz.horecaos.platform.storefrontapps.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppClientType;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What the app tier counts (ADR 0070).
 *
 * <p>The number that matters in rollout stage one is {@code outcome=unattributed}: ADR 0070
 * makes the identity required only once that count has been zero for a stated period, so
 * the count has to exist before the requirement can be earned.
 *
 * <p>Bounded labels only. {@code outcome} is one of {@code attributed}, {@code unattributed}
 * or {@code refused}; {@code reason} on a refusal is the stable error code; {@code clientType}
 * is public or confidential. No app id, tenant, origin or customer is ever a label: an origin
 * is attacker-chosen text and would make the series unbounded.
 */
@Component
public class StorefrontAppMetrics {

    public static final String REQUESTS = "horecaos.storefront.app.requests";

    private final MeterRegistry registry;

    public StorefrontAppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A registry nobody scrapes, for code wired by hand. */
    public static StorefrontAppMetrics none() {
        return new StorefrontAppMetrics(new SimpleMeterRegistry());
    }

    void attributed(StorefrontAppClientType clientType) {
        registry.counter(
                        REQUESTS,
                        "outcome",
                        "attributed",
                        "clientType",
                        clientType.name().toLowerCase(java.util.Locale.ROOT))
                .increment();
    }

    void unattributed() {
        registry.counter(REQUESTS, "outcome", "unattributed").increment();
    }

    void refused(ErrorCode code) {
        registry.counter(REQUESTS, "outcome", "refused", "reason", code.name().toLowerCase(java.util.Locale.ROOT))
                .increment();
    }

    /** For tests: the registry these counters live in. */
    public MeterRegistry registry() {
        return registry;
    }
}
