package uz.horecaos.platform.integration.camel.sms;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The verification route's steps, as plain Java (ADR 0007).
 *
 * <p>Here rather than in the route DSL so they are unit-testable without a Camel
 * context, and so the route reads as policy — never repeat a send, always resolve
 * an uncertain one — instead of as logic.
 *
 * <p>Nothing in this class logs, counts, or puts on the MDC anything that
 * identifies the customer. The tenant, the challenge id and our own reason code
 * are the whole vocabulary: the destination is a phone number and the text is a
 * live one-time code, and ADR 0029 keeps both out of every log, metric, trace and
 * dead-letter summary. The challenge id is safe because it is a random identifier
 * this platform minted and holds nothing about the person.
 */
@Component
public class SmsProcessor {

    /**
     * The reason codes that mean somebody has to do something, tonight.
     *
     * <p>{@code PROVIDER_AUTHENTICATION} is {@code 13 wrong key} surviving the one
     * refresh past the ADR 0028 secret cache, which means the key was rotated in
     * the provider's console and never written to OpenBao. {@code SMS_SPAM_LIMIT}
     * is {@code 1 spam}: the provider allows fifty an hour per number per partner
     * and our own OTP budget is five, so it is unreachable by a working limiter —
     * seeing it means the limiter is broken or somebody else is sending on this
     * account. Neither is a case for backing off and trying later.
     */
    private static final java.util.Set<String> ALARMS = java.util.Set.of(
            "PROVIDER_AUTHENTICATION",
            "SMS_SPAM_LIMIT",
            "SMS_SENDER_NOT_REGISTERED",
            "SMS_ACCOUNT_MISCONFIGURED",
            "SMS_TEXT_TOO_LONG",
            "SMS_PROVIDER_UNSUPPORTED");

    private static final Logger log = LoggerFactory.getLogger(SmsProcessor.class);

    private final SmsGateway gateway;
    private final MeterRegistry meters;

    public SmsProcessor(SmsGateway gateway, MeterRegistry meters) {
        this.gateway = gateway;
        this.meters = meters;
    }

    /**
     * Restores tenant and challenge context onto the MDC.
     *
     * <p>Without it a route log line cannot be tied to the request that caused it.
     * The destination and the code are deliberately not among them: an MDC value
     * reaches every log line the call produces.
     */
    public void restoreContext(Exchange exchange) {
        SmsVerificationOperation operation = operation(exchange);
        MDC.put("tenantId", operation.tenantId().toString());
        MDC.put("challengeId", operation.challengeId().toString());
    }

    public void send(Exchange exchange) {
        SmsVerificationOperation operation = operation(exchange);
        ProviderOutcome outcome = gateway.send(operation);
        count("send", outcome);
        exchange.getIn().setHeader(SmsRouteBuilder.OUTCOME_HEADER, outcome);
    }

    /**
     * Whether the send left us unable to say if an SMS went out.
     *
     * <p>The branch this drives is the alternative to a retry. Everything else —
     * accepted, refused, or a transport fault that proves nothing left the process
     * — is already a final answer.
     */
    public boolean isUncertain(Exchange exchange) {
        ProviderOutcome outcome = outcome(exchange);
        return outcome != null && outcome.requiresReconciliation();
    }

    /**
     * Asks the gateway what it holds, and replaces the uncertain outcome with what
     * comes back.
     *
     * <p>A search that is itself uncertain leaves the uncertainty in place rather
     * than escalating it, which is the honest answer: we still do not know. What
     * the transport does with that is refuse the issuance, so the customer is
     * asked to try again and gets a fresh challenge — never a second copy of this
     * one.
     */
    public void resolve(Exchange exchange) {
        SmsVerificationOperation operation = operation(exchange);
        ProviderOutcome resolved = gateway.resolve(operation.resolving());
        count("resolve", resolved);

        log.warn(
                "An uncertain verification send for challenge {} resolved as {} ({})",
                operation.challengeId(),
                resolved.status(),
                resolved.errorCode());
        exchange.getIn().setHeader(SmsRouteBuilder.OUTCOME_HEADER, resolved);
    }

    public void recordOutcome(Exchange exchange) {
        SmsVerificationOperation operation = operation(exchange);
        ProviderOutcome outcome = outcome(exchange);
        String reason = outcome == null ? "NONE" : outcome.errorCode();

        if (reason != null && ALARMS.contains(reason)) {
            // ADR 0023. Loud on purpose and at ERROR: every code in this set is a
            // configuration or credential fault that no amount of waiting fixes,
            // and each one stops every customer of that tenant from signing in.
            log.error(
                    "The SMS gateway refused a verification code for tenant {} as {}. "
                            + "This will not resolve on its own; see docs/routes/sms-verification.md",
                    operation.tenantId(),
                    reason);
        }
        clearContext();
    }

    /**
     * Anything that escaped classification.
     *
     * <p>Uncertain rather than retryable, always. Reaching here means we cannot
     * say whether the gateway acted, and this provider has no key under which a
     * second attempt would collapse into the first.
     */
    public void deadLetter(Exchange exchange) {
        SmsVerificationOperation operation = operation(exchange);
        Throwable failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);

        // The class name only. A provider exception message can echo the request
        // back, and this request holds a phone number and a live one-time code.
        String detail = failure == null ? "unknown" : failure.getClass().getSimpleName();
        ProviderOutcome outcome = ProviderOutcome.uncertain("ROUTE_FAILURE", detail);

        log.error(
                "The verification route failed for challenge {}: {}",
                operation == null ? "unknown" : operation.challengeId(),
                detail);
        count("dead_letter", outcome);
        exchange.getIn().setHeader(SmsRouteBuilder.OUTCOME_HEADER, outcome);
        clearContext();
    }

    private void count(String step, ProviderOutcome outcome) {
        // Bounded tags only. A tenant id or a challenge id here would make the
        // cardinality unbounded and eventually take the registry down.
        meters.counter(
                        "horecaos.sms.verification.calls",
                        "step",
                        step,
                        "status",
                        outcome == null ? "NONE" : outcome.status().name(),
                        "reason",
                        outcome == null || outcome.errorCode() == null ? "none" : outcome.errorCode())
                .increment();
    }

    private static void clearContext() {
        MDC.remove("tenantId");
        MDC.remove("challengeId");
    }

    private static ProviderOutcome outcome(Exchange exchange) {
        return exchange.getIn().getHeader(SmsRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
    }

    private static SmsVerificationOperation operation(Exchange exchange) {
        return exchange.getIn().getBody(SmsVerificationOperation.class);
    }
}
