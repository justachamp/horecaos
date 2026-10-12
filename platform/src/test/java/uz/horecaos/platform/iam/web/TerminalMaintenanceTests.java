package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import uz.horecaos.platform.iam.api.terminals.TerminalServiceHours;
import uz.horecaos.platform.iam.application.terminals.TerminalMaintenance;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore;
import uz.horecaos.platform.support.RecordingOperationsAlertPort.Call;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * ADR 0155, Observability and Retention: the «terminal unseen» alert raised once per quiet episode and only during service, and the
 * purge that removes what is older than the audit window and nothing newer.
 *
 * <p>The job is built by hand with a stand-in for the branch's service schedule (the real one is tenancy's, proved by its
 * own test) and driven against the test's clock, so a minute here is a minute the clock was moved by.
 */
class TerminalMaintenanceTests extends TerminalsFixture {

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcTerminalStore store;

    @Autowired
    @SuppressWarnings("NullAway")
    private ApplicationEventPublisher publisher;

    @Autowired
    @SuppressWarnings("NullAway")
    private PlatformTransactionManager transactionManager;

    private boolean inService = true;

    private TerminalMaintenance job() {
        TerminalServiceHours hours = (tenant, brand, location, at) -> inService;
        ObjectProvider<TerminalServiceHours> provider =
                new StaticListableBeanFactory(Map.of("hours", hours)).getBeanProvider(TerminalServiceHours.class);
        return new TerminalMaintenance(store, provider, publisher, transactionManager, clock, Duration.ofMinutes(5));
    }

    private Tablet requiredTablet() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_REQUIRED");
        setPin(tablet, AZIZA, "482915");
        return tablet;
    }

    private java.util.List<Call> unseenAlerts() {
        return alerts.calls().stream()
                .filter(call -> call.eventClass().equals("TERMINAL_DEVICE_UNSEEN"))
                .toList();
    }

    @Test
    @DisplayName(
            "a tablet that must be unlocked and has gone quiet alerts once, names the device and not a person, and alerts again after it has returned and gone quiet")
    void aQuietTabletAlertsOncePerEpisode() throws Exception {
        requiredTablet();
        TerminalMaintenance job = job();

        advance(Duration.ofMinutes(4));
        job.watchUnseen();
        assertThat(unseenAlerts())
                .as("four minutes is inside the five the tenant tolerates")
                .isEmpty();

        advance(Duration.ofMinutes(2));
        job.watchUnseen();
        assertThat(unseenAlerts()).hasSize(1);
        Call alert = unseenAlerts().get(0);
        assertThat(alert.variables())
                .containsEntry("deviceLabel", "Pass tablet")
                .containsKey("lastSeenAt");
        assertThat(alert.variables().toString())
                .as("the alert goes to a chat: it names the device, never a person")
                .doesNotContain("Aziza")
                .doesNotContain("S-0");

        advance(Duration.ofMinutes(1));
        job.watchUnseen();
        assertThat(unseenAlerts())
                .as("one alert per quiet episode, not one a minute")
                .hasSize(1);

        // The tablet comes back, then goes quiet again: a new episode, a new alert.
        UUID deviceId = jdbc.sql("SELECT id FROM iam.device_principals WHERE display_name = 'Pass tablet'")
                .query(UUID.class)
                .single();
        clearCache(CacheRegistry.IAM_DEVICE_LAST_SEEN.cacheName());
        mvc.perform(get(TERMINAL_SESSION + "/roster").with(token(subjectOf(deviceId))))
                .andReturn();
        job.watchUnseen();
        assertThat(unseenAlerts()).hasSize(1);

        advance(Duration.ofMinutes(6));
        job.watchUnseen();
        assertThat(unseenAlerts()).hasSize(2);
        assertThat(unseenAlerts().get(1).idempotencyKeyBase())
                .as("the second episode has its own key, or the fan-out would swallow it as a replay")
                .isNotEqualTo(unseenAlerts().get(0).idempotencyKeyBase());
    }

    @Test
    @DisplayName("a quiet tablet alerts nobody while the branch is closed")
    void aQuietTabletOutsideServiceAlertsNobody() throws Exception {
        requiredTablet();
        inService = false;

        advance(Duration.ofMinutes(30));
        job().watchUnseen();

        assertThat(unseenAlerts()).isEmpty();
    }

    @Test
    @DisplayName(
            "a tablet where a person may unlock, one never heard from and one that was revoked are none of the monitor's business")
    void onlyATabletThatMustBeUnlockedIsWatched() throws Exception {
        Tablet optional = enrolTablet("Optional tablet");
        setMode(optional, "PERSON_OPTIONAL");
        setPin(optional, AZIZA, "482915");

        UUID neverSeen = insertTablet("Never seen", CENTRE, "PERSON_REQUIRED").deviceId();
        assertThat(neverSeen).isNotNull();

        Tablet revoked = enrolTablet("Revoked tablet");
        setMode(revoked, "PERSON_REQUIRED");
        setPin(revoked, BAKHROM, "735914");
        jdbc.sql("""
                UPDATE iam.device_principals
                   SET status = 'REVOKED', revoked_at = :now, revoked_by = 'test', revoked_reason = 'test'
                 WHERE id = :id
                """)
                .param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .param("id", revoked.deviceId())
                .update();

        advance(Duration.ofHours(1));
        job().watchUnseen();

        assertThat(unseenAlerts()).isEmpty();
    }

    @Test
    @DisplayName(
            "the purge removes an ended session and a spent code older than four hundred days and keeps an open session, a pending code and anything newer")
    void retentionRemovesOnlyWhatIsOlderThanTheAuditWindow() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_OPTIONAL");
        setPin(tablet, AZIZA, "482915"); // a session S1 and a consumed code A1
        unlockOk(tablet, AZIZA, "482915"); // S1 is superseded; S2 is open

        Instant ancient = clock.instant().minus(Duration.ofDays(401));
        jdbc.sql("UPDATE iam.terminal_sessions SET opened_at = :ancient, last_activity_at = :ancient")
                .param("ancient", ancient.atOffset(java.time.ZoneOffset.UTC))
                .update();
        jdbc.sql("UPDATE iam.staff_pin_activations SET created_at = :ancient")
                .param("ancient", ancient.atOffset(java.time.ZoneOffset.UTC))
                .update();

        issueCode(BAKHROM, CENTRE_MANAGER); // A2 pending...
        issueCode(BAKHROM, CENTRE_MANAGER); // ...superseded by A3 (spent, but recent), which is pending
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions")).isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM iam.staff_pin_activations")).isEqualTo(3L);

        job().purge();

        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_at IS NULL"))
                .as("an open session is never purged, however old its row")
                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions"))
                .as("the ended session past the window is gone")
                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM iam.staff_pin_activations WHERE status = 'PENDING'"))
                .as("a pending code is not a spent one")
                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM iam.staff_pin_activations"))
                .as("the old consumed code is gone; the recent superseded one and the pending one stay")
                .isEqualTo(2L);
    }
}
