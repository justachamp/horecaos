package uz.horecaos.platform.printing.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.ApproveEnrolment;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.PendingEnrolmentView;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.printing.application.PrintAudit.Subject;
import uz.horecaos.platform.printing.application.PrintJobQueue.AgentIdentity;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.AdvertRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Enrolling, listing and revoking print agents (ADR 0154, Decision 9; ADR 0079).
 *
 * <p>An agent is a device principal enrolled through ADR 0079's pairing handshake with {@code deviceClass =
 * PRINT_AGENT} and approved from the Printing screen by a holder of {@code print.manage} at the branch. The
 * role it is granted is {@code print-agent} and nothing else -- two capabilities, no read of any order -- and
 * is the fixed consequence of the approval, never a choice. {@code iam} holds the Keycloak client and the
 * enrolment bookkeeping; this holds the two decisions that are printing's: which role the class gets and the
 * audit fact naming the person who approved or revoked.
 *
 * <p>Also answers "who is this caller?" for the agent's own endpoints: the ACTIVE print agent whose Keycloak
 * subject made the request, from the row revocation changes -- so a revoked agent's token that is still valid
 * stops at its very next request.
 */
@Service
public class PrintAgentService {

    private final DeviceEnrolmentPort enrolment;
    private final JdbcPrintingStore store;
    private final TenantRlsSession rls;
    private final PrintAudit audit;

    public PrintAgentService(
            DeviceEnrolmentPort enrolment, JdbcPrintingStore store, TenantRlsSession rls, PrintAudit audit) {
        this.enrolment = enrolment;
        this.store = store;
        this.rls = rls;
        this.audit = audit;
    }

    /** One agent as the console lists it: its enrolment, the printers it advertises, and the rows bound to it. */
    public record AgentView(DevicePrincipalView device, List<AdvertRow> advertised, List<PrinterRow> boundPrinters) {}

    /**
     * The agent a request comes from: active, of class {@code PRINT_AGENT}, and enrolled at this very
     * branch. Refused with 403 for anyone else -- a staff token has no device row, a revoked agent's row is no
     * longer active, and an agent of another branch holds no grant here.
     */
    public AgentIdentity requireAgent(String subject, UUID tenantId, UUID brandId, UUID locationId) {
        DevicePrincipalView device = enrolment
                .activeDeviceOf(subject)
                .filter(found -> found.deviceClass() == DevicePrincipalClass.PRINT_AGENT)
                .filter(found -> found.tenantId().equals(tenantId)
                        && found.brandId().equals(brandId)
                        && found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INSUFFICIENT_CAPABILITY, "Only an enrolled print agent of this branch may do this"));
        return new AgentIdentity(tenantId, brandId, locationId, device.id());
    }

    /** What a pending code claims, so the approver sees it is an agent before approving. */
    public Optional<PendingEnrolmentView> pending(String userCode) {
        return enrolment.pendingEnrolment(userCode);
    }

    /**
     * Approves a pending enrolment as a print agent at this branch, granted exactly {@code print-agent}. A
     * code that asked to be anything else -- a kitchen display -- is refused with nothing written: the class an
     * approval names can narrow what a device asked for and never widen it, and an agent is not a narrowing of
     * a display.
     */
    @Transactional
    public DevicePrincipalView approve(
            UUID tenantId, UUID brandId, UUID locationId, String userCode, String displayName, String approver) {
        rls.bindTenant(tenantId);
        PendingEnrolmentView pending = enrolment
                .pendingEnrolment(userCode)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No pending enrolment with this code"));
        if (pending.requestedClass() != DevicePrincipalClass.PRINT_AGENT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That code was not asked for by a print agent");
        }
        DevicePrincipalView device = enrolment.approve(
                new ApproveEnrolment(
                        userCode,
                        ResourceScope.location(tenantId, brandId, locationId),
                        DevicePrincipalClass.PRINT_AGENT,
                        PlatformRole.PRINT_AGENT.code(),
                        displayName),
                approver);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deviceClass", device.deviceClass().name());
        after.put("displayName", displayName);
        audit.created(
                "printing.agent.enrolled",
                AuditClass.SECURITY,
                new Subject(
                        tenantId,
                        brandId,
                        locationId,
                        "iam.device_principal",
                        device.id(),
                        approver,
                        "ADR 0154 print agent enrolment: " + displayName),
                after);
        return device;
    }

    /**
     * Revokes an agent: its grant first (cache evicted at once, so its very next request is refused whatever
     * its token's remaining life), then its Keycloak client. Idempotent: revoking a revoked agent writes no
     * second fact and answers {@code false}.
     */
    @Transactional
    public boolean revoke(UUID tenantId, UUID brandId, UUID locationId, UUID deviceId, String reason, String revoker) {
        rls.bindTenant(tenantId);
        boolean here = enrolment.list(tenantId, locationId).stream()
                .anyMatch(device ->
                        device.id().equals(deviceId) && device.deviceClass() == DevicePrincipalClass.PRINT_AGENT);
        if (!here) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such print agent at this branch");
        }
        boolean revoked = enrolment.revoke(deviceId, revoker, reason);
        if (revoked) {
            audit.changed(
                    "printing.agent.revoked",
                    AuditClass.SECURITY,
                    new Subject(tenantId, brandId, locationId, "iam.device_principal", deviceId, revoker, reason),
                    Map.of("revoked", false),
                    Map.of("revoked", true));
        }
        return revoked;
    }

    /** The branch's print agents, active and revoked alike, with what each advertises and what is bound to it. */
    @Transactional(readOnly = true)
    public List<AgentView> list(UUID tenantId, UUID brandId, UUID locationId) {
        rls.bindTenant(tenantId);
        List<AdvertRow> adverts = store.listAdverts(tenantId, locationId);
        List<PrinterRow> printers = store.listPrinters(tenantId, locationId);
        return enrolment.list(tenantId, locationId).stream()
                .filter(device -> device.deviceClass() == DevicePrincipalClass.PRINT_AGENT)
                .filter(device -> device.brandId().equals(brandId))
                .map(device -> new AgentView(
                        device,
                        adverts.stream()
                                .filter(advert -> advert.agentDeviceId().equals(device.id()))
                                .toList(),
                        printers.stream()
                                .filter(printer -> device.id().equals(printer.agentDeviceId()))
                                .toList()))
                .toList();
    }
}
