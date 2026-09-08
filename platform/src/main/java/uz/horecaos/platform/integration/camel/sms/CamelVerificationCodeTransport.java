package uz.horecaos.platform.integration.camel.sms;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.api.CustomerConfigurationKeys;
import uz.horecaos.platform.customers.spi.VerificationCodeTransport;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.provider.telegramgateway.TelegramGatewayClient;
import uz.horecaos.platform.integration.provider.telegramgateway.TelegramGatewayVerificationOperation;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

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
 *
 * <p><strong>ADR 0063's delivery-policy seam lives here.</strong> This is the one
 * place every verification message already funnels through, so it is where the
 * two channels are ordered rather than a third place that has to agree with
 * both. Which channel goes first is no longer a Java conditional: the owner's
 * 2026-09-08 direction is that platform configuration belongs at the control
 * plane, so the order is {@link CustomerConfigurationKeys#OTP_DELIVERY_CHANNEL_ORDER},
 * an ADR 0030 value resolved fresh on every send. The default,
 * {@code TELEGRAM_GATEWAY,SMS}, is ADR 0063's own decision — Gateway is
 * attempted only when {@link TelegramGatewayClient#isConfigured()} (an
 * unconfigured deployment never pays a network round trip finding that out),
 * and only a channel's <em>refusal</em> (a business rejection, an unreachable
 * provider, a rate limit, or an answer too uncertain to trust — see {@link
 * TelegramGatewayClient#sendVerificationMessage} for what "uncertain" means
 * on the Gateway side) falls through to the next channel in the order; a
 * channel's {@code ACCEPTED} returns immediately and nothing further is
 * asked. Whichever value is configured, both channels stay reachable — an
 * operator can flip which one goes first, never drop one, matching ADR
 * 0063's own Alternatives table ("SMS stays the fallback... Never fully"
 * dropped).
 */
@Component
public class CamelVerificationCodeTransport implements VerificationCodeTransport {

    private static final Logger log = LoggerFactory.getLogger(CamelVerificationCodeTransport.class);

    /** The route never started, so no provider was contacted. See the route README. */
    static final String ROUTE_UNAVAILABLE = "SMS_ROUTE_UNAVAILABLE";

    static final String SMS_CHANNEL = CustomerConfigurationKeys.CHANNEL_SMS;
    static final String TELEGRAM_GATEWAY_CHANNEL = CustomerConfigurationKeys.CHANNEL_TELEGRAM_GATEWAY;

    /** Both channels, ADR 0063's own order — used when no valid override is configured. */
    private static final List<String> DEFAULT_ORDER = List.of(TELEGRAM_GATEWAY_CHANNEL, SMS_CHANNEL);

    private static final Set<String> VALID_CHANNELS = Set.of(TELEGRAM_GATEWAY_CHANNEL, SMS_CHANNEL);

    private final ProducerTemplate producer;
    private final TelegramGatewayClient gateway;
    private final ConfigurationResolver configuration;

    public CamelVerificationCodeTransport(
            ProducerTemplate producer, TelegramGatewayClient gateway, ConfigurationResolver configuration) {
        this.producer = producer;
        this.gateway = gateway;
        this.configuration = configuration;
    }

    @Override
    public Outcome send(VerificationMessage message) {
        if (message.channel() != ContactChannel.SMS) {
            // The port names one channel today. A second one would need its own
            // adapter, and answering "refused" is better than sending an e-mail
            // address to an SMS gateway.
            return Outcome.refused("CHANNEL_UNSUPPORTED");
        }

        Outcome last = null;
        for (String channel : resolveChannelOrder(message.tenantId(), message.brandId())) {
            Outcome attempt =
                    switch (channel) {
                        case TELEGRAM_GATEWAY_CHANNEL -> gateway.isConfigured() ? tryGateway(message) : null;
                        case SMS_CHANNEL -> trySms(message);
                        default -> null; // unreachable: resolveChannelOrder only ever returns VALID_CHANNELS
                    };

            if (attempt == null) {
                // Gateway named in the order but no token configured yet (ADR
                // 0063's open input): skip straight to the next channel rather
                // than spend a network round trip finding that out again.
                continue;
            }
            if (attempt.status() == Outcome.Status.ACCEPTED) {
                return attempt;
            }
            last = attempt;
        }

        // SMS is always one of the two entries resolveChannelOrder returns and
        // trySms never answers null, so `last` is set by the time the loop
        // ends. The fallback below exists only so this can never NPE if that
        // invariant is ever broken, not because it is expected to run.
        return last != null ? last : Outcome.unavailable("NO_CHANNEL_CONFIGURED");
    }

    /**
     * Which order to try the two channels in, for this tenant and brand
     * (ADR 0030, ADR 0063).
     *
     * <p>Resolved fresh on every send rather than cached here: resolution
     * itself is already cached (ADR 0033's {@code tenant.configuration},
     * sixty-second TTL), so an operator's change to the control-plane value
     * takes effect within that window without a deploy.
     *
     * <p>A configured value that is not a permutation of both channels — a
     * typo, a single channel named alone, a channel named twice — is refused
     * the same way {@code TelegramUpdateHandler} refuses a phone pattern that
     * fails to compile: logged, and the platform default is used instead,
     * rather than letting one operator mistake in the control plane stop
     * every OTP for a tenant.
     */
    private List<String> resolveChannelOrder(UUID tenantId, UUID brandId) {
        String configured = configuration
                .resolve(CustomerConfigurationKeys.OTP_DELIVERY_CHANNEL_ORDER, ResourceScope.brand(tenantId, brandId))
                .value();
        List<String> parsed = parseOrder(configured);
        if (parsed != null) {
            return parsed;
        }
        log.error(
                "Configured OTP delivery channel order \"{}\" for tenant {} brand {} is not a valid ordering of "
                        + "{} and {}; using the platform default order",
                configured,
                tenantId,
                brandId,
                SMS_CHANNEL,
                TELEGRAM_GATEWAY_CHANNEL);
        return DEFAULT_ORDER;
    }

    private static @Nullable List<String> parseOrder(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        List<String> channels = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
        if (channels.size() != VALID_CHANNELS.size() || !Set.copyOf(channels).equals(VALID_CHANNELS)) {
            return null;
        }
        return channels;
    }

    /**
     * @return the {@link Outcome} Gateway produced: {@code ACCEPTED} when it
     *         took the message, or its refusal translated for the caller to
     *         weigh against the next channel in the order
     */
    private Outcome tryGateway(VerificationMessage message) {
        ProviderOutcome outcome = gateway.sendVerificationMessage(new TelegramGatewayVerificationOperation(
                message.tenantId(), message.challengeId(), message.destination(), message.code()));

        if (outcome.status() == ProviderOutcome.Status.SUCCESS) {
            Long costMinor = longOrNull(outcome.normalized().get(TelegramGatewayClient.COST_MINOR_KEY));
            String costCurrency = stringOrNull(outcome.normalized().get(TelegramGatewayClient.COST_CURRENCY_KEY));
            return Outcome.accepted(TELEGRAM_GATEWAY_CHANNEL, outcome.externalReference(), costMinor, costCurrency);
        }

        log.info(
                "Telegram Gateway declined verification delivery for challenge {}: {}",
                message.challengeId(),
                outcome.errorCode());
        return translate(outcome);
    }

    /** SMS's own attempt, always answered — never null the way a skipped Gateway try is. */
    private Outcome trySms(VerificationMessage message) {
        SmsVerificationOperation operation = SmsVerificationOperation.send(
                message, VerificationCodeText.render(message.code(), message.validFor(), message.locale()));
        return translate(dispatch(operation));
    }

    private static @Nullable Long longOrNull(@Nullable Object value) {
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private static @Nullable String stringOrNull(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
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
            case SUCCESS -> Outcome.accepted(SMS_CHANNEL, outcome.externalReference(), null, null);
            case REJECTED -> Outcome.refused(reason);
            case RETRYABLE, UNCERTAIN -> Outcome.unavailable(reason);
        };
    }
}
