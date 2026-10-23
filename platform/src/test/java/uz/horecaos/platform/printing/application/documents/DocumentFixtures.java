package uz.horecaos.platform.printing.application.documents;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.Item;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.PrintableTicket;
import uz.horecaos.platform.kitchen.api.KitchenTicketPrintSource.Station;
import uz.horecaos.platform.ordering.api.OrderPrintSource.Line;
import uz.horecaos.platform.ordering.api.OrderPrintSource.Modifier;
import uz.horecaos.platform.ordering.api.OrderPrintSource.Preset;
import uz.horecaos.platform.ordering.api.OrderPrintSource.PrintableOrder;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.Evidence;
import uz.horecaos.platform.payments.api.FiscalReceiptSource.FiscalReceipt;
import uz.horecaos.platform.payments.api.PaymentDirectory.PaymentSummary;
import uz.horecaos.platform.printing.domain.TapeWidth;
import uz.horecaos.platform.tenancy.api.FiscalSeller;

/**
 * An order, its ticket, a fiscal receipt and a branch, small enough to read and with every shape the
 * documents have to get right: a modifier, a coded preset, a free-text note, a combo split across two
 * stations, and a customer whose name, phone and address exist nowhere in them (ADR 0154: no document
 * carries one, because the ports that feed a document do not).
 */
final class DocumentFixtures {

    static final UUID TENANT = UUID.fromString("018fc154-0000-7000-8000-0000000000a1");
    static final UUID BRAND = UUID.fromString("018fc154-0000-7000-8000-0000000000b1");
    static final UUID LOCATION = UUID.fromString("018fc154-0000-7000-8000-0000000000c1");
    static final UUID GRILL = UUID.fromString("018fc154-0000-7000-8000-0000000000d1");
    static final UUID BAR = UUID.fromString("018fc154-0000-7000-8000-0000000000d2");
    static final UUID ORDER = UUID.fromString("018fc154-0000-7000-8000-0000000000e1");
    static final UUID TICKET = UUID.fromString("018fc154-0000-7000-8000-0000000000f1");
    static final UUID COMBO = UUID.fromString("018fc154-0000-7000-8000-0000000000aa");
    static final UUID LEGAL_ENTITY = UUID.fromString("018fc154-0000-7000-8000-0000000000ee");

    static final UUID LAGMAN_LINE = UUID.fromString("018fc154-0000-7000-8000-000000000101");
    static final UUID COLA_LINE = UUID.fromString("018fc154-0000-7000-8000-000000000102");
    static final UUID BURGER_LINE = UUID.fromString("018fc154-0000-7000-8000-000000000103");
    static final UUID COMBO_DRINK_LINE = UUID.fromString("018fc154-0000-7000-8000-000000000104");

    static final Instant CONFIRMED_AT = Instant.parse("2026-10-08T07:02:00Z");
    static final String RECEIPT_URL =
            "https://ofd.soliq.uz/epi?t=EP000000000000&r=1234567&c=20261008120501&s=987654321098";

    private DocumentFixtures() {}

    static BuildContext context(String locale, int width, boolean copy, String... footers) {
        return new BuildContext(
                locale,
                TapeWidth.require(width),
                "Asia/Tashkent",
                copy,
                new BuildContext.Branch("Chilonzor", "Bunyodkor 12, Chilonzor, Tashkent", "+998 71 200 00 00"),
                null,
                List.of(footers));
    }

    static PrintableOrder order(@Nullable Long cashTendered) {
        Line lagman = new Line(
                LAGMAN_LINE,
                1,
                "Lagman",
                "Large",
                new BigDecimal("2.000"),
                45_000,
                100_000,
                "no cilantro please, the guest is allergic",
                List.of(new Modifier(UUID.randomUUID(), null, "Extras", "Extra beef", 1, 10_000)),
                List.of(new Preset(
                        "NO_ONION", java.util.Map.of("ru", "Без лука", "uz-Latn", "Piyozsiz", "en", "No onion"))),
                null,
                null,
                null);
        Line cola = new Line(
                COLA_LINE,
                2,
                "Cola",
                "0.5 L",
                new BigDecimal("1.000"),
                8_000,
                8_000,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null);
        Line burger = new Line(
                BURGER_LINE,
                3,
                "Burger",
                null,
                new BigDecimal("1.000"),
                0,
                30_000,
                null,
                List.of(),
                List.of(new Preset(
                        "WELL_DONE",
                        java.util.Map.of("ru", "Хорошо прожарить", "uz-Latn", "Yaxshi pishirish", "en", "Well done"))),
                COMBO,
                "Combo No.1",
                1);
        Line comboDrink = new Line(
                COMBO_DRINK_LINE,
                4,
                "Fanta",
                null,
                new BigDecimal("1.000"),
                0,
                0,
                null,
                List.of(),
                List.of(),
                COMBO,
                "Combo No.1",
                1);
        long total = 138_000;
        return new PrintableOrder(
                ORDER,
                TENANT,
                BRAND,
                LOCATION,
                "A-014",
                "CONFIRMED",
                "PICKUP",
                "TELEGRAM",
                Instant.parse("2026-10-08T07:01:30Z"),
                CONFIRMED_AT,
                3,
                "UZS",
                total,
                0,
                0,
                0,
                total,
                cashTendered,
                cashTendered == null ? null : cashTendered - total,
                null,
                List.of(lagman, cola, burger, comboDrink));
    }

    static PrintableTicket ticket(int amendmentRevision) {
        return new PrintableTicket(
                TICKET,
                TENANT,
                BRAND,
                LOCATION,
                ORDER,
                "A-014",
                "PICKUP",
                "TELEGRAM",
                "FIRED",
                Instant.parse("2026-10-08T07:02:05Z"),
                Instant.parse("2026-10-08T07:25:00Z"),
                amendmentRevision,
                List.of(
                        new Station(GRILL, "GRILL", "Гриль", "Gril", "Grill", false),
                        new Station(BAR, "BAR", "Бар", "Bar", "Bar", false)),
                List.of(
                        new Item(UUID.randomUUID(), LAGMAN_LINE, GRILL, new BigDecimal("2.000"), null),
                        new Item(UUID.randomUUID(), COLA_LINE, BAR, new BigDecimal("1.000"), null),
                        new Item(UUID.randomUUID(), BURGER_LINE, GRILL, new BigDecimal("1.000"), COMBO),
                        new Item(UUID.randomUUID(), COMBO_DRINK_LINE, BAR, new BigDecimal("1.000"), COMBO)));
    }

    static FiscalSeller seller() {
        return new FiscalSeller(
                LEGAL_ENTITY,
                TENANT,
                "LE-1",
                "Sinov MCHJ",
                "123456789",
                true,
                UUID.randomUUID(),
                true,
                UUID.randomUUID(),
                1,
                LocalDate.of(2026, 1, 1),
                null,
                "Bunyodkor 12, Tashkent",
                "+998 71 200 00 00");
    }

    static FiscalReceipt receipt(String type, String status, @Nullable Evidence evidence) {
        return new FiscalReceipt(
                UUID.fromString("018fc154-0000-7000-8000-0000000001f1"),
                TENANT,
                ORDER,
                LEGAL_ENTITY,
                type,
                status,
                "UZS",
                Instant.parse("2026-10-08T07:02:30Z"),
                List.of(new uz.horecaos.platform.payments.api.FiscalReceiptSource.Line(
                        "Order A-014", java.math.BigDecimal.ONE, 138_000, 138_000, 0, 0)),
                138_000,
                0,
                evidence);
    }

    static Evidence evidence() {
        return new Evidence(
                "1234567", "987654321098", "EP000000000000", Instant.parse("2026-10-08T07:05:01Z"), RECEIPT_URL);
    }

    static PaymentSummary payment(String tender, @Nullable String fiscalStatus) {
        return new PaymentSummary(
                UUID.randomUUID(),
                ORDER,
                tender,
                "CASH".equals(tender) ? "cash" : "click",
                "CASH".equals(tender) ? null : "CLICK",
                "PAID",
                true,
                false,
                false,
                138_000,
                138_000,
                0,
                "UZS",
                fiscalStatus,
                null);
    }
}
