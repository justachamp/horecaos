package uz.horecaos.platform.ordering.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.ordering.api.OrderReceived;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.application.CheckoutEligibilityGuard.Eligible;
import uz.horecaos.platform.ordering.application.CheckoutService.CheckoutCommand;
import uz.horecaos.platform.ordering.domain.AcceptanceMode;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.TransitionTrigger;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * Step 6 of {@link CheckoutService}'s order of operations: the order, and
 * everything it must remember for ever. Resolves how it will be accepted,
 * allocates its human-facing number, writes the order row and its ADR 0039
 * checkout revision, copies the priced result onto immutable line and customer
 * snapshots (encrypting every personal field under ADR 0029 on the way),
 * records the {@code RECEIVED} transition, and publishes {@link OrderReceived}.
 */
@Component
class CheckoutOrderWriter {

    private static final Logger log = LoggerFactory.getLogger(CheckoutOrderWriter.class);

    private static final String ORDER_LINE_TABLE = "ordering.order_lines";
    private static final String NOTE_COLUMN = "note_encrypted";

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";
    private static final String SNAPSHOT_NAME_COLUMN = "display_name_encrypted";
    private static final String SNAPSHOT_CONTACT_COLUMN = "contact_encrypted";
    private static final String SNAPSHOT_ADDRESS_COLUMN = "address_encrypted";
    private static final String SNAPSHOT_INSTRUCTIONS_COLUMN = "delivery_instructions_encrypted";

    private final JdbcOrderStore orders;
    private final OrderAcceptancePolicyService acceptancePolicies;
    private final OrderingTenantContext tenancy;
    private final OrderCatalogSnapshot catalog;
    private final CartService cartService;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;
    private final PaymentIntentPort payments;
    private final ApplicationEventPublisher events;

    CheckoutOrderWriter(
            JdbcOrderStore orders,
            OrderAcceptancePolicyService acceptancePolicies,
            OrderingTenantContext tenancy,
            OrderCatalogSnapshot catalog,
            CartService cartService,
            FieldProtection protection,
            ObjectMapper objectMapper,
            PaymentIntentPort payments,
            ApplicationEventPublisher events) {
        this.orders = orders;
        this.acceptancePolicies = acceptancePolicies;
        this.tenancy = tenancy;
        this.catalog = catalog;
        this.cartService = cartService;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.payments = payments;
        this.events = events;
    }

    /**
     * @param paymentFirst whether ADR 0013's capture timing requires the money
     *     before confirmation, decided here because the answer also picks the
     *     order's opening payment projection
     */
    record Written(
            String publicNumber,
            OrderAcceptancePolicyService.Effective policy,
            boolean approvalRequired,
            @Nullable Instant approvalDeadline,
            boolean paymentFirst) {

        Written {
            if (approvalRequired != (approvalDeadline != null)) {
                throw new IllegalArgumentException("approvalDeadline must be set exactly when approval is required");
            }
        }
    }

    Written create(CheckoutCommand command, Eligible eligible, UUID orderId, Set<UUID> variantIds, Instant now) {
        CartRow cart = eligible.cart();
        SalesChannel channel = eligible.channel();
        QuoteSnapshot quote = eligible.quote();
        // CheckoutEligibilityGuard already refused any command with no payment
        // method before this collaborator ever runs.
        String paymentMethodCode = Objects.requireNonNull(command.paymentMethodCode());

        var policy = acceptancePolicies.resolve(command.tenantId(), command.brandId(), cart.locationId());
        boolean approvalRequired = policy.policy().mode() == AcceptanceMode.RESTAURANT_APPROVAL;
        Instant approvalDeadline = approvalRequired ? now.plus(policy.policy().approvalTimeout()) : null;

        String publicNumber = allocateNumber(command.tenantId(), cart.locationId(), now);
        OrderPromise promise = promise(command, cart, variantIds, eligible.decision(), now);

        // Asked before the row is written because the answer decides the payment
        // projection that goes on it. Asked of payments rather than decided here:
        // the answer is ADR 0013's capture timing for the channel's method, and
        // ordering owning a copy of that table is how the two drift apart.
        boolean paymentFirst =
                payments.paymentRequiredBeforeConfirmation(command.tenantId(), orderId, paymentMethodCode);

        orders.insertOrder(new JdbcOrderStore.NewOrder(
                orderId,
                publicNumber,
                command.tenantId(),
                command.brandId(),
                cart.locationId(),
                cart.channelId(),
                channel.code(),
                cart.customerAccountId(),
                cart.guestReferenceHash(),
                cart.fulfillmentMode(),
                policy.policy().mode().name(),
                policy.policyId(),
                policy.policyVersion(),
                policy.policy().approvalChannel().name(),
                approvalRequired ? policy.policy().timeoutAction().name() : null,
                approvalDeadline,
                OrderStatus.RECEIVED,
                paymentProjection(paymentFirst),
                "PENDING",
                quote.currency(),
                quote.subtotalMinor(),
                quote.taxMinor(),
                quote.discountMinor(),
                quote.feeMinor(),
                quote.totalMinor(),
                quote.quoteId(),
                quote.contextHash(),
                quote.catalogPublicationId(),
                cart.cartId(),
                command.idempotencyKey(),
                promise,
                command.actorType(),
                command.actorId(),
                now));

        // ADR 0039 revision 1: the ADR 0019 checkout snapshot, written before the
        // lines that belong to it. A report pinned here must still reconcile to
        // the original total after ten amendments, which it can only do if the
        // five figures were copied at the moment they were agreed rather than
        // recomputed later from an order row somebody has since amended.
        orders.insertRevision(new JdbcOrderStore.NewRevision(
                orderId,
                1,
                command.tenantId(),
                "CHECKOUT",
                null,
                quote.quoteId(),
                quote.contextHash(),
                quote.currency(),
                quote.subtotalMinor(),
                quote.taxMinor(),
                quote.discountMinor(),
                quote.feeMinor(),
                quote.totalMinor(),
                0L,
                command.actorType(),
                command.actorId(),
                now));

        writeSnapshots(command, eligible, orderId);

        orders.recordTransition(
                command.tenantId(),
                orderId,
                1,
                null,
                OrderStatus.RECEIVED,
                TransitionTrigger.CHECKOUT,
                null,
                command.actorType(),
                command.actorId(),
                command.correlationId(),
                now);

        events.publishEvent(new OrderReceived(
                UUID.randomUUID(),
                new TenantId(command.tenantId()),
                orderId,
                now,
                command.brandId(),
                cart.locationId(),
                channel.code(),
                publicNumber,
                cart.fulfillmentMode().name(),
                policy.policy().mode().name(),
                policy.policyId(),
                policy.policyVersion(),
                OrderStatus.RECEIVED.name(),
                1,
                quote.currency(),
                quote.totalMinor(),
                quote.lines().size()));

        return new Written(publicNumber, policy, approvalRequired, approvalDeadline, paymentFirst);
    }

    /**
     * Copies the priced result onto the order.
     *
     * <p>Names come from the catalog and amounts from the quote, and both are
     * copied rather than referenced. That is the whole mechanism behind "an
     * order's commercial snapshot survives a menu republish": there is no join
     * from an order line back to a catalog row for a republish to change.
     */
    private void writeSnapshots(CheckoutCommand command, Eligible eligible, UUID orderId) {
        CartRow cart = eligible.cart();
        var cartLines = eligible.cartLines();
        QuoteSnapshot quote = eligible.quote();
        Optional<CartService.CapturedDestination> destination = eligible.destination();

        Map<String, CartLineRow> cartLinesByKey = cartLines.stream()
                .collect(Collectors.toMap(CartLineRow::lineKey, line -> line, (a, b) -> a, LinkedHashMap::new));

        Set<UUID> variantIds =
                quote.lines().stream().map(QuoteSnapshot.Line::variantId).collect(Collectors.toUnmodifiableSet());
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

            orders.insertLine(
                    orderLineId,
                    command.tenantId(),
                    orderId,
                    lineNumber,
                    descriptor == null ? null : descriptor.productId(),
                    line.variantId(),
                    // The quote's description is the name the customer was shown at
                    // the moment they were shown the price; the catalog lookup is
                    // only a fallback for a variant the quote could not describe.
                    line.descriptionSnapshot(),
                    descriptor == null ? null : descriptor.variantName(),
                    descriptor == null ? null : descriptor.sku(),
                    line.quantity(),
                    line.unitAmountMinor(),
                    line.baseAmountMinor(),
                    line.finalAmountMinor(),
                    line.taxAmountMinor(),
                    reEncryptNote(command.tenantId(), cartLine, orderLineId));

            orderLineIdsByKey.put(line.lineKey(), orderLineId);

            if (cartLine == null) {
                continue;
            }
            for (UUID optionId : cartService.modifierIdsOf(cartLine)) {
                var option = options.get(optionId);
                orders.insertLineModifier(
                        command.tenantId(),
                        orderLineId,
                        option == null ? null : option.groupId(),
                        optionId,
                        option == null ? null : option.groupName(),
                        option == null ? optionId.toString() : option.optionName(),
                        1,
                        0L,
                        0L);
            }
        }

        for (QuoteSnapshot.Adjustment adjustment : quote.adjustments()) {
            orders.insertAdjustment(
                    command.tenantId(),
                    orderId,
                    adjustment.sequence(),
                    adjustment.lineKey() == null ? null : orderLineIdsByKey.get(adjustment.lineKey()),
                    adjustment.adjustmentType(),
                    adjustment.sourceType(),
                    adjustment.sourceId(),
                    adjustment.sourceVersion(),
                    adjustment.descriptionCode(),
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
        orders.insertCustomerSnapshot(
                command.tenantId(),
                orderId,
                destination
                        .map(captured ->
                                protect(command.tenantId(), orderId, SNAPSHOT_NAME_COLUMN, captured.recipientName()))
                        .orElse(null),
                destination
                        .map(captured -> protect(
                                command.tenantId(), orderId, SNAPSHOT_CONTACT_COLUMN, captured.recipientPhone()))
                        .orElse(null),
                destination
                        .map(captured -> protect(
                                command.tenantId(), orderId, SNAPSHOT_ADDRESS_COLUMN, destinationDocument(captured)))
                        .orElse(null),
                destination
                        .map(captured -> protect(
                                command.tenantId(), orderId, SNAPSHOT_INSTRUCTIONS_COLUMN, captured.deliveryNote()))
                        .orElse(null),
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

    private @Nullable String protect(UUID tenantId, UUID orderId, String column, @Nullable String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef(SNAPSHOT_TABLE, column, orderId),
                        plaintext)
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
    private @Nullable String reEncryptNote(UUID tenantId, @Nullable CartLineRow cartLine, UUID orderLineId) {
        if (cartLine == null || cartLine.customerNoteEncrypted() == null) {
            return null;
        }
        // revealNote returns null only when customerNoteEncrypted() is null,
        // which is already excluded above.
        String note = Objects.requireNonNull(cartService.revealNote(tenantId, cartLine, "ORDER_SNAPSHOT"));
        return protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef(ORDER_LINE_TABLE, NOTE_COLUMN, orderLineId),
                        note)
                .serialize();
    }

    /**
     * The human-facing number: the branch's local date and a counter that resets
     * with it.
     *
     * <p>Local rather than UTC, because a UTC date rolls over at 05:00 in Tashkent
     * and would split one evening's service across two numbering days.
     */
    private String allocateNumber(UUID tenantId, UUID locationId, Instant now) {
        ZoneId zone = tenancy.timezoneOf(tenantId, locationId)
                .orElseThrow(() -> new IllegalStateException("Location " + locationId + " has no timezone"));
        LocalDate businessDate = now.atZone(zone).toLocalDate();
        int sequence = orders.nextOrderNumber(tenantId, locationId, businessDate);
        return "%02d%02d-%03d".formatted(businessDate.getMonthValue(), businessDate.getDayOfMonth(), sequence);
    }

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
    private OrderPromise promise(
            CheckoutCommand command, CartRow cart, Set<UUID> variantIds, Serviceability decision, Instant now) {

        Duration slowestItem = catalog.longestPreparationOverride(
                        command.tenantId(), command.brandId(), cart.locationId(), variantIds)
                .orElse(null);

        // Travel is null rather than zero. ADR 0037's zone model is not built, so
        // a delivery promise today covers the kitchen and nothing else; recording
        // that as a zero would claim the road takes no time, and the backfill that
        // eventually corrects these orders could not find them.
        return OrderPromise.assemble(now, decision.preparationMinutes(), slowestItem, null);
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
}
