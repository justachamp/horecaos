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
}
