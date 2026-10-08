package uz.horecaos.platform.integration.camel.einvoicing;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.api.EInvoiceDocumentReference;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorAccount;

/** The invoice and the account every adapter test sends, small enough to check by hand. */
public final class EInvoiceFixtures {

    public static final UUID INSTALLATION = UUID.fromString("018f9c10-5000-7000-8000-0000000000d1");
    public static final String CLIENT_REFERENCE = "018f9c105000700080000000000000a1";
    public static final String NUMBER = "S-2026-09-000001";

    /** The credential an operator login is stored as; a test must never see it in an assertion message. */
    public static final String DIDOX_SECRET = "{\"partnerToken\":\"partner-token-1\",\"password\":\"the-password-1\"}";

    public static final String FAKTURA_SECRET =
            "{\"username\":\"horeca\",\"password\":\"the-password-2\",\"clientId\":\"cid-1\",\"clientSecret\":\"csecret-1\"}";

    private EInvoiceFixtures() {}

    public static EInvoiceOperatorAccount account(String providerType, String baseUrl) {
        return new EInvoiceOperatorAccount(
                INSTALLATION,
                providerType,
                providerType.equals("DIDOX") ? "didox_production" : "faktura_uz_production",
                baseUrl,
                "horecaos:production:provider_einvoicing:platform:operator-1",
                Map.of("sellerTaxpayerNumber", "305000001", "sellerName", "HorecaOS MCHJ", "locale", "ru"));
    }

    /**
     * A plan line of 500 000.00 sum and a module of three at 150 000.00, with 12% VAT on each: net
     * 950 000.00, VAT 114 000.00, gross 1 064 000.00. Amounts are in tiyin.
     */
    public static EInvoiceDocument document() {
        EInvoiceDocument.Party seller = new EInvoiceDocument.Party(
                "305000001",
                "HorecaOS MCHJ",
                "Toshkent, Amir Temur 1",
                "326000000001",
                "20208000100000000001",
                "00014",
                "Test Bank");
        EInvoiceDocument.Party buyer =
                new EInvoiceDocument.Party("301234567", "Non uyi MCHJ", "Toshkent, Navoi 5", null, null, null, null);
        EInvoiceDocument.Line plan = new EInvoiceDocument.Line(
                1,
                "Подписка на платформу HorecaOS (тарифный план): BASIC v1, MONTHLY",
                "10305011001000000",
                "Услуги по предоставлению доступа к программному обеспечению",
                "1500002",
                "услуга",
                1,
                50_000_000,
                50_000_000,
                1200,
                6_000_000,
                56_000_000);
        EInvoiceDocument.Line module = new EInvoiceDocument.Line(
                2,
                "Подписка на платформу HorecaOS (модуль): analytics, PER_LOCATION",
                "10305011001000000",
                "Услуги по предоставлению доступа к программному обеспечению",
                "1500002",
                "услуга",
                3,
                15_000_000,
                45_000_000,
                1200,
                5_400_000,
                50_400_000);
        return new EInvoiceDocument(
                CLIENT_REFERENCE,
                NUMBER,
                LocalDate.parse("2026-10-07"),
                "UZS",
                seller,
                buyer,
                NUMBER,
                LocalDate.parse("2026-10-01"),
                true,
                List.of(plan, module));
    }

    public static EInvoiceDocumentReference reference(@Nullable String operatorDocumentId) {
        return new EInvoiceDocumentReference(
                operatorDocumentId, CLIENT_REFERENCE, NUMBER, LocalDate.parse("2026-10-07"));
    }
}
