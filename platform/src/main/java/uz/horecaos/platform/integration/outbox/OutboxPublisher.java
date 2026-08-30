package uz.horecaos.platform.integration.outbox;

interface OutboxPublisher {

    void publish(ClaimedOutboxEvent event) throws Exception;
}
