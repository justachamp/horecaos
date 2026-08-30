package uz.horecaos.platform.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ApprovalAction.MissingPolicyMode;
import uz.horecaos.platform.iam.api.ResourceScope;

class ApprovalActionTests {

    @Test
    void everyShippedApprovalActionHasAnExplicitMissingPolicyMode() {
        assertThat(ApprovalAction.values())
                .allSatisfy(action -> assertThat(action.missingPolicyMode()).isNotNull());
        assertThat(ApprovalAction.COURIER_MANUAL_PENALTY.missingPolicyMode())
                .isEqualTo(MissingPolicyMode.REQUIRE_CONFIGURED_POLICY);
    }

    @Test
    void anUnknownActionCannotSilentlyInheritThePermissiveDefault() {
        assertThatThrownBy(() -> new ApprovalRequestCommand(
                        "payments.remedy.teleport",
                        "a".repeat(64),
                        ResourceScope.tenant(UUID.randomUUID()),
                        ActorRef.user("operator", null),
                        "A test action",
                        ApprovalRequestCommand.DEFAULT_VALIDITY))
                .isInstanceOf(ApprovalAction.UnknownApprovalActionException.class);
    }
}
