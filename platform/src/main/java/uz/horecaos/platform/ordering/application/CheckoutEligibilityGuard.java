package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.api.MigrationOwnershipPort;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.application.CheckoutService.CheckoutCommand;
import uz.horecaos.platform.ordering.domain.CartStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;

/**
 * Step 2 of {@link CheckoutService}'s order of operations: locks and validates
 * the cart, the channel, serviceability, the publication and the quote — all
 * reads. Nothing here mutates, which is what lets {@link CheckoutService} run
 * every one of these checks before anything can be refused only by compensation.
 */
@Component
class CheckoutEligibilityGuard {

    /** The ADR 0027 purpose recorded against the one decrypt this transaction makes. */
    private static final String SNAPSHOT_PURPOSE = "ORDER_SNAPSHOT";

    private final JdbcCartStore carts;
    private final CartService cartService;
    private final SalesChannelLookup channels;
    private final ServiceabilityResolver serviceability;
    private final MigrationOwnershipPort migrationOwnership;
    private final PaymentIntentPort payments;
    private final OrderSettlementPort settlements;
    private final QuoteAcceptancePort quotes;
    private final OrderCatalogSnapshot catalog;

    CheckoutEligibilityGuard(
            JdbcCartStore carts,
            CartService cartService,
            SalesChannelLookup channels,
            ServiceabilityResolver serviceability,
            MigrationOwnershipPort migrationOwnership,
            PaymentIntentPort payments,
            OrderSettlementPort settlements,
            QuoteAcceptancePort quotes,
            OrderCatalogSnapshot catalog) {
        this.carts = carts;
        this.cartService = cartService;
        this.channels = channels;
        this.serviceability = serviceability;
        this.migrationOwnership = migrationOwnership;
        this.payments = payments;
        this.settlements = settlements;
        this.quotes = quotes;
        this.catalog = catalog;
    }

    /** Every fact a validated checkout needs downstream, gathered in one read-only pass. */
    record Eligible(
            CartRow cart,
            List<CartLineRow> cartLines,
            SalesChannel channel,
            Serviceability decision,
            Optional<CartService.CapturedDestination> destination,
            QuoteSnapshot quote) {}

    /**
     * @param eligible non-null when the checkout may proceed
     * @param rejectionCode non-null when it may not, naming why
     */
    record Result(Eligible eligible, String rejectionCode, String rejectionDetail) {

        boolean isEligible() {
            return eligible != null;
        }

        static Result eligible(Eligible eligible) {
            return new Result(eligible, null, null);
        }

        static Result rejected(String code, String detail) {
            return new Result(null, code, detail);
        }
    }

    Result check(CheckoutCommand command, Instant now) {
        CartRow cart = carts.findForUpdate(command.tenantId(), command.brandId(), command.cartId())
                .orElse(null);
        if (cart == null) {
            return Result.rejected("CART_NOT_FOUND", "No cart for this brand");
        }

        // ADR 0024's single-writer gate, at the one place it has to be: taking an
        // order is the capability whose ownership must be unambiguous during a
        // cutover. Placed here — after the cart is loaded, so the branch is known,
        // and before any validation settles an outcome — because the resolution
        // runs location, then brand, then tenant, and a scope that has cut over at
        // one branch while the rest of the brand has not is the whole point.
        //
        // Inside the checkout transaction, per the port's contract: a check that
        // committed separately from the write it authorises leaves a window a
        // concurrent cutover fits inside, and that window is where two writers
        // exist.
        requireOrdersAreOursToTake(command.tenantId(), command.brandId(), cart.locationId());

        if (cart.status() != CartStatus.ACTIVE) {
            // The common way to get here is the honest one: another checkout on the
            // same cart won, converted it, and this one arrived second.
            return Result.rejected("CART_NOT_ACTIVE", "This cart is " + cart.status());
        }
        if (cart.version() != command.expectedCartVersion()) {
            return Result.rejected(
                    "CART_VERSION_STALE", "The cart changed since version %d".formatted(command.expectedCartVersion()));
        }
        if (!cart.expiresAt().isAfter(now)) {
            return Result.rejected("CART_EXPIRED", "This cart has expired");
        }

        List<CartLineRow> cartLines = cartService.lines(command.tenantId(), command.cartId());
        if (cartLines.isEmpty()) {
            return Result.rejected("CART_EMPTY", "An empty cart cannot be checked out");
        }

        // The quote must be the one bound to this cart. A client naming any quote
        // id could otherwise name one priced for a different, cheaper basket.
        if (cart.pricingQuoteId() == null
                || !cart.pricingQuoteId().equals(command.quoteId())
                || !cart.pricingContextHash().equals(command.contextHash())) {
            return Result.rejected("QUOTE_NOT_BOUND_TO_CART", "This quote was not the one this cart was priced at");
        }

        Optional<SalesChannel> channel = channels.byId(command.tenantId(), cart.channelId());
        if (channel.isEmpty() || !channel.get().sellable()) {
            return Result.rejected("CHANNEL_NOT_SELLABLE", "The cart's channel no longer sells");
        }
        if (cart.customerAccountId() == null && !channel.get().guestOrdersAllowed()) {
            return Result.rejected("GUEST_ORDERS_NOT_ALLOWED", "This channel requires an account");
        }

        // ADR 0036 rule set, re-resolved from PostgreSQL inside this transaction
        // rather than from the browse cache. A branch that closed while the
        // customer was in the basket refuses here, which is the whole reason the
        // resolver is called twice.
        Serviceability decision = serviceability.resolve(
                command.tenantId(),
                command.brandId(),
                cart.locationId(),
                cart.channelId(),
                cart.fulfillmentMode(),
                now);
        if (!decision.available()) {
            return Result.rejected("NOT_SERVICEABLE", decision.reason().name());
        }

        // Where it is going, for an order that is going anywhere (ADR 0014, ADR
        // 0019). Refused here, among the read-only validations, and never later.
        //
        // Discovering a missing destination when a courier is being sourced means
        // the customer has paid, the kitchen has cooked, and the only remaining
        // moves are a refund or a telephone call — while refusing it here costs a
        // customer one screen. An order that cannot be delivered is not an order,
        // and this is the last moment at which saying so is cheap.
        Optional<CartService.CapturedDestination> destination = Optional.empty();
        if (cart.fulfillmentMode() == FulfillmentMode.DELIVERY) {
            destination = cartService.destination(command.tenantId(), cart.cartId(), SNAPSHOT_PURPOSE);
            if (destination.isEmpty()) {
                return Result.rejected("DELIVERY_DESTINATION_REQUIRED", "A delivery order must say where it is going");
            }
        }

        // Naming how the order will be paid is not optional (ADR 0046).
        //
        // It used to be, and the consequence was not an unpaid order — it was an
        // unrefundable one. Step 7b below is gated on the method being present, so
        // a checkout that omitted it created a real, confirmable, completable
        // order with no settlement, no tenders and no refund path, and every
        // remedy against it answered "the order has no settlement". The comment
        // that stood here said such a checkout "is not asking for one: that is
        // every offline order" — but no offline caller exists, every order this
        // service creates is a customer's, and an order a customer placed that
        // nobody can refund is not a lighter kind of order. If an order without a
        // money tender ever needs to exist, it needs a settlement shape of its own
        // decided in a reviewed change, not silence here.
        if (!namesAPaymentMethod(command)) {
            return Result.rejected(
                    "PAYMENT_METHOD_REQUIRED", "An order says how it will be paid, or it cannot be settled or refunded");
        }

        // ADR 0013's precondition, and the last read-only refusal. A method with no
        // merchant account behind it is refused here rather than at the payment
        // step, because the alternative is an order that has taken a kitchen slot
        // and a quote and can never be paid.
        if (!payments.canAcceptPayment(command.tenantId(), cart.locationId(), command.paymentMethodCode())) {
            return Result.rejected(
                    "PAYMENT_METHOD_UNAVAILABLE", "This location cannot take " + command.paymentMethodCode());
        }

        // ADR 0046's balance tender, refused here for the two reasons that need no
        // account, no policy and no balance to decide. The rest — the redemption
        // cap, the brand rule, whether the points are spendable yet — is decided
        // inside the reserving transaction by the module that owns the ledger, and
        // is deliberately not second-guessed here.
        if (command.redeemFromBalanceMinor() < 0) {
            return Result.rejected("REDEMPTION_INVALID", "A redemption settles a positive amount, or none");
        }
        if (command.redeemFromBalanceMinor() > 0 && cart.customerAccountId() == null) {
            return Result.rejected("GUEST_CANNOT_REDEEM", "A guest checkout has no account to redeem a balance from");
        }
        if (command.redeemFromBalanceMinor() > 0 && !settlements.isWired()) {
            // An assembly with no payments module plans no settlement, so a
            // redemption on it would be recorded on no tender while the customer's
            // intent was still for the whole total — the customer charged in full
            // for points they also spent. Refused here, among the read-only
            // validations, so such a build takes the order for money rather than
            // taking it wrongly.
            return Result.rejected(
                    "REDEMPTION_UNAVAILABLE", "This deployment cannot settle an order from a balance");
        }

        QuoteSnapshot quote =
                quotes.quoteSnapshot(command.tenantId(), command.quoteId()).orElse(null);
        if (quote == null) {
            return Result.rejected("QUOTE_NOT_FOUND", "No such quote for this tenant");
        }
        if (!quote.brandId().equals(command.brandId()) || !quote.locationId().equals(cart.locationId())) {
            return Result.rejected("QUOTE_SCOPE_MISMATCH", "This quote was priced for another brand or location");
        }
        if (quote.status() != QuoteSnapshot.Status.ACTIVE || !quote.expiresAt().isAfter(now)) {
            return Result.rejected("QUOTE_EXPIRED", "This quote has expired or was already accepted");
        }

        // "Points cannot cover the whole order", refused where refusing is still
        // cheap. ADR 0046 states it structurally — a settlement carries at least one
        // money tender — and the settlement would refuse this too; asking here as
        // well costs the customer one screen instead of a rolled-back checkout.
        //
        // >= and not >, and the difference is a som. Equality leaves a money leg of
        // zero, and there is no such thing as a provider intent for nothing, no
        // fiscal path for a zero-consideration sale, and nothing for a courier to
        // collect — so it is refused. One som less is an order like any other: the
        // money leg is one som, the intent is created for one som, and it is
        // collected. RedemptionLimit already caps every redemption at the total
        // less one som for the same reason, so this is the same boundary said twice
        // and never a different one.
        if (command.redeemFromBalanceMinor() >= quote.totalMinor()) {
            return Result.rejected("REDEMPTION_EXCEEDS_ORDER", "An order settles at least partly with money");
        }

        // The menu must still be the one the quote was priced against. A
        // republication changes what a dish is, and honouring the old price for the
        // new dish is wrong in both directions.
        Optional<UUID> livePublication = catalog.activePublicationId(
                command.tenantId(), command.brandId(), channel.get().code());
        if (livePublication.isEmpty() || !livePublication.get().equals(quote.catalogPublicationId())) {
            return Result.rejected("PUBLICATION_CHANGED", "The menu was republished since this cart was priced");
        }

        return Result.eligible(new Eligible(cart, cartLines, channel.get(), decision, destination, quote));
    }

    /**
     * Refuses the checkout when something else owns this branch's orders (ADR 0024).
     *
     * <h2>What "no scope registered" resolves to, and why</h2>
     *
     * <p>{@link MigrationOwnershipPort} fails closed on every unknown, and the
     * absence of a scope row is one of its unknowns: {@code
     * CapabilityOwnership.unmanaged} answers that legacy owns the capability and
     * the target may not write. Applied literally here, that would refuse every
     * checkout on this platform, because no tenant has a migration scope and most
     * never will.
     *
     * <p>The two principles genuinely conflict, and this is the seam they are
     * separated along. "Fails closed" is kept for every case where a scope row
     * exists: paused, blocked on reconciliation, rolling back, still a shadow, or
     * drifted into modes its state does not permit. In all of those somebody has
     * enrolled this capability in a migration and something has said the target is
     * not the writer, so a refusal is the honest answer and a write would be the
     * second authority ADR 0024 exists to prevent.
     *
     * <p>"Does not break an unmigrated platform" wins in exactly one case: no
     * scope row at all, which the exception reports as a null {@code scopeId}. The
     * gate is not saying that ownership is contested; it is saying the migration
     * has never reached this capability for this tenant. There is no legacy writer
     * to defer to, because there is no legacy program, and refusing would fence a
     * platform against a system that does not exist. The domain already draws this
     * distinction for us — a null {@code scopeId} is precisely what tells an
     * operator apart a scope that exists and has not started from no scope at all
     * — so this is reading a distinction the type makes, not inventing one.
     *
     * <p><strong>The cost, stated plainly.</strong> This resolution is fail-open
     * for a missing row, so a scope deleted by hand, or an ORDERS scope never
     * opened while the tenant's other capabilities were migrated, reads as
     * unmanaged and the target takes the order. That is a real hole and it is the
     * price of the gate being deployable at all; closing it means enrolling every
     * tenant in a scope row, at which point this branch stops being reachable and
     * can be deleted. Registering a scope is what arms the gate, and until one
     * exists there is nothing for it to be wrong about.
     *
     * <p>Deliberately not logged. On an unmigrated platform this branch is every
     * checkout, and a line per order is telemetry nobody reads hiding the ones
     * they would.
     */
    private void requireOrdersAreOursToTake(UUID tenantId, UUID brandId, UUID locationId) {
        // Asked as a question first, and the reason is not style. The branch
        // below used to be written as a try/catch around requireTargetMayWrite,
        // and it did not work: that method is
        // `@Transactional(propagation = SUPPORTS)`, so it joins *this*
        // transaction, and Spring's transaction interceptor marks the joined
        // transaction rollback-only on the way out with a RuntimeException.
        // Catching the exception here caught the exception and nothing else. The
        // order was written, this method returned, every later step ran, and the
        // commit then failed with `UnexpectedRollbackException: Transaction rolled
        // back because it has been marked as rollback-only`.
        //
        // Which made checkout impossible on exactly the platform this branch
        // exists to protect. An unmigrated tenant has no ORDERS scope row, so
        // every one of its checkouts took the swallow path, created an order,
        // logged it as CONFIRMED, and answered 500. `make run` against the local
        // fixture reproduced it on the first order ever placed.
        //
        // `ownershipOf` is the same resolution without the throw. When it finds
        // no scope there is nothing to lock and nothing to fence, so returning
        // here is the whole of the fail-open case and no exception is ever
        // created. When it does find one, requireTargetMayWrite runs for real —
        // it re-resolves under a row lock, and if it fences, rollback-only is
        // exactly what we want, because that write must not commit.
        //
        // The window between the two reads is the one the port's own javadoc
        // already documents for an unmanaged capability: nothing serialises a
        // scope opened concurrently, because there was no row to lock. This adds
        // no hole that the null-scope answer did not already have.
        if (migrationOwnership
                        .ownershipOf(tenantId, MigrationCapability.ORDERS, brandId, locationId)
                        .scopeId()
                == null) {
            return;
        }
        migrationOwnership.requireTargetMayWrite(tenantId, MigrationCapability.ORDERS, brandId, locationId);
    }

    private static boolean namesAPaymentMethod(CheckoutCommand command) {
        return command.paymentMethodCode() != null
                && !command.paymentMethodCode().isBlank();
    }
}
