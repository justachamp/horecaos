package uz.horecaos.platform.ordering.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.catalog.api.MenuPriceLookup;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Injection;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Outcome;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Skipped;
import uz.horecaos.platform.ordering.domain.BasketFacts;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * Applies the auto-add rules to a cart (ADR 0158): the one place that writes a line the customer did
 * not type.
 *
 * <p>Called by the cart on every edit, inside the same transaction, so web, bot and operator entry see
 * one basket. It inserts a missing injected line; changes the quantity or variant of one the customer has
 * not adjusted; removes one whose rule no longer fires; leaves a customer-adjusted line alone unless it now
 * exceeds the rule's maximum or, for a mandatory rule, has fallen below the computed quantity; and does
 * nothing at all while {@code ordering.auto_add_enabled} is false.
 *
 * <p><strong>A rule that cannot be honoured is skipped, never made into a refusal.</strong> The checks are
 * the cart's own, run on the injected variant exactly as {@code putLine} and {@code price()} run them on a
 * customer's line (on the menu at the location, on sale now, available, priced), and a failure is a
 * {@link Skipped}. The pure {@link AutoAddEvaluator} is told the answer; it reads no cart, no clock and
 * no stock.
 *
 * <p>An injected line is an ordinary cart line with a provenance: key {@code aa:<ruleId>}, origin {@code
 * AUTO_ADD}, the rule id and the rule's published version. The customer's own lines are the only input to
 * the rule, so a line a rule injected can never trigger another rule.
 */
@Component
public class AutoAddReconciler {

    /** Reserved for lines a rule injected; a customer cannot create one. */
    public static final String LINE_KEY_PREFIX = "aa:";

    /** The Problem Details code for removing, or lowering below the computed quantity, a mandatory line. */
    public static final String AUTO_ADD_LINE_MANDATORY = "AUTO_ADD_LINE_MANDATORY";

    /** The Problem Details code for a quantity outside the rule's bounds. */
    public static final String AUTO_ADD_QUANTITY_OUT_OF_BOUNDS = "AUTO_ADD_QUANTITY_OUT_OF_BOUNDS";

    static final String RULES_METRIC = "horecaos.autoadd.rules";
    static final String SKIPPED_METRIC = "horecaos.autoadd.skipped";

    private final JdbcCartStore carts;
    private final CartAutoAddRules rules;
    private final SalesChannelLookup channels;
    private final ConfigurationResolver configuration;
    private final InventoryReservationPort inventory;
    private final CartSaleWindowRules saleWindows;
    private final OrderingTenantContext tenancy;
    private final MenuPriceLookup prices;
    private final CartPricingPort pricing;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AutoAddReconciler(
            JdbcCartStore carts,
            CartAutoAddRules rules,
            SalesChannelLookup channels,
            ConfigurationResolver configuration,
            InventoryReservationPort inventory,
            CartSaleWindowRules saleWindows,
            OrderingTenantContext tenancy,
            MenuPriceLookup prices,
            CartPricingPort pricing,
            ObjectMapper objectMapper,
            MeterRegistry metrics,
            Clock clock) {
        this.carts = carts;
        this.rules = rules;
        this.channels = channels;
        this.configuration = configuration;
        this.inventory = inventory;
        this.saleWindows = saleWindows;
        this.tenancy = tenancy;
        this.prices = prices;
        this.pricing = pricing;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What the cart's reads say about one injected line, resolved from the published rule. */
    public record LineInfo(
            UUID ruleId,
            String ruleCode,
            Map<String, String> customerText,
            boolean mandatory,
            boolean removable,
            BigDecimal minimumQuantity,
            BigDecimal maximumQuantity) {}

    /** Whether the gate is open for this cart's brand: closed means the cart neither adds nor removes. */
    public boolean enabled(CartRow cart) {
        return Boolean.TRUE.equals(configuration.value(
                OrderingConfigurationKeys.AUTO_ADD_ENABLED,
                ResourceScope.location(cart.tenantId(), cart.brandId(), cart.locationId())));
    }

    private boolean shadow(CartRow cart) {
        return Boolean.TRUE.equals(configuration.value(
                OrderingConfigurationKeys.AUTO_ADD_SHADOW_ENABLED,
                ResourceScope.location(cart.tenantId(), cart.brandId(), cart.locationId())));
    }

    /**
     * Brings the cart's injected lines in line with the rules, inside the caller's transaction.
     *
     * @return true when a line was written or removed, so the caller advances the cart's version and the
     *         priced response can say the basket changed
     */
    public boolean reconcile(CartRow cart) {
        boolean enabled = enabled(cart);
        boolean shadow = !enabled && shadow(cart);
        if (!enabled && !shadow) {
            return false;
        }
        Optional<SalesChannel> channel = channels.byId(cart.tenantId(), cart.channelId());
        if (channel.isEmpty()) {
            return false;
        }
        List<PublishedAutoAddRule> published =
                rules.rulesFor(cart.tenantId(), cart.brandId(), channel.get().code());
        List<CartLineRow> lines = carts.lines(cart.tenantId(), cart.cartId());
        boolean holdsInjected = lines.stream().anyMatch(CartLineRow::injected);
        if (published.isEmpty() && (!holdsInjected || shadow)) {
            return false;
        }

        Evaluation evaluation = evaluate(cart, channel.get(), published, lines);
        if (shadow) {
            // Count what would have fired and write nothing: the owner reads these numbers against
            // what staff add by hand before turning the rule set on.
            for (Outcome outcome : evaluation.outcomes()) {
                if (outcome instanceof Skipped skipped) {
                    count(skipped.rule(), skipped.reason().equals(AutoAddEvaluator.DECLINED) ? "declined" : "skipped");
                    metrics.counter(SKIPPED_METRIC, "reason", reasonTag(skipped.reason()))
                            .increment();
                } else {
                    count(outcome.rule(), "shadow");
                }
            }
            return false;
        }
        return apply(cart, lines, evaluation);
    }

    /**
     * What the rules compute for this cart now, for one rule: the quantity a mandatory line may not fall
     * below. Empty when the rule does not fire for the basket as it stands.
     */
    public Optional<BigDecimal> computedQuantity(CartRow cart, UUID ruleId) {
        Optional<SalesChannel> channel = channels.byId(cart.tenantId(), cart.channelId());
        if (channel.isEmpty()) {
            return Optional.empty();
        }
        List<PublishedAutoAddRule> published =
                rules.rulesFor(cart.tenantId(), cart.brandId(), channel.get().code());
        Evaluation evaluation = evaluate(cart, channel.get(), published, carts.lines(cart.tenantId(), cart.cartId()));
        return evaluation.outcomes().stream()
                .filter(outcome -> outcome.rule().ruleId().equals(ruleId))
                .filter(Injection.class::isInstance)
                .map(outcome -> ((Injection) outcome).quantity())
                .findFirst();
    }

    /**
     * Describes the injected lines among these: which rule put each there, the text the customer reads,
     * whether it can be removed and the bounds on its quantity. A line whose rule the publication no longer
     * carries, or any line while the gate is closed, is an ordinary line that can be removed.
     */
    public Map<String, LineInfo> describe(CartRow cart, List<CartLineRow> lines) {
        Map<String, LineInfo> described = new LinkedHashMap<>();
        if (lines.stream().noneMatch(CartLineRow::injected)) {
            return described;
        }
        boolean enforced = enabled(cart);
        Optional<SalesChannel> channel = channels.byId(cart.tenantId(), cart.channelId());
        Map<UUID, PublishedAutoAddRule> byId = new LinkedHashMap<>();
        channel.ifPresent(found -> rules.rulesFor(cart.tenantId(), cart.brandId(), found.code())
                .forEach(rule -> byId.put(rule.ruleId(), rule)));
        for (CartLineRow line : lines) {
            if (!line.injected() || line.autoAddRuleId() == null) {
                continue;
            }
            PublishedAutoAddRule rule = byId.get(line.autoAddRuleId());
            if (rule == null) {
                described.put(
                        line.lineKey(),
                        new LineInfo(
                                line.autoAddRuleId(), "", Map.of(), false, true, BigDecimal.ZERO, line.quantity()));
                continue;
            }
            boolean mandatory = enforced && rule.mandatory();
            BigDecimal minimum =
                    !mandatory ? BigDecimal.ZERO : (line.customerAdjusted() ? rule.minimumQuantity() : line.quantity());
            described.put(
                    line.lineKey(),
                    new LineInfo(
                            rule.ruleId(),
                            rule.code(),
                            rule.customerText(),
                            mandatory,
                            !mandatory,
                            minimum,
                            rule.maximumQuantity()));
        }
        return described;
    }

    /**
     * Refuses a quantity the customer may not set on an injected line (ADR 0158): above the rule's maximum,
     * or, for a mandatory rule, below the quantity the rules compute for the basket now.
     *
     * @throws CartService.CartRefusedException {@code AUTO_ADD_QUANTITY_OUT_OF_BOUNDS}
     */
    public void requireQuantityInBounds(CartRow cart, CartLineRow line, BigDecimal quantity) {
        Map<String, LineInfo> info = describe(cart, List.of(line));
        LineInfo described = info.get(line.lineKey());
        if (described == null) {
            return;
        }
        if (quantity.compareTo(described.maximumQuantity()) > 0) {
            throw new CartService.CartRefusedException(
                    AUTO_ADD_QUANTITY_OUT_OF_BOUNDS,
                    "This line holds at most %s"
                            .formatted(described.maximumQuantity().toPlainString()));
        }
        if (described.mandatory() && line.autoAddRuleId() != null) {
            BigDecimal floor = computedQuantity(cart, line.autoAddRuleId()).orElse(described.minimumQuantity());
            if (quantity.compareTo(floor) < 0) {
                throw new CartService.CartRefusedException(
                        AUTO_ADD_QUANTITY_OUT_OF_BOUNDS,
                        "This line holds at least %s".formatted(floor.toPlainString()));
            }
        }
    }

    /**
     * Whether a customer (or an operator on the customer's behalf) may not decline this rule: it is published
     * as mandatory and the gate is open. A rule the publication no longer carries cannot be mandatory.
     */
    public boolean isMandatory(CartRow cart, UUID ruleId) {
        if (!enabled(cart)) {
            return false;
        }
        return channels.byId(cart.tenantId(), cart.channelId())
                .map(channel -> rules.rulesFor(cart.tenantId(), cart.brandId(), channel.code()).stream()
                        .anyMatch(rule -> rule.ruleId().equals(ruleId) && rule.mandatory()))
                .orElse(false);
    }

    // ------------------------------------------------------------------------------ internals

    private record Evaluation(List<Outcome> outcomes) {}

    private Evaluation evaluate(
            CartRow cart, SalesChannel channel, List<PublishedAutoAddRule> published, List<CartLineRow> lines) {
        BasketFacts basket = basketOf(cart, channel, lines);
        AutoAddEvaluator.Context context = new AutoAddEvaluator.Context(
                cart.fulfillmentMode().name(), channel.systemType().name(), cart.locationId());
        Set<UUID> declined = carts.declinedRules(cart.tenantId(), cart.cartId());

        // Phase one asks what would fire if every variant could be sold; the cart's own checks are then
        // run on exactly those variants and handed back, so the evaluator stays a pure function.
        List<Outcome> candidates =
                AutoAddEvaluator.evaluate(published, basket, context, declined, AutoAddEvaluator.Eligibility.ALL);
        Set<UUID> candidateVariants = new LinkedHashSet<>();
        for (Outcome outcome : candidates) {
            if (outcome instanceof Injection injection) {
                candidateVariants.add(injection.variantId());
            } else if (outcome instanceof AutoAddEvaluator.Offer offer) {
                candidateVariants.add(offer.variantId());
            }
        }
        Map<UUID, String> unusable = unusableVariants(cart, channel, candidateVariants);
        List<Outcome> outcomes = AutoAddEvaluator.evaluate(
                published,
                basket,
                context,
                declined,
                (rule, variantId) -> Optional.ofNullable(unusable.get(variantId)));
        return new Evaluation(outcomes);
    }

    /** The customer's own lines as a rule sees them: combos expanded into components, injected lines left out. */
    private BasketFacts basketOf(CartRow cart, SalesChannel channel, List<CartLineRow> lines) {
        record Sold(UUID variantId, BigDecimal units) {}
        List<CartLineRow> customerLines = lines.stream()
                .filter(line -> CartLineRow.ORIGIN_CUSTOMER.equals(line.origin()))
                .toList();
        Set<UUID> componentIds = new LinkedHashSet<>();
        Map<String, List<CartService.ComboPick>> picksByLine = new LinkedHashMap<>();
        for (CartLineRow line : customerLines) {
            List<CartService.ComboPick> picks = comboPicks(line);
            if (!picks.isEmpty()) {
                picksByLine.put(line.lineKey(), picks);
                picks.forEach(pick -> componentIds.add(pick.componentId()));
            }
        }
        Map<UUID, CartAutoAddRules.ComboComponentFacts> components =
                rules.comboComponents(cart.tenantId(), cart.brandId(), channel.code(), componentIds);

        List<Sold> sold = new ArrayList<>();
        for (CartLineRow line : customerLines) {
            List<CartService.ComboPick> picks = picksByLine.get(line.lineKey());
            if (picks == null) {
                sold.add(new Sold(line.variantId(), line.quantity()));
                continue;
            }
            for (CartService.ComboPick pick : picks) {
                CartAutoAddRules.ComboComponentFacts component = components.get(pick.componentId());
                if (component != null) {
                    sold.add(new Sold(
                            component.variantId(),
                            line.quantity()
                                    .multiply(
                                            BigDecimal.valueOf((long) component.defaultQuantity() * pick.quantity()))));
                }
            }
        }
        Set<UUID> variantIds = new LinkedHashSet<>();
        sold.forEach(one -> variantIds.add(one.variantId()));
        Map<UUID, CartAutoAddRules.VariantFacts> facts =
                rules.variantFacts(cart.tenantId(), cart.brandId(), channel.code(), variantIds);
        List<BasketFacts.Line> basket = new ArrayList<>();
        for (Sold one : sold) {
            CartAutoAddRules.VariantFacts fact = facts.get(one.variantId());
            basket.add(BasketFacts.Line.of(
                    one.variantId(),
                    fact == null ? null : fact.productId(),
                    fact == null ? Set.of() : fact.categoryIds(),
                    one.units(),
                    fact == null ? null : fact.portionsPerUnit()));
        }
        return new BasketFacts(basket);
    }

    private List<CartService.ComboPick> comboPicks(CartLineRow line) {
        if (line.comboPicksJson() == null || line.comboPicksJson().isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(line.comboPicksJson(), new TypeReference<List<CartService.ComboPick>>() {});
    }

    /**
     * Runs the cart's own checks on the variants a rule would add and reports the ones that fail, by a stable
     * reason code. On the menu at that location, on sale now, available and priced, plus the pricing port's
     * own selection check: none of them refuses the cart, each skips the rule.
     */
    private Map<UUID, String> unusableVariants(CartRow cart, SalesChannel channel, Set<UUID> variantIds) {
        Map<UUID, String> unusable = new LinkedHashMap<>();
        if (variantIds.isEmpty()) {
            return unusable;
        }
        Map<UUID, CartAutoAddRules.VariantFacts> facts =
                rules.variantFacts(cart.tenantId(), cart.brandId(), channel.code(), variantIds);
        for (UUID variantId : variantIds) {
            if (!facts.containsKey(variantId)) {
                unusable.put(variantId, "NOT_ON_MENU");
            }
        }
        Instant now = clock.instant();
        Optional<ZoneId> zone = tenancy.timezoneOf(cart.tenantId(), cart.locationId());
        for (UUID variantId : variantIds) {
            if (unusable.containsKey(variantId)) {
                continue;
            }
            if (zone.isPresent()
                    && !saleWindows.isOnSaleAt(cart.tenantId(), cart.locationId(), variantId, zone.get(), now)) {
                unusable.put(variantId, "ITEM_OUT_OF_SALE_WINDOW");
                continue;
            }
            try {
                pricing.checkSelection(
                        cart.tenantId(),
                        cart.brandId(),
                        channel.code(),
                        new CartPricingPort.PricingCommand.Item("aa:probe", variantId, BigDecimal.ONE, List.of()));
            } catch (CartPricingPort.PricingRefusedException refused) {
                unusable.put(variantId, "SELECTION_REFUSED");
            }
        }
        Set<UUID> stillUsable = new LinkedHashSet<>(variantIds);
        stillUsable.removeAll(unusable.keySet());
        if (!stillUsable.isEmpty()) {
            AvailabilityDecision decision = inventory.checkAvailabilityOnChannel(
                    cart.tenantId(), cart.locationId(), stillUsable, cart.channelId());
            if (!decision.available()) {
                for (AvailabilityDecision.Unavailable item : decision.unavailableItems()) {
                    unusable.put(item.variantId(), item.reason());
                }
            }
            Optional<MenuPriceLookup.MenuPrices> priced = prices.pricesFor(
                    cart.tenantId(), cart.brandId(), cart.locationId(), channel.code(), stillUsable, Set.of());
            for (UUID variantId : stillUsable) {
                if (!unusable.containsKey(variantId)
                        && (priced.isEmpty() || !priced.get().variantPrices().containsKey(variantId))) {
                    unusable.put(variantId, "UNPRICED");
                }
            }
        }
        return unusable;
    }

    private boolean apply(CartRow cart, List<CartLineRow> lines, Evaluation evaluation) {
        Map<String, CartLineRow> existing = new LinkedHashMap<>();
        lines.stream().filter(CartLineRow::injected).forEach(line -> existing.put(line.lineKey(), line));
        Instant now = clock.instant();
        boolean changed = false;
        Set<String> keep = new HashSet<>();

        for (Outcome outcome : evaluation.outcomes()) {
            if (outcome instanceof Skipped skipped) {
                count(skipped.rule(), skipped.reason().equals(AutoAddEvaluator.DECLINED) ? "declined" : "skipped");
                metrics.counter(SKIPPED_METRIC, "reason", reasonTag(skipped.reason()))
                        .increment();
                continue;
            }
            if (!(outcome instanceof Injection injection)) {
                // OFFER rules suggest and write nothing: they ship as their own change.
                continue;
            }
            PublishedAutoAddRule rule = injection.rule();
            String key = LINE_KEY_PREFIX + rule.ruleId();
            keep.add(key);
            CartLineRow current = existing.get(key);
            if (current == null) {
                if (!carts.insertInjectedLine(
                        UUID.randomUUID(),
                        cart.tenantId(),
                        cart.cartId(),
                        key,
                        injection.variantId(),
                        injection.quantity(),
                        rule.ruleId(),
                        rule.version(),
                        now)) {
                    // Another request on this cart wrote the rule's line after this one read the lines: the
                    // basket this edit was built on is gone. A stale version, which a client re-reads and
                    // retries, and not the duplicate key a plain insert would have raised.
                    throw new CartService.StaleCartException(
                            cart.version(),
                            carts.find(cart.tenantId(), cart.brandId(), cart.cartId())
                                    .map(CartRow::version)
                                    .orElse(cart.version()));
                }
                count(rule, "fired");
                changed = true;
                continue;
            }
            boolean sameVariant = current.variantId().equals(injection.variantId());
            BigDecimal target;
            boolean adjusted;
            if (!sameVariant || !current.customerAdjusted()) {
                // A line the customer has not touched follows the rule; a band that moved to another variant
                // is another line altogether and starts again from the computed quantity.
                target = injection.quantity();
                adjusted = false;
            } else if (rule.mandatory() && current.quantity().compareTo(injection.quantity()) < 0) {
                target = injection.quantity();
                adjusted = true;
            } else if (current.quantity().compareTo(rule.maximumQuantity()) > 0) {
                target = rule.maximumQuantity();
                adjusted = true;
            } else {
                target = current.quantity();
                adjusted = true;
            }
            boolean versionMoved =
                    current.autoAddRuleVersion() == null || current.autoAddRuleVersion() != rule.version();
            if (!sameVariant
                    || current.quantity().compareTo(target) != 0
                    || adjusted != current.customerAdjusted()
                    || versionMoved) {
                carts.updateInjectedLine(
                        cart.tenantId(),
                        cart.cartId(),
                        key,
                        injection.variantId(),
                        target,
                        rule.version(),
                        adjusted,
                        now);
                changed = changed || !sameVariant || current.quantity().compareTo(target) != 0;
            }
        }

        // A rule that no longer fires, was skipped, or is no longer published takes its line with it.
        for (CartLineRow line : existing.values()) {
            if (!keep.contains(line.lineKey())) {
                carts.deleteLine(cart.tenantId(), cart.cartId(), line.lineKey());
                changed = true;
            }
        }
        return changed;
    }

    private void count(PublishedAutoAddRule rule, String outcome) {
        metrics.counter(RULES_METRIC, "kind", rule.kind().name().toLowerCase(Locale.ROOT), "outcome", outcome)
                .increment();
    }

    /** Skip reasons are closed codes; anything unrecognised is folded so a label can never carry a free value. */
    private static String reasonTag(String reason) {
        return switch (reason) {
            case AutoAddEvaluator.DECLINED,
                    "NOT_ON_MENU",
                    "ITEM_OUT_OF_SALE_WINDOW",
                    "SELECTION_REFUSED",
                    "UNPRICED",
                    "SOLD_OUT",
                    "ON_STOP",
                    "NOT_STOCKED_AT_LOCATION",
                    "CHANNEL_STOPPED",
                    "RESERVATION_NO_LONGER_HELD" -> reason.toLowerCase(Locale.ROOT);
            default -> "other";
        };
    }
}
