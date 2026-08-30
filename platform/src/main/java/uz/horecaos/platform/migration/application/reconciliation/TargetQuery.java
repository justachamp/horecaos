package uz.horecaos.platform.migration.application.reconciliation;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The same three reads against the platform's own database (ADR 0024).
 *
 * <p>A reconciliation rule reads the target directly rather than through a domain
 * service, and that is the one place on this platform where doing so is correct:
 * the question is what the target <em>stores</em>, and a service could answer
 * with what it computes. A rule that agreed with the code it was checking would
 * be evidence of nothing.
 *
 * <p>Every query a rule writes here carries the tenant predicate. The rule is
 * evaluated for one scope, and a count that spanned tenants would reconcile one
 * restaurant's migration against another's data.
 */
public interface TargetQuery {

    Optional<BigInteger> exactInteger(String sql, Map<String, Object> parameters);

    Optional<String> text(String sql, Map<String, Object> parameters);

    List<Map<String, Object>> rows(String sql, Map<String, Object> parameters);
}
