package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender;
import uz.horecaos.platform.iam.api.staff.StaffSelfAuthorized;

/**
 * The one fact the password card needs: can the platform send the reset email. The route's
 * authorization is the self-service kind (no account id in the handler), which {@code
 * EndpointCapabilityDeclarationTests} also holds the whole API to.
 */
class StaffPasswordControllerTests {

    private final StaffEmailSender mail = mock(StaffEmailSender.class);
    private final StaffPasswordController controller = new StaffPasswordController(mail);

    @Test
    @DisplayName("it answers what the mail sender says: configured")
    void configured() {
        when(mail.configured()).thenReturn(true);

        assertThat(controller.handoff(UUID.randomUUID()).emailConfigured()).isTrue();
    }

    @Test
    @DisplayName("and when no mail server is set up, it says so rather than promising an email")
    void notConfigured() {
        when(mail.configured()).thenReturn(false);

        assertThat(controller.handoff(UUID.randomUUID()).emailConfigured()).isFalse();
    }

    @Test
    @DisplayName("it is a self-service read: a capability held at any scope, and the handler takes no account id")
    void isSelfServiceAndTakesNoAccountId() throws Exception {
        Method handler = StaffPasswordController.class.getMethod("handoff", UUID.class);

        assertThat(handler.getAnnotation(StaffSelfAuthorized.class).value()).isEqualTo(Capability.STAFF_SELF_MANAGE);
        assertThat(handler.getParameterTypes()).containsExactly(UUID.class);
    }
}
