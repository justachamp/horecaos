package uz.horecaos.platform.integration.camel.notification;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup.InstallationSnapshot;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;
import uz.horecaos.platform.notifications.api.NotificationDispatch;

/**
 * The single entry point from the notification route to a gateway (ADR 0007,
 * ADR 0026).
 *
 * <p>It does the three things adapters must not do for themselves: pick the
 * adapter for a binding, turn an ADR 0026 installation into a base URL and a live
 * credential, and refresh that credential once when the provider rejects it.
 *
 * <p>The binding is resolved here rather than passed in, because notifications is
 * a domain module and must not know that provider accounts exist. It asks for a
 * message to be sent on a channel; which external account handles that at this
 * brand is an ADR 0026 answer.
 */
@Service
public class NotificationGateway {

    /** The ADR 0020 provider capability, as an ADR 0026 binding capability code. */
    public static final String SEND_SMS = "SEND_SMS";

    /** Keys under which the binding travels back on a normalised outcome. */
    static final String BINDING_ID_KEY = "providerBindingId";

    static final String PROVIDER_TYPE_KEY = "providerType";

    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final Map<String, NotificationChannelAdapter> adaptersByChannel;
    private final ProviderInstallationLookup installations;
    private final SecretResolver secrets;

    public NotificationGateway(
            List<NotificationChannelAdapter> adapters,
            ProviderInstallationLookup installations,
            SecretResolver secrets) {
        this.adaptersByChannel = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(NotificationChannelAdapter::channel, adapter -> adapter));
        this.installations = installations;
        this.secrets = secrets;
    }

    /** Whether any adapter exists for a channel, without calling one. */
    public boolean supports(String channel) {
        return adaptersByChannel.containsKey(channel);
    }

    public ProviderOutcome send(NotificationDispatch dispatch) {
        return invoke(
                dispatch.tenantId(),
                dispatch.brandId(),
                dispatch.locationId(),
                dispatch.channel(),
                dispatch.providerIdempotencyKey(),
                // A Telegram dispatch already names the exact chat it is for
                // (ADR 0058 fan-out: several bindings legitimately want the same
                // event, so there is no single "primary" to select). Every other
                // channel keeps resolving by scope, unchanged.
                "TELEGRAM".equals(dispatch.channel()) ? tryParseUuid(dispatch.recipientValue()) : null,
                (adapter, call) -> adapter.send(dispatch, call));
    }

    /**
     * The reconciliation path after an uncertain outcome. Always safe to call.
     *
     * <p>Resolved at the same scope the send was, because a provider account is
     * bound to a brand or a location and a status query has to reach the account
     * that holds the message. Asking at the tenant alone resolves nothing and
     * leaves every uncertain message stuck in uncertainty.
     */
    public ProviderOutcome queryStatus(
            UUID tenantId, UUID brandId, @Nullable UUID locationId, String channel, String providerIdempotencyKey) {
        return invoke(
                tenantId,
                brandId,
                locationId,
                channel,
                providerIdempotencyKey,
                // No binding travels with a bare idempotency key. Harmless for
                // Telegram specifically, whose queryStatus never uses the call it
                // is given — see TelegramChannelAdapter's own note on why the Bot
                // API has no status query to make one meaningful.
                null,
                (adapter, call) -> adapter.queryStatus(providerIdempotencyKey, call));
    }

    private static @Nullable UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return null;
        }
    }

    private ProviderOutcome invoke(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String channel,
            String idempotencyKey,
            @Nullable UUID explicitBindingId,
            BiFunction<NotificationChannelAdapter, ProviderCall, ProviderOutcome> operation) {

        // ADR 0024. The real suppression is in OrderNotificationTrigger, which
        // stops the intent from being written at all; this is the tripwire for a
        // future import port that dispatches inline instead of leaving the message
        // to the worker. An SMS is the one effect on this list that cannot be
        // withdrawn once it has left, so it is worth failing a run over.
        ImportSuppression.refuse(ExternalEffect.NOTIFICATION_PROVIDER_CALL, "send on channel " + channel);

        NotificationChannelAdapter adapter = adaptersByChannel.get(channel);
        if (adapter == null) {
            return ProviderOutcome.rejected("NO_ADAPTER", "No notification adapter is registered for " + channel);
        }

        Optional<BindingRef> binding = explicitBindingId != null
                ? installations.binding(tenantId, explicitBindingId)
                : installations.primaryBinding(tenantId, brandId, locationId, capabilityFor(channel));
        if (binding.isEmpty() && explicitBindingId == null && "TELEGRAM".equals(channel)) {
            // Telegram never marks a binding primary (fan-out: several chats
            // legitimately want the same capability at one scope, and ADR 0026's
            // one-primary-per-scope index exists to prevent exactly that for
            // categories where it would be a bug). Reached only by queryStatus,
            // whose adapter call ignores the binding entirely.
            binding = installations.candidateBindings(tenantId, brandId, locationId, capabilityFor(channel)).stream()
                    .findFirst();
        }
        if (binding.isEmpty()) {
            // A rejection rather than a failure: nothing is wrong with the message,
            // the tenant simply has no gateway bound here. Retrying on a timer would
            // hide a configuration gap behind a growing backlog.
            return ProviderOutcome.rejected(
                    "NO_PROVIDER_BINDING", "No %s provider is bound for this brand".formatted(channel));
        }

        Optional<InstallationSnapshot> snapshot =
                installations.installation(tenantId, binding.get().installationId());
        if (snapshot.isEmpty()) {
            return ProviderOutcome.rejected(
                    "INSTALLATION_MISSING", "Installation " + binding.get().installationId() + " is not available");
        }
        InstallationSnapshot installation = snapshot.get();
        if (!"ACTIVE".equals(installation.status())) {
            // A suspended installation is a deliberate stop — often mid-rotation or
            // after a billing failure — and calling anyway would earn a 401.
            return ProviderOutcome.rejected(
                    "INSTALLATION_INACTIVE",
                    "Installation " + binding.get().installationId() + " is " + installation.status());
        }

        BindingRef resolved = binding.get();
        if (!adapter.providerType().equals(resolved.providerType())) {
            // Selecting on channel alone was safe while one adapter existed per
            // channel. It stopped being safe the moment a second SMS provider was
            // implemented: a tenant bound to that provider would have every
            // notification posted at its base URL in this adapter's request shape,
            // fail with a 404, and be marked permanently rejected — a silent loss
            // of that tenant's order confirmations. The binding names the provider;
            // the adapter has to be the one that speaks it.
            log.error(
                    "Binding {} claims {} with provider type {}, but the wired adapter speaks {}",
                    resolved.bindingId(),
                    channel,
                    resolved.providerType(),
                    adapter.providerType());
            return ProviderOutcome.rejected(
                    "PROVIDER_ADAPTER_MISMATCH",
                    "No %s adapter is wired for provider type %s".formatted(channel, resolved.providerType()));
        }

        SecretReference reference = SecretReference.parse(installation.secretReference());
        // Not disposed: the resolver caches and hands back the same instance, so
        // clearing it here would blank the credential for every other caller.
        SecretValue credential = secrets.resolve(reference);
        ProviderOutcome outcome = operation.apply(
                adapter,
                new ProviderCall(installation.baseUrl(), credential.reveal(), idempotencyKey, DEFAULT_TIMEOUT));

        if (isAuthenticationFailure(outcome)) {
            // One retry past the cache, exactly as ADR 0028 prescribes: a token
            // rotated after we cached it looks identical to a revoked one, and only
            // a fresh read tells them apart. Once, not in a loop — a genuinely
            // revoked credential must surface as an incident, not as retry traffic.
            log.warn(
                    "The {} gateway rejected the cached credential for installation {}; " + "refreshing once",
                    channel,
                    binding.get().installationId());
            SecretValue fresh = secrets.resolveFresh(reference);
            outcome = operation.apply(
                    adapter, new ProviderCall(installation.baseUrl(), fresh.reveal(), idempotencyKey, DEFAULT_TIMEOUT));
        }
        // Attributed to the account that handled it, whatever the answer was. A
        // rejection from a named gateway and a rejection from no gateway at all
        // are different problems, and the attempt row has to be able to say which.
        return attributedTo(outcome, resolved);
    }

    /**
     * Carries the binding back on the outcome.
     *
     * <p>Through {@code normalized} rather than a new field on
     * {@link ProviderOutcome}, because that type is shared with delivery and
     * payments and widening it for one caller's convenience is how a shared
     * contract accumulates optional fields nobody sets.
     */
    private static ProviderOutcome attributedTo(ProviderOutcome outcome, BindingRef binding) {
        Map<String, Object> normalized = new LinkedHashMap<>(outcome.normalized());
        normalized.put(BINDING_ID_KEY, binding.bindingId().toString());
        normalized.put(PROVIDER_TYPE_KEY, binding.providerType());
        return new ProviderOutcome(
                outcome.status(),
                Map.copyOf(normalized),
                outcome.externalReference(),
                outcome.errorCode(),
                outcome.detail(),
                outcome.retryAfter());
    }

    private static String capabilityFor(String channel) {
        return switch (channel) {
            case "SMS" -> SEND_SMS;
            case "TELEGRAM" -> TelegramBindingStore.SEND_TELEGRAM_MESSAGE;
            // ADR 0020 names SendPush and SendEmail as well. They are absent
            // rather than mapped to codes nothing declares, because a capability
            // code with no adapter behind it resolves a binding that then cannot
            // be used.
            default -> throw new IllegalArgumentException("No provider capability is defined for channel " + channel);
        };
    }

    private static boolean isAuthenticationFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.REJECTED
                && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode());
    }
}
