package uz.horecaos.platform.kitchen.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.domain.TicketStatus;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;

/**
 * {@link KitchenBoardController.TicketResponse#of}, in isolation from the rest
 * of the board — no database, no Spring context, because this is a pure mapping
 * and the property under test is exactly "every field {@link TicketRow} carries
 * that a client needs actually reaches the wire".
 *
 * <p>Written because it once did not: {@code fulfilmentMode} and {@code
 * channelCode} sat on {@code TicketRow} since V0030 but were silently dropped by
 * this mapping, and nothing failed — {@code TicketResponse} simply had fewer
 * fields than the row it was built from. A test that only checked the response
 * was non-null would have passed throughout; this one checks the values that
 * would be wrong if the mapping regressed.
 */
class KitchenBoardControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-08-30T09:00:00Z");

    @Test
    void carriesFulfilmentModeChannelCodeAndCreatedAtOntoTheWire() {
        TicketRow ticket = new TicketRow(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                UUID.randomUUID(),
                "A-014",
                "DELIVERY",
                "telegram-bot",
                TicketStatus.FIRED,
                ReleaseMode.AUTO_ON_CONFIRM,
                null,
                CREATED_AT,
                180,
                CREATED_AT.plusSeconds(180),
                null,
                null,
                null,
                1,
                1,
                CREATED_AT);

        KitchenBoardController.TicketResponse response = KitchenBoardController.TicketResponse.of(ticket, List.of());

        assertThat(response.fulfilmentMode()).isEqualTo("DELIVERY");
        assertThat(response.channelCode()).isEqualTo("telegram-bot");
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void rendersAHeldTicketsFulfilmentModeAndChannelUnchanged() {
        // channelCode is typed non-null on TicketRow itself, matching every ticket
        // this board has actually opened; TicketResponse widens it to @Nullable on
        // the wire in case a future response carries a genuinely absent channel,
        // and that widening — not a null TicketRow field — is what this asserts.
        TicketRow ticket = new TicketRow(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                UUID.randomUUID(),
                "A-015",
                "PICKUP",
                "kiosk",
                TicketStatus.HELD,
                ReleaseMode.MANUAL_HOLD,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                1,
                CREATED_AT);

        KitchenBoardController.TicketResponse response = KitchenBoardController.TicketResponse.of(ticket, List.of());

        assertThat(response.fulfilmentMode()).isEqualTo("PICKUP");
        assertThat(response.channelCode()).isEqualTo("kiosk");
    }

    @Test
    void theTwoArgumentOverloadCarriesNoChannelSystemTypeOrCourierEta() {
        // The single-ticket read and every mutation response keep the cheaper
        // overload (see TicketResponse's own doc) — this asserts that choice
        // actually means what it claims rather than silently resolving one.
        TicketRow ticket = ticketAt(TicketStatus.FIRED);

        KitchenBoardController.TicketResponse response = KitchenBoardController.TicketResponse.of(ticket, List.of());

        assertThat(response.channelSystemType()).isNull();
        assertThat(response.courierEtaAt()).isNull();
    }

    @Test
    void theFiveArgumentOverloadCarriesTheResolvedChannelSystemTypeAndCourierEta() {
        // Only board() calls this overload (gap map rows 2.1 and 2.1a), after
        // resolving channelCode against tenant.sales_channels.system_type and
        // courierEtaAt against fulfillment.delivery_plans.courier_eta_at —
        // this asserts the mapping itself, not either resolution.
        TicketRow ticket = ticketAt(TicketStatus.FIRED);
        Instant eta = CREATED_AT.plusSeconds(1_200);

        KitchenBoardController.TicketResponse response =
                KitchenBoardController.TicketResponse.of(ticket, List.of(), "AGGREGATOR", null, eta);

        assertThat(response.channelSystemType()).isEqualTo("AGGREGATOR");
        assertThat(response.courierEtaAt()).isEqualTo(eta);
    }

    @Test
    void theFiveArgumentOverloadCarriesBothTheExternalReferenceAndTheCourierEtaTogether() {
        // board() resolves both joins off the same orderIds batch (gap map
        // rows 2.1a and 2.4) and passes both through the one full overload —
        // this asserts neither crowds out the other.
        TicketRow ticket = ticketAt(TicketStatus.FIRED);
        Instant eta = CREATED_AT.plusSeconds(1_200);

        KitchenBoardController.TicketResponse response = KitchenBoardController.TicketResponse.of(
                ticket, List.of(), "AGGREGATOR", "YE-2291-04", eta);

        assertThat(response.externalReference()).isEqualTo("YE-2291-04");
        assertThat(response.courierEtaAt()).isEqualTo(eta);
    }

    @Test
    void neitherTheTwoNorTheThreeArgumentOverloadCarriesAnExternalReference() {
        // The single-ticket read and every mutation response keep one of these
        // two cheaper overloads (see TicketResponse's own doc) — externalReference
        // stays null on both, the same as channelSystemType above.
        TicketRow ticket = ticketAt(TicketStatus.FIRED);

        assertThat(KitchenBoardController.TicketResponse.of(ticket, List.of()).externalReference())
                .isNull();
        assertThat(KitchenBoardController.TicketResponse.of(ticket, List.of(), "AGGREGATOR")
                        .externalReference())
                .isNull();
    }

    @Test
    void theFiveArgumentOverloadCarriesTheExternalReference() {
        // Only board() calls this overload (gap map row 2.4, wave T02): the
        // provider-assigned identifier a courier or customer would quote —
        // never sequenceLabel, HorecaOS's own number, which this test leaves
        // unchanged to prove the two are not confused with one another.
        TicketRow ticket = ticketAt(TicketStatus.FIRED);

        KitchenBoardController.TicketResponse response =
                KitchenBoardController.TicketResponse.of(ticket, List.of(), "AGGREGATOR", "YE-2291-04", null);

        assertThat(response.externalReference()).isEqualTo("YE-2291-04");
        assertThat(response.sequenceLabel()).isEqualTo(ticket.sequenceLabel());
    }

    @Test
    void aTicketWithNoPartnerReferenceCarriesNullRatherThanAPlaceholder() {
        // externalReference is honestly absent, not "", for the common case: a
        // direct order, or an order from a partner nobody has issued a code for
        // yet — a client must not render either as a real courier-facing code.
        TicketRow ticket = ticketAt(TicketStatus.FIRED);

        KitchenBoardController.TicketResponse response =
                KitchenBoardController.TicketResponse.of(ticket, List.of(), "AGGREGATOR", null, null);

        assertThat(response.externalReference()).isNull();
    }

    private static TicketRow ticketAt(TicketStatus status) {
        return new TicketRow(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                UUID.randomUUID(),
                "A-020",
                "DELIVERY",
                "yandex-eats",
                status,
                ReleaseMode.AUTO_ON_CONFIRM,
                null,
                CREATED_AT,
                180,
                CREATED_AT.plusSeconds(180),
                null,
                null,
                null,
                1,
                1,
                CREATED_AT);
    }
}
