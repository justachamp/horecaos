package uz.horecaos.platform.integration.web.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.provider.telegram.TelegramUpdateHandler;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;

/**
 * ADR 0058's "no oracle" invariant is about cost, not only status and body: a
 * not-found/inactive installation id must not be cheaper to answer than a
 * real, {@code ACTIVE}, webhook-registered one, because
 * {@code SecurityConfiguration} now reaches this controller without
 * authentication and installation ids are v7 (guessable by creation-time
 * window). A plain Mockito unit test — no Spring context, no Postgres, no
 * fake Bot API — proves the shape of work directly: every collaborator would
 * throw or return an unusable value if a branch skipped it, so a passing
 * test here is evidence the same calls happen on both paths, not a
 * coincidence of stubbing.
 */
class TelegramWebhookControllerTimingTests {

    private final TelegramWebhookInstallationLookup installations = mock(TelegramWebhookInstallationLookup.class);
    private final SecretResolver secrets = mock(SecretResolver.class);
    private final SecretIngressGateway door = mock(SecretIngressGateway.class);
    private final TelegramUpdateHandler handler = mock(TelegramUpdateHandler.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);

    private final TelegramWebhookController controller =
            new TelegramWebhookController(installations, secrets, door, handler, objectMapper);

    @Test
    void aNotFoundInstallationStillResolvesASecretBeforeRefusing() {
        UUID installationId = UUID.randomUUID();
        when(installations.find(installationId)).thenReturn(Optional.empty());
        SecretReference decoy = new SecretReference("test", SecretCategory.PROVIDER_NOTIFICATION, "decoy", "1");
        when(door.write(any(), any(), any())).thenReturn(decoy);
        when(secrets.resolve(decoy)).thenReturn(SecretValue.of("unused"));

        ResponseEntity<Void> response = controller.webhook(installationId, "whatever", new byte[0]);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(secrets, times(1)).resolve(decoy);
    }

    @Test
    void theDecoyReferenceIsMintedOnceAndReusedAcrossNotFoundCalls() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(installations.find(any())).thenReturn(Optional.empty());
        SecretReference decoy = new SecretReference("test", SecretCategory.PROVIDER_NOTIFICATION, "decoy", "1");
        when(door.write(any(), any(), any())).thenReturn(decoy);
        when(secrets.resolve(any())).thenReturn(SecretValue.of("unused"));

        controller.webhook(first, "x", new byte[0]);
        controller.webhook(second, "x", new byte[0]);

        // Only minted once: a real installation's own reference warms into
        // SecretResolver's cache from repeated genuine traffic, and the decoy
        // must warm the same way rather than paying a fresh write+resolve on
        // every single probe.
        verify(door, times(1)).write(any(), any(), any());
        verify(secrets, times(2)).resolve(decoy);
    }

    @Test
    void aFoundButWrongTokenInstallationResolvesItsOwnRealReferenceNotTheDecoy() {
        UUID installationId = UUID.randomUUID();
        SecretReference real = new SecretReference("test", SecretCategory.PROVIDER_NOTIFICATION, "tenant-x", "real");
        WebhookInstallation installation = new WebhookInstallation(
                installationId,
                UUID.randomUUID(),
                "TELEGRAM_BOT_API",
                "ACTIVE",
                "http://bot.example",
                "bot-token-ref",
                real.toString());
        when(installations.find(installationId)).thenReturn(Optional.of(installation));
        when(secrets.resolve(real)).thenReturn(SecretValue.of("the-real-secret"));

        ResponseEntity<Void> response = controller.webhook(installationId, "not-the-right-token", new byte[0]);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(secrets, times(1)).resolve(real);
        verify(door, never()).write(any(), any(), any());
    }

    @Test
    void anInactiveInstallationIsTreatedLikeNotFoundAndStillPaysTheResolveCost() {
        UUID installationId = UUID.randomUUID();
        SecretReference real = new SecretReference("test", SecretCategory.PROVIDER_NOTIFICATION, "tenant-x", "real");
        WebhookInstallation installation = new WebhookInstallation(
                installationId,
                UUID.randomUUID(),
                "TELEGRAM_BOT_API",
                "SUSPENDED",
                "http://bot.example",
                "bot-token-ref",
                real.toString());
        when(installations.find(installationId)).thenReturn(Optional.of(installation));
        SecretReference decoy = new SecretReference("test", SecretCategory.PROVIDER_NOTIFICATION, "decoy", "1");
        when(door.write(any(), any(), any())).thenReturn(decoy);
        when(secrets.resolve(decoy)).thenReturn(SecretValue.of("unused"));

        ResponseEntity<Void> response = controller.webhook(installationId, "whatever", new byte[0]);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(secrets, times(1)).resolve(decoy);
        verify(secrets, never()).resolve(real);
    }
}
