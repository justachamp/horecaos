package uz.horecaos.platform.kitchen.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.kitchen.api.KitchenConfigurationKeys;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenDeviceDisplayStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenDeviceDisplayStore.DisplayRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.LocationSummary;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What the kitchen holds about a wall display, and what a device may ask about itself (ADR 0151).
 *
 * <p><strong>The display's configuration is the device's, held by the kitchen.</strong> The station a
 * wall shows is a row beside the device, set by a manager from Kitchen → Devices, not a {@code
 * <select>} a reload forgets. For a caller that is a wall display, {@code GET .../kitchen/vdu} applies
 * that station and ignores the request's; for a person it behaves as it always did. The station is a
 * display convenience, not a security boundary against the same branch's staff.
 *
 * <p>Nothing here carries a secret, a Keycloak client id or a token: a device learns its own record and
 * nothing more.
 */
@Service
public class KitchenDeviceDisplayService {

    /** The wall's read stamps {@code last_read_at} at most this often (ADR 0151). */
    static final int LAST_READ_MINIMUM_GAP_SECONDS = 60;

    /** ADR 0151's counter: only the outcome, never a tenant, branch or device. */
    public static final String READS = "horecaos.kitchen.display.reads";

    private final JdbcKitchenDeviceDisplayStore displays;
    private final JdbcKitchenStore kitchen;
    private final DeviceEnrolmentPort devices;
    private final ConfigurationResolver configuration;
    private final AuditRecorder audit;
    private final Clock clock;
    private final MeterRegistry meters;

    @Autowired
    public KitchenDeviceDisplayService(
            JdbcKitchenDeviceDisplayStore displays,
            JdbcKitchenStore kitchen,
            DeviceEnrolmentPort devices,
            ConfigurationResolver configuration,
            AuditRecorder audit,
            Clock clock,
            MeterRegistry meters) {
        this.displays = displays;
        this.kitchen = kitchen;
        this.devices = devices;
        this.configuration = configuration;
        this.audit = audit;
        this.clock = clock;
        this.meters = meters;
    }

    /** For code wired by hand: a registry nobody scrapes. */
    public KitchenDeviceDisplayService(
            JdbcKitchenDeviceDisplayStore displays,
            JdbcKitchenStore kitchen,
            DeviceEnrolmentPort devices,
            ConfigurationResolver configuration,
            AuditRecorder audit,
            Clock clock) {
        this(displays, kitchen, devices, configuration, audit, clock, new SimpleMeterRegistry());
    }

    /** A freshly approved wall gets its row (station: the whole branch); a touch KDS has none. */
    public void provision(DevicePrincipalView device) {
        if (device.deviceClass() == DevicePrincipalClass.KITCHEN_VDU) {
            displays.insert(device.tenantId(), device.locationId(), device.id(), clock.instant());
        }
    }

    // ------------------------------------------------------------------ the manager's side

    /** Every display of a branch, by device, with the station it shows. */
    public Map<UUID, Display> displaysOf(UUID tenantId, UUID brandId, UUID locationId) {
        Duration notSeenAfter = notSeenAfter(tenantId, brandId, locationId);
        Map<UUID, Display> byDevice = new LinkedHashMap<>();
        displays.forLocation(tenantId, locationId)
                .forEach((deviceId, row) -> byDevice.put(deviceId, displayOf(row, notSeenAfter, null)));
        return byDevice;
    }

    /**
     * Points a wall at one station, or at the whole branch ({@code stationId} null), as a manager.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} for a device that is not at this branch,
     *                      {@code UNPROCESSABLE_STATE} for one that is not a wall display or is revoked,
     *                      {@code VALIDATION_FAILED} for a station that is not an active station of this
     *                      branch, {@code STALE_VERSION} when the row moved on
     */
    @Transactional
    public Display configure(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID deviceId,
            @Nullable UUID stationId,
            long expectedVersion,
            String actorSubject) {

        DevicePrincipalView device = devices.list(tenantId, locationId).stream()
                .filter(candidate -> candidate.id().equals(deviceId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such device"));
        if (device.deviceClass() != DevicePrincipalClass.KITCHEN_VDU || !"ACTIVE".equals(device.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "Only an active wall display has a display configuration");
        }
        StationRow station = stationId == null ? null : activeStationOf(tenantId, locationId, stationId);
        DisplayRow before = displays.find(tenantId, deviceId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No display configuration"));
        if (before.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }

        Instant now = clock.instant();
        int version = displays.setStation(tenantId, deviceId, stationId, before.version(), now)
                .orElseThrow(() -> ApiException.staleVersion(expectedVersion, before.version()));

        StationRow previous = before.stationId() == null
                ? null
                : kitchen.findStation(tenantId, before.stationId()).orElse(null);
        audit.record(AuditFact.of("kitchen.device.display_configured", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("iam.device_principal", deviceId)
                .targetVersion((long) version)
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because("ADR 0151 wall display configuration: " + device.displayName())
                .changed(ChangeDocuments.diff(snapshot(previous, before.version()), snapshot(station, version)))
                .correlatedBy(deviceId.toString())
                .occurredAt(now)
                .build());

        return displayOf(
                new DisplayRow(tenantId, locationId, deviceId, stationId, before.lastReadAt(), version),
                notSeenAfter(tenantId, brandId, locationId),
                station);
    }

    private StationRow activeStationOf(UUID tenantId, UUID locationId, UUID stationId) {
        return kitchen.findStation(tenantId, stationId)
                .filter(station -> station.locationId().equals(locationId) && "ACTIVE".equals(station.status()))
                .orElseThrow(
                        () -> new ApiException(ErrorCode.VALIDATION_FAILED, "Not an active station of this branch"));
    }

    /** The station before and after, by its stable code: no free text, nothing about a person. */
    private static Map<String, Object> snapshot(@Nullable StationRow station, int version) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("station", station == null ? null : station.code());
        flat.put("displayVersion", version);
        return flat;
    }

    // -------------------------------------------------------------------- the wall's side

    /**
     * The calling principal's own wall display, when the caller is an active wall display and not a
     * person or a touch KDS. This is what makes the server, not the URL, decide which station a wall
     * shows.
     */
    public Optional<WallCaller> wallCaller(String principalSubject, UUID tenantId, UUID locationId) {
        return devices.activeDeviceOf(principalSubject)
                .filter(device -> device.deviceClass() == DevicePrincipalClass.KITCHEN_VDU)
                .filter(device -> device.tenantId().equals(tenantId)
                        && device.locationId().equals(locationId))
                .flatMap(device -> displays.find(tenantId, device.id())
                        .map(row -> new WallCaller(device.id(), device.locationId(), row.stationId())));
    }

    /** Stamps the wall's read (at most once a minute) and counts the outcome. */
    public void recordWallRead(WallCaller wall, UUID tenantId) {
        displays.touchRead(tenantId, wall.deviceId(), clock.instant(), LAST_READ_MINIMUM_GAP_SECONDS);
        meters.counter(READS, "outcome", "wall_served").increment();
    }

    /** Counts a person's read of the same projection (a manager previewing a wall). */
    public void recordStaffRead() {
        meters.counter(READS, "outcome", "staff_served").increment();
    }

    /**
     * What an enrolled kitchen device may learn about itself: who it is, where, in which zone, and for
     * a wall display which station it shows.
     *
     * @return empty for a subject that is not an active kitchen device (a person, or a revoked device)
     */
    public Optional<DeviceSelf> selfOf(String principalSubject) {
        return devices.activeDeviceOf(principalSubject).map(device -> {
            LocationSummary location = kitchen.locationSummary(device.tenantId(), device.locationId())
                    .orElse(new LocationSummary("", "UTC"));
            StationRow station = null;
            if (device.deviceClass() == DevicePrincipalClass.KITCHEN_VDU) {
                station = displays.find(device.tenantId(), device.id())
                        .flatMap(row -> row.stationId() == null
                                ? Optional.<StationRow>empty()
                                : kitchen.findStation(device.tenantId(), row.stationId()))
                        .orElse(null);
            }
            return new DeviceSelf(device, location, station);
        });
    }

    // ----------------------------------------------------------------------------- helpers

    private Duration notSeenAfter(UUID tenantId, UUID brandId, UUID locationId) {
        Integer minutes = configuration
                .resolve(
                        KitchenConfigurationKeys.DISPLAY_NOT_SEEN_AFTER_MINUTES,
                        ResourceScope.location(tenantId, brandId, locationId))
                .value();
        return Duration.ofMinutes(minutes == null || minutes < 1 ? 5 : minutes);
    }

    private Display displayOf(DisplayRow row, Duration notSeenAfter, @Nullable StationRow known) {
        StationRow station = known;
        if (station == null && row.stationId() != null) {
            station = kitchen.findStation(row.tenantId(), row.stationId()).orElse(null);
        }
        return new Display(row.deviceId(), station, row.lastReadAt(), row.version(), notSeenAfter);
    }

    /**
     * @param station      the station shown, null for the whole branch
     * @param lastReadAt   when the wall last read, null before its first read
     * @param notSeenAfter how long without a read counts as "not seen" (ADR 0151)
     */
    public record Display(
            UUID deviceId,
            @Nullable StationRow station,
            @Nullable Instant lastReadAt,
            int version,
            Duration notSeenAfter) {

        /** A wall with no read for {@code notSeenAfter}, measured from its last read or, before one, from {@code since}. */
        public boolean notSeen(Instant since, Instant now) {
            Instant reference = lastReadAt != null ? lastReadAt : since;
            return reference.plus(notSeenAfter).isBefore(now);
        }
    }

    /** The calling wall display: its device id, its branch and the station the server holds for it. */
    public record WallCaller(
            UUID deviceId, UUID locationId, @Nullable UUID stationId) {}

    /** A device's own record, as {@code GET /api/v1/devices/me} answers it. */
    public record DeviceSelf(
            DevicePrincipalView device,
            LocationSummary location,
            @Nullable StationRow station) {}
}
