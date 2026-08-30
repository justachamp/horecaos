package uz.qoida.platform.integration.outbox;

interface OutboxPublisher {

    void publish(ClaimedOutboxEvent event) throws Exception;
}
