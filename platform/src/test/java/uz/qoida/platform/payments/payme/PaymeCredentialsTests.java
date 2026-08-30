package uz.qoida.platform.payments.payme;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.iam.api.secrets.SecretCategory;
import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.qoida.platform.payments.domain.PaymentProviderType;
import uz.qoida.platform.payments.domain.ProviderBinding;
import uz.qoida.platform.payments.infrastructure.payme.PaymeCredentials;
import uz.qoida.platform.payments.infrastructure.payme.PaymeRpcException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Basic credential, checked by hand.
 *
 * <p>Every failure below is {@code -32504} and nothing else, whatever went wrong: a
 * missing header, a header that is not base64 at all, a login that is not
 * {@code Paycom}, a key that is one character out. The endpoint tells an
 * unauthenticated caller which of those it was in no way at all.
 */
class PaymeCredentialsTests {

    /**
     * Thirty-six characters, the length Payme documents for a cashbox key, and
     * supplied through the secret resolver rather than sat in a column.
     */
    private static final String KEY = "fkWW6UNrzvzyV6DhrdHJ6aEhr3dRcvJYkaGx";

    private static final SecretReference SECRET =
            new SecretReference("test", SecretCategory.PROVIDER_PAYMENT, "payme", "cashbox-one");

    /** Mutable, so a rotation can happen mid-test the way it happens mid-service. */
    private final Map<String, String> vault = new java.util.HashMap<>(
            Map.of("qoida.secrets.provider_payment.payme.cashbox-one", KEY));

    private final Clock clock =
            Clock.fixed(java.time.Instant.parse("2026-08-22T09:00:00Z"), ZoneOffset.UTC);

    private final PaymeCredentials credentials = new PaymeCredentials(
            new uz.qoida.platform.payments.infrastructure.RotationAwareSecrets(
                    new EnvironmentSecretResolver(vault::get, clock), clock),
            "Paycom");

    private final ProviderBinding binding = new ProviderBinding(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), PaymentProviderType.PAYME, UUID.randomUUID(),
            UUID.randomUUID(), "587f72c72cac0d162c722ae2", null, null, SECRET,
            "payme-cashbox-one",
            false, true, LocalDate.of(2026, 1, 1), null);

    /**
     * The login is the literal string {@code Paycom}.
     *
     * <p>The prose docs say only "ask a Payme technical specialist"; Payme's PHP
     * config states it outright and both templates use it. It is therefore a default
     * with an override rather than a constant, so a per-merchant login would be a
     * property change and not a release (U3).
     */
    @Test
    @DisplayName("Paycom and the cashbox key are accepted")
    void acceptsTheDocumentedCredential() {
        assertThatCode(() -> credentials.authenticate(binding, basic("Paycom:" + KEY)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a key one character out is refused")
    void refusesANearMissKey() {
        assertThatThrownBy(() -> credentials.authenticate(binding,
                basic("Paycom:" + KEY.substring(0, KEY.length() - 1) + "Y")))
                .isInstanceOfSatisfying(PaymeRpcException.class,
                        failure -> assertThat(failure.code()).isEqualTo(-32504));
    }

    @Test
    @DisplayName("the right key under the wrong login is refused")
    void refusesAWrongLogin() {
        assertThatThrownBy(() -> credentials.authenticate(binding, basic("Qoida:" + KEY)))
                .isInstanceOf(PaymeRpcException.class);
    }

    /**
     * A header that is not base64 is the same answer, not a fault.
     *
     * <p>Left to propagate, an {@code IllegalArgumentException} from the decoder
     * would surface as an HTTP 500 — which Payme reads as {@code -32400}, telling it
     * the merchant's systems are broken when in fact somebody sent a malformed
     * header.
     */
    @Test
    @DisplayName("a malformed header is -32504 rather than a decoder failure")
    void refusesAMalformedHeader() {
        assertThatThrownBy(() -> credentials.authenticate(binding, "Basic ***not base64***"))
                .isInstanceOfSatisfying(PaymeRpcException.class,
                        failure -> assertThat(failure.code()).isEqualTo(-32504));
    }

    @Test
    @DisplayName("no header at all is -32504")
    void refusesAMissingHeader() {
        assertThatThrownBy(() -> credentials.authenticate(binding, null))
                .isInstanceOf(PaymeRpcException.class);
        assertThatThrownBy(() -> credentials.authenticate(binding, "Bearer a-token"))
                .isInstanceOf(PaymeRpcException.class);
    }

    /**
     * A binding whose secret has no value behind it fails as a fault, not as an
     * authentication answer.
     *
     * <p>The distinction is worth keeping. A missing secret is a deployment error and
     * belongs in the {@code -32400} bucket where somebody will look at it; folding it
     * into {@code -32504} would present a whole cashbox's misconfiguration as Payme
     * sending bad credentials, and the alert that fires would name the wrong problem.
     */
    @Test
    @DisplayName("an unconfigured cashbox key is a fault, not a rejected credential")
    void anUnconfiguredKeyIsAFault() {
        PaymeCredentials unconfigured = new PaymeCredentials(
                new uz.qoida.platform.payments.infrastructure.RotationAwareSecrets(
                        new EnvironmentSecretResolver(Map.<String, String>of()::get,
                                Clock.systemUTC()),
                        Clock.systemUTC()),
                "Paycom");

        assertThatThrownBy(() -> unconfigured.authenticate(binding, basic("Paycom:" + KEY)))
                .isNotInstanceOf(PaymeRpcException.class);
    }

    private static String basic(String credential) {
        return "Basic " + Base64.getEncoder()
                .encodeToString(credential.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The case the fresh read exists for, and it is money rather than availability.
     *
     * <p>The resolvers cache for five minutes. Without a fresh retry, a cashbox key
     * rotated at T leaves this process comparing against the old value until
     * T+5min while Payme already presents the new one — and the answer Qoida gives
     * is {@code -32504}, a <em>definite</em> refusal, to every method including
     * {@code PerformTransaction}. Payme does not read that as a lost response to
     * retry into; it reads it as Qoida disowning a transaction whose card has
     * already been debited.
     */
    @Test
    @DisplayName("a key rotated behind the reference authenticates without waiting out the cache")
    void aRotatedKeyIsPickedUpImmediately() {
        String rotated = "9pQm2XbaLe4kTsHvUdNwRcZyBjFgKoAiEuXt";
        assertThatCode(() -> credentials.authenticate(binding, basic("Paycom:" + KEY)))
                .doesNotThrowAnyException();

        vault.put("qoida.secrets.provider_payment.payme.cashbox-one", rotated);

        assertThatCode(() -> credentials.authenticate(binding, basic("Paycom:" + rotated)))
                .as("the cached value is stale and the fresh read is what saves the payment")
                .doesNotThrowAnyException();
    }

    /**
     * The fresh read must not become an amplifier. This endpoint has no auth
     * header worth the name — anyone who finds the callback segment can send a
     * wrong key — so a fresh secrets-manager round trip per failure would point
     * the public endpoint at the platform's own secret store.
     */
    @Test
    @DisplayName("a flood of wrong keys does not drive a fresh read per request")
    void repeatedFailuresDoNotHammerTheSecretsManager() {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        PaymeCredentials counted = new PaymeCredentials(
                new uz.qoida.platform.payments.infrastructure.RotationAwareSecrets(
                        new EnvironmentSecretResolver(key -> {
                            reads.incrementAndGet();
                            return vault.get(key);
                        }, clock), clock),
                "Paycom");

        for (int attempt = 0; attempt < 50; attempt++) {
            assertThatThrownBy(() -> counted.authenticate(binding, basic("Paycom:wrong")))
                    .isInstanceOf(PaymeRpcException.class);
        }

        assertThat(reads.get())
                .as("one cached fill plus at most one fresh read inside the cooldown")
                .isLessThanOrEqualTo(2);
    }

}
