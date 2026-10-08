package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.commercial.domain.CardTopUp;

/**
 * One attempt to charge a tenant's card for money to hold in its wallet (ADR 0095, V0505).
 *
 * <p>Written before the provider is asked and settled after, like {@link JdbcCardChargeAttemptStore}: a
 * row left {@code PENDING} is exactly "we asked and never learned the answer", the state a
 * reconciliation pass resolves under the same key.
 */
@Repository
public class JdbcCardTopUpStore {

    private static final String COLUMNS = """
            id, tenant_id, amount_minor, currency, outcome, provider_detail, wallet_entry_id,
            requested_by, requested_at, settled_at
            """;

    private final JdbcClient jdbc;

    public JdbcCardTopUpStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes the attempt as PENDING, pinned to the card it is asked under. A second PENDING attempt for the
     * tenant is refused by the unique index (a {@code DuplicateKeyException}), which is what turns two
     * clicks into one charge.
     */
    public void begin(
            UUID id,
            UUID tenantId,
            long amountMinor,
            String currency,
            String cardTokenReference,
            String requestedBy,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO commercial.card_top_ups (
                            id, tenant_id, amount_minor, currency, card_token_reference, outcome,
                            requested_by, requested_at)
                        VALUES (:id, :tenantId, :amount, :currency, :token, 'PENDING', :requestedBy, :now)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("token", cardTokenReference)
                .param("requestedBy", requestedBy)
                .param("now", utc(now))
                .update();
    }

    public Optional<CardTopUp> find(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM commercial.card_top_ups WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(JdbcCardTopUpStore::read)
                .optional();
    }

    public Optional<CardTopUp> findPending(UUID tenantId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM commercial.card_top_ups WHERE tenant_id = :tenantId AND outcome = 'PENDING'")
                .param("tenantId", tenantId)
                .query(JdbcCardTopUpStore::read)
                .optional();
    }

    /** Newest first. */
    public List<CardTopUp> recent(UUID tenantId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM commercial.card_top_ups WHERE tenant_id = :tenantId"
                        + " ORDER BY requested_at DESC, id DESC LIMIT :limit")
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query(JdbcCardTopUpStore::read)
                .list();
    }

    /** The card token an attempt was minted under; read only to retry the attempt under its own card. */
    public Optional<String> cardTokenOf(UUID tenantId, UUID id) {
        return jdbc.sql(
                        "SELECT card_token_reference FROM commercial.card_top_ups WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(String.class)
                .optional();
    }

    /** Resolves a PENDING attempt as declined or unconfigured; false when something already resolved it. */
    public boolean settleWithoutMoney(UUID tenantId, UUID id, String outcome, @Nullable String detail, Instant now) {
        return jdbc.sql("""
                                UPDATE commercial.card_top_ups
                                   SET outcome = :outcome, provider_detail = :detail, settled_at = :now
                                 WHERE tenant_id = :tenantId AND id = :id AND outcome = 'PENDING'
                                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("outcome", outcome)
                        .param("detail", detail)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Resolves a PENDING attempt as paid, naming the ledger entry that holds the money. */
    public boolean settleSucceeded(UUID tenantId, UUID id, String providerReference, UUID walletEntryId, Instant now) {
        return jdbc.sql("""
                                UPDATE commercial.card_top_ups
                                   SET outcome = 'SUCCEEDED', provider_detail = :reference,
                                       wallet_entry_id = :entryId, settled_at = :now
                                 WHERE tenant_id = :tenantId AND id = :id AND outcome = 'PENDING'
                                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("reference", providerReference)
                        .param("entryId", walletEntryId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * How many attempts are still waiting for an answer from the merchant account {@code installationId}:
     * the ones whose card reference was minted under it, and the hand-typed ones, which go to whichever
     * account is active. Read before that account is suspended, because the account that replaces it has
     * never heard of their keys and cannot say whether they were charged.
     */
    public long countPendingUnder(UUID installationId) {
        return jdbc.sql("SELECT count(*) FROM commercial.card_top_ups WHERE outcome = 'PENDING' AND "
                        + CardReferenceSql.ASKED_THROUGH_INSTALLATION)
                .param("installationId", installationId.toString())
                .query(Long.class)
                .single();
    }

    /** Attempts still waiting for an answer that were asked before {@code cutoff}, oldest first. */
    public List<CardTopUp> pendingRequestedBefore(Instant cutoff, int limit) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM commercial.card_top_ups WHERE outcome = 'PENDING' AND requested_at <= :cutoff"
                        + " ORDER BY requested_at ASC LIMIT :limit")
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .query(JdbcCardTopUpStore::read)
                .list();
    }

    private static CardTopUp read(ResultSet row, int number) throws SQLException {
        return new CardTopUp(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getLong("amount_minor"),
                row.getString("currency"),
                row.getString("outcome"),
                row.getString("provider_detail"),
                row.getObject("wallet_entry_id", UUID.class),
                row.getString("requested_by"),
                java.util.Objects.requireNonNull(instant(row, "requested_at")),
                instant(row, "settled_at"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
