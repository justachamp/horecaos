package uz.qoida.platform.payments.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.payments.application.PaymentBindingResolver;
import uz.qoida.platform.payments.domain.PaymentProviderType;
import uz.qoida.platform.payments.domain.ProviderBinding;

/**
 * Resolves the merchant account for a legal entity and a provider (ADR 0013).
 *
 * <p>The legal entity is in the predicate, not the tenant alone. A tenant holding
 * three legal entities holds three Click services and three Payme cashboxes,
 * because neither provider takes a seller identity as a per-request field, and a
 * resolver that matched on tenant and provider would return whichever row came
 * first and put another restaurant's name on the receipt.
 *
 * <p>Nothing here reads a credential. {@code secret_reference} is an ADR 0028
 * handle; the adapter resolves it at call time, uses it, and never logs it.
 */
@Repository
public class JdbcPaymentBindingResolver implements PaymentBindingResolver {

    private static final String SELECT = """
            SELECT id, tenant_id, legal_entity_id, provider_type, installation_id, binding_id,
                   merchant_account_reference, merchant_user_reference, merchant_id_reference,
                   secret_reference,
                   callback_path_segment, supports_reversal, supports_partner_fiscalization,
                   effective_from, effective_until
            FROM payments.merchant_bindings
            """;

    private final JdbcClient jdbc;

    public JdbcPaymentBindingResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProviderBinding> resolve(UUID tenantId, UUID legalEntityId,
            PaymentProviderType providerType, LocalDate businessDate) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId
                   AND legal_entity_id = :legalEntityId
                   AND provider_type = :providerType
                   AND status = 'ACTIVE'
                   AND effective_from <= :businessDate
                   AND (effective_until IS NULL OR effective_until > :businessDate)
                """)
                .param("tenantId", tenantId).param("legalEntityId", legalEntityId)
                .param("providerType", providerType.name()).param("businessDate", businessDate)
                .query(JdbcPaymentBindingResolver::map)
                .optional();
    }

    /**
     * The inbound lookup.
     *
     * <p>Deliberately not scoped by tenant, because at this point there is no
     * tenant yet: the request has arrived from a provider on an endpoint whose path
     * carries the binding, and the binding is what supplies the tenant. The
     * segment is not a credential — the MD5 signature or the Basic credential is
     * what authenticates the request, and this only says which secret to check it
     * against.
     */
    @Override
    public Optional<ProviderBinding> byCallbackSegment(String callbackPathSegment) {
        return jdbc.sql(SELECT + """
                 WHERE callback_path_segment = :segment AND status = 'ACTIVE'
                """)
                .param("segment", callbackPathSegment)
                .query(JdbcPaymentBindingResolver::map)
                .optional();
    }

    private static ProviderBinding map(ResultSet row, int rowNumber) throws SQLException {
        return new ProviderBinding(
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
                row.getObject("effective_until", LocalDate.class));
    }
}
