package uz.horecaos.platform.iam.application.devices;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Implements {@link DeviceEnrolmentPort} (ADR 0079).
 *
 * <p>Nothing here reaches for a new authorization mechanism. A device is
 * granted through {@link GrantManagementService} exactly as a person would
 * be, so once enrolled it is indistinguishable from a staff principal to
 * {@code AuthorizationService}, {@code ResourceScopeVerifier}, or the ADR
 * 0033 grant cache — the only new things in this class are how a Keycloak
 * identity gets minted for it and how the credential reaches the device
 * without ever passing through a human's hands or this platform's own
 * database.
 */
@Service
public class DeviceEnrolmentService implements DeviceEnrolmentPort {

    /** The window a manager has to approve a pending code before it dies unused. */
    private static final Duration ENROLMENT_TTL = Duration.ofMinutes(10);

    private static final int POLL_INTERVAL_SECONDS = 4;

    /**
     * Recorded as {@code granted_by} on the underlying {@code iam.grants} row.
     * Never a Keycloak subject, per {@link GrantManagementService#grantSystemInitiated}'s
     * own contract — the human who approved the enrolment is recorded
     * separately, on {@code iam.device_principals.enrolled_by} and on the
     * kitchen module's own ADR 0027 audit fact, not here.
     */
    private static final String SYSTEM_ACTOR = "iam-device-enrolment";

    private static final int USER_CODE_INSERT_ATTEMPTS = 5;

    /**
     * Both endpoints are unauthenticated — an unenrolled device has no
     * subject to hold a per-principal limit against — so both are rate
     * limited per caller the same way {@code CustomerVerificationService}
     * limits its own pre-account endpoints: an opaque, hashed handle for the
     * caller (never a Keycloak subject or an address) rather than a name.
     */
    private static final String BEGIN_OPERATION = "device-enrolment-begin";

    private static final String POLL_OPERATION = "device-enrolment-poll";
    private static final RateLimiter.Policy BEGIN_PER_CALLER = RateLimiter.Policy.strictPerMinute(6);

    /** Polling is expected to happen every few seconds for up to ten minutes. */
    private static final RateLimiter.Policy POLL_PER_CALLER = RateLimiter.Policy.strictPerMinute(30);

    private final JdbcClient jdbc;
    private final DeviceClientProvisioner provisioner;
    private final GrantManagementService grants;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public DeviceEnrolmentService(
            JdbcClient jdbc,
            DeviceClientProvisioner provisioner,
            GrantManagementService grants,
            RateLimiter rateLimiter,
            Clock clock) {
        this.jdbc = jdbc;
        this.provisioner = provisioner;
        this.grants = grants;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Override
    @Transactional
    public EnrolmentBeginResult beginEnrolment(BeginEnrolment command, String callerKey) {
        requireNotRateLimited(BEGIN_OPERATION, callerKey, BEGIN_PER_CALLER);
        DeviceEnrolmentSecret.Issued secret = DeviceEnrolmentSecret.issue();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ENROLMENT_TTL);

        for (int attempt = 1; attempt <= USER_CODE_INSERT_ATTEMPTS; attempt++) {
            String userCode = EnrolmentUserCode.generate();
            try {
                jdbc.sql("""
                        INSERT INTO iam.device_enrolment_requests
                            (id, device_code_hash, user_code, requested_class, requested_label, status, expires_at)
                        VALUES (:id, :hash, :userCode, :requestedClass, :label, 'PENDING', :expiresAt)
                        """)
                        .param("id", Ids.newId())
                        .param("hash", secret.hash())
                        .param("userCode", userCode)
                        .param("requestedClass", command.deviceClass().name())
                        .param("label", command.requestedLabel())
                        .param("expiresAt", at(expiresAt))
                        .update();
                return new EnrolmentBeginResult(secret.plaintext(), userCode, expiresAt, POLL_INTERVAL_SECONDS);
            } catch (DuplicateKeyException collision) {
                // Either device_code_hash (astronomically unlikely for a 256-bit
                // value) or user_code, whose only uniqueness is while PENDING —
                // a fresh code on the next attempt resolves either.
            }
        }
        throw new IllegalStateException(
                "Could not mint a unique enrolment code after %d attempts".formatted(USER_CODE_INSERT_ATTEMPTS));
    }

    @Override
    @Transactional
    public EnrolmentPollResult poll(String deviceCode, String callerKey) {
        requireNotRateLimited(POLL_OPERATION, callerKey, POLL_PER_CALLER);
        String hash = DeviceEnrolmentSecret.hash(deviceCode);
        Instant now = clock.instant();

        // A PENDING request past its window is dead the moment anybody asks
        // about it again, not on a sweep timer — the same lazy-expiry shape
        // ADR 0040's handover challenges already use.
        jdbc.sql("""
                UPDATE iam.device_enrolment_requests
                   SET status = 'EXPIRED', version = version + 1, updated_at = :now
                 WHERE device_code_hash = :hash AND status = 'PENDING' AND expires_at <= :now
                """).param("hash", hash).param("now", at(now)).update();

        Optional<String> status = jdbc.sql(
                        "SELECT status FROM iam.device_enrolment_requests WHERE device_code_hash = :hash")
                .param("hash", hash)
                .query(String.class)
                .optional();

        if (status.isEmpty()) {
            // An unrecognised code answers exactly like an expired one: the two
            // are indistinguishable to a caller and must stay that way, or a
            // device code becomes an oracle for which codes were ever issued.
            return new EnrolmentPollResult(EnrolmentStatus.EXPIRED, null);
        }

        return switch (status.get()) {
            case "PENDING" -> new EnrolmentPollResult(EnrolmentStatus.PENDING, null);
            case "DENIED" -> new EnrolmentPollResult(EnrolmentStatus.DENIED, null);
            // CLAIMED reads as EXPIRED to every poll but the one that won the
            // claim: there is nothing left to hand out, and a distinct
            // "already used" answer would only tell a second caller that a
            // race happened, which is not theirs to know.
            case "EXPIRED", "CLAIMED" -> new EnrolmentPollResult(EnrolmentStatus.EXPIRED, null);
            case "APPROVED" -> claim(hash, now);
            default -> throw new IllegalStateException("Unknown enrolment status: " + status.get());
        };
    }

    /**
     * The one-shot claim: exactly one caller's {@code UPDATE} moves the row
     * off {@code APPROVED}, and only that caller ever sees the credential —
     * minted fresh here, at the moment of hand-off, rather than read back
     * from whatever Keycloak assigned at creation.
     */
    private EnrolmentPollResult claim(String hash, Instant now) {
        Optional<UUID> devicePrincipalId = jdbc.sql("""
                UPDATE iam.device_enrolment_requests
                   SET status = 'CLAIMED', claimed_at = :now, version = version + 1, updated_at = :now
                 WHERE device_code_hash = :hash AND status = 'APPROVED'
                RETURNING device_principal_id
                """)
                .param("hash", hash)
                .param("now", at(now))
                .query(UUID.class)
                .optional();

        if (devicePrincipalId.isEmpty()) {
            // Lost the race to a concurrent poll of the same device_code — the
            // only caller that can lose it is another instance of the same
            // device retrying a request whose response it never saw.
            return new EnrolmentPollResult(EnrolmentStatus.EXPIRED, null);
        }

        DeviceRow device = requireDevice(devicePrincipalId.get());
        String freshSecret = provisioner.regenerateSecret(device.keycloakClientInternalId());
        return new EnrolmentPollResult(
                EnrolmentStatus.APPROVED,
                new DeviceCredential(device.tokenEndpoint(), device.keycloakClientId(), freshSecret));
    }

    @Override
    @Transactional
    public DevicePrincipalView approve(ApproveEnrolment command, String approverSubject) {
        if (command.scope().type() != ScopeType.LOCATION) {
            // ADR 0079: a device principal is always LOCATION-scoped. Enforced
            // here rather than trusted from the caller, because this is the
            // one primitive every future device class shares, and the next
            // caller must not be able to widen it by omission.
            throw new IllegalArgumentException("A device principal may only be enrolled at LOCATION scope");
        }
        // ResourceScope's own compact constructor guarantees all three are
        // non-null for LOCATION scope; NullAway cannot see that invariant
        // through the type() check above, so it is made explicit here rather
        // than at every later read.
        UUID tenantId = Objects.requireNonNull(command.scope().tenantId());
        UUID brandId = Objects.requireNonNull(command.scope().brandId());
        UUID locationId = Objects.requireNonNull(command.scope().locationId());

        Instant now = clock.instant();
        PendingRow pending = jdbc.sql("""
                SELECT id, requested_class, expires_at
                  FROM iam.device_enrolment_requests
                 WHERE user_code = :userCode AND status = 'PENDING'
                """)
                .param("userCode", command.userCode())
                .query((rs, n) -> new PendingRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("requested_class"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No pending enrolment with this code"));

        if (!pending.expiresAt().isAfter(now)) {
            jdbc.sql("""
                    UPDATE iam.device_enrolment_requests
                       SET status = 'EXPIRED', version = version + 1, updated_at = :now
                     WHERE id = :id AND status = 'PENDING'
                    """).param("id", pending.id()).param("now", at(now)).update();
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This enrolment code has expired");
        }

        DevicePrincipalClass deviceClass = DevicePrincipalClass.valueOf(pending.requestedClass());

        DeviceClientProvisioner.ProvisionedClient client = provisioner.create(command.displayName());

        UUID grantId = grants.grantSystemInitiated(
                new GrantManagementService.GrantCommand(
                        client.serviceAccountSubject(),
                        command.roleCode(),
                        command.scope(),
                        "ADR 0079 kitchen device enrolment, approved by " + approverSubject,
                        null),
                SYSTEM_ACTOR);

        UUID deviceId = Ids.newId();
        jdbc.sql("""
                INSERT INTO iam.device_principals (
                    id, tenant_id, brand_id, location_id, device_class, display_name,
                    keycloak_client_internal_id, keycloak_client_id, principal_subject, grant_id,
                    status, enrolled_by, enrolled_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :deviceClass, :displayName,
                        :internalId, :clientId, :subject, :grantId,
                        'ACTIVE', :enrolledBy, :enrolledAt)
                """)
                .param("id", deviceId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("deviceClass", deviceClass.name())
                .param("displayName", command.displayName())
                .param("internalId", client.keycloakClientInternalId())
                .param("clientId", client.clientId())
                .param("subject", client.serviceAccountSubject())
                .param("grantId", grantId)
                .param("enrolledBy", approverSubject)
                .param("enrolledAt", at(now))
                .update();

        int approved = jdbc.sql("""
                UPDATE iam.device_enrolment_requests
                   SET status = 'APPROVED', tenant_id = :tenantId, brand_id = :brandId, location_id = :locationId,
                       device_principal_id = :deviceId, approved_by = :approvedBy, approved_at = :now,
                       version = version + 1, updated_at = :now
                 WHERE id = :id AND status = 'PENDING'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("deviceId", deviceId)
                .param("approvedBy", approverSubject)
                .param("now", at(now))
                .param("id", pending.id())
                .update();

        if (approved == 0) {
            // Somebody else approved or the request expired between the read
            // above and this write. The transaction rolls back the grant and
            // the device row this method just inserted; the one thing that
            // does not roll back is the Keycloak client already created,
            // which is left disabled-by-absence — it holds no grant, so it
            // authorizes nothing even if somebody later found its secret.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This enrolment code was already resolved");
        }

        return new DevicePrincipalView(
                deviceId,
                tenantId,
                brandId,
                locationId,
                deviceClass,
                command.displayName(),
                "ACTIVE",
                approverSubject,
                now,
                null,
                null,
                null);
    }

    @Override
    @Transactional
    public boolean revoke(UUID devicePrincipalId, String revokerSubject, String reason) {
        DeviceRow device = requireDevice(devicePrincipalId);
        if (!"ACTIVE".equals(device.status())) {
            return false;
        }

        Instant now = clock.instant();
        int updated = jdbc.sql("""
                UPDATE iam.device_principals
                   SET status = 'REVOKED', revoked_by = :revokedBy, revoked_at = :now, revoked_reason = :reason,
                       version = version + 1, updated_at = :now
                 WHERE id = :id AND status = 'ACTIVE'
                """)
                .param("id", devicePrincipalId)
                .param("revokedBy", revokerSubject)
                .param("reason", reason)
                .param("now", at(now))
                .update();

        if (updated == 0) {
            return false;
        }

        // The grant revocation is what actually stops the device -- cache
        // evicted immediately, exactly the mechanism that already stops a
        // revoked staff grant on its next request. Disabling the Keycloak
        // client is belt-and-suspenders against a token minted moments
        // before, never the control this depends on.
        grants.revoke(device.tenantId(), device.grantId(), revokerSubject, reason);
        provisioner.disable(device.keycloakClientInternalId());
        return true;
    }

    @Override
    public List<DevicePrincipalView> list(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, device_class, display_name, status,
                       enrolled_by, enrolled_at, revoked_by, revoked_at, revoked_reason
                  FROM iam.device_principals
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY enrolled_at DESC
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(DeviceEnrolmentService::toView)
                .list();
    }

    private DeviceRow requireDevice(UUID devicePrincipalId) {
        return jdbc.sql("""
                SELECT tenant_id, principal_subject, grant_id, keycloak_client_internal_id,
                       keycloak_client_id, status
                  FROM iam.device_principals WHERE id = :id
                """)
                .param("id", devicePrincipalId)
                .query((rs, n) -> new DeviceRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("principal_subject"),
                        rs.getObject("grant_id", UUID.class),
                        rs.getString("keycloak_client_internal_id"),
                        rs.getString("keycloak_client_id"),
                        rs.getString("status"),
                        tokenEndpointFor()))
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such device"));
    }

    /**
     * Recomputed rather than stored: the realm's token endpoint is
     * deployment configuration, not a fact about one device, and storing it
     * per row would mean every device minted before a Keycloak migration
     * carries a stale value.
     */
    private String tokenEndpointFor() {
        return provisioner.tokenEndpoint();
    }

    private static DevicePrincipalView toView(ResultSet rs, int rowNumber) throws SQLException {
        return new DevicePrincipalView(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                DevicePrincipalClass.valueOf(rs.getString("device_class")),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getString("enrolled_by"),
                rs.getObject("enrolled_at", OffsetDateTime.class).toInstant(),
                rs.getString("revoked_by"),
                optionalInstant(rs, "revoked_at"),
                rs.getString("revoked_reason"));
    }

    private static @Nullable Instant optionalInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private void requireNotRateLimited(String operation, String callerKey, RateLimiter.Policy policy) {
        RateLimiter.Decision decision = rateLimiter.check(new RateLimiter.Key(operation, null, callerKey), policy);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many enrolment requests. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    private record PendingRow(UUID id, String requestedClass, Instant expiresAt) {}

    private record DeviceRow(
            UUID tenantId,
            String principalSubject,
            UUID grantId,
            String keycloakClientInternalId,
            String keycloakClientId,
            String status,
            String tokenEndpoint) {}
}
