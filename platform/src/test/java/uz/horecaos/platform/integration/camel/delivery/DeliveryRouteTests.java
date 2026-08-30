package uz.horecaos.platform.integration.camel.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The route's outcome policy (ADR 0007).
 *
 * <p>These run against a real Camel context with a scripted partner, because the
 * behaviour under test is the route's — what it does <em>next</em> after each of
 * the four outcomes — and a mocked gateway would test the mock.
 */
class DeliveryRouteTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    private CamelContext camel;
    private RecordingReconciliationOutbox reconciliations;

    @AfterEach
    void stopCamel() {
        if (camel != null) {
            camel.stop();
        }
    }

    @Test
    @DisplayName("an uncertain create reconciles by query and never creates again")
    void uncertainCreateNeverRepeatsTheSideEffect() throws Exception {
        ScriptedPartner partner = new ScriptedPartner()
                .onCreate(ProviderOutcome.uncertain("READ_TIMEOUT", "no response"))
                .onQuery(ProviderOutcome.success(Map.of("state", "CONFIRMED"), "ext-1"));

        ProviderOutcome outcome = run(partner, operation(DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT, "ext-1"));

        // The whole reason UNCERTAIN exists. A second create here is a second
        // courier, a second delivery fee, and a customer receiving two orders.
        assertThat(partner.creates).isEqualTo(1);
        assertThat(partner.queries).isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(outcome.normalized()).containsEntry("state", "CONFIRMED");
    }

    @Test
    @DisplayName("an uncertain create with no reference stops for a human")
    void uncertainCreateWithoutAReferenceIsEscalated() throws Exception {
        ScriptedPartner partner =
                new ScriptedPartner().onCreate(ProviderOutcome.uncertain("READ_TIMEOUT", "no response"));

        ProviderOutcome outcome = run(partner, operation(DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT, null));

        // Nothing to query by, so there is no safe automated move. Guessing
        // either way risks a duplicate booking or a silently dropped order.
        assertThat(partner.queries).isZero();
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(outcome.errorCode()).isEqualTo("RECONCILE_MANUAL");
    }

    @Test
    @DisplayName("a business rejection is returned as-is and never retried")
    void businessRejectionIsNotRetried() throws Exception {
        ScriptedPartner partner =
                new ScriptedPartner().onCreate(ProviderOutcome.rejected("OUT_OF_ZONE", "address outside coverage"));

        ProviderOutcome outcome = run(partner, operation(DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT, "ext-1"));

        assertThat(partner.creates).isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("OUT_OF_ZONE");
    }

    @Test
    @DisplayName("a capability the partner lacks is answered without calling it")
    void unsupportedCapabilityShortCircuits() throws Exception {
        ScriptedPartner partner = new ScriptedPartner();

        ProviderOutcome outcome = run(partner, operation(DeliveryCapability.RESCHEDULE_SHIPMENT, "ext-1"));

        assertThat(partner.creates).isZero();
        assertThat(partner.queries).isZero();
        assertThat(outcome.errorCode()).isEqualTo("CAPABILITY_UNSUPPORTED");
    }

    @Test
    @DisplayName("a retryable outcome is surfaced with its backoff rather than looped in the route")
    void retryableIsSurfacedNotLooped() throws Exception {
        ScriptedPartner partner = new ScriptedPartner()
                .onQuery(ProviderOutcome.retryable(
                        "PROVIDER_ERROR_503", "unavailable", java.time.Duration.ofSeconds(10)));

        ProviderOutcome outcome = run(partner, operation(DeliveryCapability.QUERY_SHIPMENT, "ext-1"));

        // One attempt. Retry is the caller's to schedule with the same command
        // id, so the provider's own idempotency still applies on the next try.
        assertThat(partner.queries).isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.RETRYABLE);
        assertThat(outcome.retryDelay()).contains(java.time.Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a binding from another tenant is rejected before it can be routed")
    void crossTenantBindingIsRefused() {
        BindingRef otherTenant = new BindingRef(
                UUID.randomUUID(), INSTALLATION, UUID.randomUUID(), ProviderCategory.DELIVERY, "scripted", BRAND, null);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new DeliveryOperation(
                        UUID.randomUUID(),
                        TENANT,
                        otherTenant,
                        DeliveryCapability.QUERY_SHIPMENT,
                        null,
                        "ext-1",
                        null,
                        "corr-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different tenant");
    }

    @Test
    @DisplayName("repeated provider faults open the circuit and stop calling the partner")
    void providerFaultsOpenTheCircuit() throws Exception {
        ScriptedPartner partner =
                new ScriptedPartner().onQuery(ProviderOutcome.retryable("PROVIDER_ERROR_503", "unavailable", null));

        List<ProviderOutcome> outcomes =
                runMany(partner, 20, () -> operation(DeliveryCapability.QUERY_SHIPMENT, "ext-1"));

        // The point of the breaker: once a partner is clearly down, stop adding
        // load to it and stop making every order wait for a timeout first.
        assertThat(partner.queries)
                .as("the circuit should stop calls before all 20 reach the partner")
                .isLessThan(20);
        assertThat(outcomes).anyMatch(outcome -> "CIRCUIT_OPEN".equals(outcome.errorCode()));
    }

    @Test
    @DisplayName("one partner's outage does not stop calls to the other")
    void circuitsAreIndependentPerProvider() throws Exception {
        ScriptedPartner failing = new ScriptedPartner("noor-delivery")
                .onQuery(ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "down", null));
        ScriptedPartner healthy = new ScriptedPartner("yandex-delivery")
                .onQuery(ProviderOutcome.success(Map.of("state", "CONFIRMED"), "ext-1"));

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        // Enough failing calls to open the failing partner's circuit, then a run
        // against the healthy one on the same route.
        List<ProviderOutcome> outcomes = runMany(List.of(failing, healthy), 40, alternating(), meters);

        List<ProviderOutcome> healthyOutcomes = outcomes.subList(0, outcomes.size());

        // The failing partner's circuit opens and spares it further load.
        assertThat(failing.queries).isLessThan(20);
        // The healthy partner keeps taking every call. A single shared breaker —
        // which is all Camel's route-level circuitBreaker() can be — would have
        // stopped these too, turning one partner's outage into a total delivery
        // outage and sending the operator to the wrong status page.
        assertThat(healthy.queries).isEqualTo(20);
        assertThat(healthyOutcomes).anyMatch(o -> o.status() == ProviderOutcome.Status.SUCCESS);
    }

    @Test
    @DisplayName("the circuit_open metric fires only when the circuit actually refuses a call")
    void circuitOpenMetricIsNotEmittedForOrdinaryFaults() throws Exception {
        ScriptedPartner partner =
                new ScriptedPartner().onQuery(ProviderOutcome.retryable("PROVIDER_ERROR_503", "unavailable", null));

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        // Fewer calls than the breaker's minimum, so it never opens.
        runMany(List.of(partner), 5, () -> operation(DeliveryCapability.QUERY_SHIPMENT, "ext-1"), meters);

        // Previously every retryable and uncertain outcome went through the
        // fallback and was counted as circuit_open, which made the metric
        // useless for the one question it exists to answer.
        assertThat(meters.find("horecaos.delivery.route")
                        .tag("event", "circuit_open")
                        .counter())
                .isNull();
    }

    private java.util.function.Supplier<DeliveryOperation> alternating() {
        int[] index = {0};
        return () -> {
            String provider = index[0]++ % 2 == 0 ? "noor-delivery" : "yandex-delivery";
            return operationFor(provider, DeliveryCapability.QUERY_SHIPMENT, "ext-1");
        };
    }

    @Test
    @DisplayName("repeated business rejections leave the circuit closed")
    void businessRejectionsDoNotOpenTheCircuit() throws Exception {
        ScriptedPartner partner =
                new ScriptedPartner().onQuery(ProviderOutcome.rejected("OUT_OF_ZONE", "address outside coverage"));

        List<ProviderOutcome> outcomes =
                runMany(partner, 20, () -> operation(DeliveryCapability.QUERY_SHIPMENT, "ext-1"));

        // A partner declining twenty out-of-zone addresses is a partner working
        // correctly. Opening the circuit here would take a healthy courier
        // offline because customers kept asking for the wrong thing.
        assertThat(partner.queries).isEqualTo(20);
        assertThat(outcomes).allMatch(outcome -> outcome.status() == ProviderOutcome.Status.REJECTED);
    }

    @Test
    @DisplayName("an uncertain create the query cannot settle queues a durable reconciliation command")
    void anUnsettledReconciliationBecomesADurableCommand() throws Exception {
        ScriptedPartner partner = new ScriptedPartner()
                .onCreate(ProviderOutcome.uncertain("READ_TIMEOUT", "no response"))
                .onQuery(ProviderOutcome.uncertain("READ_TIMEOUT", "no response"));

        ProviderOutcome outcome = run(partner, operation(DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT, "ext-1"));

        // Before ADR 0007's reconciliation command existed, this case left a log
        // line and an outcome nobody would revisit: a courier possibly booked,
        // possibly not, and no durable record of the question. The caller still
        // learns it is unsettled; the difference is that the platform now owes
        // itself an answer and will go and get one.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(reconciliations.requested()).singleElement().satisfies(command -> {
            assertThat(command.externalReference()).isEqualTo("ext-1");
            assertThat(command.capability()).isEqualTo("CREATE_ON_DEMAND_SHIPMENT");
            assertThat(command.uncertainErrorCode()).isEqualTo("READ_TIMEOUT");
        });
        assertThat(partner.creates)
                .as("the original create is never repeated; only the query is")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unsettled query does not queue a command for itself")
    void anUnsettledQueryDoesNotQueueItself() throws Exception {
        ScriptedPartner partner =
                new ScriptedPartner().onQuery(ProviderOutcome.uncertain("READ_TIMEOUT", "no response"));

        run(partner, operation(DeliveryCapability.QUERY_SHIPMENT, "ext-1"));

        // A query deferring a query would enqueue forever, and the loop would
        // look exactly like a partner outage while being self-inflicted.
        assertThat(reconciliations.requested()).isEmpty();
    }

    private List<ProviderOutcome> runMany(
            ScriptedPartner partner, int times, java.util.function.Supplier<DeliveryOperation> operations)
            throws Exception {
        return runMany(List.of(partner), times, operations, new SimpleMeterRegistry());
    }

    private List<ProviderOutcome> runMany(
            List<DeliveryPartner> partners,
            int times,
            java.util.function.Supplier<DeliveryOperation> operations,
            SimpleMeterRegistry meters)
            throws Exception {
        DeliveryGateway gateway = new DeliveryGateway(partners, lookup(), fixedResolver());
        DeliveryProcessor processor = new DeliveryProcessor(
                gateway,
                new DeliveryCircuitBreakers(meters, Clock.systemUTC()),
                meters,
                reconciliations = new RecordingReconciliationOutbox());

        camel = new DefaultCamelContext();
        camel.addRoutes(new DeliveryRouteBuilder(processor));
        camel.start();

        List<ProviderOutcome> outcomes = new ArrayList<>();
        try (ProducerTemplate template = camel.createProducerTemplate()) {
            for (int attempt = 0; attempt < times; attempt++) {
                outcomes.add(template.request(
                                DeliveryRouteBuilder.OPERATION_ENDPOINT,
                                exchange -> exchange.getIn().setBody(operations.get()))
                        .getIn()
                        .getHeader(DeliveryRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class));
            }
        }
        return outcomes;
    }

    private ProviderOutcome run(ScriptedPartner partner, DeliveryOperation operation) throws Exception {
        DeliveryGateway gateway = new DeliveryGateway(List.of(partner), lookup(), fixedResolver());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DeliveryProcessor processor = new DeliveryProcessor(
                gateway,
                new DeliveryCircuitBreakers(meters, Clock.systemUTC()),
                meters,
                reconciliations = new RecordingReconciliationOutbox());

        camel = new DefaultCamelContext();
        camel.addRoutes(new DeliveryRouteBuilder(processor));
        camel.start();

        try (ProducerTemplate template = camel.createProducerTemplate()) {
            return template.request(
                            DeliveryRouteBuilder.OPERATION_ENDPOINT,
                            exchange -> exchange.getIn().setBody(operation))
                    .getIn()
                    .getHeader(DeliveryRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        }
    }

    private static DeliveryOperation operation(DeliveryCapability capability, String reference) {
        return operationFor("scripted", capability, reference);
    }

    private static DeliveryOperation operationFor(
            String providerType, DeliveryCapability capability, String reference) {
        BindingRef binding = new BindingRef(
                UUID.randomUUID(), INSTALLATION, TENANT, ProviderCategory.DELIVERY, providerType, BRAND, null);
        DeliveryPartner.DeliveryRequest request = new DeliveryPartner.DeliveryRequest(
                "QO-3003",
                new DeliveryPartner.Pickup(41.3, 69.2, "a", "n", "+998", null),
                new DeliveryPartner.Dropoff(41.2, 69.1, "b", "n", "+998", null, null, null, null),
                null,
                true,
                1000L,
                "UZS",
                Map.of());
        return new DeliveryOperation(
                UUID.randomUUID(), TENANT, binding, capability, request, reference, "test", "corr-1");
    }

    private static SecretResolver fixedResolver() {
        return new SecretResolver() {
            private final SecretValue value = SecretValue.of("token");

            @Override
            public SecretValue resolve(SecretReference reference) {
                return value;
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return value;
            }
        };
    }

    private static ProviderInstallationLookup lookup() {
        SecretReference reference =
                new SecretReference("local", SecretCategory.PROVIDER_DELIVERY, "tenant", "scripted");
        return new ProviderInstallationLookup() {
            @Override
            public Optional<BindingRef> primaryBinding(UUID t, UUID b, UUID l, String code) {
                return Optional.empty();
            }

            @Override
            public List<BindingRef> candidateBindings(UUID t, UUID b, UUID l, String code) {
                return List.of();
            }

            @Override
            public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
                return Optional.of(new InstallationSnapshot(
                        INSTALLATION,
                        ProviderCategory.DELIVERY,
                        "scripted",
                        "local",
                        "http://127.0.0.1:1",
                        "ACTIVE",
                        reference.toString(),
                        "v1"));
            }
        };
    }

    /** Counts each operation separately, which is what the reconciliation tests assert on. */
    private static final class ScriptedPartner implements DeliveryPartner {

        private final String providerType;
        private final List<String> log = new ArrayList<>();

        ScriptedPartner() {
            this("scripted");
        }

        ScriptedPartner(String providerType) {
            this.providerType = providerType;
        }

        private ProviderOutcome createOutcome = ProviderOutcome.success(Map.of(), "ext-1");
        private ProviderOutcome queryOutcome = ProviderOutcome.success(Map.of(), "ext-1");
        private int creates;
        private int queries;

        ScriptedPartner onCreate(ProviderOutcome outcome) {
            this.createOutcome = outcome;
            return this;
        }

        ScriptedPartner onQuery(ProviderOutcome outcome) {
            this.queryOutcome = outcome;
            return this;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public Set<DeliveryCapability> capabilities() {
            return Set.of(
                    DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT,
                    DeliveryCapability.QUERY_SHIPMENT,
                    DeliveryCapability.QUOTE_DELIVERY,
                    DeliveryCapability.CANCEL_SHIPMENT);
        }

        @Override
        public ProviderOutcome quote(DeliveryRequest request, ProviderCall call) {
            log.add("quote");
            return queryOutcome;
        }

        @Override
        public ProviderOutcome createShipment(DeliveryRequest request, ProviderCall call) {
            creates++;
            log.add("create");
            return createOutcome;
        }

        @Override
        public ProviderOutcome confirmShipment(String externalReference, ProviderCall call) {
            log.add("confirm");
            return createOutcome;
        }

        @Override
        public ProviderOutcome cancellationCost(String externalReference, ProviderCall call) {
            return queryOutcome;
        }

        @Override
        public ProviderOutcome cancelShipment(String externalReference, String reason, ProviderCall call) {
            log.add("cancel");
            return queryOutcome;
        }

        @Override
        public ProviderOutcome queryShipment(String externalReference, ProviderCall call) {
            queries++;
            log.add("query");
            return queryOutcome;
        }
    }
}
