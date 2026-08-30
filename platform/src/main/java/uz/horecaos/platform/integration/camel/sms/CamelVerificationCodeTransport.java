package uz.horecaos.platform.integration.camel.sms;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.spi.VerificationCodeTransport;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * {@link VerificationCodeTransport} over the ADR 0007 route (ADR 0015, ADR 0020).
 *
 * <p>This class is the reason the customers module compiles without Camel, Jackson
 * or an HTTP client on its classpath, which {@code ModularArchitectureTests}
 * enforces: customers names a code and a destination, and the translation into an
 * exchange, a route, a provider account and a wire format happens here.
 *
 * <p><strong>Its existence is what lets a non-local profile start.</strong>
 * {@code VerificationTransportGuard} refuses to boot without a bean implementing
 * the port, deliberately, so that "nobody wired the SMS gateway" cannot look like
 * "the SMS gateway is working". This is that bean, and it is registered
 * unconditionally rather than behind a property: whether a <em>particular tenant</em>
 * has a gateway is an ADR 0026 binding question answered at call time, and
 * answered loudly — {@code NO_PROVIDER_BINDING} — rather than by the application
 * declining to start for everybody.
 *
 * <p><strong>Nothing here throws for a provider failure</strong>, per the port's
 * contract: whether the challenge is kept or torn down is the caller's decision
 * to make from the outcome, and an exception would take that decision away.
 *
 * <p>Only the reason code crosses back. Never the provider's detail: this gateway
 * echoes what it was sent inside an error, and what it was sent is a phone number
 * and a live one-time code, which ADR 0029 keeps out of the ADR 0031 problem
 * document the caller turns this into.
 */
@Component
public class CamelVerificationCodeTransport implements VerificationCodeTransport {

    private static final Logger log = LoggerFactory.getLogger(CamelVerificationCodeTransport.class);

    /** The route never started, so no provider was contacted. See the route README. */
    static final String ROUTE_UNAVAILABLE = "SMS_ROUTE_UNAVAILABLE";

    private final ProducerTemplate producer;

    public CamelVerificationCodeTransport(ProducerTemplate producer) {
        this.producer = producer;
    }

    @Override
    public Outcome send(VerificationMessage message) {
        if (message.channel() != ContactChannel.SMS) {
            // The port names one channel today. A second one would need its own
            // adapter, and answering "refused" is better than sending an e-mail
            // address to an SMS gateway.
            return Outcome.refused("CHANNEL_UNSUPPORTED");
        }

        SmsVerificationOperation operation = SmsVerificationOperation.send(
                message, VerificationCodeText.render(message.code(), message.validFor(), message.locale()));

        return translate(dispatch(operation));
    }

    private ProviderOutcome dispatch(SmsVerificationOperation operation) {
        try {
            // The whole exchange rather than a body, because the outcome travels
            // as a header: the dead-letter path replaces the body, and reading the
            // body would erase the very classification the caller needs.
            Exchange result = producer.request(
                    SmsRouteBuilder.SEND_ENDPOINT, exchange -> exchange.getIn().setBody(operation));

            ProviderOutcome outcome =
                    result.getMessage().getHeader(SmsRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);

            if (outcome == null && result.getException() != null) {
                // ProducerTemplate.request attaches a failure to the exchange
                // rather than throwing it, so this branch is the ordinary one when
                // the route never started — not an exotic case. Read explicitly,
                // because the alternative reading is "the route ran and classified
                // nothing", which is a different and much worse answer.
                return unreachable(operation, result.getException());
            }

            return outcome == null
                    // A route that returned without classifying anything cannot say
                    // whether the gateway acted. Uncertain rather than retryable:
                    // assuming the comfortable answer here is how a wiring mistake
                    // becomes duplicate messages.
                    ? ProviderOutcome.uncertain(
                            "ROUTE_PRODUCED_NO_OUTCOME", "The route returned without classifying the call")
                    : outcome;

        } catch (RuntimeException failure) {
            return unreachable(operation, failure);
        }
    }

    /**
     * The route was never entered.
     *
     * <p>Almost always "no consumers available": the route failed to build at
     * startup, so nothing was sent and there is nothing to reconcile. That is the
     * one thing this case has going for it, and it is why it is reported as
     * unavailable rather than as uncertainty — it is also the failure
     * {@code CamelRouteHealthIndicator} exists to tell apart from a provider
     * outage, because the two have opposite fixes.
     *
     * <p>The exception's class name only. Camel wraps the exchange into the
     * message of the exception it throws, and the exchange body is a phone number
     * and a live one-time code.
     */
    private static ProviderOutcome unreachable(SmsVerificationOperation operation, Throwable failure) {
        log.error(
                "The verification route could not be reached for challenge {}: {}",
                operation.challengeId(),
                failure.getClass().getSimpleName());
        return ProviderOutcome.retryable(ROUTE_UNAVAILABLE, failure.getClass().getSimpleName(), null);
    }

    /**
     * ADR 0007's four outcomes onto the port's three.
     *
     * <p>The port has no "uncertain", and that is deliberate on its side: a
     * repeated verification message carries the same code from the same single-use
     * challenge, so a duplicate is not a second effect the way a duplicate order
     * confirmation is. It does not follow that this adapter may resend — the cost
     * the port is accepting is one extra SMS, not an unbounded number of them, and
     * the customer's per-destination budget is what bounds it.
     *
     * <p>So an uncertain send has already been through {@code /search} by the time
     * it reaches here, and what arrives is uncertainty that survived the search.
     * It becomes {@code UNAVAILABLE}: the caller tears down the challenge and the
     * customer is invited to ask again, which produces a fresh challenge and a
     * fresh code rather than a second copy of this one.
     */
    private static Outcome translate(ProviderOutcome outcome) {
        String reason = outcome.errorCode();
        return switch (outcome.status()) {
            case SUCCESS -> Outcome.accepted();
            case REJECTED -> Outcome.refused(reason);
            case RETRYABLE, UNCERTAIN -> Outcome.unavailable(reason);
        };
    }
}
