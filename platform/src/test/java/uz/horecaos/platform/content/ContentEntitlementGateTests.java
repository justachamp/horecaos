package uz.horecaos.platform.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.application.EntitlementGateAdapter;
import uz.horecaos.platform.content.api.ContentEntitlements;
import uz.horecaos.platform.iam.api.EntitlementGate;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The two entitlement keys content gates on (ADR 0157): declared in the catalogue as features owned
 * by {@code content}, off by default, and answered by the real {@code EntitlementGate}.
 *
 * <p>The thing this suite exists to hold is the difference between {@code checkFeature} and
 * {@code checkGranted}. The catalogue's default enforcement mode is meter-only, under which
 * {@code featureEnabled} answers <em>true</em> for a feature nobody granted ("should the platform
 * currently act on this"); a switch that is off until the platform owner turns it on must ask whether
 * the plan <em>grants</em> it. Asked the first way, every tenant would have had a news section and
 * video on the day this shipped.
 */
class ContentEntitlementGateTests {

    private static TestDatabase.Handle db;
    private EntitlementGate gate;
    private UUID tenant;

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
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        tenant = UUID.randomUUID();
        new ContentFixture(jdbc).tenant(tenant, "gate-" + tenant.toString().substring(0, 8));
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        gate = new EntitlementGateAdapter(CommercialDefaults.wire(jdbc, clock).entitlements());
    }

    @Test
    @DisplayName(
            "both keys are declared in the catalogue as features owned by content, off by default, and the gate answers for them")
    void bothKeysAreDeclared() {
        for (String code : new String[] {ContentEntitlements.EDITORIAL_ENABLED, ContentEntitlements.VIDEO_ENABLED}) {
            var key = EntitlementKeys.find(code).orElseThrow();
            assertThat(key.valueType()).isEqualTo(Boolean.class);
            assertThat(key.safeDefault()).isEqualTo(Boolean.FALSE);
            assertThat(key.owningModule()).isEqualTo("content");
            assertThat(gate.checkFeature(tenant, code))
                    .as("checkFeature answers non-empty for %s", code)
                    .isPresent();
            assertThat(gate.checkGranted(tenant, code))
                    .as("checkGranted answers non-empty for %s", code)
                    .isPresent();
        }
        assertThat(ContentEntitlements.EDITORIAL_ENABLED).isEqualTo(EntitlementKeys.CONTENT_EDITORIAL_ENABLED.code());
        assertThat(ContentEntitlements.VIDEO_ENABLED).isEqualTo(EntitlementKeys.CONTENT_VIDEO_ENABLED.code());
    }

    @Test
    @DisplayName(
            "a tenant nobody granted the features to is not entitled to them, even though the meter-only default answers 'act on it' to checkFeature")
    void anUngrantedFeatureIsNotGranted() {
        for (String code : new String[] {ContentEntitlements.EDITORIAL_ENABLED, ContentEntitlements.VIDEO_ENABLED}) {
            assertThat(gate.checkGranted(tenant, code).orElseThrow().entitled())
                    .as("%s is off until the platform owner turns it on", code)
                    .isFalse();
        }
        // The trap, pinned: this is the answer a gate built on checkFeature would have acted on.
        assertThat(gate.checkFeature(tenant, ContentEntitlements.VIDEO_ENABLED)
                        .orElseThrow()
                        .entitled())
                .as("featureEnabled is true for an ungranted meter-only feature; that is why content does not ask it")
                .isTrue();
    }

    @Test
    @DisplayName(
            "a key the catalogue does not know, and one that is not a feature, answer empty, which content reads as not entitled")
    void unknownKeysAnswerEmpty() {
        assertThat(gate.checkGranted(tenant, "content.nonsense")).isEmpty();
        assertThat(gate.checkGranted(tenant, EntitlementKeys.BRANDS_MAX_COUNT.code()))
                .isEmpty();
    }
}
