package uz.horecaos.platform.integration.provider.voice.asterisk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.integration.provider.voice.VoiceProcessedEventStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallEventTypeCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.InboundCallEvent;

/**
 * The Asterisk-class adapter over a real TCP connection to {@link
 * FakeAsteriskAmiServer}: proves the socket connect, the AMI login handshake,
 * the block-by-block event loop, and this build's dedup table, end to end —
 * the one part of ADR 0064 that is meaningless to test any other way, since
 * everything interesting here is on the wire.
 *
 * <p>Never run against a real Asterisk instance — none is available (ADR
 * 0064's Implementation status says so). This proves the adapter's own
 * mechanics against the wire shape it expects, not that a live PBX actually
 * speaks it identically.
 */
class AsteriskAmiSocketClientEndToEndTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final String USERNAME = "horecaos";
    private static final String SECRET = "ami-secret-value";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private @Nullable FakeAsteriskAmiServer server;
    private @Nullable Thread clientThread;

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
        jdbc.sql("TRUNCATE TABLE integration.voice_processed_events").update();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientThread != null) {
            clientThread.interrupt();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void connectsLogsInAndIngestsAPushedNewchannelEvent() throws Exception {
        server = FakeAsteriskAmiServer.start(USERNAME, SECRET);
        RecordingIngestion ingestion = new RecordingIngestion();
        clientThread = startClient(ingestion, server.port());

        server.awaitLogin(5);
        server.pushEvent(
                Map.of("Event", "Newchannel", "Uniqueid", "1.100", "CallerIDNum", "998901234567", "Exten", "1001"));

        waitUntil(Duration.ofSeconds(5), () -> ingestion.ingested.size() == 1);
        assertThat(ingestion.ingested).hasSize(1);
        assertThat(ingestion.ingested.getFirst().type()).isEqualTo(CallEventTypeCode.OFFERED);
        assertThat(ingestion.ingested.getFirst().providerCallId()).isEqualTo("1.100");
    }

    @Test
    void anEventDeliveredTwiceOnTheSameConnectionIsIngestedOnce() throws Exception {
        server = FakeAsteriskAmiServer.start(USERNAME, SECRET);
        RecordingIngestion ingestion = new RecordingIngestion();
        clientThread = startClient(ingestion, server.port());

        server.awaitLogin(5);
        Map<String, String> event = Map.of("Event", "Hangup", "Uniqueid", "1.200");
        server.pushEvent(event);
        server.pushEvent(event);

        // Wait for the first ingestion, then hold a further beat: if dedup
        // were broken this would flake toward 2, never stay at 1 by accident,
        // which is what makes the final assertion meaningful rather than a
        // race that happens to pass.
        waitUntil(Duration.ofSeconds(5), () -> !ingestion.ingested.isEmpty());
        Thread.sleep(500);
        assertThat(ingestion.ingested).hasSize(1);
    }

    @Test
    void aWrongSecretIsRefusedAndNothingIsIngested() throws Exception {
        server = FakeAsteriskAmiServer.start(USERNAME, "the-real-secret");
        RecordingIngestion ingestion = new RecordingIngestion();
        var installation = installation(server.port());
        var client = new AsteriskAmiSocketClient(
                installation,
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.provider_voice.tenant-x.ami-secret-1", "the-wrong-secret")::get,
                        Clock.systemUTC()),
                new VoiceProcessedEventStore(jdbc, Clock.systemUTC()),
                ingestion,
                objectMapper(),
                Clock.systemUTC());
        clientThread = new Thread(client, "test-asterisk-client");
        clientThread.setDaemon(true);
        clientThread.start();

        // The fake never counts down loggedIn on a rejected login; the client
        // thread should simply end on its own rather than looping forever.
        clientThread.join(Duration.ofSeconds(5).toMillis());
        assertThat(clientThread.isAlive()).isFalse();
        assertThat(ingestion.ingested).isEmpty();
    }

    private Thread startClient(RecordingIngestion ingestion, int port) {
        var installation = installation(port);
        var client = new AsteriskAmiSocketClient(
                installation,
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.provider_voice.tenant-x.ami-secret-1", SECRET)::get,
                        Clock.systemUTC()),
                new VoiceProcessedEventStore(jdbc, Clock.systemUTC()),
                ingestion,
                objectMapper(),
                Clock.systemUTC());
        Thread thread = new Thread(client, "test-asterisk-client");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static VoiceInstallation installation(int port) {
        return new VoiceInstallation(
                INSTALLATION,
                TENANT,
                BRAND,
                LOCATION,
                UUID.randomUUID(),
                "ASTERISK_AMI",
                "ACTIVE",
                "https://asterisk.local/ami",
                "horecaos:local:provider_voice:tenant-x:ami-secret-1",
                null,
                "{\"amiHost\":\"127.0.0.1\",\"amiPort\":" + port + ",\"amiUsername\":\"" + USERNAME + "\"}");
    }

    private static ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
    }

    private static void waitUntil(Duration timeout, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition did not become true within " + timeout);
            }
            Thread.sleep(50);
        }
    }

    private static final class RecordingIngestion implements VoiceEventInboundPort {
        private final List<InboundCallEvent> ingested = new CopyOnWriteArrayList<>();

        @Override
        public IngestOutcome ingest(InboundCallEvent event) {
            ingested.add(event);
            return new IngestOutcome(UUID.randomUUID());
        }
    }
}
