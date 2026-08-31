package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fiscal.api.FiscalDocumentBlocked;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;

/** {@link FiscalOperationsAlertTrigger}, against a recording {@link uz.horecaos.platform.notifications.api.OperationsAlertPort} fake. */
class FiscalOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    @Test
    void aBlockedDocumentFansOutWithTheReasonCodeAndTheDocumentAsSubject() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        FiscalOperationsAlertTrigger trigger = new FiscalOperationsAlertTrigger(port, Duration.ofDays(3));

        trigger.onDocumentBlocked(new FiscalDocumentBlocked(
                EVENT_ID, TENANT, BRAND, LOCATION, DOCUMENT, ORDER, "PROVIDER_REPORT_OVERDUE", Instant.now()));

        assertThat(port.calls()).hasSize(1);
        RecordingOperationsAlertPort.Call call = port.calls().get(0);
        assertThat(call.tenantId()).isEqualTo(TENANT);
        assertThat(call.brandId()).isEqualTo(BRAND);
        assertThat(call.locationId()).isEqualTo(LOCATION);
        assertThat(call.eventClass()).isEqualTo(FiscalOperationsAlertTrigger.FISCAL_DOCUMENT_BLOCKED);
        assertThat(call.templateKey()).isEqualTo(FiscalOperationsAlertTrigger.FISCAL_DOCUMENT_BLOCKED);
        assertThat(call.subjectType()).isEqualTo("FiscalDocument");
        assertThat(call.subjectId()).isEqualTo(DOCUMENT);
        assertThat(call.triggerEventId()).isEqualTo(EVENT_ID);
        assertThat(call.variables()).containsEntry("reasonCode", "PROVIDER_REPORT_OVERDUE");
    }

    @Test
    void theIdempotencyKeyIsStablePerDocumentSoARepeatedFanOutCollapsesToOneAlert() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        FiscalOperationsAlertTrigger trigger = new FiscalOperationsAlertTrigger(port, Duration.ofDays(3));
        FiscalDocumentBlocked first = new FiscalDocumentBlocked(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, DOCUMENT, ORDER, "PROVIDER_REPORT_OVERDUE", Instant.now());
        FiscalDocumentBlocked replay = new FiscalDocumentBlocked(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, DOCUMENT, ORDER, "PROVIDER_REPORT_OVERDUE", Instant.now());

        trigger.onDocumentBlocked(first);
        trigger.onDocumentBlocked(replay);

        assertThat(port.calls())
                .extracting(RecordingOperationsAlertPort.Call::idempotencyKeyBase)
                .containsExactly(
                        port.calls().get(0).idempotencyKeyBase(),
                        port.calls().get(0).idempotencyKeyBase());
    }
}
