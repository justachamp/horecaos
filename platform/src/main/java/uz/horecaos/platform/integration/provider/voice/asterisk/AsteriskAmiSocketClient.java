package uz.horecaos.platform.integration.provider.voice.asterisk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.integration.provider.voice.VoiceProcessedEventStore;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort;

/**
 * The Asterisk-class adapter's inbound half (ADR 0064): connects to one AMI
 * (Asterisk Manager Interface) endpoint, authenticates, and translates every
 * event it reads into a canonical call event for as long as the connection
 * lasts.
 *
 * <p>{@link Runnable}, one instance per installation, run on its own daemon
 * thread by {@link AsteriskAmiConnectionSupervisor} — an AMI session is a
 * long-lived blocking read loop, not a poll, so a scheduled executor tick is
 * the wrong shape for it.
 *
 * <p>AMI has no built-in redelivery: if this connection is down when
 * Asterisk fires an event, that event is simply lost, not resent on
 * reconnect. That is a property of the protocol, not a gap in this adapter —
 * a hosted PBX's webhook retry has no AMI equivalent to lean on.
 */
public class AsteriskAmiSocketClient implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AsteriskAmiSocketClient.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final VoiceInstallation installation;
    private final SecretResolver secrets;
    private final VoiceProcessedEventStore processed;
    private final VoiceEventInboundPort ingestion;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AsteriskAmiSocketClient(
            VoiceInstallation installation,
            SecretResolver secrets,
            VoiceProcessedEventStore processed,
            VoiceEventInboundPort ingestion,
            ObjectMapper objectMapper,
            Clock clock) {
        this.installation = installation;
        this.secrets = secrets;
        this.processed = processed;
        this.ingestion = ingestion;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void run() {
        AsteriskConnectionConfig config;
        try {
            config = AsteriskConnectionConfig.fromJson(objectMapper, installation.nonSensitiveConfigJson());
        } catch (RuntimeException misconfigured) {
            log.error(
                    "Asterisk installation {} cannot be connected: {}",
                    installation.installationId(),
                    misconfigured.getMessage());
            return;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(config.host(), config.port()), CONNECT_TIMEOUT_MILLIS);
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            // The AMI banner line ("Asterisk Call Manager/x.y.z") precedes any
            // block and is not itself Key: Value framed; readBlock tolerates it
            // by skipping content-free lines, but the banner line does have
            // content with no colon, which readBlock simply records as a
            // fieldless entry — harmless, since login() only checks Response.
            login(reader, out, config);

            for (Optional<Map<String, String>> block = AsteriskAmiEventParser.readBlock(reader);
                    block.isPresent();
                    block = AsteriskAmiEventParser.readBlock(reader)) {
                process(block.get());
            }
            log.info("Asterisk AMI connection for installation {} closed by the peer", installation.installationId());
        } catch (IOException failure) {
            log.warn(
                    "Asterisk AMI connection for installation {} failed: {}",
                    installation.installationId(),
                    failure.getMessage());
        }
    }

    private void login(BufferedReader reader, OutputStream out, AsteriskConnectionConfig config) throws IOException {
        String secret = installation.secretReference() == null
                ? ""
                : secrets.resolve(SecretReference.parse(installation.secretReference()))
                        .reveal();
        String actionId = UUID.randomUUID().toString();
        String action = "Action: Login\r\n"
                + "ActionID: " + actionId + "\r\n"
                + "Username: " + config.username() + "\r\n"
                + "Secret: " + secret + "\r\n"
                + "\r\n";
        out.write(action.getBytes(StandardCharsets.UTF_8));
        out.flush();

        Optional<Map<String, String>> response = AsteriskAmiEventParser.readBlock(reader);
        String status = response.map(fields -> fields.get("Response")).orElse(null);
        if (!"Success".equalsIgnoreCase(status)) {
            throw new IOException("AMI login refused for installation " + installation.installationId());
        }
    }

    private void process(Map<String, String> fields) {
        if (!fields.containsKey("Event")) {
            return;
        }
        var now = clock.instant();
        // Deliberately no wall-clock time in this key: a genuine redelivery
        // (a reconnect replaying the same event) arrives with a different
        // instant than the original every time, so including one would defeat
        // the dedup entirely rather than narrow it — this is not hypothetical,
        // it is exactly what made the first version of this method never
        // actually deduplicate anything. SequenceNumber, when a newer Asterisk
        // reports one, is used first since it is genuinely unique per event;
        // event+Uniqueid alone is the fallback, which trades away catching two
        // truly distinct same-type events on one channel within one
        // connection — a real but much smaller gap than the one it replaces.
        String providerEventId = fields.get("Event") + ":" + fields.getOrDefault("Uniqueid", "") + ":"
                + fields.getOrDefault("SequenceNumber", "");
        if (!processed.recordIfNew(installation.tenantId(), installation.installationId(), providerEventId)) {
            return;
        }
        try {
            AsteriskAmiEventMapper.toInboundCallEvent(fields, installation, now).ifPresent(ingestion::ingest);
        } catch (RuntimeException problem) {
            log.warn(
                    "Asterisk AMI event from installation {} could not be ingested: {}",
                    installation.installationId(),
                    problem.getMessage());
        }
    }
}
