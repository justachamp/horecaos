package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.CatalogValidator.CompositeContext;
import uz.horecaos.platform.catalog.application.CatalogValidator.LocalizedText;
import uz.horecaos.platform.catalog.application.CatalogValidator.Snapshot;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.CompositeProducts;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ModifierAttachment;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The rules ADR 0136 adds to {@link CatalogValidator}, on literals, and the pure
 * arithmetic in {@link CompositeProducts} they stand on.
 *
 * <p>Each rule is drawn next to its boundary: the snapshot that must trip it and the
 * nearby one that must not, so a validator that always fires cannot satisfy it and a
 * validator that never fires cannot either.
 */
class CompositeProductRulesTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String LOCALE = "en";

    private final CatalogValidator validator = new CatalogValidator();

    // ------------------------------------------------------------ combo prices

    @Test
    @DisplayName("a component with no active COMBO_COMPONENT price blocks publication; a priced one does not")
    void anUnpricedComponentIsBlocked() {
        World world = new World();
        ComboGroup group = world.comboGroup(1, 1, false);
        ComboComponent priced = world.component(group, world.plainVariant("BURGER"));
        ComboComponent unpriced = world.component(group, world.plainVariant("WRAP"));
        world.priced(priced);

        List<ValidationFinding> blockers = world.blockers();

        assertThat(blockers)
                .filteredOn(finding -> finding.code().equals("COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE"))
                .extracting(ValidationFinding::entityId)
                .as("exactly the unpriced pairing, named so the author knows which row to price")
                .containsExactly(unpriced.id());
    }

    @Test
    @DisplayName(
            "a combo group where nothing is priced is COMBO_HAS_NO_PRICED_COMPONENTS; one priced component clears it")
    void aGroupWithNothingPricedIsBlocked() {
        World world = new World();
        ComboGroup group = world.comboGroup(1, 1, false);
        ComboComponent only = world.component(group, world.plainVariant("BURGER"));

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .contains("COMBO_HAS_NO_PRICED_COMPONENTS", "COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE");

        world.priced(only);

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("COMBO_HAS_NO_PRICED_COMPONENTS", "COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE");
    }

    @Test
    @DisplayName("a group with no components is COMBO_HAS_NO_PRICED_COMPONENTS and not also unsatisfiable")
    void anEmptyGroupIsOneFindingNotTwo() {
        World world = new World();
        world.comboGroup(2, 2, false);

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .contains("COMBO_HAS_NO_PRICED_COMPONENTS")
                .doesNotContain("COMBO_GROUP_MINIMUM_UNSATISFIABLE");
    }

    @Test
    @DisplayName("the container itself is never asked for a price; a plain unpriced variant still is")
    void theContainerIsExemptFromVariantPricing() {
        World world = new World();
        ComboGroup group = world.comboGroup(1, 1, false);
        world.priced(world.component(group, world.plainVariant("BURGER")));
        Variant unrelated = world.plainVariant("UNRELATED");

        List<ValidationFinding> blockers = world.blockers();

        assertThat(blockers)
                .filteredOn(finding -> finding.code().equals("VARIANT_HAS_NO_ACTIVE_PRICE"))
                .extracting(ValidationFinding::entityId)
                .as("the container is exempt (ADR 0136); the unrelated variant is not, so the carve-out is narrow")
                .doesNotContain(world.container.id())
                .contains(unrelated.id());
    }

    @Test
    @DisplayName("an unwired pricing port raises no component price blocker -- the report already says it did not run")
    void unwiredPricingRaisesNoComponentPriceBlocker() {
        World world = new World();
        ComboGroup group = world.comboGroup(1, 1, false);
        world.component(group, world.plainVariant("BURGER"));
        world.pricingWired = false;

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE", "COMBO_HAS_NO_PRICED_COMPONENTS");
    }

    // ------------------------------------------------------------ combo capacity

    @Test
    @DisplayName("choose 3 of 2 is unsatisfiable; choose 2 of 2 is not")
    void aMinimumAboveTheComponentsIsUnsatisfiable() {
        World world = new World();
        ComboGroup group = world.comboGroup(3, 3, false);
        world.priced(world.component(group, world.plainVariant("BURGER")));
        world.priced(world.component(group, world.plainVariant("WRAP")));

        assertThat(world.blockers())
                .filteredOn(finding -> finding.code().equals("COMBO_GROUP_MINIMUM_UNSATISFIABLE"))
                .hasSize(1)
                .first()
                .extracting(ValidationFinding::detail)
                .asString()
                .contains("Requires 3 selections but only 2 are available");

        World satisfiable = new World();
        ComboGroup fine = satisfiable.comboGroup(2, 2, false);
        satisfiable.priced(satisfiable.component(fine, satisfiable.plainVariant("BURGER")));
        satisfiable.priced(satisfiable.component(fine, satisfiable.plainVariant("WRAP")));

        assertThat(satisfiable.blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("COMBO_GROUP_MINIMUM_UNSATISFIABLE");
    }

    @Test
    @DisplayName("where one component may be picked repeatedly, a single component can satisfy the minimum")
    void repeatedPicksRaiseTheCapacity() {
        World world = new World();
        ComboGroup group = world.comboGroup(3, 3, true);
        world.priced(world.component(group, world.plainVariant("COLA")));

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("COMBO_GROUP_MINIMUM_UNSATISFIABLE");
    }

    @Test
    @DisplayName("an archived component does not count toward capacity, and an archived group is not validated")
    void archivedRowsAreIgnored() {
        World world = new World();
        ComboGroup group = world.comboGroup(2, 2, false);
        world.priced(world.component(group, world.plainVariant("BURGER")));
        ComboComponent retired = world.component(group, world.plainVariant("WRAP"));
        world.priced(retired);
        world.retire(retired);

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .as("two components were needed and one has been archived")
                .contains("COMBO_GROUP_MINIMUM_UNSATISFIABLE");

        World archivedGroup = new World();
        ComboGroup dead = archivedGroup.comboGroup(5, 5, false);
        archivedGroup.retire(dead);

        assertThat(archivedGroup.blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("COMBO_HAS_NO_PRICED_COMPONENTS", "COMBO_GROUP_MINIMUM_UNSATISFIABLE");
    }

    @Test
    @DisplayName("a component whose variant is no longer active is blocked")
    void aComponentOfAnArchivedVariantIsBlocked() {
        World world = new World();
        ComboGroup group = world.comboGroup(1, 1, false);
        Variant gone = world.plainVariant("GONE");
        world.priced(world.component(group, gone));
        world.variantStatus.put(gone.id(), Status.ARCHIVED);

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .contains("COMBO_COMPONENT_LINKS_INACTIVE_VARIANT");
    }

    @Test
    @DisplayName("a combo group needs a name like every other customer-facing entity")
    void aComboGroupNeedsAName() {
        World world = new World();
        ComboGroup group = world.comboGroup(1, 1, false);
        world.priced(world.component(group, world.plainVariant("BURGER")));
        world.nameComboGroups = false;

        assertThat(world.blockers())
                .filteredOn(finding -> finding.code().equals("MISSING_TRANSLATION"))
                .extracting(ValidationFinding::entityType)
                .containsExactly(EntityType.COMBO_GROUP);

        world.nameComboGroups = true;
        assertThat(world.blockers()).extracting(ValidationFinding::code).doesNotContain("MISSING_TRANSLATION");
    }

    // ----------------------------------------------------------- hidden groups

    @Test
    @DisplayName("a hidden group must be required and have exactly one active option")
    void aHiddenGroupMustBeUnambiguous() {
        // Required, one option: the delivery box. Fine.
        World fine = new World();
        ModifierGroup box = fine.group("BOX", true, 1, 1);
        fine.option(box, "BOX", null);
        fine.attach(fine.catalogProduct, box, Visibility.HIDDEN_AUTO_SELECT, null);
        assertThat(fine.blockers()).extracting(ValidationFinding::code).doesNotContain(ambiguous());

        // Optional: nothing to say which of "selected" and "not" it is.
        World optional = new World();
        ModifierGroup maybe = optional.group("MAYBE", false, 0, 1);
        optional.option(maybe, "ONLY", null);
        optional.attach(optional.catalogProduct, maybe, Visibility.HIDDEN_AUTO_SELECT, null);
        assertThat(optional.blockers()).extracting(ValidationFinding::code).contains(ambiguous());

        // Two options: row order would pick.
        World two = new World();
        ModifierGroup pair = two.group("PAIR", true, 1, 1);
        two.option(pair, "SMALL", null);
        two.option(pair, "LARGE", null);
        two.attach(two.catalogProduct, pair, Visibility.HIDDEN_AUTO_SELECT, null);
        assertThat(two.blockers()).extracting(ValidationFinding::code).contains(ambiguous());

        // The same group visible is an ordinary choice, not this rule's business.
        World visible = new World();
        ModifierGroup sized = visible.group("SIZED", true, 1, 1);
        visible.option(sized, "SMALL", null);
        visible.option(sized, "LARGE", null);
        visible.attach(visible.catalogProduct, sized, Visibility.VISIBLE, null);
        assertThat(visible.blockers()).extracting(ValidationFinding::code).doesNotContain(ambiguous());
    }

    @Test
    @DisplayName("an archived option does not count; a required override makes an optional group eligible")
    void effectiveValuesDecideNotTheGroupsOwn() {
        World world = new World();
        ModifierGroup box = world.group("BOX", false, 0, 1);
        world.option(box, "BOX", null);
        ModifierOption retired = world.option(box, "OLD-BOX", null);
        world.retire(retired);
        world.attach(world.catalogProduct, box, Visibility.HIDDEN_AUTO_SELECT, null, true, null, null);

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .as("required by the attachment's own override, one active option left")
                .doesNotContain(ambiguous());
    }

    @Test
    @DisplayName("a hidden attachment on a product another catalog sells is not this publication's blocker")
    void anotherCatalogsAttachmentIsNotBlockedHere() {
        World world = new World();
        ModifierGroup pair = world.group("PAIR", true, 1, 1);
        world.option(pair, "SMALL", null);
        world.option(pair, "LARGE", null);
        UUID elsewhere = UUID.randomUUID();
        world.attachments.add(
                attachment(AttachmentOwnerType.PRODUCT, elsewhere, pair, Visibility.HIDDEN_AUTO_SELECT, null));

        assertThat(world.blockers()).extracting(ValidationFinding::code).doesNotContain(ambiguous());
    }

    @Test
    @DisplayName("an override the group has since moved out from under is caught at publication")
    void aStaleOverrideIsCaught() {
        World world = new World();
        ModifierGroup sauce = world.group("SAUCE", false, 2, 3);
        world.option(sauce, "KETCHUP", null);
        world.option(sauce, "MAYO", null);
        // Written when the group's minimum was 0: a maximum of 1 was fine then.
        world.attach(world.catalogProduct, sauce, Visibility.VISIBLE, null, null, null, 1);

        assertThat(world.blockers())
                .filteredOn(finding -> finding.code().equals("MODIFIER_ATTACHMENT_OVERRIDE_CONTRADICTS"))
                .hasSize(1);

        World fine = new World();
        ModifierGroup relaxed = fine.group("SAUCE", false, 0, 3);
        fine.option(relaxed, "KETCHUP", null);
        fine.attach(fine.catalogProduct, relaxed, Visibility.VISIBLE, null, null, null, 1);
        assertThat(fine.blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("MODIFIER_ATTACHMENT_OVERRIDE_CONTRADICTS");
    }

    @Test
    @DisplayName("a variant that applies by itself a group its product offers as a choice blocks publication")
    void aVariantCannotApplyWhatItsProductOffersAsAChoice() {
        // The pairing that charges twice: the menu asks the customer for the packing, and pricing,
        // which lays the variant's attachment over the product's, adds it again.
        World world = new World();
        ModifierGroup packing = world.group("PACKING", true, 1, 1);
        world.option(packing, "BOX", null);
        world.attach(world.catalogProduct, packing, Visibility.VISIBLE, null);
        world.attachToVariant(world.container, packing, Visibility.HIDDEN_AUTO_SELECT);

        assertThat(world.blockers())
                .filteredOn(finding -> finding.code().equals("MODIFIER_ATTACHMENT_OVERRIDE_CONTRADICTS"))
                .extracting(ValidationFinding::entityId)
                .as("named on the group, so the author knows which attachment to change")
                .containsExactly(packing.id());

        World agree = new World();
        ModifierGroup alsoHidden = agree.group("PACKING", true, 1, 1);
        agree.option(alsoHidden, "BOX", null);
        agree.attach(agree.catalogProduct, alsoHidden, Visibility.HIDDEN_AUTO_SELECT, null);
        agree.attachToVariant(agree.container, alsoHidden, Visibility.HIDDEN_AUTO_SELECT);
        assertThat(agree.blockers())
                .as("the product applies it too: one charge, however many levels say so")
                .extracting(ValidationFinding::code)
                .doesNotContain("MODIFIER_ATTACHMENT_OVERRIDE_CONTRADICTS");

        World exempt = new World();
        ModifierGroup switchedOff = exempt.group("PACKING", true, 1, 1);
        exempt.option(switchedOff, "BOX", null);
        exempt.attach(exempt.catalogProduct, switchedOff, Visibility.HIDDEN_AUTO_SELECT, null);
        exempt.attachToVariant(exempt.container, switchedOff, Visibility.VISIBLE);
        assertThat(exempt.blockers())
                .as("a variant that shows what its product applies switches the charge off: deliberate, and a "
                        + "customer is asked for nothing")
                .extracting(ValidationFinding::code)
                .doesNotContain("MODIFIER_ATTACHMENT_OVERRIDE_CONTRADICTS");
    }

    // ------------------------------------------------------------ nesting depth

    @Test
    @DisplayName("one level of nesting is allowed; a third is refused on the option that causes it")
    void aThirdLevelIsRefusedAtPublication() {
        World world = new World();
        // sold variant -> group G0 -> option O1 links V1
        Variant v1 = world.otherVariant("MEAL-SIDE");
        ModifierGroup g0 = world.group("SIDES", false, 0, 1);
        world.option(g0, "SIDE", v1.id());
        world.attach(world.catalogProduct, g0, Visibility.VISIBLE, null);
        // V1 carries G1 -> option O2 links V2
        Variant v2 = world.otherVariant("SIDE-DIP");
        ModifierGroup g1 = world.group("DIPS", false, 0, 1);
        ModifierOption tooDeep = world.option(g1, "DIP", v2.id());
        world.attachToVariant(v1, g1, Visibility.VISIBLE);

        // V2 carries nothing yet: this is exactly one level of nesting.
        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .as("a linked variant with groups of its own is the allowed level")
                .doesNotContain("MODIFIER_NESTING_DEPTH_EXCEEDED");

        // Give V2 a group of its own: now the option linking it is a third level.
        ModifierGroup g2 = world.group("SPICE", false, 0, 1);
        world.option(g2, "HOT", null);
        world.attachToVariant(v2, g2, Visibility.VISIBLE);

        assertThat(world.blockers())
                .filteredOn(finding -> finding.code().equals("MODIFIER_NESTING_DEPTH_EXCEEDED"))
                .extracting(ValidationFinding::entityId)
                .as("reported on the over-nested option -- the row an author edits to fix it")
                .containsExactly(tooDeep.id());
    }

    @Test
    @DisplayName("a hidden group on the deepest variant is a charge, not a level of nesting")
    void aHiddenGroupIsNotANestingLevel() {
        World world = new World();
        Variant v1 = world.otherVariant("MEAL-SIDE");
        ModifierGroup g0 = world.group("SIDES", false, 0, 1);
        world.option(g0, "SIDE", v1.id());
        world.attach(world.catalogProduct, g0, Visibility.VISIBLE, null);
        Variant v2 = world.otherVariant("COLA");
        ModifierGroup g1 = world.group("DRINKS", false, 0, 1);
        world.option(g1, "COLA", v2.id());
        world.attachToVariant(v1, g1, Visibility.VISIBLE);
        ModifierGroup deposit = world.group("DEPOSIT", true, 1, 1);
        world.option(deposit, "BOTTLE", null);
        world.attachToVariant(v2, deposit, Visibility.HIDDEN_AUTO_SELECT);

        assertThat(world.blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("MODIFIER_NESTING_DEPTH_EXCEEDED");
    }

    @Test
    @DisplayName("groups a linked variant inherits from its product count as that variant's own")
    void productLevelGroupsOfALinkedVariantCount() {
        World world = new World();
        Variant v1 = world.otherVariant("MEAL-SIDE");
        ModifierGroup g0 = world.group("SIDES", false, 0, 1);
        world.option(g0, "SIDE", v1.id());
        world.attach(world.catalogProduct, g0, Visibility.VISIBLE, null);
        Variant v2 = world.otherVariant("DIP-CUP");
        ModifierGroup g1 = world.group("DIPS", false, 0, 1);
        ModifierOption deep = world.option(g1, "DIP", v2.id());
        world.attachToVariant(v1, g1, Visibility.VISIBLE);
        // V2's product, not V2 itself, carries the group.
        ModifierGroup g2 = world.group("CUP-SIZE", false, 0, 1);
        world.option(g2, "SMALL", null);
        world.attach(world.productOf(v2), g2, Visibility.VISIBLE, null);

        assertThat(world.blockers())
                .filteredOn(finding -> finding.code().equals("MODIFIER_NESTING_DEPTH_EXCEEDED"))
                .extracting(ValidationFinding::entityId)
                .containsExactly(deep.id());
    }

    // ----------------------------------------------------------- domain rules

    @Test
    @DisplayName("a variant-level attachment of the same group wins over the product's; others pass through ordered")
    void aVariantAttachmentWinsOverTheProductsForTheSameGroup() {
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        UUID productOnly = UUID.randomUUID();
        ModifierAttachment productLevel = new ModifierAttachment(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                product,
                shared,
                5,
                Visibility.VISIBLE,
                null,
                null,
                null,
                null,
                1);
        ModifierAttachment productOnlyLevel = new ModifierAttachment(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                product,
                productOnly,
                1,
                Visibility.VISIBLE,
                null,
                null,
                null,
                null,
                1);
        ModifierAttachment variantLevel = new ModifierAttachment(
                TENANT,
                BRAND,
                AttachmentOwnerType.VARIANT,
                variant,
                shared,
                0,
                Visibility.HIDDEN_AUTO_SELECT,
                null,
                null,
                null,
                null,
                1);

        List<ModifierAttachment> effective =
                CompositeProducts.effectiveAttachments(List.of(productLevel, productOnlyLevel), List.of(variantLevel));

        assertThat(effective).hasSize(2);
        assertThat(effective.get(0))
                .as("sorted by sort order: the variant's own sort order 0 comes first")
                .isEqualTo(variantLevel);
        assertThat(effective.get(1)).isEqualTo(productOnlyLevel);
    }

    @Test
    @DisplayName("effective values fall back to the group's own when the override is null")
    void effectiveValuesFallBackToTheGroup() {
        ModifierGroup group =
                new ModifierGroup(UUID.randomUUID(), TENANT, BRAND, "G", true, 1, 3, false, 0, Status.ACTIVE, 1);
        ModifierAttachment plain = new ModifierAttachment(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                UUID.randomUUID(),
                group.id(),
                0,
                Visibility.VISIBLE,
                null,
                null,
                null,
                null,
                1);
        ModifierAttachment overridden = new ModifierAttachment(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                UUID.randomUUID(),
                group.id(),
                0,
                Visibility.VISIBLE,
                null,
                false,
                0,
                1,
                1);

        assertThat(plain.effectiveRequired(group)).isTrue();
        assertThat(plain.effectiveMinimum(group)).isEqualTo(1);
        assertThat(plain.effectiveMaximum(group)).isEqualTo(3);
        assertThat(overridden.effectiveRequired(group)).isFalse();
        assertThat(overridden.effectiveMinimum(group)).isZero();
        assertThat(overridden.effectiveMaximum(group)).isEqualTo(1);
        assertThat(plain.rangeProblem(group)).isNull();
        assertThat(overridden.rangeProblem(group)).isNull();
    }

    @Test
    @DisplayName("an attachment with no modes applies on every fulfilment mode; with modes, only those")
    void modesFilterOnlyWhenSet() {
        UUID group = UUID.randomUUID();
        ModifierAttachment everywhere = new ModifierAttachment(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                UUID.randomUUID(),
                group,
                0,
                Visibility.HIDDEN_AUTO_SELECT,
                null,
                null,
                null,
                null,
                1);
        ModifierAttachment deliveryOnly = new ModifierAttachment(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                UUID.randomUUID(),
                group,
                0,
                Visibility.HIDDEN_AUTO_SELECT,
                EnumSet.of(FulfillmentMode.DELIVERY),
                null,
                null,
                null,
                1);

        for (FulfillmentMode mode : FulfillmentMode.values()) {
            assertThat(everywhere.appliesTo(mode)).isTrue();
        }
        assertThat(deliveryOnly.appliesTo(FulfillmentMode.DELIVERY)).isTrue();
        assertThat(deliveryOnly.appliesTo(FulfillmentMode.PICKUP)).isFalse();
        assertThat(deliveryOnly.appliesTo(FulfillmentMode.DINE_IN)).isFalse();
    }

    // ---------------------------------------------------------------- fixtures

    private static String ambiguous() {
        return "HIDDEN_MODIFIER_GROUP_AMBIGUOUS_DEFAULT";
    }

    private static ModifierAttachment attachment(
            AttachmentOwnerType type,
            UUID owner,
            ModifierGroup group,
            Visibility visibility,
            @Nullable Set<FulfillmentMode> modes) {
        return new ModifierAttachment(
                TENANT, BRAND, type, owner, group.id(), 0, visibility, modes, null, null, null, 1);
    }

    /** A catalog selling one product with a combo container variant, built up per test. */
    private final class World {

        final Product catalogProduct;
        final Variant container;
        final List<Product> products = new ArrayList<>();
        final List<Variant> variants = new ArrayList<>();
        final List<ComboGroup> groups = new ArrayList<>();
        final List<ComboComponent> components = new ArrayList<>();
        final Set<UUID> pricedComponents = new java.util.HashSet<>();
        final Map<UUID, Status> variantStatus = new LinkedHashMap<>();
        final List<ModifierGroup> modifierGroups = new ArrayList<>();
        final Map<UUID, List<ModifierOption>> options = new LinkedHashMap<>();
        final List<ModifierAttachment> attachments = new ArrayList<>();
        final Map<UUID, UUID> productOfVariant = new LinkedHashMap<>();
        boolean pricingWired = true;
        boolean nameComboGroups = true;

        World() {
            catalogProduct = new Product(UUID.randomUUID(), TENANT, BRAND, "LUNCH-BOX", Status.ACTIVE, 1);
            products.add(catalogProduct);
            container = variantOf(catalogProduct, "LUNCH-BOX-V", true);
        }

        private Variant variantOf(Product product, String sku, boolean isDefault) {
            Variant variant = new Variant(
                    UUID.randomUUID(), TENANT, BRAND, product.id(), sku, "PIECE", isDefault, 0, Status.ACTIVE, 1);
            variants.add(variant);
            variantStatus.put(variant.id(), Status.ACTIVE);
            productOfVariant.put(variant.id(), product.id());
            return variant;
        }

        /** A sellable variant of its own product in this catalog -- a real dish. */
        Variant plainVariant(String sku) {
            Product product = new Product(UUID.randomUUID(), TENANT, BRAND, sku, Status.ACTIVE, 1);
            products.add(product);
            return variantOf(product, sku, true);
        }

        /** A variant in the brand that this catalog does not sell, such as a side dish only options link. */
        Variant otherVariant(String sku) {
            Product product = new Product(UUID.randomUUID(), TENANT, BRAND, sku, Status.ACTIVE, 1);
            Variant variant = new Variant(
                    UUID.randomUUID(), TENANT, BRAND, product.id(), sku, "PIECE", true, 0, Status.ACTIVE, 1);
            variantStatus.put(variant.id(), Status.ACTIVE);
            productOfVariant.put(variant.id(), product.id());
            return variant;
        }

        UUID productOf(Variant variant) {
            return Objects.requireNonNull(productOfVariant.get(variant.id()));
        }

        ComboGroup comboGroup(int minimum, int maximum, boolean allowSame) {
            ComboGroup group = new ComboGroup(
                    UUID.randomUUID(),
                    TENANT,
                    BRAND,
                    container.id(),
                    "G" + groups.size(),
                    minimum,
                    maximum,
                    allowSame,
                    0,
                    Status.ACTIVE,
                    1);
            groups.add(group);
            return group;
        }

        ComboComponent component(ComboGroup group, Variant variant) {
            ComboComponent component = new ComboComponent(
                    UUID.randomUUID(), TENANT, BRAND, group.id(), variant.id(), 1, 0, Status.ACTIVE, 1);
            components.add(component);
            return component;
        }

        void priced(ComboComponent component) {
            pricedComponents.add(component.id());
        }

        void retire(ComboComponent component) {
            components.set(
                    components.indexOf(component),
                    new ComboComponent(
                            component.id(),
                            TENANT,
                            BRAND,
                            component.comboGroupId(),
                            component.componentVariantId(),
                            1,
                            0,
                            Status.ARCHIVED,
                            2));
        }

        void retire(ComboGroup group) {
            groups.set(
                    groups.indexOf(group),
                    new ComboGroup(
                            group.id(),
                            TENANT,
                            BRAND,
                            group.containerVariantId(),
                            group.code(),
                            group.minimumSelections(),
                            group.maximumSelections(),
                            group.allowSameComponentMultipleTimes(),
                            0,
                            Status.ARCHIVED,
                            2));
        }

        void retire(ModifierOption option) {
            List<ModifierOption> inGroup = Objects.requireNonNull(options.get(option.modifierGroupId()));
            inGroup.set(
                    inGroup.indexOf(option),
                    new ModifierOption(
                            option.id(),
                            TENANT,
                            BRAND,
                            option.modifierGroupId(),
                            option.code(),
                            option.linkedVariantId(),
                            1,
                            0,
                            Status.ARCHIVED,
                            2));
        }

        ModifierGroup group(String code, boolean required, int minimum, int maximum) {
            ModifierGroup group = new ModifierGroup(
                    UUID.randomUUID(), TENANT, BRAND, code, required, minimum, maximum, false, 0, Status.ACTIVE, 1);
            modifierGroups.add(group);
            options.put(group.id(), new ArrayList<>());
            return group;
        }

        ModifierOption option(ModifierGroup group, String code, @Nullable UUID linkedVariantId) {
            ModifierOption option = new ModifierOption(
                    UUID.randomUUID(), TENANT, BRAND, group.id(), code, linkedVariantId, 1, 0, Status.ACTIVE, 1);
            Objects.requireNonNull(options.get(group.id())).add(option);
            return option;
        }

        void attach(Product product, ModifierGroup group, Visibility visibility, @Nullable Set<FulfillmentMode> modes) {
            attach(product, group, visibility, modes, null, null, null);
        }

        void attach(
                Product product,
                ModifierGroup group,
                Visibility visibility,
                @Nullable Set<FulfillmentMode> modes,
                @Nullable Boolean required,
                @Nullable Integer minimum,
                @Nullable Integer maximum) {
            attach(product.id(), group, visibility, modes, required, minimum, maximum);
        }

        void attach(UUID productId, ModifierGroup group, Visibility visibility, @Nullable Set<FulfillmentMode> modes) {
            attach(productId, group, visibility, modes, null, null, null);
        }

        private void attach(
                UUID productId,
                ModifierGroup group,
                Visibility visibility,
                @Nullable Set<FulfillmentMode> modes,
                @Nullable Boolean required,
                @Nullable Integer minimum,
                @Nullable Integer maximum) {
            attachments.add(new ModifierAttachment(
                    TENANT,
                    BRAND,
                    AttachmentOwnerType.PRODUCT,
                    productId,
                    group.id(),
                    0,
                    visibility,
                    modes,
                    required,
                    minimum,
                    maximum,
                    1));
        }

        void attachToVariant(Variant variant, ModifierGroup group, Visibility visibility) {
            attachments.add(new ModifierAttachment(
                    TENANT,
                    BRAND,
                    AttachmentOwnerType.VARIANT,
                    variant.id(),
                    group.id(),
                    0,
                    visibility,
                    null,
                    null,
                    null,
                    null,
                    1));
        }

        List<ValidationFinding> blockers() {
            Map<UUID, List<Variant>> variantsByProduct =
                    variants.stream().collect(Collectors.groupingBy(Variant::productId));
            Map<String, LocalizedText> names = new LinkedHashMap<>();
            for (Product product : products) {
                names.put(
                        Snapshot.translationKey(EntityType.PRODUCT, product.id(), LOCALE),
                        new LocalizedText(LOCALE, product.code(), null));
            }
            for (ModifierGroup group : modifierGroups) {
                names.put(
                        Snapshot.translationKey(EntityType.MODIFIER_GROUP, group.id(), LOCALE),
                        new LocalizedText(LOCALE, group.code(), null));
            }
            if (nameComboGroups) {
                for (ComboGroup group : groups) {
                    names.put(
                            Snapshot.translationKey(EntityType.COMBO_GROUP, group.id(), LOCALE),
                            new LocalizedText(LOCALE, group.code(), null));
                }
            }
            Map<UUID, List<ComboComponent>> componentsByGroup =
                    components.stream().collect(Collectors.groupingBy(ComboComponent::comboGroupId));
            Map<UUID, ModifierGroup> groupsById =
                    modifierGroups.stream().collect(Collectors.toMap(ModifierGroup::id, group -> group));

            Snapshot snapshot = new Snapshot(
                    LOCALE,
                    LOCALE,
                    products,
                    variants,
                    variantsByProduct,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    modifierGroups,
                    options.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue()))),
                    names,
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    CatalogValidator.FiscalContext.empty(),
                    pricingWired,
                    new CompositeContext(
                            List.copyOf(groups),
                            componentsByGroup,
                            Set.copyOf(pricedComponents),
                            variantStatus,
                            List.copyOf(attachments),
                            productOfVariant,
                            groupsById,
                            options.entrySet().stream()
                                    .collect(Collectors.toMap(
                                            Map.Entry::getKey, entry -> List.copyOf(entry.getValue())))));
            return validator.validate(snapshot).blockers();
        }
    }
}
