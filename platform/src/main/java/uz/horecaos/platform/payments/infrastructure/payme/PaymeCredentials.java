package uz.horecaos.platform.payments.infrastructure.payme;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.payments.infrastructure.RotationAwareSecrets;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.payments.domain.ProviderBinding;

/**
 * Basic authentication for the Payme endpoint, done by hand and on purpose
 * (ADR 0013, ADR 0028).
 *
 * <p><strong>Why this is not {@code httpBasic()}.</strong> Spring Security's stock
 * mechanism answers a bad credential with a bodyless HTTP 401. Payme requires
 * every response to be HTTP 200 — it reads any other status as {@code -32400} —
 * and its very first sandbox test ("Неверная авторизация") expects
 * {@code -32504} inside a JSON-RPC error body with the request's own {@code id}
 * echoed back. A 401 fails that test before a single som has moved, which is
 * exactly what Payme's own Java template does. So the endpoint is
 * {@code permitAll} at the filter chain and authenticated here, before any method
 * is dispatched and for every method including {@code GetStatement}.
 *
 * <p>The credential itself never appears in a column, a log line, or a test
 * fixture. It is an ADR 0028 reference on the binding, resolved at call time, so a
 * key rotation changes what is behind the reference and rewrites no row.
 *
 * <p>The comparison is over SHA-256 digests rather than over the strings. Payme's
 * PHP template uses {@code !=}, which leaks the length of the matching prefix;
 * digesting first makes the comparison constant-time regardless of how far the two
 * agree and regardless of their lengths.
 */
@Component
public class PaymeCredentials {

    private static final Logger log = LoggerFactory.getLogger(PaymeCredentials.class);

    private static final String BASIC_PREFIX = "Basic ";

    private final RotationAwareSecrets secrets;
    private final String login;

    /**
     * @param login Payme's Basic-auth user. Payme's own PHP config states it
     *              outright — "Login is always Paycom" — and both templates use it,
     *              while the prose docs say only to ask a Payme specialist. It is
     *              therefore a default with an override rather than a constant, so
     *              that a per-merchant login is a property change and not a release
     */
    public PaymeCredentials(RotationAwareSecrets secrets,
            @Value("${horecaos.payments.payme.login:Paycom}") String login) {
        this.secrets = secrets;
        this.login = login;
    }

    /**
     * @throws PaymeRpcException {@code -32504}, which the caller renders in an
     *                           HTTP 200 body
     */
    public void authenticate(ProviderBinding binding, String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BASIC_PREFIX)) {
            throw PaymeErrors.insufficientPrivilege();
        }

        String presented;
        try {
            byte[] decoded = Base64.getDecoder()
                    .decode(authorizationHeader.substring(BASIC_PREFIX.length()).strip());
            presented = new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // A header that is not base64 at all. Same answer as a wrong key: this
            // endpoint tells an unauthenticated caller nothing but -32504.
            throw PaymeErrors.insufficientPrivilege();
        }

        // Not disposed. The resolver caches and hands the same instance to every
        // caller, so clearing it here would blank the credential underneath the
        // next request; disposal is the resolver's, per its contract.
        byte[] offered = digest(presented);
        if (MessageDigest.isEqual(digest(login + ":" + secrets.cached(binding.secretReference())
                .reveal()), offered)) {
            return;
        }

        // ADR 0028: a mismatch is retried once against a freshly resolved secret
        // before it is called an authentication failure. This is the case that
        // read exists for, and on this endpoint it is money rather than
        // availability. The resolvers cache for five minutes, so a cashbox key
        // rotated at T leaves this process comparing against the old value until
        // T+5min while Payme is already presenting the new one — and the answer
        // HorecaOS gives is -32504, a *definite* refusal, to every method including
        // PerformTransaction. Payme does not read that as a lost response to retry
        // into; it reads it as HorecaOS disowning a transaction whose card has
        // already been debited.
        //
        // Through RotationAwareSecrets rather than resolveFresh directly: this
        // endpoint is reachable by anyone, a wrong Basic header costs an attacker
        // nothing, and a fresh read per failure would aim the public endpoint at
        // the secrets manager.
        if (secrets.fresh(binding.secretReference())
                .map(rotated -> MessageDigest.isEqual(digest(login + ":" + rotated.reveal()), offered))
                .orElse(false)) {
            return;
        }

        // The binding's toString omits the account reference and the secret
        // reference, so this line names a row and never a restaurant's cashbox.
        log.warn("Payme rejected a credential on {}.", binding);
        throw PaymeErrors.insufficientPrivilege();
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory in every JRE. If it is genuinely absent the
            // process cannot authenticate anything and must not fall back to a
            // comparison that leaks.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
