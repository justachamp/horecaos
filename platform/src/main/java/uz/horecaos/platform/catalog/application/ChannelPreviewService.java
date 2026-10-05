package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.VariantPricingLookup;
import uz.horecaos.platform.catalog.application.ChannelProjection.PriceAuthority;
import uz.horecaos.platform.catalog.application.ChannelProjection.ResolvedMedia;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.AssembledMenu;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.AssemblyInput;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuCategory;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuComboComponent;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuComboGroup;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuModifierGroup;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuModifierOption;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuProduct;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuVariant;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.ChannelFindings;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore.MarketplaceBindingRow;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * A dry run of the publication pipeline, addressed at one channel at one branch
 * (ADR 0138): what that channel would receive if the draft were published right
 * now, and what would stop it.
 *
 * <p><strong>Not a second projection engine.</strong> It runs the machinery the
 * live paths already use and returns the result instead of committing it. The
 * draft is snapshotted by {@link CatalogSnapshotLoader} exactly as {@code
 * CatalogPublicationService.publish} snapshots it, validated by the same {@link
 * CatalogValidator}, and then assembled by {@link StorefrontCatalogQuery#assemble}
 * — the very method a customer's menu read goes through — so the gates, in the
 * order ADR 0138 fixes them, are one implementation rather than two kept in
 * agreement by discipline:
 *
 * <ol>
 *   <li>location offerings, or a bound named menu where the branch has one;
 *   <li>the channel's sparse exclusions, brand-wide or narrowed to the branch;
 *   <li>price: the channel's price plane (CHANNEL over LOCATION over BRAND), or
 *       none at all when the aggregator sets the price;
 *   <li>media: this channel's override over its per-channel relation over the
 *       entity's own images.
 * </ol>
 *
 * <p>The first three live in {@code assemble}. The fourth is per channel, which the
 * draft's channel-agnostic items cannot say, so {@link ChannelMediaLayers} applies it
 * to the items before they are assembled — and {@code publish} calls the same
 * function for the channel it publishes to, so the images drawn here are the ones a
 * customer is served once the draft is published.
 *
 * <p><strong>A read.</strong> Nothing is written, no content hash is minted, and
 * the result cannot be fetched by reference: every call recomputes from the
 * current draft (ADR 0138's "accepted trade-off"). Price, availability and
 * exclusions are read live, as the storefront reads them.
 *
 * <p><strong>Where it deliberately matches the live menu rather than the record's
 * wording.</strong> ADR 0138 step 1 says a variant whose offering is not {@code
 * AVAILABLE} is absent. The live storefront it says it matches shows an {@code
 * UNAVAILABLE} (86'd) variant, not orderable; the projection does the same and
 * carries {@code orderable = false}, because the alternative — dropping it —
 * would make this a preview of a menu no customer is shown, and would leave the
 * outbound availability push (ADR 0040) nothing to say a dish is stopped with.
 * {@code HIDDEN} and absent offerings are dropped, as on the storefront.
 */
@Service
public class ChannelPreviewService {

    private final JdbcCatalogStore store;
    private final JdbcChannelProjectionStore projections;
    private final CatalogValidator validator;
    private final CatalogSnapshotLoader snapshots;
    private final StorefrontCatalogQuery storefront;
    private final SalesChannelLookup channels;
    private final MarketplaceRulesets rulesets;
    private final VariantPricingLookup pricing;

    public ChannelPreviewService(
            JdbcCatalogStore store,
            JdbcChannelProjectionStore projections,
            CatalogValidator validator,
            CatalogSnapshotLoader snapshots,
            StorefrontCatalogQuery storefront,
            SalesChannelLookup channels,
            MarketplaceRulesets rulesets,
            VariantPricingLookup pricing) {
        this.store = store;
        this.projections = projections;
        this.validator = validator;
        this.snapshots = snapshots;
        this.storefront = storefront;
        this.channels = channels;
        this.rulesets = rulesets;
        this.pricing = pricing;
    }

    /**
     * What this channel would receive at one branch.
     *
     * @throws UnknownPreviewTargetException when the catalog, channel, branch or
     *     binding is not this tenant's (or this brand's) — one answer for "does
     *     not exist" and "is somebody else's", so an id cannot be probed
     * @throws PreviewTargetRequiredException when no branch was named and the
     *     channel does not sell at exactly one
     */
    @Transactional(readOnly = true)
    public ChannelPreview preview(PreviewRequest request) {
        UUID tenantId = request.tenantId();
        UUID brandId = request.brandId();

        if (!store.catalogBelongsTo(tenantId, brandId, request.catalogId())) {
            throw new UnknownPreviewTargetException(
                    "Catalog %s does not belong to brand %s".formatted(request.catalogId(), brandId));
        }
        SalesChannel channel = channels.byId(tenantId, request.channelId())
                .orElseThrow(() -> new UnknownPreviewTargetException("No sales channel " + request.channelId()));

        List<UUID> enabledLocations = projections.activeLocationsOfChannel(tenantId, brandId, channel.id());
        Target target = resolveTarget(request, channel, enabledLocations);
        boolean enabledHere = enabledLocations.contains(target.locationId());

        CatalogValidator.Snapshot snapshot = snapshots.load(tenantId, brandId, request.catalogId());
        ValidationFinding.Report universal = validator.validate(snapshot);

        // The channel's own items: the call publish makes for this channel, so what is drawn
        // below is what would be written, images and the blockers on them included.
        CatalogSnapshotLoader.ChannelItems channelItems =
                snapshots.toPublicationItems(snapshot, tenantId, brandId, channel);

        String locale = request.locale() == null ? snapshot.defaultLocale() : request.locale();
        PriceAuthority authority = channel.externallyPriced() ? PriceAuthority.EXTERNAL : PriceAuthority.HORECAOS;

        AssembledMenu menu;
        if (enabledHere) {
            menu = storefront.assemble(new AssemblyInput(
                    tenantId,
                    brandId,
                    target.locationId(),
                    locale,
                    channel.code(),
                    itemsOf(channelItems.items(), EntityType.CATEGORY),
                    itemsOf(channelItems.items(), EntityType.PRODUCT),
                    itemsOf(channelItems.items(), EntityType.MODIFIER_GROUP),
                    itemsOf(channelItems.items(), EntityType.COMBO_GROUP),
                    authority == PriceAuthority.EXTERNAL));
        } else {
            // The channel does not sell at this branch: it would receive nothing,
            // and computing a menu for it would preview a fiction.
            menu = new AssembledMenu(null, List.of(), List.of(), List.of(), List.of());
        }

        List<MenuProduct> products = menu.products().stream()
                .sorted(Comparator.comparing(MenuProduct::productId))
                .toList();

        ChannelProjection projection = new ChannelProjection(
                tenantId,
                brandId,
                channel.id(),
                channel.code(),
                target.locationId(),
                authority,
                menu.currency(),
                menu.categories(),
                products,
                menu.modifierGroups(),
                channelItems.media());

        List<PreviewFinding> findings = new ArrayList<>();
        Map<UUID, UUID> productOfVariant = new HashMap<>();
        snapshot.variants().forEach(variant -> productOfVariant.put(variant.id(), variant.productId()));
        // A combo component has no page of its own either: it is authored on the product whose variant
        // is the combo's container.
        Map<UUID, UUID> productOfComponent = new HashMap<>();
        for (ComboGroup group : snapshot.composite().comboGroups()) {
            UUID container = snapshot.composite().productIdByVariant().get(group.containerVariantId());
            if (container != null) {
                snapshot.composite()
                        .componentsByGroup()
                        .getOrDefault(group.id(), List.of())
                        .forEach(component -> productOfComponent.put(component.id(), container));
            }
        }
        for (ValidationFinding finding : universal.findings()) {
            findings.add(new PreviewFinding(
                    finding, FindingSource.CATALOG, ownerProduct(finding, productOfVariant, productOfComponent)));
        }
        List<ValidationFinding> projectionFindings = new ArrayList<>(channelItems.findings());
        projectionFindings.addAll(projectionFindings(channel, enabledHere, projection, menu.comboGroups(), snapshot));
        for (ValidationFinding finding : projectionFindings) {
            findings.add(new PreviewFinding(
                    finding, FindingSource.PROJECTION, ownerProduct(finding, productOfVariant, productOfComponent)));
        }
        List<ValidationFinding> marketplaceFindings = marketplaceFindings(target.binding(), projection);
        for (ValidationFinding finding : marketplaceFindings) {
            findings.add(new PreviewFinding(
                    finding, FindingSource.MARKETPLACE, ownerProduct(finding, productOfVariant, productOfComponent)));
        }

        // What publish decides for this channel: the catalog's blockers and the blockers on the
        // channel's own images, which publish adds to the catalog's report.
        boolean publishable =
                universal.publishable() && channelItems.findings().stream().noneMatch(ChannelPreviewService::isBlocker);
        boolean channelReady = publishable
                && projectionFindings.stream().noneMatch(ChannelPreviewService::isBlocker)
                && marketplaceFindings.stream().noneMatch(ChannelPreviewService::isBlocker);

        // One page of products, keyset on the product id. The whole projection is
        // recomputed per page — a preview is never cached or referenced by id — so
        // the id is the only thing that has to survive between two requests.
        List<MenuProduct> afterCursor = request.afterProductId() == null
                ? products
                : products.stream()
                        .filter(product -> product.productId().compareTo(request.afterProductId()) > 0)
                        .toList();
        boolean hasMore = afterCursor.size() > request.limit();
        List<MenuProduct> page = hasMore ? afterCursor.subList(0, request.limit()) : afterCursor;

        return new ChannelPreview(
                channel,
                target.locationId(),
                target.binding(),
                locale,
                authority,
                menu.currency(),
                publishable,
                channelReady,
                findings,
                menu.categories(),
                menu.modifierGroups(),
                menu.comboGroups(),
                List.copyOf(page),
                hasMore,
                channelItems.media());
    }

    /**
     * The branches this channel sells at, and the marketplace binding at each —
     * what a console needs to offer a branch to preview.
     */
    @Transactional(readOnly = true)
    public List<PreviewTarget> targets(UUID tenantId, UUID brandId, UUID channelId) {
        SalesChannel channel = channels.byId(tenantId, channelId)
                .orElseThrow(() -> new UnknownPreviewTargetException("No sales channel " + channelId));
        List<PreviewTarget> targets = new ArrayList<>();
        for (JdbcChannelProjectionStore.BranchRow branch :
                projections.activeBranchesOfChannel(tenantId, brandId, channel.id())) {
            Optional<MarketplaceBindingRow> binding = channel.providerInstallation()
                    .flatMap(installation ->
                            projections.bindingCovering(tenantId, installation, brandId, branch.locationId()));
            targets.add(new PreviewTarget(branch.locationId(), branch.displayName(), binding.orElse(null)));
        }
        return List.copyOf(targets);
    }

    // ----------------------------------------------------------------- target

    private Target resolveTarget(PreviewRequest request, SalesChannel channel, List<UUID> enabledLocations) {
        UUID tenantId = request.tenantId();
        UUID brandId = request.brandId();

        @Nullable MarketplaceBindingRow binding = null;
        @Nullable UUID locationId = request.locationId();

        if (request.bindingId() != null) {
            binding = projections
                    .marketplaceBinding(tenantId, request.bindingId())
                    .filter(row -> channel.providerInstallation()
                            .filter(row.installationId()::equals)
                            .isPresent())
                    .filter(row -> row.brandId() == null || row.brandId().equals(brandId))
                    .orElseThrow(() -> new UnknownPreviewTargetException(
                            "No marketplace binding %s on this channel".formatted(request.bindingId())));
            if (locationId == null) {
                locationId = binding.locationId();
            } else if (binding.locationId() != null && !binding.locationId().equals(locationId)) {
                throw new UnknownPreviewTargetException("That binding is bound to a different branch");
            }
        }

        if (locationId == null) {
            if (enabledLocations.size() == 1) {
                locationId = enabledLocations.get(0);
            } else {
                throw new PreviewTargetRequiredException(enabledLocations.size());
            }
        }
        if (!projections.locationBelongsToBrand(tenantId, brandId, locationId)) {
            throw new UnknownPreviewTargetException("No branch %s in this brand".formatted(locationId));
        }

        if (binding == null) {
            final UUID branch = locationId;
            binding = channel.providerInstallation()
                    .flatMap(installation -> projections.bindingCovering(tenantId, installation, brandId, branch))
                    .orElse(null);
        }
        return new Target(locationId, binding);
    }

    private record Target(UUID locationId, @Nullable MarketplaceBindingRow binding) {}

    private static List<PublicationItem> itemsOf(List<PublicationItem> items, EntityType type) {
        return items.stream().filter(item -> item.entityType() == type).toList();
    }

    // --------------------------------------------------------------- findings

    /**
     * Mechanical facts about what this channel would receive here — none of them
     * a marketplace's opinion, so a storefront preview raises them as readily as
     * an aggregator's.
     */
    private List<ValidationFinding> projectionFindings(
            SalesChannel channel,
            boolean enabledHere,
            ChannelProjection projection,
            List<MenuComboGroup> comboGroups,
            CatalogValidator.Snapshot snapshot) {

        List<ValidationFinding> findings = new ArrayList<>();

        if (channel.status() == SalesChannel.Status.ARCHIVED) {
            findings.add(ValidationFinding.blocker(
                    ChannelFindings.CHANNEL_ARCHIVED,
                    EntityType.CATALOG,
                    null,
                    channel.code(),
                    "Channel %s is archived: publication to it is refused".formatted(channel.code())));
        } else if (channel.status() == SalesChannel.Status.INACTIVE) {
            findings.add(ValidationFinding.warning(
                    ChannelFindings.CHANNEL_INACTIVE,
                    EntityType.CATALOG,
                    null,
                    channel.code(),
                    "Channel %s is switched off: nothing is being sent to it".formatted(channel.code())));
        }

        if (!enabledHere) {
            findings.add(ValidationFinding.blocker(
                    ChannelFindings.CHANNEL_NOT_ENABLED_AT_LOCATION,
                    EntityType.CATALOG,
                    null,
                    channel.code(),
                    "Channel %s does not sell at this branch, so it would receive nothing from it"
                            .formatted(channel.code())));
            return findings;
        }

        if (projection.products().isEmpty()) {
            findings.add(ValidationFinding.blocker(
                    ChannelFindings.PROJECTION_EMPTY,
                    EntityType.CATALOG,
                    null,
                    channel.code(),
                    "Every item is gated out for this channel at this branch: it would be sent an empty menu"));
        }

        if (projection.priceAuthority() == PriceAuthority.HORECAOS
                && !projection.products().isEmpty()) {
            if (projection.currency() == null) {
                findings.add(ValidationFinding.blocker(
                        ChannelFindings.CHANNEL_PRICE_BOOK_MISSING,
                        EntityType.CATALOG,
                        null,
                        channel.code(),
                        "No active price book resolves for this branch on this channel: every amount is missing"));
            } else {
                Set<UUID> active = snapshot.activeVariantIds();
                for (MenuProduct product : projection.products()) {
                    for (MenuVariant variant : product.variants()) {
                        // Priced somewhere in the brand but not on this channel's plane.
                        // A variant priced nowhere is already VARIANT_HAS_NO_ACTIVE_PRICE,
                        // and saying it twice is noise an operator has to dismiss twice.
                        if (variant.amountMinor() == null
                                && active.contains(variant.variantId())
                                && snapshot.pricedVariantIds().contains(variant.variantId())) {
                            findings.add(ValidationFinding.blocker(
                                    ChannelFindings.CHANNEL_PRICE_MISSING,
                                    EntityType.VARIANT,
                                    variant.variantId(),
                                    variant.sku(),
                                    "This variant has no price on the price plane channel %s resolves to"
                                            .formatted(channel.code())));
                        }
                    }
                }
                findings.addAll(optionPriceFindings(channel, projection));
                findings.addAll(componentPriceFindings(channel, projection, comboGroups, snapshot));
            }
        }

        return findings;
    }

    /**
     * A modifier option the brand prices but this channel's price plane does not.
     *
     * <p>Only options a served product offers are asked about, and only those priced somewhere in
     * the brand: an option priced nowhere is a modifier nobody has priced yet, which no other rule
     * of the catalog reports, and saying so for every channel at once would drown the finding that
     * names a real gap on this one. The same posture as {@code CHANNEL_PRICE_MISSING} for a
     * variant, whose all-channels twin is {@code VARIANT_HAS_NO_ACTIVE_PRICE}.
     *
     * <p>A product offers its own groups, the groups one of its served variants carries, and the
     * groups below an option that links a variant (ADR 0136): the menu publishes all three, so a
     * customer can pick from them and an unpriced option there fails the cart as surely as one in
     * a product's own group.
     */
    private List<ValidationFinding> optionPriceFindings(SalesChannel channel, ChannelProjection projection) {
        Set<UUID> offered = new java.util.HashSet<>();
        for (MenuProduct product : projection.products()) {
            offered.addAll(product.modifierGroupIds());
            for (MenuVariant variant : product.variants()) {
                offered.addAll(variant.modifierGroupIds());
            }
        }
        // One level below an option, and the options of those groups open nothing further: the
        // validator refuses a third level (MODIFIER_NESTING_DEPTH_EXCEEDED).
        Set<UUID> nestedOffered = new java.util.HashSet<>();
        for (MenuModifierGroup group : projection.modifierGroups()) {
            if (!offered.contains(group.modifierGroupId())) {
                continue;
            }
            for (MenuModifierOption option : group.options()) {
                option.nestedGroups().forEach(nested -> nestedOffered.add(nested.modifierGroupId()));
            }
        }
        offered.addAll(nestedOffered);
        Map<UUID, MenuModifierOption> unpriced = new LinkedHashMap<>();
        for (MenuModifierGroup group : projection.modifierGroups()) {
            if (!offered.contains(group.modifierGroupId())) {
                continue;
            }
            for (MenuModifierOption option : group.options()) {
                if (option.amountMinor() == null) {
                    unpriced.put(option.optionId(), option);
                }
            }
        }
        if (unpriced.isEmpty()) {
            return List.of();
        }
        Set<UUID> pricedElsewhere =
                pricing.pricedModifierOptions(projection.tenantId(), projection.brandId(), unpriced.keySet());
        List<ValidationFinding> findings = new ArrayList<>();
        for (MenuModifierOption option : unpriced.values()) {
            if (pricedElsewhere.contains(option.optionId())) {
                findings.add(ValidationFinding.blocker(
                        ChannelFindings.CHANNEL_PRICE_MISSING,
                        EntityType.MODIFIER_OPTION,
                        option.optionId(),
                        option.code(),
                        "This option has no price on the price plane channel %s resolves to"
                                .formatted(channel.code())));
            }
        }
        return findings;
    }

    /**
     * A combo component the brand prices but this channel's price plane does not -- the same gap as
     * a variant's or an option's, on a fourth priceable type. A combo whose container this branch
     * does not serve is not asked about: its components are not on this menu.
     */
    private static List<ValidationFinding> componentPriceFindings(
            SalesChannel channel,
            ChannelProjection projection,
            List<MenuComboGroup> comboGroups,
            CatalogValidator.Snapshot snapshot) {
        Set<UUID> served = new java.util.HashSet<>();
        for (MenuProduct product : projection.products()) {
            for (MenuVariant variant : product.variants()) {
                served.add(variant.variantId());
            }
        }
        List<ValidationFinding> findings = new ArrayList<>();
        for (MenuComboGroup group : comboGroups) {
            if (!served.contains(group.containerVariantId())) {
                continue;
            }
            for (MenuComboComponent component : group.components()) {
                if (component.amountMinor() == null
                        && snapshot.composite().pricedComponentIds().contains(component.componentId())) {
                    findings.add(ValidationFinding.blocker(
                            ChannelFindings.CHANNEL_PRICE_MISSING,
                            EntityType.COMBO_COMPONENT,
                            component.componentId(),
                            group.code(),
                            "This combo component has no price on the price plane channel %s resolves to"
                                    .formatted(channel.code())));
                }
            }
        }
        return findings;
    }

    /**
     * The partner-specific findings, and — when none could be raised — the
     * statement that none were checked.
     *
     * <p>An empty ruleset looks, to an author, exactly like "this menu is fine
     * for the marketplace" when in truth nobody has told the platform what the
     * marketplace requires (ADR 0138, negative consequences). So the absence of
     * a ruleset is itself a finding: a check that did not run is not a check that
     * passed.
     */
    private List<ValidationFinding> marketplaceFindings(
            @Nullable MarketplaceBindingRow binding, ChannelProjection projection) {
        if (binding == null) {
            return List.of();
        }
        String code = binding.rulesetCode();
        if (code == null) {
            return List.of(ValidationFinding.warning(
                    ChannelFindings.MARKETPLACE_RULESET_NOT_ASSIGNED,
                    EntityType.CATALOG,
                    null,
                    binding.providerType(),
                    "This binding names no marketplace ruleset: only the universal rules were checked, which is "
                            + "not evidence the marketplace will accept the menu"));
        }
        Optional<MarketplaceRuleset> ruleset = rulesets.find(code);
        if (ruleset.isEmpty()) {
            return List.of(ValidationFinding.warning(
                    ChannelFindings.MARKETPLACE_RULESET_UNKNOWN,
                    EntityType.CATALOG,
                    null,
                    binding.providerType(),
                    "This binding names marketplace ruleset %s, which this build does not carry: no ".formatted(code)
                            + "partner-specific check ran"));
        }
        return validator.marketplaceFindings(ruleset.get(), projection);
    }

    private static boolean isBlocker(ValidationFinding finding) {
        return finding.severity() == ValidationFinding.Severity.BLOCKER;
    }

    /**
     * The product a finding is about, so a console can deep-link a variant-scoped
     * finding to the editor that fixes it: a variant has no page of its own, and
     * nothing else resolves "which product owns this variant".
     */
    private static @Nullable UUID ownerProduct(
            ValidationFinding finding, Map<UUID, UUID> productOfVariant, Map<UUID, UUID> productOfComponent) {
        if (finding.entityId() == null || finding.entityType() == null) {
            return null;
        }
        return switch (finding.entityType()) {
            case PRODUCT -> finding.entityId();
            case VARIANT -> productOfVariant.get(finding.entityId());
            case COMBO_COMPONENT -> productOfComponent.get(finding.entityId());
            default -> null;
        };
    }

    // ------------------------------------------------------------------ shapes

    /**
     * @param afterProductId the last product of the previous page, or null for the first
     * @param limit the page size, already clamped by the caller
     * @param locationId the branch to preview; null to take the channel's only one
     * @param bindingId the marketplace binding to preview; names the branch itself when it is branch-bound
     * @param locale a catalog locale code; null for the brand's default
     */
    public record PreviewRequest(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            UUID channelId,
            @Nullable UUID locationId,
            @Nullable UUID bindingId,
            @Nullable String locale,
            @Nullable UUID afterProductId,
            int limit) {}

    /**
     * One channel at one branch, as the dry run leaves it.
     *
     * @param publishable what {@code publish} would decide for this channel: no blocker in {@code GET
     *     .../validation}'s report and none on the channel's own images
     * @param channelReady {@code publishable} <em>and</em> no blocker from the projection or the
     *     marketplace ruleset — the verdict a console shows for this channel
     * @param products one page, ordered by product id
     * @param hasMore whether a further page follows {@code products}
     * @param categories the whole menu's, not the page's
     * @param comboGroups the choices every combo on the menu asks for (ADR 0136), the whole menu's
     */
    public record ChannelPreview(
            SalesChannel channel,
            UUID locationId,
            @Nullable MarketplaceBindingRow binding,
            String locale,
            PriceAuthority priceAuthority,
            @Nullable String currency,
            boolean publishable,
            boolean channelReady,
            List<PreviewFinding> findings,
            List<MenuCategory> categories,
            List<MenuModifierGroup> modifierGroups,
            List<MenuComboGroup> comboGroups,
            List<MenuProduct> products,
            boolean hasMore,
            Map<UUID, ResolvedMedia> media) {}

    /** A finding, where it came from, and the product that owns the entity it names. */
    public record PreviewFinding(
            ValidationFinding finding,
            FindingSource source,
            @Nullable UUID productId) {}

    /** Which layer raised a finding: the catalog's own rules, the projection, or a marketplace ruleset. */
    public enum FindingSource {
        CATALOG,
        PROJECTION,
        MARKETPLACE
    }

    /**
     * A branch a channel sells at, and the marketplace binding that covers it, if any.
     *
     * @param locationName the branch's own name: the one thing that tells two targets apart when a
     *     brand-wide binding covers them both
     */
    public record PreviewTarget(
            UUID locationId, String locationName, @Nullable MarketplaceBindingRow binding) {}

    /** The catalog, channel, branch or binding named is not this tenant's or brand's. */
    public static final class UnknownPreviewTargetException extends RuntimeException {

        public UnknownPreviewTargetException(String message) {
            super(message);
        }
    }

    /** No branch was named and the channel does not sell at exactly one, so there is nothing to default to. */
    public static final class PreviewTargetRequiredException extends RuntimeException {

        private final transient int branches;

        public PreviewTargetRequiredException(int branches) {
            super(
                    branches == 0
                            ? "This channel does not sell at any branch yet; there is nothing to preview"
                            : "This channel sells at %d branches; name one with locationId or bindingId"
                                    .formatted(branches));
            this.branches = branches;
        }

        public int branches() {
            return branches;
        }
    }
}
