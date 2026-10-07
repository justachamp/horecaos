package uz.horecaos.platform.integration.provider.routing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.api.RoadDistancePort;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.support.FakeOsrmEngine;
import uz.horecaos.platform.support.RoadDistancePortContract;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.cache.CacheRegistry;

/** The OSRM adapter held to the contract every {@link RoadDistancePort} owes the resolver. */
class OsrmAdapterPortContractTests extends RoadDistancePortContract {

    private static final UUID TENANT = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private FakeOsrmEngine engine;
    private OsrmRoadDistanceAdapter adapter;
    private UUID installation;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the port contract");
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
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'contract-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        engine = FakeOsrmEngine.start();
        jdbc.sql("UPDATE integration.provider_environments SET base_url = :url WHERE code = 'osrm_internal'")
                .param("url", engine.baseUrl())
                .update();

        JdbcRoutingInstallations installations = new JdbcRoutingInstallations(jdbc);
        installation = installations.insertPlatformRouting(TENANT).orElseThrow();
        adapter = new OsrmRoadDistanceAdapter(
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()),
                OsrmProperties.enabledWith("2026-10-01"),
                installations,
                new ConcurrentMapCacheManager(CacheRegistry.ROUTING_ROAD_ROUTES.cacheName()),
                new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-10-08T06:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Override
    protected RoadDistancePort port() {
        return adapter;
    }

    @Override
    protected UUID answeringInstallation() {
        return installation;
    }
}
