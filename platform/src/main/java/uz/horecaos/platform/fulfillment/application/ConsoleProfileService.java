package uz.horecaos.platform.fulfillment.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * How the operations console opens for a tenant (ADR 0164).
 *
 * <p>HorecaOS is one product. A courier-service tenant gets a deliveries-first <em>profile</em> of
 * the one console, carried by a purchased module and not by its business type: this reads the
 * entitlement the {@code deliveries-console} module switches on and nothing else. The profile
 * changes where the console opens and what its rail shows. It never grants a capability, it is not
 * consulted by any authorization decision, and a hidden section is one URL away for whoever holds
 * the capability (ADR 0025).
 *
 * <p>Nothing is stored. The profile is resolved from the entitlement snapshot on read, so ending the
 * module, or a lapsed subscription (ADR 0087), returns the console to {@link Variant#STANDARD} at the
 * next read.
 */
@Service
public class ConsoleProfileService {

    /** The route a courier-service tenant's console opens on, which the client redirects to the dispatch board. */
    static final String DELIVERIES_LANDING = "/deliveries";

    static final String STANDARD_LANDING = "/today";

    /** The sections the deliveries profile puts away: what a delivery business does not run (ADR 0164 Decision 5). */
    static final List<String> DELIVERIES_HIDDEN_SECTIONS = List.of("/kitchen", "/marketing", "/inbox");

    private final EntitlementService entitlements;
    private final MeterRegistry meters;

    public ConsoleProfileService(EntitlementService entitlements, MeterRegistry meters) {
        this.entitlements = entitlements;
        this.meters = meters;
    }

    public ConsoleProfile profileFor(UUID tenantId) {
        EntitlementSnapshot snapshot = entitlements.snapshot(tenantId);
        boolean deliveries = Boolean.TRUE.equals(snapshot.require(EntitlementKeys.DELIVERY_DELIVERIES_CONSOLE_ENABLED)
                .featureEnabled());
        Variant variant = deliveries ? Variant.DELIVERIES : Variant.STANDARD;
        // Bounded labels only: two variants, no tenant (ADR 0164, observability).
        meters.counter("console.profile", "variant", variant.name()).increment();
        return new ConsoleProfile(
                variant,
                deliveries ? DELIVERIES_LANDING : STANDARD_LANDING,
                deliveries ? DELIVERIES_HIDDEN_SECTIONS : List.of(),
                deliveries ? List.of(FulfillmentMode.DELIVERY.name()) : allHandovers(),
                snapshot.hash());
    }

    private static List<String> allHandovers() {
        return java.util.Arrays.stream(FulfillmentMode.values())
                .map(FulfillmentMode::name)
                .toList();
    }

    public enum Variant {
        STANDARD,
        DELIVERIES
    }

    /**
     * @param version the entitlement snapshot's hash, the response's ETag: the profile is a pure
     *                function of it
     */
    public record ConsoleProfile(
            Variant variant, String landing, List<String> hiddenSections, List<String> handovers, String version) {

        public ConsoleProfile {
            hiddenSections = List.copyOf(hiddenSections);
            handovers = List.copyOf(handovers);
        }
    }
}
