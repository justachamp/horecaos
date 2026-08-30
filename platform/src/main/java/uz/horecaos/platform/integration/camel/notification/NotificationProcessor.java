package uz.horecaos.platform.integration.camel.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The notification route's steps, as plain Java (ADR 0007).
 *
 * <p>They live here rather than in the route DSL so they can be unit-tested
 * without a Camel context, and so the route reads as policy — dead-lettering and
 * the rule that an uncertain outcome is never repeated — instead of as logic.
 */
@Component
public class NotificationProcessor {

    static final String OPERATION_HEADER = "HorecaOSNotificationOperation";

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final NotificationGateway gateway;
    private final MeterRegistry meters;

    public NotificationProcessor(NotificationGateway gateway, MeterRegistry meters) {
        this.gateway = gateway;
        this.meters = meters;
    }

    /**
     * Restores tenant and channel context onto the MDC.
     *
     * <p>Without this a route log line cannot be tied to the message that caused
     * it, which is the difference between a five-minute and a five-hour incident.
     * The recipient and the body are deliberately not among them.
     */
    public void restoreContext(Exchange exchange) {
        NotificationSendOperation operation = operation(exchange);
        MDC.put("tenantId", operation.tenantId().toString());
        MDC.put("channel", operation.channel());
    }

    public void clearContext(Exchange exchange) {
        MDC.remove("tenantId");
        MDC.remove("channel");
    }

    /** Contract validation before mapping, per ADR 0007 route rule 2. */
    public void validate(Exchange exchange) {
        NotificationSendOperation operation = operation(exchange);
        if (!gateway.supports(operation.channel())) {
            // Set as an outcome rather than thrown: an unsupported channel is an
            // answer for the notifications module, not a route failure for an
            // engineer, and throwing would dead-letter it into a human's queue.
            exchange.getIn()
                    .setHeader(
                            NotificationRouteBuilder.OUTCOME_HEADER,
                            ProviderOutcome.rejected(
                                    "CHANNEL_UNSUPPORTED", "No adapter is registered for " + operation.channel()));
            exchange.setRouteStop(true);
        }
    }

    public void invoke(Exchange exchange) {
        NotificationSendOperation operation = operation(exchange);

        ProviderOutcome outcome =
                switch (operation.kind()) {
                    case SEND -> gateway.send(operation.dispatch());
                    case QUERY_STATUS ->
                        gateway.queryStatus(
                                operation.tenantId(),
                                operation.brandId(),
                                operation.locationId(),
                                operation.channel(),
                                operation.providerIdempotencyKey());
                };

        count(operation, outcome);
        exchange.getIn().setHeader(NotificationRouteBuilder.OUTCOME_HEADER, outcome);
    }

    /**
     * Anything that escaped classification.
     *
     * <p>Turned into an uncertain outcome rather than a retry. Reaching here means
     * we cannot say whether the gateway acted, and a message we might already have
     * sent must be reconciled by the notifications module rather than repeated by
     * the route.
     */
    public void deadLetter(Exchange exchange) {
        Throwable failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        NotificationSendOperation operation = operation(exchange);

        // The class name only. A provider exception message can echo the request
        // back, and the request holds a phone number and the text of the message.
        String detail = failure == null ? "unknown" : failure.getClass().getSimpleName();
        log.error(
                "Notification route failed for tenant {} on {}: {}",
                operation == null ? "unknown" : operation.tenantId(),
                operation == null ? "unknown" : operation.channel(),
                detail);

        exchange.getIn()
                .setHeader(NotificationRouteBuilder.OUTCOME_HEADER, ProviderOutcome.uncertain("ROUTE_FAILURE", detail));
    }

    private void count(NotificationSendOperation operation, ProviderOutcome outcome) {
        Counter.builder("horecaos.notifications.provider.calls")
                .description("ADR 0020 notification provider outcomes")
                .tag("channel", operation.channel())
                .tag("kind", operation.kind().name())
                .tag("outcome", outcome.status().name())
                .register(meters)
                .increment();
    }

    private static NotificationSendOperation operation(Exchange exchange) {
        return exchange.getIn().getHeader(OPERATION_HEADER, NotificationSendOperation.class);
    }
}
