package uz.horecaos.platform.integration.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import uz.horecaos.platform.configuration.ConditionalOnWorkerRole;

/**
 * ADR 0023 names {@code horecaos.messaging.inbox.listener.enabled} as one of the four
 * switches the {@code app}/{@code worker} split "no longer covers", because it guards
 * a {@code @KafkaListener} rather than a {@code @Scheduled} method — so
 * {@code SchedulingConfiguration}'s blanket role gate never touched it.
 *
 * <p>Pinned by class metadata, the same way {@code SchedulerPoolSizeTests} pins the
 * scheduled method count: a reflection check costs no broker and fails the moment
 * either listener loses the annotation, which a full Kafka integration test would only
 * catch if it happened to assert on role — and nothing did, before this record.
 */
class WorkerRoleAnnotationCoverageTests {

    @Test
    void theTenancyEventListenerHonoursTheWorkerRole() {
        assertThat(TenancyEventListener.class.isAnnotationPresent(ConditionalOnWorkerRole.class))
                .as("the inbox listener switch alone cannot express \"not on this role\"; "
                        + "this annotation is what a role of app relies on to stop it")
                .isTrue();
    }

    @Test
    void theFulfillmentCommandListenerHonoursTheWorkerRole() {
        assertThat(FulfillmentCommandListener.class.isAnnotationPresent(ConditionalOnWorkerRole.class))
                .isTrue();
    }
}
