package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.CatalogNameLocales;
import uz.horecaos.platform.catalog.api.VariantPricingLookup;
import uz.horecaos.platform.catalog.application.ChannelProjection.ResolvedMedia;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.LocationOffering;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.ChannelFindings;
import uz.horecaos.platform.catalog.domain.CompositeProducts;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ModifierAttachment;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.MediaRelationRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.TranslationRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore.MediaOverrideRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCompositeCatalogStore;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.SalesChannel;

/**
 * Assembles a whole catalog in one read, then turns it into publication items
 * (ADR 0016).
 *
 * <p>Loading is separated from validating so the validator can stay a pure
 * function: no database, no clock, no service calls, and therefore every rule
 * testable on a literal.
 *
 * <p>It also means the snapshot is read once inside the publishing transaction,
 * so validation and the snapshot that gets published cannot disagree — validating
 * one state and publishing another is the classic way a "validated" menu goes
 * live broken.
 */
@Component
public class CatalogSnapshotLoader {

    /**
     * ADR 0036's fulfilment mode for delivery, as an offering records it.
     *
     * <p>A string rather than an enum because the mode vocabulary is tenancy's
     * and catalog stores it as free text on the offering row; naming the constant
     * here at least keeps the literal in one place.
     */
    private static final String DELIVERY_MODE = "DELIVERY";

    private final JdbcCatalogStore store;
    private final MediaAvailability media;
    private final VariantPricingLookup pricing;
    private final BrandLocaleLookup brandLocales;
    private final String defaultLocale;
    private final boolean pricingWired;

    /**
     * @param defaultLocale {@code horecaos.catalog.default-locale} -- where a name is
     *                      accepted when the brand's own default language has none
     */
    @Autowired
    public CatalogSnapshotLoader(
            JdbcCatalogStore store,
            MediaAvailability media,
            VariantPricingLookup pricing,
            BrandLocaleLookup brandLocales,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.store = store;
        this.media = media;
        this.pricing = pricing;
        this.brandLocales = brandLocales;
        this.defaultLocale = defaultLocale;
        this.pricingWired = pricing.isWired();
    }

    /** A loader that requires the configured locale for every brand, for callers with no tenancy to ask. */
    public CatalogSnapshotLoader(
            JdbcCatalogStore store, MediaAvailability media, VariantPricingLookup pricing, String defaultLocale) {
        this(store, media, pricing, BrandLocaleLookup.platformFallback(), defaultLocale);
    }

    public CatalogValidator.Snapshot load(UUID tenantId, UUID brandId, UUID catalogId) {
        List<Product> products = store.productsInCatalog(tenantId, brandId, catalogId);
        List<Variant> variants = store.variantsInCatalog(tenantId, brandId, catalogId);
        List<Category> categories = store.categoriesInCatalog(tenantId, brandId, catalogId);
        List<ModifierGroup> groups = store.modifierGroupsInCatalog(tenantId, brandId, catalogId);
        List<ModifierOption> options = store.optionsForGroups(
                tenantId, brandId, groups.stream().map(ModifierGroup::id).toList());
        // Membership, read in the same transaction as the entities it joins.
        // Without these the published menu is a flat list: no category a
        // customer can browse, and no group a customer can choose from.
        Map<UUID, List<UUID>> productIdsByCategory = store.productIdsByCategory(tenantId, brandId, catalogId);
        Map<UUID, List<UUID>> modifierGroupIdsByProduct = store.modifierGroupIdsByProduct(tenantId, brandId, catalogId);

        Map<UUID, List<Variant>> variantsByProduct =
                variants.stream().collect(Collectors.groupingBy(Variant::productId));
        Map<UUID, Category> categoriesById =
                categories.stream().collect(Collectors.toMap(Category::id, category -> category));
        Map<UUID, List<ModifierOption>> optionsByGroup =
                options.stream().collect(Collectors.groupingBy(ModifierOption::modifierGroupId));

        Map<String, CatalogValidator.LocalizedText> translations = new LinkedHashMap<>();
        for (TranslationRow row : store.translations(tenantId, brandId)) {
            translations.put(
                    CatalogValidator.Snapshot.translationKey(row.entityType(), row.entityId(), row.locale()),
                    new CatalogValidator.LocalizedText(row.locale(), row.name(), row.description()));
        }

        // Only media hanging off entities in this catalog. A brand's other
        // images are not this menu's problem, and validating them would block a
        // publication over a picture the customer will never see.
        Set<UUID> entityIds = new HashSet<>();
        products.forEach(product -> entityIds.add(product.id()));
        variants.forEach(variant -> entityIds.add(variant.id()));
        categories.forEach(category -> entityIds.add(category.id()));
        options.forEach(option -> entityIds.add(option.id()));

        Map<MediaAssetId, Set<UUID>> mediaReferences = new LinkedHashMap<>();
        for (MediaRelationRow relation : store.mediaRelations(tenantId, brandId)) {
            if (entityIds.contains(relation.entityId())) {
                mediaReferences
                        .computeIfAbsent(new MediaAssetId(relation.mediaAssetId()), key -> new HashSet<>())
                        .add(relation.entityId());
            }
        }

        Set<MediaAssetId> displayable = mediaReferences.keySet().stream()
                .filter(assetId -> media.allDisplayable(tenantId, Set.of(assetId)))
                .collect(Collectors.toUnmodifiableSet());

        Set<UUID> variantIds = variants.stream().map(Variant::id).collect(Collectors.toSet());
        Set<UUID> priced = pricing.pricedVariants(tenantId, brandId, variantIds);

        List<LocationOffering> offerings = store.offeringsForBrand(tenantId, brandId);
        Set<UUID> offered = offerings.stream().map(LocationOffering::variantId).collect(Collectors.toUnmodifiableSet());

        CatalogNameLocales nameLocales = CatalogNameLocales.of(brandLocales, tenantId, brandId, defaultLocale);
        return new CatalogValidator.Snapshot(
                nameLocales.preferred(),
                nameLocales.fallback(),
                products,
                variants,
                variantsByProduct,
                categories,
                categoriesById,
                productIdsByCategory,
                modifierGroupIdsByProduct,
                groups,
                optionsByGroup,
                Map.copyOf(translations),
                mediaReferences,
                displayable,
                priced,
                offered,
                loadFiscalContext(tenantId, brandId, offerings),
                pricingWired,
                loadCompositeContext(tenantId, brandId, catalogId),
                store.physicalAttributesForBrand(tenantId, brandId));
    }

    /**
     * The composite-product inputs, read in the same transaction as the rest
     * (ADR 0136).
     *
     * <p>Nothing beyond two small reads happens for a brand with no combo group and no
     * attachment, which is every brand that has not authored either: the wider
     * brand-wide reads exist to answer questions a brand without composite data cannot
     * have.
     */
    private CatalogValidator.CompositeContext loadCompositeContext(UUID tenantId, UUID brandId, UUID catalogId) {
        JdbcCompositeCatalogStore composite = store.composite();
        List<ComboGroup> comboGroups = composite.comboGroupsInCatalog(tenantId, brandId, catalogId);
        List<ModifierAttachment> attachments = composite.attachmentsForBrand(tenantId, brandId);
        if (comboGroups.isEmpty() && attachments.isEmpty()) {
            return CatalogValidator.CompositeContext.empty();
        }

        Map<UUID, List<ComboComponent>> componentsByGroup =
                composite
                        .componentsForGroups(
                                tenantId,
                                brandId,
                                comboGroups.stream().map(ComboGroup::id).toList())
                        .stream()
                        .collect(Collectors.groupingBy(ComboComponent::comboGroupId));
        Set<UUID> activeComponentIds = componentsByGroup.values().stream()
                .flatMap(List::stream)
                .filter(component -> component.status() == Status.ACTIVE)
                .map(ComboComponent::id)
                .collect(Collectors.toSet());
        Set<UUID> pricedComponents = activeComponentIds.isEmpty()
                ? Set.of()
                : pricing.pricedComboComponents(tenantId, brandId, activeComponentIds);

        List<JdbcCompositeCatalogStore.VariantFact> variantFacts = composite.variantFacts(tenantId, brandId);
        Map<UUID, UUID> productByVariant = variantFacts.stream()
                .collect(Collectors.toMap(
                        JdbcCompositeCatalogStore.VariantFact::variantId,
                        JdbcCompositeCatalogStore.VariantFact::productId));
        Map<UUID, Status> statusByVariant = variantFacts.stream()
                .collect(Collectors.toMap(
                        JdbcCompositeCatalogStore.VariantFact::variantId,
                        JdbcCompositeCatalogStore.VariantFact::status));

        List<ModifierGroup> brandGroups = store.modifierGroupsForBrand(tenantId, brandId);
        Map<UUID, ModifierGroup> groupsById =
                brandGroups.stream().collect(Collectors.toMap(ModifierGroup::id, group -> group));
        Map<UUID, List<ModifierOption>> optionsByGroup =
                store
                        .optionsForGroups(
                                tenantId,
                                brandId,
                                brandGroups.stream().map(ModifierGroup::id).toList())
                        .stream()
                        .collect(Collectors.groupingBy(ModifierOption::modifierGroupId));

        return new CatalogValidator.CompositeContext(
                comboGroups,
                componentsByGroup,
                pricedComponents,
                statusByVariant,
                attachments,
                productByVariant,
                groupsById,
                optionsByGroup);
    }

    /**
     * The fiscal inputs, read in the same transaction as the rest (ADR 0038).
     *
     * <p>Classifications are loaded for the whole brand rather than for this
     * catalog's nodes, because a modifier option resolves through a link to a
     * variant that may belong to another of the brand's catalogs, and a
     * catalog-scoped read would report that option as unclassified when it is
     * not.
     */
    private CatalogValidator.FiscalContext loadFiscalContext(
            UUID tenantId, UUID brandId, List<LocationOffering> offerings) {

        Map<UUID, FiscalClassification> byNode = store.classificationsForBrand(tenantId, brandId);

        // Whether the delivery fee is a line this brand can produce at all. Read
        // from the offerings rather than from the channel registry because the
        // question is what this brand sells for delivery, and an offering is
        // where that is stated per location.
        boolean offersDelivery = offerings.stream()
                .anyMatch(offering -> offering.status() != OfferingStatus.HIDDEN
                        && offering.fulfillmentModes().stream().anyMatch(DELIVERY_MODE::equalsIgnoreCase));

        boolean referenceLoaded = store.mxikReferenceIsLoaded();
        Set<String> knownCodes = referenceLoaded
                ? store.knownMxikCodes(byNode.values().stream()
                        .map(FiscalClassification::mxikCode)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet()))
                : Set.of();

        return new CatalogValidator.FiscalContext(
                byNode, store.feesForBrand(tenantId, brandId), offersDelivery, referenceLoaded, knownCodes);
    }

    /**
     * The items one channel is published, which are the draft's items with the images
     * that channel shows (ADR 0138 step 4, {@link ChannelMediaLayers}), and what stops
     * them being published.
     *
     * <p>This is what {@code publish} writes and what the channel preview draws, so
     * the two cannot disagree about a picture or about whether it may be shown. The
     * channel-agnostic overload above is the draft as the snapshot lists it; a
     * channel's items differ from it where an image belongs to a channel -- an
     * override this channel carries, a relation naming this channel, and the absence
     * of a relation naming another one.
     */
    public ChannelItems toPublicationItems(
            CatalogValidator.Snapshot snapshot, UUID tenantId, UUID brandId, SalesChannel channel) {
        ChannelMediaLayers.Plan plan = ChannelMediaLayers.plan(
                channel,
                snapshot,
                toPublicationItems(snapshot),
                store.mediaRelations(tenantId, brandId),
                store.channelMediaOverrides(tenantId, brandId, channel.id()));
        return new ChannelItems(
                plan.items(),
                plan.resolved(),
                plan.overridesByEntity(),
                unavailableOverrideImages(tenantId, plan.overridesByEntity()));
    }

    /**
     * An override row is not a relation the universal validator walks, so a channel image
     * that is still uploading or was withdrawn after it was chosen is caught here -- a menu
     * pushed to an aggregator with a broken picture is the failure ADR 0138 exists to catch
     * earlier.
     */
    private List<ValidationFinding> unavailableOverrideImages(
            UUID tenantId, Map<UUID, List<MediaOverrideRow>> overridesByEntity) {
        Map<UUID, Boolean> displayable = new java.util.HashMap<>();
        List<ValidationFinding> findings = new ArrayList<>();
        overridesByEntity.values().stream()
                .flatMap(List::stream)
                .sorted(java.util.Comparator.comparing(
                                (MediaOverrideRow row) -> row.entityType().name())
                        .thenComparing(MediaOverrideRow::entityId)
                        .thenComparing(MediaOverrideRow::mediaAssetId))
                .forEach(row -> {
                    boolean shown = displayable.computeIfAbsent(
                            row.mediaAssetId(),
                            asset -> media.allDisplayable(tenantId, Set.of(new MediaAssetId(asset))));
                    if (!shown) {
                        findings.add(ValidationFinding.blocker(
                                ChannelFindings.CHANNEL_MEDIA_NOT_AVAILABLE,
                                row.entityType(),
                                row.entityId(),
                                null,
                                "A channel image for this item is not verified and ready to show"));
                    }
                });
        return List.copyOf(findings);
    }

    /**
     * A channel's publication items and the images they carry.
     *
     * @param items the draft's items with every product's {@code mediaAssetIds} as this channel
     *     shows them
     * @param media the images of every product, variant and category on this channel and the layer
     *     each came from; only a product's reach a published menu
     * @param overrides the override rows this channel carries, by entity
     * @param findings blockers on the channel's own images, which {@code publish} adds to the
     *     catalog's report and the preview reports beside it
     */
    public record ChannelItems(
            List<PublicationItem> items,
            Map<UUID, ResolvedMedia> media,
            Map<UUID, List<MediaOverrideRow>> overrides,
            List<ValidationFinding> findings) {}

    /**
     * Flattens a snapshot into the rows the storefront will read.
     *
     * <p>Each item carries the entity's content as a copy, not a reference. That
     * copy is what makes a published menu immune to a later draft edit, and it is
     * why publication items are insert-only at the grant level.
     */
    public List<PublicationItem> toPublicationItems(CatalogValidator.Snapshot snapshot) {
        List<PublicationItem> items = new ArrayList<>();

        for (Category category : snapshot.categories()) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", category.code());
            // Omitted when absent rather than written as the string "null".
            putIfPresent(content, "parentCategoryId", category.parentCategoryId());
            content.put("sortOrder", category.sortOrder());
            content.put("status", category.status().name());
            // Names travel with the snapshot. The storefront reads only
            // publication items, so a name left behind here would render as a
            // database code.
            content.put("names", names(snapshot, EntityType.CATEGORY, category.id()));
            // Membership travels with the category rather than the product so
            // that sort order within the category survives -- a product sits in
            // more than one category and is ordered differently in each.
            content.put(
                    "productIds", idStrings(snapshot.productIdsByCategory().getOrDefault(category.id(), List.of())));

            items.add(new PublicationItem(EntityType.CATEGORY, category.id(), category.version(), content));
        }

        for (Product product : snapshot.products()) {
            List<Map<String, Object>> productVariants =
                    snapshot.variantsByProduct().getOrDefault(product.id(), List.of()).stream()
                            .map(variant -> {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("variantId", variant.id().toString());
                                // A variant genuinely may have no SKU. Writing "null" here
                                // put a real-looking string in front of customers and in
                                // every downstream consumer keying on sku — and because
                                // publication items are insert-only, it could not be
                                // corrected without republishing the whole menu.
                                putIfPresent(entry, "sku", variant.sku());
                                entry.put("unitCode", variant.unitCode());
                                // Resolved, not raw. A partner adapter reading this
                                // snapshot has no access to the product row to inherit
                                // from, and a fiscal receipt built from a published menu
                                // must not have to reach back into authoring — which is
                                // mutable and may have moved on.
                                putClassification(entry, snapshot.effectiveClassification(variant));
                                putPhysical(entry, snapshot.physicalByVariant().get(variant.id()));
                                entry.put("isDefault", variant.isDefault());
                                entry.put("sortOrder", variant.sortOrder());
                                entry.put("status", variant.status().name());
                                return entry;
                            })
                            .toList();

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", product.code());
            // No classification on a product. It is not priceable and never
            // reaches a receipt as its own line, so a code here would be a second
            // place to look with no rule for which one wins.
            content.put("status", product.status().name());
            content.put("variants", productVariants);
            content.put("names", names(snapshot, EntityType.PRODUCT, product.id()));
            content.put("mediaAssetIds", mediaFor(snapshot, product.id()));
            // Product-level only. V0016 also has variant_modifier_groups and
            // nothing writes it, so publishing a variant-level link would put an
            // always-empty list in front of every client.
            content.put(
                    "modifierGroupIds",
                    idStrings(snapshot.modifierGroupIdsByProduct().getOrDefault(product.id(), List.of())));
            // ADR 0136. Written only when there is something to say, so a product with no
            // combo and no override publishes exactly the content it always has and the
            // draft's content hash does not move for a brand that authored neither.
            List<UUID> comboGroups = comboGroupIdsOf(snapshot, product.id());
            if (!comboGroups.isEmpty()) {
                content.put("comboGroupIds", idStrings(comboGroups));
            }
            List<Map<String, Object>> policies = modifierGroupPolicies(snapshot, product.id());
            if (!policies.isEmpty()) {
                content.put("modifierGroupPolicies", policies);
            }

            items.add(new PublicationItem(EntityType.PRODUCT, product.id(), product.version(), content));
        }

        for (ModifierGroup group : snapshot.modifierGroups()) {
            List<Map<String, Object>> groupOptions =
                    snapshot.optionsByGroup().getOrDefault(group.id(), List.of()).stream()
                            .map(option -> {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("optionId", option.id().toString());
                                entry.put("code", option.code());
                                putIfPresent(entry, "linkedVariantId", option.linkedVariantId());
                                // What the customer reads on the option. Written only when the
                                // option has a name in some locale, so an option nobody named
                                // publishes as it always did and a client falls back to the code.
                                Map<String, Map<String, String>> optionNames =
                                        names(snapshot, EntityType.MODIFIER_OPTION, option.id());
                                if (!optionNames.isEmpty()) {
                                    entry.put("names", optionNames);
                                }
                                putClassification(entry, snapshot.effectiveClassification(option));
                                entry.put("maximumQuantity", option.maximumQuantity());
                                entry.put("sortOrder", option.sortOrder());
                                entry.put("status", option.status().name());
                                return entry;
                            })
                            .toList();

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", group.code());
            content.put("required", group.required());
            content.put("minimumSelections", group.minimumSelections());
            content.put("maximumSelections", group.maximumSelections());
            content.put("allowSameOptionMultipleTimes", group.allowSameOptionMultipleTimes());
            content.put("sortOrder", group.sortOrder());
            content.put("names", names(snapshot, EntityType.MODIFIER_GROUP, group.id()));
            content.put("options", groupOptions);

            items.add(new PublicationItem(EntityType.MODIFIER_GROUP, group.id(), group.version(), content));
        }

        for (ComboGroup group : snapshot.composite().comboGroups()) {
            if (group.status() != Status.ACTIVE) {
                continue;
            }
            items.add(new PublicationItem(
                    EntityType.COMBO_GROUP, group.id(), group.version(), comboGroupContent(snapshot, group)));
        }

        return List.copyOf(items);
    }

    /**
     * What a channel needs to render one combo choice screen with no reach back into
     * authoring (ADR 0136): the range, the repeat rule, and each active component with the
     * wording of the dish it stands for.
     *
     * <p>No price is written. A component's price is a {@code COMBO_COMPONENT} row on the
     * price book that resolves at the location and channel, read when the menu is served
     * exactly as a variant's and an option's are, so the number a customer reads is the
     * number the quote charges. A copy frozen here would disagree with checkout the moment
     * a price book moved, which is the one thing the quote exists to prevent.
     */
    private static Map<String, Object> comboGroupContent(CatalogValidator.Snapshot snapshot, ComboGroup group) {
        CatalogValidator.CompositeContext composite = snapshot.composite();
        List<Map<String, Object>> components =
                composite.componentsByGroup().getOrDefault(group.id(), List.of()).stream()
                        .filter(component -> component.status() == Status.ACTIVE)
                        .sorted(java.util.Comparator.comparingInt(ComboComponent::sortOrder))
                        .map(component -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("componentId", component.id().toString());
                            entry.put(
                                    "variantId", component.componentVariantId().toString());
                            UUID productId = composite.productIdByVariant().get(component.componentVariantId());
                            putIfPresent(entry, "productId", productId);
                            entry.put("defaultQuantity", component.defaultQuantity());
                            entry.put("sortOrder", component.sortOrder());
                            // The dish and the size, in every locale they are named in: a
                            // component is a real variant and its name is its product's,
                            // with the variant's own wording after it when it has one.
                            if (productId != null) {
                                Map<String, Map<String, String>> productNames =
                                        names(snapshot, EntityType.PRODUCT, productId);
                                if (!productNames.isEmpty()) {
                                    entry.put("productNames", productNames);
                                }
                            }
                            Map<String, Map<String, String>> variantNames =
                                    names(snapshot, EntityType.VARIANT, component.componentVariantId());
                            if (!variantNames.isEmpty()) {
                                entry.put("variantNames", variantNames);
                            }
                            return entry;
                        })
                        .toList();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", group.code());
        content.put("containerVariantId", group.containerVariantId().toString());
        content.put("minimumSelections", group.minimumSelections());
        content.put("maximumSelections", group.maximumSelections());
        content.put("allowSameComponentMultipleTimes", group.allowSameComponentMultipleTimes());
        content.put("sortOrder", group.sortOrder());
        content.put("status", group.status().name());
        content.put("names", names(snapshot, EntityType.COMBO_GROUP, group.id()));
        content.put("components", components);
        return content;
    }

    /**
     * The active combo groups whose container is one of this product's variants, in the
     * order the author set.
     */
    private static List<UUID> comboGroupIdsOf(CatalogValidator.Snapshot snapshot, UUID productId) {
        Set<UUID> variantIds = snapshot.variantsByProduct().getOrDefault(productId, List.of()).stream()
                .map(Variant::id)
                .collect(Collectors.toSet());
        return snapshot.composite().comboGroups().stream()
                .filter(group -> group.status() == Status.ACTIVE)
                .filter(group -> variantIds.contains(group.containerVariantId()))
                .sorted(java.util.Comparator.comparingInt(ComboGroup::sortOrder).thenComparing(ComboGroup::code))
                .map(ComboGroup::id)
                .toList();
    }

    /**
     * The selection rules this product's own use of a shared group states, for every visible
     * attachment that overrides at least one of them (ADR 0136).
     *
     * <p>The three values written are the effective ones -- the override where there is
     * one, the shared group's own where there is not -- so a client replaces the group's
     * values outright and never has to know which was which. The shared group is not edited
     * and is published unchanged, so a second product attaching it is unaffected.
     */
    private static List<Map<String, Object>> modifierGroupPolicies(CatalogValidator.Snapshot snapshot, UUID productId) {
        Map<UUID, ModifierGroup> groups = snapshot.composite().modifierGroupsById();
        List<Map<String, Object>> policies = new ArrayList<>();
        for (CompositeProducts.ModifierAttachment attachment :
                snapshot.composite().attachments()) {
            if (attachment.ownerType() != CompositeProducts.AttachmentOwnerType.PRODUCT
                    || !attachment.ownerId().equals(productId)
                    || attachment.hidden()
                    || (attachment.requiredOverride() == null
                            && attachment.minimumOverride() == null
                            && attachment.maximumOverride() == null)) {
                continue;
            }
            ModifierGroup group = groups.get(attachment.modifierGroupId());
            if (group == null) {
                continue;
            }
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("groupId", group.id().toString());
            policy.put("required", attachment.effectiveRequired(group));
            policy.put("minimumSelections", attachment.effectiveMinimum(group));
            policy.put("maximumSelections", attachment.effectiveMaximum(group));
            policies.add(policy);
        }
        return List.copyOf(policies);
    }

    /**
     * Identifiers as strings, matching how every other identifier is written
     * into publication content.
     */
    private static List<String> idStrings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    /**
     * Writes the classification a receipt line will be built from, omitting each
     * field that is absent (ADR 0038).
     *
     * <p>Omitted rather than written as null, for the same reason the SKU is: a
     * publication item is insert-only, so a placeholder that reaches an
     * aggregator as a real-looking code cannot be corrected without republishing
     * the entire menu — and a wrong code on a receipt is a tax classification
     * error, not a cosmetic one.
     *
     * <p>The marking flag is written even when false, because it is the one field
     * here that a consumer must act on rather than copy: a payment surface
     * reading a published menu decides which methods to offer from it, and
     * "absent" and "not marked" have to be the same answer for that to be safe.
     */
    private static void putClassification(Map<String, Object> target, FiscalClassification fiscal) {
        putIfPresent(target, "mxikCode", fiscal.mxikCode());
        putIfPresent(target, "packageCode", fiscal.packageCode());
        putIfPresent(target, "fiscalUnitCode", fiscal.fiscalUnitCode());
        putIfPresent(target, "fiscalName", fiscal.fiscalName());
        putIfPresent(target, "barcode", fiscal.barcode());
        target.put("markingRequired", fiscal.markingRequired());
        if (fiscal.markingRequired()) {
            target.put("markingScheme", fiscal.markingScheme().name());
        }
        if (fiscal.excisable()) {
            target.put("excisable", true);
        }
        putIfPresent(target, "alcoholByVolumeBp", fiscal.alcoholByVolumeBasisPoints());
        putIfPresent(target, "ageRestrictionYears", fiscal.ageRestrictionYears());
    }

    /**
     * Writes the physical attributes a customer, a cart and the pricing engine read
     * from the published menu (ADR 0137), omitting the whole block for a variant
     * that carries none.
     *
     * <p>Published rather than read live for the reason the classification is: a
     * quote has to be priced against the facts the customer was shown, and an
     * author flipping a variant to catchweight must not reinterpret a price while
     * the old menu is still on screen. The block is a copy, so it changes only with
     * the next publication.
     *
     * <p>КБЖУ travels in a nested {@code nutrition} object, per 100 g (or per
     * 100 mL), so the storefront scales it to a portion from the stored figures
     * rather than from a second stored value that could drift from them.
     */
    private static void putPhysical(Map<String, Object> target, @Nullable PhysicalAttributes physical) {
        if (physical == null) {
            return;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        putIfPresent(block, "netWeightGrams", physical.netWeightGrams());
        putIfPresent(block, "netVolumeMillilitres", physical.netVolumeMillilitres());
        block.put("catchweight", physical.catchweight());
        putIfPresent(block, "catchweightQuantumGrams", physical.catchweightQuantumGrams());
        putIfPresent(block, "catchweightNominalGrams", physical.catchweightNominalGrams());
        block.put("splittable", physical.splittable());
        putIfPresent(block, "portionSize", physical.portionSize());
        Map<String, Object> nutrition = new LinkedHashMap<>();
        putIfPresent(nutrition, "caloriesKcalPer100", physical.caloriesKcalPer100());
        putIfPresent(nutrition, "proteinGramsPer100", physical.proteinGramsPer100());
        putIfPresent(nutrition, "fatGramsPer100", physical.fatGramsPer100());
        putIfPresent(nutrition, "carbohydratesGramsPer100", physical.carbohydratesGramsPer100());
        if (!nutrition.isEmpty()) {
            block.put("nutrition", nutrition);
        }
        target.put("physical", block);
    }

    /**
     * Writes a value only when it exists.
     *
     * <p>The alternative, {@code String.valueOf(x)}, turns a null into the string
     * {@code "null"} — which reaches the storefront looking like a real value.
     */
    private static void putIfPresent(Map<String, Object> target, String key, @Nullable Object value) {
        if (value != null) {
            target.put(key, value instanceof java.util.UUID id ? id.toString() : value);
        }
    }

    /** Every locale this entity has a name in, keyed by locale. */
    private static Map<String, Map<String, String>> names(
            CatalogValidator.Snapshot snapshot, EntityType type, UUID entityId) {

        Map<String, Map<String, String>> byLocale = new LinkedHashMap<>();
        snapshot.translations().forEach((key, text) -> {
            if (key.startsWith(type.name() + ":" + entityId + ":")) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", text.name());
                if (text.description() != null) {
                    entry.put("description", text.description());
                }
                byLocale.put(text.locale(), entry);
            }
        });
        return byLocale;
    }

    private static List<String> mediaFor(CatalogValidator.Snapshot snapshot, UUID entityId) {
        return snapshot.mediaReferences().entrySet().stream()
                .filter(entry -> entry.getValue().contains(entityId))
                .map(entry -> entry.getKey().toString())
                .toList();
    }
}
