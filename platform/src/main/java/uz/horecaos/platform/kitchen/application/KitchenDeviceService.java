package uz.horecaos.platform.kitchen.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
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
 * What it owns is the decision that is specifically a kitchen's to
 * make: which classes are kitchen displays ({@link #KITCHEN_CLASSES}) and the audit
 * fact naming the human who approved or revoked one. The role a class is granted is not
 * the kitchen's to name (ADR 0162): {@code iam} derives it from the approved class.
 *
 * <p>ADR 0151 added the second class: a wall display, {@link
 * DevicePrincipalClass#KITCHEN_VDU}, granted the wall's own role and nothing else. The approver chooses the
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
     * The device classes this module approves, lists and revokes: the two kitchen displays. A kiosk is the
     * tenancy module's (ADR 0162), and the kitchen's endpoints, behind {@code kitchen.station.manage}, must
     * not be a way to approve, list or revoke one: a customer-facing device is attached by the person who
     * holds {@code kiosk.device.manage}, and that is not the head chef.
     */
    static final Set<DevicePrincipalClass> KITCHEN_CLASSES =
            Set.copyOf(EnumSet.of(DevicePrincipalClass.KITCHEN_KDS, DevicePrincipalClass.KITCHEN_VDU));

    /**
     * What a pending code claims, so the approver sees the class it asked for before choosing one. A code
     * that asked to be anything but a kitchen display is the same 404 as an unknown one: it is not the
     * kitchen's to describe (ADR 0162), and a different answer would tell a kitchen manager which codes a
     * kiosk is showing.
     */
    public Optional<PendingEnrolmentView> pendingEnrolment(String userCode, String approverSubject) {
        return enrolment
                .peek(userCode, approverSubject)
                .filter(pending -> KITCHEN_CLASSES.contains(pending.requestedClass()));
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

        // The class the device asked for, read once: both the "absent means as it asked" default and the
        // ownership check below need it, and a code that asked for another module's class is the kitchen's
        // to refuse by name rather than to approve with its own role (ADR 0162).
        DevicePrincipalClass requestedClass = enrolment
                .pendingEnrolment(userCode)
                .map(PendingEnrolmentView::requestedClass)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No pending enrolment with this code"));
        // Absent means "as it asked": a client from before ADR 0151 sends no class and keeps its meaning.
        DevicePrincipalClass approvedClass = requestedApproval != null ? requestedApproval : requestedClass;
        if (!KITCHEN_CLASSES.contains(requestedClass) || !KITCHEN_CLASSES.contains(approvedClass)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A device that asked to be %s cannot be approved as %s from the kitchen"
                            .formatted(requestedClass, approvedClass),
                    Map.of("requestedClass", requestedClass.name(), "approvedClass", approvedClass.name()));
        }

        DevicePrincipalView device = enrolment.approve(
                new ApproveEnrolment(
                        userCode, ResourceScope.location(tenantId, brandId, locationId), approvedClass, displayName),
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

    /** The branch's kitchen displays; a kiosk or any other class at the branch is not listed here (ADR 0162). */
    public List<DevicePrincipalView> list(UUID tenantId, UUID locationId) {
        return enrolment.list(tenantId, locationId).stream()
                .filter(device -> KITCHEN_CLASSES.contains(device.deviceClass()))
                .toList();
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
