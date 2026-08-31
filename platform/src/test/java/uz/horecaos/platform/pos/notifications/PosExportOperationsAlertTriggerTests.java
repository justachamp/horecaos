package uz.horecaos.platform.pos.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pos.api.PosExportAwaitingOperator;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;

/** {@link PosExportOperationsAlertTrigger}. */
class PosExportOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID EXPORT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();

    @Test
    void anExportAwaitingOperatorFansOutKeyedOnTheExport() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        PosExportOperationsAlertTrigger trigger = new PosExportOperationsAlertTrigger(port, Duration.ofDays(1));

        trigger.onExportAwaitingOperator(new PosExportAwaitingOperator(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, EXPORT, ORDER, "EXPORT_NEEDS_OPERATOR", Instant.now()));

        assertThat(port.calls()).hasSize(1);
        RecordingOperationsAlertPort.Call call = port.calls().get(0);
        assertThat(call.eventClass()).isEqualTo(PosExportOperationsAlertTrigger.POS_EXPORT_AWAITING_OPERATOR);
        assertThat(call.subjectType()).isEqualTo("PosExport");
        assertThat(call.subjectId()).isEqualTo(EXPORT);
        assertThat(call.idempotencyKeyBase()).contains(EXPORT.toString());
        assertThat(call.variables()).containsEntry("reasonCode", "EXPORT_NEEDS_OPERATOR");
    }
}
