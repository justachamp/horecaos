package uz.horecaos.platform.conversations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.conversations.application.ConversationMessageStore.Direction;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The ADR 0059/0029 retention sweep, against a real PostgreSQL and a clock
 * that actually moves. CLAUDE.md's own rule for this genre: a lifetime or
 * sweep asserted without advancing the clock is asserted against an instant,
 * not a duration — every fixture here is written through the real repository
 * calls at one clock reading and read back after the {@link MutableClock} has
 * genuinely moved forward, never inserted pre-aged by hand.
 */
class ConversationRetentionSweeperTests {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableClock clock;
    private ConversationMessageStore messages;
    private ConversationRepository conversations;
    private FlowRunRepository runs;
    private ConversationRetentionSweeper sweeper;

    private UUID tenantId;
    private UUID brandId;
    private UUID installationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the retention sweep test");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        clock = new MutableClock(T0);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get, clock);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));

        messages = new ConversationMessageStore(jdbc, clock, protection);
        conversations = new ConversationRepository(jdbc, clock);
        runs = new FlowRunRepository(jdbc, clock, protection, objectMapper);
        ConversationRetentionService retention = new ConversationRetentionService(messages, conversations, runs);
        sweeper = new ConversationRetentionSweeper(retention, clock, 500);

        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        installationId = UUID.randomUUID();
        seedTenantBrandInstallation();
    }

    @Test
    @DisplayName("messages past their conversation's retention are deleted; a newer one survives")
    void expiredMessagesAreDeletedAndNewerOnesSurvive() {
        UUID conversationId = insertConversation("IDLE", 1);

        // T0: an old message.
        UUID oldMessage = messages.record(tenantId, conversationId, Direction.INBOUND, null, "Hello")
                .id();

        // T0 + 20 days: a second message, fresher than the first by construction.
        clock.advance(Duration.ofDays(20));
        UUID freshMessage = messages.record(tenantId, conversationId, Direction.OUTBOUND, "welcome", "Welcome!")
                .id();

        // T0 + 40 days: 1 month past the old message (Jan 1 -> cutoff Jan 10 at
        // this point), 20 days past the fresh one (cutoff Jan 10, message is
        // Jan 21) — the old message has genuinely aged past retention only
        // because the clock moved, not because it was written that way.
        clock.advance(Duration.ofDays(20));

        var result = sweeper.runOnce();

        assertThat(result.deletedMessages()).isEqualTo(1);
        assertThat(messageExists(oldMessage))
                .as("the expired message is actually gone, not just unreturned")
                .isFalse();
        assertThat(messageExists(freshMessage))
                .as("a message inside its retention window must survive")
                .isTrue();
        assertThat(conversationExists(conversationId))
                .as("an IDLE conversation is never removed by this sweep, however old its history")
                .isTrue();
    }

    @Test
    @DisplayName("a CLOSED conversation whose entire history has aged out disappears, flow runs included")
    void aClosedConversationFullyAgedOutDisappears() {
        UUID conversationId = insertConversation("CLOSED", 1);
        UUID flowDocumentId = insertFlowDocument();
        UUID flowRunId = insertAbandonedFlowRun(conversationId, flowDocumentId);
        UUID messageId = messages.record(tenantId, conversationId, Direction.INBOUND, null, "Bye")
                .id();

        clock.advance(Duration.ofDays(40));

        var result = sweeper.runOnce();

        assertThat(result.deletedMessages()).isEqualTo(1);
        assertThat(result.deletedConversations()).isEqualTo(1);
        assertThat(conversationExists(conversationId)).isFalse();
        assertThat(messageExists(messageId)).isFalse();
        assertThat(flowRunExists(flowRunId))
                .as("a flow run cannot outlive the conversation it belongs to")
                .isFalse();
    }

    @Test
    @DisplayName("a CLOSED conversation is not removed while a message inside its window still exists")
    void aClosedConversationWithALiveMessageSurvives() {
        UUID conversationId = insertConversation("CLOSED", 1);
        UUID oldMessage = messages.record(tenantId, conversationId, Direction.INBOUND, null, "Bye")
                .id();
        clock.advance(Duration.ofDays(20));
        UUID freshMessage = messages.record(tenantId, conversationId, Direction.OUTBOUND, "close", "See you!")
                .id();
        clock.advance(Duration.ofDays(20));

        var result = sweeper.runOnce();

        assertThat(result.deletedMessages()).isEqualTo(1);
        assertThat(messageExists(oldMessage)).isFalse();
        assertThat(messageExists(freshMessage)).isTrue();
        assertThat(conversationExists(conversationId))
                .as("one surviving message is enough to keep even a CLOSED conversation")
                .isTrue();
    }

    @Test
    @DisplayName("a non-CLOSED conversation never disappears, however long its (empty) history has aged")
    void aNonClosedConversationNeverDisappears() {
        UUID conversationId = insertConversation("HANDED_TO_OPERATOR", 1);

        clock.advance(Duration.ofDays(400));

        var result = sweeper.runOnce();

        assertThat(result.deletedConversations()).isZero();
        assertThat(conversationExists(conversationId)).isTrue();
    }

    @Test
    @DisplayName("the batch limit holds: one pass deletes at most batchSize messages")
    void theBatchLimitHolds() {
        UUID conversationId = insertConversation("IDLE", 1);
        UUID first = messages.record(tenantId, conversationId, Direction.INBOUND, null, "one")
                .id();
        UUID second = messages.record(tenantId, conversationId, Direction.INBOUND, null, "two")
                .id();
        UUID third = messages.record(tenantId, conversationId, Direction.INBOUND, null, "three")
                .id();
        clock.advance(Duration.ofDays(40));

        ConversationRetentionService retention = new ConversationRetentionService(messages, conversations, runs);
        ConversationRetentionSweeper limitedSweeper = new ConversationRetentionSweeper(retention, clock, 2);

        var firstPass = limitedSweeper.runOnce();
        assertThat(firstPass.deletedMessages())
                .as("three are due, the batch is limited to two")
                .isEqualTo(2);
        assertThat(countRemainingMessages(conversationId))
                .as("no unbounded DELETE: exactly one is left after a batch of two")
                .isEqualTo(1);

        var secondPass = limitedSweeper.runOnce();
        assertThat(secondPass.deletedMessages()).isEqualTo(1);
        assertThat(countRemainingMessages(conversationId)).isZero();
        // Order-independent: all three named ids existed and all three are
        // gone by the second pass, regardless of which two the first claimed.
        assertThat(messageExists(first) || messageExists(second) || messageExists(third))
                .isFalse();
    }

    // ------------------------------------------------------------- fixtures

    private UUID insertConversation(String state, int retentionMonths) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO conversations.conversations (
                    id, tenant_id, brand_id, installation_id, channel, channel_chat_id,
                    state, retention_months, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :installationId, 'TELEGRAM', :chatId,
                    :state, :retentionMonths, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("installationId", installationId)
                .param("chatId", Math.abs(id.getMostSignificantBits() % 1_000_000_000L))
                .param("state", state)
                .param("retentionMonths", retentionMonths)
                .param("now", utc(clock.instant()))
                .update();
        return id;
    }

    private UUID insertFlowDocument() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO conversations.flow_documents (
                    id, tenant_id, brand_id, flow_key, version, document_yaml, is_active, authored_by, created_at)
                VALUES (:id, :tenantId, :brandId, 'retention-fixture', 1, 'states: {}', false, 'test-fixture', :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("now", utc(clock.instant()))
                .update();
        return id;
    }

    /** A terminal run, the only kind a CLOSED conversation can carry — see {@code ConversationInboxService#close}. */
    private UUID insertAbandonedFlowRun(UUID conversationId, UUID flowDocumentId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO conversations.flow_runs (
                    id, tenant_id, conversation_id, flow_document_id, flow_version, current_state_id,
                    status, created_at, updated_at)
                VALUES (:id, :tenantId, :conversationId, :flowDocumentId, 1, 'start',
                    'ABANDONED', :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("flowDocumentId", flowDocumentId)
                .param("now", utc(clock.instant()))
                .update();
        return id;
    }

    private boolean messageExists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM conversations.conversation_messages WHERE id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private int countRemainingMessages(UUID conversationId) {
        return jdbc.sql("""
                SELECT count(*) FROM conversations.conversation_messages
                 WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query(Integer.class)
                .single();
    }

    private boolean conversationExists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM conversations.conversations WHERE id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private boolean flowRunExists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM conversations.flow_runs WHERE id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private void seedTenantBrandInstallation() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'retention-sweep-pilot', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'retention-sweep-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('retention-sweep-env', 'NOTIFICATION', 'TELEGRAM_BOT_API', 'http://127.0.0.1:1', false, '127.0.0.1')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'retention-sweep-env',
                        'Pilot bot', 'ACTIVE', 'horecaos:local:provider_notification:platform:telegram-bot',
                        'horecaos:local:provider_notification:platform:telegram-bot')
                """).param("id", installationId).param("tenantId", tenantId).update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- fakes

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
