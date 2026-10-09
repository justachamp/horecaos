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
    void everyClassMayBeApprovedAsItself() {
        for (DevicePrincipalClass deviceClass : DevicePrincipalClass.values()) {
            assertThat(deviceClass.mayBeApprovedAs(deviceClass))
                    .as(deviceClass.name())
                    .isTrue();
        }
    }
}
