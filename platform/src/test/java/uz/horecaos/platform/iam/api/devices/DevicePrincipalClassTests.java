package uz.horecaos.platform.iam.api.devices;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR 0151: an approval may narrow what a device asked for and never widen it. */
class DevicePrincipalClassTests {

    @Test
    void aTouchRequestMayBeApprovedAsTouchOrAsAWallAndAWallRequestOnlyAsAWall() {
        assertThat(DevicePrincipalClass.KITCHEN_KDS.mayBeApprovedAs(DevicePrincipalClass.KITCHEN_KDS))
                .isTrue();
        assertThat(DevicePrincipalClass.KITCHEN_KDS.mayBeApprovedAs(DevicePrincipalClass.KITCHEN_VDU))
                .as("a tablet may be mounted as a wall: read-only is less authority, not more")
                .isTrue();
        assertThat(DevicePrincipalClass.KITCHEN_VDU.mayBeApprovedAs(DevicePrincipalClass.KITCHEN_VDU))
                .isTrue();
        assertThat(DevicePrincipalClass.KITCHEN_VDU.mayBeApprovedAs(DevicePrincipalClass.KITCHEN_KDS))
                .as("a TV that announced itself as read-only cannot become a control surface by an approval click")
                .isFalse();
    }

    @Test
    void aPrintAgentIsApprovedAsAPrintAgentAndAsNothingElseAndNothingIsApprovedAsOne() {
        // ADR 0154: the class decides which role a device is granted. An approval click must never hand a
        // display the agent's bundle, or an agent a display's.
        assertThat(DevicePrincipalClass.PRINT_AGENT.mayBeApprovedAs(DevicePrincipalClass.PRINT_AGENT))
                .isTrue();
        for (DevicePrincipalClass other : DevicePrincipalClass.values()) {
            if (other == DevicePrincipalClass.PRINT_AGENT) {
                continue;
            }
            assertThat(DevicePrincipalClass.PRINT_AGENT.mayBeApprovedAs(other))
                    .as("an agent cannot be approved as " + other)
                    .isFalse();
            assertThat(other.mayBeApprovedAs(DevicePrincipalClass.PRINT_AGENT))
                    .as(other + " cannot be approved as an agent")
                    .isFalse();
        }
    }

    @Test
    void everyClassMayBeApprovedAsItself() {
        for (DevicePrincipalClass deviceClass : DevicePrincipalClass.values()) {
            assertThat(deviceClass.mayBeApprovedAs(deviceClass))
                    .as(deviceClass.name())
                    .isTrue();
        }
    }
}
