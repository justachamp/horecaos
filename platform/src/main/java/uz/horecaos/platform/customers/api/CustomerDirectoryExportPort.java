package uz.horecaos.platform.customers.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;

/**
 * What the export centre's {@code CUSTOMER_DIRECTORY} report needs from the Customers module
 * (ADR 0043/ADR 0029, wave P28).
 *
 * <p>Defined here, in {@code customers.api}, and implemented by {@code
 * customers.application.CustomerDirectoryExportAdapter} — the same inverted-dependency shape
 * {@link CustomerOrderActivityPort}'s own doc explains: {@code reporting -> customers.api} keeps
 * the dependency arrow pointing one way with no cycle, rather than {@code reporting.application}
 * reaching into {@code customers.application.CustomerListQueryService} directly, which {@code
 * ModularArchitectureTests} refuses (a module's own application-layer classes are internal; only
 * {@code .api} is exposed).
 *
 * <p>{@code CustomerListQueryService#exportFiltered} already carries the one thing this port must
 * not re-decide: whether a phone reveal happens at all. Asking for {@code includePhone = true}
 * calls exactly that method, so the row quota, the truncation flag and the {@code
 * customer.list.exported} audit fact it writes are the same ones a support agent's own filtered
 * export produces — this port adds no second copy of any of it, only the column reshaping the
 * export centre's generic CSV writer needs.
 */
public interface CustomerDirectoryExportPort {

    /**
     * The row ceiling a decrypted (PII-included) export is bound to regardless of what {@code
     * rowQuota} the caller of {@link #export} passes. Declared here rather than in {@code
     * customers.application.CustomerListQueryService} — which {@code reporting} may not import,
     * see this interface's own doc — and that class's own {@code EXPORT_LIMIT} references this
     * value rather than restating it, so the two can never drift apart.
     */
    int PII_ROW_LIMIT = 2000;

    /**
     * @param includePhone whether the PII group may be revealed at all — the export centre's own
     *                     {@code customer.pii.export} decision, already made by the time this is
     *                     called. {@code false} never touches {@code FieldProtection}.
     * @param rowQuota     the ceiling to apply when {@code includePhone} is {@code false}. Ignored
     *                     when it is {@code true}: {@code CustomerListQueryService}'s own {@code
     *                     EXPORT_LIMIT} governs a decrypted export regardless, so a second,
     *                     independently-set ceiling on the same call could never disagree with it
     *                     silently.
     */
    ExportBundle export(
            UUID tenantId,
            @Nullable String status,
            @Nullable String query,
            boolean includePhone,
            int rowQuota,
            String purpose,
            ActorRef actor);

    /** @param phone null unless the export included the PII group for this row */
    record ExportedRow(
            UUID accountId,
            String status,
            @Nullable String displayName,
            @Nullable String phone) {}

    /**
     * @param approval the ADR 0027 outcome that governed this call. {@code includePhone = false}
     *     never touches an approval policy and always carries {@link ApprovalOutcome.NotRequired}.
     *     {@code includePhone = true} carries whatever {@code
     *     CustomerListQueryService#exportFiltered} decided — critically, on a {@link
     *     ApprovalOutcome.Pending}/{@link ApprovalOutcome.Declined} outcome {@code rows} is empty
     *     for a reason that has nothing to do with the filter, and every caller of {@link #export}
     *     must read this field, not just {@code rows.isEmpty()}, before treating the result as a
     *     completed export that matched no one.
     */
    record ExportBundle(List<ExportedRow> rows, boolean truncated, ApprovalOutcome approval) {}
}
