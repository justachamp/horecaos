package uz.horecaos.platform.iam.application.mfa;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authenticated encryption for the two short-lived tokens ADR 0148 hands a browser instead of
 * keeping a row: the sealed secret of an enrolment in progress, and the enrolment ticket a
 * refused sign-in carries.
 *
 * <p>AES-256-GCM under a key derived from a secret held as an ADR 0028 reference, a fresh random
 * nonce per token, and the purpose and the account's subject id bound in as associated data. That
 * binding is what makes a token <em>single-purpose</em> and <em>useless on another account</em>:
 * a ticket cannot be presented where a sealed secret is wanted, and a token sealed for one
 * subject fails authentication for any other, rather than being checked after it has been read.
 * Expiry is inside the sealed payload, so it cannot be extended by whoever holds the token.
 *
 * <p>Nothing here is logged. A token carries the enrolment's secret in a form only this process
 * can open; the secret itself reaches the browser separately, once, as the QR code.
 */
public final class SealedTokens {

    /** What a token may be used for. Part of the associated data, so it cannot be swapped. */
    public enum Purpose {
        /** The generated authenticator secret of an enrolment in progress. */
        ENROLMENT("enrolment"),
        /** Permission to call the enrolment endpoints and nothing else. */
        TICKET("ticket");

        private final String code;

        Purpose(String code) {
            this.code = code;
        }
    }

    /** A token opened and found valid: the claims it was sealed with. */
    public record Opened(Instant expiresAt, Map<String, String> claims) {}

    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String KEY_CONTEXT = "horecaos.iam.mfa.sealing.v1:";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SecretResolver secrets;
    private final SecretReference keyReference;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SealedTokens(SecretResolver secrets, SecretReference keyReference, Clock clock) {
        this.secrets = secrets;
        this.keyReference = keyReference;
        this.clock = clock;
    }

    public String seal(Purpose purpose, String subject, Map<String, String> claims, Duration lifetime) {
        Instant expiresAt = clock.instant().plus(lifetime);
        Map<String, String> payload = new LinkedHashMap<>(claims);
        payload.put("exp", Long.toString(expiresAt.getEpochSecond()));
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(purpose, subject));
            byte[] sealed = cipher.doFinal(JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + NONCE_BYTES + sealed.length];
            out[0] = VERSION;
            System.arraycopy(nonce, 0, out, 1, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, 1 + NONCE_BYTES, sealed.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("The enrolment token could not be sealed", failure);
        }
    }

    /**
     * Opens a token for this purpose and this subject.
     *
     * @throws ApiException {@link ErrorCode#INVALID_REQUEST}, one message, for a token that is
     *     malformed, forged, of another purpose, sealed for another account or expired: which of
     *     those it is must not be learnable
     */
    public Opened open(Purpose purpose, String subject, @Nullable String token) {
        if (token == null || token.isBlank()) {
            throw invalid();
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(token.strip());
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        if (raw.length < 1 + NONCE_BYTES + TAG_BITS / 8 || raw[0] != VERSION) {
            throw invalid();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(raw, 1, 1 + NONCE_BYTES)));
            cipher.updateAAD(associatedData(purpose, subject));
            byte[] plain = cipher.doFinal(raw, 1 + NONCE_BYTES, raw.length - 1 - NONCE_BYTES);
            @SuppressWarnings("unchecked")
            Map<String, String> claims = JSON.readValue(new String(plain, StandardCharsets.UTF_8), Map.class);
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(claims.get("exp")));
            if (!clock.instant().isBefore(expiresAt)) {
                throw invalid();
            }
            return new Opened(expiresAt, Map.copyOf(claims));
        } catch (GeneralSecurityException | RuntimeException notOpenable) {
            if (notOpenable instanceof ApiException api) {
                throw api;
            }
            throw invalid();
        }
    }

    /** The enrolment ticket a refused sign-in carries: it names the account it was issued for. */
    public String sealTicket(String subject, Duration lifetime) {
        return seal(Purpose.TICKET, "", Map.of("sub", subject), lifetime);
    }

    /**
     * The account an enrolment ticket was issued for. The subject is inside the token, not an
     * input: whoever presents a ticket acts on the one account it names and on no other.
     *
     * @throws ApiException as {@link #open}
     */
    public String openTicket(@Nullable String ticket) {
        String subject = open(Purpose.TICKET, "", ticket).claims().get("sub");
        if (subject == null || subject.isBlank()) {
            throw invalid();
        }
        return subject;
    }

    private javax.crypto.SecretKey key() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] material = digest.digest(
                    (KEY_CONTEXT + secrets.resolve(keyReference).reveal()).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(material, "AES");
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] associatedData(Purpose purpose, String subject) {
        return (purpose.code + "|" + subject).getBytes(StandardCharsets.UTF_8);
    }

    private static ApiException invalid() {
        return new ApiException(
                ErrorCode.INVALID_REQUEST, "This enrolment has expired or is not valid. Start the enrolment again.");
    }
}
