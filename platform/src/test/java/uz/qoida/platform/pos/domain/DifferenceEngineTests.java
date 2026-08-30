package uz.qoida.platform.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.pos.domain.DifferenceEngine.AbsenceHistory;
import uz.qoida.platform.pos.domain.DifferenceEngine.TargetCatalog;
import uz.qoida.platform.pos.domain.SyncDifference.DifferenceCategory;
import uz.qoida.platform.pos.domain.SyncDifference.EntityType;
import uz.qoida.platform.pos.domain.SyncDifference.RecommendedAction;

/**
 * The comparison, which must be the same answer twice and must never hand a
 * provider authority over a menu (ADR 0012).
 */
class DifferenceEngineTests {

    private static final Instant NOW = Instant.parse("2026-08-23T04:00:00Z");
    private static final UUID PRODUCT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121501");

    private final DifferenceEngine engine = new DifferenceEngine(FieldAuthorityPolicy.INITIAL);

    @Test
    @DisplayName("an unmapped provider product is an addition, never a rename")
    void anUnmappedProductIsAnAddition() {
        var result = engine.compare(
                snapshot(List.of(product("41", "Lagman", SourceKind.DISH, 32000L)), List.of(), false),
                TargetCatalog.empty(), AbsenceHistory.empty());

        assertThat(result.differences()).singleElement()
                .satisfies(difference -> {
                    assertThat(difference.category()).isEqualTo(DifferenceCategory.ADDITION);
                    assertThat(difference.externalEntityId()).isEqualTo("41");
                    assertThat(difference.recommendedAction()).isEqualTo(RecommendedAction.REVIEW);
                });
    }

    @Test
    @DisplayName("a Qoida-owned field never produces an applicable action")
    void aCuratedNameIsRecordedAndNotApplied() {
        var target = new TargetCatalog(Map.of(EntityType.PRODUCT, Map.of(
                "41", new TargetCatalog.Entity(PRODUCT, 3,
                        Map.of("product.name", "Lagman, hand-pulled")))));

        var result = engine.compare(
                snapshot(List.of(product("41", "lagman", SourceKind.DISH, null)), List.of(), false),
                target, AbsenceHistory.empty());

        assertThat(result.differences()).anySatisfy(difference -> {
            assertThat(difference.fieldPath()).isEqualTo("product.name");
            assertThat(difference.category()).isEqualTo(DifferenceCategory.PROTECTED_FIELD_CHANGE);
            assertThat(difference.recommendedAction())
                    .as("a reviewer must not be offered a button that overwrites curated content")
                    .isEqualTo(RecommendedAction.IGNORE);
        });
    }

    @Test
    @DisplayName("a first absence on an offset walk is not a removal")
    void oneMissedReadIsInconclusive() {
        var target = new TargetCatalog(Map.of(EntityType.PRODUCT, Map.of(
                "41", new TargetCatalog.Entity(PRODUCT, 1, Map.of()))));

        var result = engine.compare(snapshot(List.of(), List.of(), false), target,
                AbsenceHistory.empty());

        assertThat(result.differences()).singleElement().satisfies(difference -> {
            assertThat(difference.category())
                    .as("offset pagination over a catalog being edited skips rows, and a skipped "
                            + "row is indistinguishable from a deleted one")
                    .isEqualTo(DifferenceCategory.NO_CHANGE);
            assertThat(difference.note()).contains("pages by offset");
        });
    }

    @Test
    @DisplayName("a second agreeing absence is a removal signal, and still only a review")
    void twoAgreeingRunsMakeARemovalActionable() {
        var target = new TargetCatalog(Map.of(EntityType.PRODUCT, Map.of(
                "41", new TargetCatalog.Entity(PRODUCT, 1, Map.of()))));
        var history = new AbsenceHistory(Map.of(EntityType.PRODUCT, Map.of(
                "41", new AbsenceHistory.Streak(1, false))));

        var result = engine.compare(snapshot(List.of(), List.of(), false), target, history);

        assertThat(result.differences()).singleElement().satisfies(difference -> {
            assertThat(difference.category()).isEqualTo(DifferenceCategory.REMOVAL_SIGNAL);
            assertThat(difference.recommendedAction())
                    .as("ADR 0012 never physically deletes on a removal signal")
                    .isEqualTo(RecommendedAction.REVIEW);
        });
    }

    @Test
    @DisplayName("a walk that cannot skip rows needs only one absence")
    void aStableWalkMakesOneAbsenceEvidence() {
        var target = new TargetCatalog(Map.of(EntityType.PRODUCT, Map.of(
                "41", new TargetCatalog.Entity(PRODUCT, 1, Map.of()))));

        var result = engine.compare(snapshot(List.of(), List.of(), true), target,
                AbsenceHistory.empty());

        assertThat(result.differences()).singleElement()
                .satisfies(difference -> assertThat(difference.category())
                        .isEqualTo(DifferenceCategory.REMOVAL_SIGNAL));
    }

    @Test
    @DisplayName("a duplicated identifier stops the entity rather than diffing it twice")
    void aDuplicateIsAConflict() {
        var result = engine.compare(
                snapshot(List.of(
                        product("41", "Lagman", SourceKind.DISH, 32000L),
                        product("41", "Lagman", SourceKind.DISH, 33000L)), List.of(), false),
                TargetCatalog.empty(), AbsenceHistory.empty());

        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.kind()).isEqualTo(SyncConflict.Kind.DUPLICATE_EXTERNAL_ID);
            assertThat(conflict.externalEntityId()).isEqualTo("41");
        });
        assertThat(result.differences())
                .as("a snapshot that is not a consistent read must not be diffed")
                .noneSatisfy(difference -> assertThat(difference.externalEntityId()).isEqualTo("41"));
    }

    @Test
    @DisplayName("a modifier on something that is not a dish surfaces instead of vanishing")
    void anUnrepresentableModifierIsAConflict() {
        var snapshot = new CatalogSnapshot(NOW, false, 1, List.of(),
                List.of(product("7", "Cola", SourceKind.GOODS, 9000L)), List.of(),
                List.of(new CatalogSnapshot.ModifierGroup("90", "7", "Ice", 0, 1, false, Map.of())),
                List.of(), List.of());

        var result = engine.compare(snapshot, TargetCatalog.empty(), AbsenceHistory.empty());

        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.kind()).isEqualTo(SyncConflict.Kind.UNREPRESENTABLE_STRUCTURE);
            assertThat(conflict.detail()).contains("only to dishes");
        });
    }

    @Test
    @DisplayName("an ingredient is never proposed as a customer-facing product")
    void inventoryKindsAreNotMenuCandidates() {
        var result = engine.compare(
                snapshot(List.of(product("3", "Test_Tomato", SourceKind.INGREDIENT, 0L)),
                        List.of(), false),
                TargetCatalog.empty(), AbsenceHistory.empty());

        assertThat(result.differences())
                .as("the provider returns ingredients from the same list as dishes")
                .isEmpty();
    }

    @Test
    @DisplayName("the same snapshot compared twice produces the same report in the same order")
    void comparisonIsDeterministic() {
        var snapshot = snapshot(List.of(
                product("9", "Somsa", SourceKind.DISH, 12000L),
                product("2", "Non", SourceKind.GOODS, 4000L),
                product("41", "Lagman", SourceKind.DISH, 32000L)), List.of(), false);

        var first = engine.compare(snapshot, TargetCatalog.empty(), AbsenceHistory.empty());
        var second = engine.compare(snapshot, TargetCatalog.empty(), AbsenceHistory.empty());

        assertThat(first.differences()).isEqualTo(second.differences());
        assertThat(first.differences())
                .extracting(SyncDifference::externalEntityId)
                .containsExactly("2", "41", "9");
    }

    @Test
    @DisplayName("a variant whose parent the walk missed is a conflict, not an orphan")
    void aMissingParentIsAConflict() {
        var snapshot = new CatalogSnapshot(NOW, false, 1, List.of(), List.of(),
                List.of(new CatalogSnapshot.Variant("410", "41", "Large", 36000L, "UZS", true,
                        "1", Map.of())),
                List.of(), List.of(), List.of());

        var result = engine.compare(snapshot, TargetCatalog.empty(), AbsenceHistory.empty());

        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict -> assertThat(conflict.kind())
                        .isEqualTo(SyncConflict.Kind.MISSING_PARENT));
    }

    private static CatalogSnapshot snapshot(List<CatalogSnapshot.Product> products,
            List<CatalogSnapshot.Variant> variants, boolean walkStable) {
        return new CatalogSnapshot(NOW, walkStable, 1, List.of(), products, variants,
                List.of(), List.of(), List.of());
    }

    private static CatalogSnapshot.Product product(String id, String name, SourceKind kind,
            Long priceMinor) {
        return new CatalogSnapshot.Product(id, name, "1", kind, kind.menuCandidate(), false,
                priceMinor, priceMinor == null ? null : "UZS", true, false, null, Map.of());
    }
}
