package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.integration.outbox.JdbcOutboxStore;
import uz.horecaos.platform.integration.outbox.TenancyOutboxEventListener;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.CreateTenantCommand;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityMode;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

class TenantOutboxTransactionIntegrationTests {

    private static TestDatabase.Handle db;
    private static DriverManagerDataSource dataSource;

    private AnnotationConfigApplicationContext context;

    @BeforeAll
    static void startDatabase() {

        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        JdbcClient.create(dataSource)
                .sql("TRUNCATE TABLE tenant.tenants CASCADE")
                .update();
        TestConfiguration.dataSource = dataSource;
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void commitsTheTenantAndTypedOutboxEventInOneServiceTransaction() {
        TenantControlPlaneService service = context.getBean(TenantControlPlaneService.class);

        var tenant = service.createTenant(new CreateTenantCommand(
                "food-group",
                "Food Group LLC",
                "Food Group",
                "UZS",
                "Asia/Tashkent",
                CustomerIdentityMode.TENANT_SHARED));

        JdbcClient jdbc = context.getBean(JdbcClient.class);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.tenants WHERE id = :tenantId")
                        .param("tenantId", tenant.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT event_type, tenant_id, aggregate_id, status, payload->>'slug' AS slug
                        FROM integration.outbox_events
                        """)
                        .query((resultSet, rowNumber) -> Map.of(
                                "eventType", resultSet.getString("event_type"),
                                "tenantId", resultSet.getObject("tenant_id"),
                                "aggregateId", resultSet.getObject("aggregate_id"),
                                "status", resultSet.getString("status"),
                                "slug", resultSet.getString("slug")))
                        .single())
                .containsEntry("eventType", "TenantCreated")
                .containsEntry("tenantId", tenant.id())
                .containsEntry("aggregateId", tenant.id())
                .containsEntry("status", "PENDING")
                .containsEntry("slug", "food-group");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {

        // Wired from the outer class's setUp(), before the context refreshes;
        // never read before that assignment happens.
        private static @Nullable DataSource dataSource;

        @Bean
        DataSource dataSource() {
            return Objects.requireNonNull(dataSource, "setUp() must set the data source before the context refreshes");
        }

        @Bean
        JdbcClient jdbcClient(DataSource configuredDataSource) {
            return JdbcClient.create(configuredDataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource configuredDataSource) {
            return new DataSourceTransactionManager(configuredDataSource);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-19T01:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        CurrentActor currentActor() {
            AuthenticatedActor actor = new AuthenticatedActor("platform-admin", Set.of("platform-admin"), Map.of());
            return () -> actor;
        }

        @Bean
        TenantAccessPolicy tenantAccessPolicy(CurrentActor currentActor) {
            return new TenantAccessPolicy(currentActor, denyAll(), false);
        }

        @Bean
        TenantControlPlaneStore tenantControlPlaneStore(JdbcClient jdbc) {
            return new JdbcTenantControlPlaneStore(jdbc);
        }

        @Bean
        JdbcOutboxStore jdbcOutboxStore(JdbcClient jdbc) {
            return new JdbcOutboxStore(jdbc);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean
        TenancyOutboxEventListener tenancyOutboxEventListener(JdbcOutboxStore outbox, ObjectMapper objectMapper) {
            return new TenancyOutboxEventListener(outbox, objectMapper, "tenancy.events");
        }

        @Bean
        uz.horecaos.platform.audit.api.AuditRecorder auditRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
            return new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(jdbc, objectMapper);
        }

        @Bean
        TenantControlPlaneService tenantControlPlaneService(
                TenantControlPlaneStore store,
                TenantAccessPolicy accessPolicy,
                Clock clock,
                ApplicationEventPublisher events,
                uz.horecaos.platform.audit.api.AuditRecorder auditRecorder,
                CurrentActor currentActor) {
            return new TenantControlPlaneService(store, accessPolicy, clock, events, auditRecorder, currentActor);
        }
    }

    /**
     * A resolver that grants nothing, so these tests exercise the ADR 0003 rule
     * that is actually in force rather than accidentally passing on capabilities.
     */
    private static uz.horecaos.platform.iam.api.AuthorizationService denyAll() {
        return new uz.horecaos.platform.iam.api.AuthorizationService() {
            @Override
            public boolean has(
                    String subject,
                    uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                return false;
            }

            @Override
            public void require(
                    String subject,
                    uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                throw new AccessDeniedException(capability, scope);
            }

            @Override
            public uz.horecaos.platform.iam.api.CapabilityView viewFor(String subject, java.util.UUID tenantId) {
                return new uz.horecaos.platform.iam.api.CapabilityView(
                        subject, "", java.util.Set.of(), java.util.List.of(), 0);
            }
        };
    }
}
