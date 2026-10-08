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
import uz.horecaos.platform.printing.application.PrintAudit.Subject;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.RouteRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What prints where (ADR 0154, Decision 6): a route maps (location, document type, optional station) to a
 * printer, with a number of copies and an {@code auto_print} flag.
 *
 * <p>A new location has no routes and a document with no route prints nowhere. The trigger is fixed by the
 * document type -- a slip prints when the order is confirmed, a ticket when it is released, a receipt when its
 * fiscal document is issued -- and is deliberately not a column. {@code auto_print} needs an agent printer,
 * because nobody sits at a browser to press a button when a ticket is released. The database refuses a route
 * that names a printer or a station of another branch; this names the cases it can first.
 */
@Service
public class RouteService {

    private final TenantRlsSession rls;
    private final JdbcPrintingStore store;
    private final PrintingGuard guard;
    private final PrintAudit audit;
    private final Clock clock;

    public RouteService(
            TenantRlsSession rls, JdbcPrintingStore store, PrintingGuard guard, PrintAudit audit, Clock clock) {
        this.rls = rls;
        this.store = store;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
    }

    public record RouteDraft(
            PrintDocumentType documentType,
            @Nullable UUID stationId,
            UUID printerId,
            int copies,
            boolean autoPrint,
            boolean active) {}

    @Transactional(readOnly = true)
    public List<RouteRow> list(UUID tenantId, UUID locationId) {
        rls.bindTenant(tenantId);
        return store.listRoutes(tenantId, locationId);
    }

    @Transactional
    public RouteRow create(UUID tenantId, UUID brandId, UUID locationId, RouteDraft draft, String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabled(tenantId, brandId, locationId);
        validate(tenantId, locationId, draft);
        Instant now = clock.instant();
        RouteRow route = new RouteRow(
                Ids.newId(),
                tenantId,
                brandId,
                locationId,
                draft.documentType(),
                draft.stationId(),
                draft.printerId(),
                draft.copies(),
                draft.autoPrint(),
                draft.active() ? "ACTIVE" : "INACTIVE",
                1,
                now,
                now);
        try {
            store.insertRoute(route);
        } catch (DataIntegrityViolationException refused) {
            throw conflict(refused);
        }
        audit.created(
                "printing.route.changed",
                AuditClass.BUSINESS,
                new Subject(
                        tenantId,
                        brandId,
                        locationId,
                        "printing.route",
                        route.id(),
                        actorSubject,
                        "Print route created"),
                snapshot(route));
        return route;
    }

    @Transactional
    public RouteRow update(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID routeId,
            RouteDraft draft,
            long expectedVersion,
            String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabled(tenantId, brandId, locationId);
        RouteRow before = store.findRoute(tenantId, routeId)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such route"));
        if (before.documentType() != draft.documentType()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A route's document type does not change; make another route");
        }
        validate(tenantId, locationId, draft);
        if (before.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        Optional<Integer> version;
        try {
            version = store.updateRoute(
                    tenantId,
                    routeId,
                    draft.stationId(),
                    draft.printerId(),
                    draft.copies(),
                    draft.autoPrint(),
                    draft.active() ? "ACTIVE" : "INACTIVE",
                    (int) expectedVersion,
                    clock.instant());
        } catch (DataIntegrityViolationException refused) {
            throw conflict(refused);
        }
        if (version.isEmpty()) {
            throw ApiException.staleVersion(
                    expectedVersion,
                    store.findRoute(tenantId, routeId).map(RouteRow::version).orElse(0));
        }
        RouteRow after = store.findRoute(tenantId, routeId).orElseThrow();
        audit.changed(
                "printing.route.changed",
                AuditClass.BUSINESS,
                new Subject(
                        tenantId, brandId, locationId, "printing.route", routeId, actorSubject, "Print route changed"),
                snapshot(before),
                snapshot(after));
        return after;
    }

    @Transactional
    public void delete(
            UUID tenantId, UUID brandId, UUID locationId, UUID routeId, long expectedVersion, String actorSubject) {
        rls.bindTenant(tenantId);
        guard.requireEnabled(tenantId, brandId, locationId);
        RouteRow before = store.findRoute(tenantId, routeId)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such route"));
        if (before.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        if (!store.deleteRoute(tenantId, routeId, (int) expectedVersion)) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        Map<String, Object> gone = new LinkedHashMap<>();
        gone.put("present", false);
        Map<String, Object> was = new LinkedHashMap<>(snapshot(before));
        was.put("present", true);
        audit.changed(
                "printing.route.changed",
                AuditClass.BUSINESS,
                new Subject(
                        tenantId, brandId, locationId, "printing.route", routeId, actorSubject, "Print route removed"),
                was,
                gone);
    }

    private void validate(UUID tenantId, UUID locationId, RouteDraft draft) {
        if (!draft.documentType().routable()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Only a kitchen ticket, an order slip or a fiscal receipt is routed");
        }
        if (draft.stationId() != null && draft.documentType() != PrintDocumentType.KITCHEN_TICKET) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only a kitchen ticket is routed by station");
        }
        if (draft.copies() < 1 || draft.copies() > 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A route prints one to three copies");
        }
        PrinterRow printer = store.findPrinter(tenantId, draft.printerId())
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "That printer is not at this branch",
                        Map.of("reason", "PRINTER_NOT_AT_BRANCH")));
        if (!printer.active()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "That printer is retired", Map.of("reason", "PRINTER_RETIRED"));
        }
        if (draft.autoPrint() && printer.browser()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A browser printer cannot print by itself: nobody sits at the screen when a ticket is released."
                            + " Bind an agent printer, or leave auto-print off",
                    Map.of("reason", "AUTO_PRINT_NEEDS_AN_AGENT"));
        }
    }

    private static ApiException conflict(DataIntegrityViolationException refused) {
        String detail = String.valueOf(refused.getMostSpecificCause().getMessage());
        if (detail.contains("uq_print_route_target")) {
            return new ApiException(ErrorCode.RESOURCE_CONFLICT, "That route already exists");
        }
        if (detail.contains("fk_print_route_station")) {
            return new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That station is not at this branch",
                    Map.of("reason", "STATION_NOT_AT_BRANCH"));
        }
        if (detail.contains("fk_print_route_printer")) {
            return new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That printer is not at this branch",
                    Map.of("reason", "PRINTER_NOT_AT_BRANCH"));
        }
        return new ApiException(ErrorCode.RESOURCE_CONFLICT, "The route could not be saved");
    }

    static Map<String, Object> snapshot(RouteRow route) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("documentType", route.documentType().name());
        fields.put(
                "stationId",
                route.stationId() == null ? null : route.stationId().toString());
        fields.put("printerId", route.printerId().toString());
        fields.put("copies", route.copies());
        fields.put("autoPrint", route.autoPrint());
        fields.put("status", route.status());
        return fields;
    }
}
