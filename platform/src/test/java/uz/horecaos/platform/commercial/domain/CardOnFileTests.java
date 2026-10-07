package uz.horecaos.platform.commercial.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** A card is good through the END of the month printed on it, and a warning is owed before it stops. */
class CardOnFileTests {

    private static CardOnFile expiring(int month, int year) {
        return new CardOnFile("4242", "HUMO", month, year, Instant.parse("2025-01-01T00:00:00Z"), "u");
    }

    @Test
    void aCardIsGoodThroughTheLastInstantOfItsExpiryMonth() {
        CardOnFile card = expiring(5, 2027);

        assertThat(card.lapsedBy(Instant.parse("2027-05-31T23:59:59Z"))).isFalse();
        assertThat(card.lapsedBy(Instant.parse("2027-06-01T00:00:00Z"))).isTrue();
        assertThat(card.lapsedBy(Instant.parse("2027-05-01T00:00:00Z"))).isFalse();
    }

    @Test
    void aWarningIsOwedOnlyWhileItIsStillGoodAndWillHaveLapsedWithinTheWindow() {
        CardOnFile card = expiring(5, 2027);
        Instant now = Instant.parse("2027-05-20T00:00:00Z");

        assertThat(card.lapsesBefore(now, now.plusSeconds(14 * 86_400L)))
                .as("lapses on 1 June, within 14 days")
                .isTrue();
        assertThat(card.lapsesBefore(now, now.plusSeconds(5 * 86_400L)))
                .as("not yet inside a five-day window")
                .isFalse();
        assertThat(card.lapsesBefore(Instant.parse("2027-06-02T00:00:00Z"), Instant.parse("2027-07-01T00:00:00Z")))
                .as("already lapsed is a different message")
                .isFalse();
    }

    @Test
    void aCardNothingIsKnownAboutNeverClaimsToLapse() {
        CardOnFile unknown = CardOnFile.unknown();

        assertThat(unknown.lapsedBy(Instant.parse("2099-01-01T00:00:00Z"))).isFalse();
        assertThat(unknown.lapsesBefore(Instant.parse("2027-05-20T00:00:00Z"), Instant.parse("2099-01-01T00:00:00Z")))
                .isFalse();
    }
}
