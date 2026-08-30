package uz.horecaos.platform.migration.infrastructure.persistence;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.migration.application.reconciliation.TargetQuery;

/**
 * Reconciliation's read of the platform's own database (ADR 0024).
 *
 * <p>Deliberately thin. The SQL belongs to the rule, which is versioned; this
 * binds parameters and converts. What it does <em>not</em> do is convert a
 * numeric to a double on the way past, which is the one conversion that would
 * silently cost money — every integer leaves here as {@link BigInteger}.
 */
@Repository
public class JdbcTargetQuery implements TargetQuery {

    private final JdbcClient jdbc;

    public JdbcTargetQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigInteger> exactInteger(String sql, Map<String, Object> parameters) {
        return jdbc.sql(sql)
                .params(parameters)
                .query((row, number) -> exact(row.getObject(1)))
                .optional();
    }

    @Override
    public Optional<String> text(String sql, Map<String, Object> parameters) {
        return jdbc.sql(sql).params(parameters).query(String.class).optional();
    }

    @Override
    public List<Map<String, Object>> rows(String sql, Map<String, Object> parameters) {
        return jdbc.sql(sql).params(parameters).query().listOfRows();
    }

    /**
     * Exact, or an error. Never a rounding.
     *
     * <p>{@code toBigIntegerExact} because a scale on a column that has none is a
     * mapping error, and a reconciliation that rounded it away would report a
     * difference of zero for a discrepancy of a fraction — which is precisely the
     * difference an auditor asks about.
     */
    static BigInteger exact(Object value) {
        return switch (value) {
            case null -> null;
            case BigInteger exact -> exact;
            case BigDecimal decimal -> decimal.toBigIntegerExact();
            case Number number -> BigInteger.valueOf(number.longValue());
            default -> throw new IllegalStateException("A reconciliation measure came back as " + value.getClass());
        };
    }
}
