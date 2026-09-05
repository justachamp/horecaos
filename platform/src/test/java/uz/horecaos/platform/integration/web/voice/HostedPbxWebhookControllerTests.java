package uz.horecaos.platform.integration.web.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup;
import uz.horecaos.platform.integration.provider.voice.VoiceProcessedEventStore;
import uz.horecaos.platform.integration.provider.voice.hostedpbx.HostedPbxWebhookPayload;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort;

/**
 * The hosted-PBX adapter's inbound half, hand-wired against real Postgres —
 * the {@code TelegramInteractiveBotIntegrationTest} genre, proving the
 * controller's own mechanics (installation lookup, secret verification,
 * dedup) directly rather than through a full Spring HTTP round trip. {@link
 * VoiceModuleIntegrationTest} already proves what ingestion does with a
 * mapped event; this proves the webhook gets that far only when it should.
 */
class HostedPbxWebhookControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final String SECRET_VALUE = "a-hosted-pbx-webhook-secret";
    private static final String SECRET_REFERENCE = "horecaos:local:provider_voice:tenant-x:webhook-secret-1";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private HostedPbxWebhookController controller;
    private RecordingIngestion ingestion;

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
        insertFixture();

        Clock clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), java.time.ZoneOffset.UTC);
        // EnvironmentSecretResolver looks a reference up by a derived property
        // name (category.ownerScope.opaqueId), never by the reference string
        // itself — see its own propertyNameFor().
        var secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.provider_voice.tenant-x.webhook-secret-1", SECRET_VALUE)::get, clock);
        ingestion = new RecordingIngestion();
        controller = new HostedPbxWebhookController(
                new VoiceInstallationLookup(jdbc), new VoiceProcessedEventStore(jdbc, clock), secrets, ingestion);
    }

    @Test
    void aValidSecretTokenIngestsTheMappedEvent() {
        ResponseEntity<Void> response = controller.webhook(INSTALLATION, SECRET_VALUE, payload("evt-1", "OFFERED"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(ingestion.ingested).hasSize(1);
        assertThat(ingestion.ingested.getFirst().providerCallId()).isEqualTo("call-1");
    }

    @Test
    void aMissingOrWrongSecretTokenIsRefusedWithoutIngesting() {
        ResponseEntity<Void> wrong = controller.webhook(INSTALLATION, "not-the-secret", payload("evt-2", "OFFERED"));
        ResponseEntity<Void> missing = controller.webhook(INSTALLATION, null, payload("evt-3", "OFFERED"));

        assertThat(wrong.getStatusCode().value()).isEqualTo(403);
        assertThat(missing.getStatusCode().value()).isEqualTo(403);
        assertThat(ingestion.ingested).isEmpty();
    }

    @Test
    void aRetriedEventIdIsNotIngestedTwice() {
        controller.webhook(INSTALLATION, SECRET_VALUE, payload("evt-4", "OFFERED"));
        controller.webhook(INSTALLATION, SECRET_VALUE, payload("evt-4", "OFFERED"));

        assertThat(ingestion.ingested).hasSize(1);
    }

    @Test
    void anUnknownInstallationIsRefused() {
        ResponseEntity<Void> response =
                controller.webhook(UUID.randomUUID(), SECRET_VALUE, payload("evt-5", "OFFERED"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(ingestion.ingested).isEmpty();
    }

    @Test
    void aMalformedEventTypeReturns200WithoutIngestingRatherThanBeingRetriedForever() {
        ResponseEntity<Void> response = controller.webhook(INSTALLATION, SECRET_VALUE, payload("evt-6", "RINGING"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(ingestion.ingested).isEmpty();
    }

    private static HostedPbxWebhookPayload payload(String eventId, String eventType) {
        return new HostedPbxWebhookPayload(
                eventId,
                eventType,
                "call-1",
                "INBOUND",
                "+998712001234",
                "+998901234567",
                null,
                null,
                Instant.parse("2026-09-05T10:00:00Z"));
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE integration.voice_processed_events").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    private void insertFixture() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'hosted-pbx-webhook-test', 'Hosted PBX Test', 'Hosted PBX Test',
                        'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('hosted-pbx-test', 'VOICE', 'HOSTED_PBX', 'https://pbx.example.invalid', false, '')
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status,
                     webhook_secret_reference, non_sensitive_config)
                VALUES (:id, :tenantId, 'VOICE', 'HOSTED_PBX', 'hosted-pbx-test', 'Hosted PBX', 'ACTIVE',
                        :webhookSecretReference, '{}')
                """)
                .param("id", INSTALLATION)
                .param("tenantId", TENANT)
                .param("webhookSecretReference", SECRET_REFERENCE)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .update();
    }

    private static final class RecordingIngestion implements VoiceEventInboundPort {
        private final List<InboundCallEvent> ingested = new ArrayList<>();

        @Override
        public IngestOutcome ingest(InboundCallEvent event) {
            ingested.add(event);
            return new IngestOutcome(UUID.randomUUID());
        }
    }
}
