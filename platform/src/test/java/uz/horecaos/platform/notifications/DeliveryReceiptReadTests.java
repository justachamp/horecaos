package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.integration.web.sms.SmsReceiptFixture;
import uz.horecaos.platform.notifications.api.DeliveryReceiptPort.ReceiptDisposition;
import uz.horecaos.platform.notifications.application.DeliveryReceiptService;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0182: a read is carried through the evidence, so a channel that reports one lights up the
 * report without a code change. Nothing wired today can report a read (VAS and the Bot API have no
 * read receipt), so this drives the service the way a future provider's receipt would, and proves
 * the pipeline is not what would block it: a read follows a delivery, it does not move the time of
 * the delivery, and it is never accepted after a failure.
 */
class DeliveryReceiptReadTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private SmsReceiptFixture fixture;
    private JdbcDeliveryReceiptStore receipts;
    private DeliveryReceiptService service;
    private UUID binding;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
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
        jdbc = JdbcClient.create(db.dataSource());
        fixture = new SmsReceiptFixture(jdbc);
        fixture.truncate();
        fixture.tenantWithBrand(TENANT, BRAND, "read-receipts");
        fixture.environment("read-env", "SOME_PROVIDER");
        binding = fixture.installation(UUID.randomUUID(), TENANT, BRAND, "SOME_PROVIDER", "read-env", "ACTIVE", null);
        receipts = new JdbcDeliveryReceiptStore(jdbc);
        service = new DeliveryReceiptService(
                receipts,
                new JdbcNotificationStore(jdbc),
                (tenantId, brandId, accountId, channel, correlationId) -> false,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a read that follows a delivery is counted as a read, and the delivery keeps its time")
    void aReadFollowsADelivery() {
        UUID attempt = fixture.acceptedAttempt(TENANT, BRAND, binding, "SOME_PROVIDER", "m-1", null, NOW);
        assertThat(apply("m-1", "DELIVERED")).isEqualTo(ReceiptDisposition.APPLIED);
        Instant deliveredAt = java.util.Objects.requireNonNull(acknowledgedAt(attempt));

        assertThat(apply("m-1", "READ"))
                .as("a channel that reports both reports a read after a delivery, and the ladder must allow it")
                .isEqualTo(ReceiptDisposition.APPLIED);

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("DELIVERED");
        assertThat(fixture.eventStatuses(attempt)).containsExactlyInAnyOrder("ACCEPTED", "DELIVERED", "READ");
        assertThat(acknowledgedAt(attempt))
                .as("the read does not rewrite when it was delivered")
                .isEqualTo(deliveredAt);
        var evidence = java.util.Objects.requireNonNull(
                receipts.evidenceFor(TENANT, java.util.List.of(notificationOf(attempt)))
                        .get(notificationOf(attempt)));
        var latest = java.util.Objects.requireNonNull(evidence.attempt());
        assertThat(latest.read()).isTrue();
        assertThat(latest.status()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a read with no delivery before it is a delivery as well")
    void aReadAloneDelivers() {
        UUID attempt = fixture.acceptedAttempt(TENANT, BRAND, binding, "SOME_PROVIDER", "m-2", null, NOW);

        assertThat(apply("m-2", "READ")).isEqualTo(ReceiptDisposition.APPLIED);

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("DELIVERED");
        assertThat(acknowledgedAt(attempt)).isNotNull();
    }

    @Test
    @DisplayName("a delivery after a read, and a read after a failure, are regressions and change nothing")
    void theLadderStillOnlyClimbs() {
        UUID read = fixture.acceptedAttempt(TENANT, BRAND, binding, "SOME_PROVIDER", "m-3", null, NOW);
        apply("m-3", "READ");
        assertThat(apply("m-3", "DELIVERED")).isEqualTo(ReceiptDisposition.REGRESSED);
        assertThat(apply("m-3", "FAILED")).isEqualTo(ReceiptDisposition.REGRESSED);
        assertThat(fixture.attemptStatus(read)).isEqualTo("DELIVERED");

        UUID failed = fixture.acceptedAttempt(TENANT, BRAND, binding, "SOME_PROVIDER", "m-4", null, NOW);
        assertThat(apply("m-4", "FAILED")).isEqualTo(ReceiptDisposition.APPLIED);
        assertThat(apply("m-4", "READ")).as("a failed message was not read").isEqualTo(ReceiptDisposition.REGRESSED);
        assertThat(fixture.attemptStatus(failed)).isEqualTo("FAILED");
        assertThat(fixture.eventStatuses(failed)).containsExactlyInAnyOrder("ACCEPTED", "FAILED");
    }

    @Test
    @DisplayName("the same read twice is one read")
    void aDuplicateReadIsOneRead() {
        UUID attempt = fixture.acceptedAttempt(TENANT, BRAND, binding, "SOME_PROVIDER", "m-5", null, NOW);
        apply("m-5", "DELIVERED");
        apply("m-5", "READ");

        assertThat(apply("m-5", "READ")).isEqualTo(ReceiptDisposition.DUPLICATE);
        assertThat(fixture.eventStatuses(attempt)).containsExactlyInAnyOrder("ACCEPTED", "DELIVERED", "READ");
    }

    // ---------------------------------------------------------------- fixtures

    private ReceiptDisposition apply(String messageId, String status) {
        return service.applyPulled(TENANT, binding, messageId, status, status, false);
    }

    private UUID notificationOf(UUID attemptId) {
        return jdbc.sql("SELECT notification_id FROM notifications.delivery_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(UUID.class)
                .single();
    }

    private @Nullable Instant acknowledgedAt(UUID attemptId) {
        return jdbc.sql("SELECT acknowledged_at FROM notifications.delivery_attempts WHERE id = :id")
                .param("id", attemptId)
                .query((row, number) -> row.getObject("acknowledged_at", java.time.OffsetDateTime.class))
                .optional()
                .map(java.time.OffsetDateTime::toInstant)
                .orElse(null);
    }
}
