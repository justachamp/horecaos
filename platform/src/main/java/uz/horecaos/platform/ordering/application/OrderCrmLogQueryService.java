package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore.CrmLogRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore.CustomerLabelRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderCrmLogStore.LogCursor;

/**
 * Wave 9 w4-reports-distance-crm (7.2a): the CRM half of the console order
 * log, decrypted exactly as far as an ordinary console read may go (orders.md
 * §1.5/§3.7) — the customer's name in full, the phone decrypted for the
 * caller to mask ({@code PhoneMasking}, in {@code ordering.web}). Not a
 * reveal in the ADR 0029 sense, the same distinction {@code
 * OrderQueryService#customerDetail}'s own doc draws: there is no
 * caller-supplied purpose to thread through and no audit fact for an
 * ordinary list read, because the masked form this produces is what the
 * console may show with no separate gate. The PII-including export path
 * ({@code OrderCrmLogExportAdapter}) is the one caller of this data that
 * writes an audit fact, for the reason a bulk egress and a bounded console
 * page are different acts (ADR 0029/ADR 0043).
 */
@Service
public class OrderCrmLogQueryService {

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";
    private static final String NAME_COLUMN = "display_name_encrypted";
    private static final String CONTACT_COLUMN = "contact_encrypted";

    /**
     * The fixed purpose behind every ordinary display decrypt this service
     * performs — see {@code OrderQueryService#DETAIL_DISPLAY_PURPOSE}'s own
     * doc for why an unprompted list read carries no caller-supplied purpose.
     */
    private static final String LOG_DISPLAY_PURPOSE = "ORDER_CRM_LOG_DISPLAY";

    private final JdbcOrderCrmLogStore store;
    private final FieldProtection protection;

    public OrderCrmLogQueryService(JdbcOrderCrmLogStore store, FieldProtection protection) {
        this.store = store;
        this.protection = protection;
    }

    @Transactional(readOnly = true)
    public List<CrmLogEntry> log(
            UUID tenantId, Instant from, Instant to, List<UUID> locationIds, int limit, @Nullable LogCursor cursor) {
        return store.list(tenantId, from, to, locationIds, limit, cursor).stream()
                .map(row -> toEntry(tenantId, row))
                .toList();
    }

    /**
     * Gap map row 1.1: the order board's own Клиент column — the customer
     * half of a batch of already-fetched board rows, never a date range.
     * Decrypted exactly as far as {@link #log} already goes (the name in
     * full, the phone for the caller to mask); no audit fact, for the
     * identical reason this class's own doc gives for an ordinary list read.
     */
    @Transactional(readOnly = true)
    public List<CustomerLabelEntry> customerLabels(UUID tenantId, Set<UUID> orderIds) {
        return store.customerLabels(tenantId, orderIds).stream()
                .map(row -> toLabelEntry(tenantId, row))
                .toList();
    }

    private CustomerLabelEntry toLabelEntry(UUID tenantId, CustomerLabelRow row) {
        return new CustomerLabelEntry(
                row.orderId(),
                row.customerType(),
                row.anonymized(),
                decrypt(tenantId, row.orderId(), NAME_COLUMN, row.displayNameEncrypted()),
                decrypt(tenantId, row.orderId(), CONTACT_COLUMN, row.contactEncrypted()));
    }

    private CrmLogEntry toEntry(UUID tenantId, CrmLogRow row) {
        return new CrmLogEntry(
                row.orderId(),
                row.occurredAt(),
                row.locationId(),
                row.customerType(),
                row.anonymized(),
                decrypt(tenantId, row.orderId(), NAME_COLUMN, row.displayNameEncrypted()),
                decrypt(tenantId, row.orderId(), CONTACT_COLUMN, row.contactEncrypted()),
                row.operatorPrincipalId(),
                row.courierDisplayReference());
    }

    private @Nullable String decrypt(UUID tenantId, UUID orderId, String column, @Nullable String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(ciphertext),
                new RecordRef(SNAPSHOT_TABLE, column, orderId),
                LOG_DISPLAY_PURPOSE);
    }

    /**
     * One console row. {@code customerName}/{@code customerPhone} are
     * decrypted (never masked here — see this class's own doc); a caller
     * rendering the console masks the phone and never the name, and a caller
     * exporting decides column-by-column via the export centre's own PII
     * gate.
     */
    public record CrmLogEntry(
            UUID orderId,
            Instant occurredAt,
            UUID locationId,
            String customerType,
            boolean anonymized,
            @Nullable String customerName,
            @Nullable String customerPhone,
            String operatorPrincipalId,
            @Nullable String courierDisplayReference) {}

    /**
     * One order's customer label — gap map row 1.1's Клиент column, the same
     * decrypt-not-mask rule {@link CrmLogEntry}'s own doc states.
     */
    public record CustomerLabelEntry(
            UUID orderId,
            String customerType,
            boolean anonymized,
            @Nullable String customerName,
            @Nullable String customerPhone) {}
}
