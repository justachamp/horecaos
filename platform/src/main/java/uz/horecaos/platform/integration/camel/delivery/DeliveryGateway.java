package uz.horecaos.platform.integration.camel.delivery;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.DeliveryRequest;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup.InstallationSnapshot;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;

/**
 * The single entry point from a delivery route to a courier partner (ADR 0007).
 *
 * <p>It does three things adapters must not do for themselves: pick the partner
 * for a binding, turn an ADR 0026 installation into a base URL and a live
 * credential, and refresh that credential once when the partner rejects it.
 *
 * <p>Adapters therefore hold no configuration and no secret. A credential exists
 * only for the duration of one {@link ProviderCall}, which is what keeps a
 * rotated token from being pinned inside a long-lived bean.
 */
@Service
public class DeliveryGateway {

    private static final Logger log = LoggerFactory.getLogger(DeliveryGateway.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final Map<String, DeliveryPartner> partners;
    private final ProviderInstallationLookup installations;
    private final SecretResolver secrets;

    public DeliveryGateway(List<DeliveryPartner> partners,
            ProviderInstallationLookup installations,
            SecretResolver secrets) {
        this.partners = partners.stream()
                .collect(Collectors.toUnmodifiableMap(DeliveryPartner::providerType, partner -> partner));
        this.installations = installations;
        this.secrets = secrets;
    }

    /** Whether this binding's partner can do something, without calling it. */
    public boolean supports(BindingRef binding, DeliveryCapability capability) {
        return partner(binding).map(p -> p.supports(capability)).orElse(false);
    }

    public ProviderOutcome quote(BindingRef binding, DeliveryRequest request, String idempotencyKey) {
        return invoke(binding, DeliveryCapability.QUOTE_DELIVERY, idempotencyKey,
                (partner, call) -> partner.quote(request, call));
    }

    /**
     * Books, or holds where the partner distinguishes them.
     *
     * <p>The capability checked depends on the partner: a two-phase partner is
     * asked for a reservation, a one-phase partner for a live shipment. Callers
     * must read {@code normalized().get("live")} to learn which they got, rather
     * than assuming, because on a one-phase partner this dispatched a courier.
     */
    public ProviderOutcome createShipment(BindingRef binding, DeliveryRequest request, String idempotencyKey) {
        DeliveryCapability required = supports(binding, DeliveryCapability.RESERVE_SHIPMENT)
                ? DeliveryCapability.RESERVE_SHIPMENT
                : DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT;
        return invoke(binding, required, idempotencyKey,
                (partner, call) -> partner.createShipment(request, call));
    }

    public ProviderOutcome confirmShipment(BindingRef binding, String externalReference, String idempotencyKey) {
        return invoke(binding, DeliveryCapability.CONFIRM_SHIPMENT, idempotencyKey,
                (partner, call) -> partner.confirmShipment(externalReference, call));
    }

    public ProviderOutcome cancellationCost(BindingRef binding, String externalReference, String idempotencyKey) {
        return invoke(binding, DeliveryCapability.QUERY_CANCELLATION_COST, idempotencyKey,
                (partner, call) -> partner.cancellationCost(externalReference, call));
    }

    public ProviderOutcome cancelShipment(BindingRef binding, String externalReference,
            String reason, String idempotencyKey) {
        return invoke(binding, DeliveryCapability.CANCEL_SHIPMENT, idempotencyKey,
                (partner, call) -> partner.cancelShipment(externalReference, reason, call));
    }

    /** The reconciliation path after an uncertain outcome. Always safe to call. */
    public ProviderOutcome queryShipment(BindingRef binding, String externalReference, String idempotencyKey) {
        return invoke(binding, DeliveryCapability.QUERY_SHIPMENT, idempotencyKey,
                (partner, call) -> partner.queryShipment(externalReference, call));
    }

    private ProviderOutcome invoke(BindingRef binding, DeliveryCapability capability,
            String idempotencyKey, BiFunction<DeliveryPartner, ProviderCall, ProviderOutcome> operation) {

        // ADR 0024's tripwire, and it covers every capability rather than only
        // createShipment. Every other rejection below is a normalised outcome the
        // caller is expected to handle, and returning one here would let an import
        // book nothing, read "NO_ADAPTER", and carry on as though delivery had
        // simply not been configured. Reaching a courier partner inside an import
        // is a defect in the import port, so the run fails and names it.
        //
        // Placed on the shared path deliberately: a quote during an import is
        // harmless in itself and is still proof that a live delivery journey is
        // being replayed, which is the thing worth finding out about.
        ImportSuppression.refuse(ExternalEffect.COURIER_BOOKING,
                "%s on %s".formatted(capability, binding.providerType()));

        Optional<DeliveryPartner> resolved = partner(binding);
        if (resolved.isEmpty()) {
            return ProviderOutcome.rejected("NO_ADAPTER",
                    "No delivery adapter is registered for " + binding.providerType());
        }
        DeliveryPartner partner = resolved.get();

        if (!partner.supports(capability)) {
            // A rejection, not a failure: the partner is working exactly as
            // documented, and sourcing needs to pick a different one.
            return ProviderOutcome.rejected("CAPABILITY_UNSUPPORTED",
                    "%s does not support %s".formatted(binding.providerType(), capability));
        }

        Optional<InstallationSnapshot> snapshot =
                installations.installation(binding.tenantId(), binding.installationId());
        if (snapshot.isEmpty()) {
            return ProviderOutcome.rejected("INSTALLATION_MISSING",
                    "Installation " + binding.installationId() + " is not available");
        }
        InstallationSnapshot installation = snapshot.get();
        if (!"ACTIVE".equals(installation.status())) {
            // A suspended installation is a deliberate stop — often mid-rotation
            // or after a billing failure — and calling anyway would earn a 401.
            return ProviderOutcome.rejected("INSTALLATION_INACTIVE",
                    "Installation " + binding.installationId() + " is " + installation.status());
        }

        SecretReference reference = SecretReference.parse(installation.secretReference());
        // Not disposed: the resolver caches and hands back the same instance, so
        // clearing it here would blank the credential for every other caller.
        // Lifecycle belongs to the resolver, per SecretResolver's contract.
        SecretValue credential = secrets.resolve(reference);
        ProviderOutcome outcome = operation.apply(partner,
                new ProviderCall(installation.baseUrl(), credential.reveal(), idempotencyKey, DEFAULT_TIMEOUT));

        if (isAuthenticationFailure(outcome)) {
            // One retry past the cache, exactly as ADR 0028 prescribes: a token
            // rotated after we cached it looks identical to a revoked one, and
            // only a fresh read tells them apart. Once, not in a loop — a genuinely
            // revoked credential must surface as an incident, not as retry traffic.
            log.warn("Provider {} rejected the cached credential for installation {}; refreshing once",
                    binding.providerType(), binding.installationId());
            SecretValue fresh = secrets.resolveFresh(reference);
            outcome = operation.apply(partner,
                    new ProviderCall(installation.baseUrl(), fresh.reveal(), idempotencyKey, DEFAULT_TIMEOUT));
        }
        return outcome;
    }

    private static boolean isAuthenticationFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.REJECTED
                && "PROVIDER_UNAUTHORIZED".equals(outcome.errorCode());
    }

    private Optional<DeliveryPartner> partner(BindingRef binding) {
        return Optional.ofNullable(partners.get(binding.providerType()));
    }
}
