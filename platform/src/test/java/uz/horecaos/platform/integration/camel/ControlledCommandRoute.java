package uz.horecaos.platform.integration.camel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;

/**
 * The ADR 0007 controlled route, in test sources only.
 *
 * <p>Its purpose is to prove the whole path end to end — a versioned command
 * arriving on a Kafka record, through the ADR 0005 inbox, out to a provider
 * through Camel, and back as a canonical result written to the ADR 0004 outbox —
 * against a provider that can be asked to fail. No production route can be used
 * for that, because no production provider can be asked to accept a command and
 * then lose the reply.
 *
 * <p>It is not a production route and never becomes one. ADR 0007 forbids a
 * scenario switch in production provider code, and the safest way to honour that
 * is for this class not to ship: the fake, the scenario header, and this route
 * all live in test sources.
 *
 * <p>The shape deliberately mirrors {@code DeliveryRouteBuilder}: classify,
 * reconcile an uncertain outcome rather than repeat it, and dead-letter anything
 * that escaped classification. A test route that took shortcuts the real routes
 * do not would prove nothing about them.
 */
public final class ControlledCommandRoute extends RouteBuilder {

    public static final String COMMAND_ENDPOINT = "direct:controlled.command";
    static final String RECONCILE_ENDPOINT = "direct:controlled.reconcile";
    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    private final String baseUrl;
    private final ProviderExceptionClassifier classifier;
    private final HttpClient http;

    public ControlledCommandRoute(String baseUrl, ProviderExceptionClassifier classifier) {
        this.baseUrl = baseUrl;
        this.classifier = classifier;
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("controlled.command.dead-letter")
                .handled(true)
                // Zero redeliveries, as on every real route: anything arriving
                // here escaped classification, so nobody can say whether the
                // provider acted, and a retry would be a guess with a side effect.
                .maximumRedeliveries(0)
                .process(this::deadLetter);

        from(COMMAND_ENDPOINT)
                .routeId("controlled.command.v1")
                .description("Calls the controlled fake provider for one command")
                .process(this::invoke)
                .choice()
                .when(exchange -> outcome(exchange).requiresReconciliation())
                .to(RECONCILE_ENDPOINT)
                .end()
                .process(exchange -> exchange.getIn().setBody(outcome(exchange)));

        from(RECONCILE_ENDPOINT)
                .routeId("controlled.reconcile.v1")
                .description("Discovers the true state after an uncertain outcome")
                .process(this::reconcile);
    }

    private void invoke(Exchange exchange) {
        ControlledCommand command = exchange.getIn().getBody(ControlledCommand.class);
        String idempotencyKey = command.commandId().toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/provider/commands"))
                .header(ControlledFakeProvider.SCENARIO_HEADER, command.scenario())
                .header(ControlledFakeProvider.IDEMPOTENCY_HEADER, idempotencyKey)
                .header("Content-Type", "application/json")
                .timeout(READ_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"controlled\"}"))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Duration retryAfter = response.headers()
                    .firstValue("Retry-After")
                    .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                    .orElse(null);
            exchange.getIn()
                    .setHeader(OUTCOME_HEADER, classifier.classify(response.statusCode(), response.body(), retryAfter));
        } catch (Exception failure) {
            // Sent is the default, and only a connect-phase failure proves
            // otherwise. Assuming "sent" costs one status query; assuming "not
            // sent" costs a duplicate side effect.
            exchange.getIn().setHeader(OUTCOME_HEADER, classifier.classify(failure, true));
        }
    }

    /**
     * Asks the provider what it already did. Never repeats the command — that is
     * the entire difference between reconciliation and a retry.
     */
    private void reconcile(Exchange exchange) {
        ControlledCommand command = exchange.getIn().getBody(ControlledCommand.class);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/provider/status?key=" + command.commandId()))
                .timeout(READ_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                exchange.getIn()
                        .setHeader(
                                OUTCOME_HEADER,
                                ProviderOutcome.success(Map.of("reconciled", true), reference(response.body())));
                return;
            }
            // The provider has no record of it, so nothing was acted on and
            // sending it again is safe.
            exchange.getIn()
                    .setHeader(
                            OUTCOME_HEADER,
                            ProviderOutcome.retryable(
                                    "NOT_ACCEPTED", "The provider has no record of this command", null));
        } catch (Exception failure) {
            // Still uncertain, and still not a reason to re-send the command.
            exchange.getIn().setHeader(OUTCOME_HEADER, classifier.classify(failure, true));
        }
    }

    private void deadLetter(Exchange exchange) {
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        exchange.getIn()
                .setHeader(
                        OUTCOME_HEADER,
                        ProviderOutcome.uncertain(
                                "UNCLASSIFIED",
                                cause == null
                                        ? "Unknown route failure"
                                        : cause.getClass().getSimpleName()));
        exchange.getIn().setBody(outcome(exchange));
    }

    private static String reference(String body) {
        int start = body.indexOf("\"externalReference\":\"");
        if (start < 0) {
            return null;
        }
        int from = start + "\"externalReference\":\"".length();
        return body.substring(from, body.indexOf('"', from));
    }

    private static ProviderOutcome outcome(Exchange exchange) {
        return exchange.getIn().getHeader(OUTCOME_HEADER, ProviderOutcome.class);
    }

    /** One provider-neutral command, as a domain port would send it. */
    public record ControlledCommand(UUID commandId, UUID tenantId, String scenario) {}
}
