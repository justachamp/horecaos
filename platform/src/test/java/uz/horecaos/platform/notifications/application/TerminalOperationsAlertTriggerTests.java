package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.terminals.TerminalAlert;
import uz.horecaos.platform.integration.provider.telegram.TelegramEventClass;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;

/**
 * ADR 0155, Observability and alerts: a PIN lock and a {@code PERSON_REQUIRED} device unseen during service each reach the chats
 * subscribed to their event class, and say no more than a non-personal reference and a device label.
 */
class TerminalOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();
    private static final Instant LOCKED_AT = Instant.parse("2026-10-09T08:00:00Z");

    private final RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
    private final TerminalOperationsAlertTrigger trigger =
            new TerminalOperationsAlertTrigger(port, Duration.ofHours(12));

    private static TerminalAlert locked(Instant episode) {
        return new TerminalAlert(
                TerminalAlert.CREDENTIAL_LOCKED,
                TENANT,
                BRAND,
                LOCATION,
                MEMBER,
                "StaffMember",
                DEVICE,
                "Pass tablet",
                "S-0142",
                "DEVICE_CONSECUTIVE",
                episode,
                UUID.randomUUID());
    }

    private static TerminalAlert unseen(Instant lastSeen) {
        return new TerminalAlert(
                TerminalAlert.DEVICE_UNSEEN,
                TENANT,
                BRAND,
                LOCATION,
                DEVICE,
                "Device",
                DEVICE,
                "Pass tablet",
                "",
                "",
                lastSeen,
                UUID.randomUUID());
    }

    @Test
    @DisplayName(
            "a lock fans out under its own event class and template key, naming the member's reference and the device")
    void aLockFansOutOnce() {
        trigger.onTerminalAlert(locked(LOCKED_AT));

        assertThat(port.calls()).hasSize(1);
        RecordingOperationsAlertPort.Call call = port.calls().get(0);
        assertThat(call.eventClass()).isEqualTo("TERMINAL_CREDENTIAL_LOCKED");
        assertThat(call.templateKey()).isEqualTo("TERMINAL_CREDENTIAL_LOCKED");
        assertThat(call.subjectType()).isEqualTo("StaffMember");
        assertThat(call.subjectId()).isEqualTo(MEMBER);
        assertThat(call.locationId()).isEqualTo(LOCATION);
        assertThat(call.variables())
                .containsOnlyKeys("deviceLabel", "staffReference", "lockRule")
                .containsEntry("staffReference", "S-0142")
                .containsEntry("deviceLabel", "Pass tablet")
                .containsEntry("lockRule", "DEVICE_CONSECUTIVE");
    }

    @Test
    @DisplayName("an unseen device fans out under its own class with the device and when it was last seen")
    void anUnseenDeviceFansOut() {
        trigger.onTerminalAlert(unseen(LOCKED_AT));

        RecordingOperationsAlertPort.Call call = port.calls().get(0);
        assertThat(call.eventClass()).isEqualTo("TERMINAL_DEVICE_UNSEEN");
        assertThat(call.subjectType()).isEqualTo("Device");
        assertThat(call.variables()).containsOnlyKeys("deviceLabel", "lastSeenAt");
    }

    @Test
    @DisplayName("a replayed trigger reaches a chat once, and a later episode alerts again")
    void theEpisodeNamesTheDeduplicationKey() {
        trigger.onTerminalAlert(locked(LOCKED_AT));
        trigger.onTerminalAlert(locked(LOCKED_AT));
        trigger.onTerminalAlert(locked(LOCKED_AT.plusSeconds(3600)));

        assertThat(port.calls().get(0).idempotencyKeyBase())
                .isEqualTo(port.calls().get(1).idempotencyKeyBase());
        assertThat(port.calls().get(2).idempotencyKeyBase())
                .as("a later lock is a new episode")
                .isNotEqualTo(port.calls().get(0).idempotencyKeyBase());
    }

    @Test
    @DisplayName("both classes are in the closed set a chat can subscribe to")
    void bothClassesCanBeSubscribedTo() {
        assertThat(TelegramEventClass.valueOf(TerminalAlert.CREDENTIAL_LOCKED))
                .isEqualTo(TelegramEventClass.TERMINAL_CREDENTIAL_LOCKED);
        assertThat(TelegramEventClass.valueOf(TerminalAlert.DEVICE_UNSEEN))
                .isEqualTo(TelegramEventClass.TERMINAL_DEVICE_UNSEEN);
    }

    @Test
    @DisplayName("the variables carry no protected field, by the same heuristic every operations message is held to")
    void theVariablesAreClean() {
        for (TerminalAlert alert : new TerminalAlert[] {locked(LOCKED_AT), unseen(LOCKED_AT)}) {
            assertThat(TerminalOperationsAlertTrigger.variablesFor(alert).keySet().stream()
                            .filter(uz.horecaos.platform.iam.api.protection.ClassificationScanner::isProtectedName)
                            .toList())
                    .as(alert.eventClass())
                    .isEmpty();
        }
    }
}
