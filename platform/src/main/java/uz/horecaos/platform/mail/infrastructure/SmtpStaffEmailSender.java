package uz.horecaos.platform.mail.infrastructure;

import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.mail.StaffEmail;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;

/**
 * {@code iam}'s outbound mail port (ADR 0098) over ADR 0097's one SMTP
 * connection.
 *
 * <p>Nothing but a translation, and it lives here rather than in {@code iam}
 * for the reason {@code iam.api.mail}'s own package note gives: {@code mail}
 * already depends on {@code iam} for its ADR 0028 password, so the adapter has
 * to sit on this side of the boundary or the two modules become cyclic.
 */
@Component
class SmtpStaffEmailSender implements StaffEmailSender {

    private final PlatformMailer mailer;

    SmtpStaffEmailSender(PlatformMailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public Delivery send(StaffEmail email) {
        MailOutcome outcome = mailer.send(new OutgoingMail(email.to(), email.subject(), email.text(), email.html()));
        return switch (outcome) {
            case MailOutcome.Sent ignored -> Delivery.SENT;
            case MailOutcome.NotConfigured ignored -> Delivery.NOT_CONFIGURED;
            case MailOutcome.Rejected rejected -> new Delivery(Status.REJECTED, rejected.code());
            case MailOutcome.Failed failed -> new Delivery(Status.FAILED, failed.code());
        };
    }

    @Override
    public boolean configured() {
        return mailer.configured();
    }
}
