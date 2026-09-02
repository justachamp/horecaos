package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    /** Bounds a filtered export to something a browser can hold and an operator can open in Excel. */
    private static final int EXPORT_LIMIT = 2000;

    private final JdbcCustomerStore store;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final Clock clock;
    private final CustomerOrderActivityPort orderActivity;

    public CustomerListQueryService(
            JdbcCustomerStore store,
            FieldProtection protection,
            AuditRecorder audit,
            Clock clock,
            CustomerOrderActivityPort orderActivity) {
        this.store = store;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
        this.orderActivity = orderActivity;
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
     * The grid header's three counters, all computed for "today" in UTC.
     *
     * <p>A known simplification, named rather than hidden: a tenant's brands and
     * locations can each carry their own IANA timezone, and there is no single
     * "the" timezone at the tenant-wide scope this grid reads at. A
     * location-timezone-aware boundary is future work; UTC midnight is what
     * this build has, and every count below uses the same boundary as every
     * other, so the three numbers at least agree with each other.
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
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return new HeaderCounts(
                store.countActive(tenantId),
                store.countCreatedBetween(tenantId, dayStart, dayEnd),
                orderActivity.customersOrderedBetween(tenantId, dayStart, dayEnd));
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
    public List<ExportRow> exportFiltered(
            UUID tenantId, @Nullable String status, @Nullable String query, String purpose, ActorRef actor) {

        List<AccountSummaryRow> matched = list(tenantId, status, query, null, EXPORT_LIMIT);

        audit.record(AuditFact.of("customer.list.exported", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .because(purpose)
                .changed(Map.of(
                        "revealedCount",
                        matched.size(),
                        "statusFilter",
                        status == null ? "ALL" : status,
                        "hadSearchQuery",
                        query != null && !query.isBlank()))
                .correlatedBy(tenantId.toString())
                .occurredAt(clock.instant())
                .build());

        return matched.stream()
                .map(row -> new ExportRow(row.id(), row.status(), row.displayName(), primaryPhone(tenantId, row.id())))
                .toList();
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
}
