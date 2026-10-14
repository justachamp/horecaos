package uz.horecaos.platform.ordering.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.domain.AcceptanceMode;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * The two auto-accept gates, read where an order is accepted (ADR 0177 decision 3, gap map row
 * {@code 10.3b}; the Delever comparison in {@code settings.md} §10.3 named both).
 *
 * <p>Under {@code AUTO_CONFIRM}, an order is auto-confirmed unless a gate holds it back:
 * <ul>
 *   <li>{@code ordering.auto_accept_eligible_channels} -- {@code ALL} (the default, no gate), or a
 *       comma-separated list of {@code tenant.sales_channels.code}; an order on any other channel is
 *       held. "Auto-accept the bot, hand-check the aggregator."
 *   <li>{@code ordering.auto_accept_min_prior_orders} -- the anti-fraud gate: a customer with fewer
 *       {@code COMPLETED} orders in the brand than this is held. A guest has none, and {@code 0} is no
 *       gate.
 * </ul>
 * Both are read at the order's location scope, so a branch can be stricter than its brand.
 *
 * <p><strong>A held order follows the acceptance document's own approval path</strong> -- its channel,
 * timeout and timeout action (see {@link OrderAcceptancePolicy#hasApprovalPath()}) -- rather than a
 * second, gate-specific one. When the document carries no approval channel there is nowhere to send a
 * held order, and this does not strand it: the answer is {@link Unenforceable}, the order auto-confirms
 * as it did before the gates were read, and a counter and a log line say a gate that could not hold
 * anything was set. The settings page says the same beside the gate fields.
 *
 * <p>{@code RESTAURANT_APPROVAL} is never gated: every order already waits for a decision. Orders that
 * bypass checkout (a marketplace or aggregator order, written by {@code JdbcMarketplaceOrderIntake} or
 * {@code JdbcAggregatorOrderStore} already accepted by the aggregator, ADR 0040) never reach this class.
 */
@Component
class AutoAcceptGate {

    private static final Logger log = LoggerFactory.getLogger(AutoAcceptGate.class);

    /** {@code horecaos.order.auto_accept_gate}: how many orders a gate held, and how many it could not. */
    static final String METRIC = "horecaos.order.auto_accept_gate";

    /** The literal that means "every channel". */
    static final String ALL_CHANNELS = "ALL";

    /** Why a gate held an order back. Recorded as the checkout transition's reason code. */
    enum Reason {
        CHANNEL_NOT_ELIGIBLE("AUTO_ACCEPT_HELD_CHANNEL"),
        TOO_FEW_PRIOR_ORDERS("AUTO_ACCEPT_HELD_PRIOR_ORDERS");

        private final String reasonCode;

        Reason(String reasonCode) {
            this.reasonCode = reasonCode;
        }

        String reasonCode() {
            return reasonCode;
        }
    }

    /** What the gates say about one order. */
    sealed interface Decision permits Pass, Held, Unenforceable {}

    /** Neither gate applies, or neither holds this order: it is accepted as the mode says. */
    record Pass() implements Decision {}

    /** A gate holds the order: it follows the document's approval path. */
    record Held(Reason reason) implements Decision {}

    /** A gate would hold the order but the document has no approval path to hold it on. */
    record Unenforceable(Reason reason) implements Decision {}

    private static final Pass PASS = new Pass();

    private final ConfigurationResolver configuration;
    private final JdbcOrderStore orders;
    private final MeterRegistry meters;

    AutoAcceptGate(ConfigurationResolver configuration, JdbcOrderStore orders, MeterRegistry meters) {
        this.configuration = configuration;
        this.orders = orders;
        this.meters = meters;
    }

    Decision decide(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String channelCode,
            @Nullable UUID customerAccountId,
            OrderAcceptancePolicy policy) {

        if (policy.mode() != AcceptanceMode.AUTO_CONFIRM) {
            return PASS;
        }
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);
        Reason reason = channelGate(scope, channelCode);
        if (reason == null) {
            reason = priorOrdersGate(scope, tenantId, brandId, customerAccountId);
        }
        if (reason == null) {
            return PASS;
        }
        if (!policy.hasApprovalPath()) {
            meters.counter(METRIC, "outcome", "unenforceable", "reason", reason.name())
                    .increment();
            log.warn(
                    "An auto-accept gate ({}) would hold an order at location {} but the acceptance policy has no "
                            + "approval channel to hold it on; the order is auto-confirmed",
                    reason,
                    locationId);
            return new Unenforceable(reason);
        }
        meters.counter(METRIC, "outcome", "held", "reason", reason.name()).increment();
        return new Held(reason);
    }

    private @Nullable Reason channelGate(ResourceScope scope, String channelCode) {
        String configured = configuration.value(OrderingConfigurationKeys.AUTO_ACCEPT_ELIGIBLE_CHANNELS, scope);
        if (configured == null || configured.isBlank() || ALL_CHANNELS.equalsIgnoreCase(configured.trim())) {
            return null;
        }
        Set<String> eligible = Arrays.stream(configured.split(","))
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return eligible.contains(channelCode.trim().toUpperCase(Locale.ROOT)) ? null : Reason.CHANNEL_NOT_ELIGIBLE;
    }

    private @Nullable Reason priorOrdersGate(
            ResourceScope scope, UUID tenantId, UUID brandId, @Nullable UUID customerAccountId) {
        Integer minimum = configuration.value(OrderingConfigurationKeys.AUTO_ACCEPT_MIN_PRIOR_ORDERS, scope);
        if (minimum == null || minimum <= 0) {
            return null;
        }
        long prior =
                customerAccountId == null ? 0L : orders.countCompletedForCustomer(tenantId, brandId, customerAccountId);
        return prior < minimum ? Reason.TOO_FEW_PRIOR_ORDERS : null;
    }
}
