package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.DeliveryFeePort;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.pricing.api.QuoteAcceptance;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.pricing.domain.Money;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
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
     * Long enough to finish a checkout, short enough that a sold-out item or a
     * price change is caught before payment rather than after. Matches the
     * ADR 0017 reservation TTL, so a hold never outlives the price it was
     * taken for.
     */
    public static final Duration QUOTE_TTL = Duration.ofMinutes(15);

    /** Uzbekistan VAT. A tenant elsewhere gets its own profile row. */
    private static final String DEFAULT_JURISDICTION = "UZ";

    private final JdbcPricingStore store;
    private final PricingEngine engine;
    private final CatalogPricingContext catalog;
    private final SalesChannelLookup channels;
    private final DeliveryFeePort deliveryFees;
    private final Clock clock;

    public QuoteService(
            JdbcPricingStore store,
            PricingEngine engine,
            CatalogPricingContext catalog,
            SalesChannelLookup channels,
            DeliveryFeePort deliveryFees,
            Clock clock) {
        this.store = store;
        this.engine = engine;
        this.catalog = catalog;
        this.channels = channels;
        this.deliveryFees = deliveryFees;
        this.clock = clock;
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
        UUID pricingChannelId = channel.map(SalesChannel::pricingChannelId).orElse(null);

        var publication = catalog.activePublicationId(request.tenantId(), request.brandId(), request.channel())
                .orElseThrow(() -> new NoPublishedMenuException(request.brandId()));

        var priceBook = store.resolvePriceBook(
                        request.tenantId(), request.brandId(), request.locationId(), pricingChannelId, now)
                .orElseThrow(() -> new NoPriceBookException(request.brandId(), request.locationId()));

        var taxProfile = store.resolveTaxProfile(request.tenantId(), request.brandId(), DEFAULT_JURISDICTION, now)
                .orElseThrow(() -> new NoTaxProfileException(request.brandId()));

        Set<UUID> variantIds =
                request.lines().stream().map(QuoteRequest.Line::variantId).collect(Collectors.toUnmodifiableSet());
        Set<UUID> modifierIds = request.lines().stream()
                .flatMap(line -> line.modifierOptionIds().stream())
                .collect(Collectors.toUnmodifiableSet());

        Map<UUID, Long> variantPrices = store.pricesFor(priceBook.id(), "VARIANT", variantIds, now);
        Map<UUID, Long> modifierPrices = store.pricesFor(priceBook.id(), "MODIFIER_OPTION", modifierIds, now);

        // ADR 0037. The delivery charge is resolved here, before the engine runs,
        // for the same reason the price book is: everything that could differ
        // between two runs — geometry, a clock, a routing provider — happens in
        // this method, and nothing that decides an amount happens anywhere but the
        // engine. The resolved charge enters the context hash, so a zone edit
        // invalidates an in-flight quote exactly as a price change does.
        //
        // The quote id is minted before resolution rather than after, so the
        // evidence row can name the quote it explains. Resolving first and
        // stitching the id on afterwards would need an UPDATE against a table that
        // is deliberately write-once.
        UUID quoteId = UUID.randomUUID();
        ResolvedDeliveryCharge charge = resolveDeliveryCharge(
                request, quoteId, priceBook.currency(), goodsSubtotal(request, variantPrices, modifierPrices), now);

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
                catalog.descriptions(request.tenantId(), request.brandId(), variantIds),
                charge);

        var result = engine.price(request, inputs, now);

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
                now.plus(QUOTE_TTL),
                now);

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
     * The basket total, from the same price maps the engine will use.
     *
     * <p>Unpriced items are skipped rather than thrown on here. The engine refuses
     * them a few lines later with the id of the offending item, and throwing first
     * from a helper whose job is a threshold comparison would move that error to a
     * place that cannot explain it.
     */
    private static long goodsSubtotal(
            QuoteRequest request, Map<UUID, Long> variantPrices, Map<UUID, Long> modifierPrices) {
        long subtotal = 0;
        for (QuoteRequest.Line line : request.lines()) {
            Long unit = variantPrices.get(line.variantId());
            if (unit == null) {
                continue;
            }
            long withModifiers = unit;
            for (UUID optionId : line.modifierOptionIds()) {
                withModifiers += modifierPrices.getOrDefault(optionId, 0L);
            }
            subtotal = Math.addExact(subtotal, Math.multiplyExact(withModifiers, (long) line.quantity()));
        }
        return subtotal;
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
                command.items().stream()
                        .map(item -> new QuoteRequest.Line(
                                item.lineKey(), item.variantId(), item.quantity(), item.modifierOptionIds()))
                        .toList(),
                command.idempotencyKey(),
                // Null until ordering supplies a destination on the command. Until
                // then a cart priced through this port is priced as a collection,
                // which is the honest reading of a command that names no address.
                null);

        try {
            Quote quote = quote(request);
            // Re-read rather than mapping the in-memory result. An idempotent
            // replay returns a header-only reconstruction with no lines, and
            // mapping that would hand ordering an empty basket to snapshot onto an
            // order. The row is the authority in both paths.
            return store.findQuoteSnapshot(command.tenantId(), quote.quoteId())
                    .orElseThrow(() -> new IllegalStateException("Quote vanished mid-transaction"));
        } catch (PricingEngine.UnpricedItemException unpriced) {
            throw new PricingRefusedException("ITEM_NOT_PRICED", unpriced.priceableId(), unpriced.getMessage());
        } catch (NoPublishedMenuException noMenu) {
            throw new PricingRefusedException("NO_PUBLISHED_MENU", command.brandId(), noMenu.getMessage());
        } catch (NoPriceBookException noBook) {
            throw new PricingRefusedException("NO_PRICE_BOOK", command.locationId(), noBook.getMessage());
        } catch (NoTaxProfileException noTax) {
            throw new PricingRefusedException("NO_TAX_PROFILE", command.brandId(), noTax.getMessage());
        } catch (PricingEngine.UnsupportedTaxModeException unsupported) {
            throw new PricingRefusedException("UNSUPPORTED_TAX_MODE", command.brandId(), unsupported.getMessage());
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
                Money total = Objects.requireNonNull(acceptance.total(), "an ACCEPTED acceptance always carries a total");
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
                        row.expiresAt()));
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
