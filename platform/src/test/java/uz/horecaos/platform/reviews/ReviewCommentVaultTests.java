package uz.horecaos.platform.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.reviews.application.ReviewCommentVault;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.domain.RevealPurpose;
import uz.horecaos.platform.support.TestProtection;

/**
 * The one place a review's comment and a case's note are encrypted and decrypted (ADR 0159, ADR 0029): a
 * round trip through the real envelope encryption, the binding of a ciphertext to its own row, and the rule
 * that the anonymous reveal is reachable only for a published, consented comment -- and, as a scan of the
 * production sources, only through this class.
 *
 * <p>The scan is how the "this purpose appears nowhere else" sentence in the vault's own documentation is
 * kept true: a second caller of the public reveal would be a second place that has to remember the two
 * conditions, and it would be added by someone who had not read this class.
 */
class ReviewCommentVaultTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-4000-7000-8000-0000000000a2");
    private static final UUID REVIEW = UUID.fromString("018f9f10-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_REVIEW = UUID.fromString("018f9f10-4000-7000-8000-0000000000b2");
    private static final UUID EVENT = UUID.fromString("018f9f10-4000-7000-8000-0000000000c1");
    private static final String COMMENT = "Cold soup; ask for Jasur Tashmatov, 998901234567";

    private final ReviewCommentVault vault = new ReviewCommentVault(TestProtection.envelope());

    @Test
    @DisplayName("a comment round-trips for a signed-in reader, and the stored form carries none of it")
    void aCommentRoundTrips() {
        String stored = vault.protectComment(TENANT, REVIEW, COMMENT);

        assertThat(stored).doesNotContain("Cold").doesNotContain("Jasur").doesNotContain("998901234567");
        assertThat(vault.revealComment(TENANT, REVIEW, stored, RevealPurpose.OPERATIONS_REVIEWS_SCREEN))
                .isEqualTo(COMMENT);
        assertThat(vault.revealComment(TENANT, REVIEW, null, RevealPurpose.STOREFRONT_MY_REVIEWS))
                .as("a review with no comment reveals nothing")
                .isNull();
    }

    @Test
    @DisplayName("a note round-trips for the drawer, bound to its own timeline entry")
    void aNoteRoundTrips() {
        String stored = vault.protectNote(TENANT, EVENT, "Phoned her on 998901234567");

        assertThat(vault.revealNote(TENANT, EVENT, stored)).isEqualTo("Phoned her on 998901234567");
        assertThat(vault.revealNote(TENANT, EVENT, null)).isNull();
    }

    @Test
    @DisplayName("a ciphertext lifted onto another review, or into another tenant, does not decrypt")
    void aCiphertextIsBoundToItsRow() {
        String stored = vault.protectComment(TENANT, REVIEW, COMMENT);

        assertThatThrownBy(() ->
                        vault.revealComment(TENANT, OTHER_REVIEW, stored, RevealPurpose.OPERATIONS_REVIEWS_SCREEN))
                .as("another review's id")
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() ->
                        vault.revealComment(OTHER_TENANT, REVIEW, stored, RevealPurpose.OPERATIONS_REVIEWS_SCREEN))
                .as("another tenant")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("the signed-in reveal refuses the public purpose: that one has its own method and its own row check")
    void theSignedInRevealRefusesThePublicPurpose() {
        String stored = vault.protectComment(TENANT, REVIEW, COMMENT);

        assertThatThrownBy(() -> vault.revealComment(TENANT, REVIEW, stored, RevealPurpose.PUBLIC_DISPLAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the public reveal opens a published comment its author agreed to show, and nothing else")
    void thePublicRevealNeedsBothConditions() {
        String stored = vault.protectComment(TENANT, REVIEW, COMMENT);

        assertThat(vault.revealForPublicDisplay(TENANT, REVIEW, CommentState.PUBLISHED, true, stored))
                .isEqualTo(COMMENT);
        for (CommentState state : CommentState.values()) {
            if (state == CommentState.PUBLISHED) {
                continue;
            }
            assertThatThrownBy(() -> vault.revealForPublicDisplay(TENANT, REVIEW, state, true, stored))
                    .as("consented but %s", state)
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> vault.revealForPublicDisplay(TENANT, REVIEW, CommentState.PUBLISHED, false, stored))
                .as("published without the author's yes")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("nothing but the vault names the public purpose or holds the protection port")
    void onlyTheVaultReachesThePublicReveal() throws IOException {
        Path root = Path.of("src/main/java/uz/horecaos/platform");
        Pattern purpose = Pattern.compile("\\bPUBLIC_DISPLAY\\b|reviews\\.public\\.display");

        List<String> namingThePublicPurpose;
        List<String> holdingTheProtectionPort;
        try (Stream<Path> files = Files.walk(root.resolve("reviews"))) {
            List<Path> sources =
                    files.filter(path -> path.toString().endsWith(".java")).toList();
            namingThePublicPurpose = sources.stream()
                    .filter(path -> mentions(path, purpose))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
            holdingTheProtectionPort = sources.stream()
                    .filter(path -> mentions(path, Pattern.compile("\\bFieldProtection\\b")))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

        assertThat(namingThePublicPurpose)
                .as("the enum that declares it, and the one class that may use it")
                .containsExactlyInAnyOrder("ReviewCommentVault.java", "RevealPurpose.java");
        assertThat(holdingTheProtectionPort)
                .as("a second holder of FieldProtection is a second place a comment could be decrypted")
                .containsExactly("ReviewCommentVault.java");
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(path -> path.toString().endsWith(".java"))
                            .filter(path -> !path.toString().contains("/reviews/"))
                            .filter(path -> mentions(path, Pattern.compile("RevealPurpose")))
                            .map(path -> path.getFileName().toString())
                            .toList())
                    .as("no other module names a reveal purpose of reviews")
                    .isEmpty();
        }
    }

    private static boolean mentions(Path file, Pattern pattern) {
        try {
            return pattern.matcher(Files.readString(file)).find();
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
