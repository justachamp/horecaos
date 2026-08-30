package uz.qoida.platform.integration.camel.sms;

import java.time.Duration;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * The verification-code route (ADR 0007, ADR 0015), described by
 * {@code docs/routes/sms-verification.md}.
 *
 * <p>Separate from {@code notification.send.v1} rather than folded into it, for
 * three reasons that are all about consequences. The command is a different type
 * from a different module — customers cannot depend on notifications without
 * making the two cyclic, which is why {@code customers.spi} exists at all. The
 * reconciliation is a different question: notifications asks the gateway about an
 * idempotency key, and this provider has none, so the question here is "what did
 * you send to this number today". And the failure semantics are opposite: an
 * undelivered order confirmation is an inconvenience, while an undelivered code
 * means a customer cannot sign in at all, so this route resolves and reports
 * rather than leaving a durable row to be retried by a worker.
 *
 * <p><strong>The send never retries.</strong> There is no idempotency key on
 * {@code /send}, so a redelivery is a second SMS to a real person's phone, and
 * ADR 0007's own rule is that Camel redelivery is safe only for an operation
 * proven safe under one key. What replaces retry is the branch below: an
 * uncertain send goes to {@code /search} to discover what the gateway already
 * has.
 *
 * <p><strong>The search retries and safely so.</strong> It creates nothing, so
 * repeating it cannot text anybody — the same exception the notification route
 * makes for its status query.
 */
@Component
public class SmsRouteBuilder extends RouteBuilder {

    /**
     * Customers sends here, through the transport. The only entry to this gateway.
     *
     * <p>{@code block=false} is load-bearing. A {@code direct:} producer whose
     * consumer never started blocks for thirty seconds by default, and the thread
     * it blocks is the one serving a customer who is looking at a sign-up form —
     * so a route that failed to build at boot would turn into an estate-wide
     * request-thread exhaustion rather than into a fast, legible refusal. Failing
     * at once gives the transport {@code SMS_ROUTE_UNAVAILABLE}, which
     * {@code CamelRouteHealthIndicator} and the route README already explain how
     * to act on.
     */
    public static final String SEND_ENDPOINT = "direct:sms.verification.send?block=false";

    /** Where an uncertain send is resolved. Queries; never repeats the send. */
    public static final String SEARCH_ENDPOINT = "direct:sms.verification.search?block=false";

    static final String OUTCOME_HEADER = "QoidaProviderOutcome";

    private final SmsProcessor processor;

    public SmsRouteBuilder(SmsProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("sms.verification.dead-letter")
                .handled(true)
                // Zero, explicitly. Anything reaching here escaped classification,
                // so nobody can say whether an SMS left. It becomes an uncertain
                // outcome the transport turns into a refusal to the customer, and
                // never a second message.
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(SEND_ENDPOINT)
                .routeId("sms.verification.send.v1")
                .description("Sends one verification code through the bound SMS gateway")
                .process(processor::restoreContext)
                .process(processor::send)
                // The whole point of the route. An uncertain send is resolved by
                // asking the provider what it holds, in the same exchange, so the
                // caller gets one answer rather than a promise to find out later:
                // the customer is standing at a form waiting for a code.
                .choice()
                    .when(processor::isUncertain)
                        .to(SEARCH_ENDPOINT)
                .end()
                .process(processor::recordOutcome);

        from(SEARCH_ENDPOINT)
                .routeId("sms.verification.search.v1")
                .description("Discovers what the gateway holds after an uncertain send")
                // Redelivery is safe here and nowhere else on this route: a search
                // has no side effect, so repeating it cannot send a second code.
                // Bounded tightly because a customer is waiting on the answer.
                .onException(Exception.class)
                    .maximumRedeliveries(2)
                    .redeliveryDelay(Duration.ofSeconds(1).toMillis())
                    .backOffMultiplier(2)
                    .handled(false)
                    .end()
                .process(processor::resolve);
    }
}
