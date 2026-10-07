package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
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
import uz.horecaos.platform.integration.web.sms.SmsReceiptFixture;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.ReceiptEnablement;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0146 Decision 5: "no receipt" is a state, and only where a receipt can
 * arrive; the pull is narrow; and neither ever resends.
 *
 * <p>Against a real PostgreSQL and a controlled clock. A sweep asserted without
 * moving the clock is asserted against an instant, not a duration, so each test
 * says how old the attempt is and where "now" is.
 */
class SmsReceiptSweeperTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private static TestDatabase.Handle db;

    private SmsReceiptFixture fixture;
    private UUID genericBinding;
    private UUID vasBinding;
    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
    private SmsReceiptSweeper sweeper;

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
        DataSource dataSource = db.dataSource();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        fixture = new SmsReceiptFixture(jdbc);
        fixture.truncate();
        fixture.tenantWithBrand(TENANT, BRAND, "sweeper");
        fixture.environment("sweeper-generic", "GENERIC_SMS");
        fixture.environment("sweeper-vas", "SMSGW_VAS");
        genericBinding = fixture.installation(
                UUID.randomUUID(), TENANT, BRAND, "GENERIC_SMS", "sweeper-generic", "ACTIVE", null);
        vasBinding = fixture.installation(UUID.randomUUID(), TENANT, BRAND, "SMSGW_VAS", "sweeper-vas", "ACTIVE", null);

        // Receipts exist for the generic gateway and, as shipped, for nobody else.
        ReceiptEnablement enablement = new ReceiptEnablement() {
            @Override
            public boolean isEnabled(String providerType) {
                return "GENERIC_SMS".equals(providerType);
            }

            @Override
            public Set<String> enabledProviderTypes() {
                return Set.of("GENERIC_SMS");
            }
        };
        DeliveryReceiptService receipts = new DeliveryReceiptService(
                new JdbcDeliveryReceiptStore(jdbc),
                new JdbcNotificationStore(jdbc),
                (tenantId, brandId, accountId, channel, correlationId) -> false,
                clock);
        sweeper = new SmsReceiptSweeper(
                new JdbcDeliveryReceiptStore(jdbc),
                enablement,
                dispatch,
                receipts,
                meters,
                clock,
                Duration.ofHours(24),
                Duration.ofHours(1));
    }

    @Test
    @DisplayName("with receipts enabled, an accepted attempt nobody reported on becomes 'no receipt' after the window")
    void silenceBecomesAStateOnlyAfterTheWindow() {
        UUID old = attempt(genericBinding, "GENERIC_SMS", "g-old", NOW.minus(Duration.ofHours(25)));
        UUID recent = attempt(genericBinding, "GENERIC_SMS", "g-recent", NOW.minus(Duration.ofHours(23)));

        assertThat(sweeper.markNoReceipt()).isEqualTo(1);

        assertThat(fixture.receiptState(old)).isEqualTo("NO_RECEIPT");
        assertThat(fixture.receiptState(recent))
                .as("inside the window it is simply early")
                .isNull();
        assertThat(fixture.attemptStatus(old))
                .as("silence is not failure: the attempt is still the accepted one it was")
                .isEqualTo("ACCEPTED");
        assertThat(meters.get("horecaos.sms.no_receipt")
                        .tag("provider", "GENERIC_SMS")
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // Time passes and nothing else does: the window is a duration.
        clock.advance(Duration.ofHours(2));
        assertThat(sweeper.markNoReceipt()).isEqualTo(1);
        assertThat(fixture.receiptState(recent)).isEqualTo("NO_RECEIPT");
    }

    @Test
    @DisplayName("a provider type with no receipt source is never visited, however old the attempt")
    void aTypeWithoutReceiptsIsNeverMarked() {
        UUID ancient = attempt(vasBinding, "SMSGW_VAS", "v-ancient", NOW.minus(Duration.ofDays(400)));

        clock.advance(Duration.ofDays(365));
        assertThat(sweeper.markNoReceipt()).isZero();

        assertThat(fixture.receiptState(ancient))
                .as("with nothing listening, the absence of a receipt says nothing")
                .isNull();
        assertThat(fixture.attemptStatus(ancient)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("an attempt the provider did report on is not 'no receipt'")
    void aReportedAttemptIsNotMarked() {
        UUID reported = attempt(genericBinding, "GENERIC_SMS", "g-reported", NOW.minus(Duration.ofHours(30)));
        event(reported, "g-reported:DISPATCHED", "DISPATCHED");

        assertThat(sweeper.markNoReceipt()).isZero();

        assertThat(fixture.receiptState(reported)).isNull();
    }

    @Test
    @DisplayName("the pull asks only about attempts reported unknown, applies a definite answer, and sends nothing")
    void thePullChasesOnlyUnknownAttempts() {
        UUID unknown = attempt(genericBinding, "GENERIC_SMS", "g-unknown", NOW.minus(Duration.ofHours(3)));
        event(unknown, "g-unknown:UNKNOWN", "UNKNOWN");
        UUID quietlyAccepted = attempt(genericBinding, "GENERIC_SMS", "g-quiet", NOW.minus(Duration.ofHours(3)));
        UUID vasUnknown = attempt(vasBinding, "SMSGW_VAS", "v-unknown", NOW.minus(Duration.ofHours(3)));
        event(vasUnknown, "v-unknown:UNKNOWN", "UNKNOWN");
        when(dispatch.pullState(any()))
                .thenReturn(Optional.of(
                        DispatchOutcome.accepted("g-unknown", "DELIVERED").understood("DELIVERED", null, false)));

        assertThat(sweeper.pullUnknown()).isEqualTo(1);

        assertThat(fixture.attemptStatus(unknown)).isEqualTo("DELIVERED");
        assertThat(fixture.eventStatuses(unknown)).containsExactly("ACCEPTED", "UNKNOWN", "DELIVERED");
        assertThat(fixture.attemptStatus(quietlyAccepted))
                .as("every pull decrypts a number, so a message nobody called unresolved is not chased")
                .isEqualTo("ACCEPTED");
        assertThat(fixture.attemptStatus(vasUnknown)).isEqualTo("ACCEPTED");
        verify(dispatch, org.mockito.Mockito.times(1)).pullState(any());
    }

    @Test
    @DisplayName("a pull that learns nothing changes nothing")
    void anUncertainPullIsInert() {
        UUID unknown = attempt(genericBinding, "GENERIC_SMS", "g-unknown-2", NOW.minus(Duration.ofHours(3)));
        event(unknown, "g-unknown-2:UNKNOWN", "UNKNOWN");
        when(dispatch.pullState(any())).thenReturn(Optional.of(DispatchOutcome.uncertain("SMS_SEND_UNCONFIRMED", "x")));

        assertThat(sweeper.pullUnknown()).isZero();

        assertThat(fixture.attemptStatus(unknown)).isEqualTo("ACCEPTED");
        assertThat(fixture.eventStatuses(unknown)).containsExactly("ACCEPTED", "UNKNOWN");
    }

    @Test
    @DisplayName("an attempt too fresh to be worth asking about is left alone")
    void theSettleDelayIsRespected() {
        UUID unknown = attempt(genericBinding, "GENERIC_SMS", "g-fresh", NOW.minus(Duration.ofMinutes(10)));
        event(unknown, "g-fresh:UNKNOWN", "UNKNOWN");

        assertThat(sweeper.pullUnknown()).isZero();

        verify(dispatch, never()).pullState(any());
    }

    private UUID attempt(UUID binding, String providerType, String messageId, Instant requestedAt) {
        return fixture.acceptedAttempt(TENANT, BRAND, binding, providerType, messageId, null, requestedAt);
    }

    private void event(UUID attemptId, String providerEventId, String normalizedStatus) {
        JdbcClient.create(db.dataSource())
                .sql("""
                        INSERT INTO notifications.delivery_status_events (
                            id, tenant_id, attempt_id, provider_event_id, normalized_status, provider_status,
                            occurred_at, recorded_at)
                        VALUES (:id, :tenantId, :attemptId, :eventId, :status, :status, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("attemptId", attemptId)
                .param("eventId", providerEventId)
                .param("status", normalizedStatus)
                .update();
    }

    /** A clock a test can move, because a duration asserted without advancing time is an instant. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
