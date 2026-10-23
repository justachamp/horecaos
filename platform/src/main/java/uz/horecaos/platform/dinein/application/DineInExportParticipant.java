package uz.horecaos.platform.dinein.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportReveal;
import uz.horecaos.platform.customers.spi.ExportRow;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.iam.api.protection.FieldProtection;

/**
 * Her table bookings and the visits she opened (ADR 0029): the name, number and note a booking took, opened under the
 * producing person's purpose, and the table sessions she started by scanning a code. A booking that was never linked to her
 * account but holds her number is hers too, found by the keyed hash of the number, the rule the erasure uses.
 */
@Component
@ExportsCustomerData(
        name = "dinein",
        tables = {"dinein.reservations", "dinein.table_sessions"})
public class DineInExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;
    private static final String RESERVATIONS = "dinein.reservations";

    private final JdbcClient jdbc;
    private final FieldProtection protection;

    public DineInExportParticipant(JdbcClient jdbc, FieldProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        String byNumber = subject.phoneLookupHashes().isEmpty()
                ? ""
                : " OR (customer_account_id IS NULL AND guest_phone_lookup_hash IN (:hashes))";
        var reservations = jdbc.sql("""
                SELECT id, brand_id, location_id, guest_name_encrypted, guest_phone_encrypted,
                       secondary_phone_encrypted, note_encrypted, party_size, requested_from, requested_to,
                       status, created_at
                  FROM dinein.reservations
                 WHERE tenant_id = :tenantId AND (customer_account_id = :accountId""" + byNumber + """
                ) ORDER BY requested_from DESC, id DESC LIMIT :limit
                """)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH);
        if (!subject.phoneLookupHashes().isEmpty()) {
            reservations = reservations.param("hashes", subject.phoneLookupHashes());
        }
        List<Map<String, @Nullable Object>> reservationRows = new ArrayList<>();
        for (Map<String, Object> raw : reservations.query().listOfRows()) {
            reservationRows.add(ExportReveal.row(protection, subject, RESERVATIONS, "id", raw));
        }

        List<Map<String, @Nullable Object>> sessionRows = jdbc
                .sql("""
                SELECT id, brand_id, location_id, reservation_id, party_size, business_date, opened_at, status,
                       currency, settled_total_minor, closed_at, origin
                  FROM dinein.table_sessions
                 WHERE tenant_id = :tenantId AND opened_by_account_id = :accountId
                 ORDER BY opened_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows()
                .stream()
                .map(ExportRow::render)
                .toList();
        return ExportSection.of(
                ExportTable.ofNewestFirst(RESERVATIONS, reservationRows),
                ExportTable.ofNewestFirst("dinein.table_sessions", sessionRows));
    }
}
