package uz.horecaos.platform.integration.provider.telegram.sendpulse;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * A documented, tolerant reading of a SendPulse bot-audience export (ADR 0059
 * stage 3).
 *
 * <p>SendPulse's own export columns vary by account — which custom
 * subscriber-form fields a tenant configured, and whether the tenant chose a
 * CSV or a JSON download. This class does not assume a fixed schema; it
 * matches a small set of recognised column-name aliases per logical field,
 * case- and punctuation-insensitively ({@link #normalizeKey}), and treats a
 * row that cannot be honestly classified as a per-row rejection rather than a
 * guess or a whole-file failure — the same "never abort the batch" rule the
 * ADR states for resolution failures applies here, one level earlier, to
 * parse failures.
 *
 * <h2>The mapping, stated once</h2>
 *
 * <ul>
 *   <li><b>Chat id</b> ({@link #CHAT_ID_KEYS}) — required. A row with none is
 *       {@link SendPulseImportRejectReason#MISSING_CHAT_ID}.
 *   <li><b>Telegram user id</b> ({@link #USER_ID_KEYS}) — optional; defaults
 *       to the chat id, which is correct for every 1:1 bot subscriber (the
 *       whole of a bot audience export).
 *   <li><b>Phone</b> ({@link #PHONE_KEYS}) — optional, and not validated
 *       here at all: {@code SendPulseContactImportRowService} validates it
 *       through the {@code customers.api} seam it already calls to match or
 *       attach one, which is also where {@link
 *       SendPulseImportRejectReason#MALFORMED_PHONE} is produced.
 *   <li><b>Subscription status</b> ({@link #STATUS_KEYS}) — required, and its
 *       <em>value</em> must also be one this parser recognises ({@link
 *       #SUBSCRIBED_VALUES}, {@link #UNSUBSCRIBED_VALUES}). Anything else —
 *       missing column, or a value neither list names — is {@link
 *       SendPulseImportRejectReason#UNRECOGNIZED_SUBSCRIPTION_STATUS}: ADR
 *       0059 is explicit that consent provenance is "never a silent
 *       default", and a status this parser cannot read is exactly that risk.
 *   <li><b>Subscription date</b> ({@link #DECIDED_AT_KEYS}) — optional,
 *       parsed as ISO-8601 (instant, local date-time, or bare date); an
 *       unparseable or absent value is not a rejection — the import's own
 *       run timestamp stands in instead, which the ADR 0015 consent record
 *       already allows for an imported decision.
 * </ul>
 */
@Component
public class SendPulseContactFileParser {

    private static final List<String> CHAT_ID_KEYS =
            List.of("chat_id", "chatid", "telegram_chat_id", "id", "subscriber_id", "contact_id", "external_id");
    private static final List<String> USER_ID_KEYS = List.of("telegram_user_id", "user_id", "userid");
    private static final List<String> PHONE_KEYS = List.of("phone", "phone_number", "msisdn", "variables_phone", "tel");
    private static final List<String> STATUS_KEYS =
            List.of("status", "subscription_status", "is_subscribed", "subscribed", "state");
    private static final List<String> DECIDED_AT_KEYS =
            List.of("subscribed_at", "subscription_date", "date_added", "created_at", "date", "updated_at");

    private static final Set<String> SUBSCRIBED_VALUES =
            Set.of("subscribed", "active", "true", "1", "yes", "opted_in", "opt_in", "y");
    private static final Set<String> UNSUBSCRIBED_VALUES = Set.of(
            "unsubscribed",
            "inactive",
            "false",
            "0",
            "no",
            "blocked",
            "banned",
            "stopped",
            "opted_out",
            "opt_out",
            "n");

    private final ObjectMapper objectMapper;

    public SendPulseContactFileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Dispatches on {@code format}; both branches return rows in source order, 1-based. */
    public List<SendPulseContactRow> parse(SendPulseImportFormat format, String content) {
        return switch (format) {
            case CSV -> parseCsv(content);
            case JSON -> parseJson(content);
        };
    }

    private List<SendPulseContactRow> parseCsv(String content) {
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();
        try (CSVParser parser = CSVParser.parse(new StringReader(content), format)) {
            return parser.stream()
                    .map(record -> toRow(record.toMap(), (int) record.getRecordNumber()))
                    .toList();
        } catch (IOException e) {
            // commons-csv only throws for a genuinely malformed CSV document
            // (an unterminated quote, for example) — something no per-row
            // rejection can express, because there are no rows yet.
            throw new SendPulseImportFormatException("The CSV document could not be parsed: " + e.getMessage());
        }
    }

    private List<SendPulseContactRow> parseJson(String content) {
        List<Map<String, Object>> entries;
        try {
            entries = objectMapper.readValue(content, new TypeReference<List<Map<String, Object>>>() {});
        } catch (RuntimeException e) {
            throw new SendPulseImportFormatException(
                    "The JSON document is not a list of contact objects: " + e.getMessage());
        }
        List<SendPulseContactRow> rows = new java.util.ArrayList<>(entries.size());
        int rowNumber = 1;
        for (Map<String, Object> entry : entries) {
            rows.add(toRow(entry, rowNumber));
            rowNumber++;
        }
        return List.copyOf(rows);
    }

    private SendPulseContactRow toRow(Map<String, ?> raw, int rowNumber) {
        Map<String, String> normalized = normalize(raw);

        String rawChatId = firstMatch(normalized, CHAT_ID_KEYS);
        Long chatId = null;
        SendPulseImportRejectReason reject = null;
        if (rawChatId == null) {
            reject = SendPulseImportRejectReason.MISSING_CHAT_ID;
        } else {
            try {
                chatId = Long.parseLong(rawChatId.strip());
            } catch (NumberFormatException notANumber) {
                reject = SendPulseImportRejectReason.MALFORMED_CHAT_ID;
            }
        }

        long telegramUserId = 0L;
        if (chatId != null) {
            String rawUserId = firstMatch(normalized, USER_ID_KEYS);
            telegramUserId = rawUserId == null ? chatId : parseLongOr(rawUserId, chatId);
        }

        // Deliberately not validated here: PhoneNumber.normalize lives in
        // customers.domain, and this module may not call across a module
        // boundary into another module's domain package (ModularArchitectureTests
        // enforces it). SendPulseContactImportRowService validates it instead,
        // through the customers.api seam it already calls to match or attach
        // a phone — see SendPulseImportRejectReason.MALFORMED_PHONE's own
        // comment for which layer produces it.
        String rawPhone = firstMatch(normalized, PHONE_KEYS);

        Boolean subscribed = null;
        if (reject == null) {
            String rawStatus = firstMatch(normalized, STATUS_KEYS);
            subscribed = rawStatus == null ? null : classify(rawStatus);
            if (subscribed == null) {
                reject = SendPulseImportRejectReason.UNRECOGNIZED_SUBSCRIPTION_STATUS;
            }
        }

        Instant decidedAt = null;
        String rawDecidedAt = firstMatch(normalized, DECIDED_AT_KEYS);
        if (rawDecidedAt != null) {
            decidedAt = parseInstant(rawDecidedAt);
        }

        return new SendPulseContactRow(rowNumber, chatId, telegramUserId, rawPhone, subscribed, decidedAt, reject);
    }

    private static @Nullable Boolean classify(String rawStatus) {
        String value = rawStatus.strip().toLowerCase(Locale.ROOT);
        if (SUBSCRIBED_VALUES.contains(value)) {
            return Boolean.TRUE;
        }
        if (UNSUBSCRIBED_VALUES.contains(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static long parseLongOr(String raw, long fallback) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static @Nullable Instant parseInstant(String raw) {
        String value = raw.strip();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // An unparseable date is not a rejection (see this class's own
            // javadoc) — the caller falls back to the import's own timestamp.
            return null;
        }
    }

    private static Map<String, String> normalize(Map<String, ?> raw) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String value = String.valueOf(entry.getValue()).strip();
            if (value.isEmpty()) {
                continue;
            }
            normalized.putIfAbsent(normalizeKey(entry.getKey()), value);
        }
        return normalized;
    }

    /** Case- and punctuation-insensitive: {@code "Chat ID"} and {@code "variables.phone"} both match their alias. */
    private static String normalizeKey(String key) {
        return key.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static @Nullable String firstMatch(Map<String, String> normalized, List<String> candidateKeys) {
        for (String key : candidateKeys) {
            String value = normalized.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** The document could not be parsed as its declared format at all — nothing to report per row. */
    public static final class SendPulseImportFormatException extends RuntimeException {
        public SendPulseImportFormatException(String message) {
            super(message);
        }
    }
}
