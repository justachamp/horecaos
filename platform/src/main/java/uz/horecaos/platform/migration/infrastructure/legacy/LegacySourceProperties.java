package uz.horecaos.platform.migration.infrastructure.legacy;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to reach the legacy PostgreSQL, read-only (ADR 0024, ADR 0028).
 *
 * <p>No password field, and that is the point. ADR 0028 forbids a credential in a
 * committed file, so what is configured here is a <em>reference</em> the ADR 0028
 * manager resolves at connection time. A property named {@code password} would be
 * filled in by the first person deploying this under time pressure, and it would
 * be filled in in a file that is in git.
 *
 * <p>{@code enabled} defaults to false. A platform with no migration running has
 * no reason to hold an open pool against somebody else's production database, and
 * a datasource that appeared by default would be one more thing to remember to
 * turn off at retirement — which ADR 0024 makes a signed step.
 *
 * @param username the migration user, which must hold SELECT and nothing else.
 *                 ADR 0024 requires read-only source access, and the enforcement
 *                 that matters is the grant on the legacy side rather than the
 *                 absence of an INSERT in this codebase
 * @param maximumPoolSize deliberately small. This pool runs against a database
 *                 that is still serving customers, and ADR 0024 requires catch-up
 *                 to be load-tested precisely so migration cannot exhaust
 *                 production capacity
 * @param statementTimeout an upper bound on any one extraction query, applied as
 *                 the connection's {@code statement_timeout}. A page that scans
 *                 more than expected is a mapping error, and it must surface as a
 *                 failed page rather than as a long-running query on somebody
 *                 else's primary
 */
@ConfigurationProperties(prefix = "horecaos.migration.legacy")
public record LegacySourceProperties(
        boolean enabled,
        String url,
        String username,
        String passwordReference,
        Integer maximumPoolSize,
        Duration statementTimeout) {

    public LegacySourceProperties {
        maximumPoolSize = maximumPoolSize == null ? 4 : maximumPoolSize;
        statementTimeout = statementTimeout == null ? Duration.ofSeconds(30) : statementTimeout;

        if (enabled) {
            requirePresent(url, "horecaos.migration.legacy.url");
            requirePresent(username, "horecaos.migration.legacy.username");
            requirePresent(passwordReference, "horecaos.migration.legacy.password-reference");
            if (maximumPoolSize < 1) {
                throw new IllegalArgumentException("A legacy pool holds at least one connection");
            }
            if (statementTimeout.isNegative() || statementTimeout.isZero()) {
                throw new IllegalArgumentException(
                        "A statement timeout of zero is no timeout, which is what it would mean to "
                                + "PostgreSQL as well");
            }
        }
    }

    private static void requirePresent(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    property + " is required when the legacy source is enabled");
        }
    }
}
