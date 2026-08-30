package uz.horecaos.platform.integration.camel.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
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
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingIntent;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingStatus;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
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
 * The producer ADR 0014's checklist said did not exist (ADR 0007, ADR 0014).
 *
 * <p>Run against a real Camel context and a scripted partner rather than a
 * mocked route, because what is under test is the translation <em>through</em>
 * the route: a fulfilment intent going in and a classified booking coming back
 * out, with the route's own uncertainty policy in the middle.
 */
class CamelShipmentBookingPortTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID ONE_PHASE_BINDING = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID TWO_PHASE_BINDING = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private CamelContext camel;

    @AfterEach
    void stopCamel() {
        if (camel != null) {
            camel.stop();
        }
    }

    @Test
    @DisplayName("a partner is listed with the hold and scheduling facts its adapter declares")
    void partnersCarryTheirVerifiedCapabilities() throws Exception {
        ShipmentBookingPort port = port(onePhase(), twoPhase());

        List<PartnerOption> partners = port.partners(TENANT, BRAND, LOCATION);

        // The binding row records what a tenant enabled; the adapter records
        // what the partner documented. It is the second that decides whether a
        // create is a hold or a courier already on a scooter.
        assertThat(partners)
                .extracting(PartnerOption::providerType)
                .containsExactlyInAnyOrder("noor-like", "yandex-like");
        assertThat(partners)
                .filteredOn(option -> "noor-like".equals(option.providerType()))
                .singleElement()
                .returns(false, PartnerOption::supportsHold)
                .returns(true, PartnerOption::supportsScheduling);
        assertThat(partners)
                .filteredOn(option -> "yandex-like".equals(option.providerType()))
                .singleElement()
                .returns(true, PartnerOption::supportsHold);
    }

    @Test
    @DisplayName("booking now on a one-phase partner is a single live create")
    void onePhaseBookingIsOneCall() throws Exception {
        ScriptedPartner noor = onePhase();
        ShipmentBookingPort port = port(noor);

        BookingReceipt receipt = port.book(command(ONE_PHASE_BINDING, BookingIntent.BOOK_NOW));

        assertThat(receipt.status()).isEqualTo(BookingStatus.BOOKED);
        assertThat(receipt.externalReference()).isEqualTo("noor-1");
        assertThat(noor.creates).isEqualTo(1);
        assertThat(noor.confirms).isZero();
    }

    @Test
    @DisplayName("booking now on a two-phase partner reserves and then accepts")
    void twoPhaseBookingReservesThenAccepts() throws Exception {
        ScriptedPartner yandex = twoPhase();
        ShipmentBookingPort port = port(yandex);

        BookingReceipt receipt = port.book(command(TWO_PHASE_BINDING, BookingIntent.BOOK_NOW));

        // A created-but-unaccepted claim is not a live booking. Handing one back
        // as BOOKED is how an order sits unaccepted while everyone believes a
        // courier is on the way.
        assertThat(yandex.creates).isEqualTo(1);
        assertThat(yandex.confirms).isEqualTo(1);
        assertThat(receipt.status()).isEqualTo(BookingStatus.BOOKED);
    }

    @Test
    @DisplayName("the accept carries its own idempotency key, not the create's")
    void theAcceptUsesADistinctKey() throws Exception {
        ScriptedPartner yandex = twoPhase();
        ShipmentBookingPort port = port(yandex);

        port.book(command(TWO_PHASE_BINDING, BookingIntent.BOOK_NOW));

        // Two different operations under one key is a partner seeing the accept
        // as a replay of the create. Derived rather than random, so the retry of
        // either still deduplicates.
        assertThat(yandex.keys).hasSize(2);
        assertThat(yandex.keys.get(0)).isNotEqualTo(yandex.keys.get(1));
    }

    @Test
    @DisplayName("a hold is reported as a hold and is never promoted behind the caller's back")
    void aHoldStaysAHold() throws Exception {
        ScriptedPartner yandex = twoPhase();
        ShipmentBookingPort port = port(yandex);

        BookingReceipt receipt = port.book(command(TWO_PHASE_BINDING, BookingIntent.HOLD));

        assertThat(receipt.status()).isEqualTo(BookingStatus.HELD);
        assertThat(receipt.holdsProviderState())
                .as("a hold that does not win must still be cancellable, so it is provider state")
                .isTrue();
        assertThat(yandex.confirms).isZero();
    }

    @Test
    @DisplayName("a failed accept still reports the hold's reference so it can be cancelled")
    void aFailedAcceptKeepsTheHoldReference() throws Exception {
        ScriptedPartner yandex = twoPhase();
        yandex.confirmOutcome = ProviderOutcome.rejected("CLAIM_VERSION_STALE", "changed");
        ShipmentBookingPort port = port(yandex);

        BookingReceipt receipt = port.book(command(TWO_PHASE_BINDING, BookingIntent.BOOK_NOW));

        // Without the reference this is an abandoned hold, which ADR 0014 calls
        // an operational exception rather than a no-op — and nothing else can
        // cancel a claim whose id was thrown away.
        assertThat(receipt.status()).isEqualTo(BookingStatus.HELD);
        assertThat(receipt.externalReference()).isEqualTo("yandex-1");
        assertThat(receipt.errorCode()).isEqualTo("CLAIM_VERSION_STALE");
    }

    @Test
    @DisplayName("an uncertain create comes back uncertain and books nothing twice")
    void anUncertainCreateIsNeverRepeated() throws Exception {
        ScriptedPartner noor = onePhase();
        noor.createOutcome = ProviderOutcome.uncertain("READ_TIMEOUT", "no response");
        ShipmentBookingPort port = port(noor);

        BookingReceipt receipt = port.book(command(ONE_PHASE_BINDING, BookingIntent.BOOK_NOW));

        // Noor's create is immediately live and its idempotency is unverified, so
        // a second create is a second courier at the customer's door.
        assertThat(noor.creates).isEqualTo(1);
        assertThat(receipt.status()).isEqualTo(BookingStatus.UNCERTAIN);
        assertThat(receipt.errorCode()).isEqualTo("RECONCILE_MANUAL");
    }

    @Test
    @DisplayName("a binding this branch does not hold is refused before any partner is called")
    void anUnresolvableBindingIsRefusedWithoutCallingAnybody() throws Exception {
        ScriptedPartner noor = onePhase();
        ShipmentBookingPort port = port(noor);

        BookingReceipt receipt = port.book(command(UUID.randomUUID(), BookingIntent.BOOK_NOW));

        // The binding is re-resolved against the tenant, brand and location the
        // command names. An id trusted on its own is this tenant's order
        // dispatched against another tenant's partner account.
        assertThat(receipt.status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(receipt.errorCode()).isEqualTo("BINDING_UNAVAILABLE");
        assertThat(noor.creates).isZero();
    }

    @Test
    @DisplayName("a prepaid order reaches the partner as prepaid")
    void prepaidTravelsUnchanged() throws Exception {
        ScriptedPartner noor = onePhase();
        ShipmentBookingPort port = port(noor);

        port.book(command(ONE_PHASE_BINDING, BookingIntent.BOOK_NOW));

        // False here instructs a partner to collect the basket price from the
        // recipient, so a value dropped in translation charges the customer a
        // second time for food HorecaOS was already paid for.
        assertThat(noor.lastRequest.prepaid()).isTrue();
        assertThat(noor.lastRequest.horecaosReference()).isEqualTo("QO-3003");
    }

    @Test
    @DisplayName("a scheduled booking sends the pickup instant the plan asked for")
    void aScheduledBookingCarriesItsPickupInstant() throws Exception {
        ScriptedPartner noor = onePhase();
        ShipmentBookingPort port = port(noor);
        Instant pickup = Instant.parse("2026-08-24T14:00:00Z");

        port.book(new BookingCommand(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                ONE_PHASE_BINDING,
                BookingIntent.BOOK_FOR_PICKUP_WINDOW,
                "QO-3003",
                branch(),
                home(),
                pickup,
                true,
                145_000L,
                "UZS",
                "corr-1"));

        assertThat(noor.lastRequest.requestedPickupAt()).isEqualTo(pickup);
    }

    private ShipmentBookingPort port(ScriptedPartner... partners) throws Exception {
        List<DeliveryPartner> registered = List.of(partners);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DeliveryGateway gateway = new DeliveryGateway(registered, lookup(), fixedResolver());
        DeliveryProcessor processor = new DeliveryProcessor(
                gateway,
                new DeliveryCircuitBreakers(meters, Clock.systemUTC()),
                meters,
                new RecordingReconciliationOutbox());

        camel = new DefaultCamelContext();
        camel.addRoutes(new DeliveryRouteBuilder(processor));
        camel.start();
        ProducerTemplate template = camel.createProducerTemplate();

        return new CamelShipmentBookingPort(template, lookup(), gateway);
    }

    private static BookingCommand command(UUID bindingId, BookingIntent intent) {
        return new BookingCommand(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                bindingId,
                intent,
                "QO-3003",
                branch(),
                home(),
                null,
                true,
                145_000L,
                "UZS",
                "corr-1");
    }

    private static Waypoint branch() {
        return new Waypoint(41.311, 69.240, "branch", "Kitchen", "+998900000001", null, null, null, null);
    }

    private static Waypoint home() {
        return new Waypoint(41.325, 69.281, "home", "Customer", "+998900000002", "gate code 12", "2", "5", "17");
    }

    private static ScriptedPartner onePhase() {
        return new ScriptedPartner(
                "noor-like",
                "noor-1",
                Set.of(
                        DeliveryCapability.QUOTE_DELIVERY,
                        DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT,
                        DeliveryCapability.SCHEDULE_SHIPMENT,
                        DeliveryCapability.CANCEL_SHIPMENT,
                        DeliveryCapability.QUERY_SHIPMENT),
                true);
    }

    private static ScriptedPartner twoPhase() {
        return new ScriptedPartner(
                "yandex-like",
                "yandex-1",
                Set.of(
                        DeliveryCapability.QUOTE_DELIVERY,
                        DeliveryCapability.RESERVE_SHIPMENT,
                        DeliveryCapability.CONFIRM_SHIPMENT,
                        DeliveryCapability.SCHEDULE_SHIPMENT,
                        DeliveryCapability.CANCEL_SHIPMENT,
                        DeliveryCapability.QUERY_SHIPMENT),
                false);
    }

    private static ProviderInstallationLookup lookup() {
        SecretReference reference =
                new SecretReference("local", SecretCategory.PROVIDER_DELIVERY, "tenant", "scripted");
        BindingRef onePhase = new BindingRef(
                ONE_PHASE_BINDING, INSTALLATION, TENANT, ProviderCategory.DELIVERY, "noor-like", BRAND, LOCATION);
        BindingRef twoPhase = new BindingRef(
                TWO_PHASE_BINDING, INSTALLATION, TENANT, ProviderCategory.DELIVERY, "yandex-like", BRAND, LOCATION);

        return new ProviderInstallationLookup() {
            @Override
            public Optional<BindingRef> primaryBinding(UUID t, UUID b, UUID l, String code) {
                return Optional.empty();
            }

            @Override
            public List<BindingRef> candidateBindings(UUID t, UUID b, UUID l, String code) {
                if (!TENANT.equals(t) || !LOCATION.equals(l)) {
                    return List.of();
                }
                // What ADR 0026 actually stores: a one-phase partner declares the
                // on-demand create and a two-phase one declares the reservation,
                // so a port asking for only one of the codes sees only half the
                // partners this branch has.
                return switch (code) {
                    case "CreateOnDemandShipment" -> List.of(onePhase);
                    case "ReserveShipment" -> List.of(twoPhase);
                    default -> List.of();
                };
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

    /**
     * A partner whose create is live or a hold, which is the one difference the
     * booking port has to get right.
     */
    private static final class ScriptedPartner implements DeliveryPartner {

        private final String providerType;
        private final String reference;
        private final Set<DeliveryCapability> capabilities;
        private final boolean createIsLive;
        private final List<String> keys = new ArrayList<>();

        private ProviderOutcome createOutcome;
        private ProviderOutcome confirmOutcome;
        private DeliveryRequest lastRequest;
        private int creates;
        private int confirms;

        ScriptedPartner(
                String providerType, String reference, Set<DeliveryCapability> capabilities, boolean createIsLive) {
            this.providerType = providerType;
            this.reference = reference;
            this.capabilities = capabilities;
            this.createIsLive = createIsLive;
            this.createOutcome = ProviderOutcome.success(Map.of("live", createIsLive), reference);
            this.confirmOutcome = ProviderOutcome.success(Map.of("live", true), reference);
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public Set<DeliveryCapability> capabilities() {
            return capabilities;
        }

        @Override
        public ProviderOutcome quote(DeliveryRequest request, ProviderCall call) {
            return ProviderOutcome.success(Map.of(), null);
        }

        @Override
        public ProviderOutcome createShipment(DeliveryRequest request, ProviderCall call) {
            creates++;
            keys.add(call.idempotencyKey());
            lastRequest = request;
            return createOutcome;
        }

        @Override
        public ProviderOutcome confirmShipment(String externalReference, ProviderCall call) {
            confirms++;
            keys.add(call.idempotencyKey());
            return confirmOutcome;
        }

        @Override
        public ProviderOutcome cancellationCost(String externalReference, ProviderCall call) {
            return ProviderOutcome.uncertain("UNSUPPORTED", "no pre-check");
        }

        @Override
        public ProviderOutcome cancelShipment(String externalReference, String reason, ProviderCall call) {
            return ProviderOutcome.success(Map.of(), externalReference);
        }

        @Override
        public ProviderOutcome queryShipment(String externalReference, ProviderCall call) {
            return ProviderOutcome.success(Map.of("live", createIsLive), reference);
        }
    }
}
