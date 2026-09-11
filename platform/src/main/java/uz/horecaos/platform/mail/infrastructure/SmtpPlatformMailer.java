package uz.horecaos.platform.mail.infrastructure;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import java.util.Locale;
import java.util.Properties;
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

    private final SecretResolver secrets;
    private final String host;
    private final int port;
    private final String security;
    private final String username;
    private final String passwordReference;
    private final String from;

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
        if (!this.security.equals("STARTTLS") && !this.security.equals("TLS") && !this.security.equals("NONE")) {
            throw new IllegalStateException("horecaos.mail.smtp.security is STARTTLS, TLS or NONE");
        }
        if (!this.username.isEmpty() && !this.passwordReference.isEmpty()) {
            // Parsed now so a malformed reference stops startup, not the first invitation.
            SecretReference.parse(this.passwordReference);
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
            helper.setFrom(from);
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), mail.html());
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
            if (refused.getFailedMessages().values().stream().anyMatch(SmtpPlatformMailer::addressRefused)) {
                return new MailOutcome.Rejected("ADDRESS_REJECTED");
            }
            log.warn(
                    "The SMTP server did not accept an email ({})",
                    refused.getClass().getSimpleName());
            return new MailOutcome.Failed("SMTP_UNAVAILABLE");
        } catch (MailException unreachable) {
            log.warn(
                    "The SMTP server could not be reached ({})",
                    unreachable.getClass().getSimpleName());
            return new MailOutcome.Failed("SMTP_UNAVAILABLE");
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
