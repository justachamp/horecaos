package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.CatalogValidator.LocalizedText;
import uz.horecaos.platform.catalog.application.CatalogValidator.Snapshot;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.media.api.MediaAssetId;

/**
 * {@link CatalogValidator}'s own rules, on literals (ADR 0016).
 *
 * <p>The validator is a pure function over a loaded {@link Snapshot} — no
 * database, no clock, no service call — precisely so each rule can be tested by
 * constructing the one snapshot that should trip it and the one nearby snapshot
 * that should not. {@code CatalogPublicationTests} already proves the rules that
 * matter most end to end (missing translation, unsatisfiable modifier minimum,
 * unavailable media, no active variant); this class exists for the rules that
 * suite never reaches at all — the default-variant tie-break, the repeat-count
 * arithmetic in modifier capacity, multi-node category cycles, an offering or a
 * modifier link pointing at nothing — and for drawing, for each rule, the
 * boundary next to the one it enforces so a test that always finds the blocker
 * cannot be satisfied by a validator that always fires.
 */
class CatalogValidatorTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String LOCALE = "en";

    private final CatalogValidator validator = new CatalogValidator();

    // ---------------------------------------------------------- products

    @Test
    @DisplayName("a product with no ACTIVE variant is blocked, even if it has an archived one")
    void productWithOnlyArchivedVariantsIsBlocked() {
        UUID productId = UUID.randomUUID();
        Product product = new Product(productId, TENANT, BRAND, "ORPHAN", Status.ACTIVE, 1);
        Variant archived =
                new Variant(UUID.randomUUID(), TENANT, BRAND, productId, "SKU", "PIECE", true, 0, Status.ARCHIVED, 1);

        // A validator that checked "has any variant at all" rather than "has an
        // active one" would let this publish a product nothing can add to a
        // basket.
        Snapshot snapshot = snapshotOf(
                List.of(product),
                List.of(archived),
                List.of(),
                List.of(),
                Map.of(),
                nameIn(EntityType.PRODUCT, productId, LOCALE, "Orphan"),
                Set.of(),
                Set.of());

        ValidationFinding.Report report = validator.validate(snapshot);

        assertThat(report.blockers()).extracting(ValidationFinding::code).contains("PRODUCT_HAS_NO_ACTIVE_VARIANT");
    }

    @Test
    @DisplayName("several variants with no default is blocked; exactly one variant with no default is not")
    void defaultVariantIsRequiredOnlyWhenThereIsAChoiceToMake() {
        UUID productId = UUID.randomUUID();
        Product product = new Product(productId, TENANT, BRAND, "SIZES", Status.ACTIVE, 1);
        Map<String, LocalizedText> names = nameIn(EntityType.PRODUCT, productId, LOCALE, "Sizes");

        Variant small =
                new Variant(UUID.randomUUID(), TENANT, BRAND, productId, "S", "PIECE", false, 0, Status.ACTIVE, 1);
        Variant large =
                new Variant(UUID.randomUUID(), TENANT, BRAND, productId, "L", "PIECE", false, 1, Status.ACTIVE, 1);

        Snapshot twoVariantsNoDefault = snapshotOf(
                List.of(product),
                List.of(small, large),
                List.of(),
                List.of(),
                Map.of(),
                names,
                Set.of(small.id(), large.id()),
                Set.of());

        assertThat(validator.validate(twoVariantsNoDefault).blockers())
                .extracting(ValidationFinding::code)
                .contains("PRODUCT_HAS_NO_DEFAULT_VARIANT");

        // The storefront needs no basis for choosing what a single tap adds when
        // there is only one thing to add. A validator that dropped the
        // "variants.size() > 1" half of the rule would fail this half of the test.
        Variant only =
                new Variant(UUID.randomUUID(), TENANT, BRAND, productId, "ONE", "PIECE", false, 0, Status.ACTIVE, 1);
        Snapshot oneVariantNoDefault = snapshotOf(
                List.of(product), List.of(only), List.of(), List.of(), Map.of(), names, Set.of(only.id()), Set.of());

        assertThat(validator.validate(oneVariantNoDefault).blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("PRODUCT_HAS_NO_DEFAULT_VARIANT");
    }

    // ---------------------------------------------------------- variants / pricing

    @Test
    @DisplayName("an active variant with no price is blocked; an archived one with no price is not even checked")
    void onlyActiveVariantsAreCheckedForAPrice() {
        UUID productId = UUID.randomUUID();
        Variant priced =
                new Variant(UUID.randomUUID(), TENANT, BRAND, productId, "PRICED", "PIECE", true, 0, Status.ACTIVE, 1);
        Variant unpriced = new Variant(
                UUID.randomUUID(), TENANT, BRAND, productId, "UNPRICED", "PIECE", false, 1, Status.ACTIVE, 1);
        Variant archivedUnpriced = new Variant(
                UUID.randomUUID(), TENANT, BRAND, productId, "GONE", "PIECE", false, 2, Status.ARCHIVED, 1);

        Snapshot snapshot = snapshotOf(
                List.of(),
                List.of(priced, unpriced, archivedUnpriced),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                // Only "priced" is in the priced set; "unpriced" and
                // "archivedUnpriced" both are absent from it.
                Set.of(priced.id()),
                Set.of());

        List<ValidationFinding> blockers = validator.validate(snapshot).blockers();

        assertThat(blockers)
                .filteredOn(finding -> finding.code().equals("VARIANT_HAS_NO_ACTIVE_PRICE"))
                .extracting(ValidationFinding::entityId)
                // The archived variant is absent from pricing too, but it must
                // not be reported: a rule that iterated every variant rather than
                // only the active ones would fail this assertion by naming it.
                .containsExactly(unpriced.id());
    }

    // ---------------------------------------------------------- modifier groups

    @Test
    @DisplayName("an active modifier group with no active options is blocked")
    void modifierGroupWithNoActiveOptionsIsBlocked() {
        UUID groupId = UUID.randomUUID();
        ModifierGroup group =
                new ModifierGroup(groupId, TENANT, BRAND, "SAUCES", false, 0, 1, false, 0, Status.ACTIVE, 1);
        ModifierOption archivedOption = new ModifierOption(
                UUID.randomUUID(), TENANT, BRAND, groupId, "OLD", null, 1, 0, Status.ARCHIVED, 1);

        Snapshot snapshot = snapshotOf(
                List.of(),
                List.of(),
                List.of(),
                List.of(group),
                Map.of(groupId, List.of(archivedOption)),
                Map.of(),
                Set.of(),
                Set.of());

        assertThat(validator.validate(snapshot).blockers())
                .extracting(ValidationFinding::code)
                .contains("MODIFIER_GROUP_HAS_NO_OPTIONS");
    }

    @Test
    @DisplayName("when repeats are allowed, selectable capacity is the sum of quantities, not the option count")
    void repeatCountsTowardsSelectableCapacityOnlyWhenAllowed() {
        UUID groupId = UUID.randomUUID();
        // One option, worth three selections if repeats are allowed.
        ModifierOption option =
                new ModifierOption(UUID.randomUUID(), TENANT, BRAND, groupId, "EXTRA_CHEESE", null, 3, 0, Status.ACTIVE, 1);

        ModifierGroup capacityInsufficientEvenWithRepeats =
                new ModifierGroup(groupId, TENANT, BRAND, "CHEESE", true, 4, 4, true, 0, Status.ACTIVE, 1);
        Snapshot insufficient = snapshotOf(
                List.of(),
                List.of(),
                List.of(),
                List.of(capacityInsufficientEvenWithRepeats),
                Map.of(groupId, List.of(option)),
                Map.of(),
                Set.of(),
                Set.of());

        assertThat(validator.validate(insufficient).blockers())
                .extracting(ValidationFinding::code)
                .contains("MODIFIER_GROUP_MINIMUM_UNSATISFIABLE");

        // Requiring exactly three is satisfiable by one option repeated three
        // times. A validator that used options.size() instead of the summed
        // quantity would report this one as unsatisfiable too (1 option < 3).
        ModifierGroup capacityExactlySufficientWithRepeats =
                new ModifierGroup(groupId, TENANT, BRAND, "CHEESE", true, 3, 3, true, 0, Status.ACTIVE, 1);
        Snapshot sufficient = snapshotOf(
                List.of(),
                List.of(),
                List.of(),
                List.of(capacityExactlySufficientWithRepeats),
                Map.of(groupId, List.of(option)),
                Map.of(),
                Set.of(),
                Set.of());

        assertThat(validator.validate(sufficient).blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("MODIFIER_GROUP_MINIMUM_UNSATISFIABLE");
    }

    @Test
    @DisplayName("a modifier option linked to an archived variant is blocked")
    void modifierOptionLinkedToAnArchivedVariantIsBlocked() {
        UUID groupId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Variant archivedVariant = new Variant(
                UUID.randomUUID(), TENANT, BRAND, productId, "GONE", "PIECE", true, 0, Status.ARCHIVED, 1);
        ModifierGroup group = new ModifierGroup(groupId, TENANT, BRAND, "ADDONS", false, 0, 1, false, 0, Status.ACTIVE, 1);
        ModifierOption linkedToArchived = new ModifierOption(
                UUID.randomUUID(), TENANT, BRAND, groupId, "ADD_GONE", archivedVariant.id(), 1, 0, Status.ACTIVE, 1);

        Snapshot snapshot = snapshotOf(
                List.of(),
                List.of(archivedVariant),
                List.of(),
                List.of(group),
                Map.of(groupId, List.of(linkedToArchived)),
                Map.of(),
                Set.of(),
                Set.of());

        assertThat(validator.validate(snapshot).blockers())
                .extracting(ValidationFinding::code)
                .contains("MODIFIER_OPTION_LINKS_INACTIVE_VARIANT");

        // The same link to an ACTIVE variant must not be reported, or the rule
        // would be "this modifier links a variant" rather than "links an
        // inactive one".
        Variant activeVariant =
                new Variant(UUID.randomUUID(), TENANT, BRAND, productId, "HERE", "PIECE", true, 0, Status.ACTIVE, 1);
        ModifierOption linkedToActive = new ModifierOption(
                UUID.randomUUID(), TENANT, BRAND, groupId, "ADD_HERE", activeVariant.id(), 1, 0, Status.ACTIVE, 1);
        Snapshot healthy = snapshotOf(
                List.of(),
                List.of(activeVariant),
                List.of(),
                List.of(group),
                Map.of(groupId, List.of(linkedToActive)),
                Map.of(),
                Set.of(),
                Set.of());

        assertThat(validator.validate(healthy).blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("MODIFIER_OPTION_LINKS_INACTIVE_VARIANT");
    }

    // ---------------------------------------------------------- category tree

    @Test
    @DisplayName("a three-node category cycle is caught from every node in it, not just the one it starts from")
    void categoryTreeCycleIsDetectedFromEveryNodeInTheLoop() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID catalogId = UUID.randomUUID();
        // a -> c -> b -> a: nothing in this loop is its own parent, so the
        // database's single-level check on parent_category_id never sees it.
        Category catA = new Category(a, TENANT, BRAND, catalogId, c, "A", 0, Status.ACTIVE, 1);
        Category catB = new Category(b, TENANT, BRAND, catalogId, a, "B", 1, Status.ACTIVE, 1);
        Category catC = new Category(c, TENANT, BRAND, catalogId, b, "C", 2, Status.ACTIVE, 1);

        Snapshot snapshot =
                snapshotOf(List.of(), List.of(), List.of(catA, catB, catC), List.of(), Map.of(), Map.of(), Set.of(), Set.of());

        List<ValidationFinding> cycleFindings = validator.validate(snapshot).blockers().stream()
                .filter(finding -> finding.code().equals("CATEGORY_TREE_HAS_CYCLE"))
                .toList();

        // Each of the three categories starts its own walk to the root, so a
        // validator that only checked the first category it iterated would leave
        // this at 1 rather than 3.
        assertThat(cycleFindings).hasSize(3);
        assertThat(cycleFindings).extracting(ValidationFinding::entityId).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    @DisplayName("a category naming a parent absent from the catalog is blocked, not silently treated as a root")
    void categoryWithAMissingParentIsBlocked() {
        UUID catalogId = UUID.randomUUID();
        UUID orphanParent = UUID.randomUUID();
        Category category =
                new Category(UUID.randomUUID(), TENANT, BRAND, catalogId, orphanParent, "LOST", 0, Status.ACTIVE, 1);

        Snapshot snapshot =
                snapshotOf(List.of(), List.of(), List.of(category), List.of(), Map.of(), Map.of(), Set.of(), Set.of());

        assertThat(validator.validate(snapshot).blockers())
                .extracting(ValidationFinding::code)
                .contains("CATEGORY_PARENT_MISSING");
    }

    // ---------------------------------------------------------- translations

    @Test
    @DisplayName("a name in another locale does not satisfy the brand default locale requirement")
    void translationMustExistInTheBrandDefaultLocaleSpecifically() {
        UUID productId = UUID.randomUUID();
        Product product = new Product(productId, TENANT, BRAND, "RU_ONLY", Status.ACTIVE, 1);
        Variant variant =
                new Variant(UUID.randomUUID(), TENANT, BRAND, productId, "SKU", "PIECE", true, 0, Status.ACTIVE, 1);

        // Named in Russian; the snapshot's default locale is "en". A validator
        // that checked hasTranslation(type, id, ANY locale) would miss this.
        Snapshot missingDefault = snapshotOf(
                List.of(product),
                List.of(variant),
                List.of(),
                List.of(),
                Map.of(),
                nameIn(EntityType.PRODUCT, productId, "ru", "Только по-русски"),
                Set.of(variant.id()),
                Set.of());

        assertThat(validator.validate(missingDefault).blockers())
                .extracting(ValidationFinding::code)
                .contains("MISSING_TRANSLATION");

        Snapshot hasDefault = snapshotOf(
                List.of(product),
                List.of(variant),
                List.of(),
                List.of(),
                Map.of(),
                nameIn(EntityType.PRODUCT, productId, LOCALE, "English name"),
                Set.of(variant.id()),
                Set.of());

        assertThat(validator.validate(hasDefault).blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("MISSING_TRANSLATION");
    }

    // ---------------------------------------------------------- media

    @Test
    @DisplayName("a referenced media asset that is not displayable blocks; the same asset marked displayable does not")
    void mediaMustBeDisplayableToPublish() {
        UUID productId = UUID.randomUUID();
        MediaAssetId asset = MediaAssetId.generate();
        Map<MediaAssetId, Set<UUID>> reference = Map.of(asset, Set.of(productId));

        Snapshot notDisplayable = snapshotOf(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                reference,
                Set.of(),
                Set.of(),
                Set.of(),
                true);

        assertThat(validator.validate(notDisplayable).blockers())
                .extracting(ValidationFinding::code)
                .contains("MEDIA_NOT_AVAILABLE");

        Snapshot displayable = snapshotOf(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                reference,
                Set.of(asset),
                Set.of(),
                Set.of(),
                true);

        // A validator that flagged every reference regardless of displayability
        // would fail this half — the point of the rule is the distinction.
        assertThat(validator.validate(displayable).blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("MEDIA_NOT_AVAILABLE");
    }

    // ---------------------------------------------------------- offerings

    @Test
    @DisplayName("an offering naming a variant that is not active in this brand is blocked")
    void offeringReferencingAnUnknownVariantIsBlocked() {
        UUID strayVariantId = UUID.randomUUID();

        Snapshot snapshot =
                snapshotOf(List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), Set.of(), Set.of(strayVariantId));

        assertThat(validator.validate(snapshot).blockers())
                .extracting(ValidationFinding::code)
                .contains("OFFERING_REFERENCES_UNKNOWN_VARIANT");

        // An offering naming a variant that genuinely is active in this brand
        // must not be reported, or the rule would fire on every offering.
        UUID productId = UUID.randomUUID();
        Variant realVariant =
                new Variant(UUID.randomUUID(), TENANT, BRAND, productId, "REAL", "PIECE", true, 0, Status.ACTIVE, 1);
        Snapshot healthy = snapshotOf(
                List.of(),
                List.of(realVariant),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of(realVariant.id()));

        assertThat(validator.validate(healthy).blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("OFFERING_REFERENCES_UNKNOWN_VARIANT");
    }

    // ---------------------------------------------------------- pricing wiring

    @Test
    @DisplayName("an unwired pricing port is reported as a warning, never as a blocker")
    void unwiredPricingIsAWarningOnly() {
        Snapshot unwired = snapshotOf(
                List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), false);

        ValidationFinding.Report report = validator.validate(unwired);
        assertThat(report.findings()).extracting(ValidationFinding::code).contains("PRICING_VALIDATION_NOT_WIRED");
        assertThat(report.blockers()).extracting(ValidationFinding::code).doesNotContain("PRICING_VALIDATION_NOT_WIRED");

        Snapshot wired = snapshotOf(
                List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), true);

        assertThat(validator.validate(wired).findings())
                .extracting(ValidationFinding::code)
                .doesNotContain("PRICING_VALIDATION_NOT_WIRED");
    }

    // ---------------------------------------------------------- fixtures

    private static Map<String, LocalizedText> nameIn(EntityType type, UUID entityId, String locale, String name) {
        return Map.of(Snapshot.translationKey(type, entityId, locale), new LocalizedText(locale, name, null));
    }

    private static Snapshot snapshotOf(
            List<Product> products,
            List<Variant> variants,
            List<Category> categories,
            List<ModifierGroup> groups,
            Map<UUID, List<ModifierOption>> optionsByGroup,
            Map<String, LocalizedText> translations,
            Set<UUID> pricedVariantIds,
            Set<UUID> offeredVariantIds) {
        return snapshotOf(
                products,
                variants,
                categories,
                groups,
                optionsByGroup,
                translations,
                Map.of(),
                Set.of(),
                pricedVariantIds,
                offeredVariantIds,
                true);
    }

    private static Snapshot snapshotOf(
            List<Product> products,
            List<Variant> variants,
            List<Category> categories,
            List<ModifierGroup> groups,
            Map<UUID, List<ModifierOption>> optionsByGroup,
            Map<String, LocalizedText> translations,
            Map<MediaAssetId, Set<UUID>> mediaReferences,
            Set<MediaAssetId> displayableMedia,
            Set<UUID> pricedVariantIds,
            Set<UUID> offeredVariantIds,
            boolean pricingWired) {

        Map<UUID, List<Variant>> variantsByProduct = variants.stream().collect(Collectors.groupingBy(Variant::productId));
        Map<UUID, Category> categoriesById = new LinkedHashMap<>();
        categories.forEach(category -> categoriesById.put(category.id(), category));

        return new Snapshot(
                LOCALE,
                products,
                variants,
                variantsByProduct,
                categories,
                categoriesById,
                Map.of(),
                Map.of(),
                groups,
                optionsByGroup,
                translations,
                mediaReferences,
                displayableMedia,
                pricedVariantIds,
                offeredVariantIds,
                CatalogValidator.FiscalContext.empty(),
                pricingWired);
    }
}
