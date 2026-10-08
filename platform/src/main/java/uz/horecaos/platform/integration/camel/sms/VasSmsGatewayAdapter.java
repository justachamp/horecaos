package uz.horecaos.platform.integration.camel.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.notification.AccountContext;
import uz.horecaos.platform.integration.camel.notification.AccountReadiness;
import uz.horecaos.platform.integration.camel.notification.NotificationChannelAdapter;
import uz.horecaos.platform.integration.camel.notification.ReceiptEvent;
import uz.horecaos.platform.integration.camel.notification.ResolveRequest;
import uz.horecaos.platform.integration.camel.notification.SmsPurposes;
import uz.horecaos.platform.integration.provider.SmsAccountLookup.SmsAccount;
import uz.horecaos.platform.notifications.api.NotificationDispatch;

/**
 * smsgw.vas.uz, behind ADR 0007's rules, from
 * {@code docs/providers/sms-gateway-vas.md}.
 *
 * <p>Two of the provider's four operations are implemented, and the two that are
 * not are as much of the design as the two that are.
 *
 * <p><strong>{@code /send_msgs} is not used.</strong> Its envelope reports
 * {@code status.code: 0 — success} while individual entries carry their own
 * failure codes and {@code id: 0}, so a caller reading only the envelope
 * concludes everything sent. There is exactly one message in a verification code,
 * so the bulk endpoint buys nothing and costs a per-entry parser that nobody
 * would have a reason to keep honest. If a caller ever needs it, every entry has
 * to be inspected on its own and {@code id: 0} means nothing was accepted.
 *
 * <p><strong>The distribution API is not used either</strong>, which is why codes
 * 22–26 exist in {@link SmsGateCode} only so that receiving one is a named
 * refusal rather than an unreadable answer.
 *
 * <p><strong>There is no idempotency key on {@code /send}.</strong> That single
 * fact shapes everything here. A lost or unreadable answer is
 * {@link ProviderOutcome.Status#UNCERTAIN} and is resolved by
 * {@link #resolve} — a {@code /search} against the destination for the day — in
 * the same way the Click adapter resolves an uncertain payment with
 * {@code status_by_mti} rather than by charging the card again. Nothing in this
 * class re-sends, and no header pretends the provider deduplicates: an
 * {@code Idempotency-Key} it ignores would be a claim the document does not
 * support.
 *
 * <p>The request body is never logged, at any level, on any path. See
 * {@link SmsGateBody}.
 *
 * <p><strong>One adapter, every SMS purpose</strong> (ADR 0146 Decision 3). This
 * class is also the {@link NotificationChannelAdapter} for {@code SMSGW_VAS}, so a
 * single binding serves a customer's sign-in code (through {@code SmsGateway}, the
 * verification entry point) and an order confirmation (through
 * {@code NotificationGateway}) over the same HTTP client, the same code table and
 * the same credential handling. Both entry points call the same private methods.
 * What the account may carry is not decided here: {@link #defaultPurposes} is the
 * owner's default (codes and transactional messages), and an installation widens it
 * only by naming more in {@code permittedPurposes}, which is the written answer
 * ADR 0146's first open input waits for.
 *
 * <p><strong>This provider cannot be asked by our key.</strong>
 * {@link #queryStatus} says so (uncertain) rather than pretending, and
 * {@link #resolve(ResolveRequest, ProviderCall, AccountContext)} is the honest
 * replacement: a {@code /search} by destination and day, matched on the provider's
 * own message id when the send's answer gave one and otherwise on the SHA-256 of the
 * text, which is all the platform keeps of it (ADR 0029). Not finding the message
 * is <em>unknown</em>, never <em>not sent</em>, because the day's timezone is not
 * stated and the second answer licenses the resend this whole class exists to
 * prevent.
 */
@Component
public class VasSmsGatewayAdapter implements NotificationChannelAdapter {

    /** The ADR 0026 {@code provider_type} an installation must declare to be called here. */
    public static final String PROVIDER_TYPE = "SMSGW_VAS";

    static final String SEND_PATH = "/send";
    static final String SEARCH_PATH = "/search";

    /** Keys the route and the transport read off a normalised outcome. Bounded and safe. */
    static final String MESSAGE_ID_KEY = "providerMessageId";

    static final String DELIVERY_STATE_KEY = "providerDeliveryState";

    /** {@code delivery_status_events.provider_status} is 64 characters wide. */
    private static final int PROVIDER_STATUS_MAX = 64;

    /**
     * No headers, and that is a statement rather than an omission.
     *
     * <p>The other adapters on this platform send an {@code Idempotency-Key}. This
     * provider documents none anywhere, so sending one would be a claim the
     * document does not support — and a header the provider ignores is exactly the
     * kind of thing a later reader takes for a guarantee.
     */
    private static final Map<String, String> NO_HEADERS = Map.of();

    private final ProviderHttpClient http;

    public VasSmsGatewayAdapter(ProviderHttpClient http) {
        this.http = http;
    }

    // ------------------------------------------------- NotificationChannelAdapter

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public String channel() {
        return "SMS";
    }

    /**
     * Codes and transactional messages: the owner's default for this gateway
     * (ADR 0146, open input 1). Marketing and courier SMS stay refused until an
     * installation says in {@code permittedPurposes} that the account may carry
     * them, which is the answer in writing the record waits for.
     */
    @Override
    public Set<String> defaultPurposes() {
        return Set.of(SmsPurposes.VERIFICATION, SmsPurposes.TRANSACTIONAL);
    }

    @Override
    public boolean permits(String purpose, AccountContext account) {
        return SmsPurposes.permitted(account, defaultPurposes()).contains(purpose);
    }

    /**
     * Login and sender are present, found without calling out. The credential
     * reference is the gateway's to check: it is on the installation, not in
     * non-secret configuration.
     */
    @Override
    public AccountReadiness describeAccount(AccountContext account) {
        List<String> missing = new ArrayList<>();
        if (account.value(JdbcAccountKeys.LOGIN) == null) {
            missing.add(JdbcAccountKeys.LOGIN);
        }
        if (account.value(JdbcAccountKeys.SENDER) == null) {
            missing.add(JdbcAccountKeys.SENDER);
        }
        return AccountReadiness.missing(missing);
    }

    /**
     * Refused: this provider's request carries a login and a sender, and neither is
     * on a call that arrived without an {@link AccountContext}. Reaching here means
     * a caller bypassed the gateway, and the refusal says so rather than sending a
     * body with blanks in it.
     */
    @Override
    public ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call) {
        return ProviderOutcome.rejected(
                "SMS_ACCOUNT_MISCONFIGURED", "The VAS gateway needs an account context to send");
    }

    @Override
    public ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call, AccountContext account) {
        SmsAccount smsAccount = accountOf(account);
        if (!smsAccount.isComplete()) {
            return ProviderOutcome.rejected(
                    "SMS_ACCOUNT_MISCONFIGURED", "The VAS account has no login and sender configured");
        }
        return sendMessage(dispatch.recipientValue(), dispatch.body(), smsAccount, call);
    }

    /**
     * This provider holds no key of ours, so it cannot answer "what became of
     * request K". Uncertain, which is the true answer; {@link #resolve} is how it is
     * asked what it does hold.
     */
    @Override
    public ProviderOutcome queryStatus(String providerIdempotencyKey, ProviderCall call) {
        return ProviderOutcome.uncertain("SMS_NO_KEY_LOOKUP", "The VAS gateway cannot be asked by an idempotency key");
    }

    /**
     * ADR 0146 {@code resolve}: sent, not sent, or unknown, and never by sending.
     *
     * <p>Found is a success carrying the message's own state, which may be a failure
     * (the message exists and will not arrive); it is a fact about that message and
     * licenses nothing. Not found is uncertain, always.
     */
    @Override
    public ProviderOutcome resolve(ResolveRequest request, ProviderCall call, AccountContext account) {
        String destination = request.destination();
        if (destination == null || destination.isBlank()) {
            // A search by number needs the number. The dispatcher only resolves one
            // when it has no message id to go on; with neither this is unknown.
            return ProviderOutcome.uncertain(
                    "SMS_RESOLVE_NEEDS_DESTINATION", "A search by destination was asked without one");
        }
        SmsAccount smsAccount = accountOf(account);
        if (!smsAccount.isComplete()) {
            return ProviderOutcome.uncertain("SMS_ACCOUNT_MISCONFIGURED", "The VAS account has no login configured");
        }

        String knownId = request.providerMessageId();
        String wantedHash = request.renderedContentHash();
        Predicate<Map<String, Object>> ours = entry -> {
            String id = text(entry.get("id"));
            if (knownId != null && !knownId.isBlank()) {
                return knownId.equals(id);
            }
            String sentText = text(entry.get("msg"));
            return wantedHash != null && sentText != null && wantedHash.equals(sha256Hex(sentText));
        };

        return search(
                destination, request.requestedAt(), smsAccount, call, ours, VasSmsGatewayAdapter::foundForNotification);
    }

    /**
     * ADR 0146 {@code receipt}: the callback's {@code {login, key, id, code,
     * description}} in the platform's vocabulary.
     *
     * <p>Written against the document's own example and <em>unverified against a
     * real callback</em> (the record's second open input): the receipt endpoint
     * stays off for this provider type until one has been captured. A code the
     * document does not list reads as nothing at all, not as {@code UNKNOWN}.
     */
    @Override
    public Optional<ReceiptEvent> normalise(Map<String, Object> rawReceipt) {
        String id = text(rawReceipt.get("id"));
        if (id == null || id.isBlank() || "0".equals(id)) {
            return Optional.empty();
        }
        SmsGateDeliveryState state = SmsGateDeliveryState.of(integer(rawReceipt.get("code")));
        String normalized = state.normalizedStatus();
        if (normalized == null) {
            return Optional.empty();
        }
        String description = text(rawReceipt.get("description"));
        String word = description == null || description.isBlank() ? state.name() : description.strip();
        if (word.length() > PROVIDER_STATUS_MAX) {
            word = word.substring(0, PROVIDER_STATUS_MAX);
        }
        return Optional.of(new ReceiptEvent(id, normalized, word, null, state.isBlacklisted()));
    }

    /**
     * The provider documents a {@code login}/{@code key} pair in the callback and
     * its own example shows {@code key} empty, so nothing in the request can be
     * trusted to authenticate it. What stands in its place is the edge: the
     * provider's published source addresses are the only thing allowed to reach the
     * endpoint, and the attempt-match rule (an id this installation made, advancing
     * only) limits what a forgery that got through could do.
     */
    @Override
    public ReceiptAuthentication receiptAuthentication() {
        return ReceiptAuthentication.SOURCE_ADDRESS_ALLOWLIST;
    }

    // ------------------------------------------------------ the verification entry

    /**
     * {@code POST /send} for a verification code: a thin wrapper over the same
     * send a notification takes.
     */
    public ProviderOutcome send(SmsVerificationOperation operation, SmsAccount account, ProviderCall call) {
        return sendMessage(operation.destination(), operation.text(), account, call);
    }

    /**
     * {@code POST /send}. One message, one destination, no key to repeat it under.
     *
     * <p>A 2xx here is not success on its own: this provider reports business
     * failures inside a 200 body, so the envelope's {@code status.code} decides.
     * A {@code 0} that arrives without a message id contradicts itself and is
     * treated as uncertain rather than believed — {@code id} is the only evidence
     * the gateway actually took the message.
     */
    private ProviderOutcome sendMessage(String destination, String text, SmsAccount account, ProviderCall call) {

        SmsGateBody.Send body = new SmsGateBody.Send(
                requireConfigured(account.login(), "login"),
                call.credential(),
                requireConfigured(account.sender(), "sender"),
                destination,
                text);

        return http.post(call, SEND_PATH, NO_HEADERS, body, response -> {
            SmsGateCode code = SmsGateCode.of(
                    integer(response.get("status") instanceof Map<?, ?> status ? status.get("code") : null));

            if (code.effect() != SmsGateCode.Effect.ACCEPTED) {
                return classify(code);
            }

            String messageId = text(response.get("id"));
            if (messageId == null || messageId.isBlank() || "0".equals(messageId)) {
                // Success with no identifier. The provider's own bulk response
                // uses id 0 to mean "nothing was accepted", so a 0 here is at best
                // ambiguous, and there is nothing to carry into a callback or a
                // support conversation. Resolved by asking, never by resending.
                return ProviderOutcome.uncertain(
                        "SMS_ACCEPTED_WITHOUT_ID", "The gateway reported success without a message id");
            }

            int parts = integerOr(response.get("parts"), 1);
            return ProviderOutcome.success(
                    Map.of(
                            MESSAGE_ID_KEY,
                            messageId,
                            SEGMENTS_KEY,
                            String.valueOf(parts),
                            // The send's own answer is "Created", whatever the
                            // handset later does: the provider says nothing about
                            // delivery here and neither does this map.
                            PROVIDER_STATUS_KEY,
                            SmsGateDeliveryState.CREATED.name(),
                            NORMALIZED_STATUS_KEY,
                            "ACCEPTED"),
                    messageId);
        });
    }

    /**
     * {@code POST /search}: the uncertainty resolver for a verification code. Sends
     * nothing.
     *
     * <p>The provider answers with every message it holds for that destination on
     * that day, <em>including the text</em>. Ours is the entry whose text carries
     * the code we were trying to send, which is the only correlator this API
     * offers — there is no key to ask by. The comparison happens here, in memory,
     * and neither the code nor the text it came back in is logged, counted, or
     * put on the outcome.
     *
     * <p><strong>Not finding it is not proof it was never sent.</strong> The
     * {@code date} parameter names a day whose timezone the document does not
     * state, so a message sent either side of a boundary can be absent from a
     * search that is working perfectly. The answer is therefore "unconfirmed" and
     * never "not sent" — the second would license the resend this whole path
     * exists to prevent.
     */
    public ProviderOutcome resolve(SmsVerificationOperation operation, SmsAccount account, ProviderCall call) {
        Predicate<Map<String, Object>> carriesTheCode = entry -> {
            String sentText = text(entry.get("msg"));
            return sentText != null && sentText.contains(operation.code());
        };
        return search(
                operation.destination(),
                operation.issuedAt(),
                account,
                call,
                carriesTheCode,
                VasSmsGatewayAdapter::foundForVerification);
    }

    private ProviderOutcome search(
            String destination,
            Instant day,
            SmsAccount account,
            ProviderCall call,
            Predicate<Map<String, Object>> ours,
            java.util.function.Function<Map<String, Object>, ProviderOutcome> onFound) {

        SmsGateBody.Search body = new SmsGateBody.Search(
                requireConfigured(account.login(), "login"), call.credential(), destination, day.getEpochSecond());

        return http.post(call, SEARCH_PATH, NO_HEADERS, body, response -> {
            SmsGateCode code = SmsGateCode.of(
                    integer(response.get("status") instanceof Map<?, ?> status ? status.get("code") : null));
            if (code.effect() != SmsGateCode.Effect.ACCEPTED) {
                // The search itself was refused. That says nothing about the send,
                // so it stays uncertain rather than inheriting the search's own
                // refusal — a wrong key on the query is not a blacklisted
                // recipient on the message.
                return ProviderOutcome.uncertain("SMS_SEARCH_REFUSED", code.reasonCode());
            }

            for (Map<String, Object> entry : entries(response)) {
                if (ours.test(entry)) {
                    return onFound.apply(entry);
                }
            }

            return ProviderOutcome.uncertain(
                    "SMS_SEND_UNCONFIRMED", "The gateway holds no message matching this attempt for that destination");
        });
    }

    /**
     * The message was found, for a verification code. What state it is in decides
     * the answer.
     *
     * <p>Only the three states the provider states as failures are failures.
     * {@code Sent} means handed to the operator and never confirmed, and
     * {@code Unknown} is terminal-and-unresolved; both are ordinary for a
     * subscriber whose operator sends no receipt, and CDMA subscribers send none
     * at all. Reading either as "not delivered" would tear down a challenge whose
     * code is on a customer's phone.
     */
    private static ProviderOutcome foundForVerification(Map<String, Object> entry) {
        SmsGateDeliveryState state = SmsGateDeliveryState.of(integer(entry.get("status")));
        String messageId = text(entry.get("id"));

        if (state.isBlacklisted()) {
            return ProviderOutcome.rejected(
                    SmsGateCode.RECEIVER_IN_BLACKLIST.reasonCode(), "The operator's blacklist holds this destination");
        }
        if (state.isFailure()) {
            return ProviderOutcome.retryable(
                    "SMS_DELIVERY_FAILED", "The gateway reports " + state.name() + " for this message", null);
        }
        return ProviderOutcome.success(
                Map.of(MESSAGE_ID_KEY, messageId == null ? "" : messageId, DELIVERY_STATE_KEY, state.name()),
                messageId);
    }

    /**
     * The message was found, for a notification. The answer is a fact about that
     * message — including a failed or blacklisted one — never a licence to send
     * again: a retry is a second message and a second charge.
     */
    private static ProviderOutcome foundForNotification(Map<String, Object> entry) {
        SmsGateDeliveryState state = SmsGateDeliveryState.of(integer(entry.get("status")));
        String messageId = text(entry.get("id"));
        String normalized = state.normalizedStatus();

        Map<String, Object> normalizedOutcome = new java.util.LinkedHashMap<>();
        normalizedOutcome.put(MESSAGE_ID_KEY, messageId == null ? "" : messageId);
        normalizedOutcome.put(DELIVERY_STATE_KEY, state.name());
        normalizedOutcome.put(PROVIDER_STATUS_KEY, state.name());
        // An undocumented state is "handed over, nothing more known" rather than a
        // guess in either direction.
        normalizedOutcome.put(NORMALIZED_STATUS_KEY, normalized == null ? "ACCEPTED" : normalized);
        normalizedOutcome.put(HARD_BOUNCE_KEY, String.valueOf(state.isBlacklisted()));
        return ProviderOutcome.success(Map.copyOf(normalizedOutcome), messageId);
    }

    /**
     * {@code SmsGateway.invoke} refuses to reach this adapter at all unless
     * {@code account.isComplete()}, so login and sender are always present by
     * the time either method above runs; this only makes that invariant visible
     * to the checker.
     */
    private static String requireConfigured(@Nullable String value, String field) {
        return Objects.requireNonNull(value, () -> "SmsGateway called this adapter with no " + field + " configured");
    }

    private static SmsAccount accountOf(AccountContext account) {
        return new SmsAccount(account.value(JdbcAccountKeys.LOGIN), account.value(JdbcAccountKeys.SENDER));
    }

    private static ProviderOutcome classify(SmsGateCode code) {
        return switch (code.effect()) {
            case REFUSED -> ProviderOutcome.rejected(code.reasonCode(), describe(code));
            // No retryAfter, deliberately, and it matters most for SPAM: a delay
            // would turn the one signal that our own limiter is broken into
            // patient background traffic. See SmsGateCode.
            case RETRYABLE -> ProviderOutcome.retryable(code.reasonCode(), describe(code), null);
            case UNCERTAIN -> ProviderOutcome.uncertain(code.reasonCode(), describe(code));
            case ACCEPTED -> throw new IllegalStateException("An accepted code is not a failure");
        };
    }

    /**
     * The provider's numeric code and our name for it, and nothing else.
     *
     * <p>Never the provider's {@code description}: this provider echoes what it
     * was sent inside an error, and what it was sent is a phone number and a live
     * one-time code.
     */
    private static String describe(SmsGateCode code) {
        return code == SmsGateCode.UNDOCUMENTED
                ? "The gateway answered with no code this adapter recognises"
                : "gateway code %d (%s)".formatted(code.wireValue(), code.name());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    /**
     * A wire integer, whatever JSON shape it arrived in.
     *
     * <p>This provider is inconsistent about it — {@code /send} quotes its id as a
     * string while {@code /send_msgs} returns it as a number — so a cast would
     * work in testing and throw in production.
     */
    private static @Nullable Integer integer(@Nullable Object value) {
        return switch (value) {
            case Number number -> number.intValue();
            case String string -> parse(string);
            case null, default -> null;
        };
    }

    private static int integerOr(@Nullable Object value, int fallback) {
        Integer parsed = integer(value);
        return parsed == null ? fallback : parsed;
    }

    private static @Nullable Integer parse(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The same SHA-256 hex over UTF-8 that {@code notifications.domain.ContentHashes}
     * freezes onto a notification, restated here because integration may not import
     * another module's domain package. A test holds the two equal.
     */
    static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required by every JVM", unreachable);
        }
    }

    /** The configuration keys this provider's account is read from (see {@code JdbcSmsAccountLookup}). */
    private static final class JdbcAccountKeys {
        static final String LOGIN = "login";
        static final String SENDER = "sender";

        private JdbcAccountKeys() {}
    }
}
