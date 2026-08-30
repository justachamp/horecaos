package uz.horecaos.platform.media;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.media.domain.LegacyPath;
import uz.horecaos.platform.media.domain.LegacyPathMapping;
import uz.horecaos.platform.media.domain.MediaOwner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the legacy migration will and will not read (ADR 0010).
 *
 * <p>These paths came out of legacy business rows written by an application that
 * concatenated user input, so every one of them is attacker-influenced data that
 * the copy step would turn into a filesystem read.
 */
class LegacyPathTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    @Test
    @DisplayName("two spellings of one file normalize to the same path")
    void normalizesEquivalentSpellings() {
        String canonical = LegacyPath.of("uploads/brand/./burger.jpg").orElseThrow().normalized();

        assertThat(canonical).isEqualTo("uploads/brand/burger.jpg");
        // Otherwise one file becomes two assets and the reconciliation counts
        // disagree for a reason nobody can find.
        assertThat(LegacyPath.of("/uploads//brand/burger.jpg").orElseThrow().normalized())
                .isEqualTo(canonical);
        assertThat(LegacyPath.of("uploads\\brand\\burger.jpg").orElseThrow().normalized())
                .isEqualTo(canonical);
        assertThat(LegacyPath.of("  uploads/brand/gallery/../burger.jpg  ").orElseThrow().normalized())
                .isEqualTo(canonical);
    }

    @Test
    @DisplayName("a path that climbs out of its root is refused")
    void refusesTraversal() {
        assertThat(LegacyPath.of("../../etc/passwd.jpg")).isEmpty();
        assertThat(LegacyPath.of("uploads/../../secrets/key.png")).isEmpty();
    }

    @Test
    @DisplayName("a NUL byte in the path is refused rather than normalized away")
    void refusesControlCharacters() {
        // The system call stops at the NUL while the stored row keeps the whole
        // string, so what was checked and what is opened are different files.
        assertThat(LegacyPath.of("uploads/brand/burger.jpg\0.php")).isEmpty();
        assertThat(LegacyPath.of("uploads/brand/bur\nger.jpg")).isEmpty();
    }

    @Test
    @DisplayName("the migration reads photographs and nothing else")
    void refusesWhatIsNotAnImage() {
        assertThat(LegacyPath.of("uploads/brand/dump.sql")).isEmpty();
        assertThat(LegacyPath.of("uploads/brand/shell.php")).isEmpty();
        assertThat(LegacyPath.of("uploads/brand/burger")).isEmpty();
        assertThat(LegacyPath.of("uploads/brand/burger.JPG")).isPresent();
    }

    @Test
    @DisplayName("nothing at all is refused rather than treated as the root")
    void refusesEmptyPaths() {
        assertThat(LegacyPath.of("")).isEmpty();
        assertThat(LegacyPath.of("   ")).isEmpty();
        assertThat(LegacyPath.of("///")).isEmpty();
        assertThat(LegacyPath.of(null)).isEmpty();
    }

    @Test
    @DisplayName("the most specific approved mapping owns the file")
    void resolvesTheLongestMatchingPrefix() {
        var general = new LegacyPathMapping(TENANT, "/uploads/", MediaOwner.tenant(TENANT));
        var specific = new LegacyPathMapping(TENANT, "/uploads/pizza/", MediaOwner.brand(BRAND));

        var resolved = LegacyPathMapping.resolve(
                LegacyPath.of("/uploads/pizza/margherita.jpg").orElseThrow(),
                List.of(general, specific)).orElseThrow();

        // So an exception can be carved out of a general mapping without the
        // general one having to be split into every directory it covers.
        assertThat(resolved.owner()).isEqualTo(MediaOwner.brand(BRAND));
    }

    @Test
    @DisplayName("a prefix does not claim a directory that merely starts with its name")
    void doesNotClaimASiblingDirectory() {
        var pizza = new LegacyPathMapping(TENANT, "/uploads/pizza", MediaOwner.brand(BRAND));
        var pizzahut = new LegacyPathMapping(TENANT, "/uploads/pizzahut", MediaOwner.brand(OTHER_BRAND));

        var resolved = LegacyPathMapping.resolve(
                LegacyPath.of("/uploads/pizzahut/logo.png").orElseThrow(),
                List.of(pizza, pizzahut)).orElseThrow();

        assertThat(resolved.owner()).isEqualTo(MediaOwner.brand(OTHER_BRAND));
    }

    @Test
    @DisplayName("an unclaimed path resolves to nobody rather than to a guess")
    void refusesToGuessAnOwner() {
        var mapping = new LegacyPathMapping(TENANT, "/uploads/pizza", MediaOwner.brand(BRAND));

        // A file attached to the wrong tenant is a data-protection incident, not
        // a display bug, so an unmapped file waits for a human.
        assertThat(LegacyPathMapping.resolve(
                LegacyPath.of("/uploads/unknown/photo.jpg").orElseThrow(), List.of(mapping)))
                .isEmpty();
    }
}
