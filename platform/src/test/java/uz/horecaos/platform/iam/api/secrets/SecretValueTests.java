package uz.horecaos.platform.iam.api.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The comparison and hashing side of ADR 0028's "a secret never escapes as a
 * value". A redacted {@code toString} is only half of it: a content hash is a
 * 32-bit digest anyone can print, and a short-circuiting comparison is readable
 * from a clock.
 *
 * <p>The constant-time property itself is not asserted here. The comparison is
 * {@link java.security.MessageDigest#isEqual}, whose whole documented purpose is
 * that property, and a timing assertion on a JIT-compiled loop would be a flaky
 * test rather than a proof. What is asserted is that the switch did not change
 * the verdict for any of the shapes that matter.
 */
class SecretValueTests {

    @Test
    @DisplayName("equal secrets compare equal and different ones do not")
    void comparisonIsStillCorrect() {
        assertThat(SecretValue.of("shared-webhook-secret"))
                .isEqualTo(SecretValue.of("shared-webhook-secret"))
                .isNotEqualTo(SecretValue.of("shared-webhook-secreu"))
                .isNotEqualTo(SecretValue.of("Shared-webhook-secret"))
                .isNotEqualTo(SecretValue.of("shared-webhook-secret-longer"))
                .isNotEqualTo(SecretValue.of("shared-webhook-secre"))
                .isNotEqualTo(SecretValue.of(""))
                .isNotEqualTo("shared-webhook-secret")
                .isNotEqualTo(null);
    }

    @Test
    @DisplayName("an empty secret compares equal only to another empty one")
    void theEmptyCaseIsNotAWildcard() {
        assertThat(SecretValue.of("")).isEqualTo(SecretValue.of(""));
        assertThat(SecretValue.of("")).isNotEqualTo(SecretValue.of("x"));
    }

    @Test
    @DisplayName("a multi-byte secret compares on its encoded form without changing the verdict")
    void nonAsciiSecretsCompareCorrectly() {
        assertThat(SecretValue.of("токен-🔑-998")).isEqualTo(SecretValue.of("токен-🔑-998"));
        assertThat(SecretValue.of("токен-🔑-998")).isNotEqualTo(SecretValue.of("токен-🔑-999"));
    }

    @Test
    @DisplayName("the hash carries nothing of the value")
    void hashCodeIsNotAnOracle() {
        assertThat(SecretValue.of("one-secret").hashCode())
                .as("a 32-bit digest of a secret confirms a guess without ever calling equals, "
                        + "and a collection's toString prints it")
                .isEqualTo(SecretValue.of("a-completely-different-secret").hashCode());
    }

    @Test
    @DisplayName("the byte view matches what reveal() would have produced")
    void revealBytesMatchesReveal() {
        SecretValue value = SecretValue.of("токен-🔑-998");

        assertThat(value.revealBytes())
                .as("key derivation reads this instead of reveal(); a different encoding "
                        + "would derive a different key and orphan every ciphertext")
                .isEqualTo(value.reveal().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the byte view is a copy, so a caller clearing it does not clear the secret")
    void revealBytesIsACopy() {
        SecretValue value = SecretValue.of("kek-material");
        byte[] first = value.revealBytes();
        Arrays.fill(first, (byte) 0);

        assertThat(value.reveal()).isEqualTo("kek-material");
        assertThat(value.revealBytes()).isEqualTo("kek-material".getBytes(StandardCharsets.UTF_8));
    }
}
