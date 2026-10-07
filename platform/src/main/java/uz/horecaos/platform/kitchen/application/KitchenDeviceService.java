package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.ApproveEnrolment;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.PendingEnrolmentView;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The kitchen half of ADR 0079's device enrolment: the human-approved side,
 * gated by {@code kitchen.station.manage} on the calling controller, and the
 * ADR 0027 audit trail every enrolment and revocation leaves.
 *
 * <p>This class holds no Keycloak knowledge and no enrolment-request
 * bookkeeping — both live in {@code iam}, behind {@link DeviceEnrolmentPort}.
 * What it owns is the two decisions that are specifically a kitchen's to
 * make: which role each device class is granted (a closed map, {@link
 * #roleOf}, never a parameter) and the audit fact naming the human who
 * approved or revoked one.
 *
 * <p>ADR 0151 added the second class: a wall display, {@link
 * DevicePrincipalClass#KITCHEN_VDU}, granted {@link
 * PlatformRole#KITCHEN_VDU_DEVICE} and nothing else. The approver chooses the
 * class, and the choice can only narrow what the device asked for (a touch
 * display may be approved as a wall, a wall may not be approved as a touch
 * display) — {@code iam} enforces that, so no kitchen caller can widen a request.
 */
@Service
public class KitchenDeviceService {

    private final DeviceEnrolmentPort enrolment;
    private final KitchenDeviceDisplayService displays;
    private final AuditRecorder audit;
    private final Clock clock;

    public KitchenDeviceService(
            DeviceEnrolmentPort enrolment, KitchenDeviceDisplayService displays, AuditRecorder audit, Clock clock) {
        this.enrolment = enrolment;
        this.displays = displays;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The role each device class is granted: exactly one, and the same for every device of the class.
     * A closed, code-owned map (ADR 0079): an open choice here is the "a device that can do what a
     * manager can do" failure that record exists to end.
     */
    static PlatformRole roleOf(DevicePrincipalClass deviceClass) {
        return switch (deviceClass) {
            case KITCHEN_KDS -> PlatformRole.KITCHEN_DEVICE;
            case KITCHEN_VDU -> PlatformRole.KITCHEN_VDU_DEVICE;
        };
    }

    /** What a pending code claims, so the approver sees the class it asked for before choosing one. */
    public Optional<PendingEnrolmentView> pendingEnrolment(String userCode) {
        return enrolment.pendingEnrolment(userCode);
    }

    /**
     * Approves a pending enrolment as {@code approvedClass}, granted exactly {@link #roleOf} of that
     * class at exactly this branch's {@code LOCATION} scope. The role is never a parameter: every device
     * of a class becomes the identical, narrow thing, on purpose.
     *
     * <p>The class an approval names (absent: the one the device asked for) may narrow what the device
     * asked for and never widen it (ADR 0151); a refusal leaves nothing behind. Approving a wall display also gives it its display
     * configuration (the whole branch until a manager picks a station), in the same transaction.
     * {@code kitchen.device.enrolled} records the class requested and the class approved.
     */
    @Transactional
    public DevicePrincipalView approve(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String userCode,
            String displayName,
            @Nullable DevicePrincipalClass requestedApproval,
            String approverSubject) {

        // Absent means "as it asked": a client from before ADR 0151 sends no class and keeps its meaning.
        DevicePrincipalClass approvedClass = requestedApproval != null
                ? requestedApproval
                : enrolment
                        .pendingEnrolment(userCode)
                        .map(PendingEnrolmentView::requestedClass)
                        .orElseThrow(() ->
                                new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No pending enrolment with this code"));

        DevicePrincipalView device = enrolment.approve(
                new ApproveEnrolment(
                        userCode,
                        ResourceScope.location(tenantId, brandId, locationId),
                        approvedClass,
                        roleOf(approvedClass).code(),
                        displayName),
                approverSubject);
        displays.provision(device);

        Instant now = clock.instant();
        audit.record(AuditFact.of("kitchen.device.enrolled", AuditClass.SECURITY)
                .by(ActorRef.user(approverSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("iam.device_principal", device.id())
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because("ADR 0079/0151 kitchen device enrolment: " + displayName)
                .changed(ChangeDocuments.created(Map.of(
                        "deviceClass", device.deviceClass().name(),
                        "requestedClass", device.requestedClass().name(),
                        "displayName", displayName)))
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
                    .changed(ChangeDocuments.change("revoked", false, true))
                    .correlatedBy(deviceId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        return revoked;
    }
}
