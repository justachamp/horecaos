package uz.horecaos.platform.catalog.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup;
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup.VariantAvailability;
import uz.horecaos.platform.catalog.api.MenuPriceLookup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.LocationOffering;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.CatalogLocales;
import uz.horecaos.platform.catalog.domain.ItemSaleSchedule;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;

/**
 * What a customer sees (ADR 0016).
 *
 * <p>Reads the immutable publication, never the authoring tables. That is the
 * whole mechanism preventing a half-finished draft from appearing on a live
 * menu — not a status flag anyone could forget to check.
 *
 * <p>Location offerings <em>are</em> read live, and deliberately so: marking a
 * dish sold out must take effect at once, and routing it through a republish
 * would mean re-validating an entire menu to hide one item.
 *
 * <p><strong>Row 4.4a — the named Menu entity.</strong> A branch that has
 * bound a named menu ({@code catalog.branch_menu_bindings}, V0390) publishes
 * its per-variant availability from that menu's own membership instead of
 * from {@code location_offerings} — {@link #offeringsFor} resolves a
 * channel-specific binding first, then the branch's default binding, and
 * falls back to {@code location_offerings} only when neither exists. A
 * tenant that has never bound a menu anywhere therefore sees no behaviour
 * change at all: {@code findBoundMenuId} returns empty for every branch, and
 * this method takes exactly the path it always did. See {@code
 * JdbcMenuStore}'s own class doc and {@code V0389}/{@code V0390} for why this
 * is additive to ADR 0016's model rather than a second publication mechanism
 * — the publication itself (what a product/category/modifier looks like) is
 * completely unaffected; only the answer to "does this branch sell this
 * variant, at what default availability" gains a second, curated source.
 */
@Service
public class StorefrontCatalogQuery {

    private final JdbcCatalogStore store;
    private final MenuPriceLookup prices;
    private final MenuAvailabilityLookup availability;
    private final JdbcMenuStore menus;
    private final CatalogTenantContext tenantContext;
    private final Clock clock;
    private final uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore commentPresets;
    private final BrandLocaleLookup brandLocales;
    private final String defaultLocale;

    /**
     * @param brandLocales  the brand's own default language, which a name or a preset label the
     *                      customer's language does not have falls back to (row 10.12)
     * @param defaultLocale {@code horecaos.catalog.default-locale} -- the last named fallback,
     *                      where a menu imported or sampled without a brand-specific language
     *                      put its names
     */
    @Autowired
    public StorefrontCatalogQuery(
            JdbcCatalogStore store,
            MenuPriceLookup prices,
            MenuAvailabilityLookup availability,
            JdbcMenuStore menus,
            CatalogTenantContext tenantContext,
            Clock clock,
            uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore commentPresets,
            BrandLocaleLookup brandLocales,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.store = store;
        this.prices = prices;
        this.availability = availability;
        this.menus = menus;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.commentPresets = commentPresets;
        this.brandLocales = brandLocales;
        this.defaultLocale = defaultLocale;
    }

    /** A storefront read for callers with no tenancy to ask: no brand has a default language of its own. */
    public StorefrontCatalogQuery(
            JdbcCatalogStore store,
            MenuPriceLookup prices,
            MenuAvailabilityLookup availability,
            JdbcMenuStore menus,
            CatalogTenantContext tenantContext,
            Clock clock,
            uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore commentPresets) {
        this(
                store,
                prices,
                availability,
                menus,
                tenantContext,
                clock,
                commentPresets,
                BrandLocaleLookup.platformFallback(),
                "uz");
    }

    /**
     * The live menu for one location.
     *
     * @return empty when the brand has never published; an empty menu and no
     *         menu are different answers, and a storefront needs to tell them apart
     */
    @Transactional(readOnly = true)
    public Optional<StorefrontMenu> menuFor(
            UUID tenantId, UUID brandId, UUID locationId, String locale, String channelCode) {
        // ADR 0036: the channel supplies the publication, and it is the caller's
        // own channel rather than a literal. This read was hardcoded to
        // 'STOREFRONT', which meant a kiosk browsing its own menu was served the
        // storefront's -- silently, with the kiosk publication live and unread.
        // CatalogPricingContext had already been corrected for exactly this and
        // this copy had not.
        Optional<UUID> publicationId = store.findActivePublicationId(tenantId, brandId, channelCode);
        if (publicationId.isEmpty()) {
            return Optional.empty();
        }
        UUID publication = publicationId.get();

        // Row 10.12: what a customer is shown when their own language has no wording is the
        // brand's default language, not whichever locale the publication happened to list first.
        Optional<String> brandDefault = brandLocales.brandDefaultLocale(tenantId, brandId);
        List<String> namePreference = namePreference(locale, brandDefault);

        Map<UUID, OfferingStatus> offeringByVariant = offeringsFor(tenantId, brandId, locationId, channelCode);

        // ADR 0036's sparse per-channel exclusions: default is offered, and a row
        // removes one item from one channel, optionally at one location. Read live
        // for the same reason offeringByVariant above is -- hiding a dish on this
        // channel must take effect now, not after a republish.
        Set<UUID> channelExcludedVariantIds =
                store.channelExcludedVariantIds(tenantId, brandId, channelCode, locationId);

        // Row 4.2g: marked here, live, for the identical reason offeringByVariant
        // and channelExcludedVariantIds are read live rather than from the
        // publication — an item's own sale schedule can end mid-service and a
        // customer must stop being offered it at once, not after a republish.
        Set<UUID> outOfWindowVariantIds = outOfWindowVariantIds(tenantId, locationId);

        List<PublicationItem> categoryItems = store.publicationItems(publication, EntityType.CATEGORY);
        List<PublicationItem> productItems = store.publicationItems(publication, EntityType.PRODUCT);
        List<PublicationItem> groupItems = store.publicationItems(publication, EntityType.MODIFIER_GROUP);
        // ADR 0136: empty on a publication that carries no combo, which is every one written
        // before the kind existed and every brand that has authored none.
        List<PublicationItem> comboItems = store.publicationItems(publication, EntityType.COMBO_GROUP);

        // Row 2.1b: which presets each product offers, read live for the same
        // reason offeringByVariant is — see CommentPresetLookup's own doc.
        // One bulk read for the whole menu rather than one per product.
        Set<UUID> productIds =
                productItems.stream().map(PublicationItem::entityId).collect(Collectors.toUnmodifiableSet());
        Map<UUID, List<uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.ProductPresetRow>>
                presetsByProduct = commentPresets.listForProducts(tenantId, brandId, productIds);
        // Row 10.12: a preset's wording in every locale it has, not only the platform triple's
        // columns -- one bulk read for the whole menu.
        Map<UUID, Map<String, String>> presetTranslations = commentPresets.translationsForPresets(
                tenantId,
                presetsByProduct.values().stream()
                        .flatMap(List::stream)
                        .map(
                                uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore
                                                .ProductPresetRow::presetId)
                        .collect(Collectors.toUnmodifiableSet()));
        List<String> presetPreference = presetPreference(locale, brandDefault);

        List<MenuProduct> products = new ArrayList<>();
        for (PublicationItem item : productItems) {
            List<MenuVariant> variants =
                    variantsOf(item, offeringByVariant, channelExcludedVariantIds, outOfWindowVariantIds);
            if (variants.isEmpty()) {
                // Not offered at this location at all. Absent rather than shown
                // as unavailable: the location genuinely does not sell it.
                continue;
            }
            List<String> mediaIds = mediaIds(item.content());
            products.add(new MenuProduct(
                    item.entityId(),
                    code(item.content()),
                    name(item.content(), namePreference),
                    description(item.content(), namePreference),
                    mediaIds,
                    imageUrls(tenantId, mediaIds),
                    variants,
                    // Prices are attached after the loop, once every variant on
                    // the menu is known, so the price book is read once rather
                    // than once per dish.
                    idList(item.content(), "modifierGroupIds"),
                    commentPresetsOf(
                            presetsByProduct.getOrDefault(item.entityId(), List.of()),
                            presetTranslations,
                            presetPreference),
                    idList(item.content(), "comboGroupIds"),
                    policiesOf(item.content())));
        }

        // A product the location does not offer was dropped above. Its id must
        // not survive in a category, or the customer taps a name with nothing
        // behind it.
        Set<UUID> servedProducts =
                products.stream().map(MenuProduct::productId).collect(Collectors.toUnmodifiableSet());

        List<MenuCategory> categories = categoryItems.stream()
                .map(item -> new MenuCategory(
                        item.entityId(),
                        code(item.content()),
                        name(item.content(), namePreference),
                        parentOf(item.content()),
                        intOf(item.content(), "sortOrder"),
                        idList(item.content(), "productIds").stream()
                                .filter(servedProducts::contains)
                                .toList()))
                // A category whose every product is unavailable here is not an
                // empty shelf to show the customer; it is not part of this
                // location's menu. Parents are kept: one holds children, not
                // products.
                .filter(category -> !category.productIds().isEmpty() || isParent(category.categoryId(), categoryItems))
                .sorted(java.util.Comparator.comparingInt(MenuCategory::sortOrder))
                .toList();

        List<MenuModifierGroup> modifierGroups = groupItems.stream()
                .map(item -> new MenuModifierGroup(
                        item.entityId(),
                        code(item.content()),
                        name(item.content(), namePreference),
                        Boolean.TRUE.equals(item.content().get("required")),
                        intOf(item.content(), "minimumSelections"),
                        intOf(item.content(), "maximumSelections"),
                        Boolean.TRUE.equals(item.content().get("allowSameOptionMultipleTimes")),
                        optionsOf(item, namePreference)))
                .toList();

        // ADR 0018. Resolved against the same price book the quote will use, on
        // the same channel plane, so the number a customer reads is the number
        // checkout charges. A brand with no active price book yields no prices
        // rather than zeros: free food is a very different claim from "not
        // priced yet", and only one of them is true.
        Set<UUID> variantIds = products.stream()
                .flatMap(product -> product.variants().stream())
                .map(MenuVariant::variantId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> optionIds = modifierGroups.stream()
                .flatMap(group -> group.options().stream())
                .map(MenuModifierOption::optionId)
                .collect(Collectors.toUnmodifiableSet());

        List<MenuComboGroup> comboGroups = combosOf(comboItems, namePreference);
        Set<UUID> componentIds = comboGroups.stream()
                .flatMap(group -> group.components().stream())
                .map(MenuComboComponent::componentId)
                .collect(Collectors.toUnmodifiableSet());

        Optional<MenuPriceLookup.MenuPrices> resolved =
                prices.pricesFor(tenantId, brandId, locationId, channelCode, variantIds, optionIds, componentIds);

        String currency = resolved.map(MenuPriceLookup.MenuPrices::currency).orElse(null);
        Map<UUID, Long> variantPrices =
                resolved.map(MenuPriceLookup.MenuPrices::variantPrices).orElse(Map.of());
        Map<UUID, Long> optionPrices =
                resolved.map(MenuPriceLookup.MenuPrices::modifierOptionPrices).orElse(Map.of());
        Map<UUID, Long> componentPrices =
                resolved.map(MenuPriceLookup.MenuPrices::comboComponentPrices).orElse(Map.of());

        // Rows 4.4c/4.4d, storefront half: one batched read for the whole
        // page, keyed by the caller's own channel exactly as prices.pricesFor
        // above is, so a QUANTITY item at zero remaining or a stopped BINARY
        // item stops rendering orderable here instead of only at checkout's
        // own inventory hold.
        // ADR 0136: stock is held on a combo's components, not on its container, so the
        // components' variants are asked about too.
        Set<UUID> availabilityVariantIds = new java.util.HashSet<>(variantIds);
        comboGroups.forEach(
                group -> group.components().forEach(component -> availabilityVariantIds.add(component.variantId())));
        Map<UUID, VariantAvailability> availabilityByVariant =
                availability.availabilityFor(tenantId, brandId, locationId, channelCode, availabilityVariantIds);

        List<MenuProduct> pricedProducts = products.stream()
                .map(product -> product.withPrices(variantPrices))
                .map(product -> product.withAvailability(availabilityByVariant))
                .toList();
        List<MenuModifierGroup> pricedGroups = modifierGroups.stream()
                .map(group -> group.withPrices(optionPrices))
                .toList();

        // A component is shown when the location offers it at all, and orderable only when
        // everything that makes a variant orderable says so -- the same four reads a product's
        // own variants go through above. One the location does not offer is absent, like a
        // product it does not serve.
        List<MenuComboGroup> pricedCombos = comboGroups.stream()
                .map(group -> group.withComponents(group.components().stream()
                        .filter(component ->
                                isOffered(component.variantId(), offeringByVariant, channelExcludedVariantIds))
                        .map(component -> component.priced(
                                componentPrices.get(component.componentId()),
                                offeringByVariant.get(component.variantId()) == OfferingStatus.AVAILABLE
                                        && !outOfWindowVariantIds.contains(component.variantId())
                                        && orderableByInventory(availabilityByVariant, component.variantId())))
                        .toList()))
                .toList();
        // A combo cannot be sold while one of its groups can no longer collect its minimum from
        // what is orderable, whatever the container's own offering says: shown as unavailable
        // rather than let a customer fill a basket the cart then refuses by name.
        Set<UUID> unsatisfiableContainers = pricedCombos.stream()
                .filter(group -> !group.satisfiable())
                .map(MenuComboGroup::containerVariantId)
                .collect(Collectors.toUnmodifiableSet());
        List<MenuProduct> comboAwareProducts = unsatisfiableContainers.isEmpty()
                ? pricedProducts
                : pricedProducts.stream()
                        .map(product -> product.withoutOrderable(unsatisfiableContainers))
                        .toList();

        return Optional.of(new StorefrontMenu(
                publication, locale, currency, categories, comboAwareProducts, pricedGroups, pricedCombos));
    }

    private static boolean isOffered(
            UUID variantId, Map<UUID, OfferingStatus> offeringByVariant, Set<UUID> channelExcludedVariantIds) {
        OfferingStatus offering = offeringByVariant.get(variantId);
        return offering != null && offering != OfferingStatus.HIDDEN && !channelExcludedVariantIds.contains(variantId);
    }

    private static boolean orderableByInventory(Map<UUID, VariantAvailability> byVariant, UUID variantId) {
        VariantAvailability decision = byVariant.get(variantId);
        return decision == null || decision.orderable();
    }

    /**
     * Every combo group of the publication, resolved into the customer's language. Prices and
     * availability are attached after the whole menu is read, as they are for a variant.
     */
    @SuppressWarnings("unchecked")
    private static List<MenuComboGroup> combosOf(List<PublicationItem> items, List<String> preference) {
        List<MenuComboGroup> groups = new ArrayList<>();
        for (PublicationItem item : items) {
            Map<String, Object> content = item.content();
            List<MenuComboComponent> components = new ArrayList<>();
            if (content.get("components") instanceof List<?> published) {
                for (Object element : published) {
                    Map<String, Object> component = (Map<String, Object>) element;
                    String dish = nameIn(component.get("productNames"), preference);
                    String size = nameIn(component.get("variantNames"), preference);
                    String productId = string(component, "productId");
                    components.add(new MenuComboComponent(
                            UUID.fromString(String.valueOf(component.get("componentId"))),
                            UUID.fromString(String.valueOf(component.get("variantId"))),
                            productId == null ? null : UUID.fromString(productId),
                            // A component with no wording at all is the empty string rather than
                            // "null": the same "odd label beats a failed menu" choice name() makes.
                            dish != null ? dish : size != null ? size : "",
                            dish != null ? size : null,
                            Math.max(1, intOf(component, "defaultQuantity")),
                            intOf(component, "sortOrder"),
                            false,
                            null));
                }
            }
            groups.add(new MenuComboGroup(
                    item.entityId(),
                    UUID.fromString(String.valueOf(content.get("containerVariantId"))),
                    code(content),
                    name(content, preference),
                    intOf(content, "minimumSelections"),
                    intOf(content, "maximumSelections"),
                    Boolean.TRUE.equals(content.get("allowSameComponentMultipleTimes")),
                    intOf(content, "sortOrder"),
                    components));
        }
        groups.sort(java.util.Comparator.comparingInt(MenuComboGroup::sortOrder));
        return List.copyOf(groups);
    }

    /** A published {@code locale -> {name}} map resolved in the first preferred locale that has one, else any. */
    @SuppressWarnings("unchecked")
    private static @Nullable String nameIn(@Nullable Object rawNames, List<String> preference) {
        if (!(rawNames instanceof Map<?, ?> names) || names.isEmpty()) {
            return null;
        }
        Map<String, Map<String, String>> byLocale = (Map<String, Map<String, String>>) names;
        for (String wanted : preference) {
            Map<String, String> entry = byLocale.get(wanted);
            if (entry != null && entry.get("name") != null) {
                return entry.get("name");
            }
        }
        return byLocale.values().stream()
                .map(entry -> entry.get("name"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * The product's own use of its attached groups where it overrides the shared group's rules
     * (ADR 0136), already resolved to the effective values.
     */
    private static List<MenuModifierGroupPolicy> policiesOf(Map<String, Object> content) {
        if (!(content.get("modifierGroupPolicies") instanceof List<?> published)) {
            return List.of();
        }
        List<MenuModifierGroupPolicy> policies = new ArrayList<>();
        for (Object element : published) {
            if (element instanceof Map<?, ?> policy) {
                policies.add(new MenuModifierGroupPolicy(
                        UUID.fromString(String.valueOf(policy.get("groupId"))),
                        Boolean.TRUE.equals(policy.get("required")),
                        policy.get("minimumSelections") instanceof Number min ? min.intValue() : 0,
                        policy.get("maximumSelections") instanceof Number max ? max.intValue() : 1));
            }
        }
        return List.copyOf(policies);
    }

    /**
     * Row 4.4a: a bound menu's membership when this branch has one, exactly
     * as {@code location_offerings} always answered when it did not. See
     * this class's own doc for why a tenant with no bindings anywhere is
     * provably unaffected — {@code findBoundMenuId} returns empty and this
     * falls straight through to the unmodified {@code offeringsForLocation}
     * call.
     */
    private Map<UUID, OfferingStatus> offeringsFor(UUID tenantId, UUID brandId, UUID locationId, String channelCode) {
        Optional<UUID> boundMenuId = menus.findBoundMenuId(tenantId, brandId, locationId, channelCode);
        if (boundMenuId.isPresent()) {
            return menus.menuMembershipOfferings(tenantId, brandId, boundMenuId.get());
        }
        return store.offeringsForLocation(tenantId, locationId).stream()
                .collect(Collectors.toMap(
                        LocationOffering::variantId, LocationOffering::status, (first, second) -> first));
    }

    @SuppressWarnings("unchecked")
    private static List<MenuVariant> variantsOf(
            PublicationItem item,
            Map<UUID, OfferingStatus> offeringByVariant,
            Set<UUID> channelExcludedVariantIds,
            Set<UUID> outOfWindowVariantIds) {

        Object raw = item.content().get("variants");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }

        List<MenuVariant> variants = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> variant = (Map<String, Object>) element;
            UUID variantId = UUID.fromString(String.valueOf(variant.get("variantId")));
            OfferingStatus offering = offeringByVariant.get(variantId);

            // The HIDDEN half looks redundant and is not safe to remove.
            // JdbcCatalogStore.offeringsForLocation already excludes HIDDEN in
            // SQL, so dropping either guard alone changes nothing and no test
            // fails — which cost three separate wrong conclusions before
            // somebody removed both at once and watched two tests go red. Take
            // out the pair or neither; see that method's own javadoc.
            if (offering == null
                    || offering == OfferingStatus.HIDDEN
                    || channelExcludedVariantIds.contains(variantId)) {
                continue;
            }
            variants.add(new MenuVariant(
                    variantId,
                    // Nullable, and read as null rather than the string "null".
                    // A variant genuinely may have no SKU, and String.valueOf on
                    // an absent key hands the customer app a real-looking value.
                    string(variant, "sku"),
                    string(variant, "unitCode"),
                    Boolean.TRUE.equals(variant.get("isDefault")),
                    // Shown but not orderable, which is what a customer needs to
                    // see rather than an item that silently vanished.
                    offering == OfferingStatus.AVAILABLE,
                    // Row 4.2g: shown, but not orderable right now, distinct from
                    // an 86'd dish — the storefront tells the two states apart.
                    !outOfWindowVariantIds.contains(variantId),
                    // Attached after the whole menu is read; see menuFor.
                    null,
                    // Rows 4.4c/4.4d: attached after the whole menu is read too,
                    // by withAvailability — see menuFor.
                    null));
        }
        return variants;
    }

    private static List<CommentPresetOption> commentPresetsOf(
            List<uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.ProductPresetRow> rows,
            Map<UUID, Map<String, String>> translations,
            List<String> preference) {
        return rows.stream()
                .map(row -> {
                    Map<String, String> labels = LocalizedLabels.merge(
                            row.labelRu(),
                            row.labelUz(),
                            row.labelEn(),
                            translations.getOrDefault(row.presetId(), Map.of()));
                    String label = LocalizedLabels.pick(labels, preference);
                    return new CommentPresetOption(
                            row.code(),
                            row.labelRu(),
                            row.labelUz(),
                            row.labelEn(),
                            labels,
                            label == null ? row.code() : label);
                })
                .toList();
    }

    /**
     * The locales a preset's wording is wanted in, best first: the customer's own (the storefront
     * sends the catalog's code, so {@code uz} is the platform's {@code uz-Latn}), then the brand's
     * default. {@link LocalizedLabels#pick} falls through to whatever wording exists after that.
     */
    private static List<String> presetPreference(String requested, Optional<String> brandDefault) {
        List<String> preference = new ArrayList<>();
        preference.add(CatalogLocales.toPlatformLocale(requested));
        brandDefault.ifPresent(preference::add);
        return preference;
    }

    /**
     * The catalog locales a published name is wanted in, best first: the customer's own, the
     * brand's default, then the server's configured one. A name in none of them is still shown
     * (see {@link #name}) rather than the code.
     */
    private List<String> namePreference(String requested, Optional<String> brandDefault) {
        List<String> preference = new ArrayList<>();
        preference.add(requested);
        brandDefault.map(CatalogLocales::forBrandLocale).ifPresent(preference::add);
        preference.add(defaultLocale);
        return preference;
    }

    /**
     * Row 4.2g: every variant at this location whose own sale schedule does
     * not include the current local moment, read live.
     *
     * <p>Empty, rather than refusing the whole menu read, when this tenant's
     * location id does not resolve to a zone — the same "unaffected by
     * default" guarantee {@code CartSaleWindowRules}' own callers rely on,
     * applied here to a read instead of a refusal.
     */
    private Set<UUID> outOfWindowVariantIds(UUID tenantId, UUID locationId) {
        Optional<ZoneId> zone = tenantContext.timezoneOf(tenantId, locationId);
        if (zone.isEmpty()) {
            return Set.of();
        }
        Map<UUID, List<ItemSaleSchedule.Window>> windowsByVariant =
                store.itemSaleWindowsForLocation(tenantId, locationId);
        if (windowsByVariant.isEmpty()) {
            return Set.of();
        }
        LocalDateTime local = LocalDateTime.ofInstant(clock.instant(), zone.get());
        Set<UUID> outOfWindow = new java.util.HashSet<>();
        windowsByVariant.forEach((variantId, windows) -> {
            if (!new ItemSaleSchedule(windows).isOnSaleAt(local)) {
                outOfWindow.add(variantId);
            }
        });
        return outOfWindow;
    }

    @SuppressWarnings("unchecked")
    private static List<MenuModifierOption> optionsOf(PublicationItem item, List<String> preference) {
        Object raw = item.content().get("options");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<MenuModifierOption> options = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> option = (Map<String, Object>) element;
            options.add(new MenuModifierOption(
                    UUID.fromString(String.valueOf(option.get("optionId"))),
                    code(option),
                    intOf(option, "maximumQuantity"),
                    null,
                    nameIn(option.get("names"), preference)));
        }
        return options;
    }

    /**
     * Resolves a name in the first of {@code preference} the entity has -- the customer's
     * language, then the brand's default, then the server's -- falling back to any published one.
     *
     * <p>Publication already refused to proceed without a name in the brand
     * default, so this cannot normally return the code — but it returns the code
     * rather than throwing if it somehow does, because a menu with one odd label
     * beats a menu that fails to load.
     */
    private static String name(Map<String, Object> content, List<String> preference) {
        Map<String, String> wording = wording(content, preference);
        String name = wording == null ? null : wording.get("name");
        if (name != null) {
            return name;
        }
        // The loader writes a code on every item, so this is reached with a real
        // value; the empty string is the same "odd label over failed menu" choice
        // for content hand-written around the loader.
        String code = string(content, "code");
        return code != null ? code : "";
    }

    /**
     * The description that was published with the name {@link #name} shows, or null when that
     * wording has none.
     *
     * <p>A description travels with its name: a dish shown under the brand default's name (the
     * customer's language has none) is described in the brand default too, and a dish shown under
     * the customer's own name is never described in another language -- a Russian sentence under an
     * Uzbek title is worse than no sentence.
     */
    private static @Nullable String description(Map<String, Object> content, List<String> preference) {
        Map<String, String> wording = wording(content, preference);
        return wording == null ? null : wording.get("description");
    }

    /**
     * The published wording (name and description) of the first locale in {@code preference} that
     * has a name, else the first one the entity carries; null when it carries none.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, String> wording(Map<String, Object> content, List<String> preference) {
        Object raw = content.get("names");
        if (!(raw instanceof Map<?, ?> names) || names.isEmpty()) {
            return null;
        }
        Map<String, Map<String, String>> byLocale = (Map<String, Map<String, String>>) names;
        for (String wanted : preference) {
            Map<String, String> entry = byLocale.get(wanted);
            if (entry != null && entry.get("name") != null) {
                return entry;
            }
        }
        return byLocale.values().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static List<String> mediaIds(Map<String, Object> content) {
        Object raw = content.get("mediaAssetIds");
        return raw instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * A published list of identifier strings.
     *
     * <p>Absent on publications written before membership was carried. Those are
     * immutable and still served, so this reads as "no membership" rather than
     * failing the whole menu.
     */
    private static List<UUID> idList(Map<String, Object> content, String key) {
        Object raw = content.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(value -> UUID.fromString(String.valueOf(value)))
                .toList();
    }

    /** Whether any other category names this one as its parent. */
    private static boolean isParent(UUID categoryId, List<PublicationItem> categoryItems) {
        return categoryItems.stream().anyMatch(item -> categoryId.equals(parentOf(item.content())));
    }

    private static @Nullable UUID parentOf(Map<String, Object> content) {
        // Absent means a root category. The "null" string check is kept for
        // publications written before absent values were omitted, which are
        // immutable and therefore still out there.
        String parent = string(content, "parentCategoryId");
        return parent == null || "null".equals(parent) ? null : UUID.fromString(parent);
    }

    private static @Nullable String string(Map<String, Object> content, String key) {
        Object value = content.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The entity's code, which the loader writes on every published item.
     *
     * <p>The empty string covers only content written around the loader, on the
     * same reasoning as {@link #name}: one odd label beats a menu that fails to
     * load.
     */
    private static String code(Map<String, Object> content) {
        String code = string(content, "code");
        return code != null ? code : "";
    }

    /**
     * One platform URL per media asset, in the order the publication lists them.
     *
     * <p>Built as a string here rather than resolved through the media module,
     * and that is deliberate: catalog knows an asset id and nothing else about
     * media, and the endpoint at the other end is what enforces that the asset is
     * PUBLIC and AVAILABLE. An id that names a private or withdrawn asset
     * therefore yields a URL that answers 404 -- a broken image, which is what a
     * retired object looks like behind any CDN, rather than a leak.
     */
    private static List<String> imageUrls(UUID tenantId, List<String> mediaAssetIds) {
        return mediaAssetIds.stream()
                .map(assetId -> "/api/v1/storefront/tenants/%s/media/%s".formatted(tenantId, assetId))
                .toList();
    }

    private static int intOf(Map<String, Object> content, String key) {
        Object value = content.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * One location's live menu, exactly as a customer is shown it.
     *
     * @param publicationId the exact snapshot served, so a cache can key on it
     * @param currency the price book's currency, or null when this brand has no
     *     active price book for this location and channel. Null means every
     *     amount below is null too, and a client must render the menu without
     *     prices rather than as free.
     */
    public record StorefrontMenu(
            UUID publicationId,
            String locale,
            @Nullable String currency,
            List<MenuCategory> categories,
            List<MenuProduct> products,
            List<MenuModifierGroup> modifierGroups,
            // ADR 0136: the choices every combo asks for, empty when the menu sells none. A
            // combo's container is the product variant a group names as its container; the
            // customer never buys it directly, only picks from these.
            List<MenuComboGroup> comboGroups) {}

    /**
     * One choice a combo asks the customer to make (ADR 0136).
     *
     * @param containerVariantId the sellable "Комбо №1" variant. It has no price of its own
     * @param minimumSelections how many picks the group needs before the combo can be ordered
     * @param maximumSelections how many it takes
     * @param allowSameComponentMultipleTimes whether one component may be picked more than once
     */
    public record MenuComboGroup(
            UUID comboGroupId,
            UUID containerVariantId,
            String code,
            String name,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameComponentMultipleTimes,
            int sortOrder,
            List<MenuComboComponent> components) {

        MenuComboGroup withComponents(List<MenuComboComponent> next) {
            return new MenuComboGroup(
                    comboGroupId,
                    containerVariantId,
                    code,
                    name,
                    minimumSelections,
                    maximumSelections,
                    allowSameComponentMultipleTimes,
                    sortOrder,
                    next);
        }

        /**
         * Whether the orderable components can still fill the group's minimum. A group that
         * repeats a component fills from one; one that does not needs as many components as
         * the minimum names.
         */
        boolean satisfiable() {
            long orderable =
                    components.stream().filter(MenuComboComponent::orderable).count();
            if (minimumSelections <= 0) {
                return true;
            }
            return allowSameComponentMultipleTimes ? orderable > 0 : orderable >= minimumSelections;
        }
    }

    /**
     * One real dish or drink offered inside a combo group.
     *
     * @param componentId the pairing of the group with the variant: what a pick names, and
     *     what the price below is keyed to
     * @param name the dish's own name in the customer's language
     * @param variantName the size or form, when the variant carries wording of its own
     * @param defaultQuantity units one pick puts on the order
     * @param orderable false means shown as sold out rather than hidden
     * @param amountMinor what one unit costs as part of this combo, per unit; null when no
     *     active price resolves, which is never read as free
     */
    public record MenuComboComponent(
            UUID componentId,
            UUID variantId,
            @Nullable UUID productId,
            String name,
            @Nullable String variantName,
            int defaultQuantity,
            int sortOrder,
            boolean orderable,
            @Nullable Long amountMinor) {

        MenuComboComponent priced(@Nullable Long price, boolean isOrderable) {
            return new MenuComboComponent(
                    componentId,
                    variantId,
                    productId,
                    name,
                    variantName,
                    defaultQuantity,
                    sortOrder,
                    isOrderable,
                    price);
        }
    }

    /**
     * How one product uses a modifier group it attaches, where that differs from the shared
     * group (ADR 0136). The three values are the effective ones, so a client replaces the
     * group's own outright.
     */
    public record MenuModifierGroupPolicy(
            UUID modifierGroupId, boolean required, int minimumSelections, int maximumSelections) {}

    /**
     * One shelf of the menu, holding only what this location serves.
     *
     * @param productIds in the category's own order, filtered to what this location serves
     */
    public record MenuCategory(
            UUID categoryId,
            String code,
            String name,
            @Nullable UUID parentCategoryId,
            int sortOrder,
            List<UUID> productIds) {}

    /**
     * One dish as the customer sees it, variants and pictures included.
     *
     * @param imageUrls one platform URL per entry of {@code mediaAssetIds}, in the
     *     same order. Served rather than signed here: the URL is stable, cacheable
     *     with the menu, and resolves to a short-lived signed one at the moment a
     *     browser asks. ADR 0010 forbids a public bucket outright and its CDN
     *     origin does not exist yet, so this is the only shape that works today
     *     and the same URL can be fronted by the CDN when it lands.
     */
    public record MenuProduct(
            UUID productId,
            String code,
            String name,
            @Nullable String description,
            List<String> mediaAssetIds,
            List<String> imageUrls,
            List<MenuVariant> variants,
            List<UUID> modifierGroupIds,
            // Row 2.1b: the coded kitchen-instruction presets this product
            // offers on a line, in display order — read live, not from the
            // publication; see CommentPresetLookup's own doc for why.
            List<CommentPresetOption> commentPresets,
            // ADR 0136: the combo groups whose container is one of this product's variants,
            // in the author's order. Empty on every product that is not a combo.
            List<UUID> comboGroupIds,
            // ADR 0136: this product's own min/max/required for a group it attaches, for the
            // groups where it overrides the shared one.
            List<MenuModifierGroupPolicy> modifierGroupPolicies) {

        MenuProduct withPrices(Map<UUID, Long> variantPrices) {
            return new MenuProduct(
                    productId,
                    code,
                    name,
                    description,
                    mediaAssetIds,
                    imageUrls,
                    variants.stream()
                            .map(variant -> variant.withPrice(variantPrices.get(variant.variantId())))
                            .toList(),
                    modifierGroupIds,
                    commentPresets,
                    comboGroupIds,
                    modifierGroupPolicies);
        }

        /** A combo whose group can no longer be filled is shown, as sold out, never hidden. */
        MenuProduct withoutOrderable(Set<UUID> variantIds) {
            return new MenuProduct(
                    productId,
                    code,
                    name,
                    description,
                    mediaAssetIds,
                    imageUrls,
                    variants.stream()
                            .map(variant -> variantIds.contains(variant.variantId())
                                    ? variant.withAvailability(false, variant.remainingQuantity())
                                    : variant)
                            .toList(),
                    modifierGroupIds,
                    commentPresets,
                    comboGroupIds,
                    modifierGroupPolicies);
        }

        /**
         * Rows 4.4c/4.4d, storefront half. A variant absent from {@code
         * byVariant} carries no additional restriction — see {@code
         * MenuAvailabilityLookup#availabilityFor}'s own doc — so it is left
         * exactly as the offering-based read already produced it.
         */
        MenuProduct withAvailability(Map<UUID, VariantAvailability> byVariant) {
            return new MenuProduct(
                    productId,
                    code,
                    name,
                    description,
                    mediaAssetIds,
                    imageUrls,
                    variants.stream()
                            .map(variant -> {
                                VariantAvailability decision = byVariant.get(variant.variantId());
                                return decision == null
                                        ? variant
                                        : variant.withAvailability(decision.orderable(), decision.remainingQuantity());
                            })
                            .toList(),
                    modifierGroupIds,
                    commentPresets,
                    comboGroupIds,
                    modifierGroupPolicies);
        }
    }

    /**
     * One preset a product offers on a line, every locale so the storefront renders its own.
     *
     * @param labelRu/labelUz/labelEn the platform triple's columns, kept for a client that
     *     reads only them
     * @param labels every wording the preset has, keyed by locale -- the triple plus any locale
     *     a tenant's brands support beyond it (row 10.12, V0430)
     * @param label the wording resolved for the locale the menu was requested in, then the
     *     brand's default, then whichever exists; the preset's code only if it has no wording
     *     at all, which the schema does not allow
     */
    public record CommentPresetOption(
            String code, String labelRu, String labelUz, String labelEn, Map<String, String> labels, String label) {}

    /**
     * One orderable size or form of a product.
     *
     * @param sku null when the variant has none; never the string "null"
     * @param orderable false means shown as sold out rather than hidden
     * @param onSaleNow row 4.2g: false means this variant has its own sale
     *     schedule and the current moment falls outside every window on it —
     *     shown, distinct from {@code orderable}, so a customer can tell "sold
     *     out today" from "not on the menu right now, try again during
     *     breakfast hours" apart. Always true for a variant with no schedule.
     * @param amountMinor null when this variant has no active price. Not zero:
     *     an unpriced variant is a menu that is not finished, and showing it as
     *     free is how a brand sells a dish for nothing.
     * @param remainingQuantity rows 4.4c/4.4d, storefront half: set only for a
     *     QUANTITY item whose remaining stock has dropped to inventory's own
     *     small displayed threshold — never above it, and never when {@code
     *     orderable} is already false. See {@code MenuAvailabilityLookup}'s
     *     own doc.
     */
    public record MenuVariant(
            UUID variantId,
            @Nullable String sku,
            @Nullable String unitCode,
            boolean isDefault,
            boolean orderable,
            boolean onSaleNow,
            @Nullable Long amountMinor,
            @Nullable BigDecimal remainingQuantity) {

        MenuVariant withPrice(@Nullable Long price) {
            return new MenuVariant(variantId, sku, unitCode, isDefault, orderable, onSaleNow, price, remainingQuantity);
        }

        /**
         * Rows 4.4c/4.4d: ANDed with the offering-based {@code orderable}
         * already computed by {@code variantsOf} — inventory can only ever
         * take an already-orderable item off the menu, never put a
         * catalog-hidden one back on it.
         */
        MenuVariant withAvailability(boolean inventoryOrderable, @Nullable BigDecimal remainingQuantity) {
            return new MenuVariant(
                    variantId,
                    sku,
                    unitCode,
                    isDefault,
                    orderable && inventoryOrderable,
                    onSaleNow,
                    amountMinor,
                    remainingQuantity);
        }
    }

    /**
     * A choice offered on a product, published with its selection rules.
     *
     * @param allowSameOptionMultipleTimes whether one option may be taken more
     *     than once, without which a client cannot honour maximumQuantity and
     *     has to pin it to one
     */
    public record MenuModifierGroup(
            UUID modifierGroupId,
            String code,
            String name,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            List<MenuModifierOption> options) {

        MenuModifierGroup withPrices(Map<UUID, Long> optionPrices) {
            return new MenuModifierGroup(
                    modifierGroupId,
                    code,
                    name,
                    required,
                    minimumSelections,
                    maximumSelections,
                    allowSameOptionMultipleTimes,
                    options.stream()
                            .map(option -> option.withPrice(optionPrices.get(option.optionId())))
                            .toList());
        }
    }

    /**
     * One selectable option within a modifier group.
     *
     * @param amountMinor null when unpriced, and never zero for "no price".
     */
    public record MenuModifierOption(
            UUID optionId,
            String code,
            int maximumQuantity,
            @Nullable Long amountMinor,
            // What the customer reads, in their language then the brand's. Null when nobody
            // named the option, and a client then shows the code as it always has.
            @Nullable String name) {

        MenuModifierOption withPrice(@Nullable Long price) {
            return new MenuModifierOption(optionId, code, maximumQuantity, price, name);
        }
    }
}
