package uz.qoida.platform.pos.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import uz.qoida.platform.pos.domain.SyncDifference.DifferenceCategory;
import uz.qoida.platform.pos.domain.SyncDifference.EntityType;
import uz.qoida.platform.pos.domain.SyncDifference.FieldAuthority;
import uz.qoida.platform.pos.domain.SyncDifference.RecommendedAction;
import uz.qoida.platform.pos.domain.SyncDifference.Severity;

/**
 * Compares a staged provider snapshot against what Qoida holds (ADR 0012).
 *
 * <p>Pure, deterministic, and unit-tested on its own. Re-running it over the same
 * two inputs produces the same list in the same order — the outputs are sorted
 * before they are returned — because a review queue whose contents shuffle
 * between runs cannot be reviewed incrementally, and a difference report nobody
 * can diff against yesterday's is a report nobody reads twice.
 *
 * <p>The engine writes nothing. It decides nothing about the catalog either: it
 * produces statements and recommendations, and applying them is a separate
 * command a person authorises. That separation is what makes a provider bug an
 * unpleasant morning rather than a lunchtime with the wrong menu live.
 *
 * <p>Three rules do most of the work.
 *
 * <ol>
 *   <li><b>Identity comes from the mapping, never from a name.</b> The first real
 *       provider offers an integer id and nothing else — no SKU, no external
 *       code, no slug — and every other field including the name is editable in
 *       the back office. An unmapped entity is an addition or a conflict; it is
 *       never matched by resemblance.</li>
 *   <li><b>A Qoida-authoritative field never produces an applicable action.</b>
 *       The difference is recorded so an operator can see the disagreement, and
 *       recommended {@code IGNORE} so nobody is offered a button that overwrites
 *       curated content.</li>
 *   <li><b>Absence is not removal until the quorum says so.</b> See
 *       {@link RemovalQuorum}.</li>
 * </ol>
 */
public final class DifferenceEngine {

    private final FieldAuthorityPolicy policy;

    public DifferenceEngine(FieldAuthorityPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "A field authority policy is required");
    }

    /**
     * @param target      what Qoida currently holds for the entities this binding
     *                    has mapped
     * @param absences    how many consecutive runs, including this one, have
     *                    failed to see each mapped external id. Supplied rather
     *                    than counted here so the engine stays pure
     */
    public Result compare(CatalogSnapshot snapshot, TargetCatalog target, AbsenceHistory absences) {
        List<SyncDifference> differences = new ArrayList<>();
        List<SyncConflict> conflicts = new ArrayList<>();

        detectDuplicates(snapshot, conflicts);
        detectMissingParents(snapshot, conflicts);
        detectUnrepresentableModifiers(snapshot, conflicts);

        Set<String> conflicted = conflicts.stream()
                .map(SyncConflict::externalEntityId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        compareProducts(snapshot, target, conflicted, differences);
        compareVariants(snapshot, target, conflicted, differences);
        detectRemovals(snapshot, target, absences, snapshot.walkStable(), differences);

        differences.sort(ORDER);
        conflicts.sort(Comparator
                .comparing((SyncConflict conflict) -> conflict.entityType().name())
                .thenComparing(SyncConflict::externalEntityId)
                .thenComparing(conflict -> conflict.kind().name()));

        return new Result(List.copyOf(differences), List.copyOf(conflicts));
    }

    // ------------------------------------------------------------------
    // Conflicts
    // ------------------------------------------------------------------

    private static void detectDuplicates(CatalogSnapshot snapshot, List<SyncConflict> conflicts) {
        duplicatesOf(snapshot.products().stream().map(CatalogSnapshot.Product::externalId).toList())
                .forEach(id -> conflicts.add(new SyncConflict(EntityType.PRODUCT, id,
                        SyncConflict.Kind.DUPLICATE_EXTERNAL_ID,
                        "The snapshot contains this product identifier more than once, so it is not a "
                                + "consistent read of the catalog. An offset walk over a catalog being "
                                + "edited returns a row twice as readily as it skips one.",
                        List.of())));

        duplicatesOf(snapshot.variants().stream().map(CatalogSnapshot.Variant::externalId).toList())
                .forEach(id -> conflicts.add(new SyncConflict(EntityType.VARIANT, id,
                        SyncConflict.Kind.DUPLICATE_EXTERNAL_ID,
                        "The snapshot contains this variant identifier more than once.", List.of())));
    }

    private static void detectMissingParents(CatalogSnapshot snapshot, List<SyncConflict> conflicts) {
        Set<String> productIds = snapshot.products().stream()
                .map(CatalogSnapshot.Product::externalId)
                .collect(java.util.stream.Collectors.toSet());

        for (CatalogSnapshot.Variant variant : snapshot.variants()) {
            if (!productIds.contains(variant.externalProductId())) {
                conflicts.add(new SyncConflict(EntityType.VARIANT, variant.externalId(),
                        SyncConflict.Kind.MISSING_PARENT,
                        // The likeliest cause is the pagination race, and saying so
                        // saves an operator from hunting for a deletion that did not
                        // happen.
                        "This variant's parent product %s is not in the snapshot. Either it was "
                                .formatted(variant.externalProductId())
                                + "deleted between our reads, or the paged walk skipped it.",
                        List.of(variant.externalProductId())));
            }
        }
    }

    /**
     * Modifier groups the provider's own model cannot carry.
     *
     * <p>Surfaced rather than dropped, which is ADR 0012's rule about lossy
     * provider models. A modifier that quietly disappears is a customer who
     * cannot order their coffee without sugar and a restaurant that never finds
     * out why.
     */
    private static void detectUnrepresentableModifiers(CatalogSnapshot snapshot,
            List<SyncConflict> conflicts) {

        Map<String, CatalogSnapshot.Product> byId = new LinkedHashMap<>();
        snapshot.products().forEach(product -> byId.put(product.externalId(), product));

        for (CatalogSnapshot.ModifierGroup group : snapshot.modifierGroups()) {
            CatalogSnapshot.Product owner = byId.get(group.externalProductId());
            if (owner == null) {
                conflicts.add(new SyncConflict(EntityType.MODIFIER_GROUP, group.externalId(),
                        SyncConflict.Kind.MISSING_PARENT,
                        "This modifier group's product %s is not in the snapshot."
                                .formatted(group.externalProductId()),
                        List.of(group.externalProductId())));
                continue;
            }
            if (owner.sourceKind() != SourceKind.DISH) {
                conflicts.add(new SyncConflict(EntityType.MODIFIER_GROUP, group.externalId(),
                        SyncConflict.Kind.UNREPRESENTABLE_STRUCTURE,
                        ("The provider attaches modifiers only to dishes, and product %s is a %s. "
                                + "Qoida can express this and the provider cannot, so the two catalogs "
                                + "cannot be kept equivalent for this group.")
                                .formatted(owner.externalId(), owner.sourceKind()),
                        List.of(owner.externalId())));
            }
        }
    }

    // ------------------------------------------------------------------
    // Differences
    // ------------------------------------------------------------------

    private void compareProducts(CatalogSnapshot snapshot, TargetCatalog target,
            Set<String> conflicted, List<SyncDifference> differences) {

        for (CatalogSnapshot.Product product : snapshot.comparableProducts()) {
            if (conflicted.contains(product.externalId())) {
                continue;
            }
            TargetCatalog.Entity mapped = target.find(EntityType.PRODUCT, product.externalId());
            if (mapped == null) {
                differences.add(addition(EntityType.PRODUCT, product.externalId(), product.name()));
                continue;
            }
            compareField(differences, EntityType.PRODUCT, product.externalId(), mapped,
                    "product.name", mapped.fields().get("product.name"), product.name());
            compareField(differences, EntityType.PRODUCT, product.externalId(), mapped,
                    "product.price", mapped.fields().get("product.price"), money(product.priceMinor()));
            compareField(differences, EntityType.PRODUCT, product.externalId(), mapped,
                    "product.sourceKind", mapped.fields().get("product.sourceKind"),
                    product.sourceKind().name());
            compareField(differences, EntityType.PRODUCT, product.externalId(), mapped,
                    "product.governmentCode", mapped.fields().get("product.governmentCode"),
                    product.governmentCode());
        }
    }

    private void compareVariants(CatalogSnapshot snapshot, TargetCatalog target,
            Set<String> conflicted, List<SyncDifference> differences) {

        for (CatalogSnapshot.Variant variant : snapshot.variants()) {
            if (conflicted.contains(variant.externalId())) {
                continue;
            }
            TargetCatalog.Entity mapped = target.find(EntityType.VARIANT, variant.externalId());
            if (mapped == null) {
                differences.add(addition(EntityType.VARIANT, variant.externalId(), variant.name()));
                continue;
            }
            compareField(differences, EntityType.VARIANT, variant.externalId(), mapped,
                    "variant.name", mapped.fields().get("variant.name"), variant.name());
            compareField(differences, EntityType.VARIANT, variant.externalId(), mapped,
                    "variant.price", mapped.fields().get("variant.price"), money(variant.priceMinor()));
        }
    }

    /**
     * Entities Qoida has mapped that the provider did not send.
     *
     * <p>The quorum is applied here rather than at apply time on purpose. An
     * inconclusive absence produces no {@code REMOVAL_SIGNAL} at all — not a
     * signal with a warning on it — because a queue item an operator must learn
     * to ignore is worse than no queue item.
     */
    private void detectRemovals(CatalogSnapshot snapshot, TargetCatalog target,
            AbsenceHistory absences, boolean walkStable, List<SyncDifference> differences) {

        Set<String> present = new HashSet<>();
        snapshot.products().forEach(product -> present.add(product.externalId()));
        Set<String> presentVariants = new HashSet<>();
        snapshot.variants().forEach(variant -> presentVariants.add(variant.externalId()));

        for (Map.Entry<String, TargetCatalog.Entity> entry
                : target.entities(EntityType.PRODUCT).entrySet()) {
            if (present.contains(entry.getKey())) {
                continue;
            }
            differences.add(removal(EntityType.PRODUCT, entry.getKey(), entry.getValue(),
                    absences.consecutiveAbsentRuns(EntityType.PRODUCT, entry.getKey()),
                    walkStable && absences.everyWalkStable(EntityType.PRODUCT, entry.getKey())));
        }

        for (Map.Entry<String, TargetCatalog.Entity> entry
                : target.entities(EntityType.VARIANT).entrySet()) {
            if (presentVariants.contains(entry.getKey())) {
                continue;
            }
            differences.add(removal(EntityType.VARIANT, entry.getKey(), entry.getValue(),
                    absences.consecutiveAbsentRuns(EntityType.VARIANT, entry.getKey()),
                    walkStable && absences.everyWalkStable(EntityType.VARIANT, entry.getKey())));
        }
    }

    private SyncDifference removal(EntityType type, String externalId, TargetCatalog.Entity mapped,
            int consecutiveAbsentRuns, boolean everyWalkStable) {

        boolean actionable = RemovalQuorum.actionable(consecutiveAbsentRuns, everyWalkStable);
        return new SyncDifference(
                type, externalId, mapped.qoidaId(),
                actionable ? DifferenceCategory.REMOVAL_SIGNAL : DifferenceCategory.NO_CHANGE,
                null,
                "present", "absent",
                FieldAuthority.MAPPING,
                actionable ? Severity.WARNING : Severity.INFO,
                // Even an actionable removal is only ever a review. ADR 0012
                // never physically deletes a Qoida product, so the strongest
                // approved action is suspending an offering or retiring a mapping.
                actionable ? RecommendedAction.REVIEW : RecommendedAction.IGNORE,
                actionable
                        ? "Absent from %d consecutive runs.".formatted(consecutiveAbsentRuns)
                        : RemovalQuorum.inconclusiveReason(consecutiveAbsentRuns));
    }

    private void compareField(List<SyncDifference> differences, EntityType type, String externalId,
            TargetCatalog.Entity mapped, String fieldPath, String current, String imported) {

        if (Objects.equals(normalise(current), normalise(imported))) {
            return;
        }
        FieldAuthority authority = policy.authorityOf(fieldPath);
        DifferenceCategory category = authority == FieldAuthority.QOIDA
                ? DifferenceCategory.PROTECTED_FIELD_CHANGE
                : DifferenceCategory.AUTHORIZED_CHANGE;

        differences.add(new SyncDifference(
                type, externalId, mapped.qoidaId(), category, fieldPath,
                current, imported, authority,
                // A protected field disagreeing is worth noticing and is not a
                // problem: it is usually a restaurant editing their own till,
                // which is exactly what they are entitled to do.
                category == DifferenceCategory.PROTECTED_FIELD_CHANGE ? Severity.INFO : Severity.INFO,
                policy.recommendationFor(fieldPath),
                category == DifferenceCategory.PROTECTED_FIELD_CHANGE
                        ? "Qoida owns this field. The provider's value is recorded and not applied."
                        : null));
    }

    private static SyncDifference addition(EntityType type, String externalId, String name) {
        return new SyncDifference(type, externalId, null, DifferenceCategory.ADDITION, null,
                null, name, FieldAuthority.MAPPING, Severity.INFO, RecommendedAction.REVIEW,
                // Draft, never live. A product created from an import has no
                // translation, no photograph and no reviewed price.
                "New at the provider. May be created as a DRAFT Qoida product with a proposed mapping.");
    }

    private static List<String> duplicatesOf(List<String> ids) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                duplicated.add(id);
            }
        }
        return List.copyOf(duplicated);
    }

    /** Null and blank are the same absence, and trailing space is not a change. */
    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String money(Long minor) {
        return minor == null ? null : Long.toString(minor);
    }

    /** Deterministic ordering, so two runs over one snapshot produce one report. */
    private static final Comparator<SyncDifference> ORDER = Comparator
            .comparing((SyncDifference difference) -> difference.entityType().name())
            .thenComparing(SyncDifference::externalEntityId)
            .thenComparing(difference -> difference.fieldPath() == null ? "" : difference.fieldPath())
            .thenComparing(difference -> difference.category().name());

    public record Result(List<SyncDifference> differences, List<SyncConflict> conflicts) {

        public long countOf(DifferenceCategory category) {
            return differences.stream().filter(d -> d.category() == category).count();
        }
    }

    /** What Qoida holds for the entities this binding has mapped. */
    public record TargetCatalog(Map<EntityType, Map<String, Entity>> byType) {

        public TargetCatalog {
            byType = Map.copyOf(byType == null ? Map.of() : byType);
        }

        public static TargetCatalog empty() {
            return new TargetCatalog(Map.of());
        }

        public Map<String, Entity> entities(EntityType type) {
            return byType.getOrDefault(type, Map.of());
        }

        public Entity find(EntityType type, String externalId) {
            return entities(type).get(externalId);
        }

        /**
         * @param version the optimistic version the comparison read. An apply
         *                carries it forward so a target somebody edited between
         *                comparison and apply returns to review instead of being
         *                overwritten
         */
        public record Entity(UUID qoidaId, int version, Map<String, String> fields) {

            public Entity {
                fields = Map.copyOf(fields == null ? Map.of() : fields);
            }
        }
    }

    /** How long each mapped entity has been missing, per entity type. */
    public record AbsenceHistory(Map<EntityType, Map<String, Streak>> byType) {

        public AbsenceHistory {
            byType = Map.copyOf(byType == null ? Map.of() : byType);
        }

        public static AbsenceHistory empty() {
            return new AbsenceHistory(Map.of());
        }

        /**
         * @return the streak <em>including</em> the run being compared, so a first
         *         absence answers 1 rather than 0
         */
        public int consecutiveAbsentRuns(EntityType type, String externalId) {
            Streak streak = byType.getOrDefault(type, Map.of()).get(externalId);
            return streak == null ? 1 : streak.runs() + 1;
        }

        public boolean everyWalkStable(EntityType type, String externalId) {
            Streak streak = byType.getOrDefault(type, Map.of()).get(externalId);
            return streak == null || streak.allWalksStable();
        }

        public record Streak(int runs, boolean allWalksStable) { }
    }
}
