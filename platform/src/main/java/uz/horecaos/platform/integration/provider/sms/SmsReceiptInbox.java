package uz.horecaos.platform.integration.provider.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope.TransportContext;
import uz.horecaos.platform.integration.inbox.JdbcInboxStore;
import uz.horecaos.platform.integration.inbox.JdbcInboxStore.InboxRow;
import uz.horecaos.platform.notifications.api.DeliveryReceiptPort;
import uz.horecaos.platform.notifications.api.DeliveryReceiptPort.ReceiptCommand;
import uz.horecaos.platform.notifications.api.DeliveryReceiptPort.ReceiptDisposition;

/**
 * Receipts through the ADR 0005 inbox (ADR 0146 Decision 4), so a duplicate is a
 * no-op and a crash between "heard" and "applied" is finished by the next arrival
 * rather than lost.
 *
 * <p>An HTTP callback has no Kafka position, and the inbox's transport key wants
 * one, so a value is minted from a sequence: unique per arrival and saying nothing
 * about the message. The business key is the event id, derived from the installation,
 * the provider's message id and the normalised status, so the same fact arriving
 * twice is one inbox row however many times the provider repeats it, and a different
 * fact about the same message (Sent, then Delivered) is its own.
 *
 * <p>What is stored is the normalised receipt and nothing of the callback body: a
 * VAS callback carries the provider account's {@code key} field, and a credential is
 * never written down, empty or not.
 */
@Component
public class SmsReceiptInbox {

    static final String TOPIC = "providers.sms.receipts";
    static final String EVENT_TYPE = "SmsDeliveryReceipt";

    private final JdbcInboxStore inbox;
    private final DeliveryReceiptPort receipts;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final Clock clock;

    public SmsReceiptInbox(
            JdbcInboxStore inbox,
            DeliveryReceiptPort receipts,
            TransactionTemplate transactions,
            JdbcClient jdbc,
            Clock clock) {
        this.inbox = inbox;
        this.receipts = receipts;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Applies one receipt at most once, and says what became of it. */
    public ReceiptDisposition accept(String providerType, ReceiptCommand receipt) {
        String consumer = "sms.receipts." + providerType.toLowerCase(java.util.Locale.ROOT);
        UUID eventId = UUID.nameUUIDFromBytes(
                (receipt.installationId() + "|" + receipt.providerMessageId() + "|" + receipt.normalizedStatus())
                        .getBytes(StandardCharsets.UTF_8));
        String payload = "{\"providerMessageId\":\"%s\",\"normalizedStatus\":\"%s\"}"
                .formatted(escape(receipt.providerMessageId()), receipt.normalizedStatus());

        long position = jdbc.sql("SELECT nextval('integration.provider_receipt_inbox_position_seq')")
                .query(Long.class)
                .single();
        ExternalEventEnvelope<String> envelope = new ExternalEventEnvelope<>(
                eventId,
                EVENT_TYPE,
                1,
                receipt.tenantId(),
                "ProviderInstallation",
                receipt.installationId(),
                eventId.toString(),
                null,
                clock.instant(),
                payload,
                sha256(payload),
                new TransportContext(
                        TOPIC, 0, position, receipt.installationId().toString()));

        Optional<InboxRow> existing = inbox.claim(consumer, envelope, payload);
        if (existing.isPresent() && "PROCESSED".equals(existing.get().status())) {
            return ReceiptDisposition.DUPLICATE;
        }
        InboxRow row = existing.orElseGet(() -> inbox.find(consumer, eventId)
                .orElseThrow(() -> new IllegalStateException("A receipt that was just claimed is not in the inbox")));

        UUID token = UUID.randomUUID();
        if (!inbox.beginProcessing(row.id(), token)) {
            // Another arrival of the same fact holds it. Whatever it decides stands.
            return ReceiptDisposition.DUPLICATE;
        }
        // The effect and the PROCESSED transition commit together (ADR 0005). A
        // failure rolls both back and leaves the row PROCESSING, so the provider's
        // own retry picks it up once the lease has lapsed.
        return transactions.execute(status -> {
            ReceiptDisposition disposition = receipts.apply(receipt);
            inbox.markProcessed(row.id(), token);
            return disposition;
        });
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required by every JVM", unreachable);
        }
    }
}
