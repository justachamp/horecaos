package uz.horecaos.platform.iam.infrastructure.authorization;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration-driven platform-admin bootstrap (ADR 0025, Gap A of the
 * 2026-08-30 proving run).
 *
 * <p>A fresh deployment has no {@code PLATFORM}-scope grant and no HTTP path
 * that could create the first one — {@link JdbcAuthorizationService}'s Keycloak
 * realm-role bypass confers exactly {@code iam.grant.manage}, at any scope,
 * specifically so a platform admin can create their own first grant and then
 * grant themselves everything else through the ordinary audited API. Every
 * real deployment and every proving run needs that first grant to exist
 * without a human clicking through Keycloak <em>and</em> hand-writing a SQL
 * row, which is what this configuration replaces.
 *
 * @param bootstrapPlatformAdmins Keycloak subject ids that {@link
 *                                PlatformAdminBootstrapReconciler} ensures hold
 *                                the platform-admin grant on every startup.
 *                                Empty by default, and blank entries are
 *                                dropped rather than rejected — an env var
 *                                left as {@code ""} must resolve to "nobody
 *                                configured", not to a startup failure that
 *                                takes an unrelated deployment down.
 */
@ConfigurationProperties(prefix = "horecaos.iam")
public record IamBootstrapProperties(List<String> bootstrapPlatformAdmins) {

    public IamBootstrapProperties {
        bootstrapPlatformAdmins = bootstrapPlatformAdmins == null
                ? List.of()
                : bootstrapPlatformAdmins.stream()
                        .filter(subject -> subject != null && !subject.isBlank())
                        .distinct()
                        .toList();
    }
}
