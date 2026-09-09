package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.inbox.EnvelopeValidator;
import uz.horecaos.platform.integration.inbox.InboxExecutor;
import uz.horecaos.platform.integration.inbox.InboxHandlerRegistry;
import uz.horecaos.platform.integration.inbox.InboxResult;
import uz.horecaos.platform.integration.inbox.JdbcInboxStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0005's inbox around {@code PosSyncRequestedHandler}, end to end (ADR
 * 0012).
 *
 * <p>The Kafka broker is not started — records are offered directly to {@link
 * InboxExecutor#execute}, the exact method the real {@code PosCommandListener}
 * calls with a consumer record's fields, matching {@code
 * ShipmentReconciliationPathTests}'s own reasoning for why that is enough.
 * {@link PosCatalogSyncService} is mocked rather than built for real: the
 * property under test is the inbox's delivery guarantee around the handler, not
 * the provider read {@link PosCatalogSyncService#run} performs, which is its own
 * class's subject.
 */
class PosSyncRequestedHandlerPathTests {

    private static final String TOPIC = "pos.commands";
    private static final UUID TENANT = UUID.fromString("018f6f4e-7500-7000-8000-0000000e0001");
    private static final UUID BINDING = UUID.fromString("018f6f4e-7500-7000-8000-0000000e0002");
    private static final Instant NOW = Instant.parse("2026-08-25T11:00:00Z");

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private PosCatalogSyncService sync;
    private PosSyncRequestedHandler handler;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
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
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     status, version)
                VALUES (:id, 'pos-command-inbox', 'Legal', 'POS command inbox', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        sync = mock(PosCatalogSyncService.class);
        when(sync.run(any(UUID.class), any(UUID.class), anyString(), eq(false)))
                .thenReturn(new PosCatalogSyncService.RunResult(
                        UUID.randomUUID(), "REVIEW_REQUIRED", ProviderOutcome.success(Map.of(), null), 0, 0));
        handler = new PosSyncRequestedHandler(sync);
    }

    @Test
    @DisplayName("a duplicated command reads the provider once, not once per delivery")
    void aDuplicateCallsSyncOnce() {
        UUID requestId = UUID.randomUUID();
        String body = scheduledBody(requestId, UUID.randomUUID());

        assertThat(offer(requestId, body, 0)).isEqualTo(InboxResult.PROCESSED);
        assertThat(offer(requestId, body, 1)).isEqualTo(InboxResult.DUPLICATE_IGNORED);
        assertThat(offer(requestId, body, 2)).isEqualTo(InboxResult.DUPLICATE_IGNORED);

        verify(sync, times(1)).run(eq(TENANT), eq(BINDING), eq("SCHEDULED"), eq(false));
    }

    @Test
    @DisplayName("two commands for the same binding are both handled however they are delivered")
    void twoCommandsOutOfTheirOwnOrderAreBothHandled() {
        // occurredAt(earlier) is the schedule's own claim time; occurredAt(later)
        // is a resume requested afterward. Delivered in reverse: the later one
        // reaches the inbox first, at the earlier Kafka offset -- the shape a
        // redelivery or a rebalance can produce, since Kafka orders a partition,
        // not the business time inside two different records.
        UUID earlierRequest = UUID.randomUUID();
        UUID laterRequest = UUID.randomUUID();
        UUID resumedRun = UUID.randomUUID();

        String laterBody = resumedBody(laterRequest, resumedRun, "2026-08-25T11:30:00Z");
        String earlierBody = scheduledBody(earlierRequest, UUID.randomUUID(), "2026-08-25T11:00:00Z");

        assertThat(offer(laterRequest, laterBody, 0)).isEqualTo(InboxResult.PROCESSED);
        assertThat(offer(earlierRequest, earlierBody, 1)).isEqualTo(InboxResult.PROCESSED);

        verify(sync, times(1)).run(eq(TENANT), eq(BINDING), eq("SCHEDULED"), eq(false));
        verify(sync, times(1)).run(eq(TENANT), eq(BINDING), eq("RESUMED"), eq(false));
    }

    private InboxResult offer(UUID eventId, String body, long offset) {
        return executor()
                .execute(PosSyncRequestedHandler.CONSUMER_NAME, BINDING.toString(), body, Map.of(), TOPIC, 0, offset);
    }

    private InboxExecutor executor() {
        return new InboxExecutor(
                new JdbcInboxStore(jdbc, Clock.fixed(NOW, ZoneOffset.UTC)),
                new InboxHandlerRegistry(List.of(handler)),
                new EnvelopeValidator(JsonMapper.builder().build(), 262_144),
                JsonMapper.builder().build(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new SimpleMeterRegistry(),
                event -> {},
                10);
    }

    private String scheduledBody(UUID requestId, UUID scheduleId) {
        return scheduledBody(requestId, scheduleId, "2026-08-25T10:00:00Z");
    }

    /** The record exactly as {@code PosSyncOutbox} writes it. */
    private String scheduledBody(UUID requestId, UUID scheduleId, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"PosSyncRequested","eventVersion":1,
                 "tenantId":"%s","aggregateType":"PosBinding","aggregateId":"%s",
                 "correlationId":"correlation-1","causationId":null,
                 "occurredAt":"%s",
                 "payload":{"requestId":"%s","tenantId":"%s","bindingId":"%s",
                            "scheduleId":"%s","resumedRunId":null,"triggerType":"SCHEDULED",
                            "requestedAt":"%s"}}""".formatted(
                        requestId, TENANT, BINDING, occurredAt, requestId, TENANT, BINDING, scheduleId, occurredAt);
    }

    private String resumedBody(UUID requestId, UUID resumedRunId, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"PosSyncRequested","eventVersion":1,
                 "tenantId":"%s","aggregateType":"PosBinding","aggregateId":"%s",
                 "correlationId":"correlation-2","causationId":null,
                 "occurredAt":"%s",
                 "payload":{"requestId":"%s","tenantId":"%s","bindingId":"%s",
                            "scheduleId":null,"resumedRunId":"%s","triggerType":"RESUMED",
                            "requestedAt":"%s"}}""".formatted(
                        requestId, TENANT, BINDING, occurredAt, requestId, TENANT, BINDING, resumedRunId, occurredAt);
    }
}
