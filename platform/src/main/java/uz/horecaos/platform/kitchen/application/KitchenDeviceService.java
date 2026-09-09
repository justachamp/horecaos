package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.ApproveEnrolment;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;

/**
 * The kitchen half of ADR 0079's device enrolment: the human-approved side,
 * gated by {@code kitchen.station.manage} on the calling controller, and the
 * ADR 0027 audit trail every enrolment and revocation leaves.
 *
 * <p>This class holds no Keycloak knowledge and no enrolment-request
 * bookkeeping — both live in {@code iam}, behind {@link DeviceEnrolmentPort}.
 * What it owns is the two decisions that are specifically a kitchen's to
 * make: which role a device is granted ({@link PlatformRole#KITCHEN_DEVICE},
 * hard-coded rather than caller-supplied — see {@link #approve}) and the
 * audit fact naming the human who approved or revoked one.
 */
@Service
public class KitchenDeviceService {

    private final DeviceEnrolmentPort enrolment;
    private final AuditRecorder audit;
    private final Clock clock;

    public KitchenDeviceService(DeviceEnrolmentPort enrolment, AuditRecorder audit, Clock clock) {
        this.enrolment = enrolment;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Approves a pending enrolment as a {@link DevicePrincipalClass#KITCHEN_KDS}
     * device, granted exactly {@link PlatformRole#KITCHEN_DEVICE} at exactly
     * this branch's {@code LOCATION} scope. The role is never a parameter:
     * every device this method enrols becomes the identical, narrow thing, on
     * purpose — an open choice here is exactly the "a device that can do what
     * a manager can do" failure ADR 0079 exists to end.
     */
    @Transactional
    public DevicePrincipalView approve(
            UUID tenantId, UUID brandId, UUID locationId, String userCode, String displayName, String approverSubject) {

        DevicePrincipalView device = enrolment.approve(
                new ApproveEnrolment(
                        userCode,
                        ResourceScope.location(tenantId, brandId, locationId),
                        PlatformRole.KITCHEN_DEVICE.code(),
                        displayName),
                approverSubject);

        Instant now = clock.instant();
        audit.record(AuditFact.of("kitchen.device.enrolled", AuditClass.SECURITY)
                .by(ActorRef.user(approverSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("iam.device_principal", device.id())
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because("ADR 0079 kitchen display device enrolment: " + displayName)
                .changed(Map.of("deviceClass", device.deviceClass().name(), "displayName", displayName))
                .correlatedBy(device.id().toString())
                .occurredAt(now)
                .build());

        return device;
    }

    public List<DevicePrincipalView> list(UUID tenantId, UUID locationId) {
        return enrolment.list(tenantId, locationId);
    }

    /**
     * Revokes a device. Idempotent — revoking an already-revoked device
     * writes no second audit fact and returns {@code false} — because a
     * manager who presses revoke twice on a screen that already went dark is
     * not raising a second security event.
     */
    @Transactional
    public boolean revoke(
            UUID tenantId, UUID brandId, UUID locationId, UUID deviceId, String reason, String revokerSubject) {
        boolean revoked = enrolment.revoke(deviceId, revokerSubject, reason);
        if (revoked) {
            audit.record(AuditFact.of("kitchen.device.revoked", AuditClass.SECURITY)
                    .by(ActorRef.user(revokerSubject, null))
                    .at(ResourceScope.location(tenantId, brandId, locationId))
                    .target("iam.device_principal", deviceId)
                    .outcome(AuditFact.Outcome.SUCCEEDED)
                    .because(reason)
                    .changed(Map.of())
                    .correlatedBy(deviceId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        return revoked;
    }
}
