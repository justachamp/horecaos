package uz.qoida.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.iam.api.protection.DataClass;
import uz.qoida.platform.iam.api.protection.FieldProtection;
import uz.qoida.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.qoida.platform.ordering.domain.CartStatus;
import uz.qoida.platform.ordering.domain.DeliveryDestination;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.qoida.platform.pricing.api.CartPricingPort;
import uz.qoida.platform.pricing.api.QuoteSnapshot;
import uz.qoida.platform.tenancy.api.FulfillmentMode;
import uz.qoida.platform.tenancy.api.SalesChannel;
import uz.qoida.platform.tenancy.api.SalesChannelLookup;
import uz.qoida.platform.tenancy.api.Serviceability;
import uz.qoida.platform.tenancy.api.ServiceabilityResolver;

/**
 * The server-side cart (ADR 0019).
 *
 * <p>Server-side rather than in the client, because the price, the availability
 * and the menu a cart is valid against all live here. A client-held basket can
 * only ever be a request to reconstruct one.
 *
 * <p>Three rules shape everything below.
 *
 * <p><strong>A cart belongs to one location.</strong> Moving location rebuilds it
 * ({@link #rebuildAtLocation}), because catalog, availability, tax, fee and
 * promise all change with the branch, and carrying lines across silently would
 * show prices that do not apply.
 *
 * <p><strong>Any edit invalidates the price.</strong> Adding, changing or
 * removing a line clears the attached quote in the same statement that bumps the
 * version. A cart holding a quote for contents it no longer has is the shortest
 * path to charging a customer for a basket they did not agree to.
 *
 * <p><strong>Customer notes are personal data.</strong> "No onions, ring the top
 * bell" is free text a customer wrote about themselves, so it is encrypted at
 * rest under ADR 0029 and never leaves the database in an event.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    /**
     * Long enough for a customer to be interrupted and come back, short enough
     * that an abandoned basket stops looking live to the branch. Deliberately far
     * longer than the fifteen-minute quote TTL: the cart survives, the price does
     * not.
     */
    public static final Duration CART_TTL = Duration.ofHours(4);

    private static final String CART_LINE_TABLE = "ordering.cart_lines";
    private static final String NOTE_COLUMN = "customer_note_encrypted";

    private static final String CART_FULFILLMENT_TABLE = "ordering.cart_fulfillment";
    private static final String ADDRESS_COLUMN = "address_encrypted";
    private static final String INSTRUCTIONS_COLUMN = "delivery_instructions_encrypted";
    private static final String RECIPIENT_NAME_COLUMN = "recipient_name_encrypted";
    private static final String RECIPIENT_PHONE_COLUMN = "recipient_phone_encrypted";

    /** The ADR 0027 purpose recorded when a customer chooses where their order goes. */
    private static final String CAPTURE_PURPOSE = "CART_DESTINATION_CAPTURE";

    private final JdbcCartStore carts;
    private final SalesChannelLookup channels;
    private final CartMenuRules menu;
    private final ServiceabilityResolver serviceability;
    private final OrderingTenantContext tenancy;
    private final CartPricingPort pricing;
    private final CustomerAddressBook addresses;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CartService(JdbcCartStore carts, SalesChannelLookup channels, CartMenuRules menu,
            ServiceabilityResolver serviceability, OrderingTenantContext tenancy,
            CartPricingPort pricing, CustomerAddressBook addresses, FieldProtection protection,
            ObjectMapper objectMapper, Clock clock) {
        this.carts = carts;
        this.channels = channels;
        this.menu = menu;
        this.serviceability = serviceability;
        this.tenancy = tenancy;
        this.pricing = pricing;
        this.addresses = addresses;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Opens a cart at one location, on one channel, for one fulfilment mode.
     *
     * <p>Serviceability is checked here and again at checkout. Both, not either:
     * checking only here would let a branch that shut while the customer was
     * choosing still take the order, and checking only at checkout would let them
     * fill a basket they were never going to be allowed to place.
     */
    @Transactional
    public CartRow create(UUID tenantId, UUID brandId, UUID locationId, String channelCode,
            FulfillmentMode fulfillmentMode, UUID customerAccountId, String guestReferenceHash) {

        SalesChannel channel = channels.byCode(tenantId, channelCode)
                .orElseThrow(() -> new CartRefusedException("CHANNEL_NOT_REGISTERED",
                        "No sales channel " + channelCode + " for this tenant"));

        if (!channel.sellable()) {
            throw new CartRefusedException("CHANNEL_NOT_SELLABLE",
                    "Channel %s is %s".formatted(channelCode, channel.status()));
        }
        if (customerAccountId == null && !channel.guestOrdersAllowed()) {
            // ADR 0036 makes this a channel property. Discovering it at checkout
            // instead would let a guest fill a basket and be turned away at the
            // last step, which is the worst possible moment to learn it.
            throw new CartRefusedException("GUEST_ORDERS_NOT_ALLOWED",
                    "Channel " + channelCode + " requires an account");
        }

        Instant now = clock.instant();
        Serviceability decision = serviceability.resolve(
                tenantId, brandId, locationId, channel.id(), fulfillmentMode, now);
        if (!decision.available()) {
            throw new CartRefusedException("NOT_SERVICEABLE", decision.reason().name());
        }

        String currency = tenancy.defaultCurrency(tenantId)
                .orElseThrow(() -> new CartRefusedException("TENANT_NOT_FOUND",
                        "No tenant " + tenantId));

        CartRow cart = new CartRow(UUID.randomUUID(), tenantId, brandId, locationId, channel.id(),
                customerAccountId, guestReferenceHash, fulfillmentMode, currency,
                CartStatus.ACTIVE, null, null, null, 1, now.plus(CART_TTL), null);

        carts.insertCart(cart, now);
        log.debug("Opened cart {} at location {} on channel {}", cart.cartId(), locationId,
                channelCode);
        return cart;
    }

    /**
     * Reads a cart, for the customer it belongs to.
     *
     * <p>{@code callerAccountId} is not decoration. A cart id is a UUID that
     * travels in a URL, and until this argument existed the only things standing
     * between one and somebody else's basket were the tenant and the brand — both
     * of which are in the same URL. That made the cart id a bearer token for
     * reading a stranger's order, editing it, and pricing it.
     */
    @Transactional(readOnly = true)
    public Optional<CartView> view(UUID tenantId, UUID brandId, UUID callerAccountId, UUID cartId) {
        return carts.find(tenantId, brandId, cartId)
                .filter(cart -> ownedBy(cart, callerAccountId))
                .map(cart -> new CartView(cart, lines(tenantId, cartId)));
    }

    /**
     * Adds or replaces one line.
     *
     * <p>The expected version is required, not optional. Two devices editing one
     * cart is ordinary — a phone and a tablet at a table — and without the version
     * the second edit silently discards the first.
     *
     * <p>The published menu's modifier rules are enforced here rather than only at
     * checkout, because a cart is what the customer is looking at: a basket that
     * holds two sizes of one drink, or a burger with no bun chosen from a required
     * group, is a basket the kitchen cannot make, and discovering that at the
     * payment step is the worst moment to learn it.
     */
    @Transactional
    public CartView putLine(UUID tenantId, UUID brandId, UUID callerAccountId, UUID cartId,
            int expectedVersion, String lineKey, UUID variantId, int quantity,
            List<UUID> modifierOptionIds, String customerNote) {

        CartRow cart = requireEditable(tenantId, brandId, callerAccountId, cartId);
        requireSelectionRules(tenantId, brandId, cart, variantId, modifierOptionIds);
        Instant now = clock.instant();

        UUID lineId = lines(tenantId, cartId).stream()
                .filter(line -> line.lineKey().equals(lineKey))
                .map(CartLineRow::lineId)
                .findFirst()
                .orElseGet(UUID::randomUUID);

        String noteEncrypted = customerNote == null || customerNote.isBlank()
                ? null
                : protection.protect(tenantId, DataClass.PERSONAL,
                        new RecordRef(CART_LINE_TABLE, NOTE_COLUMN, lineId), customerNote)
                        .serialize();

        carts.upsertLine(lineId, tenantId, cartId, lineKey, variantId, quantity,
                modifiersJson(modifierOptionIds), noteEncrypted, now);

        if (!carts.touchAndInvalidatePricing(tenantId, cartId, expectedVersion, now)) {
            // The version moved between the read and the write, so this edit is
            // built on a cart that no longer exists in that shape. Failing here
            // rolls the line write back with it.
            throw new StaleCartException(expectedVersion, cart.version());
        }
        return view(tenantId, brandId, callerAccountId, cartId).orElseThrow();
    }

    @Transactional
    public CartView removeLine(UUID tenantId, UUID brandId, UUID callerAccountId, UUID cartId,
            int expectedVersion, String lineKey) {
        CartRow cart = requireEditable(tenantId, brandId, callerAccountId, cartId);
        Instant now = clock.instant();

        if (!carts.deleteLine(tenantId, cartId, lineKey)) {
            throw new CartRefusedException("LINE_NOT_FOUND", "No line " + lineKey + " in this cart");
        }
        if (!carts.touchAndInvalidatePricing(tenantId, cartId, expectedVersion, now)) {
            throw new StaleCartException(expectedVersion, cart.version());
        }
        return view(tenantId, brandId, callerAccountId, cartId).orElseThrow();
    }

    /**
     * Names where a delivery cart is going (ADR 0019, ADR 0015, ADR 0029).
     *
     * <p><strong>A saved address, never an ad-hoc one.</strong> The command names
     * one of the caller's own {@code customer.addresses} rows and nothing else,
     * and that is a decision rather than an omission. ADR 0015 already owns what
     * an address is: the encrypted field document, the coordinate, and V0021's
     * {@code coordinate_source} that separates "nobody has geocoded this yet" from
     * "this address deliberately has no point". An ad-hoc address typed into a
     * cart would have to reproduce all of that or skip it, and skipping it writes
     * an unroutable destination; reproducing it makes ordering the second module
     * that decides what an address is, and two writers of one concept drift. It
     * also gives a customer's home a second home: ADR 0029 erasure and ADR 0015
     * geocoding both run over {@code customer.addresses}, and an address that
     * exists only on a cart is outside both. The path for an operator taking an
     * order by phone from a customer with no saved address is to save one first —
     * one record, one erasure path, one geocoding queue.
     *
     * <p><strong>A copy, not a reference.</strong> What is written here is the
     * address as it was when the customer chose it. Re-reading it at checkout
     * would let an edit made in the profile screen change where an order in flight
     * is going, and archiving the address would strand a cart that was already
     * deliverable. The stored {@code customer_address_id} is provenance and
     * nothing more; choosing the address again is what refreshes the copy.
     *
     * <p><strong>The recipient is named, not inferred.</strong> A courier who
     * cannot telephone the person at the door does not deliver in this market, and
     * an order is often for somebody other than the account holder. The name and
     * phone are therefore required of the request rather than resolved from the
     * account, so that what the courier is given is what the customer confirmed.
     *
     * <p>The version precondition and the quote invalidation are the same ones a
     * line edit gets, for the same reason and one more: ADR 0037 prices the
     * delivery fee from the destination, so a basket priced to one door is not
     * priced to another.
     */
    @Transactional
    public CartView setDestination(UUID tenantId, UUID brandId, UUID callerAccountId, UUID cartId,
            int expectedVersion, DestinationCommand command) {

        CartRow cart = requireEditable(tenantId, brandId, callerAccountId, cartId);
        if (cart.fulfillmentMode() != FulfillmentMode.DELIVERY) {
            // A cart's mode is its identity and is never updated, so this is not a
            // race: it is a client asking a collected order where it is going.
            throw new CartRefusedException("DESTINATION_NOT_APPLICABLE",
                    "A " + cart.fulfillmentMode() + " cart has nowhere to be delivered");
        }

        var saved = addresses.destination(tenantId, cart.customerAccountId(),
                        command.customerAddressId(), CAPTURE_PURPOSE)
                .orElseThrow(() -> new CartRefusedException("ADDRESS_NOT_FOUND",
                        "No such address for this customer"));

        if (!saved.located()) {
            // ADR 0015 treats a landmark-only address as finished rather than
            // broken, and dispatch reaches it by telephone. Delivery sourcing
            // cannot: ADR 0037 measures from a point and a partner booking carries
            // primitive coordinates. Refused here, where the customer can drop a
            // pin, rather than when a courier is being sourced for an order they
            // have already paid for.
            throw new CartRefusedException("DESTINATION_NOT_LOCATED",
                    "This address has no coordinate and cannot be delivered to");
        }

        Instant now = clock.instant();
        DeliveryDestination destination = saved.destination();
        String note = command.deliveryNote() == null || command.deliveryNote().isBlank()
                // The address's own standing instruction when this order does not
                // override it. A customer who wrote "ring the top bell" once should
                // not have to write it again for every order.
                ? saved.deliveryInstructions()
                : command.deliveryNote();

        carts.upsertFulfillment(tenantId, cartId, saved.addressId(),
                encrypt(tenantId, cartId, ADDRESS_COLUMN,
                        objectMapper.writeValueAsString(destination)),
                encrypt(tenantId, cartId, INSTRUCTIONS_COLUMN, note),
                encrypt(tenantId, cartId, RECIPIENT_NAME_COLUMN, command.recipientName()),
                encrypt(tenantId, cartId, RECIPIENT_PHONE_COLUMN, command.recipientPhone()),
                destination.latitude(), destination.longitude(), now);

        if (!carts.touchAndInvalidatePricing(tenantId, cartId, expectedVersion, now)) {
            // Rolls the destination write back with it, exactly as a losing line
            // edit does. A destination attached to a cart version nobody asked for
            // is a delivery to an address the customer has already replaced.
            throw new StaleCartException(expectedVersion, cart.version());
        }
        // The address id, and never a fragment of the address. This line is the
        // one that would otherwise carry a doorstep into the log aggregator.
        log.debug("Cart {} is going to address {}", cartId, saved.addressId());
        return view(tenantId, brandId, callerAccountId, cartId).orElseThrow();
    }

    /**
     * The captured destination, decrypted, for the one transaction entitled to it.
     *
     * <p>Called by checkout and by nothing else. Every value it returns is
     * personal data and none of it may reach an event, a log or an error message;
     * the returned record prints as nothing for that reason.
     *
     * @param purpose recorded as an ADR 0027 fact against each reveal
     */
    @Transactional(readOnly = true)
    public Optional<CapturedDestination> destination(UUID tenantId, UUID cartId, String purpose) {
        return carts.findFulfillment(tenantId, cartId)
                .map(row -> new CapturedDestination(
                        row.customerAddressId(),
                        objectMapper.readValue(
                                decrypt(tenantId, cartId, ADDRESS_COLUMN, row.addressEncrypted(),
                                        purpose),
                                DeliveryDestination.class),
                        decrypt(tenantId, cartId, INSTRUCTIONS_COLUMN,
                                row.instructionsEncrypted(), purpose),
                        decrypt(tenantId, cartId, RECIPIENT_NAME_COLUMN,
                                row.recipientNameEncrypted(), purpose),
                        decrypt(tenantId, cartId, RECIPIENT_PHONE_COLUMN,
                                row.recipientPhoneEncrypted(), purpose)));
    }

    /**
     * Which of the customer's saved addresses this cart is going to, if any.
     *
     * <p>The id and never the address. It is the customer's own address id, which
     * is what lets a storefront show "delivering to: Home" by matching it against
     * ADR 0015's own listing; nothing decrypts a doorstep in order to render a
     * cart.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> destinationAddressId(UUID tenantId, UUID brandId, UUID callerAccountId,
            UUID cartId) {
        return carts.find(tenantId, brandId, cartId)
                .filter(cart -> ownedBy(cart, callerAccountId))
                .flatMap(cart -> carts.findFulfillment(tenantId, cartId))
                .map(JdbcCartStore.CartFulfillmentRow::customerAddressId);
    }

    private String encrypt(UUID tenantId, UUID cartId, String column, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return protection.protect(tenantId, DataClass.PERSONAL,
                        new RecordRef(CART_FULFILLMENT_TABLE, column, cartId), plaintext)
                .serialize();
    }

    private String decrypt(UUID tenantId, UUID cartId, String column, String ciphertext,
            String purpose) {
        if (ciphertext == null) {
            return null;
        }
        return protection.reveal(tenantId,
                uz.qoida.platform.iam.api.protection.ProtectedValue.deserialize(ciphertext),
                new RecordRef(CART_FULFILLMENT_TABLE, column, cartId), purpose);
    }

    /**
     * Prices the cart and binds the resulting quote to it.
     *
     * <p>The binding is what lets checkout accept a quote by identity rather than
     * by a client's word. A client that could name any quote id could name one
     * priced for a different, cheaper basket.
     */
    @Transactional
    public PricedCart price(UUID tenantId, UUID brandId, UUID callerAccountId, UUID cartId,
            int expectedVersion) {
        CartRow cart = requireEditable(tenantId, brandId, callerAccountId, cartId);
        if (cart.version() != expectedVersion) {
            throw new StaleCartException(expectedVersion, cart.version());
        }

        List<CartLineRow> lines = lines(tenantId, cartId);
        if (lines.isEmpty()) {
            throw new CartRefusedException("CART_EMPTY", "An empty cart has nothing to price");
        }

        SalesChannel channel = channels.byId(tenantId, cart.channelId())
                .orElseThrow(() -> new CartRefusedException("CHANNEL_NOT_REGISTERED",
                        "The cart's channel no longer exists"));

        QuoteSnapshot quote = pricing.priceCart(new CartPricingPort.PricingCommand(
                tenantId, brandId, cart.locationId(), cart.customerAccountId(),
                channel.code(),
                lines.stream()
                        .map(line -> new CartPricingPort.PricingCommand.Item(
                                line.lineKey(), line.variantId(), line.quantity(),
                                modifierIdsOf(line)))
                        .toList(),
                // Keyed on the cart and its version, so re-pricing an unchanged
                // cart returns the same quote rather than a second one holding a
                // second reservation — and any edit, which bumps the version,
                // produces a genuinely new quote.
                "cart:%s:v%d".formatted(cartId, cart.version())));

        if (!carts.attachQuote(tenantId, cartId, cart.version(), quote.quoteId(),
                quote.contextHash(), quote.catalogPublicationId(), clock.instant())) {
            throw new StaleCartException(cart.version(), cart.version());
        }
        return new PricedCart(cart.cartId(), cart.version(), quote);
    }

    /**
     * Rebuilds the cart at another location.
     *
     * <p>ADR 0019 is explicit that a cart is never carried across: catalog,
     * availability, tax, fee and promise all change. The old cart is abandoned and
     * a new one is opened with the same lines, unpriced. Anything the new location
     * does not sell will be refused when the new cart is priced, which is the
     * right place for that answer because it can name the item.
     */
    @Transactional
    public CartView rebuildAtLocation(UUID tenantId, UUID brandId, UUID callerAccountId,
            UUID cartId, int expectedVersion, UUID newLocationId) {

        CartRow existing = requireEditable(tenantId, brandId, callerAccountId, cartId);
        if (existing.version() != expectedVersion) {
            throw new StaleCartException(expectedVersion, existing.version());
        }
        if (existing.locationId().equals(newLocationId)) {
            return view(tenantId, brandId, callerAccountId, cartId).orElseThrow();
        }

        SalesChannel channel = channels.byId(tenantId, existing.channelId())
                .orElseThrow(() -> new CartRefusedException("CHANNEL_NOT_REGISTERED",
                        "The cart's channel no longer exists"));

        List<CartLineRow> oldLines = lines(tenantId, cartId);
        Instant now = clock.instant();

        CartRow rebuilt = create(tenantId, brandId, newLocationId, channel.code(),
                existing.fulfillmentMode(), existing.customerAccountId(),
                existing.guestReferenceHash());

        for (CartLineRow line : oldLines) {
            // The note ciphertext is bound to its old row by the ADR 0029
            // associated data, so it cannot simply be copied across: a value
            // moved to another row fails to decrypt. A rebuilt line therefore
            // starts without a note rather than with one nobody can read.
            carts.upsertLine(UUID.randomUUID(), tenantId, rebuilt.cartId(), line.lineKey(),
                    line.variantId(), line.quantity(), line.selectedModifiersJson(), null, now);
        }

        // The destination does come across, where the note above does not, and the
        // difference is what each one costs to lose. A note is a nicety; a
        // destination is the difference between a cart that can be checked out and
        // one that is refused, and a customer who moved branch did not change where
        // they live. Decrypted and re-encrypted rather than copied, because the ADR
        // 0029 associated data binds a ciphertext to its cart id.
        destination(tenantId, cartId, "CART_REBUILD").ifPresent(captured ->
                carts.upsertFulfillment(tenantId, rebuilt.cartId(), captured.customerAddressId(),
                        encrypt(tenantId, rebuilt.cartId(), ADDRESS_COLUMN,
                                objectMapper.writeValueAsString(captured.destination())),
                        encrypt(tenantId, rebuilt.cartId(), INSTRUCTIONS_COLUMN,
                                captured.deliveryNote()),
                        encrypt(tenantId, rebuilt.cartId(), RECIPIENT_NAME_COLUMN,
                                captured.recipientName()),
                        encrypt(tenantId, rebuilt.cartId(), RECIPIENT_PHONE_COLUMN,
                                captured.recipientPhone()),
                        captured.destination().latitude(), captured.destination().longitude(),
                        now));

        carts.transition(tenantId, cartId, CartStatus.ACTIVE, CartStatus.ABANDONED, null, now);
        log.info("Rebuilt cart {} as {} at location {}", cartId, rebuilt.cartId(), newLocationId);

        return view(tenantId, brandId, callerAccountId, rebuilt.cartId()).orElseThrow();
    }

    @Transactional
    public void abandon(UUID tenantId, UUID brandId, UUID cartId) {
        carts.transition(tenantId, cartId, CartStatus.ACTIVE, CartStatus.ABANDONED, null,
                clock.instant());
    }

    /** Sweeps carts past their TTL. Scheduled elsewhere; the rule lives with the model. */
    @Transactional
    public int expireStaleCarts() {
        int expired = carts.expireStaleCarts(clock.instant());
        if (expired > 0) {
            log.debug("Expired {} stale carts", expired);
        }
        return expired;
    }

    public List<CartLineRow> lines(UUID tenantId, UUID cartId) {
        return carts.lines(tenantId, cartId);
    }

    /** The chosen option ids, read back out of the stored snapshot. */
    public List<UUID> modifierIdsOf(CartLineRow line) {
        if (line.selectedModifiersJson() == null || line.selectedModifiersJson().isBlank()) {
            return List.of();
        }
        List<?> raw = objectMapper.readValue(line.selectedModifiersJson(), List.class);
        List<UUID> ids = new ArrayList<>(raw.size());
        raw.forEach(value -> ids.add(UUID.fromString(String.valueOf(value))));
        return ids;
    }

    /** The customer's note, decrypted for the one place that may see it. */
    public String revealNote(UUID tenantId, CartLineRow line, String purpose) {
        if (line.customerNoteEncrypted() == null) {
            return null;
        }
        return protection.reveal(tenantId,
                uz.qoida.platform.iam.api.protection.ProtectedValue.deserialize(
                        line.customerNoteEncrypted()),
                new RecordRef(CART_LINE_TABLE, NOTE_COLUMN, line.lineId()), purpose);
    }

    private CartRow requireEditable(UUID tenantId, UUID brandId, UUID callerAccountId,
            UUID cartId) {
        CartRow cart = carts.find(tenantId, brandId, cartId)
                .filter(row -> ownedBy(row, callerAccountId))
                .orElseThrow(() -> new CartRefusedException("CART_NOT_FOUND",
                        "No cart " + cartId + " for this brand"));
        if (!cart.status().editable()) {
            throw new CartRefusedException("CART_NOT_EDITABLE",
                    "This cart is " + cart.status());
        }
        if (!cart.expiresAt().isAfter(clock.instant())) {
            throw new CartRefusedException("CART_EXPIRED", "This cart has expired");
        }
        return cart;
    }

    /**
     * Whether this caller is the customer whose cart this is.
     *
     * <p>A null caller never matches, including against a guest cart that has no
     * owner. "Nobody owns this" and "nobody is asking" are not the same fact, and
     * treating them as equal would make every guest cart readable by every
     * unauthenticated request. ADR 0015's guest claim is what will bind a guest
     * cart to its holder; until it exists there is nothing here to match on.
     */
    private static boolean ownedBy(CartRow cart, UUID callerAccountId) {
        return callerAccountId != null && callerAccountId.equals(cart.customerAccountId());
    }

    /**
     * Enforces what the published menu says about this product's modifier groups.
     *
     * <p>The rules are authored, validated and published, and until now nothing
     * read them back: the cart accepted any list of option ids at all. A line
     * could hold no selection for a required group, more than the maximum, the
     * same option twice where repeats are forbidden, or options belonging to a
     * group the product does not offer — three of which reach the kitchen as an
     * unmakeable ticket and the fourth of which prices modifiers from another
     * dish.
     *
     * <p>Read from the publication the storefront serves, so what is enforced is
     * what was on screen. A variant the live publication does not describe carries
     * no rules and is left alone here; it is refused when the cart is priced,
     * where the refusal can name the item.
     */
    private void requireSelectionRules(UUID tenantId, UUID brandId, CartRow cart, UUID variantId,
            List<UUID> modifierOptionIds) {

        String channelCode = channels.byId(tenantId, cart.channelId())
                .orElseThrow(() -> new CartRefusedException("CHANNEL_NOT_REGISTERED",
                        "The cart's channel no longer exists"))
                .code();

        CartMenuRules.ProductRules rules =
                menu.forVariant(tenantId, brandId, channelCode, variantId).orElse(null);
        if (rules == null) {
            return;
        }

        List<UUID> chosen = modifierOptionIds == null ? List.of() : modifierOptionIds;
        for (UUID optionId : chosen) {
            if (rules.owning(optionId).isEmpty()) {
                throw new CartRefusedException("MODIFIER_NOT_OFFERED",
                        "Option %s is not offered by this product".formatted(optionId));
            }
        }

        for (CartMenuRules.GroupRules group : rules.groups()) {
            List<UUID> inGroup = chosen.stream().filter(group::offers).toList();

            // A required group must have a minimum of at least one. V0016 makes
            // that a check constraint, so this only matters for a menu published
            // before it — but a required group satisfied by nothing is precisely
            // the sandwich with no bread.
            int minimum = group.required()
                    ? Math.max(1, group.minimumSelections())
                    : group.minimumSelections();

            if (inGroup.size() < minimum) {
                throw new CartRefusedException("MODIFIER_GROUP_MINIMUM_NOT_MET",
                        "Group %s requires %d selection(s) and has %d"
                                .formatted(group.code(), minimum, inGroup.size()));
            }
            if (inGroup.size() > group.maximumSelections()) {
                throw new CartRefusedException("MODIFIER_GROUP_MAXIMUM_EXCEEDED",
                        "Group %s allows at most %d selection(s) and has %d"
                                .formatted(group.code(), group.maximumSelections(),
                                        inGroup.size()));
            }

            for (UUID optionId : inGroup.stream().distinct().toList()) {
                long repeats = inGroup.stream().filter(optionId::equals).count();
                if (repeats > 1 && !group.allowSameOptionMultipleTimes()) {
                    throw new CartRefusedException("MODIFIER_OPTION_NOT_REPEATABLE",
                            "Group %s does not allow the same option twice".formatted(group.code()));
                }
                if (repeats > group.maximumQuantityOf(optionId)) {
                    throw new CartRefusedException("MODIFIER_OPTION_QUANTITY_EXCEEDED",
                            "Option %s may be chosen at most %d time(s)"
                                    .formatted(optionId, group.maximumQuantityOf(optionId)));
                }
            }
        }
    }

    private String modifiersJson(List<UUID> modifierOptionIds) {
        List<String> ordered = modifierOptionIds == null
                ? List.of()
                // Sorted, so the same choices in a different tap order produce the
                // same document and therefore the same pricing context hash. An
                // unsorted list would make re-pricing an unchanged cart look like a
                // changed one.
                : modifierOptionIds.stream().map(UUID::toString).sorted().toList();
        return objectMapper.writeValueAsString(ordered);
    }

    /**
     * A cart with its lines, as every read of a cart returns it.
     *
     * <p>Deliberately without the destination. Reading a cart is the most frequent
     * operation in the storefront and the destination is a sub-resource read on its
     * own, so that the common path stays two statements rather than three — and so
     * that a cart response can never grow a field that turns out to be somebody's
     * home address.
     */
    public record CartView(CartRow cart, List<CartLineRow> lines) { }

    /**
     * What was captured on the cart, decrypted for one transaction.
     *
     * <p>Personal data in every component, so it prints as nothing — the reason
     * {@code ShipmentBookingPort.Waypoint} does the same. A record's generated
     * {@code toString} prints every field, and one interpolated log line then puts
     * a customer's address and telephone number into the log aggregator.
     */
    public record CapturedDestination(UUID customerAddressId, DeliveryDestination destination,
            String deliveryNote, String recipientName, String recipientPhone) {

        @Override
        public String toString() {
            return "CapturedDestination[REDACTED]";
        }
    }

    /**
     * A customer naming where their delivery order goes.
     *
     * @param customerAddressId one of the caller's own saved addresses. A cart
     *                          cannot name a stranger's address id: the lookup is
     *                          scoped to the account the cart belongs to and
     *                          answers not-found otherwise
     * @param recipientName     who the courier asks for. Required, because the
     *                          person at the door is often not the account holder
     * @param recipientPhone    what the courier rings. Required, because a
     *                          delivery in this market that cannot be telephoned
     *                          is a delivery that does not happen
     * @param deliveryNote      this order's courier note, or null to keep the
     *                          standing instruction saved with the address
     */
    public record DestinationCommand(UUID customerAddressId, String recipientName,
            String recipientPhone, String deliveryNote) {

        public DestinationCommand {
            if (customerAddressId == null) {
                throw new IllegalArgumentException("A destination names a saved address");
            }
            if (recipientName == null || recipientName.isBlank()) {
                throw new IllegalArgumentException("A destination needs somebody to ask for");
            }
            if (recipientPhone == null || recipientPhone.isBlank()) {
                throw new IllegalArgumentException("A destination needs a number to ring");
            }
        }

        /** Prints nothing: two of its four components are personal data. */
        @Override
        public String toString() {
            return "DestinationCommand[address=%s]".formatted(customerAddressId);
        }
    }

    /** @param cartVersion the version after the quote was attached */
    public record PricedCart(UUID cartId, int cartVersion, QuoteSnapshot quote) { }

    /** A cart operation refused for a business reason, with a stable code. */
    public static class CartRefusedException extends RuntimeException {

        private final String code;

        public CartRefusedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The cart changed since the caller read it. */
    public static class StaleCartException extends RuntimeException {

        private final int expected;
        private final int actual;

        public StaleCartException(int expected, int actual) {
            super("The cart has changed since version %d was read".formatted(expected));
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }
}
