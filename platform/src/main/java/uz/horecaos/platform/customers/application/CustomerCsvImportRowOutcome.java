package uz.horecaos.platform.customers.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What happened — or, on a dry run, what would have happened — to one row of
 * a customer CSV import (row {@code X.13}/{@code 5.1b}).
 *
 * <p>The same shape either way, which is what makes a dry-run report exact:
 * {@link CustomerCsvImportRowService} computes this identically in both
 * modes, and only the write behind {@link #customerAccountId} differs (real
 * on a write run, {@code null} for a row that would have created a new
 * account on a dry run — see {@code SendPulseImportRowOutcome}'s own doc for
 * the identical rule).
 */
public record CustomerCsvImportRowOutcome(
        Type type,
        @Nullable UUID customerAccountId,
        @Nullable CustomerCsvImportRejectReason rejectReason) {

    public static CustomerCsvImportRowOutcome created(@Nullable UUID customerAccountId) {
        return new CustomerCsvImportRowOutcome(Type.CREATED_CUSTOMER, customerAccountId, null);
    }

    public static CustomerCsvImportRowOutcome matched(UUID customerAccountId) {
        return new CustomerCsvImportRowOutcome(Type.MATCHED_CUSTOMER, customerAccountId, null);
    }

    public static CustomerCsvImportRowOutcome rejected(CustomerCsvImportRejectReason reason) {
        return new CustomerCsvImportRowOutcome(Type.REJECTED, null, reason);
    }

    public enum Type {
        CREATED_CUSTOMER,
        MATCHED_CUSTOMER,
        REJECTED
    }
}
