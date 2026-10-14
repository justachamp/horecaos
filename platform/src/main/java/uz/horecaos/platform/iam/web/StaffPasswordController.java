package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender;
import uz.horecaos.platform.iam.api.staff.StaffSelfAuthorized;

/**
 * What «Мой профиль» needs to know before it hands a person to the password reset (ADR 0098,
 * gap-map row {@code X.5}): whether this platform can send the email the reset arrives by.
 *
 * <p>The reset request itself answers 202 and nothing else, always, so that it cannot be used to
 * ask who works here. That is right for a stranger and wrong for a signed-in person: told "an
 * email is on its way" by a platform that has no mail server, she waits for a message that
 * cannot come. This is the one question that can be answered for her without telling anyone else
 * anything, because it is a fact about the platform and not about any account.
 *
 * <p>Authorised like the rest of {@code staff/me/*}: {@link StaffSelfAuthorized}, no account id
 * in any handler. It reveals no address, no sign-in name and nothing about the caller.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/staff/me/password")
@Tag(name = "My password", description = "Whether the password reset can reach the caller by email (ADR 0098)")
public class StaffPasswordController {

    private final StaffEmailSender mail;

    public StaffPasswordController(StaffEmailSender mail) {
        this.mail = mail;
    }

    @GetMapping
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Operation(
            summary = "Can a password reset reach me by email",
            description = "emailConfigured is false when this platform has no mail server set up, so a reset "
                    + "link would never arrive. The reset itself is Keycloak-backed and unchanged: it answers "
                    + "202 whatever the account, and ends every sign-in the account holds once it is used.")
    public PasswordHandoffResponse handoff(@PathVariable UUID tenantId) {
        return new PasswordHandoffResponse(mail.configured());
    }

    public record PasswordHandoffResponse(boolean emailConfigured) {}
}
