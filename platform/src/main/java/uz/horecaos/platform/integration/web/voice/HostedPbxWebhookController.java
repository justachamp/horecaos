package uz.horecaos.platform.integration.web.voice;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.integration.provider.voice.VoiceProcessedEventStore;
import uz.horecaos.platform.integration.provider.voice.hostedpbx.HostedPbxEventMapper;
import uz.horecaos.platform.integration.provider.voice.hostedpbx.HostedPbxWebhookPayload;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort;

/**
 * The hosted SIP/PBX adapter's inbound half (ADR 0064). Mirrors {@code
 * TelegramWebhookController} exactly: an unauthenticated path segment names
 * the installation, a secret-token header proves the call, and the dedup
 * table is what makes a retried delivery harmless.
 *
 * <p>No live provider account exists yet — the owner has not named a hosted
 * SIP/PBX vendor (ADR 0064's Implementation status says so plainly). This
 * controller is proven against the normalized contract itself, via the fake
 * PBX in the ADR 0007 genre ({@code FakeHostedPbxWebhookFixtures}), not
 * against a real vendor's wire format.
 */
@RestController
@RequestMapping("/providers/voice/hosted-pbx")
@Hidden
public class HostedPbxWebhookController {

    private static final Logger log = LoggerFactory.getLogger(HostedPbxWebhookController.class);
    static final String SECRET_HEADER = "X-HorecaOS-Voice-Secret-Token";

    private final VoiceInstallationLookup installations;
    private final VoiceProcessedEventStore processed;
    private final SecretResolver secrets;
    private final VoiceEventInboundPort ingestion;

    public HostedPbxWebhookController(
            VoiceInstallationLookup installations,
            VoiceProcessedEventStore processed,
            SecretResolver secrets,
            VoiceEventInboundPort ingestion) {
        this.installations = installations;
        this.processed = processed;
        this.secrets = secrets;
        this.ingestion = ingestion;
    }

    @PostMapping("/{installationId}/webhook")
    public ResponseEntity<Void> webhook(
            @PathVariable UUID installationId,
            @RequestHeader(value = SECRET_HEADER, required = false) @Nullable String presentedToken,
            @RequestBody HostedPbxWebhookPayload payload) {

        VoiceInstallation installation = installations.find(installationId).orElse(null);
        if (installation == null
                || installation.webhookSecretReference() == null
                || !"ACTIVE".equals(installation.status())) {
            return ResponseEntity.status(403).build();
        }

        String expected = secrets.resolve(SecretReference.parse(installation.webhookSecretReference()))
                .reveal();
        if (presentedToken == null || !constantTimeEquals(presentedToken, expected)) {
            log.warn("Hosted-PBX webhook for installation {} presented an invalid secret token", installationId);
            return ResponseEntity.status(403).build();
        }

        if (!processed.recordIfNew(installation.tenantId(), installationId, payload.eventId())) {
            // Already ingested — a retried delivery, not a new fact.
            return ResponseEntity.ok().build();
        }

        try {
            ingestion.ingest(HostedPbxEventMapper.toInboundCallEvent(payload, installation));
        } catch (IllegalArgumentException malformed) {
            log.warn(
                    "Hosted-PBX webhook for installation {} could not be mapped: {}",
                    installationId,
                    malformed.getMessage());
            // 200 regardless: a malformed body is not something the provider
            // should retry indefinitely, and a 4xx invites exactly that.
        }
        return ResponseEntity.ok().build();
    }

    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
