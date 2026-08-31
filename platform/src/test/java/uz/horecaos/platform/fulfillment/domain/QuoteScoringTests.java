package uz.horecaos.platform.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.QuoteScoring;
import uz.horecaos.platform.fulfillment.domain.sourcing.QuoteScoring.ScoredPartner;

/**
 * Which partner is asked first when several answered (ADR 0014).
 *
 * <p>A pure function with a fixed clock, because ADR 0014 requires a selection to
 * be reproducible from stored evidence and that is only checkable where every
 * input is an argument.
 */
class QuoteScoringTests {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    private static final PartnerOption NOOR =
            new PartnerOption(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"), "noor-delivery", false, true);
    private static final PartnerOption YANDEX =
            new PartnerOption(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"), "yandex-delivery", true, true);

    @Test
    @DisplayName("the cheaper partner is asked first however the bindings were configured")
    void theCheaperPartnerIsAskedFirst() {
        List<ScoredPartner> scored = QuoteScoring.rank(
                List.of(NOOR, YANDEX), List.of(quote(NOOR, 28_000L, 480), quote(YANDEX, 19_000L, 600)), plan(), NOW);

        // Noor is the narrower binding and would otherwise win by configuration.
        // Every som between them is one the tenant or the platform absorbs, because
        // ADR 0013 forbids raising the customer's fee after they agreed to it.
        assertThat(QuoteScoring.order(scored)).containsExactly(YANDEX, NOOR);
        assertThat(scored.getFirst().reason()).isEqualTo(QuoteScoring.QUOTED);
    }

    @Test
    @DisplayName(
            "a courier who cannot reach the branch before the window shuts is excluded, " + "not merely ranked last")
    void aPartnerThatCannotMakeTheWindowIsExcluded() {
        // Cheapest by a wide margin, and forty minutes away from a window that
        // closes in thirty. Ranking it last would still book it once the other
        // partner refused, which is a late delivery with a commission attached.
        List<ScoredPartner> scored = QuoteScoring.rank(
                List.of(NOOR, YANDEX), List.of(quote(NOOR, 5_000L, 2_400), quote(YANDEX, 19_000L, 600)), plan(), NOW);

        assertThat(QuoteScoring.order(scored)).containsExactly(YANDEX);
        assertThat(scored.getLast().reason()).isEqualTo(QuoteScoring.PICKUP_ETA_MISSES_WINDOW);
    }

    @Test
    @DisplayName("a quote past the TTL HorecaOS imposed is not an answer any more")
    void anExpiredQuoteIsNotUsed() {
        DeliveryQuote stale = new DeliveryQuote(
                UUID.randomUUID(),
                NOOR.bindingId(),
                "noor-delivery",
                UUID.randomUUID(),
                5_000L,
                "UZS",
                300,
                1_200,
                3_400,
                900,
                NOW.minusSeconds(1),
                false,
                null,
                NOW.minusSeconds(200));

        List<ScoredPartner> scored =
                QuoteScoring.rank(List.of(NOOR, YANDEX), List.of(stale, quote(YANDEX, 19_000L, 600)), plan(), NOW);

        assertThat(QuoteScoring.order(scored)).containsExactly(YANDEX);
        assertThat(scored.getLast().reason()).isEqualTo(QuoteScoring.QUOTE_EXPIRED);
    }

    @Test
    @DisplayName("a partner nobody could quote stays bookable, behind every partner who answered")
    void anUnquotedPartnerIsStillBookable() {
        List<ScoredPartner> scored =
                QuoteScoring.rank(List.of(NOOR, YANDEX), List.of(quote(YANDEX, 19_000L, 600)), plan(), NOW);

        // Neither verified partner has to answer a quote call, and a fleet-less
        // tenant whose only partner cannot quote must still be able to source.
        assertThat(QuoteScoring.order(scored)).containsExactly(YANDEX, NOOR);
        assertThat(scored.getLast().reason()).isEqualTo(QuoteScoring.NOT_QUOTED);
    }

    @Test
    @DisplayName("two identical situations rank identically, whichever order the rows arrived in")
    void tieBreakingIsDeterministic() {
        DeliveryQuote noor = quote(NOOR, 19_000L, 600);
        DeliveryQuote yandex = quote(YANDEX, 19_000L, 600);

        List<PartnerOption> oneWay =
                QuoteScoring.order(QuoteScoring.rank(List.of(NOOR, YANDEX), List.of(noor, yandex), plan(), NOW));
        List<PartnerOption> theOther =
                QuoteScoring.order(QuoteScoring.rank(List.of(NOOR, YANDEX), List.of(yandex, noor), plan(), NOW));

        // A selection that depends on the order rows came back in is one nobody can
        // reproduce, and reproducing it is what ADR 0014 requires of the evidence.
        assertThat(oneWay).isEqualTo(theOther).containsExactly(NOOR, YANDEX);
    }

    @Test
    @DisplayName("a partner that refused the quote is refused the booking")
    void aRefusalIsCarriedIntoTheSelection() {
        DeliveryQuote refused = new DeliveryQuote(
                UUID.randomUUID(),
                NOOR.bindingId(),
                "noor-delivery",
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                null,
                NOW.plusSeconds(120),
                false,
                "CancelledOutOfZone",
                NOW);

        List<ScoredPartner> scored =
                QuoteScoring.rank(List.of(NOOR, YANDEX), List.of(refused, quote(YANDEX, 19_000L, 600)), plan(), NOW);

        assertThat(QuoteScoring.order(scored)).containsExactly(YANDEX);
        assertThat(scored.getLast().reason()).isEqualTo(QuoteScoring.PARTNER_REFUSED);
        DeliveryQuote lastQuote = Objects.requireNonNull(
                scored.getLast().quote(), "the refused quote is the one carried into the selection");
        assertThat(lastQuote.status()).isEqualTo("REFUSED");
    }

    private static DeliveryQuote quote(PartnerOption partner, long priceMinor, int pickupEta) {
        return new DeliveryQuote(
                UUID.randomUUID(),
                partner.bindingId(),
                partner.providerType(),
                UUID.randomUUID(),
                priceMinor,
                "UZS",
                pickupEta,
                1_500,
                3_400,
                900,
                NOW.plusSeconds(120),
                false,
                null,
                NOW);
    }

    private static PickupPlan plan() {
        return PickupPlan.forOrder(
                NOW, Duration.ofMinutes(15), ZoneId.of("Asia/Tashkent"), DeliverySourcingPolicy.DEFAULTS);
    }
}
