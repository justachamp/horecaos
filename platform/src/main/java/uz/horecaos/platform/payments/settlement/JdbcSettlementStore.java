package uz.horecaos.platform.payments.settlement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Settlements, their tenders, and the tenant payment-method registry
 * (ADR 0046, ADR 0038).
 *
 * <p>Every query carries the tenant predicate. Every state change is a
 * conditional UPDATE naming the status it expects, so a retried callback and a
 * concurrent operator action are separated by the row count rather than by
 * whoever read the row last.
 */
@Repository
public class JdbcSettlementStore {

    private final JdbcClient jdbc;

    public JdbcSettlementStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A registered tenant payment method.
     *
     * @param settlesFromBalance the flag every rule keys on, rather than the code.
     *                           A balance-backed method registered later inherits
     *                           reservation ordering, the money-tender invariant,
     *                           accrual net of the redeemed portion, and the
     *                           courier's cash figure without a code change
     * @param icon                     row 10.6: a code-owned icon identifier the
     *                                 console renders; {@code null} falls back to
     *                                 a generic icon
     * @param sortOrder                row 10.6: display position among this
     *                                 tenant's own methods
     * @param providerInstallationId   row 10.6: the ADR 0026 installation this
     *                                 method settles through by default
     * @param contractReference        row 10.6: the acquirer's own contract or
     *                                 merchant-agreement number, for reconciliation
     * @param version                  optimistic-locking version, incremented by
     *                                 every {@link #updateMethod} and
     *                                 {@link #updateMethodStatus}
     */
    public record MethodRow(
            UUID id,
            String code,
            String displayName,
            String responsibility,
            boolean settlesFromBalance,
            String status,
            @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Nullable String contractReference,
            int version) {}

    public UUID registerMethod(
            UUID tenantId,
            String code,
            String displayName,
            String responsibility,
            boolean settlesFromBalance,
            Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.payment_methods (
                    id, tenant_id, code, display_name, responsibility, settles_from_balance,
                    status, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :code, :displayName, :responsibility, :settlesFromBalance,
                    'ACTIVE', 1, :now, :now)
                ON CONFLICT ON CONSTRAINT uq_payment_method_code DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("displayName", displayName)
                .param("responsibility", responsibility)
                .param("settlesFromBalance", settlesFromBalance)
                .param("now", utc(now))
                .update();
        return findMethodByCode(tenantId, code).map(MethodRow::id).orElse(id);
    }

    public Optional<MethodRow> findMethodByCode(UUID tenantId, String code) {
        return jdbc.sql("""
                SELECT * FROM payments.payment_methods
                 WHERE tenant_id = :tenantId AND code = :code
                """)
                .param("tenantId", tenantId)
                .param("code", code)
                .query(JdbcSettlementStore::toMethod)
                .optional();
    }

    public Optional<MethodRow> findMethod(UUID tenantId, UUID methodId) {
        return jdbc.sql("""
                SELECT * FROM payments.payment_methods
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", methodId)
                .query(JdbcSettlementStore::toMethod)
                .optional();
    }

    /** Every method this tenant has registered, including {@code DISABLED} ones -- 10.6's own list. */
    public List<MethodRow> listMethodsForTenant(UUID tenantId) {
        return jdbc.sql("""
                SELECT * FROM payments.payment_methods
                 WHERE tenant_id = :tenantId
                 ORDER BY sort_order, code
                """)
                .param("tenantId", tenantId)
                .query(JdbcSettlementStore::toMethod)
                .list();
    }

    /**
     * Registers a new method under an operator's own choices, in {@code ACTIVE}.
     *
     * <p>Deliberately not {@link #registerMethod}: that method's {@code ON
     * CONFLICT ... DO NOTHING} is right for a checkout lazily seeding a code it
     * has never seen, and wrong for an explicit "create" a tenant asked for --
     * silently returning the existing row would tell an operator their new
     * method was created when it was refused. A duplicate code here is left to
     * raise {@link org.springframework.dao.DataIntegrityViolationException} for
     * {@link #explain} to translate.
     */
    public MethodRow insertMethod(
            UUID tenantId,
            String code,
            String displayName,
            String responsibility,
            @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Nullable String contractReference,
            Instant now) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO payments.payment_methods (
                    id, tenant_id, code, display_name, responsibility, settles_from_balance,
                    icon, sort_order, provider_installation_id, contract_reference,
                    status, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :code, :displayName, :responsibility, false,
                    :icon, :sortOrder, :installationId, :contractReference,
                    'ACTIVE', 1, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("displayName", displayName)
                .param("responsibility", responsibility)
                .param("icon", icon)
                .param("sortOrder", sortOrder)
                .param("installationId", providerInstallationId)
                .param("contractReference", contractReference)
                .param("now", utc(now))
                .update();
        return new MethodRow(
                id,
                code,
                displayName,
                responsibility,
                false,
                "ACTIVE",
                icon,
                sortOrder,
                providerInstallationId,
                contractReference,
                1);
    }

    /** Renames, re-icons, re-orders or re-binds a method. The code and responsibility never change. */
    public boolean updateMethod(
            UUID tenantId,
            UUID methodId,
            String displayName,
            @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Nullable String contractReference,
            int expectedVersion,
            Instant now) {
        return jdbc.sql("""
                UPDATE payments.payment_methods
                   SET display_name = :displayName, icon = :icon, sort_order = :sortOrder,
                       provider_installation_id = :installationId, contract_reference = :contractReference,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("tenantId", tenantId)
                        .param("id", methodId)
                        .param("displayName", displayName)
                        .param("icon", icon)
                        .param("sortOrder", sortOrder)
                        .param("installationId", providerInstallationId)
                        .param("contractReference", contractReference)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Activates or disables a method. {@code ck_payment_method_status} bounds {@code status} to the two. */
    public boolean updateMethodStatus(UUID tenantId, UUID methodId, String status, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE payments.payment_methods
                   SET status = :status, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("tenantId", tenantId)
                        .param("id", methodId)
                        .param("status", status)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Every locale this tenant has translated this method's name into. */
    public Map<String, String> methodTranslations(UUID tenantId, UUID methodId) {
        Map<String, String> byLocale = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT locale, display_name FROM payments.payment_method_translations
                 WHERE tenant_id = :tenantId AND payment_method_id = :methodId
                 ORDER BY locale
                """)
                .param("tenantId", tenantId)
                .param("methodId", methodId)
                .query((row, number) -> Map.entry(row.getString("locale"), row.getString("display_name")))
                .list()
                .forEach(entry -> byLocale.put(entry.getKey(), entry.getValue()));
        return byLocale;
    }

    /** Every method's translations in one tenant, for the list endpoint -- one query, never one per row. */
    public Map<UUID, Map<String, String>> methodTranslationsForTenant(UUID tenantId) {
        Map<UUID, Map<String, String>> byMethod = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT payment_method_id, locale, display_name FROM payments.payment_method_translations
                 WHERE tenant_id = :tenantId
                 ORDER BY payment_method_id, locale
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> Map.entry(
                        row.getObject("payment_method_id", UUID.class),
                        Map.entry(row.getString("locale"), row.getString("display_name"))))
                .list()
                .forEach(entry -> byMethod.computeIfAbsent(entry.getKey(), key -> new LinkedHashMap<>())
                        .put(entry.getValue().getKey(), entry.getValue().getValue()));
        return byMethod;
    }

    /**
     * Replaces a method's whole set of localized names, the same whole-write
     * discipline {@code replacePaymentMethods} on the channel matrix follows: a
     * caller editing three locales in one form submits all three, never a
     * per-locale PATCH that could interleave with another editor's tab.
     */
    public void replaceMethodTranslations(UUID tenantId, UUID methodId, Map<String, String> byLocale, Instant now) {
        jdbc.sql("""
                DELETE FROM payments.payment_method_translations
                 WHERE tenant_id = :tenantId AND payment_method_id = :methodId
                """).param("tenantId", tenantId).param("methodId", methodId).update();
        byLocale.forEach((locale, displayName) -> jdbc.sql("""
                INSERT INTO payments.payment_method_translations (
                    tenant_id, payment_method_id, locale, display_name, created_at, updated_at)
                VALUES (:tenantId, :methodId, :locale, :displayName, :now, :now)
                """)
                .param("tenantId", tenantId)
                .param("methodId", methodId)
                .param("locale", locale)
                .param("displayName", displayName)
                .param("now", utc(now))
                .update());
    }

    /** Translates the registry's constraints into the sentence each one is protecting. */
    public static ApiException explain(DataIntegrityViolationException violation) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("uq_payment_method_code")) {
            return new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "A payment method with this code already exists for the tenant");
        }
        if (message.contains("fk_payment_method_provider_installation")) {
            return new ApiException(ErrorCode.VALIDATION_FAILED, "That installation does not belong to this tenant");
        }
        if (message.contains("ck_payment_method_translation_locale")) {
            return new ApiException(ErrorCode.VALIDATION_FAILED, "That locale is not one of ru, uz-Latn or en");
        }
        if (message.contains("ck_payment_method_translation_present")) {
            return new ApiException(ErrorCode.VALIDATION_FAILED, "A localized name cannot be blank");
        }
        if (message.contains("ck_payment_method_code")) {
            return new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A payment method code is upper-case letters, digits and underscores");
        }
        if (message.contains("ck_payment_method_responsibility")) {
            return new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Base type must be one of PARTNER, TERMINAL, MARKETPLACE or OPERATOR");
        }
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    public record SettlementRow(
            UUID id,
            UUID tenantId,
            UUID orderId,
            String currency,
            long totalDueMinor,
            long settledMinor,
            SettlementStatus status,
            int version) {}

    public void insertSettlement(SettlementRow settlement, Instant now) {
        jdbc.sql("""
                INSERT INTO payments.order_settlements (
                    id, tenant_id, order_id, currency, total_due_minor, settled_minor,
                    status, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :orderId, :currency, :total, :settled,
                    :status, 1, :now, :now)
                """)
                .param("id", settlement.id())
                .param("tenantId", settlement.tenantId())
                .param("orderId", settlement.orderId())
                .param("currency", settlement.currency())
                .param("total", settlement.totalDueMinor())
                .param("settled", settlement.settledMinor())
                .param("status", settlement.status().name())
                .param("now", utc(now))
                .update();
    }

    /** The settlement by its own id, for a caller that arrived through a tender. */
    public Optional<SettlementRow> findSettlementById(UUID tenantId, UUID settlementId) {
        return jdbc.sql("""
                SELECT * FROM payments.order_settlements
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", settlementId)
                .query(JdbcSettlementStore::toSettlement)
                .optional();
    }

    public Optional<SettlementRow> findSettlement(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT * FROM payments.order_settlements
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcSettlementStore::toSettlement)
                .optional();
    }

    /**
     * The worklist of settlements that closed for less than the order is worth.
     *
     * <p>V0042's own column comment says {@code PARTIALLY_SETTLED} never rests
     * across a checkout boundary, and for a settlement whose legs all resolve
     * together it still does not: the status exists for the instant between one
     * tender settling and the next. A row that is still in it some time later is
     * therefore not a transient — it is an order whose money arrived after one of
     * its legs had already been resolved, most often a points hold released while
     * a provider redirect was still live. Those are exactly the orders a human has
     * to decide about: collect the difference, or refund what was collected.
     *
     * <p>Oldest first, because age is the signal, and bounded by
     * {@code settledBefore} so an order settling right now is not offered to an
     * operator mid-transaction.
     */
    public List<SettlementRow> settlementsRestingPartiallySettled(UUID tenantId, Instant settledBefore, int limit) {
        return jdbc.sql("""
                SELECT * FROM payments.order_settlements
                 WHERE tenant_id = :tenantId
                   AND status = 'PARTIALLY_SETTLED'
                   AND updated_at < :before
                 ORDER BY updated_at
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("before", utc(settledBefore))
                .param("limit", limit)
                .query(JdbcSettlementStore::toSettlement)
                .list();
    }

    public boolean transitionSettlement(
            UUID tenantId,
            UUID settlementId,
            SettlementStatus from,
            SettlementStatus to,
            long settledMinor,
            Instant now) {
        return jdbc.sql("""
                UPDATE payments.order_settlements
                   SET status = :to, settled_minor = :settled,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = :from
                """)
                        .param("tenantId", tenantId)
                        .param("id", settlementId)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("settled", settledMinor)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * One tender within a settlement.
     *
     * @param refundedMinor how much of this tender has already been given back.
     *     Present so that a second partial refund cannot re-refund the whole
     *     tender; see {@code V0048}.
     * @param paymentIntentId never set on a balance tender (the CHECK constraint
     *     {@code NOT settles_from_balance OR payment_intent_id IS NULL} refuses it)
     * @param loyaltyReservationId never set on a money tender (the CHECK constraint
     *     {@code settles_from_balance OR loyalty_reservation_id IS NULL} refuses it)
     */
    public record TenderRow(
            UUID id,
            UUID tenantId,
            UUID settlementId,
            int sequence,
            UUID paymentMethodId,
            boolean settlesFromBalance,
            long amountMinor,
            String currency,
            TenderStatus status,
            @Nullable UUID paymentIntentId,
            @Nullable UUID loyaltyReservationId,
            long refundedMinor,
            int version) {

        /** What is still refundable on this tender. Never negative. */
        public long refundableMinor() {
            return Math.max(0L, amountMinor - refundedMinor);
        }
    }

    public void insertTender(TenderRow tender, String idempotencyKey, Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", tender.id());
        parameters.put("tenantId", tender.tenantId());
        parameters.put("settlementId", tender.settlementId());
        parameters.put("sequence", tender.sequence());
        parameters.put("methodId", tender.paymentMethodId());
        parameters.put("settlesFromBalance", tender.settlesFromBalance());
        parameters.put("amount", tender.amountMinor());
        parameters.put("currency", tender.currency());
        parameters.put("status", tender.status().name());
        parameters.put("intentId", tender.paymentIntentId());
        parameters.put("reservationId", tender.loyaltyReservationId());
        parameters.put("idempotencyKey", idempotencyKey);
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO payments.tenders (
                    id, tenant_id, settlement_id, sequence, payment_method_id,
                    settles_from_balance, amount_minor, currency, status,
                    payment_intent_id, loyalty_reservation_id, idempotency_key,
                    version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :settlementId, :sequence, :methodId,
                    :settlesFromBalance, :amount, :currency, :status,
                    :intentId, :reservationId, :idempotencyKey, 1, :now, :now)
                """).params(parameters).update();
    }

    /** One tender by id, for a caller holding a tender reference and nothing else. */
    public Optional<TenderRow> findTender(UUID tenantId, UUID tenderId) {
        return jdbc.sql("""
                SELECT * FROM payments.tenders
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", tenderId)
                .query(JdbcSettlementStore::toTender)
                .optional();
    }

    public List<TenderRow> tendersOf(UUID tenantId, UUID settlementId) {
        return jdbc.sql("""
                SELECT * FROM payments.tenders
                 WHERE tenant_id = :tenantId AND settlement_id = :settlementId
                 ORDER BY sequence
                """)
                .param("tenantId", tenantId)
                .param("settlementId", settlementId)
                .query(JdbcSettlementStore::toTender)
                .list();
    }

    /**
     * Adds to what this tender has refunded, refusing to go past what it settled.
     *
     * <p>The bound lives in the WHERE clause rather than in a read-then-write,
     * because two concurrent refunds that each read "nothing refunded yet" would
     * both pass a check made in Java and both write. Here the second one matches
     * no row and reports false.
     */
    public boolean addRefunded(UUID tenantId, UUID tenderId, long amountMinor, Instant now) {
        return jdbc.sql("""
                UPDATE payments.tenders
                   SET refunded_minor = refunded_minor + :amount,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND refunded_minor + :amount <= amount_minor
                """)
                        .param("tenantId", tenantId)
                        .param("id", tenderId)
                        .param("amount", amountMinor)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean transitionTender(UUID tenantId, UUID tenderId, TenderStatus from, TenderStatus to, Instant now) {
        boolean terminalMoney = to == TenderStatus.SETTLED || to == TenderStatus.REVERSED;
        return jdbc.sql("""
                UPDATE payments.tenders
                   SET status = :to,
                       settled_at = CASE WHEN :terminal THEN COALESCE(settled_at, :now) ELSE NULL END,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = :from
                """)
                        .param("tenantId", tenantId)
                        .param("id", tenderId)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("terminal", terminalMoney)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Binds a balance tender to the loyalty hold it took, once the hold exists. */
    public void attachReservation(UUID tenantId, UUID tenderId, UUID reservationId, Instant now) {
        jdbc.sql("""
                UPDATE payments.tenders
                   SET loyalty_reservation_id = :reservationId,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND settles_from_balance
                """)
                .param("tenantId", tenantId)
                .param("id", tenderId)
                .param("reservationId", reservationId)
                .param("now", utc(now))
                .update();
    }

    /**
     * What the courier collects at the door.
     *
     * <p>The order total minus every non-cash tender that has already taken its
     * share. The failure this prevents is concrete and Delever built for it
     * explicitly: a courier who sees only the order total on an order part-settled
     * from a balance collects the total, the customer has paid twice, the tenant
     * refunds, and the customer stops using their balance.
     *
     * <p><strong>{@code RESERVED} counts, not only {@code SETTLED}.</strong> This
     * subtracted settled tenders alone, which is exactly the wrong test at exactly
     * the wrong moment: a cash order's tenders settle at handover, so at the
     * instant the courier is shown the figure the points tender is still
     * {@code RESERVED} and the query returned the full order total — the double
     * charge the method exists to prevent. A reserved balance tender is points the
     * customer has already given up; the courier must not collect them again. The
     * test passed only because it settled the points tender by hand first, a
     * sequence production never produces.
     *
     * <p>{@code RELEASED}, {@code FAILED} and {@code REVERSED} are excluded on
     * purpose: those points went back to the customer, so the money is due after
     * all.
     */
    public long cashDueMinor(UUID tenantId, UUID settlementId, String cashMethodCode) {
        Long due = jdbc.sql("""
                SELECT s.total_due_minor - COALESCE(SUM(
                           CASE WHEN t.status IN ('RESERVED', 'SETTLED') AND m.code <> :cashCode
                                THEN t.amount_minor ELSE 0 END), 0)
                  FROM payments.order_settlements s
                  LEFT JOIN payments.tenders t
                         ON t.settlement_id = s.id AND t.tenant_id = s.tenant_id
                  LEFT JOIN payments.payment_methods m
                         ON m.id = t.payment_method_id AND m.tenant_id = t.tenant_id
                 WHERE s.tenant_id = :tenantId AND s.id = :settlementId
                 GROUP BY s.total_due_minor
                """)
                .param("tenantId", tenantId)
                .param("settlementId", settlementId)
                .param("cashCode", cashMethodCode)
                .query(Long.class)
                .optional()
                .orElse(0L);
        return due == null ? 0L : due;
    }

    private static MethodRow toMethod(ResultSet row, int number) throws SQLException {
        return new MethodRow(
                row.getObject("id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("responsibility"),
                row.getBoolean("settles_from_balance"),
                row.getString("status"),
                row.getString("icon"),
                row.getInt("sort_order"),
                row.getObject("provider_installation_id", UUID.class),
                row.getString("contract_reference"),
                row.getInt("version"));
    }

    private static SettlementRow toSettlement(ResultSet row, int number) throws SQLException {
        return new SettlementRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getString("currency"),
                row.getLong("total_due_minor"),
                row.getLong("settled_minor"),
                SettlementStatus.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static TenderRow toTender(ResultSet row, int number) throws SQLException {
        return new TenderRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("settlement_id", UUID.class),
                row.getInt("sequence"),
                row.getObject("payment_method_id", UUID.class),
                row.getBoolean("settles_from_balance"),
                row.getLong("amount_minor"),
                row.getString("currency"),
                TenderStatus.valueOf(row.getString("status")),
                row.getObject("payment_intent_id", UUID.class),
                row.getObject("loyalty_reservation_id", UUID.class),
                row.getLong("refunded_minor"),
                row.getInt("version"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
