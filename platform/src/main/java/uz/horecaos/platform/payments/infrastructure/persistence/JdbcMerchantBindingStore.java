package uz.horecaos.platform.payments.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.payments.domain.MerchantBinding;
import uz.horecaos.platform.payments.domain.MerchantBindingStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The write side of {@code payments.merchant_bindings}, in SQL (ADR 0013).
 *
 * <p>{@link JdbcPaymentBindingResolver} reads this table too, and deliberately
 * stays a reader: its contract is "resolve the live binding for a checkout", not
 * "hold the aggregate a registration console mutates under a version", and
 * folding both into one class would make a resolver whose only two real callers
 * are a Click callback and a Payme callback also responsible for a registration
 * form's optimistic-locking retries. This class is the other half, modelled on
 * {@code tenancy.infrastructure.persistence.JdbcLegalEntityStore}: an aggregate
 * store an application service holds, not a repository either module reaches
 * into directly.
 *
 * <p>Every statement carries the tenant predicate. A binding id arriving from a
 * path variable is not evidence of anything until it is checked against the
 * tenant that is asking for it — filtering afterwards is how a cross-tenant read
 * becomes another restaurant's merchant account on a console.
 */
@Repository
public class JdbcMerchantBindingStore {

    private static final String COLUMNS = """
            id, tenant_id, legal_entity_id, provider_type, installation_id, binding_id,
            merchant_account_reference, merchant_user_reference, merchant_id_reference,
            secret_reference, callback_path_segment, supports_reversal,
            supports_partner_fiscalization, status, effective_from, effective_until, version,
            last_secret_rotated_at
            """;

    private final JdbcClient jdbc;

    public JdbcMerchantBindingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(MerchantBinding binding, Instant now) {
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (
                    id, tenant_id, legal_entity_id, provider_type, installation_id, binding_id,
                    merchant_account_reference, merchant_user_reference, merchant_id_reference,
                    secret_reference, callback_path_segment, supports_reversal,
                    supports_partner_fiscalization, status, effective_from, effective_until,
                    version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :legalEntityId, :providerType, :installationId, :bindingId,
                    :account, :user, :merchantId, :secretReference, :segment, :supportsReversal,
                    :supportsFiscalization, :status, :effectiveFrom, :effectiveUntil, :version,
                    :now, :now)
                """)
                .param("id", binding.id())
                .param("tenantId", binding.tenantId())
                .param("legalEntityId", binding.legalEntityId())
                .param("providerType", binding.providerType().name())
                .param("installationId", binding.installationId())
                .param("bindingId", binding.integrationBindingId())
                .param("account", binding.merchantAccountReference())
                .param("user", binding.merchantUserReference().orElse(null))
                .param("merchantId", binding.merchantIdReference().orElse(null))
                .param("secretReference", binding.secretReference().toString())
                .param("segment", binding.callbackPathSegment())
                .param("supportsReversal", binding.supportsReversal())
                .param("supportsFiscalization", binding.supportsPartnerFiscalization())
                .param("status", binding.status().name())
                .param("effectiveFrom", binding.effectiveFrom())
                .param("effectiveUntil", binding.effectiveUntil())
                .param("version", binding.version())
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Writes a status transition back under its expected version.
     *
     * <p>Only {@code status} and {@code version} are in the {@code SET} list.
     * Every other column is the account's own identity — {@link
     * MerchantBinding#legalEntityId()}, {@link MerchantBinding#providerType()},
     * {@link MerchantBinding#merchantAccountReference()},
     * {@link MerchantBinding#secretReference()} — and none of it may move once
     * registered; a re-registration is a new binding, not an edit of this one.
     *
     * @return false when somebody else moved the row first
     */
    public boolean update(MerchantBinding binding, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE payments.merchant_bindings
                   SET status = :status,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("id", binding.id())
                        .param("tenantId", binding.tenantId())
                        .param("status", binding.status().name())
                        .param("expectedVersion", expectedVersion)
                        .param("now", timestamp(now))
                        .update()
                == 1;
    }

    /**
     * Writes a rotated secret reference back under its expected version (ADR
     * 0065).
     *
     * <p>Only {@code secret_reference}, {@code last_secret_rotated_at}, and
     * {@code version} are in the {@code SET} list, the same narrow-column
     * discipline {@link #update} keeps for a status transition — every other
     * column is this account's own identity and none of it moves for a
     * rotation.
     *
     * @return false when somebody else moved the row first
     */
    public boolean updateSecretReference(
            UUID tenantId, UUID bindingId, SecretReference newReference, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE payments.merchant_bindings
                   SET secret_reference = :newReference,
                       last_secret_rotated_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("newReference", newReference.toString())
                        .param("tenantId", tenantId)
                        .param("id", bindingId)
                        .param("expectedVersion", expectedVersion)
                        .param("now", timestamp(now))
                        .update()
                == 1;
    }

    public Optional<MerchantBinding> find(UUID tenantId, UUID bindingId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM payments.merchant_bindings
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", bindingId)
                .query(JdbcMerchantBindingStore::toBinding)
                .optional();
    }

    public List<MerchantBinding> listForTenant(UUID tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM payments.merchant_bindings
                 WHERE tenant_id = :tenantId
                 ORDER BY created_at
                """)
                .param("tenantId", tenantId)
                .query(JdbcMerchantBindingStore::toBinding)
                .list();
    }

    /** Translates the constraints into the sentence each one is protecting. */
    public static ApiException explain(DataIntegrityViolationException violation) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("ux_merchant_binding_live_per_entity")) {
            return new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This legal entity already has an active binding for this provider; suspend or "
                            + "retire it before activating another");
        }
        if (message.contains("ux_merchant_account_belongs_to_one_entity")) {
            return new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "That merchant account is already bound elsewhere; one Click service or Payme "
                            + "cashbox belongs to exactly one legal entity across the whole platform");
        }
        if (message.contains("ux_merchant_binding_callback_segment")) {
            return new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "That callback path segment is already assigned to another binding");
        }
        if (message.contains("fk_merchant_binding_legal_entity")) {
            return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That legal entity does not belong to this tenant");
        }
        if (message.contains("fk_merchant_binding_binding")) {
            return new ApiException(
                    ErrorCode.INVALID_REQUEST, "That integration binding does not belong to this tenant");
        }
        if (message.contains("fk_merchant_binding_installation")) {
            return new ApiException(
                    ErrorCode.INVALID_REQUEST, "That provider installation does not belong to this tenant");
        }
        if (message.contains("ck_merchant_binding_secret_is_a_reference")) {
            return new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The secret reference does not match the ADR 0028 format "
                            + "horecaos:{environment}:provider_payment:{owner}:{id}");
        }
        if (message.contains("ck_merchant_binding_callback_segment")) {
            return new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The callback path segment must be 8-64 lowercase letters, digits, or hyphens, "
                            + "starting with a letter or digit");
        }
        if (message.contains("ck_merchant_binding_validity")) {
            return new ApiException(ErrorCode.VALIDATION_FAILED, "effectiveUntil must be after effectiveFrom");
        }
        return new ApiException(ErrorCode.RESOURCE_CONFLICT, "The requested value conflicts with an existing resource");
    }

    private static MerchantBinding toBinding(ResultSet row, int number) throws SQLException {
        return MerchantBinding.reconstitute(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                PaymentProviderType.valueOf(row.getString("provider_type")),
                row.getObject("installation_id", UUID.class),
                row.getObject("binding_id", UUID.class),
                row.getString("merchant_account_reference"),
                row.getString("merchant_user_reference"),
                row.getString("merchant_id_reference"),
                SecretReference.parse(row.getString("secret_reference")),
                row.getString("callback_path_segment"),
                row.getBoolean("supports_reversal"),
                row.getBoolean("supports_partner_fiscalization"),
                row.getObject("effective_from", LocalDate.class),
                row.getObject("effective_until", LocalDate.class),
                MerchantBindingStatus.valueOf(row.getString("status")),
                row.getInt("version"),
                row.getObject("last_secret_rotated_at", OffsetDateTime.class));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
