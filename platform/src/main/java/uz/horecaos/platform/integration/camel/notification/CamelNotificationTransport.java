package uz.horecaos.platform.integration.camel.notification;

import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;

/**
 * {@link NotificationTransport} over the ADR 0007 route.
 *
 * <p>This class is the whole reason the notifications module compiles without
 * Camel on its classpath, which {@code ModularArchitectureTests} enforces: the
 * domain names a send, and the translation into an exchange, a route, and a
 * provider outcome happens here.
 *
 * <p>Nothing is thrown for a provider failure. A route that dead-letters produces
 * an uncertain outcome, and the module reconciles it — the distinction between
 * "not sent" and "possibly sent" is the decision this module makes next, and an
 * exception would erase it.
 */
@Component
public class CamelNotificationTransport implements NotificationTransport {

    private final ProducerTemplate producer;
    private final NotificationGateway gateway;

    public CamelNotificationTransport(ProducerTemplate producer, NotificationGateway gateway) {
        this.producer = producer;
        this.gateway = gateway;
    }

    @Override
    public DispatchOutcome dispatch(NotificationDispatch dispatch) {
        return translate(send(NotificationRouteBuilder.SEND_ENDPOINT, NotificationSendOperation.send(dispatch)));
    }

    @Override
    public DispatchOutcome reconcile(
            UUID tenantId, UUID brandId, UUID locationId, String channel, String providerIdempotencyKey) {
        ProviderOutcome outcome = send(
                NotificationRouteBuilder.STATUS_ENDPOINT,
                NotificationSendOperation.queryStatus(tenantId, brandId, locationId, channel, providerIdempotencyKey));

        if (outcome.status() == ProviderOutcome.Status.REJECTED && isNotFound(outcome)) {
            // The gateway has no record of this key, so it never acted. Reported as
            // retryable rather than rejected, because that is the one answer that
            // makes a second send safe — and the only one that should.
            return DispatchOutcome.retryable(
                    "PROVIDER_HAS_NO_RECORD", "The provider has no record of this request", null);
        }
        return translate(outcome);
    }

    @Override
    public boolean supports(String channel) {
        return gateway.supports(channel);
    }

    private ProviderOutcome send(String endpoint, NotificationSendOperation operation) {
        // The whole exchange rather than a body, because the outcome travels as a
        // header: the route's dead-letter path replaces the body, and reading the
        // body would erase the very classification the caller needs.
        Exchange result = producer.request(endpoint, exchange -> {
            exchange.getIn().setBody(operation);
            exchange.getIn().setHeader(NotificationProcessor.OPERATION_HEADER, operation);
        });

        ProviderOutcome outcome =
                result.getMessage().getHeader(NotificationRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);

        return outcome == null
                // A route that returned without classifying anything cannot tell us
                // whether the gateway acted, so this is uncertain rather than
                // retryable. Assuming the safe-looking answer here is how a
                // configuration mistake becomes duplicate messages.
                ? ProviderOutcome.uncertain(
                        "ROUTE_PRODUCED_NO_OUTCOME", "The route returned without classifying the call")
                : outcome;
    }

    private static boolean isNotFound(ProviderOutcome outcome) {
        return SmsGatewayAdapter.NO_RECORD.equals(outcome.errorCode());
    }

    /**
     * ADR 0007's four outcomes onto ADR 0020's four.
     *
     * <p>Restated rather than shared, so the notifications module describes its own
     * domain without importing an integration type. The mapping is one to one and
     * must stay so: collapsing uncertain into retryable is the failure both
     * vocabularies exist to prevent.
     */
    private static DispatchOutcome translate(ProviderOutcome outcome) {
        DispatchOutcome translated =
                switch (outcome.status()) {
                    case SUCCESS ->
                        DispatchOutcome.accepted(
                                outcome.externalReference(), text(outcome.normalized(), "providerStatus"));
                    case REJECTED -> DispatchOutcome.rejected(outcome.errorCode(), outcome.detail());
                    case RETRYABLE ->
                        DispatchOutcome.retryable(
                                outcome.errorCode(),
                                outcome.detail(),
                                outcome.retryDelay().orElse(null));
                    case UNCERTAIN -> DispatchOutcome.uncertain(outcome.errorCode(), outcome.detail());
                };

        String bindingId = text(outcome.normalized(), NotificationGateway.BINDING_ID_KEY);
        return bindingId == null
                ? translated
                : translated.from(
                        UUID.fromString(bindingId), text(outcome.normalized(), NotificationGateway.PROVIDER_TYPE_KEY));
    }

    private static String text(Map<String, Object> normalized, String key) {
        Object value = normalized.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
