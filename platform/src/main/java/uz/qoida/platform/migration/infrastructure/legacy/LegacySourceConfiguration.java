package uz.qoida.platform.migration.infrastructure.legacy;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.iam.api.secrets.SecretResolver;

/**
 * The read-only connection to the legacy PostgreSQL (ADR 0024, ADR 0028).
 *
 * <p>A second datasource, never the platform's own. The two databases have
 * different owners, different uptime expectations and different failure meanings:
 * the target being down is an outage, and the legacy being down is a paused
 * migration. Sharing a pool would make the second look like the first.
 *
 * <p>Not a {@code @Primary} bean and not named {@code dataSource}, so Spring's
 * transaction manager, Flyway and every existing {@code JdbcClient} keep pointing
 * at the target. A migration that could accidentally run Flyway against the
 * system it is retiring is not a migration anyone would sign off.
 *
 * <p>{@code read-only} on the pool and a session-level {@code default_transaction_read_only}
 * are belt and braces over the grant. ADR 0024 requires read-only source access;
 * the grant on the legacy side is what makes it true, and these two make a bug on
 * this side fail rather than write.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LegacySourceProperties.class)
@ConditionalOnProperty(prefix = "qoida.migration.legacy", name = "enabled", havingValue = "true")
public class LegacySourceConfiguration {

    /** The bean name every legacy-side component qualifies on. */
    public static final String LEGACY_JDBC_CLIENT = "legacyJdbcClient";

    @Bean(destroyMethod = "close")
    HikariDataSource legacyDataSource(LegacySourceProperties properties, SecretResolver secrets) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("legacy-source");
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        // Resolved at startup and held by Hikari for the life of the pool, which
        // is the one place on this platform where that is acceptable: a pool
        // cannot re-authenticate per connection, and the migration user's
        // credential rotates by restarting a migrator rather than mid-run. The
        // value is never logged and never written down (ADR 0028).
        config.setPassword(secrets.resolve(SecretReference.parse(properties.passwordReference()))
                .reveal());
        config.setMaximumPoolSize(properties.maximumPoolSize());
        config.setReadOnly(true);
        config.setAutoCommit(true);
        config.addDataSourceProperty("ApplicationName", "qoida-migration");
        // Belt to the pool's braces: this one is enforced by the server, so a
        // component that opened its own transaction on this connection would still
        // be unable to write.
        config.setConnectionInitSql(
                "SET default_transaction_read_only = on; SET statement_timeout = %d"
                        .formatted(properties.statementTimeout().toMillis()));
        return new HikariDataSource(config);
    }

    @Bean(LEGACY_JDBC_CLIENT)
    JdbcClient legacyJdbcClient(HikariDataSource legacyDataSource) {
        return JdbcClient.create((DataSource) legacyDataSource);
    }
}
