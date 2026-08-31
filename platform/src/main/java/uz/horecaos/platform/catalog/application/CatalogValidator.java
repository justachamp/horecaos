package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Fee;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.media.api.MediaAssetId;

/**
 * Decides whether a draft is fit to show a customer (ADR 0016).
 *
 * <p>A pure function over a loaded snapshot: no database, no clock, no service
 * calls. That is what makes every rule below testable in isolation, and it is
 * why the snapshot is assembled by the publication service rather than fetched
 * here.
 *
 * <p>The rules exist because each one, unenforced, produces a specific customer
 * experience: a product that cannot be ordered, a choice that cannot be
 * completed, a name that is a database code, or a picture that does not load.
 */
@Component
public class CatalogValidator {

    public ValidationFinding.Report validate(Snapshot snapshot) {
        List<ValidationFinding> findings = new ArrayList<>();

        if (!snapshot.pricingWired()) {
            // Surfaced on every report rather than only in a startup log, so an
            // operator reading "publication succeeded" also reads that one of
            // its checks did not actually run.
            findings.add(ValidationFinding.warning(
                    "PRICING_VALIDATION_NOT_WIRED",
                    EntityType.CATALOG,
                    null,
                    null,
                    "No pricing module is wired; variants were not checked for an active price"));
        }

        validateProducts(snapshot, findings);
        validateVariants(snapshot, findings);
        validateModifierGroups(snapshot, findings);
        validateCategoryTree(snapshot, findings);
        validateTranslations(snapshot, findings);
        validateMedia(snapshot, findings);
        validateOfferings(snapshot, findings);
        validateFiscalClassification(snapshot, findings);

        return new ValidationFinding.Report(List.copyOf(findings));
    }

    /**
     * Fiscal classification coverage over every node that reaches a receipt
     * (ADR 0038).
     *
     * <p>Warnings, not blockers, and still deliberately so. ADR 0038 is now
     * Accepted and its rules are specified as blockers, but its own rollout puts
     * them behind stage 3 — "validator rules enabled per brand once its coverage
     * report is clean. A brand is not published against a wall it has not been
     * given tools to pass." The bulk assignment tooling and the ИКПУ/MXIK
     * reference import that stage 2 owes are not built, and the reference table
     * V0028 creates is empty. Turning the wall on before the tools exist would
     * stop every brand in the system from publishing anything, including the
     * changes that have nothing to do with tax. V0021's reasoning holds for the
     * same reason it held then: staying silent would be worse, because an
     * operator would read "publication succeeded" over a menu aggregators reject
     * and receipts cannot classify.
     *
     * <p>What has changed since V0021 is what a finding says. "Unclassified" is
     * not something a brand with four hundred dishes can act on; which of the
     * four required fields is missing is one field to fill in. All four are
     * required by the provider contracts — Click cannot build a line without a
     * unit code, because it demands the unit inside a 63-character {@code Name},
     * and both providers mark the package code required — so reporting only the
     * ИКПУ, as V0021 did, understated the gap by half.
     */
    private void validateFiscalClassification(Snapshot snapshot, List<ValidationFinding> findings) {
        int incomplete = 0;

        for (Variant variant : snapshot.variants()) {
            if (variant.status() != Status.ACTIVE) {
                continue;
            }
            FiscalClassification fiscal = snapshot.effectiveClassification(variant);
            if (!fiscal.isComplete()) {
                incomplete++;
                findings.add(ValidationFinding.warning(
                        "FISCAL_CLASSIFICATION_MISSING",
                        EntityType.VARIANT,
                        variant.id(),
                        variant.sku(),
                        describeGap(fiscal, "this variant")));
            }
            reportUnknownCode(snapshot, findings, fiscal, EntityType.VARIANT, variant.id(), variant.sku());
        }

        for (ModifierGroup group : snapshot.modifierGroups()) {
            if (group.status() != Status.ACTIVE) {
                continue;
            }
            for (ModifierOption option : snapshot.optionsByGroup().getOrDefault(group.id(), List.of())) {
                if (option.status() != Status.ACTIVE) {
                    continue;
                }
                FiscalClassification fiscal = snapshot.effectiveClassification(option);
                if (!fiscal.isComplete()) {
                    incomplete++;
                    findings.add(ValidationFinding.warning(
                            "FISCAL_CLASSIFICATION_MISSING",
                            EntityType.MODIFIER_OPTION,
                            option.id(),
                            option.code(),
                            describeGap(
                                    fiscal,
                                    option.linkedVariantId() == null
                                            ? "this modifier option"
                                            : "this modifier option or the variant it links to")));
                }
                reportUnknownCode(snapshot, findings, fiscal, EntityType.MODIFIER_OPTION, option.id(), option.code());
            }
        }

        incomplete += validateDeliveryFee(snapshot, findings);

        if (incomplete > 0) {
            // One catalog-level finding naming the whole gap, so an operator
            // reading a report full of per-node warnings also reads why none of
            // them stopped the publication.
            findings.add(ValidationFinding.warning(
                    "FISCAL_CLASSIFICATION_NOT_ENFORCED",
                    EntityType.CATALOG,
                    null,
                    null,
                    ("%d priceable nodes cannot yet produce a conformant receipt line. "
                                    + "ADR 0038 makes this a publication blocker at rollout stage 3, "
                                    + "once bulk classification tooling and the ИКПУ/MXIK reference "
                                    + "import exist; until then it is reported and not enforced. "
                                    + "Aggregators already reject menus without these codes, and on "
                                    + "the Payme path there is no later checkpoint — the line data is "
                                    + "fixed before the customer pays.")
                            .formatted(incomplete)));
        }
    }

    /**
     * The delivery fee is a receipt line and needs a code like any other
     * (ADR 0038).
     *
     * <p>It is reported only for a brand that actually delivers. A brand selling
     * pickup only is not missing a delivery fee classification; it is not
     * charging one.
     *
     * <p>The fee cannot ride in Payme's {@code shipping} block, which accepts a
     * title and a price and carries no ИКПУ, no package code and no VAT percent.
     * It goes out as an ordinary {@code items} entry, which is why it needs the
     * same four fields as a dish — and why an unclassified fee is a gap rather
     * than a detail.
     */
    private int validateDeliveryFee(Snapshot snapshot, List<ValidationFinding> findings) {
        if (!snapshot.fiscal().brandOffersDelivery()) {
            return 0;
        }

        Fee delivery = snapshot.fiscal().fees().stream()
                .filter(fee -> Fee.DELIVERY.equals(fee.code()))
                .filter(fee -> fee.status() != Status.ARCHIVED)
                .findFirst()
                .orElse(null);

        if (delivery == null) {
            // V0028 seeds one per brand and the authoring service creates one on
            // demand, so this is a brand created between the two.
            findings.add(ValidationFinding.warning(
                    "FISCAL_DELIVERY_FEE_UNCLASSIFIED",
                    EntityType.FEE,
                    null,
                    Fee.DELIVERY,
                    "This brand delivers but has no delivery fee node to classify"));
            return 1;
        }

        FiscalClassification fiscal =
                snapshot.fiscal().byNode().getOrDefault(delivery.id(), FiscalClassification.unclassified());
        if (fiscal.isComplete()) {
            return 0;
        }

        findings.add(ValidationFinding.warning(
                "FISCAL_DELIVERY_FEE_UNCLASSIFIED",
                EntityType.FEE,
                delivery.id(),
                delivery.code(),
                describeGap(fiscal, "the delivery fee")));
        return 1;
    }

    /**
     * A code the official list does not contain (ADR 0038).
     *
     * <p>Reported only once the reference has actually been imported. An empty
     * reference means nobody has run the import, and calling every code in the
     * catalog unknown would bury the findings that mean something under one that
     * means the import is outstanding.
     */
    private void reportUnknownCode(
            Snapshot snapshot,
            List<ValidationFinding> findings,
            FiscalClassification fiscal,
            EntityType type,
            UUID entityId,
            @Nullable String entityCode) {

        if (!snapshot.fiscal().referenceLoaded() || fiscal.mxikCode() == null) {
            return;
        }
        if (!snapshot.fiscal().referenceCodes().contains(fiscal.mxikCode())) {
            findings.add(ValidationFinding.warning(
                    "FISCAL_MXIK_CODE_UNKNOWN",
                    type,
                    entityId,
                    entityCode,
                    "ИКПУ/MXIK %s is not in the imported reference list. A code the tax ".formatted(fiscal.mxikCode())
                            + "authority does not recognise is a classification error on a "
                            + "legal document, and it is most often a transcription slip"));
        }
    }

    /** Names the missing fields rather than the fact that some are missing. */
    private static String describeGap(FiscalClassification fiscal, String subject) {
        if (fiscal.isEmpty()) {
            return "Nothing is classified on %s: it needs %s"
                    .formatted(
                            subject,
                            String.join(
                                    ", ", FiscalClassification.unclassified().missingFields()));
        }
        return "%s is missing %s"
                .formatted(
                        Character.toUpperCase(subject.charAt(0)) + subject.substring(1),
                        String.join(", ", fiscal.missingFields()));
    }

    private void validateProducts(Snapshot snapshot, List<ValidationFinding> findings) {
        for (Product product : snapshot.products()) {
            List<Variant> variants = snapshot.variantsByProduct().getOrDefault(product.id(), List.of()).stream()
                    .filter(variant -> variant.status() == Status.ACTIVE)
                    .toList();

            if (variants.isEmpty()) {
                // A product with no sellable variant renders as a menu entry that
                // cannot be added to a basket.
                findings.add(ValidationFinding.blocker(
                        "PRODUCT_HAS_NO_ACTIVE_VARIANT",
                        EntityType.PRODUCT,
                        product.id(),
                        product.code(),
                        "A product must have at least one active variant to be published"));
                continue;
            }

            long defaults = variants.stream().filter(Variant::isDefault).count();
            if (defaults == 0 && variants.size() > 1) {
                // With several variants and no default, the storefront has no
                // basis for choosing what a single tap adds.
                findings.add(ValidationFinding.blocker(
                        "PRODUCT_HAS_NO_DEFAULT_VARIANT",
                        EntityType.PRODUCT,
                        product.id(),
                        product.code(),
                        "A product with several variants must mark one as the default"));
            }
        }
    }

    private void validateVariants(Snapshot snapshot, List<ValidationFinding> findings) {
        for (Variant variant : snapshot.variants()) {
            if (variant.status() != Status.ACTIVE) {
                continue;
            }
            // Pricing owns money, but a variant with no price cannot be sold, so
            // the absence is a catalog blocker even though the fact is not ours.
            if (!snapshot.pricedVariantIds().contains(variant.id())) {
                findings.add(ValidationFinding.blocker(
                        "VARIANT_HAS_NO_ACTIVE_PRICE",
                        EntityType.VARIANT,
                        variant.id(),
                        variant.sku(),
                        "No active price exists for this variant"));
            }
        }
    }

    private void validateModifierGroups(Snapshot snapshot, List<ValidationFinding> findings) {
        for (ModifierGroup group : snapshot.modifierGroups()) {
            if (group.status() != Status.ACTIVE) {
                continue;
            }
            List<ModifierOption> options = snapshot.optionsByGroup().getOrDefault(group.id(), List.of()).stream()
                    .filter(option -> option.status() == Status.ACTIVE)
                    .toList();

            if (options.isEmpty()) {
                findings.add(ValidationFinding.blocker(
                        "MODIFIER_GROUP_HAS_NO_OPTIONS",
                        EntityType.MODIFIER_GROUP,
                        group.id(),
                        group.code(),
                        "An active modifier group must offer at least one active option"));
                continue;
            }

            // The database constrains minimum <= maximum, but not against the
            // number of options that actually exist. "Choose 3 of 2" passes the
            // check constraint and still traps the customer at checkout.
            int selectableCapacity = group.allowSameOptionMultipleTimes()
                    ? options.stream().mapToInt(ModifierOption::maximumQuantity).sum()
                    : options.size();

            if (group.minimumSelections() > selectableCapacity) {
                findings.add(ValidationFinding.blocker(
                        "MODIFIER_GROUP_MINIMUM_UNSATISFIABLE",
                        EntityType.MODIFIER_GROUP,
                        group.id(),
                        group.code(),
                        "Requires %d selections but only %d are available"
                                .formatted(group.minimumSelections(), selectableCapacity)));
            }

            for (ModifierOption option : options) {
                UUID linked = option.linkedVariantId();
                if (linked != null && !snapshot.activeVariantIds().contains(linked)) {
                    // A modifier pointing at an archived variant adds an item to
                    // the basket that no longer exists.
                    findings.add(ValidationFinding.blocker(
                            "MODIFIER_OPTION_LINKS_INACTIVE_VARIANT",
                            EntityType.MODIFIER_OPTION,
                            option.id(),
                            option.code(),
                            "Linked variant " + linked + " is not active"));
                }
            }
        }
    }

    /**
     * Walks each category to its root.
     *
     * <p>The database forbids a category being its own parent, but not a longer
     * cycle. An A→B→A loop would make a menu renderer recurse until it ran out
     * of stack, so the whole path is walked rather than only the first step.
     */
    private void validateCategoryTree(Snapshot snapshot, List<ValidationFinding> findings) {
        Map<UUID, Category> byId = snapshot.categoriesById();

        for (Category category : snapshot.categories()) {
            Set<UUID> seen = new LinkedHashSet<>();
            UUID current = category.id();

            while (current != null) {
                if (!seen.add(current)) {
                    findings.add(ValidationFinding.blocker(
                            "CATEGORY_TREE_HAS_CYCLE",
                            EntityType.CATEGORY,
                            category.id(),
                            category.code(),
                            "Category ancestry forms a cycle: " + seen));
                    break;
                }
                Category node = byId.get(current);
                if (node == null) {
                    findings.add(ValidationFinding.blocker(
                            "CATEGORY_PARENT_MISSING",
                            EntityType.CATEGORY,
                            category.id(),
                            category.code(),
                            "Ancestor " + current + " is not in this catalog"));
                    break;
                }
                current = node.parentCategoryId();
            }
        }
    }

    /**
     * Every published entity needs a name in the brand's default locale.
     *
     * <p>Without this the storefront falls back to a code, and a customer is
     * shown something like {@code BURG-DBL-01} where a dish name should be.
     */
    private void validateTranslations(Snapshot snapshot, List<ValidationFinding> findings) {
        String locale = snapshot.defaultLocale();

        for (Product product : snapshot.products()) {
            if (product.status() == Status.ACTIVE
                    && !snapshot.hasTranslation(EntityType.PRODUCT, product.id(), locale)) {
                findings.add(ValidationFinding.blocker(
                        "MISSING_TRANSLATION",
                        EntityType.PRODUCT,
                        product.id(),
                        product.code(),
                        "No name in the brand default locale " + locale));
            }
        }
        for (Category category : snapshot.categories()) {
            if (category.status() == Status.ACTIVE
                    && !snapshot.hasTranslation(EntityType.CATEGORY, category.id(), locale)) {
                findings.add(ValidationFinding.blocker(
                        "MISSING_TRANSLATION",
                        EntityType.CATEGORY,
                        category.id(),
                        category.code(),
                        "No name in the brand default locale " + locale));
            }
        }
        for (ModifierGroup group : snapshot.modifierGroups()) {
            if (group.status() == Status.ACTIVE
                    && !snapshot.hasTranslation(EntityType.MODIFIER_GROUP, group.id(), locale)) {
                findings.add(ValidationFinding.blocker(
                        "MISSING_TRANSLATION",
                        EntityType.MODIFIER_GROUP,
                        group.id(),
                        group.code(),
                        "No name in the brand default locale " + locale));
            }
        }
    }

    /**
     * Referenced images must already be verified (ADR 0010).
     *
     * <p>Publishing a reference to a pending upload produces a live menu of
     * broken images, and the publication is immutable, so it cannot quietly fix
     * itself when the upload finishes.
     */
    private void validateMedia(Snapshot snapshot, List<ValidationFinding> findings) {
        for (Map.Entry<MediaAssetId, Set<UUID>> entry :
                snapshot.mediaReferences().entrySet()) {
            if (!snapshot.displayableMedia().contains(entry.getKey())) {
                findings.add(ValidationFinding.blocker(
                        "MEDIA_NOT_AVAILABLE",
                        EntityType.PRODUCT,
                        entry.getValue().iterator().next(),
                        null,
                        "Media asset %s is not available".formatted(entry.getKey())));
            }
        }
    }

    /**
     * A location may only offer its own brand's variants.
     *
     * <p>The composite foreign key already enforces this, so a finding here means
     * data arrived by some path that bypassed it — a migration, most likely.
     * Reported as a blocker rather than trusted away.
     */
    private void validateOfferings(Snapshot snapshot, List<ValidationFinding> findings) {
        Set<UUID> known = new HashSet<>(snapshot.activeVariantIds());
        snapshot.offeredVariantIds().stream()
                .filter(variantId -> !known.contains(variantId))
                .forEach(variantId -> findings.add(ValidationFinding.blocker(
                        "OFFERING_REFERENCES_UNKNOWN_VARIANT",
                        EntityType.VARIANT,
                        variantId,
                        null,
                        "A location offers a variant that is not active in this brand")));
    }

    /**
     * Everything the rules need, loaded once.
     *
     * @param pricedVariantIds contributed by pricing; catalog does not own money
     * @param displayableMedia contributed by media; catalog does not own bytes
     */
    public record Snapshot(
            String defaultLocale,
            List<Product> products,
            List<Variant> variants,
            Map<UUID, List<Variant>> variantsByProduct,
            List<Category> categories,
            Map<UUID, Category> categoriesById,
            Map<UUID, List<UUID>> productIdsByCategory,
            Map<UUID, List<UUID>> modifierGroupIdsByProduct,
            List<ModifierGroup> modifierGroups,
            Map<UUID, List<ModifierOption>> optionsByGroup,
            Map<String, LocalizedText> translations,
            Map<MediaAssetId, Set<UUID>> mediaReferences,
            Set<MediaAssetId> displayableMedia,
            Set<UUID> pricedVariantIds,
            Set<UUID> offeredVariantIds,
            FiscalContext fiscal,
            boolean pricingWired) {

        /**
         * The classification a variant would actually be fiscalized under
         * (ADR 0038).
         *
         * <p>Its own row, or nothing. V0021 let a variant inherit a code from its
         * product, and V0028 ends that: a product is not priceable and never
         * reaches a receipt as its own line, so an inherited code has to be
         * resolved by whoever builds the line — from a published snapshot that
         * does not contain the product row. The convenience cost a brand three
         * edits for three sizes of one pizza; it also meant the classification of
         * a thing lived somewhere other than on the thing.
         */
        public FiscalClassification effectiveClassification(Variant variant) {
            return fiscal.byNode().getOrDefault(variant.id(), FiscalClassification.unclassified());
        }

        /**
         * The classification a modifier option would be fiscalized under.
         *
         * <p>The one inheritance that survives, because it crosses an identity
         * rather than a hierarchy: a modifier linked to a variant *is* that
         * variant, sold as an addition. Classifying it twice is how one physical
         * good reaches a receipt under two codes that can be corrected
         * independently and then disagree.
         */
        public FiscalClassification effectiveClassification(ModifierOption option) {
            FiscalClassification own = fiscal.byNode().getOrDefault(option.id(), FiscalClassification.unclassified());
            if (option.linkedVariantId() == null) {
                return own;
            }
            return own.orInherited(fiscal.byNode().get(option.linkedVariantId()));
        }

        public Set<UUID> activeVariantIds() {
            return variants.stream()
                    .filter(variant -> variant.status() == Status.ACTIVE)
                    .map(Variant::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        public boolean hasTranslation(EntityType type, UUID entityId, String locale) {
            return translations.containsKey(translationKey(type, entityId, locale));
        }

        /**
         * Resolves a name by ADR 0016's fallback order: requested locale, then
         * the brand default. Publication refuses to proceed without the default,
         * so a published item always has at least one name.
         */
        public @Nullable LocalizedText text(EntityType type, UUID entityId, String locale) {
            LocalizedText requested = translations.get(translationKey(type, entityId, locale));
            return requested != null ? requested : translations.get(translationKey(type, entityId, defaultLocale));
        }

        public static String translationKey(EntityType type, UUID entityId, String locale) {
            return type.name() + ":" + entityId + ":" + locale;
        }
    }

    /** A name and optional description in one locale. */
    public record LocalizedText(String locale, String name, @Nullable String description) {}

    /**
     * Everything the fiscal rules need, loaded with the rest of the snapshot
     * (ADR 0038).
     *
     * <p>Grouped rather than added as five more components on {@code Snapshot},
     * so a reader can see at a glance which inputs belong to which rules — and so
     * that the fiscal module, when it exists, has one thing to take rather than
     * five to find.
     *
     * @param byNode              classification per priceable node: variants,
     *                            modifier options and fees in one map, because
     *                            they are distinct identifiers keyed in one table
     * @param fees                this brand's non-catalogue charge lines
     * @param brandOffersDelivery whether any location offers any variant for
     *                            delivery. A brand selling pickup only is not
     *                            missing a delivery fee classification; it is not
     *                            charging one
     * @param referenceLoaded     whether the official ИКПУ/MXIK list has been
     *                            imported at all. Separate from
     *                            {@code referenceCodes} being empty, because an
     *                            un-imported reference and a catalog full of
     *                            unrecognised codes are different problems with
     *                            different owners
     * @param referenceCodes      the subset of this catalog's codes the reference
     *                            recognises
     */
    public record FiscalContext(
            Map<UUID, FiscalClassification> byNode,
            List<Fee> fees,
            boolean brandOffersDelivery,
            boolean referenceLoaded,
            Set<String> referenceCodes) {

        /** For a test or a caller with nothing fiscal to say. */
        public static FiscalContext empty() {
            return new FiscalContext(Map.of(), List.of(), false, false, Set.of());
        }
    }
}
