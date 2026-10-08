package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.printing.application.PrintAudit.Subject;
import uz.horecaos.platform.printing.domain.TapeWidth;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A branch's printers (ADR 0154, Decisions 4 and 6): registering one, changing its name, roll or binding,
 * and retiring it.
 *
 * <p>A printer belongs to a location and carries a roll of 58 or 80 mm. It is reached either by a print
 * agent -- bound to an alias the agent itself advertises, never to an address a manager typed, because the
 * platform cannot make a branch machine open a connection -- or through a browser at a workstation. The
 * database refuses an agent enrolled at another branch; this refuses the cases it can name properly before
 * the database has to.
 */
@Service
public class PrinterService {

    private final TenantRlsSession rls;
    private final JdbcPrintingStore store;
    private final DeviceEnrolmentPort devices;
    private final PrintingGuard guard;
    private final PrintAudit audit;
    private final Clock clock;

    public PrinterService(
            TenantRlsSession rls,
            JdbcPrintingStore store,
            DeviceEnrolmentPort devices,
            PrintingGuard guard,
            PrintAudit audit,
            Clock clock) {
        this.rls = rls;
        this.store = store;
        this.devices = devices;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * What a printer is registered with. {@code agentDeviceId} and {@code agentPrinterKey} both present is an
     * agent printer; both absent is a browser printer; one without the other is refused.
     */
    public record PrinterDraft(
            String displayName,
            int tapeWidthMm,
            @Nullable UUID agentDeviceId,
            @Nullable String agentPrinterKey) {}

    @Transactional(readOnly = true)
    public List<PrinterRow> list(UUID tenantId, UUID locationId) {
        rls.bindTenant(tenantId);
        return store.listPrinters(tenantId, locationId);
    }

    @Transactional(readOnly = true)
    public Optional<PrinterRow> find(UUID tenantId, UUID locationId, UUID printerId) {
        rls.bindTenant(tenantId);
        return store.findPrinter(tenantId, printerId)
                .filter(printer -> printer.locationId().equals(locationId));
    }

    @Transactional
    public PrinterRow register(UUID tenantId, UUID brandId, UUID locationId, PrinterDraft draft, String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabled(tenantId, brandId, locationId);
        validate(tenantId, locationId, draft);
        Instant now = clock.instant();
        boolean agent = draft.agentDeviceId() != null;
        PrinterRow printer = new PrinterRow(
                Ids.newId(),
                tenantId,
                brandId,
                locationId,
                draft.displayName().strip(),
                draft.tapeWidthMm(),
                draft.agentDeviceId(),
                draft.agentPrinterKey(),
                agent ? "AGENT" : "BROWSER",
                "ACTIVE",
                null,
                null,
                1,
                now,
                now);
        try {
            store.insertPrinter(printer);
        } catch (DataIntegrityViolationException refused) {
            throw conflict(refused);
        }
        audit.created(
                "printing.printer.registered",
                AuditClass.BUSINESS,
                new Subject(
                        tenantId,
                        brandId,
                        locationId,
                        "printing.printer",
                        printer.id(),
                        actorSubject,
                        "Printer registered"),
                snapshot(printer));
        return printer;
    }

    /**
     * Changes a printer under the version the form was opened at. Setting {@code status} to {@code RETIRED}
     * retires it (its routes stop printing, its jobs stay); {@code ACTIVE} brings it back.
     */
    @Transactional
    public PrinterRow update(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID printerId,
            PrinterDraft draft,
            String status,
            long expectedVersion,
            String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabled(tenantId, brandId, locationId);
        PrinterRow before = find(tenantId, locationId, printerId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such printer"));
        if (!"ACTIVE".equals(status) && !"RETIRED".equals(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A printer's status is ACTIVE or RETIRED");
        }
        validate(tenantId, locationId, draft);
        if (before.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        Instant now = clock.instant();
        boolean agent = draft.agentDeviceId() != null;
        Optional<Integer> version;
        try {
            version = store.updatePrinter(
                    tenantId,
                    printerId,
                    draft.displayName().strip(),
                    draft.tapeWidthMm(),
                    draft.agentDeviceId(),
                    draft.agentPrinterKey(),
                    agent ? "AGENT" : "BROWSER",
                    status,
                    (int) expectedVersion,
                    now);
        } catch (DataIntegrityViolationException refused) {
            throw conflict(refused);
        }
        if (version.isEmpty()) {
            throw ApiException.staleVersion(
                    expectedVersion,
                    store.findPrinter(tenantId, printerId)
                            .map(PrinterRow::version)
                            .orElse(0));
        }
        PrinterRow after = store.findPrinter(tenantId, printerId).orElseThrow();
        String action =
                before.active() && "RETIRED".equals(status) ? "printing.printer.retired" : "printing.printer.changed";
        String changeReason = "printing.printer.retired".equals(action) ? "Printer retired" : "Printer changed";
        audit.changed(
                action,
                AuditClass.BUSINESS,
                new Subject(tenantId, brandId, locationId, "printing.printer", printerId, actorSubject, changeReason),
                snapshot(before),
                snapshot(after));
        return after;
    }

    private void validate(UUID tenantId, UUID locationId, PrinterDraft draft) {
        String name = draft.displayName() == null ? "" : draft.displayName().strip();
        if (name.isEmpty() || name.length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A printer needs a name of up to 120 characters");
        }
        if (TapeWidth.ofMillimetres(draft.tapeWidthMm()).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A roll is 58 or 80 mm wide");
        }
        UUID agentDevice = draft.agentDeviceId();
        String agentKey = draft.agentPrinterKey();
        boolean device = agentDevice != null;
        boolean key = agentKey != null && !agentKey.isBlank();
        if (device != key) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "An agent printer names both the agent and the alias the agent advertises; a browser printer names neither");
        }
        if (agentDevice == null || agentKey == null) {
            return;
        }
        if (agentKey.length() > 64) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An alias is at most 64 characters");
        }
        boolean enrolledHere = devices.list(tenantId, locationId).stream()
                .anyMatch(found -> found.id().equals(agentDevice)
                        && found.deviceClass() == DevicePrincipalClass.PRINT_AGENT
                        && "ACTIVE".equals(found.status()));
        if (!enrolledHere) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That agent is not an active print agent of this branch",
                    Map.of("reason", "AGENT_NOT_AT_BRANCH"));
        }
        if (store.findAdvert(tenantId, agentDevice, agentKey).isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That agent has not advertised a printer with that alias",
                    Map.of("reason", "ALIAS_NOT_ADVERTISED"));
        }
    }

    private static ApiException conflict(DataIntegrityViolationException refused) {
        String detail = String.valueOf(refused.getMostSpecificCause().getMessage());
        if (detail.contains("uq_printer_name")) {
            return new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "A printer with that name already exists at this branch");
        }
        if (detail.contains("uq_printer_agent_alias")) {
            return new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "Another printer of this branch is already bound to that alias");
        }
        if (detail.contains("fk_printer_agent_device")) {
            return new ApiException(ErrorCode.VALIDATION_FAILED, "That agent is not enrolled at this branch");
        }
        return new ApiException(ErrorCode.RESOURCE_CONFLICT, "The printer could not be saved");
    }

    /** The fields an audit fact compares: a roll, a name and a binding. Never an address, because there is none. */
    static Map<String, Object> snapshot(PrinterRow printer) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("displayName", printer.displayName());
        fields.put("tapeWidthMm", printer.tapeWidthMm());
        fields.put("connectionKind", printer.connectionKind());
        fields.put(
                "agentDeviceId",
                printer.agentDeviceId() == null ? null : printer.agentDeviceId().toString());
        fields.put("agentAlias", printer.agentPrinterKey());
        fields.put("status", printer.status());
        return fields;
    }
}
