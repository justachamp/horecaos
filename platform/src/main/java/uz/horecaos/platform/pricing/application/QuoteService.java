package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.DeliveryFeePort;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.pricing.api.PricingConfigurationKeys;
import uz.horecaos.platform.pricing.api.PromoCodeQueryPort;
import uz.horecaos.platform.pricing.api.QuoteAcceptance;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.pricing.domain.Money;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * The quote lifecycle (ADR 0018).
 *
 * <p>Resolves the inputs, runs the pure {@link PricingEngine} over them, and
 * stores the result with its evidence. The split matters: everything that could
 * differ between two runs — a price book lookup, the clock — happens here, and
 * nothing that decides an amount happens anywhere but the engine.
 */
@Service
public class QuoteService implements QuoteAcceptancePort, CartPricingPort {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    /**
     * The platform default: long enough to finish a checkout, short enough
     * that a sold-out item or a price change is caught before payment rather
     * than after. Matches the ADR 0017 reservation TTL, so a hold never
     * outlives the price it was taken for — {@code
     * PricingConfigurationKeyTests} keeps this literal equal to {@link
     * PricingConfigurationKeys#QUOTE_TTL_SECONDS}'s own default so the two
     * cannot drift apart.
     *
     * <p>Wired 2026-09-10 to {@code pricing.quote_ttl_seconds} (ADR 0030):
     * {@link #quote} resolves the live TTL per tenant/brand/location rather
     * than using this constant directly. It survives as the code default a
     * tenant that has never overridden the key still gets, and as the number
     * this class's own javadoc and tests can point at.
     */
    public static final Duration QUOTE_TTL = Duration.ofMinutes(15);

    /** Uzbekistan VAT. A tenant elsewhere gets its own profile row. */
    private static final String DEFAULT_JURISDICTION = "UZ";

    private final JdbcPricingStore store;
    private final PricingEngine engine;
    private final CatalogPricingContext catalog;
    private final SalesChannelLookup channels;
    private final DeliveryFeePort deliveryFees;
    private final JdbcPromoCodeStore promoCodes;
    private final PromoCodeEligibilityService promoCodeEligibility;
    private final Clock clock;
    private final ConfigurationResolver configuration;
    private final CompositeProductsLookup composites;

    /**
     * A service that prices ordinary carts only: nothing it prices is a combo, nothing
     * is auto-selected, and a nested selection finds no facts to be valid against.
     * Kept for the many tests that build the service by hand and have no composite
     * product to say anything about.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public QuoteService(
            JdbcPricingStore store,
            PricingEngine engine,
            CatalogPricingContext catalog,
            SalesChannelLookup channels,
            DeliveryFeePort deliveryFees,
            JdbcPromoCodeStore promoCodes,
            PromoCodeEligibilityService promoCodeEligibility,
            Clock clock,
            ConfigurationResolver configuration) {
        this(
                store,
                engine,
                catalog,
                channels,
                deliveryFees,
                promoCodes,
                promoCodeEligibility,
                clock,
                configuration,
                CompositeProductsLookup.none());
    }

    @Autowired
    @SuppressWarnings("checkstyle:ParameterNumber")
    public QuoteService(
            JdbcPricingStore store,
            PricingEngine engine,
            CatalogPricingContext catalog,
            SalesChannelLookup channels,
            DeliveryFeePort deliveryFees,
            JdbcPromoCodeStore promoCodes,
            PromoCodeEligibilityService promoCodeEligibility,
            Clock clock,
            ConfigurationResolver configuration,
            CompositeProductsLookup composites) {
        this.store = store;
        this.engine = engine;
        this.catalog = catalog;
        this.channels = channels;
        this.deliveryFees = deliveryFees;
        this.promoCodes = promoCodes;
        this.promoCodeEligibility = promoCodeEligibility;
        this.clock = clock;
        this.configuration = configuration;
        this.composites = composites;
    }

    /**
     * The ADR 0030 quote TTL in force at this scope (ADR 0030,
     * {@code pricing.quote_ttl_seconds}), falling back to {@link #QUOTE_TTL}
     * only in the sense that the key's own code default is that same value —
     * see {@link PricingConfigurationKeys#QUOTE_TTL_SECONDS}.
     */
    private Duration quoteTtl(UUID tenantId, UUID brandId, UUID locationId) {
        Integer seconds = configuration.value(
                PricingConfigurationKeys.QUOTE_TTL_SECONDS, ResourceScope.location(tenantId, brandId, locationId));
        return Duration.ofSeconds(Objects.requireNonNull(
                seconds, "pricing.quote_ttl_seconds declares a code default and never terminates on explicit null"));
    }

    /**
     * {@code catalog.qr_kiosk_price_plane}, resolved the shared way — see
     * {@link QrKioskHallPricing#resolve} — for the actual cart a customer
     * checks out with.
     */
    private UUID resolvePricingChannelId(UUID tenantId, SalesChannel channel) {
        return QrKioskHallPricing.resolve(tenantId, channel, channels, configuration);
    }

    /**
     * Prices a cart.
     *
     * <p>An idempotency key returns the existing quote rather than a second one,
     * so a retried request cannot leave the customer holding two quotes and two
     * reservations for the same basket.
     */
    @Transactional
    public Quote quote(QuoteRequest request) {
        Instant now = clock.instant();

        if (request.idempotencyKey() != null) {
            Optional<UUID> existing = store.findByIdempotencyKey(request.tenantId(), request.idempotencyKey());
            if (existing.isPresent()) {
                log.debug("Returning existing quote {} for idempotency key", existing.get());
                return reload(request.tenantId(), existing.get())
                        .orElseThrow(() -> new IllegalStateException("Quote vanished mid-transaction"));
            }
        }

        // ADR 0036: the cart's channel decides both the menu and the price plane.
        // An unregistered channel code resolves to no channel rather than to a
        // default one, so a typo cannot quietly price against the storefront.
        var channel = channels.byCode(request.tenantId(), request.channel());
        UUID pricingChannelId = channel.map(resolved -> resolvePricingChannelId(request.tenantId(), resolved))
                .orElse(null);

        var publication = catalog.activePublicationId(request.tenantId(), request.brandId(), request.channel())
                .orElseThrow(() -> new NoPublishedMenuException(request.brandId()));

        var priceBook = store.resolvePriceBook(
                        request.tenantId(), request.brandId(), request.locationId(), pricingChannelId, now)
                .orElseThrow(() -> new NoPriceBookException(request.brandId(), request.locationId()));

        var taxProfile = store.resolveTaxProfile(request.tenantId(), request.brandId(), DEFAULT_JURISDICTION, now)
                .orElseThrow(() -> new NoTaxProfileException(request.brandId()));

        Set<UUID> lineVariantIds =
                request.lines().stream().map(QuoteRequest.Line::variantId).collect(Collectors.toUnmodifiableSet());

        // ADR 0136. Everything composite is resolved here, before the engine runs, for
        // the reason the price book is: the engine stays a function of its inputs.
        CompositeProductsLookup.ComboCatalog combos =
                composites.comboCatalog(request.tenantId(), request.brandId(), lineVariantIds);

        Set<UUID> pickedComponentIds = request.lines().stream()
                .flatMap(line -> line.comboPicks().stream())
                .map(QuoteRequest.ComboPick::componentId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> componentVariantIds = pickedComponentIds.stream()
                .map(combos.components()::get)
                .filter(Objects::nonNull)
                .map(CompositePricing.ComboComponentFact::componentVariantId)
                .collect(Collectors.toUnmodifiableSet());

        // A container is never priced and has no line, so it is not looked up as an
        // ordinary variant: an unpriced container is the correct state of a combo.
        Set<UUID> pricedVariantIds = new HashSet<>(lineVariantIds);
        pricedVariantIds.removeAll(combos.groupIdsByContainer().keySet());
        pricedVariantIds.addAll(componentVariantIds);

        FulfillmentMode mode = request.effectiveFulfillmentMode();
        Map<UUID, List<CompositePricing.HiddenCharge>> hiddenCharges =
                composites.hiddenCharges(request.tenantId(), request.brandId(), pricedVariantIds, mode);

        Set<UUID> firstLevelOptionIds = request.lines().stream()
                .flatMap(line -> line.modifierOptionIds().stream())
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> nestedOptionIds = request.lines().stream()
                .flatMap(line -> line.nestedModifiers().stream())
                .flatMap(nested -> java.util.stream.Stream.of(nested.parentOptionId(), nested.optionId()))
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> selectedOptionIds = new HashSet<>(firstLevelOptionIds);
        selectedOptionIds.addAll(nestedOptionIds);
        CompositeProductsLookup.NestedCatalog nestedCatalog =
                composites.nestedCatalog(request.tenantId(), request.brandId(), selectedOptionIds);

        Set<UUID> modifierIds = new HashSet<>(selectedOptionIds);
        hiddenCharges.values().forEach(charges -> charges.forEach(charge -> modifierIds.add(charge.optionId())));

        Map<UUID, Long> variantPrices = store.pricesFor(priceBook.id(), "VARIANT", pricedVariantIds, now);
        Map<UUID, Long> modifierPrices = store.pricesFor(priceBook.id(), "MODIFIER_OPTION", modifierIds, now);
        Map<UUID, Long> componentPrices = store.pricesFor(priceBook.id(), "COMBO_COMPONENT", pickedComponentIds, now);

        // One id per combo cart line, shared by every component line it becomes. Minted
        // here and not in the engine, which must stay a function of its inputs; it is not
        // part of the context hash, because it prices nothing.
        Map<String, UUID> selectionIds = new HashMap<>();
        for (QuoteRequest.Line line : request.lines()) {
            if (combos.groupIdsByContainer().containsKey(line.variantId())) {
                selectionIds.put(line.lineId(), Ids.newId());
            }
        }

        var composite = new CompositePricing.CompositeInputs(
                combos.groups(),
                combos.groupIdsByContainer(),
                combos.components(),
                componentPrices,
                hiddenCharges,
                nestedCatalog.options(),
                nestedCatalog.groupsByVariant(),
                selectionIds);

        // ADR 0037. The delivery charge is resolved here, before the engine runs, for
        // the same reason the price book is: everything that could differ between two
        // runs -- geometry, a clock, a routing provider -- happens in this method, and
        // nothing that decides an amount happens anywhere but the engine. The resolved
        // charge enters the context hash, so a zone edit invalidates an in-flight quote
        // exactly as a price change does.
        //
        // The quote id is minted before resolution rather than after, so the evidence
        // row can name the quote it explains. Resolving first and stitching the id on
        // afterwards would need an UPDATE against a table that is deliberately
        // write-once.
        //
        // The goods subtotal the resolver is handed is the engine's own, asked of a
        // draft of the inputs that has no charge yet: the same arithmetic, not a second
        // copy of it, so a combo's components and a hidden charge count toward a zone's
        // minimum basket exactly as they will count toward the total.
        UUID quoteId = UUID.randomUUID();
        Map<UUID, String> descriptions = catalog.descriptions(request.tenantId(), request.brandId(), pricedVariantIds);
        var draft = new PricingEngine.PricingInputs(
                priceBook.currency(),
                publication,
                priceBook.id(),
                priceBook.version(),
                taxProfile.id(),
                taxProfile.version(),
                taxProfile.rateBasisPoints(),
                PricingEngine.TaxMode.valueOf(taxProfile.mode()),
                variantPrices,
                modifierPrices,
                descriptions,
                null,
                null,
                composite);
        ResolvedDeliveryCharge charge = resolveDeliveryCharge(
                request, quoteId, priceBook.currency(), engine.goodsSubtotal(request, draft), now);

        var inputs = new PricingEngine.PricingInputs(
                priceBook.currency(),
                publication,
                priceBook.id(),
                priceBook.version(),
                taxProfile.id(),
                taxProfile.version(),
                taxProfile.rateBasisPoints(),
                PricingEngine.TaxMode.valueOf(taxProfile.mode()),
                variantPrices,
                modifierPrices,
                descriptions,
                charge,
                resolvePromotionInputs(request, now),
                composite);

        var result = engine.price(request, inputs, now);

        Duration ttl = quoteTtl(request.tenantId(), request.brandId(), request.locationId());
        Quote quote = new Quote(
                quoteId,
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                request.customerAccountId(),
                priceBook.currency(),
                Quote.Status.ACTIVE,
                publication,
                PricingEngine.CALCULATION_VERSION,
                result.contextHash(),
                result.subtotal(),
                result.tax(),
                result.fees(),
                result.discount(),
                result.total(),
                result.lines(),
                result.adjustments(),
                now.plus(ttl),
                now,
                inputs.deliveryCharge() == null ? null : inputs.deliveryCharge().outcome(),
                result.deliveryShortfallMinor(),
                inputs.deliveryCharge() == null ? null : inputs.deliveryCharge().minBasketMinor(),
                inputs.deliveryCharge() == null ? null : inputs.deliveryCharge().freeDeliveryFromMinor());

        store.insertQuote(quote, request.idempotencyKey(), evidence(request, inputs, result));
        return quote;
    }

    /**
     * Runs ADR 0037 steps 1 to 6, or nothing at all for a collected order.
     *
     * <p>The subtotal handed to the resolver is the goods subtotal the engine is
     * about to compute, recomputed here from the same price maps rather than taken
     * from a later stage. That looks like duplication and is not avoidable: steps 7
     * and 8 compare the basket against the zone's minimum and threshold, and the
     * resolver has to carry both back before the engine can apply them. Today it is
     * exactly the engine's gross because ADR 0018's discount stages are unbuilt;
     * when they land, this becomes the post-discount figure and the two stop being
     * the same number.
     *
     * <p>A refusal is not an exception. An address outside every zone is a fact the
     * storefront renders, and the quote still returns — with no fee line — so the
     * customer sees their basket and the reason together instead of an error page.
     */
    private @Nullable ResolvedDeliveryCharge resolveDeliveryCharge(
            QuoteRequest request, UUID quoteId, String currency, long goodsSubtotal, Instant now) {

        if (request.delivery() == null) {
            return null;
        }
        ResolvedDeliveryCharge charge = deliveryFees.resolve(new DeliveryFeeQuery(
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                quoteId,
                request.delivery().destination(),
                currency,
                goodsSubtotal,
                request.delivery().pricingAuthority(),
                now));

        if (charge.outcome() != DeliveryFeeOutcome.RESOLVED
                && charge.outcome() != DeliveryFeeOutcome.EXTERNALLY_PRICED) {
            log.info("Delivery fee not resolved for location {}: {}", request.locationId(), charge.outcome());
        }
        return charge;
    }

    /**
     * ADR 0072 stages 3 and 4's input: every promotion this brand could apply,
     * and whether a presented promo code is eligible right now.
     *
     * <p>Both reads are fresh — the brand's {@code ACTIVE} promotion list and
     * the coupon's own limits and window — never cached across calls, per ADR
     * 0072's "neither check trusts the other's earlier answer". A code that
     * was eligible when the customer applied it and has since been exhausted
     * by another checkout simply produces no
     * {@code presentedCouponPromotionIds} entry: {@code PromotionEvaluator}
     * then skips its promotion (which {@code requiresCoupon}) and this price
     * carries no discount for it, changing the context hash exactly as a
     * changed price book would.
     */
    private PricingEngine.PromotionInputs resolvePromotionInputs(QuoteRequest request, Instant now) {
        List<Promotion> promotions =
                promoCodes.listActivePromotionsForPricing(request.tenantId(), request.brandId(), now);

        Set<UUID> presentedCouponPromotionIds = Set.of();
        if (request.presentedCouponCode() != null
                && !request.presentedCouponCode().isBlank()) {
            PromoCodeQueryPort.Eligibility eligibility = promoCodeEligibility.check(
                    request.tenantId(),
                    request.brandId(),
                    request.presentedCouponCode(),
                    request.customerAccountId(),
                    now);
            if (eligibility.isEligible()) {
                presentedCouponPromotionIds = Set.of(Objects.requireNonNull(
                        eligibility.promotionId(), "an OK eligibility always names a promotion"));
            }
        }

        // A repricing of an order that already exists (an amendment) carries the
        // redemption that order's own checkout took. That redemption holds its
        // slot: the coupon's consumed_count and the customer's usage row already
        // include this very order, so re-running the eligibility check above would
        // read "limit reached" for a coupon whose last slot is this order's own --
        // which is the ordinary state of a one-per-customer code -- and quietly
        // drop the discount the customer was promised. The promotion is therefore
        // presented as recorded, loaded by id because a later expiry, suspension
        // or retirement of the code is not a reason to take a redemption off an
        // order that still exists (ADR 0072: a redemption is final once checkout
        // commits). What is not carried over is any exemption from the promotion's
        // own conditions -- minimum basket, channel, location, the discount cap --
        // which PromotionEvaluator still evaluates on the new basket.
        if (request.carriedRedemptionOrderId() != null) {
            Optional<JdbcPromoCodeStore.HeldRedemption> held =
                    promoCodes.findRedemptionHeldByOrder(request.tenantId(), request.carriedRedemptionOrderId());
            if (held.isPresent() && held.get().brandId().equals(request.brandId())) {
                UUID heldPromotionId = held.get().promotionId();
                List<Promotion> heldPromotions = promoCodes.promotionsForPricingByIds(
                        request.tenantId(), request.brandId(), List.of(heldPromotionId));
                if (!heldPromotions.isEmpty()) {
                    // Replaces the same promotion if the active list already had it,
                    // so it is never priced twice, and stays in force past the close
                    // of a window the order redeemed inside.
                    List<Promotion> withHeld = new ArrayList<>(promotions.stream()
                            .filter(p -> !p.promotionId().equals(heldPromotionId))
                            .toList());
                    heldPromotions.forEach(p -> withHeld.add(p.heldPastItsWindow()));
                    promotions = withHeld;
                    Set<UUID> presented = new HashSet<>(presentedCouponPromotionIds);
                    presented.add(heldPromotionId);
                    presentedCouponPromotionIds = Set.copyOf(presented);
                }
            }
        }

        // firstOrder and customerSegments are always the same neutral value:
        // no condition this ADR's authoring surface writes ever reads either
        // (FIRST_ORDER and CUSTOMER_SEGMENT are outside the closed condition
        // set — see ADR 0072), so there is nothing here to resolve correctly
        // yet. localDayOfWeek/localMinuteOfDay are UTC-derived rather than
        // resolved from the location's own IANA timezone for the identical
        // reason (DAY_OF_WEEK/TIME_OF_DAY are likewise outside the closed
        // set) — ADR 0072 records this explicitly as a negative consequence,
        // not a silent gap: the day either of those conditions is authored
        // through any surface, this becomes a real defect.
        var utcNow = now.atZone(ZoneOffset.UTC);
        var context = new PromotionEvaluator.PromotionContext(
                request.channel(),
                request.locationId(),
                request.delivery() != null ? "DELIVERY" : "PICKUP",
                false,
                Set.of(),
                presentedCouponPromotionIds,
                utcNow.getDayOfWeek().getValue(),
                utcNow.get(ChronoField.MINUTE_OF_DAY));

        return new PricingEngine.PromotionInputs(promotions, context, Map.of());
    }

    /**
     * Accepts a quote at checkout.
     *
     * <p>The context hash must still match. If a price book changed or the menu
     * was republished under the customer's feet, the answer is a fresh quote and
     * a stable {@code PRICE_CHANGED} response — never a silent charge of the
     * difference, which is the behaviour customers experience as a scam.
     */
    @Transactional
    public Acceptance accept(UUID tenantId, UUID quoteId, String expectedContextHash) {
        Instant now = clock.instant();

        var row = store.findQuote(tenantId, quoteId).orElseThrow(() -> new IllegalArgumentException("No such quote"));

        if (!row.contextHash().equals(expectedContextHash)) {
            return Acceptance.contextChanged();
        }
        if (row.expiresAt().isBefore(now) || row.expiresAt().equals(now)) {
            return Acceptance.expired();
        }

        // Conditional update rather than a check followed by a write: two
        // concurrent checkouts would otherwise both see an active quote and both
        // proceed, and the second would pay for a basket already committed.
        if (!store.acceptQuote(tenantId, quoteId, now)) {
            return Acceptance.expired();
        }
        return Acceptance.accepted(Money.of(row.totalMinor(), row.currency()));
    }

    @Transactional(readOnly = true)
    public Optional<JdbcPricingStore.QuoteRow> find(UUID tenantId, UUID quoteId) {
        return store.findQuote(tenantId, quoteId);
    }

    /**
     * The ADR 0019 cart-pricing entry point.
     *
     * <p>Translates pricing's own exceptions into one stable, coded refusal.
     * Ordering must not catch {@code UnpricedItemException} or
     * {@code NoPublishedMenuException} directly: those live in pricing's internals
     * and would make the module boundary a fiction.
     */
    @Override
    @Transactional
    public QuoteSnapshot priceCart(PricingCommand command) {
        var request = new QuoteRequest(
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                command.customerAccountId(),
                command.channelCode(),
                command.items().stream().map(QuoteService::lineOf).toList(),
                command.idempotencyKey(),
                // Null for a cart being collected, or a delivery cart that has not
                // named a destination yet — both are honestly "not priced as a
                // delivery" rather than a fee this call invents.
                command.delivery() == null
                        ? null
                        : new QuoteRequest.Delivery(
                                command.delivery().destination(),
                                command.delivery().pricingAuthority()),
                command.presentedCouponCode(),
                command.carriedRedemptionOrderId(),
                command.fulfillmentMode());

        try {
            Quote quote = quote(request);
            // Re-read rather than mapping the in-memory result. An idempotent
            // replay returns a header-only reconstruction with no lines, and
            // mapping that would hand ordering an empty basket to snapshot onto an
            // order. The row is the authority in both paths.
            return store.findQuoteSnapshot(command.tenantId(), quote.quoteId())
                    .orElseThrow(() -> new IllegalStateException("Quote vanished mid-transaction"));
        } catch (PricingEngine.UnpricedItemException unpriced) {
            // Every one of these five exception types always constructs its
            // message from a non-null string concatenation or formatted string
            // (see their constructors), so getMessage() is never actually null;
            // the fallback only guards the checker's more conservative view of
            // Throwable#getMessage() rather than a real possibility here.
            throw new PricingRefusedException(
                    "ITEM_NOT_PRICED",
                    unpriced.priceableId(),
                    Objects.requireNonNullElse(unpriced.getMessage(), "ITEM_NOT_PRICED"));
        } catch (NoPublishedMenuException noMenu) {
            throw new PricingRefusedException(
                    "NO_PUBLISHED_MENU",
                    command.brandId(),
                    Objects.requireNonNullElse(noMenu.getMessage(), "NO_PUBLISHED_MENU"));
        } catch (NoPriceBookException noBook) {
            throw new PricingRefusedException(
                    "NO_PRICE_BOOK",
                    command.locationId(),
                    Objects.requireNonNullElse(noBook.getMessage(), "NO_PRICE_BOOK"));
        } catch (NoTaxProfileException noTax) {
            throw new PricingRefusedException(
                    "NO_TAX_PROFILE",
                    command.brandId(),
                    Objects.requireNonNullElse(noTax.getMessage(), "NO_TAX_PROFILE"));
        } catch (CompositePricing.CompositeSelectionException selection) {
            // ADR 0136: a combo or nested selection the catalog does not allow. The
            // code is the exception's own -- COMBO_GROUP_MINIMUM_NOT_MET and the like
            // -- so a storefront can say which group needs another pick.
            throw new PricingRefusedException(
                    selection.code(),
                    selection.subjectId(),
                    Objects.requireNonNullElse(selection.getMessage(), selection.code()));
        } catch (CompositeProductsLookup.HiddenModifierAmbiguousException ambiguous) {
            throw new PricingRefusedException(
                    "HIDDEN_MODIFIER_GROUP_AMBIGUOUS",
                    ambiguous.groupId(),
                    Objects.requireNonNullElse(ambiguous.getMessage(), "HIDDEN_MODIFIER_GROUP_AMBIGUOUS"));
        }
    }

    private static QuoteRequest.Line lineOf(PricingCommand.Item item) {
        return new QuoteRequest.Line(
                item.lineKey(),
                item.variantId(),
                item.quantity(),
                item.modifierOptionIds(),
                item.comboPicks().stream()
                        .map(pick -> new QuoteRequest.ComboPick(pick.componentId(), pick.quantity()))
                        .toList(),
                item.nestedModifiers().stream()
                        .map(nested -> new QuoteRequest.NestedModifier(nested.parentOptionId(), nested.optionId()))
                        .toList());
    }

    /**
     * ADR 0136: the selection rules of {@link #priceCart}, over the same facts, without
     * the price book, the tax profile or a stored quote. Nothing is written.
     */
    @Override
    @Transactional(readOnly = true)
    public SelectionCheck checkSelection(UUID tenantId, UUID brandId, PricingCommand.Item item) {
        QuoteRequest.Line line = lineOf(item);
        CompositeProductsLookup.ComboCatalog combos =
                composites.comboCatalog(tenantId, brandId, Set.of(item.variantId()));

        Set<UUID> optionIds = new HashSet<>(item.modifierOptionIds());
        item.nestedModifiers().forEach(nested -> {
            optionIds.add(nested.parentOptionId());
            optionIds.add(nested.optionId());
        });
        CompositeProductsLookup.NestedCatalog nested = composites.nestedCatalog(tenantId, brandId, optionIds);

        var facts = new CompositePricing.CompositeInputs(
                combos.groups(),
                combos.groupIdsByContainer(),
                combos.components(),
                Map.of(),
                Map.of(),
                nested.options(),
                nested.groupsByVariant(),
                Map.of());
        try {
            List<CompositePricing.ComboLine> combo = CompositePricing.resolveCombo(line, facts);
            if (combo != null) {
                return new SelectionCheck(
                        true,
                        combo.stream()
                                .map(pick -> pick.component().componentVariantId())
                                .collect(Collectors.toUnmodifiableSet()));
            }
            CompositePricing.resolveNested(line, facts);
            return new SelectionCheck(false, Set.of(item.variantId()));
        } catch (CompositePricing.CompositeSelectionException selection) {
            throw new PricingRefusedException(
                    selection.code(),
                    selection.subjectId(),
                    Objects.requireNonNullElse(selection.getMessage(), selection.code()));
        }
    }

    /**
     * The ADR 0019 checkout entry point.
     *
     * <p>Delegates to {@link #accept} rather than duplicating it: one conditional
     * update decides the race, and a second copy of that logic would eventually
     * disagree with the first about whether a quote had already been used.
     */
    @Override
    @Transactional
    public QuoteAcceptance acceptQuote(UUID tenantId, UUID quoteId, String expectedContextHash) {
        Acceptance acceptance = accept(tenantId, quoteId, expectedContextHash);
        return switch (acceptance.outcome()) {
            case ACCEPTED -> {
                // Only the ACCEPTED outcome carries a total (see Acceptance.accepted());
                // requireNonNull documents that invariant for a checker that cannot see
                // across the switch on its own.
                Money total =
                        Objects.requireNonNull(acceptance.total(), "an ACCEPTED acceptance always carries a total");
                yield new QuoteAcceptance(QuoteAcceptance.Outcome.ACCEPTED, total.minor(), total.currency());
            }
            case PRICE_CHANGED -> new QuoteAcceptance(QuoteAcceptance.Outcome.PRICE_CHANGED, 0L, null);
            case EXPIRED -> new QuoteAcceptance(QuoteAcceptance.Outcome.EXPIRED, 0L, null);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuoteSnapshot> quoteSnapshot(UUID tenantId, UUID quoteId) {
        return store.findQuoteSnapshot(tenantId, quoteId);
    }

    /** Sweeps expired quotes. Scheduled elsewhere; kept here so the rule lives with the model. */
    @Transactional
    public int expireStaleQuotes() {
        int expired = store.expireQuotes(clock.instant());
        if (expired > 0) {
            log.debug("Expired {} stale quotes", expired);
        }
        return expired;
    }

    private Optional<Quote> reload(UUID tenantId, UUID quoteId) {
        // The stored row is the authority for an idempotent replay; the lines and
        // adjustments are re-read only when a caller asks for the detail.
        return store.findQuote(tenantId, quoteId)
                .map(row -> new Quote(
                        row.id(),
                        tenantId,
                        null,
                        null,
                        null,
                        row.currency(),
                        row.status(),
                        row.catalogPublicationId(),
                        row.calculationVersion(),
                        row.contextHash(),
                        Money.zero(row.currency()),
                        Money.zero(row.currency()),
                        Money.zero(row.currency()),
                        Money.zero(row.currency()),
                        Money.of(row.totalMinor(), row.currency()),
                        java.util.List.of(),
                        java.util.List.of(),
                        row.expiresAt(),
                        row.expiresAt(),
                        // Not on QuoteRow: this reconstruction only ever backs an
                        // idempotent-replay return, and priceCart re-reads the real
                        // header — including these fields — from findQuoteSnapshot
                        // rather than trusting this value. A direct QuoteService.quote()
                        // caller replaying under an idempotency key sees no delivery
                        // detail here, which is the same "header-only" gap this
                        // reconstruction already has for every other amount.
                        null,
                        null,
                        null,
                        null));
    }

    /** The calculation inputs, stored as evidence beside the normalized columns. */
    private static Map<String, Object> evidence(
            QuoteRequest request, PricingEngine.PricingInputs inputs, PricingEngine.Result result) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("calculationVersion", PricingEngine.CALCULATION_VERSION);
        document.put("priceBookId", String.valueOf(inputs.priceBookId()));
        document.put("priceBookVersion", inputs.priceBookVersion());
        document.put("taxProfileId", String.valueOf(inputs.taxProfileId()));
        document.put("taxRateBasisPoints", inputs.taxRateBasisPoints());
        document.put("taxMode", inputs.taxMode().name());
        document.put("channel", request.channel());
        // ADR 0136: the fulfilment mode hidden auto-selected groups were applied for.
        document.put("fulfillmentMode", request.effectiveFulfillmentMode().name());
        document.put("contextHash", result.contextHash());
        if (inputs.deliveryCharge() != null) {
            // The normalized columns of fulfillment.delivery_fee_resolutions are
            // the authority for the fee; this is a copy beside the quote so a
            // single row explains the total without a cross-schema join.
            document.put("deliveryOutcome", inputs.deliveryCharge().outcome().name());
            document.put("deliveryFeeMinor", inputs.deliveryCharge().feeMinor());
            document.put(
                    "deliveryZoneId", String.valueOf(inputs.deliveryCharge().zoneId()));
            document.put("deliveryZoneVersion", inputs.deliveryCharge().zoneVersion());
            document.put(
                    "deliveryTariffId", String.valueOf(inputs.deliveryCharge().tariffId()));
            document.put("deliveryTariffVersion", inputs.deliveryCharge().tariffVersion());
            document.put("deliveryDistanceMeters", inputs.deliveryCharge().distanceMeters());
            document.put("deliveryDistanceSource", inputs.deliveryCharge().distanceSource());
        }
        if (result.deliveryShortfallMinor() != null) {
            document.put("deliveryShortfallMinor", result.deliveryShortfallMinor());
        }
        return document;
    }

    /**
     * The result of trying to accept a quote at checkout.
     *
     * @param total null unless outcome is {@link Outcome#ACCEPTED}.
     */
    public record Acceptance(Outcome outcome, @Nullable Money total) {

        public enum Outcome {
            ACCEPTED,
            PRICE_CHANGED,
            EXPIRED
        }

        static Acceptance accepted(Money total) {
            return new Acceptance(Outcome.ACCEPTED, total);
        }

        static Acceptance contextChanged() {
            return new Acceptance(Outcome.PRICE_CHANGED, null);
        }

        static Acceptance expired() {
            return new Acceptance(Outcome.EXPIRED, null);
        }
    }

    public static class NoPublishedMenuException extends RuntimeException {
        public NoPublishedMenuException(UUID brandId) {
            super("Brand " + brandId + " has no published menu to price against");
        }
    }

    public static class NoPriceBookException extends RuntimeException {
        public NoPriceBookException(UUID brandId, UUID locationId) {
            super("No active price book for brand %s at location %s".formatted(brandId, locationId));
        }
    }

    public static class NoTaxProfileException extends RuntimeException {
        public NoTaxProfileException(UUID brandId) {
            super("No tax profile for brand " + brandId);
        }
    }
}
