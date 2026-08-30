package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.Entry;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.application.port.PosAdapter.PosContext;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;

/**
 * Discovers what one installation can do, and refuses to let it claim more
 * (ADR 0011).
 *
 * <p>ADR 0011's discovery steps three to seven, in order: a bounded connection
 * check, a capability probe against controlled calls, a read-back of the
 * provider's own identity, and a stored snapshot with evidence and an adapter
 * version. The step this class deliberately does not perform is step six —
 * requiring an operator to confirm the binding points at the intended restaurant
 * — because that is a decision, not a check, and it lives on the control-plane
 * activation path with an audit record attached.
 *
 * <p>The narrowing rule is enforced here rather than trusted. A discovered
 * capability is capped at the vendor ceiling, so an adapter that reports
 * something the vendor's API has never had cannot write it into a snapshot other
 * code then reads as fact. That protects the one entry that matters most: a till
 * that reports no preparation status stays reporting none, whatever a probe
 * appears to see.
 */
@Service
public class PosCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(PosCapabilityService.class);

    private final PosAdapterRegistry adapters;
    private final JdbcPosCapabilityStore capabilities;
    private final JdbcPosBindingConfiguration configuration;
    private final Clock clock;

    public PosCapabilityService(PosAdapterRegistry adapters, JdbcPosCapabilityStore capabilities,
            JdbcPosBindingConfiguration configuration, Clock clock) {
        this.adapters = adapters;
        this.capabilities = capabilities;
        this.configuration = configuration;
        this.clock = clock;
    }

    /**
     * Runs discovery against one installation and stores what it found.
     *
     * @param providerType the ADR 0026 installation's provider type. Passed in
     *                     rather than looked up so this method has exactly one
     *                     reason to fail
     */
    @Transactional
    public Optional<CapabilitySnapshot> reconcile(UUID tenantId, UUID installationId,
            String providerType) {

        Optional<PosAdapter> adapter = adapters.forProvider(providerType);
        if (adapter.isEmpty()) {
            log.warn("No POS adapter is registered for {}", providerType);
            return Optional.empty();
        }

        Map<String, String> config = configuration
                .resolveInstallation(tenantId, installationId)
                .orElse(Map.of());

        PosContext context = new PosContext(tenantId, installationId, null, null, config,
                installationId.toString());

        CapabilitySnapshot discovered = adapter.get().discoverCapabilities(context);
        CapabilitySnapshot capped = capAtCeiling(providerType, discovered);

        capped.entries().forEach((capability, entry) -> capabilities.recordProbe(
                tenantId, installationId, capability,
                probeStatusOf(entry), null, null, entry.evidence(),
                capped.adapterVersion(), clock.instant()));

        capabilities.writeSnapshot(tenantId, installationId, capped);
        return Optional.of(capped);
    }

    public Optional<CapabilitySnapshot> snapshot(UUID tenantId, UUID installationId) {
        return capabilities.readSnapshot(tenantId, installationId);
    }

    /**
     * Whether an installation may be asked to do something.
     *
     * <p>Answers from the stored snapshot rather than by calling the provider, so
     * a control plane can render a configuration screen without a network round
     * trip per checkbox — and so the answer it shows is the same one the export
     * path will act on.
     */
    public boolean usable(UUID tenantId, UUID installationId, PosCapability capability) {
        return snapshot(tenantId, installationId)
                .map(snapshot -> snapshot.usable(capability))
                .orElse(false);
    }

    /**
     * Caps every discovered entry at what the vendor's API can ever do.
     *
     * <p>The rule is one-directional. A probe may find that this credential cannot
     * do something the vendor supports — that is exactly what a restaurant's
     * choice of staff user produces — and it may never find the reverse.
     */
    private CapabilitySnapshot capAtCeiling(String providerType, CapabilitySnapshot discovered) {
        Map<PosCapability, CapabilitySupport> ceiling = capabilities.ceiling(providerType);
        Map<PosCapability, Entry> capped = new EnumMap<>(PosCapability.class);

        discovered.entries().forEach((capability, entry) -> {
            CapabilitySupport limit = ceiling.getOrDefault(capability, CapabilitySupport.UNSUPPORTED);
            CapabilitySupport effective = narrower(entry.support(), limit);
            if (effective != entry.support()) {
                log.warn("Discovery reported {} for {} on {}, capped to {} by the provider ceiling",
                        entry.support(), capability, providerType, effective);
            }
            capped.put(capability, new Entry(effective, entry.idempotency(), entry.pushSupported(),
                    entry.capabilityVersion(), entry.limits(),
                    effective == entry.support()
                            ? entry.evidence()
                            : entry.evidence() + " (capped by the assessed provider ceiling)",
                    entry.verifiedAt()));
        });

        return new CapabilitySnapshot(capped, discovered.verifiedAt(), discovered.adapterVersion());
    }

    /** UNSUPPORTED beats PARTIAL beats SUPPORTED. */
    private static CapabilitySupport narrower(CapabilitySupport discovered, CapabilitySupport limit) {
        if (discovered == CapabilitySupport.UNSUPPORTED || limit == CapabilitySupport.UNSUPPORTED) {
            return CapabilitySupport.UNSUPPORTED;
        }
        if (discovered == CapabilitySupport.PARTIAL || limit == CapabilitySupport.PARTIAL) {
            return CapabilitySupport.PARTIAL;
        }
        return CapabilitySupport.SUPPORTED;
    }

    /**
     * A snapshot entry rendered as a probe status.
     *
     * <p>PARTIAL becomes UNVERIFIABLE rather than SUPPORTED, because the adapter
     * uses PARTIAL both for a capability with a genuinely missing half and for one
     * it could not exercise without a side effect. Recording either as proven
     * would be the probe claiming more than it did.
     */
    private static String probeStatusOf(Entry entry) {
        return switch (entry.support()) {
            case SUPPORTED -> "SUPPORTED";
            case UNSUPPORTED -> "UNSUPPORTED";
            case PARTIAL -> "UNVERIFIABLE";
        };
    }
}
