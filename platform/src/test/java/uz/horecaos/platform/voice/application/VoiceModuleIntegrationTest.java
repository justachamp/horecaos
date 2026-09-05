package uz.horecaos.platform.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.voice.api.VoiceCallEventRecorded;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallDirectionCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallEventTypeCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.InboundCallEvent;
import uz.horecaos.platform.voice.domain.OperatorPresenceState;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0064's application-layer proving suite, in the {@code
 * OperatorInboxIntegrationTest} genre: real Postgres, real {@code
 * FieldProtection}, real {@code AuditRecorder}, hand-wired application
 * services — no Spring context, no outbox, no Kafka. The webhook and AMI
 * adapters that sit in front of {@link VoiceEventIngestionService} are proven
 * separately, over the real transport each one actually uses.
 */
class VoiceModuleIntegrationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final String OPERATOR = "operator-subject-1";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcVoiceStore store;
    private OperatorPresenceService presence;
    private RecordingEventPublisher events;
    private VoiceEventIngestionService ingestion;
    private ScreenPopQueryService screenPop;
    private FakeCustomerPhoneLookup customers;
    private FakeOrderDirectory orders;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        insertTenantBrandLocation();

        ObjectMapper objectMapper = JsonMapper.builder().build();
        Clock clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC);
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, objectMapper);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                        clock),
                "local"));

        store = new JdbcVoiceStore(jdbc, objectMapper);
        presence = new OperatorPresenceService(store, audit, clock);
        events = new RecordingEventPublisher();
        customers = new FakeCustomerPhoneLookup();
        orders = new FakeOrderDirectory();
        ingestion = new VoiceEventIngestionService(store, customers, protection, events, clock);
        screenPop = new ScreenPopQueryService(store, customers, orders, protection, audit, clock);
    }

    // ---------------------------------------------------------------- presence

    @Test
    void pausingWithoutAReasonIsRefused() {
        assertThatThrownBy(() -> presence.setPresence(
                        TENANT,
                        BRAND,
                        LOCATION,
                        OPERATOR,
                        OperatorPresenceState.PAUSED,
                        null,
                        userActor(),
                        "voice.presence.manage",
                        "corr-1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void settingPresenceIsReadableAndAuditedWithTheReason() {
        presence.setPresence(
                TENANT,
                BRAND,
                LOCATION,
                OPERATOR,
                OperatorPresenceState.PAUSED,
                "Lunch break",
                userActor(),
                "voice.presence.manage",
                "corr-2");

        Optional<JdbcVoiceStore.PresenceRow> mine = presence.mine(TENANT, LOCATION, OPERATOR);
        assertThat(mine).isPresent();
        assertThat(mine.get().state()).isEqualTo("PAUSED");
        assertThat(mine.get().reason()).isEqualTo("Lunch break");

        Long auditCount = jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                WHERE action_code = 'voice.presence.changed' AND correlation_id = 'corr-2'
                """).query(Long.class).single();
        assertThat(auditCount).isEqualTo(1L);
    }

    @Test
    void reSettingPresenceUpdatesTheSameRowRatherThanAddingASecondOne() {
        presence.setPresence(
                TENANT, BRAND, LOCATION, OPERATOR, OperatorPresenceState.ONLINE, null, userActor(), "cap", "c1");
        presence.setPresence(
                TENANT, BRAND, LOCATION, OPERATOR, OperatorPresenceState.WRAP_UP, null, userActor(), "cap", "c2");

        List<JdbcVoiceStore.PresenceRow> roster = presence.roster(TENANT, LOCATION);
        assertThat(roster).hasSize(1);
        assertThat(roster.getFirst().state()).isEqualTo("WRAP_UP");
    }

    // --------------------------------------------------------------- ingestion

    @Test
    void anOfferedCallResolvesAKnownCustomerAndOpensAScreenPop() {
        UUID accountId = UUID.randomUUID();
        customers.registerAccount("+998901234567", accountId, "Alisher");
        orders.registerRecentOrder(
                accountId,
                new OrderDirectory.RecentOrder(
                        UUID.randomUUID(),
                        "A-1001",
                        LOCATION,
                        "COMPLETED",
                        "UZS",
                        45_000,
                        Instant.parse("2026-09-01T12:00:00Z")));

        ingestion.ingest(offered("call-1", "+998901234567"));

        Optional<ScreenPopQueryService.Card> card = screenPop.current(TENANT, BRAND, LOCATION);
        assertThat(card).isPresent();
        assertThat(card.get().unknownCaller()).isFalse();
        assertThat(card.get().customerAccountId()).isEqualTo(accountId);
        assertThat(card.get().customerDisplayName()).isEqualTo("Alisher");
        assertThat(card.get().maskedCallerNumber()).endsWith("4567").doesNotContain("901234");
        assertThat(card.get().recentOrders()).hasSize(1);
        assertThat(card.get().recentOrders().getFirst().publicOrderNumber()).isEqualTo("A-1001");
    }

    @Test
    void anOfferedCallFromAnUnknownNumberOpensABlankCard() {
        ingestion.ingest(offered("call-2", "+998907654321"));

        Optional<ScreenPopQueryService.Card> card = screenPop.current(TENANT, BRAND, LOCATION);
        assertThat(card).isPresent();
        assertThat(card.get().unknownCaller()).isTrue();
        assertThat(card.get().customerAccountId()).isNull();
        assertThat(card.get().recentOrders()).isEmpty();
    }

    @Test
    void endedComputesDurationFromTheOfferedTimestamp() {
        Instant offeredAt = Instant.parse("2026-09-05T10:00:00Z");
        Instant endedAt = offeredAt.plusSeconds(130);

        ingestion.ingest(offeredAt("call-3", "+998901111111", offeredAt));
        ingestion.ingest(new InboundCallEvent(
                TENANT,
                INSTALLATION,
                null,
                BRAND,
                LOCATION,
                "call-3",
                CallEventTypeCode.ENDED,
                CallDirectionCode.INBOUND,
                null,
                null,
                null,
                null,
                endedAt));

        JdbcVoiceStore.CallLogRow ended = store.recentCalls(TENANT, LOCATION, 10).stream()
                .filter(row -> row.eventType().equals("ENDED"))
                .findFirst()
                .orElseThrow();
        assertThat(ended.durationSeconds()).isEqualTo(130);
    }

    @Test
    void answeringClearsTheScreenPopForEveryoneElsePolling() {
        ingestion.ingest(offered("call-4", "+998900000000"));
        assertThat(screenPop.current(TENANT, BRAND, LOCATION)).isPresent();

        ingestion.ingest(new InboundCallEvent(
                TENANT,
                INSTALLATION,
                null,
                BRAND,
                LOCATION,
                "call-4",
                CallEventTypeCode.ANSWERED,
                CallDirectionCode.INBOUND,
                null,
                null,
                null,
                null,
                Instant.parse("2026-09-05T10:00:05Z")));

        assertThat(screenPop.current(TENANT, BRAND, LOCATION)).isEmpty();
    }

    @Test
    void acknowledgingClaimsTheCardAndARepeatAcknowledgementIsRefused() {
        ingestion.ingest(offered("call-5", "+998900000001"));
        ScreenPopQueryService.Card card =
                screenPop.current(TENANT, BRAND, LOCATION).orElseThrow();

        screenPop.acknowledge(TENANT, card.callEventId(), OPERATOR);

        assertThatThrownBy(() -> screenPop.acknowledge(TENANT, card.callEventId(), "someone-else"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aMissedCallSnapshotsTheOnlineRosterAtThatMoment() {
        presence.setPresence(
                TENANT, BRAND, LOCATION, OPERATOR, OperatorPresenceState.ONLINE, null, userActor(), "cap", "c1");

        ingestion.ingest(offered("call-6", "+998900000002"));
        ingestion.ingest(new InboundCallEvent(
                TENANT,
                INSTALLATION,
                null,
                BRAND,
                LOCATION,
                "call-6",
                CallEventTypeCode.MISSED,
                CallDirectionCode.INBOUND,
                null,
                null,
                null,
                null,
                Instant.parse("2026-09-05T10:00:20Z")));

        String roster =
                jdbc.sql("""
                SELECT online_operator_roster::text FROM voice.call_events
                WHERE tenant_id = :tenantId AND event_type = 'MISSED'
                """).param("tenantId", TENANT).query(String.class).single();
        assertThat(roster).contains(OPERATOR).contains("ONLINE");
    }

    @Test
    void anAcknowledgedOperatorIsAttributedToTheEndedCallWhenTheProviderDoesNotReportOne() {
        ingestion.ingest(offered("call-7", "+998900000003"));
        ScreenPopQueryService.Card card =
                screenPop.current(TENANT, BRAND, LOCATION).orElseThrow();
        screenPop.acknowledge(TENANT, card.callEventId(), OPERATOR);

        ingestion.ingest(new InboundCallEvent(
                TENANT,
                INSTALLATION,
                null,
                BRAND,
                LOCATION,
                "call-7",
                CallEventTypeCode.ENDED,
                CallDirectionCode.INBOUND,
                null,
                null,
                null, // the provider itself reports no operator
                null,
                Instant.parse("2026-09-05T10:01:00Z")));

        JdbcVoiceStore.CallLogRow ended = store.recentCalls(TENANT, LOCATION, 10).stream()
                .filter(row -> row.eventType().equals("ENDED"))
                .findFirst()
                .orElseThrow();
        assertThat(ended.operatorPrincipalId()).isEqualTo(OPERATOR);
    }

    @Test
    void anUnknownCallersNumberCanBeRevealedForCreateCustomerPrefill() {
        ingestion.ingest(offered("call-9", "+998900000005"));
        ScreenPopQueryService.Card card =
                screenPop.current(TENANT, BRAND, LOCATION).orElseThrow();

        String revealed =
                screenPop.revealUnknownCallerNumber(TENANT, BRAND, LOCATION, card.callEventId(), userActor(), "cap");

        assertThat(revealed).isEqualTo("+998900000005");
        Long auditCount = jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                WHERE action_code = 'voice.screen_pop.caller_number_revealed'
                """).query(Long.class).single();
        assertThat(auditCount).isEqualTo(1L);
    }

    @Test
    void aKnownCallersNumberCannotBeRevealedThroughScreenPop() {
        UUID accountId = UUID.randomUUID();
        customers.registerAccount("+998900000006", accountId, "Dilnoza");
        ingestion.ingest(offered("call-10", "+998900000006"));
        ScreenPopQueryService.Card card =
                screenPop.current(TENANT, BRAND, LOCATION).orElseThrow();

        assertThatThrownBy(() -> screenPop.revealUnknownCallerNumber(
                        TENANT, BRAND, LOCATION, card.callEventId(), userActor(), "cap"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void ingestingPublishesAVoiceCallEventRecordedWithNoPlaintextNumber() {
        ingestion.ingest(offered("call-8", "+998900000004"));

        assertThat(events.published).hasSize(1);
        VoiceCallEventRecorded event = (VoiceCallEventRecorded) events.published.getFirst();
        assertThat(event.callEventType()).isEqualTo("OFFERED");
        assertThat(event.payload().toString()).doesNotContain("998900000004");
    }

    // ------------------------------------------------------------------ fixtures

    private InboundCallEvent offered(String providerCallId, String callerNumber) {
        return offeredAt(providerCallId, callerNumber, Instant.parse("2026-09-05T10:00:00Z"));
    }

    private InboundCallEvent offeredAt(String providerCallId, String callerNumber, Instant occurredAt) {
        return new InboundCallEvent(
                TENANT,
                INSTALLATION,
                null,
                BRAND,
                LOCATION,
                providerCallId,
                CallEventTypeCode.OFFERED,
                CallDirectionCode.INBOUND,
                "+998712001234",
                callerNumber,
                null,
                null,
                occurredAt);
    }

    private static ActorRef userActor() {
        return ActorRef.user(OPERATOR, null);
    }

    private void truncate() {
        // voice.screen_pop_state FK-references voice.call_events, so a plain
        // TRUNCATE of call_events alone is refused even after screen_pop_state
        // was truncated in an earlier statement — CASCADE (or one combined
        // statement) is required, not truncation order.
        jdbc.sql("TRUNCATE TABLE voice.call_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE voice.operator_presence").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    private void insertTenantBrandLocation() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'voice-module-test', 'Voice Test', 'Voice Test', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'main', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private static final class RecordingEventPublisher implements ApplicationEventPublisher {
        private final List<Object> published = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            published.add(event);
        }
    }

    private static final class FakeCustomerPhoneLookup implements CustomerPhoneLookup {
        private final Map<String, UUID> accountsByPhone = new LinkedHashMap<>();
        private final Map<UUID, String> namesByAccount = new LinkedHashMap<>();

        void registerAccount(String phone, UUID accountId, String displayName) {
            accountsByPhone.put(phone, accountId);
            namesByAccount.put(accountId, displayName);
        }

        @Override
        public List<CustomerAccountRef> findByPhone(UUID tenantId, String rawPhoneNumber) {
            UUID accountId = accountsByPhone.get(rawPhoneNumber);
            return accountId == null ? List.of() : List.of(new CustomerAccountRef(accountId, tenantId));
        }

        @Override
        public Optional<CardProfile> cardProfile(UUID tenantId, UUID accountId) {
            String name = namesByAccount.get(accountId);
            return name == null ? Optional.empty() : Optional.of(new CardProfile(accountId, name));
        }
    }

    private static final class FakeOrderDirectory implements OrderDirectory {
        private final Map<UUID, List<RecentOrder>> byAccount = new LinkedHashMap<>();

        void registerRecentOrder(UUID accountId, RecentOrder order) {
            byAccount.computeIfAbsent(accountId, ignored -> new ArrayList<>()).add(order);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.empty();
        }

        @Override
        public List<RecentOrder> recentForCustomer(UUID tenantId, UUID brandId, UUID customerAccountId, int limit) {
            return byAccount.getOrDefault(customerAccountId, List.of());
        }
    }
}
