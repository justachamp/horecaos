package uz.horecaos.platform.commercial.infrastructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.application.CardAccount;
import uz.horecaos.platform.commercial.application.CardCharger;
import uz.horecaos.platform.commercial.application.CardChargingAvailability;
import uz.horecaos.platform.commercial.application.CardEnrolment;
import uz.horecaos.platform.commercial.application.CardProviderAdapter;
import uz.horecaos.platform.commercial.application.CardTokenReferences;
import uz.horecaos.platform.commercial.domain.PlatformCardInstallation;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlatformCardInstallationStore;

/**
 * The one {@link CardCharger} and {@link CardEnrolment} the application sees (ADR
 * 0095, ADR 0026): it finds HorecaOS's active card installation, and hands every
 * call to the adapter that answers for that installation's provider type.
 *
 * <p>With no installation active, or one whose adapter this build does not carry,
 * or a test double outside a local or test run, every answer is {@code
 * NotConfigured}: a CARD tenant is then collected exactly like an INVOICE one, as
 * it was before any of this existed. Nothing here ever charges "as a default".
 *
 * <p>A stored reference minted under another installation is refused before it
 * reaches the adapter, naming the real problem (the account changed) rather than
 * letting the provider decline a token it has never seen as if the cardholder
 * were at fault.
 */
@Component
public class PlatformCardGateway implements CardCharger, CardEnrolment, CardChargingAvailability {

    private static final Logger log = LoggerFactory.getLogger(PlatformCardGateway.class);

    private final JdbcPlatformCardInstallationStore installations;
    private final Map<String, CardProviderAdapter> adapters;
    private final boolean allowFake;

    public PlatformCardGateway(
            JdbcPlatformCardInstallationStore installations,
            List<CardProviderAdapter> adapters,
            @Value("${horecaos.commercial.card.allow-fake:false}") boolean allowFake) {
        this.installations = installations;
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(
                        CardProviderAdapter::providerType, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        this.allowFake = allowFake;
    }

    /** Whether this build carries an adapter for the type, and may use it here. */
    public boolean mayUse(String providerType) {
        CardProviderAdapter adapter = adapters.get(providerType);
        return adapter != null && (adapter.usableInProduction() || allowFake);
    }

    /** The adapter for a type, whether or not it may be used here; for activation's own checks. */
    public Optional<CardProviderAdapter> adapterFor(String providerType) {
        return Optional.ofNullable(adapters.get(providerType));
    }

    /** Whether a card can be charged through this build right now: an installation is active and usable here. */
    @Override
    public boolean available() {
        return active().isPresent();
    }

    /** Same answer, named for the console's own reading of it. */
    public boolean configured() {
        return available();
    }

    public boolean allowsTestDoubles() {
        return allowFake;
    }

    // ------------------------------------------------------------------ CardCharger

    @Override
    public Outcome charge(
            UUID tenantId,
            @Nullable String cardTokenReference,
            long amountMinor,
            String currency,
            String idempotencyKey) {
        Optional<Active> active = active();
        if (active.isEmpty()) {
            return new Outcome.NotConfigured();
        }
        if (cardTokenReference == null) {
            return new Outcome.Failed("NO_CARD_ON_FILE");
        }
        Optional<String> providerToken = providerTokenFor(active.get(), cardTokenReference);
        if (providerToken.isEmpty()) {
            return new Outcome.Failed("CARD_BOUND_UNDER_ANOTHER_MERCHANT_ACCOUNT");
        }
        return active.get()
                .adapter()
                .charge(active.get().account(), tenantId, providerToken.get(), amountMinor, currency, idempotencyKey);
    }

    @Override
    public StatusOutcome status(String idempotencyKey) {
        return active().map(active -> active.adapter().status(active.account(), idempotencyKey))
                .orElseGet(StatusOutcome.NotSucceeded::new);
    }

    // ---------------------------------------------------------------- CardEnrolment

    @Override
    public BeginOutcome begin(UUID tenantId) {
        return active().<BeginOutcome>map(active -> active.adapter().begin(active.account(), tenantId))
                .orElseGet(BeginOutcome.NotConfigured::new);
    }

    @Override
    public ConfirmOutcome confirm(
            UUID tenantId, String sessionReference, String providerToken, String verificationCode) {
        Optional<Active> active = active();
        if (active.isEmpty()) {
            return new ConfirmOutcome.NotConfigured();
        }
        ConfirmOutcome outcome = active.get()
                .adapter()
                .confirm(active.get().account(), tenantId, sessionReference, providerToken, verificationCode);
        if (outcome instanceof ConfirmOutcome.Enrolled enrolled) {
            // Bound to the account that minted it, so a later change of account cannot send it elsewhere.
            return new ConfirmOutcome.Enrolled(
                    CardTokenReferences.compose(active.get().account().installationId(), enrolled.cardTokenReference()),
                    enrolled.last4(),
                    enrolled.brand(),
                    enrolled.expiryMonth(),
                    enrolled.expiryYear());
        }
        return outcome;
    }

    @Override
    public RevokeOutcome revoke(String cardTokenReference) {
        Optional<Active> active = active();
        if (active.isEmpty()) {
            return new RevokeOutcome.NotConfigured();
        }
        Optional<String> providerToken = providerTokenFor(active.get(), cardTokenReference);
        if (providerToken.isEmpty()) {
            return new RevokeOutcome.Failed("MINTED_UNDER_ANOTHER_MERCHANT_ACCOUNT");
        }
        return active.get().adapter().revoke(active.get().account(), providerToken.get());
    }

    // -------------------------------------------------------------------- internals

    private Optional<Active> active() {
        Optional<PlatformCardInstallation> installation = installations.findActive();
        if (installation.isEmpty()) {
            return Optional.empty();
        }
        PlatformCardInstallation found = installation.get();
        CardProviderAdapter adapter = adapters.get(found.providerType());
        if (adapter == null) {
            log.warn(
                    "The active card installation {} names provider type {}, which this build has no adapter for",
                    found.id(),
                    found.providerType());
            return Optional.empty();
        }
        if (!adapter.usableInProduction() && !allowFake) {
            log.warn(
                    "The active card installation {} is a test double and this is not a local or test run; "
                            + "it is not used",
                    found.id());
            return Optional.empty();
        }
        return Optional.of(new Active(
                adapter,
                new CardAccount(
                        found.id(),
                        found.providerType(),
                        found.environmentCode(),
                        found.secretReference(),
                        found.nonSensitiveConfig())));
    }

    private static Optional<String> providerTokenFor(Active active, String reference) {
        Optional<CardTokenReferences.Parsed> parsed = CardTokenReferences.parse(reference);
        if (parsed.isEmpty()) {
            return Optional.of(reference);
        }
        return parsed.get().installationId().equals(active.account().installationId())
                ? Optional.of(parsed.get().providerToken())
                : Optional.empty();
    }

    private record Active(CardProviderAdapter adapter, CardAccount account) {}
}
