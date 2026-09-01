package uz.horecaos.platform.integration.provider.telegram.sendpulse;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One parsed line of a SendPulse contact export (ADR 0059 stage 3), before
 * anything about the platform's own state has been consulted.
 *
 * <p>Deliberately holds the {@code rawPhone} string rather than a normalized
 * or hashed form: normalization can itself fail ({@link
 * uz.horecaos.platform.customers.domain.PhoneNumber#normalize} throws on a
 * blank value), and {@link SendPulseContactFileParser} treats that failure
 * the same as every other per-row rejection rather than letting the whole
 * batch stop.
 *
 * @param rowNumber              1-based, matching the order rows appeared in
 *                               the source file — what the report and the
 *                               {@code integration.sendpulse_import_run_rows}
 *                               table both key on
 * @param chatId                 null only when {@link #rejectReason} is
 *                               {@link SendPulseImportRejectReason#MISSING_CHAT_ID}
 *                               or {@link SendPulseImportRejectReason#MALFORMED_CHAT_ID}
 * @param telegramUserId         the export's own Telegram user id column when
 *                               one exists; {@code chatId} itself otherwise —
 *                               true for every 1:1 bot subscriber, which is
 *                               what a SendPulse bot audience export is
 * @param rawPhone               null when the row carried no phone value at
 *                               all — not the same as a phone that failed to
 *                               parse, which is instead a rejection
 * @param subscribed             null only when {@link #rejectReason} is
 *                               {@link SendPulseImportRejectReason#UNRECOGNIZED_SUBSCRIPTION_STATUS}
 * @param subscriptionDecidedAt  the date SendPulse recorded the subscription
 *                               state, when the export carries a recognisable
 *                               one; null otherwise, in which case the
 *                               import's own run timestamp is used instead
 * @param rejectReason           null for a row this parse considers usable —
 *                               resolving a customer account and creating a
 *                               binding can still reject it later, for a
 *                               reason this record cannot know yet
 *                               (an ambiguous or malformed phone)
 */
public record SendPulseContactRow(
        int rowNumber,
        @Nullable Long chatId,
        long telegramUserId,
        @Nullable String rawPhone,
        @Nullable Boolean subscribed,
        @Nullable Instant subscriptionDecidedAt,
        @Nullable SendPulseImportRejectReason rejectReason) {

    public boolean isRejected() {
        return rejectReason != null;
    }
}
