package uz.horecaos.platform.migration.infrastructure.legacy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.migration.application.reconciliation.LegacyQuery;

/**
 * Reconciliation's read of the legacy database (ADR 0024).
 *
 * <p>The mirror of {@code JdbcTargetQuery}, on the read-only legacy pool. Same
 * conversion rule, and the reason is sharper on this side: the legacy
 * {@code order_price}, {@code delivery_price} and {@code packaging_price} are
 * whole som, summed by PostgreSQL into a {@code numeric}, and reading that as a
 * double would lose exactness above 2^53 — which a five-year estate total in som
 * reaches without being remarkable.
 */
@Repository
@ConditionalOnProperty(prefix = "horecaos.migration.legacy", name = "enabled", havingValue = "true")
public class JdbcLegacyQuery implements LegacyQuery {

    private final JdbcClient jdbc;

    public JdbcLegacyQuery(@Qualifier(LegacySourceConfiguration.LEGACY_JDBC_CLIENT) JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigInteger> exactInteger(String sql, Map<String, Object> parameters) {
        // The mapped column is a SQL aggregate and genuinely comes back NULL when
        // nothing matched. The row mapper wraps that in an Optional itself, rather
        // than returning a bare nullable value, so every element on the
        // one-row-expected list stays non-null; DataAccessUtils#optionalResult
        // still enforces at most one row and still turns zero rows into empty.
        return jdbc.sql(sql)
                .params(parameters)
                .query((row, number) -> Optional.ofNullable(exact(row.getObject(1))))
                .optional()
                .orElse(Optional.empty());
    }

    @Override
    public Optional<String> text(String sql, Map<String, Object> parameters) {
        return jdbc.sql(sql).params(parameters).query(String.class).optional();
    }

    @Override
    public List<Map<String, Object>> rows(String sql, Map<String, Object> parameters) {
        return jdbc.sql(sql).params(parameters).query().listOfRows();
    }

    private static @Nullable BigInteger exact(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case BigInteger exact -> exact;
            case BigDecimal decimal -> decimal.toBigIntegerExact();
            case Number number -> BigInteger.valueOf(number.longValue());
            default ->
                throw new IllegalStateException("A legacy reconciliation measure came back as " + value.getClass());
        };
    }
}
