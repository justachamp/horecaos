package uz.horecaos.platform.payments.infrastructure.click;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import uz.horecaos.platform.payments.domain.FiscalDocument;

/**
 * Click's {@code qrCodeURL}, parsed into the fields a receipt is made of
 * (ADR 0013, ADR 0038).
 *
 * <p>Click returns the fiscal evidence as one URL:
 * {@code https://ofd.soliq.uz/epi?t=…&r=…&c=…&s=…}, where {@code t} is the
 * terminal, {@code r} the receipt number, {@code c} the timestamp and {@code s}
 * the fiscal sign. Payme returns the same underlying object as named fields.
 *
 * <p>Both the URL and the parsed fields are stored. A URL is a pointer to a
 * service HorecaOS does not run, whose lifetime belongs to the OFD, and an evidence
 * record that is only a dead link is not evidence — which is also why
 * {@code fiscal_sign} and {@code external_receipt_id} are columns the schema
 * refuses to let an {@code ISSUED} document be without.
 *
 * <p>A URL that does not parse is still stored. Click's shape is not promised
 * anywhere and a receipt that arrived is a fact, so an unparseable one is kept
 * whole rather than discarded for failing an expectation this class invented.
 */
public final class ClickReceiptUrl {

    /**
     * {@code c=20221028171340} in Click's own worked example.
     *
     * <p>No timezone is stated. Read as Tashkent local time, which is the only
     * reading consistent with a receipt issued by an Uzbek fiscal terminal, and
     * recorded as an instant so that a later correction is a data fix rather than
     * a reinterpretation of every stored row.
     */
    private static final DateTimeFormatter RECEIPT_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final ZoneOffset TASHKENT = ZoneOffset.ofHours(5);

    private ClickReceiptUrl() {}

    /**
     * @param registeredAt when the receipt says it was issued, which is not when
     *                     HorecaOS read it
     */
    public static FiscalDocument.FiscalEvidence parse(String qrCodeUrl, String paymentId, Instant observedAt) {
        Map<String, String> query = query(qrCodeUrl);
        return new FiscalDocument.FiscalEvidence(
                // The receipt number is what a tax inspector and a customer both
                // quote, so it is the external id rather than Click's payment id.
                query.getOrDefault("r", paymentId),
                query.get("s"),
                query.get("t"),
                query.get("r"),
                receiptTime(query.get("c")).orElse(observedAt),
                qrCodeUrl,
                null,
                null);
    }

    /** Whether Click has a receipt for this payment yet. */
    public static boolean issued(String qrCodeUrl) {
        return qrCodeUrl != null && !qrCodeUrl.isBlank() && !"null".equalsIgnoreCase(qrCodeUrl);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        if (url == null || url.isBlank()) {
            return values;
        }
        String raw;
        try {
            raw = URI.create(url).getRawQuery();
        } catch (IllegalArgumentException notAUri) {
            return values;
        }
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                values.put(
                        URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static Optional<Instant> receiptTime(String raw) {
        if (raw == null || raw.length() != 14) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(raw, RECEIPT_TIME).toInstant(TASHKENT));
        } catch (RuntimeException unparseable) {
            return Optional.empty();
        }
    }
}
