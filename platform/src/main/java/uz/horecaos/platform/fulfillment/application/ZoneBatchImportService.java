package uz.horecaos.platform.fulfillment.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore.GeometryFacts;

/**
 * Landing a batch of legacy zone geometry as one review, rather than one
 * console form at a time (operations gap map row {@code 3.6c}, ADR 0037).
 *
 * <p>ADR 0037's own reason a coordinate-order mistake is dangerous applies at
 * full force to a migration: a batch of dozens of polygons exported from
 * another system is exactly where a latitude/longitude transposition survives
 * unnoticed, because every ring stays a valid, non-self-intersecting polygon
 * — it is simply somewhere else, often the mirrored hemisphere. No
 * containment test catches that; only comparing the shape against its source
 * on a map does, and that map is {@code X.4}, not built yet. So every row this
 * service accepts lands the same way {@link ServiceZoneService#draftPolygonVersion}
 * always has — as a {@code DRAFT} version, never activated — and this class adds
 * nothing that could activate one. What it adds is the batch shape and the
 * per-row report, and a {@code dryRun} that runs every check a real import
 * would and then rolls back, so an operator can see the outcome before
 * committing dozens of zones at once.
 *
 * <p>Each row runs in its own transaction ({@link
 * TransactionDefinition#PROPAGATION_REQUIRES_NEW}), independent of every other
 * row. A malformed polygon or a duplicate code on row 12 must not poison rows
 * 1 through 11 that already committed, and a batch with one bad row is a
 * batch with one bad row on the report — not a batch that silently imported
 * nothing.
 *
 * <p>An intra-batch duplicate {@code code} is checked in memory, before any
 * row's transaction opens, rather than left to the database's own unique
 * constraint. Under a real (non-dry) run the constraint alone would still
 * catch it — the first row's insert commits, the second's collides — but
 * under {@code dryRun} every row's transaction is individually rolled back
 * before the next one starts, so two rows sharing a code would each insert
 * against a database that has never seen the other and both come back
 * accepted, misreporting what a real import would actually do. Checking the
 * whole batch up front closes that gap for both run kinds and, as a side
 * effect, spares a real run the wasted round trip of executing a row already
 * known to collide with one still queued.
 */
@Service
public class ZoneBatchImportService {

    private final ServiceZoneService zones;
    private final JdbcServiceZoneStore store;
    private final TransactionTemplate perRow;

    public ZoneBatchImportService(
            ServiceZoneService zones, JdbcServiceZoneStore store, TransactionTemplate unitOfWork) {
        this.zones = zones;
        this.store = store;
        this.perRow = new TransactionTemplate(Objects.requireNonNull(
                unitOfWork.getTransactionManager(), "unitOfWork must already carry a transaction manager"));
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public BatchImportReport importBatch(UUID tenantId, UUID brandId, List<ImportRow> rows, boolean dryRun) {
        List<RowOutcome> outcomes = new ArrayList<>(rows.size());
        Set<String> codesSeenInThisBatch = new HashSet<>();
        for (ImportRow row : rows) {
            if (!codesSeenInThisBatch.add(row.code())) {
                outcomes.add(RowOutcome.rejected(
                        row.externalRef(),
                        "DUPLICATE_CODE_IN_BATCH: code '" + row.code()
                                + "' is already used by an earlier row in this batch"));
                continue;
            }
            RowOutcome outcome = Objects.requireNonNull(perRow.execute(status -> {
                try {
                    RowOutcome ok = importOneRow(tenantId, brandId, row);
                    if (dryRun) {
                        // Every check a real import would run has now run against a
                        // real inserted row -- draftPolygonVersion's own SQL, the
                        // same geometryFacts an activation attempt would read. Roll
                        // it back rather than reimplementing the geometry math to
                        // answer the question without writing anything.
                        status.setRollbackOnly();
                    }
                    return ok;
                } catch (DataAccessException | IllegalArgumentException failure) {
                    status.setRollbackOnly();
                    return RowOutcome.rejected(row.externalRef(), describeFailure(failure));
                }
            }));
            outcomes.add(outcome);
        }
        long accepted = outcomes.stream().filter(RowOutcome::accepted).count();
        return new BatchImportReport(rows.size(), (int) accepted, rows.size() - (int) accepted, dryRun, outcomes);
    }

    private RowOutcome importOneRow(UUID tenantId, UUID brandId, ImportRow row) {
        UUID zoneId = zones.createZone(
                tenantId,
                brandId,
                row.role(),
                row.code(),
                row.displayNameRu(),
                row.displayNameUz(),
                row.displayNameEn());

        ServiceZoneService.DraftedVersion drafted = zones.draftPolygonVersion(
                new ServiceZoneService.NewVersion(
                        tenantId,
                        brandId,
                        zoneId,
                        row.role(),
                        row.regionId(),
                        row.priority(),
                        row.currency(),
                        row.deliveryTariffId(),
                        row.freeDeliveryFromMinor(),
                        row.minBasketMinor(),
                        row.createdBy()),
                row.geoJson());

        GeometryFacts facts = store.geometryFacts(tenantId, zoneId, drafted.version())
                .orElseThrow(() -> new IllegalStateException("Just-inserted version has no geometry facts"));

        List<String> warnings = new ArrayList<>();
        if (!facts.validRings()) {
            warnings.add("INVALID_RING: " + facts.invalidReason());
        }
        if (facts.areaSquareMeters() > ServiceZoneService.MAX_ZONE_AREA_SQUARE_METERS) {
            warnings.add("AREA_ABOVE_LIMIT: %.0f km2 exceeds the %.0f km2 activation ceiling"
                    .formatted(
                            facts.areaSquareMeters() / 1_000_000d,
                            ServiceZoneService.MAX_ZONE_AREA_SQUARE_METERS / 1_000_000d));
        }
        if (facts.hasRegion() && !facts.withinRegion()) {
            warnings.add("OUTSIDE_REGION_BBOX: falls outside its region's bounding box -- the shape a "
                    + "swapped latitude/longitude produces. Compare against the source before activating.");
        }
        return RowOutcome.accepted(row.externalRef(), zoneId, drafted.version(), facts.areaSquareMeters(), warnings);
    }

    private static String describeFailure(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    /** One row of legacy geometry to land as a DRAFT zone version. */
    public record ImportRow(
            String externalRef,
            ZoneRole role,
            String code,
            String displayNameRu,
            String displayNameUz,
            String displayNameEn,
            @Nullable UUID regionId,
            int priority,
            String currency,
            @Nullable UUID deliveryTariffId,
            @Nullable Long freeDeliveryFromMinor,
            @Nullable Long minBasketMinor,
            String geoJson,
            UUID createdBy) {}

    /** The whole batch's outcome. {@code dryRun} true means every row below was rolled back regardless of its own outcome. */
    public record BatchImportReport(int totalRows, int accepted, int rejected, boolean dryRun, List<RowOutcome> rows) {}

    /**
     * One row's outcome. {@code warnings} is non-blocking diagnostic evidence
     * for the shadow-comparison map ({@code X.4}) an operator reads before
     * activating; {@code error} is set only when the row was rejected outright.
     */
    public record RowOutcome(
            String externalRef,
            boolean accepted,
            @Nullable UUID zoneId,
            @Nullable Integer version,
            @Nullable Double areaSquareMeters,
            List<String> warnings,
            @Nullable String error) {

        static RowOutcome accepted(
                String externalRef, UUID zoneId, int version, double areaSquareMeters, List<String> warnings) {
            return new RowOutcome(externalRef, true, zoneId, version, areaSquareMeters, warnings, null);
        }

        static RowOutcome rejected(String externalRef, String error) {
            return new RowOutcome(externalRef, false, null, null, null, List.of(), error);
        }
    }
}
