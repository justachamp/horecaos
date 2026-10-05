package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import uz.horecaos.platform.pricing.api.QuoteAcceptance;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.pricing.domain.CatchweightFacts;
import uz.horecaos.platform.pricing.domain.Money;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
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
    private final PromotionInputResolver promotionInputs;
    private final Clock clock;
    private final ConfigurationResolver configuration;
    private final CompositeProductsLookup composites;
    private final PromotionMetrics metrics;

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
            PromotionInputResolver promotionInputs,
            Clock clock,
            ConfigurationResolver configuration) {
        this(
                store,
                engine,
                catalog,
                channels,
                deliveryFees,
                promotionInputs,
                clock,
                configuration,
                CompositeProductsLookup.none());
    }

    /** A service nobody scrapes the promotion counters of: every caller that predates them. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public QuoteService(
            JdbcPricingStore store,
            PricingEngine engine,
            CatalogPricingContext catalog,
            SalesChannelLookup channels,
            DeliveryFeePort deliveryFees,
            PromotionInputResolver promotionInputs,
            Clock clock,
            ConfigurationResolver configuration,
            CompositeProductsLookup composites) {
        this(
                store,
                engine,
                catalog,
                channels,
                deliveryFees,
                promotionInputs,
                clock,
                configuration,
                composites,
                PromotionMetrics.none());
    }

    @Autowired
    @SuppressWarnings("checkstyle:ParameterNumber")
    public QuoteService(
            JdbcPricingStore store,
            PricingEngine engine,
            CatalogPricingContext catalog,
            SalesChannelLookup channels,
            DeliveryFeePort deliveryFees,
            PromotionInputResolver promotionInputs,
            Clock clock,
            ConfigurationResolver configuration,
            CompositeProductsLookup composites,
            PromotionMetrics metrics) {
        this.metrics = metrics;
        this.store = store;
        this.engine = engine;
        this.catalog = catalog;
        this.channels = channels;
        this.deliveryFees = deliveryFees;
        this.promotionInputs = promotionInputs;
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

        // The quote id is minted before resolution rather than after, so the
        // evidence row can name the quote it explains. Resolving first and
        // stitching the id on afterwards would need an UPDATE against a table that
        // is deliberately write-once.
        UUID quoteId = UUID.randomUUID();
        Priced priced = price(request, quoteId, now, null);
        var inputs = priced.inputs();
        var result = priced.result();
        // Real quotes only: the simulator prices through the same engine and is not the platform pricing a basket.
        metrics.evaluated(result.promotionTrace());

        Duration ttl = quoteTtl(request.tenantId(), request.brandId(), request.locationId());
        Quote quote = new Quote(
                quoteId,
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                request.customerAccountId(),
                priced.currency(),
                Quote.Status.ACTIVE,
                priced.publicationId(),
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

        store.insertQuote(
                quote,
                request.idempotencyKey(),
                evidence(request, inputs, result, priced.recorded()),
                result.loyaltyAccrualAllowed(),
                result.loyaltyRedemptionAllowed());
        return quote;
    }

    /**
     * Prices a request and writes nothing: the simulator's entry point (ADR 0140).
     *
     * <p>The identical path a real quote takes -- the same price book, tax profile,
     * delivery resolution, input resolution and engine -- with the customer facts
     * supplied as {@link PromotionInputResolver.Overrides} instead of read from an
     * account, and the delivery resolution run without a quote to pin it to. It is
     * the reason a test can assert that the simulator and a real quote agree on
     * totals, adjustments and hash: there is no second implementation to drift.
     */
    @Transactional(readOnly = true)
    public Priced simulate(QuoteRequest request, PromotionInputResolver.Overrides overrides) {
        return price(request, null, clock.instant(), overrides);
    }

    /** The result of pricing one request, with everything a quote or a simulation reports about it. */
    public record Priced(
            String currency,
            UUID publicationId,
            PricingEngine.PricingInputs inputs,
            PricingEngine.Result result,
            RecordedPromotionInputs recorded) {}

    private Priced price(
            QuoteRequest request,
            @Nullable UUID quoteId,
            Instant now,
            PromotionInputResolver.@Nullable Overrides overrides) {

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
        // The structure is the publication's, the one this quote is stamped with: a combo edited
        // and not republished prices what the customer was shown.
        CompositeProductsLookup.ComboCatalog combos =
                composites.comboCatalog(request.tenantId(), request.brandId(), publication, lineVariantIds);

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
        // ADR 0137, from the same publication the quote is stamped with, so the facts a
        // customer was shown are the facts the line is priced by.
        Map<UUID, CatchweightFacts> catchweight = catalog.catchweightFacts(publication, pricedVariantIds);

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
                composites.nestedCatalog(request.tenantId(), request.brandId(), publication, selectedOptionIds);

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
        // The quote id arrives minted (see quote()) so the evidence row can name the quote
        // it explains; a simulation has none.
        //
        // The goods subtotal the resolver is handed is the engine's own, asked of a
        // draft of the inputs that has no charge yet: the same arithmetic, not a second
        // copy of it, so a combo's components and a hidden charge count toward a zone's
        // minimum basket exactly as they will count toward the total.
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
                composite,
                catchweight);
        ResolvedDeliveryCharge charge = resolveDeliveryCharge(
                request, quoteId, priceBook.currency(), engine.goodsSubtotal(request, draft), now);

        PromotionInputResolver.Resolved resolved =
                promotionInputs.resolve(request, now, channel, charge, pricedVariantIds, overrides);

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
                resolved.inputs(),
                composite,
                catchweight);

        var result = engine.price(request, inputs, now);

        // What actually applied is recorded beside what it was priced with, so an
        // amendment can inherit both and a later reader can name the definition
        // version each promotion was evaluated at.
        List<RecordedPromotionInputs.AppliedRef> applied = result.adjustments().stream()
                .filter(adjustment -> "PROMOTION".equals(adjustment.sourceType()) && adjustment.sourceId() != null)
                .map(adjustment -> new RecordedPromotionInputs.AppliedRef(
                        Objects.requireNonNull(adjustment.sourceId()),
                        Objects.requireNonNullElse(adjustment.sourceVersion(), 1)))
                .distinct()
                .sorted(java.util.Comparator.comparing(RecordedPromotionInputs.AppliedRef::promotionId))
                .toList();
        return new Priced(
                priceBook.currency(),
                publication,
                inputs,
                result,
                resolved.recorded().withApplied(applied));
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
            QuoteRequest request, @Nullable UUID quoteId, String currency, long goodsSubtotal, Instant now) {

        if (request.delivery() == null) {
            return null;
        }
        var feeQuery = new DeliveryFeeQuery(
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                quoteId,
                request.delivery().destination(),
                currency,
                goodsSubtotal,
                request.delivery().pricingAuthority(),
                now);
        // A real quote always has an id, and its fee resolution is evidence pinned to that quote.
        // The simulator prices without one, inside a read-only transaction (ADR 0140): the same
        // resolution, with nothing written -- an INSERT there is "cannot execute INSERT in a
        // read-only transaction", a 500 for every simulated delivery cart.
        ResolvedDeliveryCharge charge =
                quoteId == null ? deliveryFees.preview(feeQuery) : deliveryFees.resolve(feeQuery);

        if (charge.outcome() != DeliveryFeeOutcome.RESOLVED
                && charge.outcome() != DeliveryFeeOutcome.EXTERNALLY_PRICED) {
            log.info("Delivery fee not resolved for location {}: {}", request.locationId(), charge.outcome());
        }
        return charge;
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
                command.fulfillmentMode(),
                command.frame() == null
                        ? null
                        : new QuoteRequest.Frame(
                                command.frame().serviceInstant(),
                                command.frame().paymentMethodCode(),
                                command.frame().fulfillmentMode(),
                                command.frame().inheritFromQuoteId(),
                                command.frame().placedAt()));

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
        } catch (PricingEngine.NotCatchweightException notWeighed) {
            throw new PricingRefusedException(
                    "NOT_CATCHWEIGHT",
                    notWeighed.variantId(),
                    Objects.requireNonNullElse(notWeighed.getMessage(), "NOT_CATCHWEIGHT"));
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
                        .toList(),
                item.actualWeightGrams());
    }

    /**
     * ADR 0136: the selection rules of {@link #priceCart}, over the same facts, without
     * the price book, the tax profile or a stored quote. Nothing is written.
     */
    @Override
    @Transactional(readOnly = true)
    public SelectionCheck checkSelection(UUID tenantId, UUID brandId, String channelCode, PricingCommand.Item item) {
        QuoteRequest.Line line = lineOf(item);
        // The channel's live menu, the one pricing will stamp the quote with. A channel with none
        // sells no combo and offers no nested choice, so the facts are empty and any selection on
        // a combo is refused by name; the missing menu itself is pricing's to report.
        Optional<UUID> publication = catalog.activePublicationId(tenantId, brandId, channelCode);
        CompositeProductsLookup.ComboCatalog combos = publication
                .map(id -> composites.comboCatalog(tenantId, brandId, id, Set.of(item.variantId())))
                .orElseGet(CompositeProductsLookup.ComboCatalog::empty);

        Set<UUID> optionIds = new HashSet<>(item.modifierOptionIds());
        item.nestedModifiers().forEach(nested -> {
            optionIds.add(nested.parentOptionId());
            optionIds.add(nested.optionId());
        });
        CompositeProductsLookup.NestedCatalog nested = publication
                .map(id -> composites.nestedCatalog(tenantId, brandId, id, optionIds))
                .orElseGet(CompositeProductsLookup.NestedCatalog::empty);

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
            QuoteRequest request,
            PricingEngine.PricingInputs inputs,
            PricingEngine.Result result,
            RecordedPromotionInputs recorded) {
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
        // ADR 0140: the promotion inputs this quote was priced with, which an
        // amendment's reprice starts from.
        document.put("promotionInputs", recorded.toDocument());
        // What the engine decided about the coupon-gated promotions the cart
        // presented, so a storefront can say why a typed code did not move the
        // total (a better automatic offer, or a condition that did not hold). Kept
        // on the quote rather than recomputed: an idempotent replay returns this
        // row without running the engine.
        Set<UUID> presented = inputs.promotions() == null
                ? Set.of()
                : inputs.promotions().context().presentedCouponPromotionIds();
        if (!presented.isEmpty()) {
            List<Map<String, Object>> verdicts = new ArrayList<>();
            for (UUID promotionId : new TreeSet<>(presented)) {
                result.promotionTrace().stream()
                        .filter(entry -> entry.promotionId().equals(promotionId))
                        .findFirst()
                        .ifPresent(entry -> {
                            Map<String, Object> verdict = new LinkedHashMap<>();
                            verdict.put("promotionId", promotionId.toString());
                            verdict.put("verdict", entry.verdict().name());
                            verdicts.add(verdict);
                        });
            }
            if (!verdicts.isEmpty()) {
                document.put("couponVerdicts", verdicts);
            }
        }
        // ADR 0140: the gifts a firing FREE_ITEM rule would price free, in the cart or not, so
        // a storefront can offer adding one. Kept on the quote for the same reason as the
        // verdicts above: an idempotent replay never runs the engine, and the offer has to
        // survive a page reload. Quantities are strings, exact for a portion (0.5) as for 2.
        if (!result.giftOffers().isEmpty()) {
            List<Map<String, Object>> gifts = new ArrayList<>();
            for (PromotionEvaluator.GiftOffer offer : result.giftOffers()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("ruleId", offer.promotionId().toString());
                entry.put("variantId", offer.variantId().toString());
                entry.put("quantity", offer.quantity().toPlainString());
                entry.put("inCart", offer.inCart());
                entry.put("toAdd", offer.toAdd().toPlainString());
                gifts.add(entry);
            }
            document.put("giftOffers", gifts);
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
