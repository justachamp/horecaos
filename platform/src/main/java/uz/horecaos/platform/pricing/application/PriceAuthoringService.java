package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * Price authoring (ADR 0018).
 *
 * <p>The write side of the tables {@link QuoteService} reads. Until this existed
 * every price in the platform came from a test fixture, so no brand could be
 * priced without somebody with a database client.
 *
 * <p>Nothing written here reaches a cart until the book is activated — the same
 * shape as catalog authoring, where a draft is edited freely and a menu changes
 * only on publication. That is what lets an operator build next season's prices
 * during service without anything moving under the customers currently ordering.
 *
 * <p><b>What activation does to quotes already issued: nothing.</b> A quote is an
 * immutable row carrying its own totals, and checkout pays what that row says, so
 * a price changed at 12:00 cannot reach into a quote issued at 11:59 — the
 * customer is charged the price they were shown, for the fifteen minutes ADR 0018
 * gives them to finish. Superseding live quotes on activation was considered and
 * rejected: it would take the price away from a customer already at the payment
 * step, to save a merchant at most fifteen minutes of the old price. What a
 * change does do is bump the book's version, which is the number the context hash
 * pins, so the next quote for the same cart hashes differently and a checkout
 * carrying a stale hash is refused with {@code PRICE_CHANGED} rather than
 * silently repriced.
 */
@Service
public class PriceAuthoringService {

    private static final Logger log = LoggerFactory.getLogger(PriceAuthoringService.class);

    private final JdbcPricingStore store;
    private final CatalogPricingContext catalog;
    private final SalesChannelLookup channels;
    private final Clock clock;

    public PriceAuthoringService(
            JdbcPricingStore store, CatalogPricingContext catalog, SalesChannelLookup channels, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.channels = channels;
        this.clock = clock;
    }

    /**
     * Creates a draft price book.
     *
     * <p>The window is the book's own, and separate from its assignments': a
     * Ramadan price book is one book valid for one month, pointed at whichever
     * branches take part, and expressing that through per-assignment dates alone
     * would mean editing every branch to end it.
     */
    @Transactional
    public PriceBook create(UUID tenantId, UUID brandId, NewPriceBook command) {
        UUID priceBookId = UUID.randomUUID();
        Instant now = clock.instant();
        Instant validFrom = command.validFrom() == null ? now : command.validFrom();

        if (command.validUntil() != null && !command.validUntil().isAfter(validFrom)) {
            throw new IllegalArgumentException("A price book's window must end after it starts");
        }

        store.insertPriceBook(
                priceBookId,
                tenantId,
                brandId,
                command.name(),
                command.currency().toUpperCase(Locale.ROOT),
                validFrom,
                command.validUntil(),
                command.priority(),
                now);

        log.info("Price book {} drafted for brand {}", priceBookId, brandId);
        return require(tenantId, brandId, priceBookId);
    }

    @Transactional(readOnly = true)
    public PriceBook require(UUID tenantId, UUID brandId, UUID priceBookId) {
        return store.findPriceBookHeader(tenantId, brandId, priceBookId)
                .map(PriceBook::of)
                .orElseThrow(() -> new UnknownPriceBookException(priceBookId));
    }

    /**
     * Points a book at a brand, a location, or a channel.
     *
     * <p>A location scope is verified by the request path — ADR 0025 refuses a
     * location that is not this brand's before the handler runs — but a channel id
     * arrives in the body, so it is checked here. An assignment naming a channel
     * that does not exist would resolve for nobody, and the operator would see a
     * price book that simply never applies.
     */
    @Transactional
    public PriceBook assign(
            UUID tenantId,
            UUID brandId,
            UUID priceBookId,
            AssignmentScope scope,
            @Nullable UUID scopeId,
            Assignment command) {

        PriceBook book = require(tenantId, brandId, priceBookId);
        requireAuthorable(book);

        // scopeId == null short-circuits before byId ever sees a null id: only a
        // CHANNEL assignment supplies one, and a channel assignment naming no
        // channel is exactly as unknown as one naming a channel that does not
        // exist for this tenant.
        if (scope == AssignmentScope.CHANNEL
                && (scopeId == null || channels.byId(tenantId, scopeId).isEmpty())) {
            throw new UnknownAssignmentScopeException(scope, scopeId);
        }

        Instant now = clock.instant();
        Instant validFrom = command.validFrom() == null ? now : command.validFrom();
        if (command.validUntil() != null && !command.validUntil().isAfter(validFrom)) {
            throw new IllegalArgumentException("An assignment's window must end after it starts");
        }

        store.upsertAssignment(
                tenantId,
                brandId,
                priceBookId,
                scope.name(),
                scope == AssignmentScope.BRAND ? null : scopeId,
                command.priority(),
                validFrom,
                command.validUntil());
        store.touchPriceBook(tenantId, brandId, priceBookId, now);

        return require(tenantId, brandId, priceBookId);
    }

    /**
     * Sets what one thing costs.
     *
     * <p>{@code amountMinor} is integer minor units, and for UZS a minor unit is a
     * whole som: 50,000 here is 50,000 som on the menu, not 500. Tiyin are
     * obsolete and both payment providers settle in whole som, so there is no
     * hundredth of anything to divide by.
     *
     * <p>The price is VAT-inclusive. It is what the customer pays, and stage 7
     * extracts the tax from inside it rather than adding tax on top.
     */
    @Transactional
    public PriceBook setPrice(
            UUID tenantId, UUID brandId, UUID priceBookId, PriceableType type, UUID priceableId, long amountMinor) {

        PriceBook book = require(tenantId, brandId, priceBookId);
        requireAuthorable(book);

        if (amountMinor < 0) {
            // Zero is a free modifier and legitimate. Negative is a discount, and a
            // discount is an adjustment with a recorded source, never a price.
            throw new IllegalArgumentException("A price cannot be negative");
        }
        if (!catalog.priceableExists(tenantId, brandId, type, priceableId)) {
            throw new UnknownPriceableException(type, priceableId);
        }

        Instant now = clock.instant();
        store.setPrice(tenantId, brandId, priceBookId, type.name(), priceableId, amountMinor, now);
        store.touchPriceBook(tenantId, brandId, priceBookId, now);

        return require(tenantId, brandId, priceBookId);
    }

    /**
     * Puts a book in front of customers.
     *
     * <p>Separately capability-gated from authoring, because writing a price and
     * deciding it is the one customers pay are different decisions — the second is
     * the one that costs money if it is wrong.
     *
     * <p>Two operators activating at once are settled by the conditional update in
     * the store rather than by a check here: a read followed by a write would let
     * both observe a draft and both proceed.
     */
    @Transactional
    public PriceBook activate(UUID tenantId, UUID brandId, UUID priceBookId, int expectedVersion) {
        PriceBook book = require(tenantId, brandId, priceBookId);

        if (book.status() != Status.DRAFT) {
            throw new PriceBookLifecycleException("A %s price book cannot be activated".formatted(book.status()));
        }
        if (store.openPriceCount(tenantId, brandId, priceBookId) == 0) {
            // An active book that prices nothing is worse than no book at all: it
            // wins resolution for its scope and then refuses every cart under it
            // with ITEM_NOT_PRICED.
            throw new PriceBookLifecycleException(
                    "This price book prices nothing; add at least one price before activating it");
        }
        if (store.tiesWithALivePriceBook(tenantId, brandId, priceBookId)) {
            throw new PriceBookLifecycleException(
                    "Another active price book covers the same scope at the same priority; "
                            + "give one of them a higher priority or end the other's window");
        }

        if (!store.activatePriceBook(tenantId, brandId, priceBookId, expectedVersion, clock.instant())) {
            throw new OptimisticLockingFailureException("The price book changed since it was read");
        }

        log.info("Price book {} activated for brand {}", priceBookId, brandId);
        return require(tenantId, brandId, priceBookId);
    }

    /**
     * Sets the brand's VAT rate for a jurisdiction.
     *
     * <p>The minimum a quote needs, and no more: {@link QuoteService} resolves one
     * profile per brand per jurisdiction and hands the engine its rate and mode.
     * Without a row here every cart in the brand refuses with
     * {@code NO_TAX_PROFILE}.
     *
     * <p>{@code EXCLUSIVE} is refused rather than stored. The engine implements
     * inclusive VAT only, so an exclusive profile would be accepted here and then
     * fail every quote the brand takes — a configuration that looks saved and
     * breaks trading. Refusing at the point of authoring puts the error where the
     * operator can act on it.
     */
    @Transactional
    public TaxProfile setTaxProfile(
            UUID tenantId, UUID brandId, String jurisdictionCode, PricingEngine.TaxMode mode, int rateBasisPoints) {

        if (mode != PricingEngine.TaxMode.INCLUSIVE) {
            throw new PricingEngine.UnsupportedTaxModeException(mode);
        }
        if (rateBasisPoints < 0 || rateBasisPoints >= 10_000) {
            throw new IllegalArgumentException(
                    "A tax rate is basis points: 1200 is 12%, and 10000 or more is not a rate");
        }
        if (jurisdictionCode == null || jurisdictionCode.isBlank() || jurisdictionCode.length() > 16) {
            // The code arrives as a path segment, where bean validation does not
            // reach it. Left to the column it would come back as a conflict, which
            // is the wrong thing to tell a caller who simply sent nonsense.
            throw new IllegalArgumentException("A jurisdiction code is 1 to 16 characters");
        }

        UUID profileId = store.supersedeTaxProfile(
                        tenantId, brandId, jurisdictionCode, mode.name(), rateBasisPoints, clock.instant())
                .orElseThrow(() -> new OptimisticLockingFailureException("The tax profile changed since it was read"));

        log.info("Tax profile {} set to {} bp for brand {}", profileId, rateBasisPoints, brandId);
        return store.findTaxProfileHeader(tenantId, brandId, jurisdictionCode)
                .map(TaxProfile::of)
                .orElseThrow(() -> new IllegalStateException("Tax profile vanished mid-transaction"));
    }

    @Transactional(readOnly = true)
    public Optional<TaxProfile> taxProfile(UUID tenantId, UUID brandId, String jurisdictionCode) {
        return store.findTaxProfileHeader(tenantId, brandId, jurisdictionCode).map(TaxProfile::of);
    }

    /**
     * Refuses a write to a book nothing can ever read again.
     *
     * <p>An ACTIVE book is deliberately writable: changing a menu price is the
     * commonest thing an operator does, and forcing a new book for it would leave
     * a brand with a hundred books and no way to tell which one is current.
     */
    private static void requireAuthorable(PriceBook book) {
        if (book.status() == Status.ARCHIVED) {
            throw new PriceBookLifecycleException("An archived price book cannot be edited; draft a new one");
        }
    }

    public enum Status {
        DRAFT,
        ACTIVE,
        ARCHIVED
    }

    public enum AssignmentScope {
        BRAND,
        LOCATION,
        CHANNEL
    }

    /**
     * A draft book to create, before it has an id or a version.
     *
     * @param validFrom defaults to now when null
     * @param validUntil null is open-ended: the book's own window never closes on
     *                   its own
     * @param priority settles overlap deterministically, so row order and
     *                 wall-clock timing never decide a price
     */
    public record NewPriceBook(
            String name,
            String currency,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil,
            int priority) {}

    /**
     * Where and when a price book applies.
     *
     * @param validFrom defaults to now when null
     * @param validUntil null is open-ended: the assignment never closes on its own
     */
    public record Assignment(
            int priority,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil) {}

    public record PriceBook(
            UUID id,
            String name,
            String currency,
            Status status,
            Instant validFrom,
            @Nullable Instant validUntil,
            int priority,
            int version) {

        static PriceBook of(JdbcPricingStore.PriceBookHeader header) {
            return new PriceBook(
                    header.id(),
                    header.name(),
                    header.currency(),
                    Status.valueOf(header.status()),
                    header.validFrom(),
                    header.validUntil(),
                    header.priority(),
                    header.version());
        }
    }

    public record TaxProfile(
            UUID id,
            String jurisdictionCode,
            PricingEngine.TaxMode mode,
            int rateBasisPoints,
            Instant validFrom,
            int version) {

        static TaxProfile of(JdbcPricingStore.TaxProfileHeader header) {
            return new TaxProfile(
                    header.id(),
                    header.jurisdictionCode(),
                    PricingEngine.TaxMode.valueOf(header.mode()),
                    header.rateBasisPoints(),
                    header.validFrom(),
                    header.version());
        }
    }

    public static class UnknownPriceBookException extends RuntimeException {
        public UnknownPriceBookException(UUID priceBookId) {
            super("No price book " + priceBookId + " for this brand");
        }
    }

    /** Thrown rather than writing a price for something the brand does not sell. */
    public static class UnknownPriceableException extends RuntimeException {
        public UnknownPriceableException(PriceableType type, UUID priceableId) {
            super("No %s %s in this brand's catalog".formatted(type, priceableId));
        }
    }

    public static class UnknownAssignmentScopeException extends RuntimeException {
        public UnknownAssignmentScopeException(AssignmentScope scope, @Nullable UUID scopeId) {
            super("No %s %s for this tenant".formatted(scope, scopeId));
        }
    }

    /** Thrown when the book's own state refuses what is being asked of it. */
    public static class PriceBookLifecycleException extends RuntimeException {
        public PriceBookLifecycleException(String message) {
            super(message);
        }
    }
}
