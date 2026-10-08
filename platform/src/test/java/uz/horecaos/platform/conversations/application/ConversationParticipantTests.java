package uz.horecaos.platform.conversations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
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
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.conversations.api.ChannelKind;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.api.ConversationOutboundGateway;
import uz.horecaos.platform.conversations.api.ConversationParticipant;
import uz.horecaos.platform.conversations.api.OutboundMessage;
import uz.horecaos.platform.conversations.domain.ConversationState;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0069's seam in ADR 0059's engine: a participant is offered the text no
 * flow consumed, in a conversation no person holds, and handing off is a change
 * of author inside one conversation -- never a change of system.
 *
 * <p>The participant here is a scripted stand-in; what is under test is the
 * engine's side of the contract. The assistant's own behaviour is proven in
 * {@code AssistantTurnServiceTests}, and the two together through the real
 * Telegram entry point in {@code AssistantConversationIntegrationTest}.
 */
class ConversationParticipantTests {

    private static final String WELCOME_FLOW = """
            flowKey: welcome-series
            name: Welcome series
            startState: ask
            states:
              ask:
                type: input-to-field
                prompt: "What is your name?"
                field: name
                next: thanks
              thanks:
                type: message
                text: "Thanks!"
            """;

    private static final String BUTTONS_FLOW = """
            flowKey: welcome-series
            name: Welcome series
            startState: greeting
            states:
              greeting:
                type: buttons
                text: "Welcome!"
                buttons:
                  - label: "Order"
                    kind: callback
                    key: order
                    next: ordered
              ordered:
                type: message
                text: "Great!"
            """;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private ConversationRepository conversations;
    private FlowRunRepository runs;
    private ConversationMessageStore messages;
    private FlowDocumentService flowDocuments;
    private Scripted participant;
    private List<OutboundMessage> sent;
    private ConversationEngine engine;
    private ConversationInboxService inbox;
    private AuditRecorder audit;
    private FieldProtection protection;
    private UUID tenant;
    private UUID brand;
    private ConversationChannelRef channel;

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
        jdbc.sql("TRUNCATE TABLE conversations.flow_runs, conversations.conversation_messages, "
                        + "conversations.conversations, conversations.flow_documents CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        objectMapper = JsonMapper.builder().build();
        Clock clock = Clock.systemUTC();
        audit = new JdbcAuditRecorder(jdbc, objectMapper);
        protection = TestProtection.envelope();
        conversations = new ConversationRepository(jdbc, clock);
        runs = new FlowRunRepository(jdbc, clock, protection, objectMapper);
        messages = new ConversationMessageStore(jdbc, clock, protection);
        flowDocuments = new FlowDocumentService(new FlowDocumentRepository(jdbc, clock), audit, clock);

        tenant = UUID.randomUUID();
        brand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", "t-" + tenant).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brand)
                .param("tenantId", tenant)
                .param("slug", "b-" + brand)
                .update();

        UUID installation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'http://127.0.0.1:1', false, '127.0.0.1')
                """).param("code", "participant-test-" + installation).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, brand_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, :brandId, 'NOTIFICATION', 'TELEGRAM_BOT_API', :env,
                        'Test bot', 'ACTIVE', 'horecaos:local:provider_notification:platform:bot', 'horecaos:local:provider_notification:platform:bot')
                """)
                .param("id", installation)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("env", "participant-test-" + installation)
                .update();

        participant = new Scripted();
        sent = new CopyOnWriteArrayList<>();
        ConversationOutboundGateway outbound = (ref, message) -> {
            sent.add(message);
            return true;
        };
        engine = new ConversationEngine(
                conversations,
                runs,
                messages,
                flowDocuments,
                outbound,
                clock,
                () -> List.of(participant),
                "https://s.example");
        inbox = new ConversationInboxService(
                conversations,
                runs,
                messages,
                flowDocuments,
                outbound,
                engine,
                audit,
                new PermissiveEntitlements(),
                clock);
        channel = new ConversationChannelRef(tenant, brand, installation, ChannelKind.TELEGRAM, 7_001L, null);
    }

    private List<ConversationMessageStore.Row> history(UUID conversationId) {
        return messages.history(tenant, conversationId);
    }

    private ConversationRepository.Row conversation() {
        return conversations.find(tenant, brand, ChannelKind.TELEGRAM, 7_001L).orElseThrow();
    }

    // ----------------------------------------------------------------- answering

    @Test
    @DisplayName(
            "text no flow wants reaches a willing participant even in a chat that never said /start, and its answer is recorded as the participant's own")
    void aParticipantAnswersTheLongTail() {
        UUID turn = UUID.randomUUID();
        participant.script(offered -> new ConversationParticipant.Replied("Plov is 45 000 so'm", turn));

        engine.handleText(channel, "How much is plov?");

        assertThat(sent).extracting(OutboundMessage::text).containsExactly("Plov is 45 000 so'm");
        List<ConversationMessageStore.Row> thread = history(conversation().id());
        assertThat(thread).extracting(row -> row.direction().name()).containsExactly("INBOUND", "ASSISTANT");
        assertThat(thread.get(0).body()).isEqualTo("How much is plov?");
        assertThat(thread.get(1).body()).isEqualTo("Plov is 45 000 so'm");
        assertThat(thread.get(1).assistantTurnId())
                .as("why did it say that has an answer")
                .isEqualTo(turn);
        assertThat(thread.get(0).assistantTurnId()).isNull();
        assertThat(conversation().state())
                .as("an answer does not hand anything over")
                .isEqualTo(ConversationState.IDLE);
    }

    @Test
    @DisplayName("with no participant willing and no conversation, free text is still the silent no-op it always was")
    void withNobodyWillingNothingChanges() {
        participant.willing = false;

        engine.handleText(channel, "How much is plov?");

        assertThat(sent).isEmpty();
        assertThat(conversations.find(tenant, brand, ChannelKind.TELEGRAM, 7_001L))
                .isEmpty();
        assertThat(participant.offers).isEmpty();
        assertThat(engine.acceptsFreeText(tenant, brand)).isFalse();
    }

    @Test
    @DisplayName(
            "a willing participant makes free text worth routing even for a brand with no flow; a flow alone still does")
    void acceptsFreeText() {
        assertThat(engine.hasActiveFlow(tenant, brand)).isFalse();
        assertThat(engine.acceptsFreeText(tenant, brand)).isTrue();

        participant.willing = false;
        assertThat(engine.acceptsFreeText(tenant, brand)).isFalse();
        flowDocuments.author(tenant, brand, "welcome-series", WELCOME_FLOW, "Welcome", true, "author", "publish");
        assertThat(engine.acceptsFreeText(tenant, brand)).isTrue();
    }

    @Test
    @DisplayName("a deterministic flow wins: text a waiting flow run consumes is never offered to the participant")
    void aFlowWins() {
        flowDocuments.author(tenant, brand, "welcome-series", WELCOME_FLOW, "Welcome", true, "author", "publish");
        engine.handleStart(channel);
        sent.clear();

        engine.handleText(channel, "Aziz");

        assertThat(participant.offers).isEmpty();
        assertThat(sent).extracting(OutboundMessage::text).containsExactly("Thanks!");

        // Once the run is over, the same kind of text is the long tail and is offered.
        participant.script(offered -> new ConversationParticipant.Replied("Hello!", UUID.randomUUID()));
        engine.handleText(channel, "And what about plov?");
        assertThat(participant.offers).hasSize(1);
    }

    @Test
    @DisplayName(
            "the participant is given the thread as it stood, with who said each message, and not the message it is being asked about")
    void contextIsTheThreadBeforeThisMessage() {
        participant.script(offered -> new ConversationParticipant.Replied("First answer", UUID.randomUUID()));
        engine.handleText(channel, "first question");

        participant.script(offered -> new ConversationParticipant.Replied("Second answer", UUID.randomUUID()));
        engine.handleText(channel, "second question");

        ConversationParticipant.Turn second = participant.offers.get(1);
        assertThat(second.customerText()).isEqualTo("second question");
        assertThat(second.history())
                .extracting(entry -> entry.author() + ":" + entry.text())
                .containsExactly("CUSTOMER:first question", "ASSISTANT:First answer");
        assertThat(second.channel()).isEqualTo(channel);
    }

    // ------------------------------------------------------------------ handoff

    @Test
    @DisplayName(
            "a handoff is a change of author: the participant's last words, then the operator's own, in one thread with its history intact")
    void aHandoffChangesTheAuthor() {
        UUID turn = UUID.randomUUID();
        participant.script(offered -> new ConversationParticipant.Replied("Plov is 45 000 so'm", UUID.randomUUID()));
        engine.handleText(channel, "How much is plov?");
        participant.script(offered -> new ConversationParticipant.HandedOff("A person will reply here.", turn));

        engine.handleText(channel, "I want to talk about my order");

        assertThat(sent)
                .extracting(OutboundMessage::text)
                .containsExactly("Plov is 45 000 so'm", "A person will reply here.");
        assertThat(conversation().state()).isEqualTo(ConversationState.HANDED_TO_OPERATOR);

        messages.recordOperatorReply(tenant, conversation().id(), "operator-7", "Hello, this is Dilnoza.");
        List<ConversationMessageStore.Row> thread = history(conversation().id());
        assertThat(thread)
                .extracting(row -> row.direction().name())
                .containsExactly("INBOUND", "ASSISTANT", "INBOUND", "ASSISTANT", "OPERATOR");
        assertThat(thread.get(3).assistantTurnId()).isEqualTo(turn);
        assertThat(thread.get(3).actorPrincipalId())
                .as("the assistant has no staff principal")
                .isNull();
        assertThat(thread.get(4).actorPrincipalId()).isEqualTo("operator-7");
        assertThat(thread.get(4).assistantTurnId()).isNull();
    }

    @Test
    @DisplayName("a handoff ends the flow run the customer was in, as a takeover would")
    void aHandoffEndsTheActiveRun() {
        flowDocuments.author(tenant, brand, "welcome-series", BUTTONS_FLOW, "Welcome", true, "author", "publish");
        engine.handleStart(channel);
        assertThat(runs.findActive(tenant, conversation().id())).isPresent();
        participant.script(
                offered -> new ConversationParticipant.HandedOff("A person will reply here.", UUID.randomUUID()));

        // The run waits on a button tap; typed text is the long tail, and the participant hands off.
        engine.handleText(channel, "never mind the buttons, my order is wrong");

        assertThat(participant.offers).hasSize(1);
        assertThat(runs.findActive(tenant, conversation().id())).isEmpty();
        assertThat(conversation().state()).isEqualTo(ConversationState.HANDED_TO_OPERATOR);
    }

    @Test
    @DisplayName(
            "once a person holds the conversation the participant is never asked again: the text is recorded for the operator")
    void nobodyAnswersOverAnOperator() {
        participant.script(offered -> new ConversationParticipant.HandedOff("A person will reply.", UUID.randomUUID()));
        engine.handleText(channel, "complaint");
        participant.script(offered -> new ConversationParticipant.Replied("should never be said", UUID.randomUUID()));
        sent.clear();

        engine.handleText(channel, "hello? anyone?");

        assertThat(participant.offers).hasSize(1);
        assertThat(sent).isEmpty();
        assertThat(history(conversation().id()))
                .extracting(row -> row.direction().name() + ":" + row.body())
                .containsExactly("INBOUND:complaint", "ASSISTANT:A person will reply.", "INBOUND:hello? anyone?");
    }

    @Test
    @DisplayName("text written into a closed conversation reopens it for staff and is not offered to the participant")
    void closedConversationsReopenForStaff() {
        participant.script(offered -> new ConversationParticipant.Replied("ok", UUID.randomUUID()));
        engine.handleText(channel, "hello");
        conversations.updateState(tenant, conversation().id(), ConversationState.CLOSED);
        participant.offers.clear();

        engine.handleText(channel, "one more thing");

        assertThat(participant.offers).isEmpty();
        assertThat(conversation().state()).isEqualTo(ConversationState.HANDED_TO_OPERATOR);
    }

    // ------------------------------------------------------------------ the inbox

    @Test
    @DisplayName(
            "an operator can take over a conversation the assistant has been answering: it is IDLE with no flow run, and taking over is how a person becomes its author")
    void anOperatorTakesOverFromTheAssistant() {
        participant.script(offered -> new ConversationParticipant.Replied("Plov is 45 000 so'm", UUID.randomUUID()));
        engine.handleText(channel, "How much is plov?");
        ConversationRepository.Row before = conversation();
        assertThat(before.state()).isEqualTo(ConversationState.IDLE);

        ConversationView taken =
                inbox.takeover(tenant, brand, before.id(), before.version(), "operator-7", "The customer asked for me");

        assertThat(taken.state()).isEqualTo("HANDED_TO_OPERATOR");
        assertThat(taken.assignedTo()).isEqualTo("operator-7");
        assertThat(jdbc.sql("""
                        SELECT reason FROM audit.audit_events
                        WHERE action_code = 'conversation.takeover' AND correlation_id = :conversationId
                        """)
                        .param("conversationId", before.id().toString())
                        .query(String.class)
                        .list())
                .as("one audited takeover, with the reason the operator gave")
                .containsExactly("The customer asked for me");

        // And from then on the participant is never asked, exactly as for a handoff.
        participant.offers.clear();
        engine.handleText(channel, "hello? are you there?");
        assertThat(participant.offers).isEmpty();
    }

    @Test
    @DisplayName("a takeover from the assistant with no reason typed says who it was taken from, in the audit trail")
    void aTakeoverFromTheAssistantSaysSoInTheAudit() {
        participant.script(offered -> new ConversationParticipant.Replied("Plov is 45 000 so'm", UUID.randomUUID()));
        engine.handleText(channel, "How much is plov?");
        ConversationRepository.Row before = conversation();

        inbox.takeover(tenant, brand, before.id(), before.version(), "operator-7", "  ");

        assertThat(jdbc.sql("""
                        SELECT reason FROM audit.audit_events
                        WHERE action_code = 'conversation.takeover' AND correlation_id = :conversationId
                        """)
                        .param("conversationId", before.id().toString())
                        .query(String.class)
                        .list())
                .containsExactly("Operator took the conversation over from the assistant");
    }

    @Test
    @DisplayName(
            "an idle conversation the assistant never spoke in is still not takeable: there is nothing to take over from")
    void anIdleConversationNobodyAnsweredIsNotTakeable() {
        participant.willing = false;
        // A conversation with a customer message and nobody answering it.
        ConversationRepository.Row created = conversations.getOrCreate(channel);
        messages.record(tenant, created.id(), ConversationMessageStore.Direction.INBOUND, null, "hello");

        assertThatThrownBy(() -> inbox.takeover(tenant, brand, created.id(), created.version(), "operator-7", null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    @DisplayName(
            "the inbox says who is answering: a conversation the assistant has spoken in is marked active until a person takes it, and involved for good")
    void theInboxMarksTheAssistant() {
        participant.script(offered -> new ConversationParticipant.Replied("Plov is 45 000 so'm", UUID.randomUUID()));
        engine.handleText(channel, "How much is plov?");
        UUID conversationId = conversation().id();

        assertThat(inbox.list(tenant, brand, 10)).singleElement().satisfies(row -> {
            assertThat(row.assistantActive()).isTrue();
            assertThat(row.assistantInvolved()).isTrue();
            assertThat(row.needsReply()).as("an answered question needs nobody").isFalse();
        });
        ConversationInboxService.ConversationHistory opened =
                inbox.history(tenant, brand, conversationId, "operator-7");
        assertThat(opened.conversation().assistantActive()).isTrue();
        assertThat(opened.messages())
                .extracting(ConversationMessageView::direction)
                .containsExactly("INBOUND", "ASSISTANT");
        assertThat(opened.messages().get(1).assistantTurnId()).isNotNull();

        ConversationView taken = inbox.takeover(
                tenant, brand, conversationId, opened.conversation().version(), "operator-7", null);

        assertThat(taken.assistantActive()).as("a person holds it now").isFalse();
        assertThat(taken.assistantInvolved()).isTrue();
        assertThat(inbox.list(tenant, brand, 10)).singleElement().satisfies(row -> {
            assertThat(row.assistantActive()).isFalse();
            assertThat(row.assistantInvolved()).isTrue();
            assertThat(row.needsReply()).isTrue();
        });
    }

    @Test
    @DisplayName(
            "a conversation the assistant handed over is involved but not active, and one it never touched is neither")
    void aHandedOverConversationIsInvolvedNotActive() {
        participant.script(
                offered -> new ConversationParticipant.HandedOff("A person will reply here.", UUID.randomUUID()));
        engine.handleText(channel, "this is a complaint");
        assertThat(inbox.list(tenant, brand, 10)).singleElement().satisfies(row -> {
            assertThat(row.state()).isEqualTo("HANDED_TO_OPERATOR");
            assertThat(row.assistantActive()).isFalse();
            assertThat(row.assistantInvolved()).isTrue();
        });

        participant.willing = false;
        ConversationChannelRef other =
                new ConversationChannelRef(tenant, brand, channel.installationId(), ChannelKind.TELEGRAM, 7_002L, null);
        messages.record(
                tenant,
                conversations.getOrCreate(other).id(),
                ConversationMessageStore.Direction.INBOUND,
                null,
                "hello");
        assertThat(inbox.list(tenant, brand, 10))
                .filteredOn(row -> !row.assistantInvolved())
                .singleElement()
                .satisfies(row -> assertThat(row.assistantActive()).isFalse());
    }

    // ---------------------------------------------------------------- robustness

    @Test
    @DisplayName(
            "a participant that fails never breaks the webhook: the message is recorded, nothing is sent, nothing is said about it")
    void aFailingParticipantIsContained() {
        participant.script(offered -> {
            throw new IllegalStateException("customer said: my secret question");
        });

        engine.handleText(channel, "my secret question");

        assertThat(sent).isEmpty();
        assertThat(history(conversation().id()))
                .extracting(row -> row.direction().name())
                .containsExactly("INBOUND");
    }

    @Test
    @DisplayName("a participant that declines leaves exactly what the engine would have left without it")
    void aDecliningParticipantChangesNothing() {
        participant.script(offered -> new ConversationParticipant.NotParticipating());

        engine.handleText(channel, "hello");

        assertThat(sent).isEmpty();
        assertThat(history(conversation().id()))
                .extracting(row -> row.direction().name())
                .containsExactly("INBOUND");
        assertThat(conversation().state()).isEqualTo(ConversationState.IDLE);
    }

    @Test
    @DisplayName("a shared location is offered with its coordinates, and the thread records only that one was shared")
    void sharedLocations() {
        participant.script(
                offered -> new ConversationParticipant.Replied("Chilonzor delivers there.", UUID.randomUUID()));

        engine.handleSharedLocation(channel, 41.2995, 69.2401);

        ConversationParticipant.Turn turn = participant.offers.getFirst();
        assertThat(turn.sharedLocation()).isEqualTo(new ConversationParticipant.SharedLocation(41.2995, 69.2401));
        assertThat(history(conversation().id()).get(0).body()).isEqualTo("[location shared]");
        assertThat(sent).hasSize(1);
    }

    // -------------------------------------------------------------------- doubles

    private static final class Scripted implements ConversationParticipant {
        volatile boolean willing = true;
        final List<Turn> offers = new CopyOnWriteArrayList<>();
        private volatile Function<Turn, Outcome> script = turn -> new NotParticipating();

        void script(Function<Turn, Outcome> script) {
            this.script = script;
        }

        @Override
        public boolean willingToParticipate(UUID tenantId, UUID brandId) {
            return willing;
        }

        @Override
        public Outcome offer(Turn turn) {
            offers.add(turn);
            return script.apply(turn);
        }
    }

    /** Takeover and history never consult the plan; a reply would, and this suite sends none. */
    private static final class PermissiveEntitlements implements EntitlementService {
        @Override
        public EntitlementSnapshot snapshot(UUID tenantId) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key) {
            return true;
        }

        @Override
        public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {}
    }
}
