package uz.horecaos.platform.integration.provider.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0058: Telegram requires an https webhook URL. {@code
 * TelegramWebhookRegistrationService#register} checks {@code
 * horecaos.public-api-origin} before it ever reads the installation row, so a
 * plain unit test — no Postgres, no fake Bot API, no Spring context — proves
 * the guard without paying for any of them. Every collaborator below is a
 * Mockito stub that would blow up if the guard let the call through to use
 * it, which is exactly the point: if this test passes for the wrong reason
 * (an NPE from a stub, say), a mismatched exception type or message would
 * fail the assertions below rather than silently passing.
 */
class TelegramWebhookRegistrationServiceOriginTests {

    private static TelegramWebhookRegistrationService serviceWithOrigin(String origin, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new TelegramWebhookRegistrationService(
                mock(JdbcClient.class),
                mock(SecretIngressGateway.class),
                mock(SecretResolver.class),
                mock(TelegramBotApiClient.class),
                mock(AuditRecorder.class),
                mock(TransactionTemplate.class),
                Clock.systemUTC(),
                environment,
                origin);
    }

    @Test
    void refusesAnHttpOriginOutsideLocalOrTest() {
        TelegramWebhookRegistrationService service = serviceWithOrigin("http://api.horecaos.uz", "production");

        assertThatThrownBy(() -> service.register(UUID.randomUUID(), UUID.randomUUID(), ActorRef.user("someone", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("https")
                .satisfies(failure ->
                        assertThat(((ApiException) failure).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE));
    }

    @Test
    void refusesAnHttpOriginWithNoActiveProfileNamedAtAll() {
        // Empty active profiles is not the same case as "default": a
        // deployment that forgot to set SPRING_PROFILES_ACTIVE must still be
        // refused, not treated as a laptop. (Contrast the guard's own
        // localOnly check, which — correctly — treats an EMPTY active-profile
        // list as local/test/default; that is Spring's own "default" profile,
        // named explicitly here as "default" rather than left empty, so this
        // test cannot be confused with that one.)
        TelegramWebhookRegistrationService service = serviceWithOrigin("http://api.horecaos.uz", "staging");

        assertThatThrownBy(() -> service.register(UUID.randomUUID(), UUID.randomUUID(), ActorRef.user("someone", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("https");
    }
}
