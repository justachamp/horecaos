package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.MappingEntityType;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.application.port.PosAdapter.PosContext;
import uz.horecaos.platform.pos.application.port.PosAdapter.ReferenceListRead;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore.ExternalCandidate;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore.MappingRow;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore.NamedCandidate;

/**
 * The tenant-facing mapping pane's business logic: sourcing the right-hand
 * side, creating and retiring a mapping by hand, and bulk auto-match (ADR
 * 0012/0026, gap-map row 10.8b).
 *
 * <p>{@link #listUnmapped} and {@link #bulkAutoMatch} are honest about
 * a real gap: no adapter in this build discovers a vendor's couriers,
 * cancellation reasons, or channel codes, and Clopos — the one real adapter —
 * discovers no payment types or discounts either (its {@link
 * PosAdapter#discoverPaymentTypes}/{@link PosAdapter#discoverDiscounts} answer
 * {@code NOT_SUPPORTED}). {@code sourced=false} on the response says so rather
 * than silently returning an empty list an operator would read as "everything
 * is mapped"; creating an {@code OPERATOR} mapping by typing the provider's
 * own code works regardless of whether the right-hand side could be sourced.
 */
@Service
public class PosMappingService {

    private final JdbcPosMappingStore mappings;
    private final JdbcPosBindingConfiguration configuration;
    private final PosAdapterRegistry adapters;
    private final Clock clock;

    public PosMappingService(
            JdbcPosMappingStore mappings,
            JdbcPosBindingConfiguration configuration,
            PosAdapterRegistry adapters,
            Clock clock) {
        this.mappings = mappings;
        this.configuration = configuration;
        this.adapters = adapters;
        this.clock = clock;
    }

    /**
     * Both unmapped sides for the pane's dual list: the provider's own
     * candidates ({@link UnmappedResult#sourced()} says whether this build can
     * read them at all) and HorecaOS's own unmapped records for this type,
     * which can always be read regardless — a merchant's own catalogue,
     * payment methods, promotions, couriers, cancellation reasons and sales
     * channels are never a discovery problem.
     */
    public UnmappedBothSides listUnmapped(UUID tenantId, UUID bindingId, MappingEntityType type) {
        Optional<BindingRef> binding = configuration.bindingRef(tenantId, bindingId);
        if (binding.isEmpty()) {
            return new UnmappedBothSides(new UnmappedResult(false, "No such binding", List.of()), List.of());
        }
        UnmappedResult external = externalCandidates(tenantId, binding.get(), type);
        List<NamedCandidate> horecaos = horecaosCandidates(tenantId, binding.get(), type);
        return new UnmappedBothSides(external, horecaos);
    }

    /**
     * Creates an {@code OPERATOR} mapping.
     *
     * @return {@link CreateOutcome#conflict()} when an {@code ACTIVE} mapping
     *         already claims either side, or when V0013's {@code
     *         uq_mapping_external}/{@code uq_mapping_horecaos} refuse the
     *         insert outright — those two are table-wide, not {@code
     *         ACTIVE}-only, so a {@code RETIRED} row's external or HorecaOS id
     *         is burned for this binding and type for good. That is existing,
     *         intentional schema, not a gap this wave closes: the sync engine
     *         itself only ever updates a mapping's external id in place or
     *         retires it, never recreates one, and {@link CreateOutcome#conflict()}
     *         is this method's version of the same rule rather than an opaque 500
     */
    public CreateOutcome create(
            UUID tenantId,
            UUID bindingId,
            MappingEntityType type,
            UUID horecaosEntityId,
            String externalEntityId,
            @Nullable String externalParentId) {

        Optional<BindingRef> binding = configuration.bindingRef(tenantId, bindingId);
        if (binding.isEmpty()) {
            return CreateOutcome.notFound("No such binding");
        }
        if (mappings.findActiveConflict(tenantId, bindingId, type, horecaosEntityId, externalEntityId)
                .isPresent()) {
            return CreateOutcome.conflict();
        }
        try {
            UUID id = mappings.create(
                    tenantId,
                    binding.get().installationId(),
                    bindingId,
                    type,
                    horecaosEntityId,
                    externalEntityId,
                    externalParentId,
                    clock.instant());
            return CreateOutcome.created(id);
        } catch (org.springframework.dao.DataIntegrityViolationException alreadyClaimed) {
            return CreateOutcome.conflict();
        }
    }

    /**
     * @return {@link RetireOutcome#staleVersion()} when the mapping moved since
     *         the caller read it, and {@link RetireOutcome#notFound()} when it
     *         does not exist for this tenant at all — two different remediations
     *         behind two different codes, matching {@code JdbcPosApplyStore}'s
     *         own {@code retireMapping}
     */
    public RetireOutcome retire(UUID tenantId, UUID mappingId, long expectedVersion) {
        Optional<MappingRow> existing = mappings.find(tenantId, mappingId);
        if (existing.isEmpty()) {
            return RetireOutcome.notFound();
        }
        boolean retired = mappings.retire(tenantId, mappingId, expectedVersion, clock.instant());
        return retired ? RetireOutcome.retired() : RetireOutcome.staleVersion();
    }

    /**
     * Matches unmapped candidates on both sides by exact, case-insensitive
     * name, and creates an {@code OPERATOR} mapping for every unambiguous
     * pair.
     *
     * <p>An ambiguous name — two externals or two HorecaOS entities sharing it
     * — is never resolved by picking one: it is reported as a {@link
     * MatchConflict} for a person to decide with the two-sided conflict card,
     * which is the whole point of the row over a last-write-wins auto-match.
     */
    public BulkAutoMatchResult bulkAutoMatch(UUID tenantId, UUID bindingId, MappingEntityType type) {
        Optional<BindingRef> binding = configuration.bindingRef(tenantId, bindingId);
        if (binding.isEmpty()) {
            return new BulkAutoMatchResult(false, "No such binding", 0, List.of());
        }
        UnmappedResult external = externalCandidates(tenantId, binding.get(), type);
        if (!external.sourced()) {
            return new BulkAutoMatchResult(false, external.detail(), 0, List.of());
        }
        List<NamedCandidate> horecaos = horecaosCandidates(tenantId, binding.get(), type);

        Map<String, List<String>> externalByName = groupExternalByName(external.entities());
        Map<String, List<UUID>> horecaosByName = groupHorecaosByName(horecaos);

        int matched = 0;
        List<MatchConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : externalByName.entrySet()) {
            List<UUID> horecaosIds = horecaosByName.get(entry.getKey());
            if (horecaosIds == null || horecaosIds.isEmpty()) {
                continue;
            }
            List<String> externalIds = entry.getValue();
            if (externalIds.size() == 1 && horecaosIds.size() == 1) {
                // A concurrent write could have mapped one of this pair between
                // the candidate read above and this insert; the unique
                // constraint (V0013) is the actual guard, this just keeps that
                // race from surfacing as a 500 for what is otherwise a
                // best-effort bulk convenience.
                try {
                    mappings.create(
                            tenantId,
                            binding.get().installationId(),
                            bindingId,
                            type,
                            horecaosIds.getFirst(),
                            externalIds.getFirst(),
                            null,
                            clock.instant());
                    matched++;
                } catch (org.springframework.dao.DataIntegrityViolationException alreadyMapped) {
                    // Skip: something else mapped one side of this pair first.
                }
            } else {
                conflicts.add(new MatchConflict(
                        displayNameFor(entry.getKey(), external.entities()), externalIds, horecaosIds));
            }
        }
        return new BulkAutoMatchResult(true, null, matched, List.copyOf(conflicts));
    }

    // ------------------------------------------------------------------

    private UnmappedResult externalCandidates(UUID tenantId, BindingRef binding, MappingEntityType type) {
        return switch (type) {
            case PRODUCT ->
                new UnmappedResult(true, null, mappings.unmappedStagedProducts(tenantId, binding.bindingId()));
            case PAYMENT_TYPE -> fromAdapterCall(tenantId, binding, PosAdapter::discoverPaymentTypes);
            case DISCOUNT -> fromAdapterCall(tenantId, binding, PosAdapter::discoverDiscounts);
            case COURIER ->
                notSourced(
                        "No provider in this build discovers a courier list; add the mapping by typing the provider's own code.");
            case CANCELLATION_REASON ->
                notSourced(
                        "No provider in this build discovers a cancellation-reason list; add the mapping by typing the "
                                + "provider's own code.");
            case CHANNEL_POS_CODE ->
                notSourced(
                        "No provider in this build discovers a channel/category code list; add the mapping by typing "
                                + "the provider's own code.");
        };
    }

    private UnmappedResult fromAdapterCall(
            UUID tenantId,
            BindingRef binding,
            java.util.function.BiFunction<PosAdapter, PosContext, ReferenceListRead> call) {
        Optional<PosAdapter> adapter = adapters.forProvider(binding.providerType());
        if (adapter.isEmpty()) {
            return notSourced("No POS adapter is registered for " + binding.providerType());
        }
        Map<String, String> config = configuration.resolve(binding).orElse(Map.of());
        PosContext context = new PosContext(
                tenantId,
                binding.installationId(),
                binding.bindingId(),
                config.get("clopos.venueId"),
                config,
                "mapping-pane");
        ReferenceListRead read = call.apply(adapter.get(), context);
        if (read.outcome().status() != ProviderOutcome.Status.SUCCESS) {
            return notSourced(
                    read.outcome().detail() == null
                            ? "The provider does not support this list"
                            : read.outcome().detail());
        }
        List<ExternalCandidate> candidates = read.entries().stream()
                .map(entry -> new ExternalCandidate(entry.externalId(), entry.name(), null))
                .toList();
        return new UnmappedResult(true, null, candidates);
    }

    private static UnmappedResult notSourced(String detail) {
        return new UnmappedResult(false, detail, List.of());
    }

    private List<NamedCandidate> horecaosCandidates(UUID tenantId, BindingRef binding, MappingEntityType type) {
        return switch (type) {
            case PRODUCT -> mappings.unmappedProducts(tenantId, binding.bindingId(), binding.brandId(), "uz-UZ");
            case PAYMENT_TYPE -> mappings.unmappedPaymentMethods(tenantId, binding.bindingId());
            case DISCOUNT -> mappings.unmappedDiscounts(tenantId, binding.bindingId(), binding.brandId());
            case COURIER -> mappings.unmappedCouriers(tenantId, binding.bindingId());
            case CANCELLATION_REASON -> mappings.unmappedCancellationReasons(tenantId, binding.bindingId());
            case CHANNEL_POS_CODE -> mappings.unmappedSalesChannels(tenantId, binding.bindingId());
        };
    }

    private static Map<String, List<String>> groupExternalByName(List<ExternalCandidate> candidates) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (ExternalCandidate candidate : candidates) {
            String key = normalize(candidate.name());
            if (key == null) {
                continue;
            }
            byName.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate.externalId());
        }
        return byName;
    }

    private static Map<String, List<UUID>> groupHorecaosByName(List<NamedCandidate> candidates) {
        Map<String, List<UUID>> byName = new LinkedHashMap<>();
        for (NamedCandidate candidate : candidates) {
            String key = normalize(candidate.name());
            if (key == null) {
                continue;
            }
            byName.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate.id());
        }
        return byName;
    }

    private static @Nullable String normalize(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static String displayNameFor(String normalized, List<ExternalCandidate> candidates) {
        return candidates.stream()
                .map(ExternalCandidate::name)
                .filter(name -> normalized.equals(normalize(name)))
                .findFirst()
                .orElse(normalized);
    }

    /** @param sourced false when no provider or HorecaOS source exists for this type in this build */
    public record UnmappedResult(boolean sourced, @Nullable String detail, List<ExternalCandidate> entities) {}

    /** Both sides of the mapping pane's dual list for one binding and entity type. */
    public record UnmappedBothSides(UnmappedResult external, List<NamedCandidate> horecaos) {}

    public record CreateOutcome(
            Kind kind, @Nullable UUID mappingId, @Nullable String detail) {

        public enum Kind {
            CREATED,
            CONFLICT,
            NOT_FOUND
        }

        static CreateOutcome created(UUID mappingId) {
            return new CreateOutcome(Kind.CREATED, mappingId, null);
        }

        static CreateOutcome conflict() {
            return new CreateOutcome(Kind.CONFLICT, null, "An active mapping already claims this pair");
        }

        static CreateOutcome notFound(String detail) {
            return new CreateOutcome(Kind.NOT_FOUND, null, detail);
        }
    }

    public record RetireOutcome(Kind kind) {

        public enum Kind {
            RETIRED,
            STALE_VERSION,
            NOT_FOUND
        }

        static RetireOutcome retired() {
            return new RetireOutcome(Kind.RETIRED);
        }

        static RetireOutcome staleVersion() {
            return new RetireOutcome(Kind.STALE_VERSION);
        }

        static RetireOutcome notFound() {
            return new RetireOutcome(Kind.NOT_FOUND);
        }
    }

    /** Two or more names collided on one side rather than pairing 1:1 — the two-sided conflict card's own data. */
    public record MatchConflict(String name, List<String> externalIds, List<UUID> horecaosEntityIds) {}

    /** @param sourced false when the external side could not be sourced at all, so matchedCount is trivially 0 */
    public record BulkAutoMatchResult(
            boolean sourced, @Nullable String detail, int matchedCount, List<MatchConflict> conflicts) {}
}
