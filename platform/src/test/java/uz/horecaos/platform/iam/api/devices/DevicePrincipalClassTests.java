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

    @Test
    void aKioskIsApprovedAsAKioskAndNothingElseAndNoOtherClassBecomesAKiosk() {
        assertThat(DevicePrincipalClass.KIOSK.mayBeApprovedAs(DevicePrincipalClass.KIOSK))
                .isTrue();
        assertThat(DevicePrincipalClass.KIOSK.mayBeApprovedAs(DevicePrincipalClass.KITCHEN_KDS))
                .as("a customer-facing device cannot be approved as a kitchen control surface")
                .isFalse();
        assertThat(DevicePrincipalClass.KIOSK.mayBeApprovedAs(DevicePrincipalClass.KITCHEN_VDU))
                .as("ADR 0162 narrows nothing: the kiosk is its own family, not a lesser kitchen display")
                .isFalse();
        for (DevicePrincipalClass other :
                new DevicePrincipalClass[] {DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_VDU}) {
            assertThat(other.mayBeApprovedAs(DevicePrincipalClass.KIOSK))
                    .as("%s must never be approved as a kiosk", other)
                    .isFalse();
        }
    }

    @Test
    void onlyTheTouchKitchenDisplayIsUnlockableByAPerson() {
        assertThat(DevicePrincipalClass.KITCHEN_KDS.unlockable()).isTrue();
        assertThat(DevicePrincipalClass.KITCHEN_VDU.unlockable())
                .as("a wall has no controls")
                .isFalse();
        assertThat(DevicePrincipalClass.KIOSK.unlockable())
                .as("a kiosk is touched by customers; a customer is never a staff identity (ADR 0162)")
                .isFalse();
    }
}
