package uz.horecaos.platform.iam.application.terminals;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.api.terminals.TerminalFact;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.AttributionMode;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicySource;
import uz.horecaos.platform.iam.application.staff.StaffTerminalFacts;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.LiveSessionRow;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.SessionHistoryRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The class-neutral terminals registry (ADR 0155, Decision 6): every device principal at a location, of any class, with the
 * same «last seen», the mode a manager set, and who is unlocked at it now.
 *
 * <p>Approval stays with the module that owns the class (the kitchen's {@code kitchen.station.manage}, the kiosk's {@code
 * kiosk.device.manage}); what lives here is what is true of every device. A manager renames one, sets its attribution mode
 * and auto-lock, and revokes it with a reason, which ends its sessions. The spec's «Перевыпустить код привязки» is not
 * built as a console-issued code: a device displays its own code, so re-pairing is revoke, then pair the replacement.
 */
@Service
public class TerminalRegistryService {

    private final DeviceEnrolmentPort devices;
    private final JdbcTerminalStore store;
    private final JdbcClient jdbc;
    private final TerminalSessionService sessions;
    private final TerminalPolicySource policies;
    private final StaffDirectory directory;
    private final StaffTerminalFacts staff;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TerminalRegistryService(
            DeviceEnrolmentPort devices,
            JdbcTerminalStore store,
            JdbcClient jdbc,
            TerminalSessionService sessions,
            TerminalPolicySource policies,
            StaffDirectory directory,
            StaffTerminalFacts staff,
            ApplicationEventPublisher events,
            Clock clock) {
        this.devices = devices;
        this.store = store;
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.policies = policies;
        this.directory = directory;
        this.staff = staff;
        this.events = events;
        this.clock = clock;
    }

    /**
     * One device in the registry.
     *
     * @param unlockable      whether a person may unlock a device of this class at all (a manager's choice of mode is
     *                        offered only where it is)
     * @param autoLockSeconds the device's own override; null follows the tenant's policy
     * @param effectiveAutoLockSeconds what applies now
     * @param unlockedNow     the person at the screen, as the roster would label them, null when nobody is
     * @param version         the {@code If-Match} the next configuration is sent with
     */
    public record Terminal(
            UUID deviceId,
            String displayName,
            String deviceClass,
            String status,
            boolean unlockable,
            String attributionMode,
            @Nullable Integer autoLockSeconds,
            int effectiveAutoLockSeconds,
            @Nullable Instant lastSeenAt,
            @Nullable String lastSeenBuild,
            @Nullable UnlockedNow unlockedNow,
            @Nullable String enrolledBy,
            Instant enrolledAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason,
            int version) {}

    public record UnlockedNow(String label, Instant since) {}

    /** Every device at a location of the tenant, active and revoked alike. */
    public List<Terminal> list(UUID tenantId, UUID brandId, UUID locationId) {
        TerminalPolicy policy = policies.of(tenantId);
        List<DevicePrincipalView> all = devices.list(tenantId, locationId).stream()
                .filter(device -> device.brandId().equals(brandId))
                .toList();
        Set<String> subjects = new LinkedHashSet<>();
        all.forEach(device -> {
            subjects.add(device.enrolledBy());
            if (device.revokedBy() != null) {
                subjects.add(device.revokedBy());
            }
        });
        Map<String, String> names = directory.namesOf(tenantId, subjects);
        Map<UUID, LiveSessionRow> live = new LinkedHashMap<>();
        store.liveSessionsAt(tenantId, locationId).forEach(row -> live.put(row.deviceId(), row));
        Map<UUID, StaffTerminalFacts.Person> people = staff.persons(
                tenantId, live.values().stream().map(LiveSessionRow::memberId).toList());

        List<Terminal> terminals = new ArrayList<>();
        for (DevicePrincipalView device : all) {
            LiveSessionRow session = live.get(device.id());
            UnlockedNow unlocked = null;
            if (session != null && session.openedAt() != null) {
                StaffTerminalFacts.Person person = people.get(session.memberId());
                String label = person == null
                        ? session.memberReference()
                        : TerminalEligibility.labelOf(person, policy.rosterDisplay());
                unlocked = new UnlockedNow(label, session.openedAt());
            }
            terminals.add(new Terminal(
                    device.id(),
                    device.displayName(),
                    device.deviceClass().name(),
                    device.status(),
                    device.deviceClass().unlockable(),
                    device.attributionMode(),
                    device.autoLockSeconds(),
                    device.autoLockSeconds() != null ? device.autoLockSeconds() : policy.autoLockSeconds(),
                    device.lastSeenAt(),
                    device.lastSeenBuild(),
                    unlocked,
                    names.get(device.enrolledBy()),
                    device.enrolledAt(),
                    device.revokedBy() == null ? null : names.get(device.revokedBy()),
                    device.revokedAt(),
                    device.revokedReason(),
                    versionOf(device.id())));
        }
        return terminals;
    }

    /**
     * Renames a device and sets its attribution mode and auto-lock, against the version the form was opened at.
     *
     * <p>A mode other than {@code DEVICE_ONLY} is refused for a class nobody unlocks, here and by the database. Setting a
     * device back to {@code DEVICE_ONLY} ends its sessions at once: rollback restores today's attribution.
     */
    @Transactional
    public Terminal configure(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID deviceId,
            String displayName,
            AttributionMode mode,
            @Nullable Integer autoLockSeconds,
            int expectedVersion,
            String actorSubject) {

        DevicePrincipalView device = devices.find(tenantId, deviceId)
                .filter(found ->
                        found.brandId().equals(brandId) && found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such device"));
        if (!"ACTIVE".equals(device.status())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "A revoked device cannot be changed");
        }
        if (mode != AttributionMode.DEVICE_ONLY && !device.deviceClass().unlockable()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A %s is not unlocked by a person, so its attribution mode stays DEVICE_ONLY"
                            .formatted(device.deviceClass()),
                    Map.of("deviceClass", device.deviceClass().name()));
        }
        if (autoLockSeconds != null && (autoLockSeconds < 30 || autoLockSeconds > 3600)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The auto-lock is between 30 and 3,600 seconds",
                    Map.of("field", "autoLockSeconds"));
        }
        int current = versionOf(deviceId);
        if (current != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, current);
        }

        Instant now = clock.instant();
        int updated = jdbc.sql("""
                        UPDATE iam.device_principals
                           SET display_name = :name, attribution_mode = :mode, auto_lock_seconds = :autoLock,
                               version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND id = :id AND version = :expected AND status = 'ACTIVE'
                        """)
                .param("name", displayName)
                .param("mode", mode.name())
                .param("autoLock", autoLockSeconds)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("id", deviceId)
                .param("expected", expectedVersion)
                .update();
        if (updated != 1) {
            throw ApiException.staleVersion(expectedVersion, versionOf(deviceId));
        }
        String correlation = Ids.newId().toString();
        if (mode == AttributionMode.DEVICE_ONLY
                && device.attributionMode().equals(AttributionMode.DEVICE_ONLY.name()) == false) {
            sessions.endDeviceSessions(tenantId, deviceId, "LOCKED", correlation);
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("displayName", device.displayName());
        before.put("attributionMode", device.attributionMode());
        before.put("autoLockSeconds", device.autoLockSeconds());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("displayName", displayName);
        after.put("attributionMode", mode.name());
        after.put("autoLockSeconds", autoLockSeconds);
        events.publishEvent(new TerminalFact(
                "terminal.device.configured",
                tenantId,
                locationId,
                brandId,
                actorSubject,
                "iam.device_principal",
                deviceId,
                deviceId,
                "Terminal configured: " + displayName,
                "terminal.manage",
                null,
                before,
                after,
                correlation,
                now));
        return list(tenantId, brandId, locationId).stream()
                .filter(terminal -> terminal.deviceId().equals(deviceId))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Revokes a device from the registry with a reason: its grant is revoked first, so its next request is refused, its
     * Keycloak client disabled, and its sessions ended. Idempotent -- a second press writes no second fact.
     */
    @Transactional
    public boolean revoke(
            UUID tenantId, UUID brandId, UUID locationId, UUID deviceId, String reason, String actorSubject) {
        DevicePrincipalView device = devices.find(tenantId, deviceId)
                .filter(found ->
                        found.brandId().equals(brandId) && found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such device"));
        boolean revoked = devices.revoke(deviceId, actorSubject, reason);
        if (revoked) {
            Instant now = clock.instant();
            String correlation = Ids.newId().toString();
            events.publishEvent(new TerminalFact(
                    "terminal.device.revoked",
                    tenantId,
                    locationId,
                    brandId,
                    actorSubject,
                    "iam.device_principal",
                    deviceId,
                    deviceId,
                    reason,
                    "terminal.manage",
                    null,
                    Map.of("revoked", false),
                    Map.of("revoked", true, "deviceClass", device.deviceClass().name()),
                    correlation,
                    now));
        }
        return revoked;
    }

    /** One page of a device's unlock history: who, when, how it ended. */
    public record TerminalSessionPage(
            List<TerminalDeviceSession> sessions, @Nullable String nextCursor) {}

    public record TerminalDeviceSession(
            UUID sessionId,
            String who,
            String reference,
            Instant openedAt,
            @Nullable Instant endedAt,
            @Nullable String endedReason,
            Instant lastActivityAt) {}

    public TerminalSessionPage sessionsOf(
            UUID tenantId, UUID brandId, UUID locationId, UUID deviceId, @Nullable String cursor) {
        if (devices.find(tenantId, deviceId)
                .filter(found ->
                        found.brandId().equals(brandId) && found.locationId().equals(locationId))
                .isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such device");
        }
        int pageSize = 25;
        Instant before = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = new String(
                                Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)
                        .split("\\|");
                before = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException malformed) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "That cursor is not one this list issued");
            }
        }
        List<SessionHistoryRow> rows = store.sessionsOfDevice(tenantId, deviceId, before, beforeId, pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<SessionHistoryRow> page = more ? rows.subList(0, pageSize) : rows;
        TerminalPolicy policy = policies.of(tenantId);
        Map<UUID, StaffTerminalFacts.Person> people = staff.persons(
                tenantId,
                page.stream().map(SessionHistoryRow::memberId).distinct().toList());
        List<TerminalDeviceSession> out = new ArrayList<>();
        for (SessionHistoryRow row : page) {
            StaffTerminalFacts.Person person = people.get(row.memberId());
            out.add(new TerminalDeviceSession(
                    row.id(),
                    person == null
                            ? row.memberReference()
                            : TerminalEligibility.labelOf(person, policy.rosterDisplay()),
                    row.memberReference(),
                    row.openedAt(),
                    row.endedAt(),
                    row.endedReasonAt(clock.instant()),
                    row.lastActivityAt()));
        }
        String next = null;
        if (more) {
            SessionHistoryRow last = page.get(page.size() - 1);
            next = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            (last.openedAt() + "|" + last.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new TerminalSessionPage(out, next);
    }

    private int versionOf(UUID deviceId) {
        Integer version = jdbc.sql("SELECT version FROM iam.device_principals WHERE id = :id")
                .param("id", deviceId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return version == null ? 0 : version;
    }

    @SuppressWarnings("unused")
    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
