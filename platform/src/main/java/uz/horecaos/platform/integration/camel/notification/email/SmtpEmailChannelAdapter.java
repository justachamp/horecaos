package uz.horecaos.platform.integration.camel.notification.email;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.notification.AccountContext;
import uz.horecaos.platform.integration.camel.notification.AccountReadiness;
import uz.horecaos.platform.integration.camel.notification.NotificationChannelAdapter;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationDispatch.EmailEnvelope;

/**
 * A tenant's campaign email, sent through the platform's own mail relay (ADR 0182, D13; the
 * relay is ADR 0097's).
 *
 * <p><strong>Platform-owned.</strong> There is one SMTP account for the whole deployment, so
 * this adapter has no ADR 0026 installation or binding and {@link #platformOwned} says so: the
 * gateway calls it directly and asks it only whether the relay is configured. A tenant's
 * choice is who the mail is <em>from</em> (the brand's name over the one address) and whether a
 * customer consented, never which account carries it.
 *
 * <p><strong>Marketing only.</strong> The adapter carries {@link NotificationDispatch#PURPOSE_MARKETING}
 * and refuses anything else, so a transactional email cannot ride a channel whose unsubscribe,
 * cap and consent rules were decided for promotions.
 *
 * <p><strong>Honest about what SMTP says.</strong> A relay's acceptance is «accepted» and no more:
 * SMTP reports neither delivery nor a read, and bounces arrive later through a provider
 * webhook that is not built. A relay's permanent refusal of an <em>address</em> is a hard bounce;
 * every other refusal is not, because a misconfigured sender or a rejected body says nothing
 * about the customer. A connection lost after the message was on its way is uncertain and is
 * never repeated, since SMTP has no idempotency key and no way to ask what a server holds.
 *
 * <p>The recipient, the subject and the bodies never reach a log or an outcome's detail.
 */
@Component
public class SmtpEmailChannelAdapter implements NotificationChannelAdapter {

    /** The ADR 0026-shaped provider type recorded on the attempt, though no installation exists. */
    public static final String PROVIDER_TYPE = "PLATFORM_SMTP";

    /** The channel this adapter sends on. */
    public static final String CHANNEL = "EMAIL";

    /** What the attempt's provider status says: a relay took it. Not a delivery. */
    static final String PROVIDER_STATUS_ACCEPTED = "SMTP_ACCEPTED";

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+");

    private final PlatformMailer mailer;

    public SmtpEmailChannelAdapter(PlatformMailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean platformOwned() {
        return true;
    }

    @Override
    public AccountReadiness describeAccount(AccountContext account) {
        return mailer.configured()
                ? AccountReadiness.satisfied()
                : AccountReadiness.missing(List.of("horecaos.mail.smtp.host", "horecaos.mail.from"));
    }

    @Override
    public boolean permits(String purpose, AccountContext account) {
        return NotificationDispatch.PURPOSE_MARKETING.equals(purpose);
    }

    @Override
    public ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call) {
        if (!NotificationDispatch.PURPOSE_MARKETING.equals(dispatch.purpose())) {
            return ProviderOutcome.rejected(
                    "EMAIL_PURPOSE_NOT_PERMITTED", "The platform's email relay carries marketing messages only");
        }
        EmailEnvelope envelope = dispatch.email();
        if (envelope == null) {
            // A campaign email without its way out is not sent: it is the one thing a bulk sender
            // may never leave off.
            return ProviderOutcome.rejected("EMAIL_UNSUBSCRIBE_MISSING", "A campaign email carries an unsubscribe");
        }
        String subject = dispatch.subject();
        if (subject == null || subject.isBlank()) {
            return ProviderOutcome.rejected("EMAIL_SUBJECT_MISSING", "An email needs a subject");
        }

        OutgoingMail mail = new OutgoingMail(
                dispatch.recipientValue(),
                oneLine(subject),
                dispatch.body() + "\n\n-- \n" + envelope.footer(),
                html(dispatch.body(), envelope.footer()),
                envelope.senderName(),
                envelope.unsubscribeUrl());

        MailOutcome outcome = mailer.send(mail);
        return switch (outcome) {
            case MailOutcome.Sent ignored ->
                ProviderOutcome.success(
                        Map.of(PROVIDER_STATUS_KEY, PROVIDER_STATUS_ACCEPTED, NORMALIZED_STATUS_KEY, "ACCEPTED"), null);
            case MailOutcome.NotConfigured ignored ->
                ProviderOutcome.rejected("EMAIL_NOT_CONFIGURED", "The platform's email relay is not configured");
            case MailOutcome.Rejected rejected -> rejection(rejected.code());
            case MailOutcome.Failed failed ->
                MailOutcome.SMTP_NO_ANSWER.equals(failed.code())
                        ? ProviderOutcome.uncertain(
                                failed.code(), "The relay did not answer after the message was sent")
                        : ProviderOutcome.retryable(failed.code(), "The email relay could not be reached", null);
        };
    }

    /**
     * A relay that refuses an address for good has said the customer cannot be reached by this
     * address, which is a deliverability fact (ADR 0044); a refusal of anything else is a fault
     * of the message or the sender and must not suppress a customer.
     */
    private static ProviderOutcome rejection(String code) {
        boolean addressFault = "ADDRESS_REJECTED".equals(code) || "ADDRESS_INVALID".equals(code);
        return new ProviderOutcome(
                ProviderOutcome.Status.REJECTED,
                addressFault ? Map.of(HARD_BOUNCE_KEY, "true") : Map.of(),
                null,
                code,
                addressFault ? "The relay refused the address for good" : "The relay refused the message",
                null);
    }

    /**
     * SMTP has no query for what a server holds, so a message whose fate is unknown stays unknown.
     * Answering «no record» here would license a second send, which is the duplicate this adapter
     * exists not to produce.
     */
    @Override
    public ProviderOutcome queryStatus(String providerIdempotencyKey, ProviderCall call) {
        return ProviderOutcome.uncertain("EMAIL_NO_STATUS_QUERY", "SMTP offers no way to ask what a server holds");
    }

    /** A subject is one line: a line break in it would let a template author add headers. */
    private static String oneLine(String subject) {
        return subject.replaceAll("[\\r\\n]+", " ").strip();
    }

    /**
     * The same words as HTML: paragraphs for blank lines, a line break for a newline, and the
     * footer's address as a link. Everything is escaped first, so a template's text is text.
     */
    static String html(String body, String footer) {
        StringBuilder html = new StringBuilder("<!doctype html><html><body style=\"font-family:sans-serif\">");
        for (String paragraph : body.strip().split("\\R{2,}")) {
            html.append("<p>")
                    .append(escape(paragraph).replaceAll("\\R", "<br>"))
                    .append("</p>");
        }
        html.append("<hr><p style=\"color:#666;font-size:12px\">")
                .append(linked(footer))
                .append("</p></body></html>");
        return html.toString();
    }

    private static String linked(String footer) {
        Matcher matcher = URL.matcher(footer);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(escape(footer.substring(last, matcher.start())));
            String url = matcher.group();
            out.append("<a href=\"")
                    .append(escape(url))
                    .append("\">")
                    .append(escape(url))
                    .append("</a>");
            last = matcher.end();
        }
        out.append(escape(footer.substring(last)));
        return out.toString();
    }

    private static String escape(@Nullable String text) {
        return text == null
                ? ""
                : text.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;");
    }
}
