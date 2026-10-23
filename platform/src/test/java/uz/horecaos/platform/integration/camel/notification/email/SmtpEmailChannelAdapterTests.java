package uz.horecaos.platform.integration.camel.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.notification.AccountContext;
import uz.horecaos.platform.integration.camel.notification.NotificationChannelAdapter;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationDispatch.EmailEnvelope;

/**
 * ADR 0182, D13: the platform's mail relay as a notification adapter. What it must get right is not
 * the happy path but what each answer of the relay means for a customer: only a relay's permanent
 * refusal of an <em>address</em> is a hard bounce, a lost answer is never repeated, and a campaign
 * email never leaves without its way out.
 */
class SmtpEmailChannelAdapterTests {

    private static final ProviderCall CALL = new ProviderCall("", "", "attempt-1", Duration.ofSeconds(5));
    private static final String UNSUBSCRIBE = "https://api.horecaos.test/providers/mail/unsubscribe/abc";

    private RecordingMailer mailer;
    private SmtpEmailChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        mailer = new RecordingMailer();
        adapter = new SmtpEmailChannelAdapter(mailer);
    }

    @Test
    @DisplayName("the adapter speaks for the platform and carries marketing only")
    void itIsPlatformOwnedAndMarketingOnly() {
        assertThat(adapter.platformOwned()).isTrue();
        assertThat(adapter.channel()).isEqualTo("EMAIL");
        assertThat(adapter.providerType()).isEqualTo("PLATFORM_SMTP");
        assertThat(adapter.permits(NotificationDispatch.PURPOSE_MARKETING, AccountContext.EMPTY))
                .isTrue();
        assertThat(adapter.permits(NotificationDispatch.PURPOSE_TRANSACTIONAL, AccountContext.EMPTY))
                .as("a receipt by email is a later decision with its own footing")
                .isFalse();
        assertThat(adapter.permits(NotificationDispatch.PURPOSE_COURIER, AccountContext.EMPTY))
                .isFalse();
    }

    @Test
    @DisplayName("an unconfigured relay says which settings are missing, without calling out")
    void anUnconfiguredRelayIsAFindingNotAFailure() {
        mailer.configured = false;

        var readiness = adapter.describeAccount(AccountContext.EMPTY);

        assertThat(readiness.complete()).isFalse();
        assertThat(readiness.missingFields()).contains("horecaos.mail.smtp.host", "horecaos.mail.from");
        assertThat(mailer.sent).isEmpty();
    }

    @Test
    @DisplayName("an accepted message is accepted and nothing more: SMTP reports no delivery and no read")
    void anAcceptedMessageIsOnlyAccepted() {
        ProviderOutcome outcome = adapter.send(dispatch("Chegirma 20%", UNSUBSCRIBE), CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(outcome.normalized())
                .containsEntry(NotificationChannelAdapter.NORMALIZED_STATUS_KEY, "ACCEPTED")
                .containsEntry(NotificationChannelAdapter.PROVIDER_STATUS_KEY, "SMTP_ACCEPTED");
        assertThat(mailer.sent).hasSize(1);
        OutgoingMail mail = mailer.sent.getFirst();
        assertThat(mail.to()).isEqualTo("guest@example.uz");
        assertThat(mail.subject()).isEqualTo("Pilot brand: aksiya");
        assertThat(mail.senderName()).isEqualTo("Pilot brand");
        assertThat(mail.unsubscribeUrl()).isEqualTo(UNSUBSCRIBE);
        assertThat(mail.text()).contains("Chegirma 20%").contains(UNSUBSCRIBE).contains("-- ");
        assertThat(mail.html()).contains("<a href=\"" + UNSUBSCRIBE + "\">");
    }

    @Test
    @DisplayName("a template's text is text: markup in the body is escaped in the HTML part")
    void theBodyIsEscaped() {
        adapter.send(dispatch("<script>alert(1)</script> & more\n\nsecond paragraph", UNSUBSCRIBE), CALL);

        String html = mailer.sent.getFirst().html();
        assertThat(html)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; more")
                .contains("<p>second paragraph</p>");
    }

    @Test
    @DisplayName("a subject cannot carry a line break: a template author cannot add headers")
    void aSubjectIsOneLine() {
        adapter.send(dispatchWithSubject("Hello\r\nBcc: someone@else.uz", "text", UNSUBSCRIBE), CALL);

        assertThat(mailer.sent.getFirst().subject()).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    @DisplayName("a campaign email without its way out is refused, not sent")
    void noUnsubscribeNoSend() {
        ProviderOutcome outcome = adapter.send(dispatchWithoutEnvelope(), CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("EMAIL_UNSUBSCRIBE_MISSING");
        assertThat(mailer.sent).isEmpty();
    }

    @Test
    @DisplayName("a purpose other than marketing is refused before the relay is touched")
    void aTransactionalPurposeIsRefused() {
        NotificationDispatch transactional = new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "EMAIL",
                "guest@example.uz",
                "Receipt",
                "Thanks",
                "attempt-1",
                "corr",
                "Order",
                UUID.randomUUID(),
                "ORDER_CONFIRMED",
                NotificationDispatch.PURPOSE_TRANSACTIONAL,
                new EmailEnvelope("Pilot brand", UNSUBSCRIBE, "footer " + UNSUBSCRIBE));

        ProviderOutcome outcome = adapter.send(transactional, CALL);

        assertThat(outcome.errorCode()).isEqualTo("EMAIL_PURPOSE_NOT_PERMITTED");
        assertThat(mailer.sent).isEmpty();
    }

    @Test
    @DisplayName("an email without a subject is refused: every inbox files it under «no subject»")
    void aSubjectIsRequired() {
        ProviderOutcome outcome = adapter.send(dispatchWithSubject("  ", "text", UNSUBSCRIBE), CALL);

        assertThat(outcome.errorCode()).isEqualTo("EMAIL_SUBJECT_MISSING");
    }

    @Test
    @DisplayName("a relay that refuses the ADDRESS for good has said the customer cannot be reached: a hard bounce")
    void anAddressRefusalIsAHardBounce() {
        mailer.outcome = new MailOutcome.Rejected("ADDRESS_REJECTED");

        ProviderOutcome outcome = adapter.send(dispatch("text", UNSUBSCRIBE), CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("ADDRESS_REJECTED");
        assertThat(outcome.normalized()).containsEntry(NotificationChannelAdapter.HARD_BOUNCE_KEY, "true");
    }

    @Test
    @DisplayName("a refusal of the MESSAGE says nothing about the customer and must not suppress them")
    void aMessageRefusalIsNotAHardBounce() {
        mailer.outcome = new MailOutcome.Rejected("MESSAGE_INVALID");

        ProviderOutcome outcome = adapter.send(dispatch("text", UNSUBSCRIBE), CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.normalized()).doesNotContainKey(NotificationChannelAdapter.HARD_BOUNCE_KEY);
    }

    @Test
    @DisplayName("a relay that could not be reached is worth retrying")
    void anUnreachableRelayIsRetryable() {
        mailer.outcome = new MailOutcome.Failed("SMTP_UNAVAILABLE");

        assertThat(adapter.send(dispatch("text", UNSUBSCRIBE), CALL).status())
                .isEqualTo(ProviderOutcome.Status.RETRYABLE);
    }

    @Test
    @DisplayName("a connection lost after the message was on its way is uncertain, and is never repeated")
    void aLostAnswerIsUncertain() {
        mailer.outcome = new MailOutcome.Failed(MailOutcome.SMTP_NO_ANSWER);

        ProviderOutcome outcome = adapter.send(dispatch("text", UNSUBSCRIBE), CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(adapter.queryStatus("attempt-1", CALL).status())
                .as("SMTP offers no way to ask, so the answer stays unknown rather than licensing a second send")
                .isEqualTo(ProviderOutcome.Status.UNCERTAIN);
    }

    @Test
    @DisplayName("an unconfigured relay at send time is a stable code, not a retry")
    void notConfiguredIsRefusedWithACode() {
        mailer.outcome = new MailOutcome.NotConfigured();

        ProviderOutcome outcome = adapter.send(dispatch("text", UNSUBSCRIBE), CALL);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("EMAIL_NOT_CONFIGURED");
    }

    // ---------------------------------------------------------------- fixtures

    private static NotificationDispatch dispatch(String body, String unsubscribeUrl) {
        return dispatchWithSubject("Pilot brand: aksiya", body, unsubscribeUrl);
    }

    private static NotificationDispatch dispatchWithSubject(String subject, String body, String unsubscribeUrl) {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "EMAIL",
                "guest@example.uz",
                subject,
                body,
                "attempt-1",
                "corr",
                "MarketingCampaign",
                UUID.randomUUID(),
                "PROMO",
                NotificationDispatch.PURPOSE_MARKETING,
                new EmailEnvelope("Pilot brand", unsubscribeUrl, "Obunani bekor qilish: " + unsubscribeUrl));
    }

    private static NotificationDispatch dispatchWithoutEnvelope() {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "EMAIL",
                "guest@example.uz",
                "Subject",
                "body",
                "attempt-1",
                "corr",
                "MarketingCampaign",
                UUID.randomUUID(),
                "PROMO",
                NotificationDispatch.PURPOSE_MARKETING);
    }

    private static final class RecordingMailer implements PlatformMailer {
        final List<OutgoingMail> sent = new ArrayList<>();
        boolean configured = true;
        MailOutcome outcome = new MailOutcome.Sent();

        @Override
        public MailOutcome send(OutgoingMail mail) {
            sent.add(mail);
            return outcome;
        }

        @Override
        public boolean configured() {
            return configured;
        }
    }
}
