package uz.horecaos.platform.mail.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;

/**
 * ADR 0097's mailer against a real SMTP server: Mailpit in a container, read
 * back through its own API, so what is asserted is what a mail server received
 * rather than what this class meant to send.
 */
class SmtpPlatformMailerTests {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private static GenericContainer<?> mailpit;
    private static RestClient api;
    private static final SecretResolver NO_SECRETS = new EnvironmentSecretResolver(key -> null, Clock.systemUTC());

    @BeforeAll
    static void startMailpit() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        mailpit = new GenericContainer<>("axllent/mailpit:v1.27")
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));
        mailpit.start();
        api = RestClient.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025));
    }

    @AfterAll
    static void stopMailpit() {
        if (mailpit != null) {
            mailpit.stop();
        }
    }

    @Test
    @DisplayName("a message reaches the server with its recipient, subject, and both bodies")
    void aMessageIsDelivered() {
        SmtpPlatformMailer mailer = mailer(mailpit.getHost(), mailpit.getMappedPort(1025));

        MailOutcome outcome = mailer.send(new OutgoingMail(
                "owner@example.uz",
                "Qoida uchun HorecaOS hisobingizni sozlang",
                "Salom!\n\nhttps://ops.test/invite#token=abc",
                "<p>Salom!</p><p><a href=\"https://ops.test/invite#token=abc\">Sozlash</a></p>"));

        assertThat(outcome).isEqualTo(new MailOutcome.Sent());
        Map<String, Object> list = api.get().uri("/api/v1/messages").retrieve().body(MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = java.util.Objects.requireNonNull((List<Map<String, Object>>)
                java.util.Objects.requireNonNull(list).get("messages"));
        assertThat(messages).hasSize(1);
        Map<String, Object> summary = messages.getFirst();
        assertThat(summary.get("Subject")).isEqualTo("Qoida uchun HorecaOS hisobingizni sozlang");
        assertThat(String.valueOf(summary.get("To"))).contains("owner@example.uz");
        assertThat(String.valueOf(summary.get("From"))).contains("no-reply@horecaos.test");

        Map<String, Object> message = api.get()
                .uri("/api/v1/message/{id}", summary.get("ID"))
                .retrieve()
                .body(MAP);
        assertThat(String.valueOf(java.util.Objects.requireNonNull(message).get("Text")))
                .contains("https://ops.test/invite#token=abc");
        assertThat(String.valueOf(message.get("HTML"))).contains("<a href=\"https://ops.test/invite#token=abc\">");
    }

    @Test
    @DisplayName("with no host configured nothing is attempted, and it says so")
    void anUnconfiguredMailerSaysSo() {
        SmtpPlatformMailer mailer =
                new SmtpPlatformMailer(NO_SECRETS, "", 587, "STARTTLS", "", "", "HorecaOS <no-reply@horecaos.test>");

        assertThat(mailer.configured()).isFalse();
        assertThat(mailer.send(new OutgoingMail("owner@example.uz", "s", "t", "<p>t</p>")))
                .isEqualTo(new MailOutcome.NotConfigured());
    }

    @Test
    @DisplayName("an unreachable server is a failure worth retrying, not a rejection")
    void anUnreachableServerIsRetryable() {
        SmtpPlatformMailer mailer = mailer("127.0.0.1", 1);

        assertThat(mailer.send(new OutgoingMail("owner@example.uz", "s", "t", "<p>t</p>")))
                .isEqualTo(new MailOutcome.Failed("SMTP_UNAVAILABLE"));
    }

    @Test
    @DisplayName("a recipient that is not an address is refused for good")
    void aMalformedAddressIsRejected() {
        SmtpPlatformMailer mailer = mailer(mailpit.getHost(), mailpit.getMappedPort(1025));

        assertThat(mailer.send(new OutgoingMail("not an address", "s", "t", "<p>t</p>")))
                .isInstanceOf(MailOutcome.Rejected.class);
    }

    private static SmtpPlatformMailer mailer(String host, int port) {
        return new SmtpPlatformMailer(NO_SECRETS, host, port, "NONE", "", "", "HorecaOS <no-reply@horecaos.test>");
    }
}
