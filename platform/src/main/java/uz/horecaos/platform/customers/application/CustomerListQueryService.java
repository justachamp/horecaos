package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.api.BusinessDayWindows;
import uz.horecaos.platform.customers.api.CustomerDirectoryExportPort;
import uz.horecaos.platform.customers.api.CustomerOrderActivityPort;
import uz.horecaos.platform.customers.domain.PhoneNumber;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AccountSummaryRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * The CRM grid: list, search, and the header counters (frontend information
 * architecture §5.1).
 *
 * <p>Its own service rather than a widening of {@link CustomerProfileService},
 * which reads and writes one account's own contact points and addresses. This
 * one reads across the tenant's whole customer base, and every method here
 * carries the section's central discipline in its own way: {@link #list} never
 * decrypts anything at all, and {@link #exportFiltered} is the one path that
 * does, behind a single audited egress event rather than one reveal per row.
 */
@Service
public class CustomerListQueryService {

    private static final String CONTACT_TABLE = "customer.contact_points";

    /**
     * A query normalizes to fewer digits than this and is treated as a name
     * search rather than a phone search. Below this length, a normalized query
     * is more likely a partial name that happens to contain digits (a nickname,
     * a numbered branch) than a phone number, and hashing it would search for a
     * number nobody typed.
     */
    private static final int MIN_PHONE_DIGITS = 7;

    /**
     * Bounds a filtered export to something a browser can hold and an operator can open in Excel.
     *
     * <p>Public rather than private since wave P28, and equal to {@link
     * CustomerDirectoryExportPort#PII_ROW_LIMIT} by reference rather than by two hand-typed
     * {@code 2000}s: the export centre's own job table records that constant as the row quota
     * it applies to a {@code CUSTOMER_DIRECTORY} export which includes the PII column group, and
     * {@code reporting} cannot import this class to read it directly (see that interface's own
     * doc for why the constant lives there).
     */
    public static final int EXPORT_LIMIT = CustomerDirectoryExportPort.PII_ROW_LIMIT;

    private final JdbcCustomerStore store;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final Clock clock;
    private final CustomerOrderActivityPort orderActivity;
    private final BusinessDayWindows businessDays;

    public CustomerListQueryService(
            JdbcCustomerStore store,
            FieldProtection protection,
            AuditRecorder audit,
            Clock clock,
            CustomerOrderActivityPort orderActivity,
            BusinessDayWindows businessDays) {
        this.store = store;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
        this.orderActivity = orderActivity;
        this.businessDays = businessDays;
    }

    /**
     * A page of the grid, newest first.
     *
     * <p>Carries no contact value, no address, nothing that was ever encrypted —
     * see the class doc. A phone-shaped {@code query} matches by the same keyed
     * hash {@code CustomerProfileService#findAccountsByContact} looks up by,
     * never by decrypting a candidate to compare it; anything else matches
     * against {@code display_name}.
     *
     * @param status  null for every status, or one value to filter to
     * @param query   null or blank to skip search and list everything
     * @param cursor  the previous page's last account id, or null for the first
     *                page
     * @throws UnknownCursorException {@code cursor} does not name an account of
     *                this tenant's
     */
    @Transactional(readOnly = true)
    public List<AccountSummaryRow> list(
            UUID tenantId, @Nullable String status, @Nullable String query, @Nullable UUID cursor, int limit) {

        Instant beforeCreatedAt = null;
        if (cursor != null) {
            beforeCreatedAt = store.accountCursor(tenantId, cursor).orElseThrow(UnknownCursorException::new);
        }

        String trimmed = query == null ? null : query.strip();
        String nameQuery = null;
        String phoneHash = null;
        if (trimmed != null && !trimmed.isEmpty()) {
            String digitsOnly = trimmed.replaceAll("[^0-9]", "");
            if (digitsOnly.length() >= MIN_PHONE_DIGITS) {
                phoneHash = protection.lookupHash(
                        tenantId,
                        CustomerProfileService.ContactType.PHONE.lookupDomain(),
                        PhoneNumber.normalize(trimmed));
            } else {
                nameQuery = trimmed;
            }
        }

        return store.listAccounts(tenantId, status, nameQuery, phoneHash, beforeCreatedAt, cursor, limit);
    }

    /**
     * The grid header's three counters, "today" scoped to the tenant's own
     * ADR 0043 business-day boundary (row {@code 5.1a}) — the same one the
     * live board and Reports use, via {@link BusinessDayWindows}, rather
     * than a second, unregistered notion of a day computed against UTC
     * midnight.
     *
     * <p>Before this method depended on {@link BusinessDayWindows}, it
     * computed "today" as {@code LocalDate.ofInstant(now, ZoneOffset.UTC)}.
     * Uzbekistan is UTC+5 with no daylight saving, so for the five hours
     * between UTC midnight and midnight in Tashkent, a customer who
     * registered at, say, 02:00 local time stopped counting as "registered
     * today" the moment the wall clock passed 05:00 local: the UTC-dated
     * window had already rolled over to the next UTC day, a range that never
     * contained that row's {@code created_at}. See {@link BusinessDayWindows}'s
     * own doc for why the fix is a shared port rather than a second copy of
     * {@code BusinessDayBoundary}'s arithmetic in this module.
     *
     * <p>{@code total} counts every non-{@code MERGED} account — ACTIVE,
     * SUSPENDED, CLOSED and ANONYMIZED all included ({@link
     * JdbcCustomerStore#countActive}'s own name is a slight misnomer kept for
     * compatibility) — the agreed meaning {@code customers.total.v1} now
     * documents in {@link uz.horecaos.platform.reporting.domain.MetricRegistry}.
     *
     * <p>{@code orderedToday} is the one counter this service cannot compute
     * alone — it asks {@link CustomerOrderActivityPort}, this module's own
     * port that {@code ordering} implements (that interface's own doc
     * explains why the dependency runs this direction), rather than querying
     * {@code ordering.orders} directly, which {@code AGENTS.md}'s
     * module-boundary rule forbids.
     */
    @Transactional(readOnly = true)
    public HeaderCounts counts(UUID tenantId) {
        BusinessDayWindows.Window today = businessDays.businessDayContaining(tenantId, clock.instant());

        return new HeaderCounts(
                store.countActive(tenantId),
                store.countCreatedBetween(tenantId, today.from(), today.to()),
                orderActivity.customersOrderedBetween(tenantId, today.from(), today.to()));
    }

    /**
     * A filtered export, decrypted, as one audited PII egress event (frontend
     * information architecture §5.1: "filtered export as an audited PII egress
     * event").
     *
     * <p>One {@link AuditFact} for the whole call, written before any row is
     * decrypted, carrying the filter that produced the set and how many rows it
     * matched — the same "purpose and count, not a client credential" shape
     * {@code CustomerProfileService#revealAddresses} uses at the scale of one
     * account, here at the scale of however many rows a filter matches. This is
     * deliberately not a loop of per-row reveal calls: an export is one export,
     * and the difference between an agent viewing one customer and exporting
     * fifty thousand is exactly what a single {@code revealedCount} answers for.
     *
     * @param purpose recorded as the audit fact's reason (ADR 0027)
     */
    @Transactional
    public ExportResult exportFiltered(
            UUID tenantId, @Nullable String status, @Nullable String query, String purpose, ActorRef actor) {

        // One row past the limit, never decrypted or returned, exists only to
        // answer "was this cut short" honestly — the trap this row's own brief
        // names: the export used to cap silently at EXPORT_LIMIT with no way
        // for an operator to tell a complete 2000-row result from a filter
        // that actually matched 2001 rows and lost the last one.
        List<AccountSummaryRow> matched = list(tenantId, status, query, null, EXPORT_LIMIT + 1);
        boolean truncated = matched.size() > EXPORT_LIMIT;
        List<AccountSummaryRow> bounded = truncated ? matched.subList(0, EXPORT_LIMIT) : matched;

        audit.record(AuditFact.of("customer.list.exported", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .because(purpose)
                .changed(Map.of(
                        "revealedCount",
                        bounded.size(),
                        "statusFilter",
                        status == null ? "ALL" : status,
                        "hadSearchQuery",
                        query != null && !query.isBlank(),
                        "truncated",
                        truncated))
                .correlatedBy(tenantId.toString())
                .occurredAt(clock.instant())
                .build());

        List<ExportRow> rows = bounded.stream()
                .map(row -> new ExportRow(row.id(), row.status(), row.displayName(), primaryPhone(tenantId, row.id())))
                .toList();
        return new ExportResult(rows, truncated);
    }

    /** The primary phone, decrypted — or null when the account holds none. Never audited per row; see {@link #exportFiltered}. */
    private @Nullable String primaryPhone(UUID tenantId, UUID accountId) {
        return store.contactPoints(tenantId, accountId).stream()
                .filter(contact -> "PHONE".equals(contact.type()) && contact.isPrimary())
                .findFirst()
                .map(contact -> protection.reveal(
                        tenantId,
                        ProtectedValue.deserialize(contact.encryptedValue()),
                        new RecordRef(CONTACT_TABLE, "encrypted_value", contact.id()),
                        "Operations console: filtered customer export"))
                .orElse(null);
    }

    /** The cursor names no account of this tenant's. */
    public static class UnknownCursorException extends RuntimeException {
        public UnknownCursorException() {
            super("This cursor does not name a customer account of this tenant's");
        }
    }

    public record HeaderCounts(long total, long registeredToday, long orderedToday) {}

    public record ExportRow(
            UUID accountId,
            String status,
            @Nullable String displayName,
            @Nullable String phone) {}

    /** @param truncated true when the filtered set held more than {@link #EXPORT_LIMIT} rows and was cut */
    public record ExportResult(List<ExportRow> rows, boolean truncated) {}
}
