package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fulfillment.api.DeliveryPlanner;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.ordering.api.MarketplaceBindingLookup;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcAggregatorOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Manual aggregator order entry (ADR 0040, orders.md §5, gap map row {@code
 * 1.3g}): "when an aggregator phones an order through because their
 * integration is down, the operator has no way to record it as that
 * aggregator's order with externally-set pricing."
 *
 * <p>Deliberately not {@link OperatorOrderingService}. That class reuses
 * {@link CartService} and {@link CheckoutService} because an operator-placed
 * order is priced by the identical HorecaOS pipeline a customer's own order
 * is. Here the whole premise is the opposite: the total was computed by the
 * aggregator, not by HorecaOS, and ADR 0040's decision is that an externally
 * priced total is never run back through {@link QuoteService} — "the
 * escape hatch is reachable from exactly one origin", enforced at the
 * database by {@code ck_order_external_pricing_is_marketplace}. So this
 * writes {@link JdbcAggregatorOrderStore} directly, the same shape {@code
 * JdbcMarketplaceOrderIntake} already writes for an automated partner push,
 * with {@code entry_mode = MANUAL} in place of {@code API}.
 *
 * <p><strong>Which aggregator, resolved from the channel the tenant already
 * configured.</strong> The operator picks the tenant's own AGGREGATOR-system-type
 * sales channel (ADR 0036) exactly as the rest of the New order screen picks
 * the operator channel — no second "which partner" picker. That channel's
 * {@code provider_installation_id} names the ADR 0026 installation, and
 * {@link MarketplaceBindingLookup#bindingForInstallation} resolves the one
 * binding of it that covers this branch, which is what {@code
 * ordering.orders.marketplace_binding_id} actually points at. Ordering asks
 * through this module's own port rather than {@code integration}'s directly
 * — see {@link MarketplaceBindingLookup}'s own doc for why.
 *
 * <p><strong>Wave 11 w5-fulfillment-destination (row {@code 1.3g}): {@code
 * DELIVERY}, by reusing the New order screen's own structured destination —
 * never a second, untyped address path.</strong> ADR 0114's own open input
 * asked whether a manual entry should ever support delivery; this wave
 * answers yes, the same way the rest of {@code AggregatorOrderRequest}
 * reuses {@code OperationsOrderController.DestinationRequest} — an existing
 * customer's saved, geocoded address ({@link CustomerAddressBook}, the exact
 * port {@link CartService#setDestination} resolves a saved address through)
 * named by {@code customerAddressId}, never a raw address typed ad hoc. This
 * does not give the order a {@code customer_account_id}: ADR 0040's "a
 * marketplace order never matches one" still holds, and {@code
 * customerAccountId} here is scope for the address lookup alone, exactly the
 * predicate {@link CustomerAddressBook#destination} already requires so that
 * one customer cannot resolve another's saved home by guessing an address
 * id. Once resolved, the destination is snapshotted onto {@code
 * ordering.order_customer_snapshots} the same encrypted way {@code
 * CheckoutOrderWriter} snapshots a native order's, and {@link
 * DeliveryPlanner#planFor} is called directly — the same port {@code
 * DeliveryPlanTrigger} calls off {@code OrderConfirmed} for a native order —
 * because a phoned-through aggregator order has no checkout transaction for
 * that trigger to listen to. Deliberately narrower than a native delivery
 * order in the two ways ADR 0114 already names for {@code PICKUP}: no
 * {@code order_external_pricing} and no {@code order_handover_challenges}
 * row (the open inputs above this doc still ask whether a manual entry
 * should ever carry settlement or handover evidence, unanswered by this
 * wave).
 */
@Service
public class AggregatorOrderIntakeService {

    private static final Logger log = LoggerFactory.getLogger(AggregatorOrderIntakeService.class);

    /** The two fulfilment modes a manual entry may carry — never {@code DINE_IN}, which has no address to resolve. */
    private static final String PICKUP = "PICKUP";

    private static final String DELIVERY = "DELIVERY";

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";
    private static final String SNAPSHOT_NAME_COLUMN = "display_name_encrypted";
    private static final String SNAPSHOT_CONTACT_COLUMN = "contact_encrypted";
    private static final String SNAPSHOT_ADDRESS_COLUMN = "address_encrypted";
    private static final String SNAPSHOT_INSTRUCTIONS_COLUMN = "delivery_instructions_encrypted";

    /** The ADR 0027 purpose recorded against the one address reveal a manual delivery entry performs. */
    private static final String ADDRESS_REVEAL_PURPOSE = "AGGREGATOR_ENTRY_DESTINATION_CAPTURE";

    private final SalesChannelLookup channels;
    private final MarketplaceBindingLookup installations;
    private final JdbcOrderStore orders;
    private final JdbcAggregatorOrderStore store;
    private final CustomerAddressBook addresses;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;
    private final DeliveryPlanner deliveryPlanner;
    private final OrderFulfillmentProcess fulfillmentProcess;
    private final Clock clock;

    public AggregatorOrderIntakeService(
            SalesChannelLookup channels,
            MarketplaceBindingLookup installations,
            JdbcOrderStore orders,
            JdbcAggregatorOrderStore store,
            CustomerAddressBook addresses,
            FieldProtection protection,
            ObjectMapper objectMapper,
            DeliveryPlanner deliveryPlanner,
            OrderFulfillmentProcess fulfillmentProcess,
            Clock clock) {
        this.channels = channels;
        this.installations = installations;
        this.orders = orders;
        this.store = store;
        this.addresses = addresses;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.deliveryPlanner = deliveryPlanner;
        this.fulfillmentProcess = fulfillmentProcess;
        this.clock = clock;
    }

    /** One line the operator typed off the aggregator's own order screen. */
    public record Line(
            @Nullable UUID variantId,
            String nameSnapshot,
            int quantity,
            long unitAmountMinor,
            @Nullable String externalItemReference) {}

    /**
     * Row {@code 1.3g}: where a {@code DELIVERY} manual entry goes — the
     * identical shape {@code OperationsOrderController.DestinationRequest}
     * and {@link OperatorOrderingService.Destination} already carry, named
     * separately here only so this module does not reach into the web
     * layer's own record for it.
     */
    public record Destination(
            UUID customerAddressId,
            String recipientName,
            String recipientPhone,
            @Nullable String deliveryNote) {

        /** Never prints the recipient's name or phone. */
        @Override
        public String toString() {
            return "Destination[address=%s]".formatted(customerAddressId);
        }
    }

    /**
     * @param fulfillmentMode  {@code PICKUP} or {@code DELIVERY}, null
     *                         treated as {@code PICKUP} — the row's own
     *                         previous, only behaviour. Anything else is
     *                         refused
     * @param customerAccountId required exactly when {@code fulfillmentMode}
     *                         is {@code DELIVERY}: whose saved address {@code
     *                         destination} names. Never written to the
     *                         order's own {@code customer_account_id} — see
     *                         this class's own doc for why a manual entry
     *                         still matches no customer under ADR 0040
     * @param destination      required exactly when {@code fulfillmentMode}
     *                         is {@code DELIVERY}, refused otherwise
     */
    public record Command(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String channelCode,
            String externalOrderId,
            List<Line> lines,
            String currency,
            long subtotalMinor,
            long discountMinor,
            long feeMinor,
            long totalMinor,
            String idempotencyKey,
            String operatorSubject,
            @Nullable String fulfillmentMode,
            @Nullable UUID customerAccountId,
            @Nullable Destination destination) {}

    public record Result(UUID orderId, String publicOrderNumber, boolean replayed) {}

    @Transactional
    public Result create(Command command) {
        if (command.lines().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An order needs at least one line");
        }
        for (Line line : command.lines()) {
            if (line.quantity() <= 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A line's quantity must be positive");
            }
            if (line.unitAmountMinor() < 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A line's price cannot be negative");
            }
        }
        if (command.subtotalMinor() < 0 || command.discountMinor() < 0 || command.feeMinor() < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Subtotal, discount and fee cannot be negative");
        }

        String fulfillmentMode = command.fulfillmentMode() == null ? PICKUP : command.fulfillmentMode();
        if (!PICKUP.equals(fulfillmentMode) && !DELIVERY.equals(fulfillmentMode)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A manual aggregator entry is PICKUP or DELIVERY, never " + fulfillmentMode);
        }
        boolean delivery = DELIVERY.equals(fulfillmentMode);
        if (delivery && (command.customerAccountId() == null || command.destination() == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "DELIVERY carries the customer whose saved address this delivers to, and that address");
        }
        if (!delivery && (command.customerAccountId() != null || command.destination() != null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A PICKUP entry carries no customer or destination");
        }

        // The header subtotal is operator-typed off the aggregator's own order
        // screen, independent of the lines the operator also types below it — a
        // mistyped digit in either would otherwise write a header and a line set
        // that quietly disagree, with nothing at the database catching it
        // (ck_order_total_reconciles only checks total against subtotal/tax/
        // fee/discount, never against the lines' own sum). Line amounts, not the
        // header, are what JdbcAggregatorOrderStore.create persists as each
        // order_lines row's final_amount_minor, so this is the one check that
        // keeps the header telling the truth about what was actually written.
        long lineTotalMinor = command.lines().stream()
                .mapToLong(line -> line.unitAmountMinor() * line.quantity())
                .sum();
        if (lineTotalMinor != command.subtotalMinor()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The subtotal does not match the sum of the lines given");
        }

        // Idempotency-Key replay, the same contract every other mutating
        // endpoint gives (ADR 0031) — this path writes ordering.orders
        // directly rather than through CheckoutService, so it must give that
        // guarantee itself rather than inheriting it.
        Optional<JdbcOrderStore.OrderRow> existing =
                orders.findByIdempotencyKey(command.tenantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            JdbcOrderStore.OrderRow row = existing.get();
            return new Result(row.orderId(), row.publicOrderNumber(), true);
        }

        SalesChannel channel = channels.byCode(command.tenantId(), command.channelCode())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such channel"));
        if (channel.systemType() != SalesChannelSystemType.AGGREGATOR) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Only an AGGREGATOR-type channel may carry a manual aggregator order");
        }
        if (!channel.sellable()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This channel is not active");
        }
        UUID installationId = channel.providerInstallationId();
        if (installationId == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This channel has no marketplace installation configured (Settings > Sales channels)");
        }
        UUID bindingId = installations
                .bindingForInstallation(command.tenantId(), installationId, command.brandId(), command.locationId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "No active marketplace binding covers this branch for this channel"));

        // ADR 0040's own rule for an externally priced order: HorecaOS
        // validates arithmetic and nothing else. Tax is derived rather than
        // independently typed, so ck_order_total_reconciles always holds by
        // construction instead of being asserted against operator arithmetic.
        long taxMinor = command.totalMinor() - command.subtotalMinor() - command.feeMinor() + command.discountMinor();
        if (taxMinor < 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The total does not reconcile with the subtotal, discount and fee given");
        }

        // Row 1.3g (DELIVERY): resolved last, right before the write, so a
        // request doomed by channel or arithmetic never pays for a decrypt —
        // the ADR 0027 purpose this reveal records is meant for a delivery
        // that is actually about to be dispatched.
        ResolvedDestination resolvedDestination = delivery
                ? resolveDestination(
                        command.tenantId(),
                        Objects.requireNonNull(command.customerAccountId(), "delivery already refused a null account"),
                        Objects.requireNonNull(command.destination(), "delivery already refused a null destination"))
                : null;

        JdbcAggregatorOrderStore.Created created = store.create(new JdbcAggregatorOrderStore.Command(
                Ids.newId(),
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                channel.id(),
                channel.code(),
                bindingId,
                command.externalOrderId(),
                command.lines().stream()
                        .map(line -> new JdbcAggregatorOrderStore.Line(
                                line.variantId(),
                                line.nameSnapshot(),
                                line.quantity(),
                                line.unitAmountMinor(),
                                line.externalItemReference()))
                        .toList(),
                command.currency(),
                command.subtotalMinor(),
                command.discountMinor(),
                command.feeMinor(),
                taxMinor,
                command.totalMinor(),
                command.idempotencyKey(),
                command.operatorSubject(),
                fulfillmentMode,
                clock.instant()));

        if (delivery) {
            planDelivery(command, created.orderId(), Objects.requireNonNull(resolvedDestination));
        }

        return new Result(created.orderId(), created.publicOrderNumber(), false);
    }

    /**
     * Row 1.3g: the destination as {@link CustomerAddressBook} revealed it,
     * plus the recipient facts the request itself carried — everything
     * {@link #planDelivery} needs to snapshot the order's customer record,
     * kept off {@link Command} because it is the one intermediate value this
     * method computes rather than one a caller supplies.
     */
    private record ResolvedDestination(
            DeliveryDestination destination,
            @Nullable String deliveryInstructions,
            String recipientName,
            String recipientPhone) {}

    private ResolvedDestination resolveDestination(UUID tenantId, UUID customerAccountId, Destination requested) {
        CustomerAddressBook.SavedDestination saved = addresses
                .destination(tenantId, customerAccountId, requested.customerAddressId(), ADDRESS_REVEAL_PURPOSE)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "No such address for this customer"));
        if (!saved.located()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This address has no coordinate and cannot be delivered to");
        }
        String note =
                requested.deliveryNote() == null || requested.deliveryNote().isBlank()
                        ? saved.deliveryInstructions()
                        : requested.deliveryNote();
        return new ResolvedDestination(
                Objects.requireNonNull(saved.destination(), "A located address has a destination"),
                note,
                requested.recipientName(),
                requested.recipientPhone());
    }

    /**
     * Row 1.3g: everything a native delivery order gets once it is confirmed
     * — a customer snapshot ({@code CheckoutOrderWriter}'s own encrypted
     * shape) and a plan ({@code DeliveryPlanTrigger}'s own call) — for an
     * order this service just wrote directly rather than through {@link
     * CheckoutService}. {@code DeliveryPlanner#planFor} never throws for an
     * order it cannot plan (an unplaced branch, for instance): this method
     * does not either, because a delivery configuration problem must not
     * fail an aggregator entry the operator already has the aggregator's
     * money for.
     */
    private void planDelivery(Command command, UUID orderId, ResolvedDestination resolved) {
        orders.insertCustomerSnapshot(
                command.tenantId(),
                orderId,
                protect(command.tenantId(), orderId, SNAPSHOT_NAME_COLUMN, resolved.recipientName()),
                protect(command.tenantId(), orderId, SNAPSHOT_CONTACT_COLUMN, resolved.recipientPhone()),
                protect(
                        command.tenantId(),
                        orderId,
                        SNAPSHOT_ADDRESS_COLUMN,
                        objectMapper.writeValueAsString(resolved.destination())),
                protect(command.tenantId(), orderId, SNAPSHOT_INSTRUCTIONS_COLUMN, resolved.deliveryInstructions()),
                true);

        Instant now = clock.instant();
        deliveryPlanner
                .planFor(command.tenantId(), command.brandId(), command.locationId(), orderId, now)
                .ifPresentOrElse(
                        planId -> {
                            log.debug("Opened delivery plan {} for manual aggregator entry {}", planId, orderId);
                            fulfillmentProcess.enqueue(orderId, command.tenantId(), planId, now);
                        },
                        () -> log.warn(
                                "Manual aggregator entry {} asked for DELIVERY but no plan was opened "
                                        + "(no branch coordinate, or another configuration gap)",
                                orderId));
    }

    private @Nullable String protect(UUID tenantId, UUID orderId, String column, @Nullable String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(SNAPSHOT_TABLE, column, orderId), plaintext)
                .serialize();
    }
}
