package uz.horecaos.platform.integration.camel.sms;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup.InstallationSnapshot;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.notification.NotificationGateway;
import uz.horecaos.platform.integration.provider.SmsAccountLookup;
import uz.horecaos.platform.integration.provider.SmsAccountLookup.SmsAccount;

/**
 * The single entry point from the verification route to the SMS gateway
 * (ADR 0007, ADR 0026, ADR 0028).
 *
 * <p>It does the four things the adapter must not do for itself: resolve the ADR
 * 0026 binding for the brand, turn that installation into a base URL, read the
 * non-secret half of the account, and resolve the credential at call time —
 * refreshing it exactly once past the ADR 0028 cache when the provider says the
 * key is wrong.
 *
 * <p><strong>There is no ADR 0024 import guard here, deliberately.</strong> The
 * sibling gateways carry one and this does not, because ADR 0024's list of
 * suppressed effects is written out rather than derived — {@code
 * MigrationImportSuppressionTests} asserts the exact set of files that consult
 * the flag — and no import path reaches verification issuance: a challenge is
 * only ever opened from a storefront request, never from an imported row. Adding
 * an eleventh guard would widen a deliberately closed inventory without anybody
 * deciding to. If an import port is ever built that verifies imported numbers,
 * the guard and the inventory entry are added together, which is what that test
 * exists to force.
 *
 * <p><strong>The provider type is checked, not assumed.</strong> The
 * {@code SEND_SMS} capability is shared with ADR 0020's notification path, and a
 * tenant may well have a different gateway bound for it. Calling
 * {@code /send} at whatever base URL that binding names would put a partner's
 * credential and a customer's one-time code into a request no one has agreed the
 * shape of. A binding for another provider is refused here by name.
 */
@Service
public class SmsGateway {

    /**
     * Fifteen seconds, matching the notification route.
     *
     * <p>The provider documents no timeout and no guidance on whether a request
     * that times out was accepted, which is precisely why the deadline is short
     * and the uncertain path is real: we will hit this, and the answer to hitting
     * it is {@code /search}, not patience.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(SmsGateway.class);

    private final ProviderInstallationLookup installations;
    private final SmsAccountLookup accounts;
    private final SecretResolver secrets;
    private final VasSmsGatewayAdapter adapter;
    private final Duration timeout;

    /**
     * The wired one. Annotated because the deadline-taking constructor below
     * makes two, and a container choosing between them by arity is a coin toss.
     */
    @Autowired
    public SmsGateway(
            ProviderInstallationLookup installations,
            SmsAccountLookup accounts,
            SecretResolver secrets,
            VasSmsGatewayAdapter adapter) {
        this(installations, accounts, secrets, adapter, DEFAULT_TIMEOUT);
    }

    /**
     * The deadline is a parameter so a test can produce the case that matters
     * here — the gateway takes the message and never answers — without holding a
     * suite still for fifteen seconds.
     */
    SmsGateway(
            ProviderInstallationLookup installations,
            SmsAccountLookup accounts,
            SecretResolver secrets,
            VasSmsGatewayAdapter adapter,
            Duration timeout) {
        this.installations = installations;
        this.accounts = accounts;
        this.secrets = secrets;
        this.adapter = adapter;
        this.timeout = timeout;
    }

    public ProviderOutcome send(SmsVerificationOperation operation) {
        return invoke(operation, adapter::send);
    }

    /** The reconciliation path. Always safe to call, and never sends anything. */
    public ProviderOutcome resolve(SmsVerificationOperation operation) {
        return invoke(operation, adapter::resolve);
    }

    private ProviderOutcome invoke(SmsVerificationOperation operation, ProviderStep call) {

        Optional<BindingRef> binding = installations.primaryBinding(
                operation.tenantId(), operation.brandId(), null, NotificationGateway.SEND_SMS);
        if (binding.isEmpty()) {
            return ProviderOutcome.rejected("NO_PROVIDER_BINDING", "No SMS provider is bound for this brand");
        }

        BindingRef resolved = binding.get();
        if (!VasSmsGatewayAdapter.PROVIDER_TYPE.equals(resolved.providerType())) {
            // Not a failure of this send but of the tenant's configuration, and it
            // must be loud: the alternative is speaking one provider's protocol at
            // another provider's endpoint, holding a credential.
            log.error(
                    "Binding {} claims SEND_SMS with provider type {}, which no verification " + "adapter implements",
                    resolved.bindingId(),
                    resolved.providerType());
            return ProviderOutcome.rejected(
                    "SMS_PROVIDER_UNSUPPORTED", "The bound SMS provider has no verification adapter");
        }

        Optional<InstallationSnapshot> snapshot =
                installations.installation(operation.tenantId(), resolved.installationId());
        if (snapshot.isEmpty()) {
            return ProviderOutcome.rejected(
                    "INSTALLATION_MISSING", "Installation " + resolved.installationId() + " is not available");
        }
        InstallationSnapshot installation = snapshot.get();
        if (!"ACTIVE".equals(installation.status())) {
            // A suspended installation is a deliberate stop, often mid-rotation.
            // Calling anyway would earn a 13 and page somebody.
            return ProviderOutcome.rejected(
                    "INSTALLATION_INACTIVE",
                    "Installation " + resolved.installationId() + " is " + installation.status());
        }
        if (installation.secretReference() == null
                || installation.secretReference().isBlank()) {
            return ProviderOutcome.rejected(
                    "SMS_ACCOUNT_MISCONFIGURED",
                    "Installation " + resolved.installationId() + " carries no secret reference");
        }

        Optional<SmsAccount> account = accounts.forBinding(resolved);
        if (account.isEmpty() || !account.get().isComplete()) {
            // Refused rather than sent with a blank. A missing sender comes back
            // as 15 and a missing login as 10, both after the credential has been
            // put on the wire for nothing.
            return ProviderOutcome.rejected(
                    "SMS_ACCOUNT_MISCONFIGURED",
                    "Binding " + resolved.bindingId() + " has no login and sender configured");
        }

        SecretReference reference = SecretReference.parse(installation.secretReference());
        // Not disposed: the resolver caches and hands back the same instance, so
        // clearing it here would blank the credential for every other caller.
        SecretValue credential = secrets.resolve(reference);
        // This provider has no idempotency key (SmsVerificationOperation's own
        // javadoc explains why /search exists instead), so nothing reads this
        // back; the challenge id stands in rather than passing a null through a
        // field ProviderCall's own toString treats as always present.
        ProviderOutcome outcome = call.apply(
                operation,
                account.get(),
                new ProviderCall(
                        installation.baseUrl(),
                        credential.reveal(),
                        operation.challengeId().toString(),
                        timeout));

        if (isWrongKey(outcome)) {
            // One read past the cache, exactly as ADR 0028 prescribes. On this
            // provider the two possibilities are unusually far apart: either our
            // cached copy aged out, or the key was rotated in the provider's
            // console and never written to OpenBao — and only a fresh read tells
            // them apart. Once, never in a loop.
            //
            // This is the one place a send is repeated, and it is safe for the
            // one reason that licenses it anywhere: code 13 is the provider
            // stating a refusal, so it answered *instead of* sending. That is the
            // opposite of a lost response, which is never repeated and goes to
            // /search instead.
            log.warn(
                    "The SMS gateway rejected the cached credential for installation {}; " + "refreshing once",
                    resolved.installationId());
            outcome = call.apply(
                    operation,
                    account.get(),
                    new ProviderCall(
                            installation.baseUrl(),
                            secrets.resolveFresh(reference).reveal(),
                            operation.challengeId().toString(),
                            timeout));
        }
        return outcome;
    }

    /**
     * Whether the provider says the key is wrong.
     *
     * <p>{@code 13 wrong key} is mapped onto the platform's authentication code by
     * {@link SmsGateCode}, so this is the same test every other gateway makes.
     */
    private static boolean isWrongKey(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.REJECTED
                && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode());
    }

    /** A three-argument function, so both operations share one resolution path. */
    @FunctionalInterface
    private interface ProviderStep {
        ProviderOutcome apply(SmsVerificationOperation operation, SmsAccount account, ProviderCall call);
    }
}
