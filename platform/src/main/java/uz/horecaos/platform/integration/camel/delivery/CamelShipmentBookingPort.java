package uz.horecaos.platform.integration.camel.delivery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.DeliveryRequest;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.Dropoff;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.Pickup;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * {@link ShipmentBookingPort} over the ADR 0007 delivery route.
 *
 * <p>{@code DeliveryOperation}'s javadoc says "Fulfilment builds this". This is
 * where it does: sourcing decides, this translates the decision into an
 * operation and puts it on {@code direct:delivery.operation}, and the route
 * decides only whether to call again. It is also why the fulfillment module
 * compiles with no Camel on its classpath, which
 * {@code ModularArchitectureTests} enforces.
 *
 * <p>Two translations happen here and neither belongs anywhere else.
 *
 * <p>The first is <b>intent to capability</b>. Sourcing asks to book now; on
 * Yandex that is create-then-accept because a created claim is not live, and on
 * Noor it is a single create because there is nothing to accept. Fulfilment must
 * not know that — ADR 0014 is explicit that ordering and fulfilment never branch
 * on who the partner is — so the branch is here, on a declared capability rather
 * than on a provider name.
 *
 * <p>The second is <b>a binding id back to a binding</b>. The command carries an
 * id and a tenant; the binding is re-resolved from the ADR 0026 lookup against
 * that tenant, brand and location rather than trusted from the id. An id alone
 * is a cross-tenant booking waiting to happen — this tenant's order dispatched
 * against another tenant's partner account.
 */
@Component
public class CamelShipmentBookingPort implements ShipmentBookingPort {

    /**
     * ADR 0014's capability names as ADR 0026 stores them in
     * {@code integration.binding_capabilities.capability_code}.
     *
     * <p>Both are asked for, because "which partners serve this branch" has two
     * legitimate answers: a one-phase partner declares the on-demand create and
     * a two-phase one declares the reservation, and looking for only one of them
     * makes half the configured partners invisible to the fallback.
     */
    static final List<String> BOOKING_CAPABILITY_CODES = List.of("CreateOnDemandShipment", "ReserveShipment");

    private final ProducerTemplate producer;
    private final ProviderInstallationLookup installations;
    private final DeliveryGateway gateway;

    public CamelShipmentBookingPort(
            ProducerTemplate producer, ProviderInstallationLookup installations, DeliveryGateway gateway) {
        this.producer = producer;
        this.installations = installations;
        this.gateway = gateway;
    }

    @Override
    public List<PartnerOption> partners(UUID tenantId, UUID brandId, UUID locationId) {
        Map<UUID, BindingRef> byBinding = new LinkedHashMap<>();
        for (String code : BOOKING_CAPABILITY_CODES) {
            for (BindingRef binding : installations.candidateBindings(tenantId, brandId, locationId, code)) {
                byBinding.putIfAbsent(binding.bindingId(), binding);
            }
        }

        List<PartnerOption> options = new ArrayList<>(byBinding.size());
        for (BindingRef binding : byBinding.values()) {
            // Read from the adapter, not from the binding row. The row records
            // what a tenant enabled; the adapter records what the partner
            // documented, verified on 2026-08-20, and it is the second of those
            // that decides whether a create is a hold or a courier on a scooter.
            options.add(new PartnerOption(
                    binding.bindingId(),
                    binding.providerType(),
                    gateway.supports(binding, DeliveryCapability.RESERVE_SHIPMENT),
                    gateway.supports(binding, DeliveryCapability.SCHEDULE_SHIPMENT)));
        }
        return List.copyOf(options);
    }

    @Override
    public BookingReceipt book(BookingCommand command) {
        Optional<BindingRef> resolved = binding(command);
        if (resolved.isEmpty()) {
            // Not an exception: a binding disabled between the partner listing
            // and the booking is an ordinary race during a configuration change,
            // and sourcing should move to the next partner rather than page
            // somebody.
            return BookingReceipt.of(
                    BookingStatus.REJECTED,
                    command,
                    null,
                    null,
                    "BINDING_UNAVAILABLE",
                    "Binding " + command.bindingId() + " is not bookable for this location");
        }
        BindingRef binding = resolved.get();
        DeliveryCapability capability = capabilityFor(command.intent(), binding);

        ProviderOutcome outcome = send(new DeliveryOperation(
                command.commandId(),
                command.tenantId(),
                binding,
                capability,
                request(command),
                null,
                null,
                command.correlationId()));

        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            return translate(outcome, command, binding, false);
        }

        boolean live = Boolean.TRUE.equals(outcome.normalized().get("live"));
        if (live || command.intent() == BookingIntent.HOLD) {
            return translate(outcome, command, binding, live);
        }

        // A hold where a booking was asked for. On Yandex this is the normal
        // path — an unaccepted claim is not a live booking — and the accept is
        // what turns it into one. Promoting it here rather than handing the
        // caller a hold it did not ask for keeps ADR 0014's rule that every hold
        // which does not win must be explicitly cancelled from applying to a
        // hold nobody meant to take.
        ProviderOutcome confirmed = send(new DeliveryOperation(
                confirmationId(command.commandId()),
                command.tenantId(),
                binding,
                DeliveryCapability.CONFIRM_SHIPMENT,
                null,
                outcome.externalReference(),
                null,
                command.correlationId()));

        if (confirmed.status() == ProviderOutcome.Status.SUCCESS) {
            return translate(confirmed, command, binding, true);
        }
        // The hold exists and the promotion did not happen. Reported with the
        // hold's own reference, because that reference is the only thing that
        // can cancel it, and a receipt without it is an abandoned hold.
        return new BookingReceipt(
                confirmed.status() == ProviderOutcome.Status.REJECTED ? BookingStatus.HELD : statusOf(confirmed, false),
                command.commandId(),
                binding.bindingId(),
                binding.providerType(),
                outcome.externalReference(),
                confirmed.errorCode(),
                confirmed.detail());
    }

    /**
     * Re-resolves the command's binding against the scope it was authorised for.
     *
     * <p>Constrained on tenant, brand and location rather than looked up by id,
     * which is the difference between booking this branch's partner account and
     * booking whichever account the id happened to name.
     */
    private Optional<BindingRef> binding(BookingCommand command) {
        return partnersAsBindings(command).stream()
                .filter(candidate -> candidate.bindingId().equals(command.bindingId()))
                .findFirst();
    }

    private List<BindingRef> partnersAsBindings(BookingCommand command) {
        List<BindingRef> bindings = new ArrayList<>();
        for (String code : BOOKING_CAPABILITY_CODES) {
            bindings.addAll(
                    installations.candidateBindings(command.tenantId(), command.brandId(), command.locationId(), code));
        }
        return bindings;
    }

    /**
     * What sourcing meant, in terms of what this partner actually implements.
     *
     * <p>A booking on a two-phase partner starts as a reservation, exactly as
     * {@code DeliveryGateway.createShipment} chooses for itself; the difference
     * is that here the choice is visible on the operation, so the route's
     * validation and the metric both name the phase that ran.
     */
    private DeliveryCapability capabilityFor(BookingIntent intent, BindingRef binding) {
        boolean holds = gateway.supports(binding, DeliveryCapability.RESERVE_SHIPMENT);
        return switch (intent) {
            case HOLD -> DeliveryCapability.RESERVE_SHIPMENT;
            case BOOK_FOR_PICKUP_WINDOW -> DeliveryCapability.SCHEDULE_SHIPMENT;
            case BOOK_NOW -> holds ? DeliveryCapability.RESERVE_SHIPMENT : DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT;
        };
    }

    private ProviderOutcome send(DeliveryOperation operation) {
        // The whole exchange rather than a body: the outcome travels as a
        // header, and the dead-letter path replaces the body, so reading the
        // body would erase the classification the caller needs most.
        Exchange result = producer.request(DeliveryRouteBuilder.OPERATION_ENDPOINT, exchange -> {
            exchange.getIn().setBody(operation);
            exchange.getIn().setHeader(DeliveryProcessor.OPERATION_HEADER, operation);
        });

        ProviderOutcome outcome =
                result.getMessage().getHeader(DeliveryRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);

        return outcome == null
                // A route that classified nothing cannot say whether the partner
                // acted, so this is uncertain rather than retryable. Guessing the
                // comfortable answer here is how a misconfiguration becomes two
                // couriers at one door.
                ? ProviderOutcome.uncertain(
                        "ROUTE_PRODUCED_NO_OUTCOME", "The route returned without classifying the call")
                : outcome;
    }

    private static BookingReceipt translate(
            ProviderOutcome outcome, BookingCommand command, BindingRef binding, boolean live) {

        return BookingReceipt.of(
                statusOf(outcome, live),
                command,
                binding.providerType(),
                outcome.externalReference(),
                outcome.errorCode(),
                outcome.detail());
    }

    /**
     * ADR 0007's four outcomes onto ADR 0014's five.
     *
     * <p>Five rather than four because {@code SUCCESS} means two different
     * things depending on the partner, and the difference is whether a courier
     * has been dispatched. Collapsing them is how an unaccepted claim gets
     * treated as a delivery on its way.
     */
    private static BookingStatus statusOf(ProviderOutcome outcome, boolean live) {
        return switch (outcome.status()) {
            case SUCCESS -> live ? BookingStatus.BOOKED : BookingStatus.HELD;
            case REJECTED -> BookingStatus.REJECTED;
            case RETRYABLE -> BookingStatus.RETRYABLE;
            case UNCERTAIN -> BookingStatus.UNCERTAIN;
        };
    }

    /**
     * The accept's own idempotency key, derived from the create's.
     *
     * <p>Distinct because it is a different operation and a partner keying on
     * one id would see the accept as a replay of the create. Derived rather than
     * random so that a retried accept reuses it, which is the property
     * {@code DeliveryOperation} says a retry depends on.
     */
    static UUID confirmationId(UUID commandId) {
        return UUID.nameUUIDFromBytes(("horecaos.delivery-confirm:" + commandId).getBytes(StandardCharsets.UTF_8));
    }

    private static DeliveryRequest request(BookingCommand command) {
        return new DeliveryRequest(
                command.horecaosReference(),
                new Pickup(
                        command.pickup().latitude(),
                        command.pickup().longitude(),
                        command.pickup().address(),
                        command.pickup().contactName(),
                        command.pickup().contactPhone(),
                        command.pickup().comment()),
                new Dropoff(
                        command.dropoff().latitude(),
                        command.dropoff().longitude(),
                        command.dropoff().address(),
                        command.dropoff().contactName(),
                        command.dropoff().contactPhone(),
                        command.dropoff().comment(),
                        command.dropoff().entrance(),
                        command.dropoff().floor(),
                        command.dropoff().apartment()),
                command.requestedPickupAt(),
                // Carried through untouched. Noor reads it as product_paid, and
                // false there instructs the partner to collect the basket price
                // from the recipient — which for an order HorecaOS already charged
                // is a customer paying twice.
                command.prepaid(),
                command.itemValueMinor(),
                command.currency(),
                Map.of());
    }
}
