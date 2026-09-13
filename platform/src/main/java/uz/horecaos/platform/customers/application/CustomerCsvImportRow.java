package uz.horecaos.platform.customers.application;

import org.jspecify.annotations.Nullable;

/**
 * One parsed line of a generic customer CSV export (row {@code X.13}/{@code
 * 5.1b}), before anything about the platform's own state has been consulted.
 *
 * <p>Unlike {@code SendPulseContactRow}, which keys on a Telegram chat id and
 * rejects any row without one, this row has exactly one required field: a
 * phone number. There is no chat, no bot, and no subscription flag — a
 * generic merchant customer list is an address book, not a messaging
 * audience export.
 *
 * @param rowNumber    1-based, matching the order rows appeared in the
 *                     source file — what the report and {@code
 *                     customer.customer_import_run_rows} both key on
 * @param rawPhone     null only when {@link #rejectReason} is {@link
 *                     CustomerCsvImportRejectReason#MISSING_PHONE}. Kept as
 *                     the raw string rather than normalized or hashed here:
 *                     normalization can itself fail, and {@link
 *                     CustomerCsvImportRowService} treats that failure the
 *                     same as every other per-row rejection rather than
 *                     letting the whole batch stop.
 * @param rejectReason null for a row this parse considers usable — resolving
 *                     a customer account can still reject it later, for a
 *                     reason this record cannot know yet (an ambiguous or
 *                     malformed phone)
 */
public record CustomerCsvImportRow(
        int rowNumber, @Nullable String rawPhone, @Nullable CustomerCsvImportRejectReason rejectReason) {

    public boolean isRejected() {
        return rejectReason != null;
    }
}
