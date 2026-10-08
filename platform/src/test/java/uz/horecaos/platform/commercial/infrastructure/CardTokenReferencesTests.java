package uz.horecaos.platform.commercial.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.application.CardTokenReferences;

class CardTokenReferencesTests {

    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-3300-7000-8000-0000000000f1");

    @Test
    void aReferenceNamesTheAccountThatMintedItAndRoundTrips() {
        String reference = CardTokenReferences.compose(INSTALLATION, "fake_card_abc:def");

        CardTokenReferences.Parsed parsed = CardTokenReferences.parse(reference).orElseThrow();

        assertThat(parsed.installationId()).isEqualTo(INSTALLATION);
        assertThat(parsed.providerToken())
                .as("a provider token that itself contains a colon survives")
                .isEqualTo("fake_card_abc:def");
    }

    @Test
    void aReferenceStaffTypedByHandIsNotMistakenForOne() {
        assertThat(CardTokenReferences.parse("vault:pilot-card")).isEmpty();
        assertThat(CardTokenReferences.parse(null)).isEmpty();
        assertThat(CardTokenReferences.parse("")).isEmpty();
        assertThat(CardTokenReferences.parse(INSTALLATION + ":"))
                .as("no token after the colon")
                .isEmpty();
        assertThat(CardTokenReferences.parse("not-a-uuid-at-all-not-a-uuid-at-all!!:x"))
                .isEmpty();
    }

    @Test
    void theParsedFormDoesNotPrintTheToken() {
        CardTokenReferences.Parsed parsed = CardTokenReferences.parse(
                        CardTokenReferences.compose(INSTALLATION, "secret-token"))
                .orElseThrow();

        assertThat(parsed.toString()).contains(INSTALLATION.toString()).doesNotContain("secret-token");
    }
}
