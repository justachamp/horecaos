package uz.horecaos.platform.integration.camel.notification;

import java.util.Optional;
import java.util.Set;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;

/**
 * One notification provider, behind ADR 0007's rules, as ADR 0146 states the
 * contract.
 *
 * <p>An adapter holds no configuration and no credential. Both arrive on the
 * {@link ProviderCall} and the {@link AccountContext} for the duration of one
 * request, which is what keeps a rotated token from being pinned inside a
 * long-lived bean.
 *
 * <p>An adapter must not throw for a provider failure. Every outcome — refused,
 * rate limited, timed out after sending — comes back as a {@link ProviderOutcome},
 * because the one thing the caller cannot recover afterwards is whether the
 * provider might already have acted.
 *
 * <p>Six obligations, stated once so each adapter can be reviewed against them:
 * {@code send}, {@code resolve}, {@code normalise} (the receipt), the segments a
 * send reports, {@code describeAccount}, and a taxonomy from every provider code to
 * an ADR 0007 outcome in the provider's {@code docs/providers/} file. A code not in
 * the table is uncertain, never a success.
 */
public interface NotificationChannelAdapter {

    /**
     * The answer that makes a second send safe: the gateway has no record of
     * this request.
     *
     * <p>The one rejection a reconciliation may be given, and the reason a
     * gateway that cannot be sure (VAS, whose search window is a day in an
     * unstated timezone) must answer uncertain instead. Every other answer leaves
     * the message possibly sent.
     */
    String NO_RECORD = "PROVIDER_HAS_NO_RECORD";

    /**
     * Keys an adapter may put on a successful outcome's {@code normalized} map for
     * the transport to read. Bounded values only: a status name, a count, a flag.
     * Never a number, a text or a credential.
     */
    String PROVIDER_STATUS_KEY = "providerStatus";

    String NORMALIZED_STATUS_KEY = "normalizedStatus";
    String SEGMENTS_KEY = "providerSegments";
    String HARD_BOUNCE_KEY = "hardBounce";

    /** The ADR 0026 {@code provider_type} this adapter implements. */
    String providerType();

    /** The ADR 0020 channel it sends on. */
    String channel();

    /**
     * Sends one message for a gateway that needs nothing beside its credential.
     */
    ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call);

    /**
     * Sends one message as the account the binding names.
     *
     * <p>A gateway whose request carries more than a credential (VAS puts a login
     * and a sender in every body) overrides this; the default is for the ones that
     * do not.
     */
    default ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call, AccountContext account) {
        return send(dispatch, call);
    }

    /**
     * Asks the provider what it holds for a request, by the key we sent it under.
     *
     * <p>Always safe to repeat, which is the entire reason it exists: it is what
     * stands between an uncertain send and a duplicate message. A provider with no
     * idempotency key cannot answer it and says so (uncertain); see {@link #resolve}.
     */
    ProviderOutcome queryStatus(String providerIdempotencyKey, ProviderCall call);

    /**
     * ADR 0146's {@code resolve}: given what the platform has, answer sent, not
     * sent, or unknown, and never by sending.
     *
     * <p>Sent is a success carrying the message's own state; not sent is a
     * rejection with the gateway's "no record" code and is the one answer that
     * licenses another send; unknown is uncertain, and is a first-class answer.
     * Defaulted to the key lookup for a gateway that holds our key.
     */
    default ProviderOutcome resolve(ResolveRequest request, ProviderCall call, AccountContext account) {
        return queryStatus(request.providerIdempotencyKey(), call);
    }

    /**
     * ADR 0146's {@code receipt}: reads a provider's delivery callback in the
     * platform's vocabulary, or nothing when the body is not one this adapter can
     * read. Empty for a gateway with no receipts, which is the default.
     */
    default Optional<ReceiptEvent> normalise(java.util.Map<String, Object> rawReceipt) {
        return Optional.empty();
    }

    /**
     * How this gateway's callbacks can prove who sent them (ADR 0146 Decision 4):
     * a per-installation secret in a header where the provider can carry one, and
     * otherwise the provider's published source addresses, enforced at the edge.
     */
    default ReceiptAuthentication receiptAuthentication() {
        return ReceiptAuthentication.SECRET_HEADER;
    }

    /** What a callback can carry to authenticate itself. */
    enum ReceiptAuthentication {
        /** {@code X-HorecaOS-Receipt-Secret}, compared in constant time with an ADR 0028 reference. */
        SECRET_HEADER,

        /** Nothing in the request: the provider's addresses are allowed at the edge and nowhere else. */
        SOURCE_ADDRESS_ALLOWLIST
    }

    /**
     * ADR 0146's {@code account}: whether the installation is configured, without
     * calling out.
     */
    default AccountReadiness describeAccount(AccountContext account) {
        return AccountReadiness.satisfied();
    }

    /**
     * Whether this account has been cleared to carry a message of {@code purpose}
     * (see {@link SmsPurposes}). A gateway with no such restriction carries
     * everything.
     */
    default boolean permits(String purpose, AccountContext account) {
        return true;
    }

    /** The purposes this gateway carries when an installation has not said otherwise. */
    default Set<String> defaultPurposes() {
        return Set.of(SmsPurposes.VERIFICATION, SmsPurposes.TRANSACTIONAL, SmsPurposes.MARKETING, SmsPurposes.COURIER);
    }
}
