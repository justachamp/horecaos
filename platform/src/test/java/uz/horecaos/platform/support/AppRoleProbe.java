package uz.horecaos.platform.support;

import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.configuration.rls.JdbcTenantRlsSession;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;

/**
 * A login role holding nothing but {@code horecaos_application}, and the plumbing to run the
 * platform's own services as it (ADR 0056).
 *
 * <p>A row-level-security claim tested from the migrator's connection proves nothing, because
 * PostgreSQL exempts a table's owner from its own policies. What the application sees is what
 * {@code horecaos_app} sees, and only a non-owner connection can show it. This is that
 * connection, plus the three things a suite then needs: a transaction manager over it, a real
 * {@link JdbcTenantRlsSession}, and {@link #transactional} - a proxy that applies the
 * {@code @Transactional} annotations of the class it wraps exactly as Spring's own advice
 * does, so a reader that forgot its annotation loses its tenant binding here as it would in
 * production, instead of being quietly rescued by the test supplying a transaction.
 *
 * <p>The role is cluster-wide, so each suite picks its own name and {@link #close()} drops it.
 */
public final class AppRoleProbe implements AutoCloseable {

    private final String role;
    private final JdbcClient owner;

    /** The application role's connection: row-level security applies to it. */
    public final DataSource dataSource;

    public final JdbcClient application;
    public final DataSourceTransactionManager transactionManager;
    public final TransactionTemplate tx;
    public final TenantRlsSession rls;

    private AppRoleProbe(String role, JdbcClient owner, DataSource dataSource) {
        this.role = role;
        this.owner = owner;
        this.dataSource = dataSource;
        this.application = JdbcClient.create(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.rls = new JdbcTenantRlsSession(application);
    }

    /**
     * Creates {@code role} (replacing a leftover of the same name) with
     * {@code horecaos_application} and, without {@code INHERIT}, the exempt role, which is the
     * shape the real {@code horecaos_app} has because the init script grants it.
     */
    public static AppRoleProbe open(TestDatabase.Handle db, String role) {
        JdbcClient owner = JdbcClient.create(db.dataSource());
        String password = role + "-pw";
        owner.sql("DROP ROLE IF EXISTS " + role).update();
        owner.sql("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'").update();
        owner.sql("ALTER ROLE " + role + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        owner.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + role).update();
        // TestDatabase's container never runs infra/production/postgres-init/10-application-role.sh,
        // which is where horecaos_app gets this membership (V0161 explains why no migration can).
        owner.sql("GRANT horecaos_platform_bypass TO " + role + " WITH INHERIT FALSE")
                .update();
        return new AppRoleProbe(role, owner, db.dataSourceAs(role, password));
    }

    /** Wraps {@code target} so its {@code @Transactional} methods run in a transaction on this role's connection. */
    @SuppressWarnings("unchecked")
    public <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @Override
    public void close() {
        try {
            TestDatabase.onCluster("DROP ROLE IF EXISTS " + role);
        } catch (RuntimeException leftover) {
            System.err.println("AppRoleProbe: " + role + " outlived its suite (" + leftover.getMessage() + ")");
        }
    }
}
