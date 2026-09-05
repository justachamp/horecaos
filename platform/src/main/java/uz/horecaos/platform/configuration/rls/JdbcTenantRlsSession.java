package uz.horecaos.platform.configuration.rls;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Binds the ADR 0056 session settings through the same {@link JdbcClient} —
 * and therefore, inside a Spring-managed transaction, the same pooled
 * connection — that the rest of a service method's repository calls use.
 *
 * <p>That sharing is the whole mechanism. Spring's {@code @Transactional}
 * binds one JDBC connection to the transaction on the thread that started it;
 * every {@link JdbcClient} call made on that thread while the transaction is
 * open reuses it via {@code DataSourceUtils.getConnection}. So a call to
 * {@link #bindTenant(UUID)} made first in a transactional method reaches
 * PostgreSQL on the exact connection every later statement in that method
 * will use, and {@code SET LOCAL} — {@code set_config}'s third argument reads
 * as PostgreSQL's own name for it — scopes what it sets to that one
 * transaction. No custom {@code TransactionManager}, connection wrapper, or
 * ordering with Spring's transactional advice is needed; the binding is a
 * plain statement issued at the right moment, which is why this class is a
 * handful of lines and not a framework.
 */
@Component
public class JdbcTenantRlsSession implements TenantRlsSession {

    /**
     * The GUC every ADR 0056 policy reads. A name containing a dot is a
     * PostgreSQL "placeholder" setting: any session may {@code SET} or
     * {@code set_config} it without {@code shared_preload_libraries} or any
     * other server-side registration, and {@code current_setting(name, true)}
     * answers {@code NULL} rather than raising for a session that never set
     * it — which is exactly the fail-closed behaviour V0161's policy template
     * relies on for a connection nothing has bound yet.
     */
    static final String TENANT_ID_SETTING = "horecaos.tenant_id";

    /** V0161. Held by {@code horecaos_app} without {@code INHERIT} — see that migration's comment. */
    static final String BYPASS_ROLE = "horecaos_platform_bypass";

    private final JdbcClient jdbc;

    public JdbcTenantRlsSession(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTenant(UUID tenantId) {
        // set_config(..., true) IS "SET LOCAL" — the boolean is documented as
        // "is_local" — expressed as a function call so it can be bound as an
        // ordinary parameterised statement instead of string-formatted SQL.
        // The setting name itself is a fixed literal, never caller input, so
        // it does not need the same treatment.
        jdbc.sql("SELECT set_config('" + TENANT_ID_SETTING + "', :tenantId, true)")
                .param("tenantId", tenantId.toString())
                .query(String.class)
                .single();
    }

    @Override
    public void bindPlatform() {
        // ROLE is not an ordinary value and PostgreSQL does not accept a bind
        // parameter in its place; BYPASS_ROLE is a fixed internal constant,
        // never caller input, so concatenating it is not the injection risk
        // it would be for anything else in this class.
        jdbc.sql("SET LOCAL ROLE " + BYPASS_ROLE).update();
    }
}
