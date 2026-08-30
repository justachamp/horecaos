package uz.qoida.platform.integration.outbox;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.support.TestDatabase;
import uz.qoida.platform.tenancy.api.TenantCreated;
import uz.qoida.platform.tenancy.api.TenantId;
import uz.qoida.platform.tenancy.domain.CustomerIdentityMode;
import uz.qoida.platform.tenancy.domain.Slug;
import uz.qoida.platform.tenancy.domain.Tenant;
import uz.qoida.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

class JdbcOutboxStoreTests {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcOutboxStore outbox;
    private JdbcTenantControlPlaneStore tenancy;
    private Tenant tenant;

    @BeforeAll
    static void startDatabase() {

        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        outbox = new JdbcOutboxStore(jdbc);
        tenancy = new JdbcTenantControlPlaneStore(jdbc);
        tenant = tenant();
        tenancy.insertTenant(tenant);
    }

    @Test
    void claimsWithALeaseAndPublishesOnlyForTheCurrentClaimToken() {
        NewOutboxEvent event = event();
        outbox.append(event);

        var claimed = outbox.claimBatch(NOW, Duration.ofMinutes(5), 10);

        assertThat(claimed).singleElement().satisfies(saved -> {
            assertThat(saved.eventId()).isEqualTo(event.eventId());
            assertThat(saved.eventType()).isEqualTo("TenantCreated");
            assertThat(saved.attemptCount()).isEqualTo(1);
            assertThat(saved.payloadJson()).contains("tenant-a");
            assertThat(outbox.markPublished(saved.eventId(), UUID.randomUUID(), NOW)).isFalse();
            assertThat(outbox.markPublished(saved.eventId(), saved.claimToken(), NOW)).isTrue();
        });
        assertThat(outbox.claimBatch(NOW.plusSeconds(1), Duration.ofMinutes(5), 10)).isEmpty();
        assertThat(status(event.eventId())).isEqualTo("PUBLISHED");
    }

    @Test
    void deadLettersAnEventAfterItsBoundedRetryBudget() {
        outbox.append(event());
        OutboxRelay relay = new OutboxRelay(
                outbox,
                ignored -> {
                    throw new IllegalStateException("broker unavailable\nwithout leaking a payload");
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry(),
                10,
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                1,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1));

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT status, attempt_count, last_error, dead_lettered_at IS NOT NULL AS dead
                        FROM integration.outbox_events
                        """)
                .query((resultSet, rowNumber) -> new Object[] {
                        resultSet.getString("status"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getString("last_error"),
                        resultSet.getBoolean("dead")
                })
                .single())
                .satisfies(row -> {
                    assertThat(row[0]).isEqualTo("DEAD_LETTER");
                    assertThat(row[1]).isEqualTo(1);
                    assertThat(row[2].toString()).doesNotContain("\n");
                    assertThat(row[3]).isEqualTo(true);
                });
    }

    @Test
    void neverClaimsALaterEventForAnAggregateBlockedByAnEarlierDeadLetter() {
        NewOutboxEvent first = event();
        NewOutboxEvent second = event(
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120302"),
                NOW.plusSeconds(1));
        outbox.append(first);
        outbox.append(second);

        ClaimedOutboxEvent claimed = outbox.claimBatch(NOW.plusSeconds(1), Duration.ofMinutes(5), 10)
                .getFirst();
        assertThat(claimed.eventId()).isEqualTo(first.eventId());
        assertThat(outbox.markFailed(
                claimed.eventId(),
                claimed.claimToken(),
                NOW.plusSeconds(2),
                NOW.plusSeconds(2),
                "retry budget exhausted",
                true)).isTrue();

        assertThat(outbox.claimBatch(NOW.plus(Duration.ofDays(1)), Duration.ofMinutes(5), 10)).isEmpty();
        assertThat(status(second.eventId())).isEqualTo("PENDING");
    }

    @Test
    void rollsBackBusinessStateAndItsOutboxRecordTogether() {
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            tenancy.insertTenant(tenant);
            outbox.append(event());
            throw new ExpectedRollbackException();
        })).isInstanceOf(ExpectedRollbackException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM tenant.tenants").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM integration.outbox_events").query(Long.class).single()).isZero();
    }

    @Test
    void mapsATypedTenancyEventIntoTheExternalOutboxContract() {
        TenancyOutboxEventListener listener = new TenancyOutboxEventListener(
                outbox,
                JsonMapper.builder().findAndAddModules().build(),
                "tenancy.events");
        MDC.put("correlationId", "request-42");
        MDC.put("traceId", "trace-42");
        try {
            listener.append(new TenantCreated(
                    UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120301"),
                    tenant.id(),
                    NOW,
                    tenant.slug().value(),
                    tenant.legalName(),
                    tenant.displayName(),
                    tenant.defaultCurrency().getCurrencyCode(),
                    tenant.defaultTimezone().getId(),
                    tenant.status().name(),
                    CustomerIdentityMode.TENANT_SHARED.name()));
        } finally {
            MDC.clear();
        }

        assertThat(outbox.claimBatch(NOW, Duration.ofMinutes(5), 1))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.topic()).isEqualTo("tenancy.events");
                    assertThat(saved.partitionKey()).isEqualTo(tenant.id().value().toString());
                    assertThat(saved.correlationId()).isEqualTo("request-42");
                    assertThat(saved.traceContextJson()).contains("trace-42");
                    assertThat(saved.payloadJson()).contains("TENANT_SHARED");
                });
    }

    private String status(UUID eventId) {
        return jdbc.sql("SELECT status FROM integration.outbox_events WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private NewOutboxEvent event() {
        return event(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120300"), NOW);
    }

    private NewOutboxEvent event(UUID eventId, Instant occurredAt) {
        return new NewOutboxEvent(
                eventId,
                "TenantCreated",
                1,
                tenant.id().value(),
                "Tenant",
                tenant.id().value(),
                "tenancy.events",
                tenant.id().value().toString(),
                "request-42",
                null,
                occurredAt,
                "{\"tenantId\":\"" + tenant.id().value() + "\",\"slug\":\"tenant-a\"}",
                "{}");
    }

    private static Tenant tenant() {
        return Tenant.provision(
                new TenantId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120200")),
                new Slug("tenant-a"),
                "Tenant A LLC",
                "Tenant A",
                Currency.getInstance("UZS"),
                ZoneId.of("Asia/Tashkent"));
    }

    private static final class ExpectedRollbackException extends RuntimeException { }
}
