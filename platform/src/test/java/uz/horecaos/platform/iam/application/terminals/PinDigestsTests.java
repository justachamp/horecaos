package uz.horecaos.platform.iam.application.terminals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR 0155, Decision 3: a keyed digest, bound to its tenant, person and PIN version, compared in constant time. */
class PinDigestsTests {

    private static final UUID TENANT = UUID.fromString("018fe155-0000-7000-8000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("018fe155-0000-7000-8000-000000000002");
    private static final UUID MEMBER = UUID.fromString("018fe155-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_MEMBER = UUID.fromString("018fe155-0000-7000-8000-0000000000a2");

    private static byte[] key(String text) {
        return (text + "-padding-to-reach-sixteen-bytes").getBytes(StandardCharsets.UTF_8);
    }

    private final PinDigests digests = new PinDigests(TerminalPinPepper.of(2, key("current"), 1, key("previous")));

    @Test
    @DisplayName("the same PIN digests differently per tenant, per person, per PIN version and per pepper")
    void aDigestCannotBeReplayedAcrossAnyOfThem() {
        String base = digests.pinDigest(TENANT, MEMBER, 1, "482915", 2);

        assertThat(base).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(digests.pinDigest(TENANT, MEMBER, 1, "482915", 2))
                .as("stable")
                .isEqualTo(base);
        assertThat(digests.pinDigest(OTHER_TENANT, MEMBER, 1, "482915", 2)).isNotEqualTo(base);
        assertThat(digests.pinDigest(TENANT, OTHER_MEMBER, 1, "482915", 2)).isNotEqualTo(base);
        assertThat(digests.pinDigest(TENANT, MEMBER, 2, "482915", 2))
                .as("a replaced PIN's digest is not valid for the new version")
                .isNotEqualTo(base);
        assertThat(digests.pinDigest(TENANT, MEMBER, 1, "482916", 2)).isNotEqualTo(base);
        assertThat(digests.pinDigest(TENANT, MEMBER, 1, "482915", 1))
                .as("keyed under the previous pepper")
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("the digest is keyed: without the pepper a database dump is not a list of working PINs")
    void aDifferentPepperGivesADifferentDigest() {
        PinDigests other = new PinDigests(TerminalPinPepper.of(2, key("another"), null, null));

        assertThat(other.pinDigest(TENANT, MEMBER, 1, "482915", 2))
                .isNotEqualTo(digests.pinDigest(TENANT, MEMBER, 1, "482915", 2));
    }

    @Test
    @DisplayName("a pepper version that is no longer resolvable yields no digest, so it can never verify")
    void anUnknownPepperVersionCannotVerify() {
        assertThat(digests.pinDigest(TENANT, MEMBER, 1, "482915", 7)).isNull();
        assertThat(PinDigests.matches("abc", null)).isFalse();
        assertThat(PinDigests.matches(null, "abc")).isFalse();
    }

    @Test
    @DisplayName("an activation code is keyed apart from a PIN, so one cannot be passed off as the other")
    void anActivationCodeIsNotAPin() {
        assertThat(digests.activationDigest(TENANT, MEMBER, "48291573"))
                .isNotEqualTo(digests.pinDigest(TENANT, MEMBER, 0, "48291573", 2));
    }

    @Test
    @DisplayName("comparison is by value, and a session token digest is plain SHA-256")
    void comparisonAndTokenDigest() {
        String digest = digests.pinDigest(TENANT, MEMBER, 1, "482915", 2);

        assertThat(PinDigests.matches(digest, digests.pinDigest(TENANT, MEMBER, 1, "482915", 2)))
                .isTrue();
        assertThat(PinDigests.matches(digest, digests.pinDigest(TENANT, MEMBER, 1, "482916", 2)))
                .isFalse();
        assertThat(PinDigests.tokenDigest("token")).hasSize(64).isEqualTo(PinDigests.tokenDigest("token"));
        assertThat(PinDigests.tokenDigest("token")).isNotEqualTo(PinDigests.tokenDigest("tokeN"));
    }

    @Test
    @DisplayName("a pepper shorter than sixteen bytes is refused, and so is a previous pepper that shares the version")
    void aWeakOrAmbiguousPepperIsRefused() {
        assertThatThrownBy(() -> TerminalPinPepper.of(1, new byte[8], null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16 bytes");
        assertThatThrownBy(() -> TerminalPinPepper.of(1, key("a"), 1, key("b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version of its own");
    }
}
