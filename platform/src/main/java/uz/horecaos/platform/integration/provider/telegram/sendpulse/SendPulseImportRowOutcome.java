package uz.horecaos.platform.integration.provider.telegram.sendpulse;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What happened — or, on a dry run, what would have happened — to one row
 * (ADR 0059 stage 3). The same shape either way, which is what makes a
 * dry-run report exact: {@link SendPulseContactImportRowService} computes
 * this identically in both modes and only the writes behind
 * {@link #customerAccountId} differ (real on a write run, {@code null} for a
 * row that would have created a new account on a dry run, since there is
 * nothing yet to name).
 */
public record SendPulseImportRowOutcome(
        Type type,
        @Nullable UUID customerAccountId,
        @Nullable Boolean subscribed,
        @Nullable SendPulseImportRejectReason rejectReason) {

    public static SendPulseImportRowOutcome created(@Nullable UUID customerAccountId, boolean subscribed) {
        return new SendPulseImportRowOutcome(Type.CREATED_CUSTOMER, customerAccountId, subscribed, null);
    }

    public static SendPulseImportRowOutcome matched(UUID customerAccountId, boolean subscribed) {
        return new SendPulseImportRowOutcome(Type.MATCHED_CUSTOMER, customerAccountId, subscribed, null);
    }

    public static SendPulseImportRowOutcome skippedAlreadyLinked(UUID customerAccountId, @Nullable Boolean subscribed) {
        return new SendPulseImportRowOutcome(Type.SKIPPED_ALREADY_LINKED, customerAccountId, subscribed, null);
    }

    public static SendPulseImportRowOutcome rejected(SendPulseImportRejectReason reason) {
        return new SendPulseImportRowOutcome(Type.REJECTED, null, null, reason);
    }

    public enum Type {
        CREATED_CUSTOMER,
        MATCHED_CUSTOMER,
        SKIPPED_ALREADY_LINKED,
        REJECTED
    }
}
