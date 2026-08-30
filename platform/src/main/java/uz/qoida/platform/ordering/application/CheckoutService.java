package uz.qoida.platform.ordering.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.iam.api.protection.DataClass;
import uz.qoida.platform.iam.api.protection.FieldProtection;
import uz.qoida.platform.inventory.api.AvailabilityDecision;
import uz.qoida.platform.inventory.api.InventoryReservationPort;
import uz.qoida.platform.inventory.api.ReservationResult;
import uz.qoida.platform.migration.api.MigrationCapability;
import uz.qoida.platform.migration.api.MigrationOwnershipPort;
import uz.qoida.platform.ordering.api.OrderAwaitingApproval;
import uz.qoida.platform.ordering.api.OrderConfirmed;
import uz.qoida.platform.ordering.api.OrderReceived;
import uz.qoida.platform.ordering.api.OrderSettlementPort;
import uz.qoida.platform.ordering.api.PaymentIntentPort;
import uz.qoida.platform.ordering.domain.AcceptanceMode;
import uz.qoida.platform.ordering.domain.CartStatus;
import uz.qoida.platform.ordering.domain.OrderAcceptancePolicy;
import uz.qoida.platform.ordering.domain.OrderPromise;
import uz.qoida.platform.ordering.domain.OrderStateMachine;
import uz.qoida.platform.ordering.domain.OrderStatus;
import uz.qoida.platform.ordering.domain.TransitionTrigger;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcCheckoutAttemptStore;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.qoida.platform.pricing.api.QuoteAcceptance;
import uz.qoida.platform.pricing.api.QuoteAcceptancePort;
import uz.qoida.platform.pricing.api.QuoteSnapshot;
import uz.qoida.platform.tenancy.api.FulfillmentMode;
import uz.qoida.platform.tenancy.api.LocationCapacityPort;
import uz.qoida.platform.tenancy.api.SalesChannel;
import uz.qoida.platform.tenancy.api.SalesChannelLookup;
import uz.qoida.platform.tenancy.api.Serviceability;
import uz.qoida.platform.tenancy.api.ServiceabilityResolver;
import uz.qoida.platform.tenancy.api.TenantId;

/**
 * The checkout transaction (ADR 0019).
 *
 * <p>One database transaction calling only local module ports backed by the same
 * PostgreSQL instance. No provider, Kafka, Keycloak, POS or delivery call happens
 * inside it: those hold network latency inside a transaction that holds row
 * locks, and any partial failure leaves state nobody can reconstruct. Everything
 * external is a consequence of the events this transaction writes to the outbox.
 *
 * <h2>Order of operations, and why</h2>
 *
 * <p>ADR 0019 says "business rejection rolls back all steps". Rolling back would
 * also roll back the idempotency record, and a retry would then run the whole
 * sequence again against a cart that has since changed — so instead this method
 * does every read-only validation before it mutates anything, and compensates
 * explicitly for the two mutations that can precede a refusal. The effect the ADR
 * asks for is preserved: a refused checkout leaves no order, no accepted quote,
 * no committed reservation and no capacity slot.
 *
 * <ol>
 *   <li>claim the idempotency record, or return the settled result;</li>
 *   <li>lock and validate the cart, the channel, serviceability, the publication
 *       and the quote — all reads;</li>
 *   <li>hold inventory (idempotent per quote, compensated on refusal);</li>
 *   <li>claim a kitchen slot under the order id that is about to exist;</li>
 *   <li>accept the quote by context hash — the point of no return;</li>
 *   <li>create the order and its immutable snapshots;</li>
 *   <li>create the provider-neutral payment intent (ADR 0013), local rows only;</li>
 *   <li>advance the state machine, arm the approval timer, enqueue the inventory
 *       process, and write the outbox events;</li>
 *   <li>convert the cart and settle the idempotency record.</li>
 * </ol>
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** The one timer type this release arms. */
    public static final String APPROVAL_TIMER = "APPROVAL_DEADLINE";

    private static final String ORDER_LINE_TABLE = "ordering.order_lines";
    private static final String NOTE_COLUMN = "note_encrypted";

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";
    private static final String SNAPSHOT_NAME_COLUMN = "display_name_encrypted";
    private static final String SNAPSHOT_CONTACT_COLUMN = "contact_encrypted";
    private static final String SNAPSHOT_ADDRESS_COLUMN = "address_encrypted";
    private static final String SNAPSHOT_INSTRUCTIONS_COLUMN = "delivery_instructions_encrypted";

    /** The ADR 0027 purpose recorded against the one decrypt this transaction makes. */
    private static final String SNAPSHOT_PURPOSE = "ORDER_SNAPSHOT";

    private final JdbcCartStore carts;
    private final JdbcOrderStore orders;
    private final JdbcCheckoutAttemptStore attempts;
    private final CartService cartService;
    private final SalesChannelLookup channels;
    private final ServiceabilityResolver serviceability;
    private final LocationCapacityPort capacity;
    private final QuoteAcceptancePort quotes;
    private final InventoryReservationPort inventory;
    private final OrderCatalogSnapshot catalog;
    private final OrderingTenantContext tenancy;
    private final OrderAcceptancePolicyService acceptancePolicies;
    private final OrderInventoryProcess inventoryProcess;
    private final MigrationOwnershipPort migrationOwnership;
    private final PaymentIntentPort payments;
    private final OrderSettlementPort settlements;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CheckoutService(JdbcCartStore carts, JdbcOrderStore orders,
            JdbcCheckoutAttemptStore attempts, CartService cartService,
            SalesChannelLookup channels, ServiceabilityResolver serviceability,
            LocationCapacityPort capacity, QuoteAcceptancePort quotes,
            InventoryReservationPort inventory, OrderCatalogSnapshot catalog,
            OrderingTenantContext tenancy, OrderAcceptancePolicyService acceptancePolicies,
            OrderInventoryProcess inventoryProcess, MigrationOwnershipPort migrationOwnership,
            PaymentIntentPort payments, OrderSettlementPort settlements,
            FieldProtection protection,
            ObjectMapper objectMapper, ApplicationEventPublisher events, Clock clock) {
        this.carts = carts;
        this.orders = orders;
        this.attempts = attempts;
        this.cartService = cartService;
        this.channels = channels;
        this.serviceability = serviceability;
        this.capacity = capacity;
        this.quotes = quotes;
        this.inventory = inventory;
        this.catalog = catalog;
        this.tenancy = tenancy;
        this.acceptancePolicies = acceptancePolicies;
        this.inventoryProcess = inventoryProcess;
        this.migrationOwnership = migrationOwnership;
        this.payments = payments;
        this.settlements = settlements;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public CheckoutResult checkout(CheckoutCommand command) {
        Instant now = clock.instant();
        String fingerprint = command.fingerprint();

        // 1. The idempotency record. The insert is attempted first so two
        // concurrent checkouts with one key contend on the unique index; the loser
        // blocks here until the winner commits and then reads its result, rather
        // than running the whole sequence in parallel.
        UUID attemptId = UUID.randomUUID();
        if (!attempts.claim(attemptId, command.tenantId(), command.idempotencyKey(),
                command.cartId(), command.quoteId(), fingerprint, now)) {

            var existing = attempts.findForUpdate(command.tenantId(), command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("Attempt vanished mid-transaction"));

            if (!existing.requestFingerprint().equals(fingerprint)) {
                // ADR 0019 rejected deriving the key from a request hash, because
                // two legitimately different carts can normalise to the same hash.
                // This is the opposite check: one client reusing a key for a
                // different request, which is a client bug and never a retry.
                return CheckoutResult.rejected("IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different checkout",
                        warnings());
            }
            if ("COMPLETED".equals(existing.status())) {
                return existing.orderId() == null
                        ? CheckoutResult.rejected(existing.outcomeCode(), existing.outcomeDetail(),
                                warnings())
                        : replayOf(command.tenantId(), existing.orderId());
            }
            // IN_PROGRESS with the row lock held elsewhere is impossible to
            // observe: the FOR UPDATE above would have blocked. Seeing it means
            // the same transaction is re-entering, which is a programming error.
            throw new IllegalStateException(
                    "Checkout attempt " + existing.attemptId() + " is already in progress");
        }

        // 2. Every validation, before anything is written.
        CartRow cart = carts.findForUpdate(command.tenantId(), command.brandId(), command.cartId())
                .orElse(null);
        if (cart == null) {
            return settle(attemptId, null, "CART_NOT_FOUND", "No cart for this brand", now);
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
            return settle(attemptId, null, "CART_NOT_ACTIVE", "This cart is " + cart.status(), now);
        }
        if (cart.version() != command.expectedCartVersion()) {
            return settle(attemptId, null, "CART_VERSION_STALE",
                    "The cart changed since version %d".formatted(command.expectedCartVersion()), now);
        }
        if (!cart.expiresAt().isAfter(now)) {
            return settle(attemptId, null, "CART_EXPIRED", "This cart has expired", now);
        }

        List<CartLineRow> cartLines = cartService.lines(command.tenantId(), command.cartId());
        if (cartLines.isEmpty()) {
            return settle(attemptId, null, "CART_EMPTY", "An empty cart cannot be checked out", now);
        }

        // The quote must be the one bound to this cart. A client naming any quote
        // id could otherwise name one priced for a different, cheaper basket.
        if (cart.pricingQuoteId() == null || !cart.pricingQuoteId().equals(command.quoteId())
                || !cart.pricingContextHash().equals(command.contextHash())) {
            return settle(attemptId, null, "QUOTE_NOT_BOUND_TO_CART",
                    "This quote was not the one this cart was priced at", now);
        }

        Optional<SalesChannel> channel = channels.byId(command.tenantId(), cart.channelId());
        if (channel.isEmpty() || !channel.get().sellable()) {
            return settle(attemptId, null, "CHANNEL_NOT_SELLABLE",
                    "The cart's channel no longer sells", now);
        }
        if (cart.customerAccountId() == null && !channel.get().guestOrdersAllowed()) {
            return settle(attemptId, null, "GUEST_ORDERS_NOT_ALLOWED",
                    "This channel requires an account", now);
        }

        // ADR 0036 rule set, re-resolved from PostgreSQL inside this transaction
        // rather than from the browse cache. A branch that closed while the
        // customer was in the basket refuses here, which is the whole reason the
        // resolver is called twice.
        Serviceability decision = serviceability.resolve(command.tenantId(), command.brandId(),
                cart.locationId(), cart.channelId(), cart.fulfillmentMode(), now);
        if (!decision.available()) {
            return settle(attemptId, null, "NOT_SERVICEABLE", decision.reason().name(), now);
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
            destination = cartService.destination(command.tenantId(), cart.cartId(),
                    SNAPSHOT_PURPOSE);
            if (destination.isEmpty()) {
                return settle(attemptId, null, "DELIVERY_DESTINATION_REQUIRED",
                        "A delivery order must say where it is going", now);
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
            return settle(attemptId, null, "PAYMENT_METHOD_REQUIRED",
                    "An order says how it will be paid, or it cannot be settled or refunded", now);
        }

        // ADR 0013's precondition, and the last read-only refusal. A method with no
        // merchant account behind it is refused here rather than at the payment
        // step, because the alternative is an order that has taken a kitchen slot
        // and a quote and can never be paid.
        if (!payments.canAcceptPayment(command.tenantId(),
                cart.locationId(), command.paymentMethodCode())) {
            return settle(attemptId, null, "PAYMENT_METHOD_UNAVAILABLE",
                    "This location cannot take " + command.paymentMethodCode(), now);
        }

        // ADR 0046's balance tender, refused here for the two reasons that need no
        // account, no policy and no balance to decide. The rest — the redemption
        // cap, the brand rule, whether the points are spendable yet — is decided
        // inside the reserving transaction by the module that owns the ledger, and
        // is deliberately not second-guessed here.
        if (command.redeemFromBalanceMinor() < 0) {
            return settle(attemptId, null, "REDEMPTION_INVALID",
                    "A redemption settles a positive amount, or none", now);
        }
        if (command.redeemFromBalanceMinor() > 0 && cart.customerAccountId() == null) {
            return settle(attemptId, null, "GUEST_CANNOT_REDEEM",
                    "A guest checkout has no account to redeem a balance from", now);
        }
        if (command.redeemFromBalanceMinor() > 0 && !settlements.isWired()) {
            // An assembly with no payments module plans no settlement, so a
            // redemption on it would be recorded on no tender while the customer's
            // intent was still for the whole total — the customer charged in full
            // for points they also spent. Refused here, among the read-only
            // validations, so such a build takes the order for money rather than
            // taking it wrongly.
            return settle(attemptId, null, "REDEMPTION_UNAVAILABLE",
                    "This deployment cannot settle an order from a balance", now);
        }

        QuoteSnapshot quote = quotes.quoteSnapshot(command.tenantId(), command.quoteId())
                .orElse(null);
        if (quote == null) {
            return settle(attemptId, null, "QUOTE_NOT_FOUND", "No such quote for this tenant", now);
        }
        if (!quote.brandId().equals(command.brandId())
                || !quote.locationId().equals(cart.locationId())) {
            return settle(attemptId, null, "QUOTE_SCOPE_MISMATCH",
                    "This quote was priced for another brand or location", now);
        }
        if (quote.status() != QuoteSnapshot.Status.ACTIVE || !quote.expiresAt().isAfter(now)) {
            return settle(attemptId, null, "QUOTE_EXPIRED",
                    "This quote has expired or was already accepted", now);
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
            return settle(attemptId, null, "REDEMPTION_EXCEEDS_ORDER",
                    "An order settles at least partly with money", now);
        }

        // The menu must still be the one the quote was priced against. A
        // republication changes what a dish is, and honouring the old price for the
        // new dish is wrong in both directions.
        Optional<UUID> livePublication =
                catalog.activePublicationId(command.tenantId(), command.brandId(), channel.get().code());
        if (livePublication.isEmpty()
                || !livePublication.get().equals(quote.catalogPublicationId())) {
            return settle(attemptId, null, "PUBLICATION_CHANGED",
                    "The menu was republished since this cart was priced", now);
        }

        // 3. Hold the stock. Idempotent per quote, and refused rather than
        // silently reused when the earlier hold has lapsed.
        Map<UUID, Integer> quantities = quantitiesOf(quote);
        ReservationResult reservation = inventory.reserveForQuote(command.tenantId(),
                command.brandId(), cart.locationId(), command.quoteId(), quantities);
        if (!reservation.isHeld()) {
            return settleUnavailable(attemptId, reservation.refusal(), now);
        }

        // 4. The kitchen slot, claimed under the id the order is about to take, so
        // a retry re-claims its own rather than consuming a second.
        UUID orderId = UUID.randomUUID();
        if (capacity.claimCapacity(command.tenantId(), command.brandId(), cart.locationId(),
                orderId) == LocationCapacityPort.CapacityOutcome.AT_CAPACITY) {
            inventory.release(command.tenantId(), command.quoteId());
            return settle(attemptId, null, "AT_CAPACITY",
                    "The kitchen is at its concurrent-order limit", now);
        }

        // 5. The point of no return. One conditional update decides which of two
        // concurrent checkouts owns this quote.
        QuoteAcceptance acceptance = quotes.acceptQuote(command.tenantId(), command.quoteId(),
                command.contextHash());
        if (!acceptance.isAccepted()) {
            capacity.releaseCapacity(command.tenantId(), orderId);
            inventory.release(command.tenantId(), command.quoteId());
            return settle(attemptId, null,
                    acceptance.outcome() == QuoteAcceptance.Outcome.PRICE_CHANGED
                            ? "PRICE_CHANGED" : "QUOTE_EXPIRED",
                    "The price changed or the quote lapsed; request a new quote", now);
        }

        // 6. The order, and everything it must remember for ever.
        var policy = acceptancePolicies.resolve(command.tenantId(), command.brandId(),
                cart.locationId());
        boolean approvalRequired = policy.policy().mode() == AcceptanceMode.RESTAURANT_APPROVAL;
        Instant approvalDeadline = approvalRequired
                ? now.plus(policy.policy().approvalTimeout()) : null;

        String publicNumber = allocateNumber(command.tenantId(), cart.locationId(), now);
        OrderPromise promise = promise(command, cart, quantities.keySet(), decision, now);

        // Asked before the row is written because the answer decides the payment
        // projection that goes on it. Asked of payments rather than decided here:
        // the answer is ADR 0013's capture timing for the channel's method, and
        // ordering owning a copy of that table is how the two drift apart.
        boolean paymentFirst = payments.paymentRequiredBeforeConfirmation(
                command.tenantId(), orderId, command.paymentMethodCode());

        orders.insertOrder(new JdbcOrderStore.NewOrder(
                orderId, publicNumber, command.tenantId(), command.brandId(), cart.locationId(),
                cart.channelId(), channel.get().code(), cart.customerAccountId(),
                cart.guestReferenceHash(), cart.fulfillmentMode(),
                policy.policy().mode().name(), policy.policyId(), policy.policyVersion(),
                policy.policy().approvalChannel().name(),
                approvalRequired ? policy.policy().timeoutAction().name() : null,
                approvalDeadline, OrderStatus.RECEIVED,
                paymentProjection(paymentFirst), "PENDING", quote.currency(),
                quote.subtotalMinor(), quote.taxMinor(), quote.discountMinor(),
                quote.feeMinor(), quote.totalMinor(), quote.quoteId(), quote.contextHash(),
                quote.catalogPublicationId(), cart.cartId(), command.idempotencyKey(),
                promise, command.actorType(), command.actorId(), now));

        // ADR 0039 revision 1: the ADR 0019 checkout snapshot, written before the
        // lines that belong to it. A report pinned here must still reconcile to
        // the original total after ten amendments, which it can only do if the
        // five figures were copied at the moment they were agreed rather than
        // recomputed later from an order row somebody has since amended.
        orders.insertRevision(new JdbcOrderStore.NewRevision(orderId, 1, command.tenantId(),
                "CHECKOUT", null, quote.quoteId(), quote.contextHash(), quote.currency(),
                quote.subtotalMinor(), quote.taxMinor(), quote.discountMinor(), quote.feeMinor(),
                quote.totalMinor(), 0L, command.actorType(), command.actorId(), now));

        writeSnapshots(command, cart, cartLines, quote, orderId, destination);

        orders.recordTransition(command.tenantId(), orderId, 1, null, OrderStatus.RECEIVED,
                TransitionTrigger.CHECKOUT, null, command.actorType(), command.actorId(),
                command.correlationId(), now);

        events.publishEvent(new OrderReceived(UUID.randomUUID(), new TenantId(command.tenantId()),
                orderId, now, command.brandId(), cart.locationId(), channel.get().code(),
                publicNumber, cart.fulfillmentMode().name(), policy.policy().mode().name(),
                policy.policyId(), policy.policyVersion(), OrderStatus.RECEIVED.name(), 1,
                quote.currency(), quote.totalMinor(), quote.lines().size()));

        // 7a. The settlement that will discharge this order (ADR 0046), and with
        // it the only figure that says what is actually to be collected.
        //
        // Here rather than on the confirmation, for three reasons that all point
        // the same way. The tender composition is decided at checkout and nowhere
        // else — the method the customer chose and how much of it they asked to
        // take from their balance are on this command and are on no event. The
        // plan has to exist before a provider is called, and for a
        // BEFORE_CONFIRMATION method the provider is called between this
        // transaction and the confirmation, so a plan taken at confirmation would
        // be taken after the money. And the balance hold belongs to a transaction
        // that can still roll back: taken here it is released by the same rollback
        // that undoes the order, while a hold taken on a confirmation would have to
        // be compensated by hand.
        //
        // Before the intent rather than after it, which is the ordering this
        // change exists for. It used to run thirty lines later, and because it did,
        // the intent had nothing to be told and computed its own amount from the
        // quote total — so a customer who paid 12 000 of a 94 000 order from points
        // was asked by Click for 94 000 while the settlement recorded an 82 000
        // money leg. Both steps write local rows in this one transaction and
        // neither calls anything external, so their order is free to choose; the
        // points hold this takes is released by the same rollback that would undo
        // the intent, exactly as it was when it ran second.
        //
        // Ungated. The method is a precondition checked among the read-only
        // refusals above, so by here it is present and this cannot be the branch
        // that quietly did not run.
        Optional<OrderSettlementPort.PlannedSettlement> settlement =
                settlements.planSettlement(new OrderSettlementPort.SettlementRequest(
                        command.tenantId(), command.brandId(), orderId, cart.customerAccountId(),
                        quote.currency(), quote.totalMinor(), command.paymentMethodCode(),
                        command.redeemFromBalanceMinor(), command.idempotencyKey(),
                        command.actorId()));

        // 7b. The provider-neutral payment intent (ADR 0019 step 7), for what the
        // provider is meant to collect. Local rows only — the order row it refers
        // to now exists, which is why this is here and not before the insert, and
        // no provider is called from inside this transaction.
        UUID intentId = payments.createIntent(command.tenantId(), orderId,
                amountDueMinor(orderId, command, quote, settlement),
                quote.currency(), command.paymentMethodCode(), command.idempotencyKey());
        if (paymentFirst && intentId == null) {
            // An order that may not be confirmed until it is paid, and nothing to
            // pay against. Failing the transaction is the only answer that leaves
            // no trace: committing would create an order permanently in
            // PAYMENT_AUTHORIZING, and confirming it would put an unpaid order in
            // front of a kitchen.
            throw new IllegalStateException("Order " + orderId + " requires payment before "
                    + "confirmation but no payment intent was created");
        }

        // 8. Advance. Payment intervenes first when ADR 0013's capture timing says
        // the money must arrive before the restaurant is asked: the order waits in
        // PAYMENT_AUTHORIZING rather than reaching a kitchen unpaid. Cash and every
        // method with no online payment take the acceptance-policy path unchanged.
        //
        // The attempt is opened and the checkout surface presented by ADR 0013's
        // own endpoint, POST /orders/{orderId}/payment-sessions, which the client
        // calls once this returns PAYMENT_AUTHORIZING. Deliberately not from here:
        // ADR 0019 keeps every external call out of the checkout transaction, and
        // ADR 0013 agrees for its own reason — a Click invoice pushed inside a
        // transaction that then rolls back is a payment request on a customer's
        // phone that no row remembers, and Click's MERCHANT API has no idempotency
        // key anywhere to recover from it. The order is durable and its intent
        // committed before anything reaches a provider, so a client that never
        // calls the endpoint leaves an unpresented order rather than an unrecorded
        // charge, and a client that calls it twice is re-presented the same attempt.
        OrderStatus finalStatus;
        if (paymentFirst) {
            finalStatus = awaitPayment(command, orderId, now);
        } else if (approvalRequired) {
            finalStatus = awaitApproval(command, cart, orderId, policy.policy(), approvalDeadline,
                    now);
        } else {
            finalStatus = confirmImmediately(command, cart, orderId, policy.policy(), quote, now);
        }

        // 9. Convert the cart and settle the record.
        if (!carts.transition(command.tenantId(), cart.cartId(), CartStatus.ACTIVE,
                CartStatus.CONVERTED, orderId, now)) {
            // Unreachable while the cart row lock is held, and worth failing on
            // rather than committing an order whose cart is still orderable.
            throw new IllegalStateException("Cart " + cart.cartId() + " changed during checkout");
        }
        attempts.complete(attemptId, orderId, "CREATED", null, now);

        log.info("Order {} ({}) created at location {} in state {}", orderId, publicNumber,
                cart.locationId(), finalStatus);

        return new CheckoutResult(CheckoutResult.Outcome.CREATED, orderId, publicNumber,
                finalStatus, orderVersion(command.tenantId(), orderId), null, null,
                List.of(), warnings());
    }

    // ------------------------------------------------------------------ steps

    /**
     * What the payment intent is created for: the order's money leg, never its
     * total.
     *
     * <p>The settlement is the authority and this only reads it. Nothing here
     * subtracts the redemption from the total, and that is deliberate — the intent
     * and the money tender were two independent derivations of one number, so on
     * every split-tender order the provider was asked for the whole total while the
     * settlement recorded {@code total - redeemed}. The customer paid the full
     * price and spent their points for the same food, and the surplus sat on no
     * tender, which put it beyond the refund path as well: {@code refund} is
     * bounded by {@code refundableMinor()} per tender and no tender knew about it.
     *
     * <p>The remaining branch is the assembly with no payments module (ADR 0046's
     * {@code OrderSettlementPort} stand-in), which plans nothing. Such a build
     * cannot redeem at all — refused among the read-only validations above — so
     * nothing was taken from a balance and the order total <em>is</em> the money
     * leg. The check below is the guard that keeps that sentence true rather than
     * assumed: if it ever stops being true, the checkout fails and rolls back
     * instead of quietly overcharging.
     */
    private long amountDueMinor(UUID orderId, CheckoutCommand command, QuoteSnapshot quote,
            Optional<OrderSettlementPort.PlannedSettlement> settlement) {

        if (settlement.isPresent()) {
            return settlement.get().moneyDueMinor();
        }
        if (command.redeemFromBalanceMinor() > 0) {
            throw new IllegalStateException("Order " + orderId + " redeems from a balance but "
                    + "planned no settlement, so no tender records the redemption and the amount "
                    + "due cannot be established");
        }
        return quote.totalMinor();
    }

    /**
     * Holds the order until the money arrives (ADR 0013's {@code
     * BEFORE_CONFIRMATION} capture timing).
     *
     * <p>No approval timer is armed here even for a restaurant-approval location.
     * The approval clock measures how long the restaurant took to answer, and a
     * restaurant that has not been asked yet is not late; the timer is armed on
     * the {@code PAYMENT_AUTHORIZING -> AWAITING_APPROVAL} transition the payment
     * produces, not on this one.
     *
     * <p>No event is published either. {@code OrderReceived} has already been
     * published by the caller and is what a consumer sees; the ordering events
     * this release has all describe a commercial decision — accepted, rejected,
     * confirmed — and "waiting on a card" is not one of them.
     */
    private OrderStatus awaitPayment(CheckoutCommand command, UUID orderId, Instant now) {

        OrderStateMachine.require(OrderStatus.RECEIVED, OrderStatus.PAYMENT_AUTHORIZING);
        int version = orders.transition(command.tenantId(), orderId, OrderStatus.RECEIVED,
                        OrderStatus.PAYMENT_AUTHORIZING, now)
                .orElseThrow(() -> new IllegalStateException("Order changed during checkout"));

        orders.recordTransition(command.tenantId(), orderId, version, OrderStatus.RECEIVED,
                OrderStatus.PAYMENT_AUTHORIZING, TransitionTrigger.CHECKOUT, null,
                command.actorType(), command.actorId(), command.correlationId(), now);

        return OrderStatus.PAYMENT_AUTHORIZING;
    }

    private OrderStatus awaitApproval(CheckoutCommand command, CartRow cart, UUID orderId,
            OrderAcceptancePolicy policy, Instant deadline, Instant now) {

        OrderStateMachine.require(OrderStatus.RECEIVED, OrderStatus.AWAITING_APPROVAL);
        int version = orders.transition(command.tenantId(), orderId, OrderStatus.RECEIVED,
                        OrderStatus.AWAITING_APPROVAL, now)
                .orElseThrow(() -> new IllegalStateException("Order changed during checkout"));

        orders.recordTransition(command.tenantId(), orderId, version, OrderStatus.RECEIVED,
                OrderStatus.AWAITING_APPROVAL, TransitionTrigger.CHECKOUT, null,
                command.actorType(), command.actorId(), command.correlationId(), now);

        // A durable timer, not an in-memory scheduler. A restart must not lose the
        // deadline and leave the order waiting for an approval nobody will ever be
        // asked for.
        orders.insertTimer(command.tenantId(), orderId, APPROVAL_TIMER, deadline);

        events.publishEvent(new OrderAwaitingApproval(UUID.randomUUID(),
                new TenantId(command.tenantId()), orderId, now, command.brandId(),
                cart.locationId(), policy.approvalChannel().name(), deadline,
                policy.timeoutAction().name(), OrderStatus.AWAITING_APPROVAL.name(), version));

        return OrderStatus.AWAITING_APPROVAL;
    }

    private OrderStatus confirmImmediately(CheckoutCommand command, CartRow cart, UUID orderId,
            OrderAcceptancePolicy policy, QuoteSnapshot quote, Instant now) {

        // ADR 0039: an auto-confirmed order was accepted by the platform's own
        // rule, and the attribution says so. Leaving it empty would make the
        // operations board show a blank "Принял" cell on every auto-confirm, which
        // is what trained legacy staff to ignore the field.
        OrderStateMachine.require(OrderStatus.RECEIVED, OrderStatus.CONFIRMED);
        int version = orders.transition(command.tenantId(), orderId, OrderStatus.RECEIVED,
                        OrderStatus.CONFIRMED, now, "SYSTEM_JOB", "order-acceptance-policy")
                .orElseThrow(() -> new IllegalStateException("Order changed during checkout"));

        orders.recordTransition(command.tenantId(), orderId, version, OrderStatus.RECEIVED,
                OrderStatus.CONFIRMED, TransitionTrigger.CHECKOUT, null,
                command.actorType(), command.actorId(), command.correlationId(), now);

        // The inventory process manager, not an inline commit. The commit is a
        // consequence of confirmation and is retried on its own if it fails,
        // rather than failing the checkout that had already succeeded.
        inventoryProcess.enqueueCommit(orderId, command.tenantId(), quote.quoteId(), now);

        events.publishEvent(new OrderConfirmed(UUID.randomUUID(),
                new TenantId(command.tenantId()), orderId, now, command.brandId(),
                cart.locationId(), policy.mode().name(), null, now, quote.currency(),
                quote.totalMinor(), OrderStatus.CONFIRMED.name(), version));

        return OrderStatus.CONFIRMED;
    }

    /**
     * Copies the priced result onto the order.
     *
     * <p>Names come from the catalog and amounts from the quote, and both are
     * copied rather than referenced. That is the whole mechanism behind "an
     * order's commercial snapshot survives a menu republish": there is no join
     * from an order line back to a catalog row for a republish to change.
     */
    private void writeSnapshots(CheckoutCommand command, CartRow cart, List<CartLineRow> cartLines,
            QuoteSnapshot quote, UUID orderId,
            Optional<CartService.CapturedDestination> destination) {

        Map<String, CartLineRow> cartLinesByKey = cartLines.stream()
                .collect(Collectors.toMap(CartLineRow::lineKey, line -> line, (a, b) -> a,
                        LinkedHashMap::new));

        Set<UUID> variantIds = quote.lines().stream()
                .map(QuoteSnapshot.Line::variantId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> optionIds = cartLines.stream()
                .flatMap(line -> cartService.modifierIdsOf(line).stream())
                .collect(Collectors.toUnmodifiableSet());

        Map<UUID, OrderCatalogSnapshot.VariantDescriptor> variants =
                catalog.variants(command.tenantId(), command.brandId(), variantIds);
        Map<UUID, OrderCatalogSnapshot.ModifierDescriptor> options =
                catalog.modifierOptions(command.tenantId(), command.brandId(), optionIds);

        Map<String, UUID> orderLineIdsByKey = new HashMap<>();
        int lineNumber = 0;

        for (QuoteSnapshot.Line line : quote.lines()) {
            lineNumber++;
            CartLineRow cartLine = cartLinesByKey.get(line.lineKey());
            var descriptor = variants.get(line.variantId());
            UUID orderLineId = UUID.randomUUID();

            orders.insertLine(orderLineId, command.tenantId(), orderId, lineNumber,
                    descriptor == null ? null : descriptor.productId(), line.variantId(),
                    // The quote's description is the name the customer was shown at
                    // the moment they were shown the price; the catalog lookup is
                    // only a fallback for a variant the quote could not describe.
                    line.descriptionSnapshot(),
                    descriptor == null ? null : descriptor.variantName(),
                    descriptor == null ? null : descriptor.sku(),
                    line.quantity(), line.unitAmountMinor(), line.baseAmountMinor(),
                    line.finalAmountMinor(), line.taxAmountMinor(),
                    reEncryptNote(command.tenantId(), cartLine, orderLineId));

            orderLineIdsByKey.put(line.lineKey(), orderLineId);

            if (cartLine == null) {
                continue;
            }
            for (UUID optionId : cartService.modifierIdsOf(cartLine)) {
                var option = options.get(optionId);
                orders.insertLineModifier(command.tenantId(), orderLineId,
                        option == null ? null : option.groupId(), optionId,
                        option == null ? null : option.groupName(),
                        option == null ? optionId.toString() : option.optionName(),
                        1, 0L, 0L);
            }
        }

        for (QuoteSnapshot.Adjustment adjustment : quote.adjustments()) {
            orders.insertAdjustment(command.tenantId(), orderId, adjustment.sequence(),
                    adjustment.lineKey() == null ? null : orderLineIdsByKey.get(adjustment.lineKey()),
                    adjustment.adjustmentType(), adjustment.sourceType(), adjustment.sourceId(),
                    adjustment.sourceVersion(), adjustment.descriptionCode(),
                    adjustment.amountMinor());
        }

        // The customer as they were, and the address as it was.
        //
        // A snapshot rather than a reference, which is the whole point of this
        // table: a customer who corrects their address next month must not
        // retroactively change where last month's order went, and an address they
        // archive must not make a delivered order unexplainable. There is
        // deliberately no join from here back to customer.addresses for either to
        // travel down.
        //
        // Every value is decrypted from the cart and re-encrypted under this
        // order's own ADR 0029 associated data rather than copied as ciphertext.
        // Copying would produce columns that no key can ever open again, because
        // the binding names the row.
        //
        // A collected order still gets its row, with the address columns null. A
        // null address and an empty address read differently to anyone
        // reconstructing a delivery, and only one of them means "nobody was ever
        // going to drive anywhere".
        orders.insertCustomerSnapshot(command.tenantId(), orderId,
                destination.map(captured -> protect(command.tenantId(), orderId,
                        SNAPSHOT_NAME_COLUMN, captured.recipientName())).orElse(null),
                destination.map(captured -> protect(command.tenantId(), orderId,
                        SNAPSHOT_CONTACT_COLUMN, captured.recipientPhone())).orElse(null),
                destination.map(captured -> protect(command.tenantId(), orderId,
                        SNAPSHOT_ADDRESS_COLUMN,
                        destinationDocument(captured))).orElse(null),
                destination.map(captured -> protect(command.tenantId(), orderId,
                        SNAPSHOT_INSTRUCTIONS_COLUMN, captured.deliveryNote())).orElse(null),
                true);

        if (cart.fulfillmentMode() != null) {
            log.debug("Snapshotted {} lines onto order {}", lineNumber, orderId);
        }
    }

    /**
     * The destination document, coordinate and all.
     *
     * <p>The point travels inside the ciphertext rather than beside it in a clear
     * column, and that is the one place the order's copy differs from the cart's.
     * A cart lives four hours; an order lives for years and is crypto-shredded, and
     * a clear coordinate would survive the shred still pointing at the building
     * somebody lived in. Inside the document it dies with the key.
     */
    private String destinationDocument(CartService.CapturedDestination captured) {
        return objectMapper.writeValueAsString(captured.destination());
    }

    private String protect(UUID tenantId, UUID orderId, String column, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return protection.protect(tenantId, DataClass.PERSONAL,
                        new FieldProtection.RecordRef(SNAPSHOT_TABLE, column, orderId), plaintext)
                .serialize();
    }

    /**
     * Carries the customer's note onto the order line.
     *
     * <p>Decrypted and re-encrypted rather than copied. ADR 0029 binds a
     * ciphertext to its row through the associated data, precisely so that a value
     * moved elsewhere fails to decrypt rather than silently revealing the wrong
     * person's words; copying the cart's ciphertext into an order line would
     * produce a note nobody could ever read again.
     */
    private String reEncryptNote(UUID tenantId, CartLineRow cartLine, UUID orderLineId) {
        if (cartLine == null || cartLine.customerNoteEncrypted() == null) {
            return null;
        }
        String note = cartService.revealNote(tenantId, cartLine, "ORDER_SNAPSHOT");
        return protection.protect(tenantId, DataClass.PERSONAL,
                        new FieldProtection.RecordRef(ORDER_LINE_TABLE, NOTE_COLUMN, orderLineId),
                        note)
                .serialize();
    }

    // ------------------------------------------------------------- helpers

    /**
     * The human-facing number: the branch's local date and a counter that resets
     * with it.
     *
     * <p>Local rather than UTC, because a UTC date rolls over at 05:00 in Tashkent
     * and would split one evening's service across two numbering days.
     */
    /**
     * The promised time, decided here and never again (ADR 0036).
     *
     * <p>Computed from the same {@link Serviceability} decision that let this
     * order through, so the band that said the branch was open is the band that
     * quotes it. Re-resolving would open a window in which a merchant editing
     * bands mid-checkout gets an order quoted from a band that never applied.
     *
     * <p>The clock starts now, at checkout, and not at confirmation — including
     * for an order that will sit in {@code AWAITING_APPROVAL}. That is deliberate,
     * and it is the reason lateness is worth showing at all: the customer was told
     * a time at checkout, and a restaurant that takes eleven minutes to accept has
     * spent eleven minutes of it. Restarting the clock on acceptance would hide
     * exactly the delay a manager needs to see, and would make the platform's
     * promise a function of how slow the branch was.
     */
    private OrderPromise promise(CheckoutCommand command, CartRow cart, Set<UUID> variantIds,
            Serviceability decision, Instant now) {

        Duration slowestItem = catalog.longestPreparationOverride(command.tenantId(),
                command.brandId(), cart.locationId(), variantIds).orElse(null);

        // Travel is null rather than zero. ADR 0037's zone model is not built, so
        // a delivery promise today covers the kitchen and nothing else; recording
        // that as a zero would claim the road takes no time, and the backfill that
        // eventually corrects these orders could not find them.
        return OrderPromise.assemble(now, decision.preparationMinutes(), slowestItem, null);
    }

    private String allocateNumber(UUID tenantId, UUID locationId, Instant now) {
        ZoneId zone = tenancy.timezoneOf(tenantId, locationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Location " + locationId + " has no timezone"));
        LocalDate businessDate = now.atZone(zone).toLocalDate();
        int sequence = orders.nextOrderNumber(tenantId, locationId, businessDate);
        return "%02d%02d-%03d".formatted(
                businessDate.getMonthValue(), businessDate.getDayOfMonth(), sequence);
    }

    private Map<UUID, Integer> quantitiesOf(QuoteSnapshot quote) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        // Summed rather than assigned: two lines of the same variant — one with
        // extra cheese, one without — are one stock demand, and overwriting would
        // under-reserve.
        quote.lines().forEach(line ->
                quantities.merge(line.variantId(), line.quantity(), Integer::sum));
        return quantities;
    }

    private CheckoutResult settle(UUID attemptId, UUID orderId, String code, String detail,
            Instant now) {
        attempts.complete(attemptId, orderId, code, detail, now);
        return CheckoutResult.rejected(code, detail, warnings());
    }

    private CheckoutResult settleUnavailable(UUID attemptId, AvailabilityDecision decision,
            Instant now) {
        String detail = decision.unavailableItems().stream()
                .map(item -> item.variantId() + ":" + item.reason())
                .collect(Collectors.joining(","));
        attempts.complete(attemptId, null, "ITEMS_UNAVAILABLE", detail, now);
        return new CheckoutResult(CheckoutResult.Outcome.REJECTED, null, null, null, 0,
                "ITEMS_UNAVAILABLE", "Some items are no longer available",
                decision.unavailableItems(), warnings());
    }

    private CheckoutResult replayOf(UUID tenantId, UUID orderId) {
        var order = orders.find(tenantId, orderId)
                .orElseThrow(() -> new IllegalStateException("Settled attempt names a missing order"));
        return new CheckoutResult(CheckoutResult.Outcome.REPLAYED, order.orderId(),
                order.publicOrderNumber(), order.status(), order.version(), null, null,
                List.of(), warnings());
    }

    private int orderVersion(UUID tenantId, UUID orderId) {
        return orders.find(tenantId, orderId).map(JdbcOrderStore.OrderRow::version).orElse(1);
    }

    /**
     * The payment column an operations list renders, and nothing more.
     *
     * <p>A projection, not an authority: payments owns the intent's lifecycle and
     * this column is written from it. What is decided here is only its opening
     * value, and it is decided from the one question ordering is allowed to ask.
     *
     * <p>{@code PENDING} is reserved for an order that is actually waiting on
     * money before it can be confirmed. Cash is {@code NOT_REQUIRED} even though a
     * cash intent exists, because nothing will ever move a cash intent to captured
     * and a {@code PENDING} cash order would sit on the operations list for ever
     * looking like a stalled provider. An unwired port answers false for every
     * order and therefore lands on {@code NOT_REQUIRED}, exactly as before.
     */
    private String paymentProjection(boolean paymentRequiredBeforeConfirmation) {
        return paymentRequiredBeforeConfirmation ? "PENDING" : "NOT_REQUIRED";
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
        if (migrationOwnership.ownershipOf(tenantId, MigrationCapability.ORDERS,
                brandId, locationId).scopeId() == null) {
            return;
        }
        migrationOwnership.requireTargetMayWrite(tenantId, MigrationCapability.ORDERS,
                brandId, locationId);
    }

    private static boolean namesAPaymentMethod(CheckoutCommand command) {
        return command.paymentMethodCode() != null && !command.paymentMethodCode().isBlank();
    }

    private List<String> warnings() {
        List<String> gaps = new java.util.ArrayList<>(2);
        if (!payments.isWired()) {
            gaps.add(PaymentIntentPort.NOT_WIRED_WARNING);
        }
        if (!settlements.isWired()) {
            // An assembly that can take an order and refund none of them. Said on
            // the order rather than only at startup, because the person who finds
            // out otherwise is an operator with a customer on the phone.
            gaps.add(OrderSettlementPort.NOT_WIRED_WARNING);
        }
        return List.copyOf(gaps);
    }

    /**
     * What a checkout needs.
     *
     * @param expectedCartVersion ADR 0031's expected-version precondition. Without
     *                            it a checkout can be built on a basket the
     *                            customer has since edited on another device
     * @param contextHash         the hash the cart was priced at, proving the
     *                            basket is the one that produced the total
     * @param paymentMethodCode   how the order will be paid. Required: a checkout
     *                            that names none is refused with
     *                            {@code PAYMENT_METHOD_REQUIRED}, because the
     *                            settlement is planned from it and an order with
     *                            no settlement can never be refunded
     * @param redeemFromBalanceMinor how much of the total the customer asked to
     *                            settle from their points balance, in whole som, or
     *                            zero. Ordering carries the figure and decides
     *                            nothing about it: whether the account holds it,
     *                            whether the brand allows it and what share of an
     *                            order it may cover are the loyalty module's rules,
     *                            checked inside the reserving transaction
     */
    public record CheckoutCommand(
            UUID tenantId, UUID brandId, UUID cartId, int expectedCartVersion, UUID quoteId,
            String contextHash, String idempotencyKey, String paymentMethodCode,
            long redeemFromBalanceMinor, String actorType, String actorId, String correlationId) {

        /**
         * Everything that makes this request the request it is.
         *
         * <p>Compared against the stored fingerprint when a key is reused, so a
         * client sending a genuinely different checkout under an old key is told
         * so rather than being handed the earlier order.
         */
        public String fingerprint() {
            // The redemption is part of what makes this request this request. A
            // retry that quietly asks for a different number of points is a
            // different checkout, and handing it the first order would spend a
            // balance the customer did not agree to spend.
            String canonical = "%s|%s|%d|%s|%s|%d".formatted(cartId, quoteId, expectedCartVersion,
                    contextHash,
                    paymentMethodCode == null ? "" : paymentMethodCode.toUpperCase(Locale.ROOT),
                    redeemFromBalanceMinor);
            // Hashed to a fixed width so the stored column stays bounded however
            // long a future field grows. Comparison is equality either way; the
            // canonical string is what defines the request, and the digest is only
            // how it is stored.
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(
                        digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required", impossible);
            }
        }
    }

    /**
     * @param outcome    CREATED on the first success, REPLAYED when an earlier
     *                   identical request had already created it, REJECTED for a
     *                   settled business refusal
     * @param warnings   platform gaps that apply to this order; carried on every
     *                   result so an unwired port is visible on a report rather
     *                   than only in a startup log
     */
    public record CheckoutResult(
            Outcome outcome, UUID orderId, String publicOrderNumber, OrderStatus status,
            int orderVersion, String rejectionCode, String rejectionDetail,
            List<AvailabilityDecision.Unavailable> unavailableItems, List<String> warnings) {

        public enum Outcome { CREATED, REPLAYED, REJECTED }

        static CheckoutResult rejected(String code, String detail, List<String> warnings) {
            return new CheckoutResult(Outcome.REJECTED, null, null, null, 0, code, detail,
                    List.of(), warnings);
        }

        public boolean created() {
            return outcome == Outcome.CREATED || outcome == Outcome.REPLAYED;
        }
    }
}
