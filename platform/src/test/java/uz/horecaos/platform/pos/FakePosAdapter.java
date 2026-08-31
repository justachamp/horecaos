package uz.horecaos.platform.pos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.Entry;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.IdempotencyBehaviour;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.domain.ExportCandidate;
import uz.horecaos.platform.pos.domain.SourceKind;

/**
 * The ADR 0007 fake point of sale.
 *
 * <p>Test sources only, and it exists to be <em>unlike</em> the one real adapter
 * in every way that matters. Clopos has no idempotency key, no push, no
 * preparation feed, and an offset-only catalog walk; this fake has all four. If
 * the provider-neutral contract were only ever exercised against Clopos, the
 * abstraction would be a description of Clopos with an interface in front of it,
 * and the first genuinely different till would break it.
 *
 * <p>The one property worth stating plainly: {@link #exportOrder} deduplicates on
 * the correlation reference and counts its side effects, so a test can assert
 * that a repeat under one reference produced one order. That is precisely the
 * guarantee Clopos does not offer, and the guarantee whose absence shapes the
 * whole export path.
 */
public final class FakePosAdapter implements PosAdapter {

    public static final String PROVIDER_TYPE = "fake-pos";

    private final Map<String, String> ordersByCorrelation = new ConcurrentHashMap<>();
    private final AtomicInteger sideEffects = new AtomicInteger();

    // Plain ArrayList, not List.copyOf below: OrderExport#correlationReference is
    // @Nullable (the provider may silently drop the field), and both ArrayList and
    // Collections.unmodifiableList tolerate that where List.copyOf would throw.
    private final List<@Nullable String> exportedCorrelations = new ArrayList<>();

    private @Nullable ProviderOutcome nextExportOutcome;

    /** How many orders the fake actually created, as opposed to was asked to. */
    public int sideEffectCount() {
        return sideEffects.get();
    }

    public List<@Nullable String> exportedCorrelations() {
        return Collections.unmodifiableList(new ArrayList<>(exportedCorrelations));
    }

    /** Makes the next export fail the way a real one does. */
    public FakePosAdapter failNextExportWith(ProviderOutcome outcome) {
        this.nextExportOutcome = outcome;
        return this;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Set<PosCapability> declaredCapabilities() {
        // Everything, including the two Clopos lacks. A capability no adapter in
        // the build implements is a capability whose refusal path is never
        // exercised, and its support path never exercised either.
        return EnumSet.allOf(PosCapability.class);
    }

    @Override
    public CapabilitySnapshot discoverCapabilities(PosContext context) {
        Map<PosCapability, Entry> entries = new EnumMap<>(PosCapability.class);
        for (PosCapability capability : PosCapability.values()) {
            entries.put(
                    capability,
                    new Entry(
                            CapabilitySupport.SUPPORTED,
                            capability == PosCapability.ORDER_EXPORT
                                    ? IdempotencyBehaviour.KEYED
                                    : IdempotencyBehaviour.NATURALLY_IDEMPOTENT,
                            true,
                            "fake-1",
                            Map.of(),
                            "Fake provider",
                            Instant.EPOCH));
        }
        return new CapabilitySnapshot(entries, Instant.EPOCH, "fake-1");
    }

    @Override
    public CatalogRead readCatalog(PosContext context) {
        CatalogSnapshot snapshot = new CatalogSnapshot(
                Instant.EPOCH,
                // A keyset walk: this fake pages by identifier, so an absence
                // from one read really is an absence.
                true,
                1,
                List.of(),
                List.of(new CatalogSnapshot.Product(
                        "f-1",
                        "Fake dish",
                        "f-cat",
                        SourceKind.DISH,
                        true,
                        false,
                        10_000L,
                        "UZS",
                        true,
                        false,
                        null,
                        Map.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return new CatalogRead(ProviderOutcome.success(Map.of(), null), snapshot);
    }

    @Override
    public AvailabilityRead readAvailability(PosContext context) {
        return new AvailabilityRead(ProviderOutcome.success(Map.of(), null), List.of());
    }

    @Override
    public ExportResult exportOrder(PosContext context, OrderExport order) {
        if (nextExportOutcome != null) {
            ProviderOutcome scripted = nextExportOutcome;
            nextExportOutcome = null;
            return new ExportResult(scripted, null, false);
        }
        exportedCorrelations.add(order.correlationReference());
        // ConcurrentHashMap refuses a null key outright, and a null correlation
        // reference has no identity to deduplicate on anyway, so it always gets a
        // fresh external id instead of going through the map.
        String correlation = order.correlationReference();
        String external = correlation == null
                ? "fake-order-" + sideEffects.incrementAndGet()
                : ordersByCorrelation.computeIfAbsent(
                        correlation, key -> "fake-order-" + sideEffects.incrementAndGet());
        return new ExportResult(ProviderOutcome.success(Map.of(), external), external, false);
    }

    @Override
    public RecoveryRead findExportedOrder(PosContext context, ExportProbe probe) {
        String probeCorrelation = probe.correlationReference();
        String external = probeCorrelation == null ? null : ordersByCorrelation.get(probeCorrelation);
        if (external == null) {
            return new RecoveryRead(ProviderOutcome.success(Map.of(), null), List.of());
        }
        // The fake echoes the reference, which is the whole difference: its match
        // is an identity where Clopos's is a resemblance.
        return new RecoveryRead(
                ProviderOutcome.success(Map.of(), null),
                List.of(new ExportCandidate(external, "ACCEPTED", Instant.EPOCH, true, true, true, 0)));
    }

    @Override
    public ProviderOutcome cancelExportedOrder(PosContext context, String externalOrderId, String reason) {
        return ProviderOutcome.success(Map.of(), externalOrderId);
    }

    @Override
    public ProviderOutcome writeFiscalIdentifier(PosContext context, String externalReceiptId, String fiscalId) {
        return ProviderOutcome.success(Map.of(), externalReceiptId);
    }

    @Override
    public ProviderOutcome writeFulfillmentStatus(PosContext context, String externalReceiptId, String status) {
        return ProviderOutcome.success(Map.of(), externalReceiptId);
    }
}
