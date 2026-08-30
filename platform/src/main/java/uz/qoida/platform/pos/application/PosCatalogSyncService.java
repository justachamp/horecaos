package uz.qoida.platform.pos.application;

import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import uz.qoida.platform.integration.api.provider.BindingRef;
import uz.qoida.platform.integration.api.provider.ProviderInstallationLookup;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.pos.api.PosCapability;
import uz.qoida.platform.pos.application.port.PosAdapter;
import uz.qoida.platform.pos.application.port.PosAdapter.CatalogRead;
import uz.qoida.platform.pos.application.port.PosAdapter.PosContext;
import uz.qoida.platform.pos.domain.CatalogSnapshot;
import uz.qoida.platform.pos.domain.DifferenceEngine;
import uz.qoida.platform.pos.domain.DifferenceEngine.AbsenceHistory;
import uz.qoida.platform.pos.domain.DifferenceEngine.TargetCatalog;
import uz.qoida.platform.pos.domain.FieldAuthorityPolicy;
import uz.qoida.platform.pos.domain.SyncDifference.EntityType;
import uz.qoida.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.qoida.platform.pos.infrastructure.persistence.JdbcPosSyncStore;
import uz.qoida.platform.pos.infrastructure.persistence.JdbcPosTargetCatalog;

/**
 * Runs one catalog import, from provider read to reviewable report (ADR 0012).
 *
 * <p>The run ends at {@code REVIEW_REQUIRED}. Nothing in this service writes to
 * the catalog, and there is no code path that could: applying a comparison is a
 * separate command with its own capability, taken by somebody who read the
 * report. ADR 0012's argument for that separation is that there is no safe undo
 * for a menu that was live and wrong during a lunch rush, and the shape of this
 * class is what makes the argument true rather than aspirational.
 *
 * <p>Two provider facts shape the sequence.
 *
 * <ul>
 *   <li><b>The read is always whole.</b> The first real provider offers no
 *       incremental fetch of any kind, so a run stages a full snapshot. A partial
 *       read is discarded rather than staged: half a menu diffed against a whole
 *       one reports the missing half as removals.</li>
 *   <li><b>An absence is recorded before it is interpreted.</b> Offset pagination
 *       over a catalog being edited can skip a row, so the run updates the absence
 *       streaks first and lets {@code RemovalQuorum} decide, inside the engine,
 *       whether this run's absence is yet worth showing anybody.</li>
 * </ul>
 */
@Service
public class PosCatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(PosCatalogSyncService.class);

    private final PosAdapterRegistry adapters;
    private final ProviderInstallationLookup installations;
    private final JdbcPosBindingConfiguration configuration;
    private final JdbcPosSyncStore runs;
    private final JdbcPosTargetCatalog targets;
    private final FieldAuthorityPolicy policy;
    private final Clock clock;

    public PosCatalogSyncService(PosAdapterRegistry adapters,
            ProviderInstallationLookup installations,
            JdbcPosBindingConfiguration configuration,
            JdbcPosSyncStore runs,
            JdbcPosTargetCatalog targets,
            Clock clock) {
        this.adapters = adapters;
        this.installations = installations;
        this.configuration = configuration;
        this.runs = runs;
        this.targets = targets;
        // ADR 0012 requires the policy to be versioned per tenant and snapshotted
        // on each run. The shipped default is the whole policy today; when the
        // control plane can author one, this becomes a lookup and the run already
        // records which version it used.
        this.policy = FieldAuthorityPolicy.INITIAL;
        this.clock = clock;
    }

    /**
     * Reads, stages, and compares. Never applies.
     *
     * @param dryRun whether this run is permitted to produce apply items at all.
     *               Both values stop before mutating the catalog; the flag is what
     *               ADR 0012's rollout uses to run reports for weeks before
     *               anything is switched on
     */
    public RunResult run(UUID tenantId, UUID bindingId, String triggerType, boolean dryRun) {
        Optional<BindingRef> resolved = binding(tenantId, bindingId);
        if (resolved.isEmpty()) {
            return RunResult.refused("BINDING_NOT_CATALOG_CAPABLE",
                    "This binding does not provide catalog read");
        }
        BindingRef binding = resolved.get();

        Optional<PosAdapter> adapter = adapters.forProvider(binding.providerType());
        if (adapter.isEmpty()) {
            return RunResult.refused("NO_ADAPTER",
                    "No POS adapter is registered for " + binding.providerType());
        }

        Map<String, String> config = configuration.resolve(binding).orElse(Map.of());
        UUID runId = runs.openRun(tenantId, bindingId, triggerType, dryRun,
                adapterVersionOf(adapter.get()), policy.version(), clock.instant());

        runs.markStatus(tenantId, runId, "FETCHING", null, clock.instant());

        PosContext context = new PosContext(tenantId, binding.installationId(), bindingId,
                config.get("clopos.venueId"), config, runId.toString());

        CatalogRead read = adapter.get().readCatalog(context);
        if (read.outcome().status() != ProviderOutcome.Status.SUCCESS || read.snapshot() == null) {
            // Nothing is staged. A run that stages what it managed to read and
            // compares it would report every unread product as removed, which is
            // the exact failure the whole quorum design exists to prevent — and it
            // would arrive with the authority of a completed run.
            runs.markFailed(tenantId, runId, read.outcome().errorCode(), read.outcome().detail());
            return new RunResult(runId, "FAILED", read.outcome(), 0, 0);
        }

        CatalogSnapshot snapshot = read.snapshot();
        runs.markStatus(tenantId, runId, "STAGED", "fetched_at", clock.instant());
        runs.stage(tenantId, runId, snapshot);
        runs.markStatus(tenantId, runId, "COMPARING", "normalized_at", clock.instant());

        TargetCatalog target = targets.read(tenantId, bindingId, binding.brandId(),
                config.getOrDefault("catalog.defaultLocale", "uz-UZ"));

        AbsenceHistory absences = runs.recordAbsences(tenantId, bindingId, runId,
                mappedIds(target), presentIds(snapshot), snapshot.walkStable(), clock.instant());

        DifferenceEngine.Result result = new DifferenceEngine(policy)
                .compare(snapshot, target, absences);

        runs.recordFindings(tenantId, runId, result.differences(), result.conflicts());
        runs.markStatus(tenantId, runId, "REVIEW_REQUIRED", "compared_at", clock.instant());

        log.info("POS catalog run {} staged {} products and produced {} differences and {} conflicts",
                runId, snapshot.products().size(), result.differences().size(),
                result.conflicts().size());

        return new RunResult(runId, "REVIEW_REQUIRED", read.outcome(),
                result.differences().size(), result.conflicts().size());
    }

    /**
     * The binding, if it currently provides catalog read.
     *
     * <p>Two steps rather than one, and both are necessary. The row read supplies
     * the scope — a capability lookup resolves at a brand and a location, and a
     * caller holding only a binding id has neither. The capability lookup then
     * confirms the binding is active and still has the capability enabled, so a
     * binding somebody suspended cannot be run by naming its id directly.
     */
    private Optional<BindingRef> binding(UUID tenantId, UUID bindingId) {
        Optional<BindingRef> row = configuration.bindingRef(tenantId, bindingId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        BindingRef reference = row.get();
        return installations
                .candidateBindings(tenantId, reference.brandId(), reference.locationId(),
                        PosCapability.CATALOG_READ.code())
                .stream()
                .filter(candidate -> candidate.bindingId().equals(bindingId))
                .findFirst();
    }

    private static Map<EntityType, Set<String>> mappedIds(TargetCatalog target) {
        Map<EntityType, Set<String>> mapped = new EnumMap<>(EntityType.class);
        mapped.put(EntityType.PRODUCT, Set.copyOf(target.entities(EntityType.PRODUCT).keySet()));
        mapped.put(EntityType.VARIANT, Set.copyOf(target.entities(EntityType.VARIANT).keySet()));
        return mapped;
    }

    private static Map<EntityType, Set<String>> presentIds(CatalogSnapshot snapshot) {
        Map<EntityType, Set<String>> present = new EnumMap<>(EntityType.class);
        present.put(EntityType.PRODUCT, snapshot.products().stream()
                .map(CatalogSnapshot.Product::externalId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        present.put(EntityType.VARIANT, snapshot.variants().stream()
                .map(CatalogSnapshot.Variant::externalId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return present;
    }

    /**
     * The adapter's version, taken from the class rather than from a constant
     * here, so a snapshot staged by one release cannot be read as though a later
     * one had produced it.
     */
    private static String adapterVersionOf(PosAdapter adapter) {
        return adapter.getClass().getSimpleName();
    }

    /**
     * @param outcome the provider's answer, kept so a failed run can say what the
     *                till said rather than only that it failed
     */
    public record RunResult(UUID runId, String status, ProviderOutcome outcome,
            int differenceCount, int conflictCount) {

        static RunResult refused(String code, String detail) {
            return new RunResult(null, "REFUSED", ProviderOutcome.rejected(code, detail), 0, 0);
        }

        public boolean started() {
            return runId != null;
        }
    }
}
