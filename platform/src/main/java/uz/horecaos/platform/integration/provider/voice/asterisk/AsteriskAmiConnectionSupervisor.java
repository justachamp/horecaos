package uz.horecaos.platform.integration.provider.voice.asterisk;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.integration.provider.voice.VoiceProcessedEventStore;
import uz.horecaos.platform.integration.provider.voice.VoiceProviderCapabilityCatalog;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort;

/**
 * Keeps one {@link AsteriskAmiSocketClient} thread alive per ACTIVE
 * Asterisk-class installation (ADR 0064).
 *
 * <p>A dedicated daemon thread per connection, not the scheduler's own thread
 * pool: an AMI session is a long-lived blocking read loop, and the scheduled
 * tick + compare-and-set idiom {@code OutboxRelay} uses for its own batch
 * poll is the wrong shape for that. This class only ever does the short,
 * non-blocking work of deciding whether a thread needs (re)starting; {@link
 * #ensureConnections()} itself runs on the scheduler and must return quickly.
 */
@Component
@ConditionalOnProperty(name = "horecaos.voice.asterisk.supervisor.enabled", havingValue = "true", matchIfMissing = true)
public class AsteriskAmiConnectionSupervisor {

    private static final Logger log = LoggerFactory.getLogger(AsteriskAmiConnectionSupervisor.class);

    private final VoiceInstallationLookup installations;
    private final SecretResolver secrets;
    private final VoiceProcessedEventStore processed;
    private final VoiceEventInboundPort ingestion;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<UUID, Thread> connections = new ConcurrentHashMap<>();

    public AsteriskAmiConnectionSupervisor(
            VoiceInstallationLookup installations,
            SecretResolver secrets,
            VoiceProcessedEventStore processed,
            VoiceEventInboundPort ingestion,
            ObjectMapper objectMapper,
            Clock clock) {
        this.installations = installations;
        this.secrets = secrets;
        this.processed = processed;
        this.ingestion = ingestion;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${horecaos.voice.asterisk.supervisor.interval:15s}")
    public void ensureConnections() {
        List<VoiceInstallation> active = installations.listActive(VoiceProviderCapabilityCatalog.ASTERISK_AMI);
        connections.entrySet().removeIf(entry -> !entry.getValue().isAlive());

        for (VoiceInstallation installation : active) {
            if (!installation.isScoped()) {
                continue;
            }
            connections.computeIfAbsent(installation.installationId(), id -> startConnection(installation));
        }
    }

    private Thread startConnection(VoiceInstallation installation) {
        var client = new AsteriskAmiSocketClient(installation, secrets, processed, ingestion, objectMapper, clock);
        Thread thread = new Thread(client, "voice-asterisk-" + installation.installationId());
        thread.setDaemon(true);
        thread.start();
        log.info("Started an Asterisk AMI connection for installation {}", installation.installationId());
        return thread;
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(Thread::interrupt);
    }
}
