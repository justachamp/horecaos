package uz.horecaos.platform.integration.camel.notification;

import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * The notification provider route (ADR 0007, ADR 0020), described by
 * {@code docs/routes/notification-send.md}.
 *
 * <p>Camel earns its place here for the same reason it does on the delivery route:
 * it holds the policies that would otherwise be copied into every adapter —
 * dead-lettering, and the rule that an uncertain outcome is reconciled rather than
 * repeated.
 *
 * <p>What this route deliberately does <em>not</em> do is retry a send. ADR 0020
 * treats a notification as a durable row with its own attempt counter and backoff,
 * and a second retry policy inside the route would multiply against it: eight
 * database attempts times three redeliveries is twenty-four messages to one
 * customer. The route calls once and reports; the module decides what happens next.
 *
 * <p>The status query is the exception, and safely so: it has no side effect, so
 * repeating it cannot text anybody.
 */
@Component
public class NotificationRouteBuilder extends RouteBuilder {

    /** Notifications sends here. The only entry point to a messaging gateway. */
    public static final String SEND_ENDPOINT = "direct:notification.send";

    /** Where an uncertain outcome is resolved. Queries; never repeats the send. */
    public static final String STATUS_ENDPOINT = "direct:notification.status";

    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private final NotificationProcessor processor;

    public NotificationRouteBuilder(NotificationProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("notification.send.dead-letter")
                .handled(true)
                // Anything reaching here escaped classification, which means we
                // cannot say whether the gateway acted. It becomes an uncertain
                // outcome for the module to reconcile rather than a retry that
                // could text the customer twice.
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(SEND_ENDPOINT)
                .routeId("notification.send.v1")
                .description("Sends one rendered message through the bound provider")
                .process(processor::restoreContext)
                .process(processor::validate)
                .process(processor::invoke)
                .process(processor::clearContext);

        from(STATUS_ENDPOINT)
                .routeId("notification.status.v1")
                .description("Discovers the true state after an uncertain send")
                // Redelivery is safe here and nowhere else in this route: a query
                // has no side effect, so repeating it cannot send a second message.
                .onException(Exception.class)
                .maximumRedeliveries(3)
                .redeliveryDelay(Duration.ofSeconds(2).toMillis())
                .backOffMultiplier(2)
                .handled(false)
                .end()
                .process(processor::restoreContext)
                .process(processor::invoke)
                .process(processor::clearContext);
    }
}
