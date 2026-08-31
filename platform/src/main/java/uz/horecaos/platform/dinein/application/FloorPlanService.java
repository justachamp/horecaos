package uz.horecaos.platform.dinein.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.dinein.domain.BearerToken;
import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SectionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SettingsRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The floor plan: sections, tables, and the token behind a table's QR code
 * (ADR 0047).
 *
 * <p>All of it is authored from nothing. The legacy estate is a delivery and
 * takeaway product — its {@code OrderType} enum has no hall and there is no table
 * or reservation model anywhere beside it — so every section, every table and
 * every seat count has to be typed in by somebody who has stood in that dining
 * room before a single code is printed.
 *
 * <p>Tables archive and never delete. A reservation whose table row is gone is a
 * booking whose location cannot be rendered, and a settled session whose table has
 * vanished is an evening nobody can reconcile to a room.
 */
@Service
public class FloorPlanService {

    private final JdbcDineInStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    public FloorPlanService(JdbcDineInStore store, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    // -------------------------------------------------------------- settings

    /**
     * A branch's dine-in settings, with every tunable optional so a caller can
     * change only what it means to.
     *
     * @param qrMode the branch-wide answer to "what does a scanned code do here".
     *               {@code SETTLE_OPEN_TICKET} is refused by {@link QrMode#require}
     *               and again by V0034, per ADR 0011's rule that an unsupported
     *               provider capability may never be the sole business path
     * @param turnaroundMinutes null to leave the current value (or the default for
     *                          a branch never configured) in place
     * @param guestSessionTtlMinutes null to leave the current value in place
     * @param serviceChargeRateBp null to leave the current value in place
     */
    public record BranchSettings(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String qrMode,
            @Nullable Integer turnaroundMinutes,
            @Nullable Integer guestSessionTtlMinutes,
            @Nullable Integer serviceChargeRateBp) {}

    @Transactional
    public SettingsRow configure(BranchSettings request, String actorSubject, String reason) {
        SettingsRow current =
                store.findSettings(request.tenantId(), request.locationId()).orElse(null);

        SettingsRow desired = new SettingsRow(
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                QrMode.require(request.qrMode()),
                orDefault(request.turnaroundMinutes(), current == null ? 15 : current.turnaroundMinutes()),
                orDefault(request.guestSessionTtlMinutes(), current == null ? 240 : current.guestSessionTtlMinutes()),
                orDefault(request.serviceChargeRateBp(), current == null ? 0 : current.serviceChargeRateBp()),
                1);

        SettingsRow saved = store.upsertSettings(desired, clock.instant());

        audit.record(AuditFact.of("dinein.settings.configured", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(saved.tenantId(), saved.brandId(), saved.locationId()))
                .target("dinein.location_settings", saved.locationId())
                .targetVersion((long) saved.version())
                .because(reason)
                .changed(Map.of(
                        "qrMode", saved.qrMode().name(),
                        "turnaroundMinutes", saved.turnaroundMinutes(),
                        "guestSessionTtlMinutes", saved.guestSessionTtlMinutes(),
                        "serviceChargeRateBp", saved.serviceChargeRateBp()))
                .usingCapability("dinein.floorplan.manage")
                .correlatedBy(saved.locationId().toString())
                .occurredAt(clock.instant())
                .build());

        return saved;
    }

    /**
     * The branch's settings, defaulted rather than absent.
     *
     * <p>A branch that has never been configured still has to answer "what does a
     * code do here", and the honest answer is the safest mode rather than an
     * empty optional every caller would have to interpret.
     */
    public SettingsRow settings(UUID tenantId, UUID brandId, UUID locationId) {
        return store.findSettings(tenantId, locationId)
                .orElseGet(() -> new SettingsRow(tenantId, brandId, locationId, QrMode.VIEW_ONLY, 15, 240, 0, 1));
    }

    // -------------------------------------------------------------- sections

    public record NewSection(
            UUID tenantId, UUID brandId, UUID locationId, String code, String displayName, Integer sortOrder) {}

    @Transactional
    public SectionRow createSection(NewSection request) {
        SectionRow section = new SectionRow(
                UUID.randomUUID(),
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                request.code(),
                request.displayName(),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                "ACTIVE",
                1);

        try {
            store.insertSection(section, clock.instant());
        } catch (DuplicateKeyException alreadyThere) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "Section %s already exists at this branch".formatted(request.code()));
        }
        return section;
    }

    public List<SectionRow> sections(UUID tenantId, UUID locationId) {
        return store.listSections(tenantId, locationId);
    }

    // ---------------------------------------------------------------- tables

    public record NewTable(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID sectionId,
            String code,
            String displayName,
            int seats,
            boolean joinable,
            @Nullable BigDecimal layoutX,
            @Nullable BigDecimal layoutY) {}

    @Transactional
    public TableRow createTable(NewTable request) {
        TableRow table = new TableRow(
                UUID.randomUUID(),
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                request.sectionId(),
                request.code(),
                request.displayName(),
                request.seats(),
                request.joinable(),
                request.layoutX(),
                request.layoutY(),
                "ACTIVE",
                null,
                null,
                1);

        try {
            store.insertTable(table, clock.instant());
        } catch (DuplicateKeyException alreadyThere) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "Table %s already exists at this branch".formatted(request.code()));
        }
        return table;
    }

    public List<TableRow> tables(UUID tenantId, UUID locationId) {
        return store.listTables(tenantId, locationId);
    }

    // ----------------------------------------------------------- the QR token

    /**
     * The only time a table token exists outside a printer.
     *
     * @param plaintext put on the printed code and never stored. Returned once,
     *                  and there is no endpoint that will ever return it again:
     *                  losing it means rotating, which is the same operation.
     */
    public record IssuedQrToken(
            UUID tableId, String plaintext, Instant rotatedAt, int version, int revokedGuestSessions) {}

    /**
     * Issues or rotates a table's QR token.
     *
     * <p>One operation for both, deliberately. "Issue" and "rotate" differ only in
     * whether a previous digest existed, and a separate first-issue path would be
     * a second place for the revocation below to be forgotten.
     *
     * <p>Rotation is the only remedy for a leaked code, and it is remediation in
     * the physical world: somebody has to walk the room with a printer. So the two
     * halves happen in one transaction — the new digest is written and every live
     * guest token minted from the old one is revoked — and the operator is told how
     * many guests were cut off, because during service that number is the cost of
     * the decision they just took.
     */
    @Transactional
    public IssuedQrToken rotateQrToken(
            UUID tenantId, UUID tableId, int expectedVersion, String actorSubject, String reason) {

        TableRow table = store.findTable(tenantId, tableId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such table"));

        if ("ARCHIVED".equals(table.status())) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "An archived table takes no orders, so a code for it would scan to nothing");
        }

        Instant now = clock.instant();
        BearerToken.Issued issued = BearerToken.issue();

        if (!store.rotateQrToken(tenantId, tableId, expectedVersion, issued.hash(), now)) {
            throw ApiException.staleVersion(expectedVersion, table.version());
        }

        int revoked = store.revokeGuestSessionsForTable(tenantId, tableId, "TABLE_TOKEN_ROTATED", now);

        // The digest is recorded and the token is not. An audit trail that carries
        // the credential it was written to protect is a second copy of the thing
        // somebody photographed.
        audit.record(AuditFact.of("dinein.qr.rotated", AuditClass.SECURITY)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(table.tenantId(), table.brandId(), table.locationId()))
                .target("dinein.table", tableId)
                .targetVersion((long) expectedVersion + 1)
                .because(reason)
                .changed(Map.of(
                        "tableCode",
                        table.code(),
                        "previouslyIssued",
                        table.qrTokenHash() != null,
                        "revokedGuestSessions",
                        revoked))
                .evidence(issued.hash())
                .usingCapability("dinein.qr.rotate")
                .correlatedBy(tableId.toString())
                .occurredAt(now)
                .build());

        return new IssuedQrToken(tableId, issued.plaintext(), now, expectedVersion + 1, revoked);
    }

    /**
     * Takes a table out of the room.
     *
     * <p>Archiving also revokes the live guest tokens at that table. A code on a
     * table that has been carried out of the building is a code that must stop
     * working, and nobody is going to remember to rotate it first.
     */
    @Transactional
    public TableRow changeTableStatus(
            UUID tenantId, UUID tableId, int expectedVersion, String status, String actorSubject, String reason) {

        TableRow table = store.findTable(tenantId, tableId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such table"));

        if (!List.of("ACTIVE", "OUT_OF_SERVICE", "ARCHIVED").contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown table status " + status);
        }

        Instant now = clock.instant();
        if (!store.updateTableStatus(tenantId, tableId, expectedVersion, status, now)) {
            throw ApiException.staleVersion(expectedVersion, table.version());
        }

        if ("ARCHIVED".equals(status)) {
            store.revokeGuestSessionsForTable(tenantId, tableId, "TABLE_ARCHIVED", now);
        }

        audit.record(AuditFact.of("dinein.table.status-changed", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(table.tenantId(), table.brandId(), table.locationId()))
                .target("dinein.table", tableId)
                .targetVersion((long) expectedVersion + 1)
                .because(reason)
                .changed(Map.of("from", table.status(), "to", status))
                .usingCapability("dinein.floorplan.manage")
                .correlatedBy(tableId.toString())
                .occurredAt(now)
                .build());

        return store.findTable(tenantId, tableId).orElseThrow();
    }

    private static int orDefault(@Nullable Integer supplied, int fallback) {
        return supplied == null ? fallback : supplied;
    }
}
