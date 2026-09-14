package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.ordering.api.MarketplaceBindingLookup;
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
 */
@Service
public class AggregatorOrderIntakeService {

    private final SalesChannelLookup channels;
    private final MarketplaceBindingLookup installations;
    private final JdbcOrderStore orders;
    private final JdbcAggregatorOrderStore store;
    private final Clock clock;

    public AggregatorOrderIntakeService(
            SalesChannelLookup channels,
            MarketplaceBindingLookup installations,
            JdbcOrderStore orders,
            JdbcAggregatorOrderStore store,
            Clock clock) {
        this.channels = channels;
        this.installations = installations;
        this.orders = orders;
        this.store = store;
        this.clock = clock;
    }

    /** One line the operator typed off the aggregator's own order screen. */
    public record Line(
            @Nullable UUID variantId,
            String nameSnapshot,
            int quantity,
            long unitAmountMinor,
            @Nullable String externalItemReference) {}

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
            String operatorSubject) {}

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
                clock.instant()));

        return new Result(created.orderId(), created.publicOrderNumber(), false);
    }
}
