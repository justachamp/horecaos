package uz.horecaos.platform.mail.infrastructure;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretResolver.SecretNotFoundException;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;

/**
 * SMTP submission to a hosted sending provider (ADR 0097).
 *
 * <p>Configured only from {@code horecaos.mail.*}, never Spring's own
 * {@code spring.mail.*}: the password is an ADR 0028 reference, resolved from
 * the secrets manager on each send (the resolver caches it for minutes), so a
 * rotated password takes effect without a restart and no value sits in
 * configuration. With no host set this answers {@link MailOutcome.NotConfigured}
 * rather than failing, so a caller can tell "nothing to send with" from "the
 * provider is down".
 *
 * <p>Nothing about a message is logged: not the recipient, not the subject,
 * not the body, which carries a one-time link. A failure is logged by its
 * class and the code this returns.
 */
@Component
public class SmtpPlatformMailer implements PlatformMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpPlatformMailer.class);

    private static final int TIMEOUT_MILLIS = 10_000;

    private static final String SMTP_UNAVAILABLE = "SMTP_UNAVAILABLE";

    private final SecretResolver secrets;
    private final String host;
    private final int port;
    private final String security;
    private final String username;
    private final String passwordReference;
    private final String from;
    private final @Nullable InternetAddress configuredFrom;

    public SmtpPlatformMailer(
            SecretResolver secrets,
            @Value("${horecaos.mail.smtp.host:}") String host,
            @Value("${horecaos.mail.smtp.port:587}") int port,
            @Value("${horecaos.mail.smtp.security:STARTTLS}") String security,
            @Value("${horecaos.mail.smtp.username:}") String username,
            @Value("${horecaos.mail.smtp.password-reference:}") String passwordReference,
            @Value("${horecaos.mail.from:}") String from) {
        this.secrets = secrets;
        this.host = host.strip();
        this.port = port;
        this.security = security.strip().toUpperCase(Locale.ROOT);
        this.username = username.strip();
        this.passwordReference = passwordReference.strip();
        this.from = from.strip();
        this.configuredFrom = parseSender(this.from);
        if (!this.security.equals("STARTTLS") && !this.security.equals("TLS") && !this.security.equals("NONE")) {
            throw new IllegalStateException("horecaos.mail.smtp.security is STARTTLS, TLS or NONE");
        }
        if (!this.username.isEmpty() && !this.passwordReference.isEmpty()) {
            // Parsed now so a malformed reference stops startup, not the first invitation.
            SecretReference.parse(this.passwordReference);
        }
    }

    /**
     * The configured sender, parsed once. A sender that is not an address stops startup rather than
     * surfacing later as «the recipient is invalid» on every message, which a campaign would read as
     * a hard bounce for each customer and suppress them all.
     */
    private static @Nullable InternetAddress parseSender(String from) {
        if (from.isEmpty()) {
            return null;
        }
        try {
            return InternetAddress.parse(from)[0];
        } catch (AddressException | RuntimeException notAnAddress) {
            throw new IllegalStateException("horecaos.mail.from is not an email address");
        }
    }

    @Override
    public boolean configured() {
        return !host.isEmpty() && !from.isEmpty() && (username.isEmpty() || !passwordReference.isEmpty());
    }

    @Override
    public MailOutcome send(OutgoingMail mail) {
        if (!configured()) {
            return new MailOutcome.NotConfigured();
        }
        try {
            JavaMailSenderImpl sender = sender();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(senderAddress(mail));
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), mail.html());
            if (mail.unsubscribeUrl() != null) {
                // RFC 8058: the URL in the header, and the announcement that a POST to it is
                // enough, which is what lets a mail client's own unsubscribe button work
                // without a person opening a page. Without it the same button files a spam
                // complaint, and the complaint lands on the one sender every tenant shares.
                message.setHeader("List-Unsubscribe", "<" + mail.unsubscribeUrl() + ">");
                message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
                message.setHeader("Precedence", "bulk");
            }
            sender.send(message);
            return new MailOutcome.Sent();
        } catch (AddressException invalid) {
            return new MailOutcome.Rejected("ADDRESS_INVALID");
        } catch (MessagingException unbuildable) {
            log.warn(
                    "An email could not be composed ({})",
                    unbuildable.getClass().getSimpleName());
            return new MailOutcome.Rejected("MESSAGE_INVALID");
        } catch (SecretNotFoundException missing) {
            log.warn("The SMTP password reference resolves to nothing in the secrets manager");
            return new MailOutcome.Failed("SMTP_SECRET_MISSING");
        } catch (MailAuthenticationException refused) {
            log.warn("The SMTP server refused the platform's credentials");
            return new MailOutcome.Failed("SMTP_AUTHENTICATION");
        } catch (MailSendException refused) {
            MailOutcome outcome = outcomeOf(refused);
            if (outcome instanceof MailOutcome.Failed failed) {
                log.warn(
                        "The SMTP server did not accept an email ({}, {})",
                        refused.getClass().getSimpleName(),
                        failed.code());
            }
            return outcome;
        } catch (MailException unreachable) {
            // Not a send failure: Spring raises the others while it parses or prepares a message,
            // before a connection is opened, so nothing can have been sent.
            log.warn(
                    "The SMTP server could not be reached ({})",
                    unreachable.getClass().getSimpleName());
            return new MailOutcome.Failed(SMTP_UNAVAILABLE);
        }
    }

    /**
     * What a send that Spring reports as a {@link MailSendException} amounts to, decided by the phase
     * of the SMTP session the failure belongs to, because that is what decides whether the message
     * may be sent again.
     *
     * <p>Spring puts the real exception of a failed send in {@link MailSendException#getFailedMessages()}
     * and gives the wrapper no cause, so the wrapper's own chain says nothing about a send; only the
     * failed message's exception does. In the order a session runs:
     *
     * <ol>
     *   <li><strong>The connection phase</strong> (connect, greeting, EHLO, STARTTLS, AUTH) is the one
     *       failure Spring wraps with a cause, and the cause is the very exception it files under the
     *       message. Nothing was transmitted, however the connection died: {@code SMTP_UNAVAILABLE}.
     *   <li><strong>Before the data went</strong> (MAIL, RCPT, DATA): a server that closes the line
     *       reports it as a reply with no code and the command it did not answer, and that command
     *       places it. A server that never took the data has accepted nothing: {@code SMTP_UNAVAILABLE}.
     *   <li><strong>After the data terminator</strong>, or anywhere the exception names no command (a
     *       reset or a read timeout surfaces as an I/O error with nothing to say where): the server may
     *       have queued the message, so {@code SMTP_NO_ANSWER}. A reset in the middle of a session is
     *       deliberately in this class: the cost of that reading is one lost send, and the cost of the
     *       other is a duplicate that a caller who must not send twice has promised not to make.
     *   <li><strong>A clear reply</strong> (a 4xx or 5xx to the data) means the server said no:
     *       {@code SMTP_UNAVAILABLE}.
     *   <li><strong>No failed message at all</strong> means every message got its final reply and what
     *       failed was the goodbye (Spring's «failed to close server connection after message
     *       sending»): the message is {@code Sent}, whatever the connection did afterwards.
     * </ol>
     */
    static MailOutcome outcomeOf(MailSendException failure) {
        Map<Object, Exception> failed = failure.getFailedMessages();
        if (failed.isEmpty()) {
            return new MailOutcome.Sent();
        }
        if (failed.values().stream().anyMatch(SmtpPlatformMailer::addressRefused)) {
            return new MailOutcome.Rejected("ADDRESS_REJECTED");
        }
        Throwable connection = failure.getCause();
        if (connection != null && failed.containsValue(connection)) {
            return new MailOutcome.Failed(SMTP_UNAVAILABLE);
        }
        return new MailOutcome.Failed(
                failed.values().stream().anyMatch(SmtpPlatformMailer::mayHaveBeenQueued)
                        ? MailOutcome.SMTP_NO_ANSWER
                        : SMTP_UNAVAILABLE);
    }

    /**
     * Whether a failure while a message was being sent leaves the server possibly holding it: the
     * line was lost with nothing to place the loss before the data (a reset, a timeout, a broken
     * pipe, a closed stream), or it was lost on the command that ends the data.
     */
    private static boolean mayHaveBeenQueued(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SMTPSendFailedException unanswered && unanswered.getReturnCode() == -1) {
                return ".".equals(unanswered.getCommand());
            }
            if (cause instanceof SMTPAddressFailedException unanswered && unanswered.getReturnCode() == -1) {
                return false;
            }
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code From} address: always the configured one, with the display name of the message when
     * it has one (a tenant's brand, ADR 0182). The address is never taken from a message, so the
     * name a tenant chooses can never change who the mail claims to come from.
     */
    private InternetAddress senderAddress(OutgoingMail mail) throws MessagingException {
        InternetAddress configured = Objects.requireNonNull(configuredFrom, "send() runs only when configured");
        if (mail.senderName() == null) {
            return configured;
        }
        try {
            return new InternetAddress(configured.getAddress(), mail.senderName(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new MessagingException("UTF-8 is not available", impossible);
        }
    }

    private static boolean addressRefused(Exception failure) {
        return failure instanceof SendFailedException sendFailed
                && sendFailed.getInvalidAddresses() != null
                && sendFailed.getInvalidAddresses().length > 0;
    }

    private JavaMailSenderImpl sender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", security.equals("TLS") ? "smtps" : "smtp");
        properties.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        properties.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        properties.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        properties.put("mail.smtps.connectiontimeout", TIMEOUT_MILLIS);
        properties.put("mail.smtps.timeout", TIMEOUT_MILLIS);
        properties.put("mail.smtps.writetimeout", TIMEOUT_MILLIS);
        if (security.equals("STARTTLS")) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        } else if (security.equals("TLS")) {
            properties.put("mail.smtps.ssl.checkserveridentity", "true");
        }
        if (!username.isEmpty()) {
            sender.setUsername(username);
            sender.setPassword(
                    secrets.resolve(SecretReference.parse(passwordReference)).reveal());
            properties.put(security.equals("TLS") ? "mail.smtps.auth" : "mail.smtp.auth", "true");
        }
        if (security.equals("TLS")) {
            sender.setProtocol("smtps");
        }
        return sender;
    }
}
