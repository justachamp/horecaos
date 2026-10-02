package uz.horecaos.platform.dinein.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.dinein.domain.BearerToken;
import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SectionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SettingsRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.WalkInPolicy;
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

    /** ADR 0055's single-currency pilot, until a location-currency read exists. */
    static final String DEFAULT_SESSION_CURRENCY = "UZS";

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
     * @param walkIn ADR 0143's per-branch switch and numbers, every one optional;
     *               null leaves them all as they are
     */
    public record BranchSettings(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String qrMode,
            @Nullable Integer turnaroundMinutes,
            @Nullable Integer guestSessionTtlMinutes,
            @Nullable Integer serviceChargeRateBp,
            @Nullable WalkInChange walkIn) {

        /** The settings before ADR 0143: nothing about self-seating is touched. */
        public BranchSettings(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                String qrMode,
                @Nullable Integer turnaroundMinutes,
                @Nullable Integer guestSessionTtlMinutes,
                @Nullable Integer serviceChargeRateBp) {
            this(
                    tenantId,
                    brandId,
                    locationId,
                    qrMode,
                    turnaroundMinutes,
                    guestSessionTtlMinutes,
                    serviceChargeRateBp,
                    null);
        }
    }

    /**
     * A change to ADR 0143's walk-in settings. Every field is optional; null leaves
     * the current value in place, so the console can flip the switch without
     * re-sending four numbers it did not edit.
     */
    public record WalkInChange(
            @Nullable Boolean selfSeat,
            @Nullable Integer claimTtlMinutes,
            @Nullable Integer horizonMinutes,
            @Nullable Integer maxUnconfirmed,
            @Nullable Integer dailyClaimsPerAccount,
            @Nullable Integer paymentDeferMinutes,
            @Nullable String sessionCurrency) {}

    /**
     * Configures a branch without a precondition: the caller takes whatever version
     * is there. For code that owns a branch's settings outright (a seed, a fixture);
     * a person at a screen goes through {@link #configure(BranchSettings, int, String,
     * String)} and its {@code If-Match}.
     */
    @Transactional
    public SettingsRow configure(BranchSettings request, String actorSubject, String reason) {
        SettingsRow current =
                store.findSettings(request.tenantId(), request.locationId()).orElse(null);
        return configure(request, current == null ? 0 : current.version(), actorSubject, reason);
    }

    /**
     * Configures a branch conditionally on the version the caller read (ADR 0031).
     *
     * <p>A branch that was never configured reads as version {@code 0} -- it has no
     * row -- and the first write must say so; the version it then creates is
     * {@code 1}. Two managers configuring a never-configured branch in the same second
     * therefore produce one row and one stale-version refusal.
     */
    @Transactional
    public SettingsRow configure(BranchSettings request, int expectedVersion, String actorSubject, String reason) {
        SettingsRow current =
                store.findSettings(request.tenantId(), request.locationId()).orElse(null);
        int currentVersion = current == null ? 0 : current.version();
        if (expectedVersion != currentVersion) {
            throw ApiException.staleVersion(expectedVersion, currentVersion);
        }

        WalkInPolicy currentWalkIn = current == null ? WalkInPolicy.OFF : current.walkIn();
        WalkInChange change = request.walkIn();
        WalkInPolicy walkIn = change == null
                ? currentWalkIn
                : new WalkInPolicy(
                        orDefault(change.selfSeat(), currentWalkIn.selfSeat()),
                        orDefault(change.claimTtlMinutes(), currentWalkIn.claimTtlMinutes()),
                        orDefault(change.horizonMinutes(), currentWalkIn.horizonMinutes()),
                        orDefault(change.maxUnconfirmed(), currentWalkIn.maxUnconfirmed()),
                        orDefault(change.dailyClaimsPerAccount(), currentWalkIn.dailyClaimsPerAccount()),
                        orDefault(change.paymentDeferMinutes(), currentWalkIn.paymentDeferMinutes()));
        String currency = change == null || change.sessionCurrency() == null
                ? (current == null ? DEFAULT_SESSION_CURRENCY : current.sessionCurrency())
                : change.sessionCurrency();

        SettingsRow desired = new SettingsRow(
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                QrMode.require(request.qrMode()),
                orDefault(request.turnaroundMinutes(), current == null ? 15 : current.turnaroundMinutes()),
                orDefault(request.guestSessionTtlMinutes(), current == null ? 240 : current.guestSessionTtlMinutes()),
                orDefault(request.serviceChargeRateBp(), current == null ? 0 : current.serviceChargeRateBp()),
                currentVersion + 1,
                walkIn,
                currency);

        Instant now = clock.instant();
        boolean written = current == null
                ? store.insertSettings(desired, now)
                : store.updateSettings(desired, expectedVersion, now);
        if (!written) {
            // Somebody wrote between the read above and this statement. The row they
            // left is the one the caller must look at, so report it.
            int actual = store.findSettings(request.tenantId(), request.locationId())
                    .map(SettingsRow::version)
                    .orElse(0);
            throw ApiException.staleVersion(expectedVersion, actual);
        }
        SettingsRow saved =
                store.findSettings(request.tenantId(), request.locationId()).orElseThrow();

        audit.record(AuditFact.of("dinein.settings.configured", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(saved.tenantId(), saved.brandId(), saved.locationId()))
                .target("dinein.location_settings", saved.locationId())
                .targetVersion((long) saved.version())
                .because(reason)
                // Staff 9.3a: every field genuinely moves from the branch's
                // prior settings (or the documented defaults, when none
                // existed yet) to the saved ones.
                .changed(ChangeDocuments.diff(branchSettingsDiffMap(current), branchSettingsDiffMap(saved)))
                .usingCapability("dinein.floorplan.manage")
                .correlatedBy(saved.locationId().toString())
                .occurredAt(now)
                .build());

        return saved;
    }

    /**
     * The branch's settings, defaulted rather than absent.
     *
     * <p>A branch that has never been configured still has to answer "what does a
     * code do here", and the honest answer is the safest mode rather than an
     * empty optional every caller would have to interpret. Its version is
     * {@code 0}: there is no row, and an {@code If-Match} of {@code 0} is how a
     * caller says "create it".
     */
    public SettingsRow settings(UUID tenantId, UUID brandId, UUID locationId) {
        return store.findSettings(tenantId, locationId)
                .orElseGet(() -> new SettingsRow(
                        tenantId,
                        brandId,
                        locationId,
                        QrMode.VIEW_ONLY,
                        15,
                        240,
                        0,
                        0,
                        WalkInPolicy.OFF,
                        DEFAULT_SESSION_CURRENCY));
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

    /**
     * Moves a table on the canvas (drag-to-reposition).
     *
     * <p>Conditional on the version the caller read, the same optimistic-locking
     * shape every other table write here uses: two managers dragging the same
     * table at once settle on one final position, not a race the last write wins
     * silently.
     *
     * <p>{@code locationId} is the path's own location, the one the caller's
     * {@code DINEIN_FLOORPLAN_MANAGE} grant was just checked at. The capability
     * interceptor only proves that; it never inspects {@code tableId}, so a
     * mismatch here means the id names a table at a different branch — the
     * caller has no standing to know that table exists, so this reports the
     * same {@code RESOURCE_NOT_FOUND} a foreign {@code tenantId} would.
     */
    @Transactional
    public TableRow moveTable(
            UUID tenantId,
            UUID locationId,
            UUID tableId,
            int expectedVersion,
            BigDecimal layoutX,
            BigDecimal layoutY,
            String actorSubject,
            String reason) {

        TableRow table = requireTableAtLocation(tenantId, locationId, tableId);

        Instant now = clock.instant();
        if (!store.updateTableLayout(tenantId, tableId, expectedVersion, layoutX, layoutY, now)) {
            throw ApiException.staleVersion(expectedVersion, table.version());
        }

        audit.record(AuditFact.of("dinein.table.repositioned", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(table.tenantId(), table.brandId(), table.locationId()))
                .target("dinein.table", tableId)
                .targetVersion((long) expectedVersion + 1)
                .because(reason)
                // Staff 9.3a: layoutX/layoutY genuinely move from the table's
                // prior position (read above, before the write) to the new one.
                .changed(ChangeDocuments.diff(
                        tableLayoutDiffMap(table.layoutX(), table.layoutY()), tableLayoutDiffMap(layoutX, layoutY)))
                .usingCapability("dinein.floorplan.manage")
                .correlatedBy(tableId.toString())
                .occurredAt(now)
                .build());

        return store.findTable(tenantId, tableId).orElseThrow();
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
     *
     * <p>{@code locationId} is checked against the fetched table the same way
     * {@link #moveTable} checks it, and for the same reason: the capability
     * check at the interceptor proves the caller holds {@code DINEIN_QR_ROTATE}
     * at the path's own branch, never that {@code tableId} belongs there.
     */
    @Transactional
    public IssuedQrToken rotateQrToken(
            UUID tenantId, UUID locationId, UUID tableId, int expectedVersion, String actorSubject, String reason) {

        TableRow table = requireTableAtLocation(tenantId, locationId, tableId);

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
                // Staff 9.3a: "qrTokenHash" genuinely moves from the table's
                // prior digest (null if none was ever issued) to the new
                // one -- the digest, never the bearer token itself, per this
                // method's own comment above. tableCode is unchanged
                // identifying context; revokedGuestSessions is this
                // rotation's own count, with no prior count to diff.
                .changed(ChangeDocuments.diff(
                        qrRotationDiffMap(table.code(), table.qrTokenHash()),
                        Map.of(
                                "tableCode",
                                table.code(),
                                "qrTokenHash",
                                issued.hash(),
                                "revokedGuestSessions",
                                revoked)))
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
     *
     * <p>{@code locationId} is checked against the fetched table the same way
     * {@link #moveTable} checks it, and for the same reason: the capability
     * check at the interceptor proves the caller holds {@code
     * DINEIN_FLOORPLAN_MANAGE} at the path's own branch, never that {@code
     * tableId} belongs there.
     */
    @Transactional
    public TableRow changeTableStatus(
            UUID tenantId,
            UUID locationId,
            UUID tableId,
            int expectedVersion,
            String status,
            String actorSubject,
            String reason) {

        TableRow table = requireTableAtLocation(tenantId, locationId, tableId);

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
                // Staff 9.3a: "status" genuinely moves from the table's prior
                // status to the requested one.
                .changed(ChangeDocuments.change("status", table.status(), status))
                .usingCapability("dinein.floorplan.manage")
                .correlatedBy(tableId.toString())
                .occurredAt(now)
                .build());

        return store.findTable(tenantId, tableId).orElseThrow();
    }

    /**
     * The table, confirmed to actually belong to {@code locationId}.
     *
     * <p>{@code DINEIN_FLOORPLAN_MANAGE}/{@code DINEIN_QR_ROTATE} are checked
     * by {@code CapabilityEnforcementInterceptor} purely from the URL's own
     * {@code tenantId}/{@code brandId}/{@code locationId} path variables — it
     * has no way to know which table {@code tableId} names, so it only proves
     * the caller holds the capability at the branch named in the path, never
     * that the table being moved, rotated, or archived is actually there.
     * {@code JdbcDineInStore.findTable} filters only on {@code tenant_id} and
     * {@code id}, so without this check a manager scoped to one branch could
     * reposition, rotate the QR code of, or archive a table belonging to any
     * other branch in the same tenant by supplying that table's id — a
     * cross-location write the LOCATION-scope grant exists to prevent.
     * {@code RESOURCE_NOT_FOUND} rather than a forbidden response, so a caller
     * outside the table's location cannot use the response to confirm the
     * table exists at all, the same convention {@code
     * OperationsOrderController.requireOrderAtLocation} uses.
     */
    private TableRow requireTableAtLocation(UUID tenantId, UUID locationId, UUID tableId) {
        TableRow table = store.findTable(tenantId, tableId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such table"));
        if (!table.locationId().equals(locationId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such table");
        }
        return table;
    }

    private static int orDefault(@Nullable Integer supplied, int fallback) {
        return supplied == null ? fallback : supplied;
    }

    private static boolean orDefault(@Nullable Boolean supplied, boolean fallback) {
        return supplied == null ? fallback : supplied;
    }

    /** A {@code {tableCode, qrTokenHash}} snapshot for {@link #rotateQrToken}'s diff -- {@code qrTokenHash} may be null. */
    private static Map<String, Object> qrRotationDiffMap(String tableCode, @Nullable String qrTokenHash) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tableCode", tableCode);
        map.put("qrTokenHash", qrTokenHash);
        return map;
    }

    /** A {@code {layoutX, layoutY}} snapshot for {@link #moveTable}'s diff. */
    private static Map<String, Object> tableLayoutDiffMap(@Nullable BigDecimal layoutX, @Nullable BigDecimal layoutY) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("layoutX", layoutX);
        map.put("layoutY", layoutY);
        return map;
    }

    /** A snapshot of every settings field, or empty when no settings row exists yet. */
    private static Map<String, Object> branchSettingsDiffMap(@Nullable SettingsRow row) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("qrMode", row.qrMode().name());
        map.put("turnaroundMinutes", row.turnaroundMinutes());
        map.put("guestSessionTtlMinutes", row.guestSessionTtlMinutes());
        map.put("serviceChargeRateBp", row.serviceChargeRateBp());
        map.put("walkInSelfSeat", row.walkIn().selfSeat());
        map.put("walkInClaimTtlMinutes", row.walkIn().claimTtlMinutes());
        map.put("walkInHorizonMinutes", row.walkIn().horizonMinutes());
        map.put("walkInMaxUnconfirmed", row.walkIn().maxUnconfirmed());
        map.put("walkInDailyClaimsPerAccount", row.walkIn().dailyClaimsPerAccount());
        map.put("walkInPaymentDeferMinutes", row.walkIn().paymentDeferMinutes());
        map.put("sessionCurrency", row.sessionCurrency());
        return map;
    }
}
