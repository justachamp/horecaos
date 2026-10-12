package uz.horecaos.platform.catalog.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.domain.AutoAddRules;
import uz.horecaos.platform.catalog.domain.AutoAddRules.AutoAddRule;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Band;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Kind;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Mode;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Trigger;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcAutoAddRuleStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCompositeCatalogStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring for cart-injection rules (ADR 0158): the rule, its trigger nodes, its portion bands, the
 * locations it is limited to, and the customer text shown beside the line it injects.
 *
 * <p>Like every catalog write this edits the draft. Nothing reaches a customer until {@link
 * CatalogPublicationService} takes a snapshot that carries the rule (an {@code ACTIVE} rule is
 * published as an {@code AUTO_ADD_RULE} item), so an author can restructure a rule in the middle of
 * service; the cart enforces what the customer was shown.
 *
 * <p>The shape rules are enforced here where the service can name the offender in a refusal, and
 * again by the schema where it can: a raw constraint violation reaches a client as a bare 409 with no
 * way to tell which rule it broke. Rules that read the rest of the catalog or another module (a
 * variant that is not sellable, two rules adding one variant, a hidden modifier that already charges
 * the same box) belong to publication, where {@code CatalogValidator} names them.
 *
 * <p>A {@code PUT} replaces the three child sets in the same transaction as the rule's version bump
 * and the {@code .updated} audit fact (ADR 0027). A rule is archived, never deleted.
 */
@Service
public class AutoAddRuleAuthoringService {

    public static final String AUTO_ADD_RULE_INVALID = "AUTO_ADD_RULE_INVALID";
    public static final String AUTO_ADD_CHANNEL_NOT_ALLOWED = "AUTO_ADD_CHANNEL_NOT_ALLOWED";
    public static final String AUTO_ADD_BANDS_OVERLAP = "AUTO_ADD_BANDS_OVERLAP";
    public static final String AUTO_ADD_RULE_ARCHIVED = "AUTO_ADD_RULE_ARCHIVED";

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final Pattern LOCALE = Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$");

    /** The cart line's own ceiling (V0022): a rule cannot ask for more than one line can hold. */
    private static final BigDecimal CART_LINE_MAXIMUM = new BigDecimal("999");

    private static final int MAX_TRIGGERS = 50;
    private static final int MAX_BANDS = 20;
    private static final int MAX_LOCATIONS = 200;

    private final JdbcAutoAddRuleStore store;
    private final JdbcCompositeCatalogStore catalogLookups;
    private final AuditRecorder audit;
    private final Clock clock;

    public AutoAddRuleAuthoringService(
            JdbcAutoAddRuleStore store, JdbcCompositeCatalogStore catalogLookups, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.catalogLookups = catalogLookups;
        this.audit = audit;
        this.clock = clock;
    }

    /** A rule with its customer text, which is what every read returns. */
    public record RuleDetail(AutoAddRule rule, Map<String, String> customerText) {}

    /** What an author states about a rule; the same shape for a create and a replace. */
    public record RuleCommand(
            Mode mode,
            @Nullable Status status,
            @Nullable UUID addVariantId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal triggerThreshold,
            boolean repeatPerMultiple,
            BigDecimal minimumQuantity,
            BigDecimal maximumQuantity,
            boolean mandatory,
            Set<String> fulfillmentModes,
            Set<String> channelSystemTypes,
            int sortOrder,
            List<Trigger> triggers,
            List<Band> bands,
            Set<UUID> locationIds,
            Map<String, String> customerText) {}

    @Transactional(readOnly = true)
    public List<RuleDetail> list(UUID tenantId, UUID brandId) {
        List<AutoAddRule> rules = store.list(tenantId, brandId);
        Map<UUID, Map<String, String>> text = store.customerText(
                tenantId, brandId, rules.stream().map(AutoAddRule::id).toList());
        return rules.stream()
                .map(rule -> new RuleDetail(rule, text.getOrDefault(rule.id(), Map.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RuleDetail get(UUID tenantId, UUID brandId, UUID ruleId) {
        AutoAddRule rule = store.find(tenantId, brandId, ruleId).orElseThrow(() -> notFound(ruleId));
        return new RuleDetail(
                rule, store.customerText(tenantId, brandId, List.of(ruleId)).getOrDefault(ruleId, Map.of()));
    }

    /** Creates a rule as a draft. A rule is activated by a replace that says so. */
    @Transactional
    public RuleDetail create(
            UUID tenantId, UUID brandId, String code, Kind kind, RuleCommand command, String actorSubject) {
        require(CODE.matcher(code).matches(), "A rule code is 1 to 64 letters, digits, '_' or '-'");
        if (store.codeTaken(tenantId, brandId, code)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A rule with code %s already exists in this brand".formatted(code),
                    Map.of("findingCode", "AUTO_ADD_RULE_CODE_TAKEN"));
        }
        AutoAddRule rule = build(tenantId, brandId, Ids.newId(), code, kind, command, Status.DRAFT, 1);
        checkReferences(rule);
        store.insert(rule);
        Map<String, String> text = canonicalText(command.customerText());
        store.replaceCustomerText(tenantId, brandId, rule.id(), text);

        audit.record(AuditFact.of("catalog.autoAddRule.created", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("AutoAddRule", rule.id())
                .targetVersion((long) rule.version())
                .because("Created an auto-add rule")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.created(fields(rule)))
                .correlatedBy(rule.id().toString())
                .occurredAt(clock.instant())
                .build());
        return new RuleDetail(rule, text);
    }

    /**
     * Replaces a rule's fields and its child sets. The code and the kind are the rule's identity
     * and are not editable: a published line names the rule and its version, and turning one kind
     * into another would change what that evidence means.
     */
    @Transactional
    public RuleDetail replace(
            UUID tenantId, UUID brandId, UUID ruleId, int expectedVersion, RuleCommand command, String actorSubject) {
        AutoAddRule before = store.find(tenantId, brandId, ruleId).orElseThrow(() -> notFound(ruleId));
        if (before.status() == Status.ARCHIVED) {
            throw refused(AUTO_ADD_RULE_ARCHIVED, "An archived rule is not edited; create a new one");
        }
        Status status = command.status() == null ? before.status() : command.status();
        require(status != Status.ARCHIVED, "A rule is archived with the archive command, not a replace");
        AutoAddRule after =
                build(tenantId, brandId, ruleId, before.code(), before.kind(), command, status, before.version() + 1);
        checkReferences(after);
        if (!store.update(after, expectedVersion)) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        Map<String, String> text = canonicalText(command.customerText());
        store.replaceCustomerText(tenantId, brandId, ruleId, text);

        audit.record(AuditFact.of("catalog.autoAddRule.updated", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("AutoAddRule", ruleId)
                .targetVersion((long) after.version())
                .because("Changed an auto-add rule")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(fields(before), fields(after)))
                .correlatedBy(ruleId.toString())
                .occurredAt(clock.instant())
                .build());
        if (before.status() != Status.ACTIVE && after.status() == Status.ACTIVE) {
            audit.record(AuditFact.of("catalog.autoAddRule.activated", AuditClass.BUSINESS)
                    .by(ActorRef.user(actorSubject, null))
                    .at(ResourceScope.brand(tenantId, brandId))
                    .target("AutoAddRule", ruleId)
                    .targetVersion((long) after.version())
                    .because("Activated an auto-add rule; it reaches customers with the next publication")
                    .usingCapability(Capability.CATALOG_AUTHOR.code())
                    .changed(ChangeDocuments.diff(
                            Map.of("status", before.status().name()),
                            Map.of("status", after.status().name())))
                    .correlatedBy(ruleId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        return new RuleDetail(after, text);
    }

    /** Archives a rule. It leaves the next publication; the row stays, so any snapshot can be traced. */
    @Transactional
    public RuleDetail archive(UUID tenantId, UUID brandId, UUID ruleId, int expectedVersion, String actorSubject) {
        AutoAddRule before = store.find(tenantId, brandId, ruleId).orElseThrow(() -> notFound(ruleId));
        if (before.status() == Status.ARCHIVED) {
            throw refused(AUTO_ADD_RULE_ARCHIVED, "This rule is already archived");
        }
        AutoAddRule after = new AutoAddRule(
                before.id(),
                before.tenantId(),
                before.brandId(),
                before.code(),
                before.kind(),
                before.mode(),
                Status.ARCHIVED,
                before.addVariantId(),
                before.quantity(),
                before.triggerThreshold(),
                before.repeatPerMultiple(),
                before.minimumQuantity(),
                before.maximumQuantity(),
                before.mandatory(),
                before.fulfillmentModes(),
                before.channelSystemTypes(),
                before.sortOrder(),
                before.version() + 1,
                before.triggers(),
                before.bands(),
                before.locationIds());
        if (!store.update(after, expectedVersion)) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        audit.record(AuditFact.of("catalog.autoAddRule.archived", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("AutoAddRule", ruleId)
                .targetVersion((long) after.version())
                .because("Archived an auto-add rule")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(fields(before), fields(after)))
                .correlatedBy(ruleId.toString())
                .occurredAt(clock.instant())
                .build());
        return new RuleDetail(
                after, store.customerText(tenantId, brandId, List.of(ruleId)).getOrDefault(ruleId, Map.of()));
    }

    // -------------------------------------------------------------------------- the shape

    private AutoAddRule build(
            UUID tenantId, UUID brandId, UUID id, String code, Kind kind, RuleCommand c, Status status, int version) {
        require(c.mode() != null, "A rule has a mode");
        require(
                status == Status.DRAFT || status == Status.ACTIVE || status == Status.ARCHIVED,
                "A rule is a draft, active or archived");
        require(!c.fulfillmentModes().isEmpty(), "A rule names at least one fulfilment mode");
        require(
                AutoAddRules.FULFILLMENT_MODES.containsAll(c.fulfillmentModes()),
                "A fulfilment mode is DELIVERY, PICKUP or DINE_IN");
        require(!c.channelSystemTypes().isEmpty(), "A rule names at least one channel type");
        if (!AutoAddRules.CART_BUILT_CHANNEL_TYPES.containsAll(c.channelSystemTypes())) {
            // The schema says the same: an aggregator order arrives with its lines and total, and a
            // POS ticket is edited by the POS, so a rule cannot name either.
            throw refused(
                    AUTO_ADD_CHANNEL_NOT_ALLOWED,
                    "A rule names only the cart-built channel types: " + String.join(", ", sortedCartTypes()));
        }
        require(c.minimumQuantity() != null && c.minimumQuantity().signum() > 0, "The minimum quantity is positive");
        require(
                c.maximumQuantity() != null && c.maximumQuantity().compareTo(c.minimumQuantity()) >= 0,
                "The maximum quantity is not below the minimum");
        require(c.maximumQuantity().compareTo(CART_LINE_MAXIMUM) <= 0, "The maximum quantity is at most 999");
        require(
                fitsColumn(c.minimumQuantity()) && fitsColumn(c.maximumQuantity()),
                "A quantity has at most 3 decimals");
        require(
                !c.mandatory() || c.mode() == Mode.INJECT,
                "A mandatory rule injects; a customer cannot decline a suggestion");
        require(c.triggers().size() <= MAX_TRIGGERS, "A rule has at most %d trigger nodes".formatted(MAX_TRIGGERS));
        require(c.bands().size() <= MAX_BANDS, "A rule has at most %d bands".formatted(MAX_BANDS));
        require(c.locationIds().size() <= MAX_LOCATIONS, "A rule names at most %d locations".formatted(MAX_LOCATIONS));
        c.customerText().forEach((locale, text) -> {
            require(LOCALE.matcher(locale).matches(), "'%s' is not a language tag".formatted(locale));
            require(text != null && !text.isBlank() && text.length() <= 255, "Customer text is 1 to 255 characters");
        });

        UUID addVariantId = c.addVariantId();
        BigDecimal quantity = c.quantity();
        BigDecimal threshold = c.triggerThreshold();
        boolean repeat = c.repeatPerMultiple();
        List<Trigger> triggers = c.triggers();
        List<Band> bands = sortedBands(c.bands());

        switch (kind) {
            case PLAIN -> {
                require(addVariantId != null, "A plain rule names the variant it adds");
                require(
                        quantity != null && quantity.signum() > 0 && fitsColumn(quantity),
                        "A plain rule adds a positive quantity");
                require(threshold == null && !repeat, "A plain rule has no trigger threshold and no repeat");
                require(triggers.isEmpty() && bands.isEmpty(), "A plain rule has no triggers and no bands");
            }
            case PRODUCT_TRIGGERED -> {
                require(addVariantId != null, "A product-triggered rule names the variant it adds");
                require(
                        quantity != null && quantity.signum() > 0 && fitsColumn(quantity),
                        "A product-triggered rule adds a positive quantity per step");
                require(
                        threshold != null && threshold.signum() > 0 && fitsColumn(threshold),
                        "A product-triggered rule needs a positive threshold");
                require(bands.isEmpty(), "A product-triggered rule has no bands");
            }
            case PORTION_BAND -> {
                require(
                        addVariantId == null && quantity == null && threshold == null && !repeat,
                        "A portion-band rule takes its variant and quantity from its bands");
                require(triggers.isEmpty(), "A portion-band rule has no trigger nodes");
                requireBands(bands);
            }
        }
        return new AutoAddRule(
                id,
                tenantId,
                brandId,
                code,
                kind,
                c.mode(),
                status,
                addVariantId,
                quantity,
                threshold,
                repeat,
                c.minimumQuantity(),
                c.maximumQuantity(),
                c.mandatory(),
                c.fulfillmentModes(),
                c.channelSystemTypes(),
                c.sortOrder(),
                version,
                triggers,
                bands,
                c.locationIds());
    }

    private static List<String> sortedCartTypes() {
        return AutoAddRules.CART_BUILT_CHANNEL_TYPES.stream().sorted().toList();
    }

    private static List<Band> sortedBands(List<Band> bands) {
        return bands.stream().sorted(Comparator.comparing(Band::portionsFrom)).toList();
    }

    private void requireBands(List<Band> bands) {
        require(!bands.isEmpty(), "A portion-band rule needs at least one band");
        BigDecimal previousEnd = null;
        for (int index = 0; index < bands.size(); index++) {
            Band band = bands.get(index);
            require(
                    band.portionsFrom() != null && band.portionsFrom().signum() >= 0,
                    "A band starts at zero portions or more");
            require(
                    band.portionsTo() == null || band.portionsTo().compareTo(band.portionsFrom()) > 0,
                    "A band ends after it starts");
            require(band.addVariantId() != null, "A band names the variant it adds");
            require(
                    band.quantity() != null && band.quantity().signum() > 0 && fitsColumn(band.quantity()),
                    "A band adds a positive quantity");
            require(
                    fitsColumn(band.portionsFrom()) && (band.portionsTo() == null || fitsColumn(band.portionsTo())),
                    "A band edge has at most 3 decimals");
            if (previousEnd != null && band.portionsFrom().compareTo(previousEnd) < 0) {
                throw refused(AUTO_ADD_BANDS_OVERLAP, "Two bands of one rule cover the same portions");
            }
            if (band.portionsTo() == null && index != bands.size() - 1) {
                // Nothing may start after a band with no upper bound: it would sit inside it.
                throw refused(AUTO_ADD_BANDS_OVERLAP, "A band with no upper bound must be the last");
            }
            previousEnd = band.portionsTo();
        }
    }

    /** Every referenced catalog row exists in this brand; the answer for "not yours" and "missing" is the same. */
    private void checkReferences(AutoAddRule rule) {
        Set<UUID> variants = new LinkedHashSet<>(rule.addedVariantIds());
        Set<UUID> products = new LinkedHashSet<>();
        Set<UUID> categories = new LinkedHashSet<>();
        for (Trigger trigger : rule.triggers()) {
            if (trigger.variantId() != null) {
                variants.add(trigger.variantId());
            }
            if (trigger.productId() != null) {
                products.add(trigger.productId());
            }
            if (trigger.categoryId() != null) {
                categories.add(trigger.categoryId());
            }
        }
        Map<UUID, Status> found = catalogLookups.variantStatuses(rule.tenantId(), rule.brandId(), variants);
        for (UUID variantId : variants) {
            if (!found.containsKey(variantId)) {
                throw notFoundOf("variant", variantId);
            }
        }
        for (UUID addedId : rule.addedVariantIds()) {
            if (found.get(addedId) == Status.ARCHIVED) {
                throw refused(
                        AUTO_ADD_RULE_INVALID, "Variant %s is archived and cannot be injected".formatted(addedId));
            }
        }
        Set<UUID> knownProducts = store.productsOfBrand(rule.tenantId(), rule.brandId(), products);
        for (UUID productId : products) {
            if (!knownProducts.contains(productId)) {
                throw notFoundOf("product", productId);
            }
        }
        Set<UUID> knownCategories = store.categoriesOfBrand(rule.tenantId(), rule.brandId(), categories);
        for (UUID categoryId : categories) {
            if (!knownCategories.contains(categoryId)) {
                throw notFoundOf("category", categoryId);
            }
        }
        Set<UUID> knownLocations = store.locationsOfBrand(rule.tenantId(), rule.brandId(), rule.locationIds());
        for (UUID locationId : rule.locationIds()) {
            if (!knownLocations.contains(locationId)) {
                throw notFoundOf("location", locationId);
            }
        }
    }

    private static Map<String, String> canonicalText(Map<String, String> text) {
        Map<String, String> clean = new LinkedHashMap<>();
        text.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> clean.put(entry.getKey(), entry.getValue().strip()));
        return clean;
    }

    private static boolean fitsColumn(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 3 && value.abs().compareTo(new BigDecimal("9999999.999")) <= 0;
    }

    /** The fields an audit fact records: ids, codes and counts, never customer text. */
    private static Map<String, Object> fields(AutoAddRule rule) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", rule.code());
        fields.put("kind", rule.kind().name());
        fields.put("mode", rule.mode().name());
        fields.put("status", rule.status().name());
        fields.put(
                "addVariantId",
                rule.addVariantId() == null ? null : rule.addVariantId().toString());
        fields.put("quantity", rule.quantity() == null ? null : rule.quantity().toPlainString());
        fields.put(
                "triggerThreshold",
                rule.triggerThreshold() == null ? null : rule.triggerThreshold().toPlainString());
        fields.put("repeatPerMultiple", rule.repeatPerMultiple());
        fields.put("minimumQuantity", rule.minimumQuantity().toPlainString());
        fields.put("maximumQuantity", rule.maximumQuantity().toPlainString());
        fields.put("mandatory", rule.mandatory());
        fields.put("fulfillmentModes", rule.fulfillmentModes().stream().sorted().toList());
        fields.put(
                "channelSystemTypes",
                rule.channelSystemTypes().stream().sorted().toList());
        fields.put("sortOrder", rule.sortOrder());
        fields.put("triggerCount", rule.triggers().size());
        fields.put("bandCount", rule.bands().size());
        fields.put("locationCount", rule.locationIds().size());
        return fields;
    }

    private static void require(boolean holds, String detail) {
        if (!holds) {
            throw refused(AUTO_ADD_RULE_INVALID, detail);
        }
    }

    private static ApiException refused(String findingCode, String detail) {
        return new ApiException(ErrorCode.UNPROCESSABLE_STATE, detail, Map.of("findingCode", findingCode));
    }

    private static ApiException notFound(UUID ruleId) {
        return notFoundOf("auto-add rule", ruleId);
    }

    private static ApiException notFoundOf(String what, UUID id) {
        // One answer for "not yours" and "does not exist": a caller able to tell them apart has an
        // existence oracle for catalog ids.
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No %s %s in this brand".formatted(what, id));
    }
}
