package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry.Kind;
import uz.horecaos.platform.customers.api.CustomerHistorySource;
import uz.horecaos.platform.customers.api.HistoryCursor;

/**
 * What the platform has tried to say to one guest, for the customer card (ADR 0111 §8): the
 * asynchronous half of the communication history -- SMS, push, e-mail and Telegram -- read from the
 * dispatch journal this module already keeps and has always owned.
 *
 * <p>Declared as {@link CustomerHistorySource} in {@code customers.api} and implemented here, the
 * direction {@code CustomerOrderActivityPort} established: {@code notifications} already imports
 * {@code customers.api}, so the reverse edge would close a cycle. Nothing is copied: this is a read
 * over {@code notifications.notifications}, one row per message, newest first.
 *
 * <p>A row says that a message was created, on which channel, in what state it ended and which
 * template it used -- stable codes -- and which order it was about. The rendered text, the
 * recipient's number and the template variables never leave this module: they are not on the card,
 * and a card that showed them would be a second place the phone number lives.
 */
@Component
public class JdbcCustomerCommunicationHistory implements CustomerHistorySource {

    private final JdbcClient jdbc;

    public JdbcCustomerCommunicationHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "NOTIFICATIONS";
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerHistoryEntry> history(
            UUID tenantId, UUID customerAccountId, @Nullable HistoryCursor before, int limit) {
        return jdbc.sql("""
                SELECT id, channel, template_key, status, suppression_reason, subject_type, subject_id, created_at
                  FROM notifications.notifications
                 WHERE tenant_id = :tenantId AND recipient_account_id = :accountId
                   AND (CAST(:before AS timestamptz) IS NULL
                        OR (created_at, id) < (CAST(:before AS timestamptz), CAST(:beforeId AS uuid)))
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .param("before", before == null ? null : before.occurredAt().atOffset(ZoneOffset.UTC))
                .param("beforeId", before == null ? null : before.referenceId().toString())
                .param("limit", limit)
                .query((rs, n) -> new CustomerHistoryEntry(
                        Kind.NOTIFICATION,
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getString("channel"),
                        rs.getString("status"),
                        // Why a message was refused is the reading an operator wants; otherwise which template.
                        rs.getString("suppression_reason") != null
                                ? rs.getString("suppression_reason")
                                : rs.getString("template_key"),
                        rs.getObject("id", UUID.class),
                        "Order".equals(rs.getString("subject_type")) ? rs.getObject("subject_id", UUID.class) : null,
                        null,
                        null))
                .list();
    }
}
