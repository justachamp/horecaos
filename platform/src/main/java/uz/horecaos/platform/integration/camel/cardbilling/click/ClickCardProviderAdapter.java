package uz.horecaos.platform.integration.camel.cardbilling.click;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.commercial.api.CardAccount;
import uz.horecaos.platform.commercial.api.CardChargeUnansweredException;
import uz.horecaos.platform.commercial.api.CardCharger;
import uz.horecaos.platform.commercial.api.CardEnrolment;
import uz.horecaos.platform.commercial.api.CardProviderAdapter;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.cardbilling.CardBillingApiCall;
import uz.horecaos.platform.integration.camel.cardbilling.CardBillingApiTransport;
import uz.horecaos.platform.integration.camel.cardbilling.CardBillingCredentials;
import uz.horecaos.platform.integration.camel.cardbilling.CardBillingGateway;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * Click's card-token calls behind {@link CardProviderAdapter} (ADR 0095, ADR 0007), from {@code
 * docs/providers/click-card-token.md}.
 *
 * <p><strong>The card number never reaches HorecaOS.</strong> The first of Click's calls,
 * {@code card_token/request}, is made by the cardholder's browser straight to Click and carries only
 * the public {@code service_id}; {@link #begin} tells the browser where, and this class sees the
 * token that call returned and the code the bank texted, never a number.
 *
 * <p><strong>This class keeps the charge contract Click does not.</strong> {@code card_token/payment}
 * has no idempotency key and moves money. {@link CardCharger}'s contract is that a repeated key is one
 * charge, so before any charge this asks Click what it holds under the key (the key travels as the
 * charge's {@code transaction_parameter}, and {@code status_by_mti} reads it back), and only a key Click
 * provably holds nothing under is charged. Anything else that cannot be proved either way is a {@link
 * CardChargeUnansweredException}: the attempt stays {@code PENDING}, which costs a delay and never a
 * double charge. That is the whole of the policy, and it fails closed on every undocumented point (the
 * {@code error_code} table, the {@code status_by_mti} date, the value of a settled {@code payment_status})
 * until the sandbox walk pins them.
 *
 * <p>Nothing here is logged but a reason code. A credential, a token, a card fragment and a payment
 * identifier never reach a log line, a metric tag, an exception message or a failure reason (ADR 0028,
 * ADR 0029).
 */
@Component
public class ClickCardProviderAdapter implements CardProviderAdapter {

    /** The {@code provider_type} a platform card installation declares to be answered by this adapter. */
    public static final String PROVIDER_TYPE = "CLICK_CARD";

    static final String ADAPTER_VERSION = "click-card-token-v1";

    /** How long a browser has between {@link #begin} and {@link #confirm}. */
    static final Duration SESSION_LIFETIME = Duration.ofMinutes(15);

    /** Uzbekistan keeps one offset all year; {@code status_by_mti}'s date is read in it. */
    private static final ZoneOffset TASHKENT = ZoneOffset.ofHours(5);

    /**
     * Today and this many days before it: the date segment of {@code status_by_mti} is undocumented. An
     * installation setting ({@value #LOOKBACK_SETTING}) widens it for an account whose charges can stay
     * unresolved longer, for example across a suspension.
     */
    static final int DEFAULT_LOOKBACK_DAYS = 2;

    static final String LOOKBACK_SETTING = "lookbackDays";

    private static final int MAX_LOOKBACK_DAYS = 31;

    /** Click's card tokens are UUID-shaped; a stricter shape than the documentation gives would refuse a real one. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private static final Pattern SMS_CODE = Pattern.compile("\\d{4,8}");
    private static final Pattern SERVICE_ID = Pattern.compile("\\d{1,18}");
    private static final Pattern EXPIRY = Pattern.compile("(0[1-9]|1[0-2])(\\d{2})");

    /** The one {@code payment_status} the payment adapter also reads as money (ADR 0013). */
    private static final int STATUS_PAID = 2;

    /** Installation setting: Click {@code error_code} values that mean "no payment is held under that id". */
    static final String NOT_FOUND_CODES_SETTING = "notFoundErrorCodes";

    private static final String SESSION_KEY_LABEL = "horecaos.card-enrolment.v1";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final CardBillingApiTransport transport;
    private final SecretResolver secrets;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public ClickCardProviderAdapter(
            CardBillingApiTransport transport, SecretResolver secrets, ObjectMapper mapper, Clock clock) {
        this.transport = transport;
        this.secrets = secrets;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public boolean usableInProduction() {
        return true;
    }

    @Override
    public boolean requiresSecret() {
        return true;
    }

    // ------------------------------------------------------------------ enrolment

    /**
     * Opens one enrolment: nothing is asked of Click. The reply tells the browser where to tokenise and
     * with which public identifier, and carries a session reference the server signs, so a confirmation
     * can only come from a session this account opened for this tenant, inside its lifetime.
     *
     * <p>Answers {@code NotConfigured} when the account cannot be used at all (no service id, no
     * environment, no credential behind the reference), which is the earliest point a tenant can be told.
     */
    @Override
    public CardEnrolment.BeginOutcome begin(CardAccount account, UUID tenantId) {
        Optional<Config> config = config(account);
        if (config.isEmpty()) {
            return new CardEnrolment.BeginOutcome.NotConfigured();
        }
        byte[] key = sessionKey(config.get());
        if (key == null) {
            return new CardEnrolment.BeginOutcome.NotConfigured();
        }
        Instant expiresAt = clock.instant().plus(SESSION_LIFETIME);
        Map<String, String> client = new LinkedHashMap<>();
        client.put("provider", PROVIDER_TYPE);
        client.put("handoff", "BROWSER_TOKENISE");
        client.put("tokeniseUrl", config.get().baseUrl() + "/card_token/request");
        client.put("serviceId", config.get().serviceId());
        client.put("temporary", "0");
        client.put("expiryFormat", "MMYY");
        return new CardEnrolment.BeginOutcome.Begun(issueSession(key, tenantId, expiresAt), null, client, expiresAt);
    }

    @Override
    public CardEnrolment.ConfirmOutcome confirm(
            CardAccount account,
            UUID tenantId,
            String sessionReference,
            String providerToken,
            String verificationCode) {
        // The expiry is not returned by Click's verify, so a confirmation without it cannot be a card
        // HorecaOS can warn about before it lapses.
        return new CardEnrolment.ConfirmOutcome.Refused("CARD_EXPIRY_REQUIRED");
    }

    @Override
    public CardEnrolment.ConfirmOutcome confirm(
            CardAccount account,
            UUID tenantId,
            String sessionReference,
            String providerToken,
            String verificationCode,
            @Nullable String cardExpiry) {
        Optional<Config> config = config(account);
        if (config.isEmpty()) {
            return new CardEnrolment.ConfirmOutcome.NotConfigured();
        }
        byte[] key = sessionKey(config.get());
        if (key == null) {
            return new CardEnrolment.ConfirmOutcome.NotConfigured();
        }
        switch (checkSession(key, tenantId, sessionReference)) {
            case EXPIRED -> {
                return new CardEnrolment.ConfirmOutcome.Refused("SESSION_EXPIRED");
            }
            case UNKNOWN -> {
                return new CardEnrolment.ConfirmOutcome.Refused("SESSION_UNKNOWN");
            }
            case VALID -> {
                // fall through to the checks below
            }
        }
        if (!TOKEN.matcher(providerToken).matches()) {
            return new CardEnrolment.ConfirmOutcome.Refused("UNKNOWN_CARD_TOKEN");
        }
        if (!SMS_CODE.matcher(verificationCode).matches()) {
            return new CardEnrolment.ConfirmOutcome.Refused("WRONG_CODE");
        }
        YearMonth expiry = expiryOf(cardExpiry);
        if (expiry == null) {
            return new CardEnrolment.ConfirmOutcome.Refused("CARD_EXPIRY_REQUIRED");
        }
        if (expiry.isBefore(YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC)))) {
            return new CardEnrolment.ConfirmOutcome.Refused("CARD_EXPIRED");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_id", Long.parseLong(config.get().serviceId()));
        body.put("card_token", providerToken);
        // Click's example sends the code as a number. A code with a leading zero would be corrupted by
        // that, so it is sent as the text the cardholder was given.
        body.put("sms_code", verificationCode.startsWith("0") ? verificationCode : Long.parseLong(verificationCode));
        ProviderOutcome outcome =
                exchange(config.get(), account, "card_token.verify", "POST", "/card_token/verify", body);

        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            return new CardEnrolment.ConfirmOutcome.Refused(unavailableReason(outcome));
        }
        Map<String, Object> answer = outcome.normalized();
        Long code = errorCode(answer);
        if (code == null) {
            return new CardEnrolment.ConfirmOutcome.Refused("PROVIDER_ANSWER_UNREADABLE");
        }
        if (code != 0L) {
            return new CardEnrolment.ConfirmOutcome.Refused("CLICK_ERROR_" + code);
        }
        String last4 = lastFour(text(answer.get("card_number")));
        if (last4 == null) {
            return new CardEnrolment.ConfirmOutcome.Refused("PROVIDER_ANSWER_UNREADABLE");
        }
        return new CardEnrolment.ConfirmOutcome.Enrolled(
                providerToken,
                last4,
                brandOf(text(answer.get("card_number"))),
                expiry.getMonthValue(),
                expiry.getYear());
    }

    @Override
    public CardEnrolment.RevokeOutcome revoke(CardAccount account, String providerToken) {
        Optional<Config> config = config(account);
        if (config.isEmpty()) {
            return new CardEnrolment.RevokeOutcome.NotConfigured();
        }
        if (!TOKEN.matcher(providerToken).matches()) {
            return new CardEnrolment.RevokeOutcome.Failed("UNKNOWN_TOKEN");
        }
        ProviderOutcome outcome = exchange(
                config.get(),
                account,
                "card_token.delete",
                "DELETE",
                "/card_token/" + config.get().serviceId() + "/" + providerToken,
                null);
        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            return new CardEnrolment.RevokeOutcome.Failed(unavailableReason(outcome));
        }
        Long code = errorCode(outcome.normalized());
        return code != null && code == 0L
                ? new CardEnrolment.RevokeOutcome.Revoked()
                : new CardEnrolment.RevokeOutcome.Failed(
                        code == null ? "PROVIDER_ANSWER_UNREADABLE" : "CLICK_ERROR_" + code);
    }

    // --------------------------------------------------------------------- charge

    @Override
    public CardCharger.Outcome charge(
            CardAccount account,
            UUID tenantId,
            @Nullable String providerToken,
            long amountMinor,
            String currency,
            String idempotencyKey) {
        if (providerToken == null) {
            return new CardCharger.Outcome.Failed("NO_CARD_ON_FILE");
        }
        Optional<Config> config = config(account);
        if (config.isEmpty()) {
            return new CardCharger.Outcome.NotConfigured();
        }
        if (!TOKEN.matcher(providerToken).matches()) {
            return new CardCharger.Outcome.Failed("UNKNOWN_TOKEN");
        }
        if (!"UZS".equals(currency)) {
            return new CardCharger.Outcome.Failed("CURRENCY_NOT_SUPPORTED");
        }
        if (amountMinor <= 0) {
            return new CardCharger.Outcome.Failed("AMOUNT_NOT_POSITIVE");
        }

        // The idempotency contract, kept here because Click has none: only a key Click provably holds
        // nothing under may be charged.
        Held held = lookup(config.get(), account, idempotencyKey);
        switch (held.state()) {
            case PAID -> {
                return new CardCharger.Outcome.Succeeded(held.requirePaymentId());
            }
            case DECLINED -> {
                return new CardCharger.Outcome.Failed("PAYMENT_DECLINED");
            }
            case IN_PROCESSING -> throw new CardChargeUnansweredException("PAYMENT_IN_PROCESSING");
            case UNKNOWN -> throw new CardChargeUnansweredException("PAYMENT_LOOKUP_UNAVAILABLE");
            case NOT_USABLE -> {
                return new CardCharger.Outcome.NotConfigured();
            }
            case NOT_HELD -> {
                // proved: nothing is held under this key, so this is a first ask
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_id", Long.parseLong(config.get().serviceId()));
        body.put("card_token", providerToken);
        // Whole som: UZS is transacted in whole som and ADR 0018 stores whole som, so minor units are som.
        body.put("amount", amountMinor);
        body.put("transaction_parameter", idempotencyKey);
        ProviderOutcome outcome =
                exchange(config.get(), account, "card_token.payment", "POST", "/card_token/payment", body);
        return switch (outcome.status()) {
            case SUCCESS -> interpretCharge(outcome.normalized());
            case REJECTED -> rejectedCharge(outcome);
            case RETRYABLE, UNCERTAIN ->
                throw new CardChargeUnansweredException(
                        outcome.errorCode() == null ? "PROVIDER_UNAVAILABLE" : outcome.errorCode());
        };
    }

    @Override
    public CardCharger.StatusOutcome status(CardAccount account, String idempotencyKey) {
        Optional<Config> config = config(account);
        if (config.isEmpty()) {
            return new CardCharger.StatusOutcome.NotSucceeded();
        }
        Held held = lookup(config.get(), account, idempotencyKey);
        return held.state() == HeldState.PAID
                ? new CardCharger.StatusOutcome.Succeeded(held.requirePaymentId())
                : new CardCharger.StatusOutcome.NotSucceeded();
    }

    private CardCharger.Outcome interpretCharge(Map<String, Object> answer) {
        Long code = errorCode(answer);
        String paymentId = text(answer.get("payment_id"));
        if (code == null) {
            throw new CardChargeUnansweredException("CHARGE_ANSWER_UNREADABLE");
        }
        if (code != 0L) {
            if (paymentId != null) {
                // An error and a payment in one answer: not a decline anybody can stand behind.
                throw new CardChargeUnansweredException("CHARGE_ANSWER_CONTRADICTORY");
            }
            return new CardCharger.Outcome.Failed("CLICK_ERROR_" + code);
        }
        if (paymentId == null) {
            throw new CardChargeUnansweredException("CHARGE_ANSWER_WITHOUT_PAYMENT");
        }
        return switch (stateOf(answer.get("payment_status"))) {
            case PAID -> new CardCharger.Outcome.Succeeded(paymentId);
            case DECLINED -> new CardCharger.Outcome.Failed("PAYMENT_DECLINED");
            // Created or in processing is not money. The next pass finds it paid, or does not.
            default -> throw new CardChargeUnansweredException("PAYMENT_IN_PROCESSING");
        };
    }

    private CardCharger.Outcome rejectedCharge(ProviderOutcome outcome) {
        if (cannotFormTheCall(outcome)) {
            // The platform could not even form the call, or Click refused our credential: the account is not
            // usable, which is what NotConfigured says. Nothing moved.
            return new CardCharger.Outcome.NotConfigured();
        }
        // Click answered with an HTTP refusal (a 4xx): it did not act, and a 4xx is never retried
        // (docs/providers/click-merchant-api.md 5.3).
        return new CardCharger.Outcome.Failed("PROVIDER_REJECTED");
    }

    // -------------------------------------------------------------------- lookup

    /**
     * What Click holds under one of our identifiers, over the days its date segment might mean.
     *
     * <p>A payment found on any day beats a lookup that failed on another; "nothing held" is concluded only
     * when every day was answered and none held one.
     */
    private Held lookup(Config config, CardAccount account, String key) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), TASHKENT);
        boolean unproven = false;
        for (int back = 0; back <= config.lookbackDays(); back++) {
            LocalDate date = today.minusDays(back);
            ProviderOutcome byTransaction = exchange(
                    config,
                    account,
                    "payment.status_by_mti",
                    "GET",
                    "/payment/status_by_mti/" + config.serviceId() + "/" + encode(key) + "/" + date,
                    null,
                    Set.of(404));
            if (cannotFormTheCall(byTransaction)) {
                return new Held(HeldState.NOT_USABLE, null);
            }
            if (byTransaction.status() != ProviderOutcome.Status.SUCCESS) {
                unproven = true;
                continue;
            }
            Map<String, Object> answer = byTransaction.normalized();
            if (Integer.valueOf(404).equals(answer.get(ProviderHttpClient.STATUS_KEY))) {
                continue;
            }
            Long code = errorCode(answer);
            String paymentId = text(answer.get("payment_id"));
            if (code != null && code == 0L && paymentId != null) {
                return new Held(readState(config, account, paymentId), paymentId);
            }
            if (code != null && code == 0L) {
                // Success and no payment_id: the shape the payment adapter already reads as "nothing held yet"
                // (Click's own worked example for it carries neither a payment nor an error).
                continue;
            }
            if (code != null && config.notFoundCodes().contains(code)) {
                continue;
            }
            // A code nobody has said means "none", or no code at all: not proof of absence.
            unproven = true;
        }
        return unproven ? new Held(HeldState.UNKNOWN, null) : new Held(HeldState.NOT_HELD, null);
    }

    private HeldState readState(Config config, CardAccount account, String paymentId) {
        ProviderOutcome outcome = exchange(
                config,
                account,
                "payment.status",
                "GET",
                "/payment/status/" + config.serviceId() + "/" + encode(paymentId),
                null);
        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            return HeldState.IN_PROCESSING;
        }
        Long code = errorCode(outcome.normalized());
        if (code == null || code != 0L) {
            return HeldState.IN_PROCESSING;
        }
        return stateOf(outcome.normalized().get("payment_status"));
    }

    /**
     * Refused before the provider could act, for a reason that is the account's and not the card's: nothing was
     * sent (no credential, an unapproved host) or Click refused the credential itself. Either way the account is
     * not usable, which is what {@code NotConfigured} says.
     */
    private static boolean cannotFormTheCall(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.REJECTED
                && outcome.errorCode() != null
                && (CardBillingGateway.NOTHING_WAS_SENT.contains(outcome.errorCode())
                        || "PROVIDER_AUTHENTICATION".equals(outcome.errorCode()));
    }

    private static HeldState stateOf(@Nullable Object raw) {
        Long value = whole(raw);
        if (value == null) {
            return HeldState.IN_PROCESSING;
        }
        if (value < 0) {
            return HeldState.DECLINED;
        }
        // Only 2 is money; 0 (created), 1 (in processing) and any value Click has not documented are not.
        return value == STATUS_PAID ? HeldState.PAID : HeldState.IN_PROCESSING;
    }

    private enum HeldState {
        PAID,
        DECLINED,
        IN_PROCESSING,
        UNKNOWN,
        NOT_HELD,
        /** The platform could not form the call at all: no credential, an unapproved host. Nothing was sent. */
        NOT_USABLE
    }

    private record Held(HeldState state, @Nullable String paymentId) {

        String requirePaymentId() {
            if (paymentId == null) {
                throw new IllegalStateException("A payment was reported held without an identifier");
            }
            return paymentId;
        }
    }

    // --------------------------------------------------------------- the wire

    private ProviderOutcome exchange(
            Config config,
            CardAccount account,
            String operation,
            String method,
            String path,
            @Nullable Map<String, Object> body) {
        return exchange(config, account, operation, method, path, body, Set.of());
    }

    private ProviderOutcome exchange(
            Config config,
            CardAccount account,
            String operation,
            String method,
            String path,
            @Nullable Map<String, Object> body,
            Set<Integer> acceptedStatuses) {
        CardBillingApiCall call = new CardBillingApiCall(
                account.installationId(),
                PROVIDER_TYPE,
                config.environmentCode(),
                operation,
                method,
                config.baseUrl(),
                path,
                config.secretReference(),
                secret -> {
                    CardBillingCredentials credentials = CardBillingCredentials.parse(mapper, secret);
                    Map<String, String> headers = Map.of(
                            "Auth",
                            authHeader(
                                    credentials.required("merchantUserId"),
                                    credentials.required("secretKey"),
                                    clock.instant().getEpochSecond()));
                    return body == null
                            ? CardBillingApiCall.Request.of(headers)
                            : CardBillingApiCall.Request.json(headers, body);
                },
                acceptedStatuses,
                null,
                null);
        return transport.exchange(call);
    }

    /** {@code Auth: merchant_user_id:sha1(timestamp + secret_key):timestamp}, as Click documents it. */
    public static String authHeader(String merchantUserId, String secretKey, long epochSeconds) {
        String timestamp = Long.toString(epochSeconds);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-1").digest((timestamp + secretKey).getBytes(StandardCharsets.UTF_8));
            return merchantUserId + ":" + HexFormat.of().formatHex(digest) + ":" + timestamp;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is part of every Java platform", impossible);
        }
    }

    /** Nothing about the cause but a code: a transport reason, never Click's free text. */
    private static String unavailableReason(ProviderOutcome outcome) {
        return cannotFormTheCall(outcome) ? "PROVIDER_NOT_CONNECTED" : "PROVIDER_UNAVAILABLE";
    }

    // ----------------------------------------------------------------- session

    private enum SessionCheck {
        VALID,
        EXPIRED,
        UNKNOWN
    }

    private String issueSession(byte[] key, UUID tenantId, Instant expiresAt) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        String payload = "v1|" + tenantId + "|" + expiresAt.getEpochSecond() + "|" + B64.encodeToString(nonce);
        return B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(hmac(key, payload));
    }

    private SessionCheck checkSession(byte[] key, UUID tenantId, String reference) {
        try {
            int dot = reference.indexOf('.');
            if (dot <= 0 || dot == reference.length() - 1) {
                return SessionCheck.UNKNOWN;
            }
            String payload = new String(B64D.decode(reference.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] signature = B64D.decode(reference.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, hmac(key, payload))) {
                return SessionCheck.UNKNOWN;
            }
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 4
                    || !"v1".equals(parts[0])
                    || !tenantId.toString().equals(parts[1])) {
                return SessionCheck.UNKNOWN;
            }
            return Instant.ofEpochSecond(Long.parseLong(parts[2])).isAfter(clock.instant())
                    ? SessionCheck.VALID
                    : SessionCheck.EXPIRED;
        } catch (RuntimeException malformed) {
            return SessionCheck.UNKNOWN;
        }
    }

    /**
     * The key sessions are signed with, derived from the merchant's own secret key so it needs no
     * storage of its own and survives a restart and a second instance. Null when the credential cannot be
     * read, which is the account not being usable.
     */
    private byte @Nullable [] sessionKey(Config config) {
        try {
            String secret = secrets.resolve(SecretReference.parse(config.secretReference()))
                    .reveal();
            byte[] secretKey = CardBillingCredentials.parse(mapper, secret)
                    .required("secretKey")
                    .getBytes(StandardCharsets.UTF_8);
            return hmac(secretKey, SESSION_KEY_LABEL);
        } catch (RuntimeException unusable) {
            return null;
        }
    }

    private static byte[] hmac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is part of every Java platform", impossible);
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Config(
            String serviceId,
            String baseUrl,
            String environmentCode,
            String secretReference,
            Set<Long> notFoundCodes,
            int lookbackDays) {}

    private static Optional<Config> config(CardAccount account) {
        String serviceId = account.externalAccountReference();
        if (serviceId == null
                || !SERVICE_ID.matcher(serviceId).matches()
                || account.baseUrl() == null
                || account.environmentCode() == null
                || account.secretReference() == null) {
            return Optional.empty();
        }
        return Optional.of(new Config(
                serviceId,
                account.baseUrl(),
                account.environmentCode(),
                account.secretReference(),
                notFoundCodes(account.configuration().get(NOT_FOUND_CODES_SETTING)),
                lookbackDays(account.configuration().get(LOOKBACK_SETTING))));
    }

    private static Set<Long> notFoundCodes(@Nullable Object configured) {
        if (!(configured instanceof List<?> list)) {
            return Set.of();
        }
        return list.stream()
                .map(ClickCardProviderAdapter::whole)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static int lookbackDays(@Nullable Object configured) {
        Long days = whole(configured);
        return days == null || days < 0 || days > MAX_LOOKBACK_DAYS ? DEFAULT_LOOKBACK_DAYS : days.intValue();
    }

    private static @Nullable YearMonth expiryOf(@Nullable String cardExpiry) {
        if (cardExpiry == null) {
            return null;
        }
        var matcher = EXPIRY.matcher(cardExpiry);
        if (!matcher.matches()) {
            return null;
        }
        return YearMonth.of(2000 + Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)));
    }

    private static @Nullable Long errorCode(Map<String, Object> answer) {
        return whole(answer.get("error_code"));
    }

    private static @Nullable Long whole(@Nullable Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && text.strip().matches("-?\\d{1,18}")) {
            return Long.parseLong(text.strip());
        }
        return null;
    }

    private static @Nullable String text(@Nullable Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        if (raw instanceof Number number) {
            return number.toString();
        }
        return null;
    }

    /** The last four digits of a masked number such as {@code 8600 55** **** 3244}. */
    private static @Nullable String lastFour(@Nullable String masked) {
        if (masked == null) {
            return null;
        }
        String digits = masked.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }

    /** The scheme, only where the leading digits Click leaves unmasked name one it serves. */
    private static @Nullable String brandOf(@Nullable String masked) {
        if (masked == null) {
            return null;
        }
        String digits = masked.replaceAll("[^0-9]", "");
        if (digits.startsWith("8600")) {
            return "UZCARD";
        }
        if (digits.startsWith("9860")) {
            return "HUMO";
        }
        return null;
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "ClickCardProviderAdapter[" + ADAPTER_VERSION + "]";
    }
}
