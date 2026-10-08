package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.CatalogValidator.AutoAddContext;
import uz.horecaos.platform.catalog.application.CatalogValidator.CompositeContext;
import uz.horecaos.platform.catalog.application.CatalogValidator.LocalizedText;
import uz.horecaos.platform.catalog.application.CatalogValidator.Snapshot;
import uz.horecaos.platform.catalog.domain.AutoAddFindings;
import uz.horecaos.platform.catalog.domain.AutoAddRules.AutoAddRule;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Band;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Kind;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Mode;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Trigger;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ModifierAttachment;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * ADR 0158's validator findings, each with the adjacent passing case: errors block publication and name the
 * rule, the two warnings do not.
 */
class AutoAddRuleValidatorTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String LOCALE = "uz";

    private static final UUID BURGER_PRODUCT = UUID.randomUUID();
    private static final UUID BURGER = UUID.randomUUID();
    private static final UUID BAG = UUID.randomUUID();
    private static final UUID CUTLERY = UUID.randomUUID();
    private static final UUID ARCHIVED_DISH = UUID.randomUUID();
    private static final UUID UNPRICED = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();

    private final CatalogValidator validator = new CatalogValidator();

    @Test
    @DisplayName("a well-formed plain rule over a sellable, priced variant raises nothing")
    void aGoodRuleIsClean() {
        assertThat(codes(validate(List.of(plain("cutlery", CUTLERY)), Set.of(), false, Map.of(), null)))
                .isEmpty();
    }

    @Test
    @DisplayName("a rule over a variant that is archived, unpriced or not on this menu is blocked as not sellable")
    void anUnsellableVariantIsBlocked() {
        assertThat(blockers(validate(List.of(plain("gone", ARCHIVED_DISH)), Set.of(), false, Map.of(), null)))
                .containsExactly(AutoAddFindings.AUTO_ADD_VARIANT_NOT_SELLABLE);
        assertThat(blockers(validate(List.of(plain("free", UNPRICED)), Set.of(), false, Map.of(), null)))
                .containsExactly(AutoAddFindings.AUTO_ADD_VARIANT_NOT_SELLABLE);
        assertThat(blockers(validate(List.of(plain("elsewhere", UUID.randomUUID())), Set.of(), false, Map.of(), null)))
                .as("a variant that is on no product of this menu")
                .containsExactly(AutoAddFindings.AUTO_ADD_VARIANT_NOT_SELLABLE);
    }

    @Test
    @DisplayName(
            "a product-triggered rule with no live trigger node is blocked; one live node among archived ones is enough")
    void aTriggerRuleNeedsALiveNode() {
        AutoAddRule noNodes = trigger("bag-per-order", BAG, List.of());
        AutoAddRule archivedOnly = trigger("bag-on-archived", BAG, List.of(new Trigger(ARCHIVED_DISH, null, null)));
        AutoAddRule oneLive = trigger(
                "bag-on-burger",
                BAG,
                List.of(new Trigger(ARCHIVED_DISH, null, null), new Trigger(null, BURGER_PRODUCT, null)));

        assertThat(blockers(validate(List.of(noNodes), Set.of(), false, Map.of(), null)))
                .containsExactly(AutoAddFindings.AUTO_ADD_TRIGGER_MISSING);
        assertThat(blockers(validate(List.of(archivedOnly), Set.of(), false, Map.of(), null)))
                .containsExactly(AutoAddFindings.AUTO_ADD_TRIGGER_MISSING);
        assertThat(blockers(validate(List.of(oneLive), Set.of(), false, Map.of(), null)))
                .isEmpty();
    }

    @Test
    @DisplayName("two active rules adding the same variant in overlapping modes, channels and locations are blocked")
    void overlappingRulesAreBlocked() {
        AutoAddRule first = plain("a", CUTLERY);
        AutoAddRule second = plain("b", CUTLERY);

        assertThat(blockers(validate(List.of(first, second), Set.of(), false, Map.of(), null)))
                .containsExactly(AutoAddFindings.AUTO_ADD_RULES_OVERLAP);

        // The adjacent passing cases: a different mode, a different channel type, a different location.
        AutoAddRule pickupOnly = withModes(plain("b", CUTLERY), Set.of("PICKUP"), Set.of("WEB"));
        AutoAddRule deliveryOnly = withModes(plain("a", CUTLERY), Set.of("DELIVERY"), Set.of("WEB"));
        assertThat(blockers(validate(List.of(deliveryOnly, pickupOnly), Set.of(), false, Map.of(), null)))
                .as("delivery and pickup never meet")
                .isEmpty();
        AutoAddRule webOnly = withModes(plain("a", CUTLERY), Set.of("DELIVERY"), Set.of("WEB"));
        AutoAddRule botOnly = withModes(plain("b", CUTLERY), Set.of("DELIVERY"), Set.of("TELEGRAM"));
        assertThat(blockers(validate(List.of(webOnly, botOnly), Set.of(), false, Map.of(), null)))
                .as("web and Telegram never meet")
                .isEmpty();
        UUID locationA = UUID.randomUUID();
        UUID locationB = UUID.randomUUID();
        assertThat(blockers(validate(
                        List.of(
                                withLocations(plain("a", CUTLERY), locationA),
                                withLocations(plain("b", CUTLERY), locationB)),
                        Set.of(),
                        false,
                        Map.of(),
                        null)))
                .as("two different branches never meet")
                .isEmpty();
        assertThat(blockers(validate(
                        List.of(withLocations(plain("a", CUTLERY), locationA), plain("b", CUTLERY)),
                        Set.of(),
                        false,
                        Map.of(),
                        null)))
                .as("a rule with no locations meets every location")
                .containsExactly(AutoAddFindings.AUTO_ADD_RULES_OVERLAP);
    }

    @Test
    @DisplayName("a rule adding what a hidden modifier already charges, in overlapping modes, is blocked")
    void aHiddenModifierCollisionIsBlocked() {
        ModifierGroup group =
                new ModifierGroup(UUID.randomUUID(), TENANT, BRAND, "box", false, 0, 1, false, 0, Status.ACTIVE, 1);
        ModifierOption option = new ModifierOption(
                UUID.randomUUID(), TENANT, BRAND, group.id(), "delivery-box", BAG, 1, 0, Status.ACTIVE, 1);
        ModifierAttachment hiddenForDelivery = new ModifierAttachment(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                BURGER_PRODUCT,
                group.id(),
                0,
                Visibility.HIDDEN_AUTO_SELECT,
                Set.of(FulfillmentMode.DELIVERY),
                null,
                null,
                null,
                1);
        CompositeContext composite = new CompositeContext(
                List.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                List.of(hiddenForDelivery),
                Map.of(BURGER, BURGER_PRODUCT),
                Map.of(group.id(), group),
                Map.of(group.id(), List.of(option)));

        assertThat(blockers(validate(
                        List.of(withModes(plain("bag", BAG), Set.of("DELIVERY"), Set.of("WEB"))),
                        Set.of(),
                        false,
                        Map.of(),
                        composite)))
                .containsExactly(AutoAddFindings.AUTO_ADD_DUPLICATES_HIDDEN_PACKAGING);
        assertThat(blockers(validate(
                        List.of(withModes(plain("bag", BAG), Set.of("PICKUP"), Set.of("WEB"))),
                        Set.of(),
                        false,
                        Map.of(),
                        composite)))
                .as("the box is charged on delivery only, so a pickup rule is no duplicate")
                .isEmpty();
    }

    @Test
    @DisplayName("an INJECT rule over a promotion's gift is blocked, and the same variant on an OFFER rule only warns")
    void aPromotionGiftIsBlockedForInjectAndWarnsForOffer() {
        AutoAddRule inject = plain("bag", BAG);
        AutoAddRule offer = withMode(plain("bag", BAG), Mode.OFFER);

        var injectReport = validate(List.of(inject), Set.of(BAG), false, Map.of(), null);
        assertThat(blockers(injectReport)).containsExactly(AutoAddFindings.AUTO_ADD_VARIANT_IS_PROMOTION_GIFT);

        var offerReport = validate(List.of(offer), Set.of(BAG), false, Map.of(), null);
        assertThat(blockers(offerReport)).isEmpty();
        assertThat(offerReport.findings().stream()
                        .filter(finding -> finding.code().startsWith("AUTO_ADD_"))
                        .toList())
                .extracting(ValidationFinding::code, ValidationFinding::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        AutoAddFindings.AUTO_ADD_VARIANT_IS_PROMOTION_GIFT, ValidationFinding.Severity.WARNING));
    }

    @Test
    @DisplayName(
            "a mandatory rule is blocked until the legal switch is on, and then needs customer text in the default language")
    void aMandatoryRuleIsGated() {
        AutoAddRule mandatory = withMandatory(plain("bag", BAG));
        Map<String, LocalizedText> text = new LinkedHashMap<>();
        text.put(
                Snapshot.translationKey(EntityType.AUTO_ADD_RULE, mandatory.id(), LOCALE),
                new LocalizedText(LOCALE, "Paket", null));

        assertThat(blockers(validate(List.of(mandatory), Set.of(), false, text, null)))
                .as("the switch is off: the text does not matter")
                .contains(AutoAddFindings.AUTO_ADD_MANDATORY_NOT_ALLOWED)
                .doesNotContain(AutoAddFindings.AUTO_ADD_MANDATORY_UNDISCLOSED);
        assertThat(blockers(validate(List.of(mandatory), Set.of(), true, Map.of(), null)))
                .as("allowed, but the customer is told nothing")
                .containsExactly(AutoAddFindings.AUTO_ADD_MANDATORY_UNDISCLOSED);
        assertThat(blockers(validate(List.of(mandatory), Set.of(), true, text, null)))
                .as("allowed and worded")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "a portion-band rule warns when no variant counts portions and when two bands leave a gap, and blocks neither")
    void portionBandWarnings() {
        AutoAddRule gapped = bands(
                "sets",
                new Band(BigDecimal.ONE, new BigDecimal("2"), CUTLERY, BigDecimal.ONE),
                new Band(new BigDecimal("4"), null, CUTLERY, new BigDecimal("2")));

        var withoutPortions = validate(List.of(gapped), Set.of(), false, Map.of(), null);
        assertThat(blockers(withoutPortions)).isEmpty();
        assertThat(codes(withoutPortions))
                .containsExactlyInAnyOrder(
                        AutoAddFindings.AUTO_ADD_PORTION_BAND_COUNTS_NOTHING, AutoAddFindings.AUTO_ADD_BAND_GAP);

        var contiguous = validate(
                List.of(bands(
                        "sets",
                        new Band(BigDecimal.ONE, new BigDecimal("4"), CUTLERY, BigDecimal.ONE),
                        new Band(new BigDecimal("4"), null, CUTLERY, new BigDecimal("2")))),
                Set.of(),
                false,
                Map.of(),
                null,
                Map.of(BURGER, physicalWithPortions("2")));
        assertThat(codes(contiguous)).as("portions counted, bands contiguous").isEmpty();
    }

    // ---------------------------------------------------------------------------- fixtures

    private ValidationFinding.Report validate(
            List<AutoAddRule> rules,
            Set<UUID> giftVariants,
            boolean mandatoryAllowed,
            Map<String, LocalizedText> extraText,
            @Nullable CompositeContext composite) {
        return validate(rules, giftVariants, mandatoryAllowed, extraText, composite, Map.of());
    }

    private ValidationFinding.Report validate(
            List<AutoAddRule> rules,
            Set<UUID> giftVariants,
            boolean mandatoryAllowed,
            Map<String, LocalizedText> extraText,
            @Nullable CompositeContext composite,
            Map<UUID, PhysicalAttributes> physical) {
        List<Variant> variants = List.of(
                variant(BURGER, BURGER_PRODUCT, Status.ACTIVE),
                variant(BAG, UUID.randomUUID(), Status.ACTIVE),
                variant(CUTLERY, UUID.randomUUID(), Status.ACTIVE),
                variant(ARCHIVED_DISH, UUID.randomUUID(), Status.ARCHIVED),
                variant(UNPRICED, UUID.randomUUID(), Status.ACTIVE));
        Map<UUID, List<Variant>> byProduct = new LinkedHashMap<>();
        variants.forEach(variant -> byProduct
                .computeIfAbsent(variant.productId(), id -> new java.util.ArrayList<>())
                .add(variant));
        List<Product> products = List.of(new Product(BURGER_PRODUCT, TENANT, BRAND, "BURGER", Status.ACTIVE, 1));
        Category category =
                new Category(CATEGORY, TENANT, BRAND, UUID.randomUUID(), null, "mains", 0, Status.ACTIVE, 1);

        Map<String, LocalizedText> translations = new LinkedHashMap<>(extraText);
        // Every entity named, so the findings under test are the only ones.
        for (Product product : products) {
            translations.put(
                    Snapshot.translationKey(EntityType.PRODUCT, product.id(), LOCALE),
                    new LocalizedText(LOCALE, "Burger", null));
        }
        for (Category each : List.of(category)) {
            translations.put(
                    Snapshot.translationKey(EntityType.CATEGORY, each.id(), LOCALE),
                    new LocalizedText(LOCALE, "Mains", null));
        }
        Set<UUID> priced =
                new java.util.HashSet<>(variants.stream().map(Variant::id).toList());
        priced.remove(UNPRICED);

        Snapshot snapshot = new Snapshot(
                LOCALE,
                LOCALE,
                products,
                variants,
                byProduct,
                List.of(category),
                Map.of(CATEGORY, category),
                Map.of(CATEGORY, List.of(BURGER_PRODUCT)),
                Map.of(),
                List.of(),
                Map.of(),
                translations,
                Map.of(),
                Set.of(),
                priced,
                Set.of(),
                CatalogValidator.FiscalContext.empty(),
                true,
                composite == null ? CompositeContext.empty() : composite,
                physical,
                new AutoAddContext(rules, giftVariants, mandatoryAllowed));
        return validator.validate(snapshot);
    }

    /** Only the findings about auto-add rules: the fixture's other warnings (fiscal, media) are not under test. */
    private static List<String> codes(ValidationFinding.Report report) {
        return report.findings().stream()
                .filter(finding -> finding.code().startsWith("AUTO_ADD_"))
                .map(ValidationFinding::code)
                .toList();
    }

    private static List<String> blockers(ValidationFinding.Report report) {
        return report.blockers().stream()
                .filter(finding -> finding.code().startsWith("AUTO_ADD_"))
                .map(ValidationFinding::code)
                .toList();
    }

    private static Variant variant(UUID id, UUID productId, Status status) {
        return new Variant(
                id, TENANT, BRAND, productId, "SKU-" + id.toString().substring(0, 8), "PIECE", true, 0, status, 1);
    }

    private static PhysicalAttributes physicalWithPortions(String portions) {
        return new PhysicalAttributes(
                null, null, false, null, null, false, null, null, null, null, null, new BigDecimal(portions));
    }

    private static AutoAddRule plain(String code, UUID variant) {
        return rule(code, Kind.PLAIN, variant, new BigDecimal("1"), null, List.of(), List.of());
    }

    private static AutoAddRule trigger(String code, UUID variant, List<Trigger> triggers) {
        return rule(
                code, Kind.PRODUCT_TRIGGERED, variant, new BigDecimal("1"), new BigDecimal("3"), triggers, List.of());
    }

    private static AutoAddRule bands(String code, Band... bands) {
        return rule(code, Kind.PORTION_BAND, null, null, null, List.of(), List.of(bands));
    }

    private static AutoAddRule rule(
            String code,
            Kind kind,
            @Nullable UUID variant,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal threshold,
            List<Trigger> triggers,
            List<Band> bands) {
        return new AutoAddRule(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                TENANT,
                BRAND,
                code,
                kind,
                Mode.INJECT,
                Status.ACTIVE,
                variant,
                quantity,
                threshold,
                false,
                BigDecimal.ONE,
                new BigDecimal("20"),
                false,
                Set.of("DELIVERY", "PICKUP", "DINE_IN"),
                Set.of("WEB", "IOS", "ANDROID", "TELEGRAM", "KIOSK", "QR_TABLE", "CALL_CENTRE"),
                0,
                1,
                triggers,
                bands,
                Set.of());
    }

    private static AutoAddRule copy(
            AutoAddRule base,
            Mode mode,
            boolean mandatory,
            Set<String> modes,
            Set<String> channels,
            Set<UUID> locations) {
        return new AutoAddRule(
                base.id(),
                base.tenantId(),
                base.brandId(),
                base.code(),
                base.kind(),
                mode,
                base.status(),
                base.addVariantId(),
                base.quantity(),
                base.triggerThreshold(),
                base.repeatPerMultiple(),
                base.minimumQuantity(),
                base.maximumQuantity(),
                mandatory,
                modes,
                channels,
                base.sortOrder(),
                base.version(),
                base.triggers(),
                base.bands(),
                locations);
    }

    private static AutoAddRule withModes(AutoAddRule base, Set<String> modes, Set<String> channels) {
        return copy(base, base.mode(), base.mandatory(), modes, channels, base.locationIds());
    }

    private static AutoAddRule withMode(AutoAddRule base, Mode mode) {
        return copy(
                base, mode, base.mandatory(), base.fulfillmentModes(), base.channelSystemTypes(), base.locationIds());
    }

    private static AutoAddRule withMandatory(AutoAddRule base) {
        return copy(base, Mode.INJECT, true, base.fulfillmentModes(), base.channelSystemTypes(), base.locationIds());
    }

    private static AutoAddRule withLocations(AutoAddRule base, UUID... locations) {
        return copy(
                base,
                base.mode(),
                base.mandatory(),
                base.fulfillmentModes(),
                base.channelSystemTypes(),
                Set.of(locations));
    }
}
