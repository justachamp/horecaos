package uz.horecaos.platform.iam.api.devices;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Enrols and revokes ADR 0079 device principals.
 *
 * <p>A device is a real ADR 0025 principal — it authenticates to Keycloak as
 * its own confidential service-account client and holds real {@code
 * iam.grants} rows — provisioned through a pairing-code handshake rather than
 * a password, because a screen cannot type one and must never hold a staff
 * member's. See ADR 0079 for the full decision; this interface is its
 * boundary.
 *
 * <p><strong>{@link #approve} and {@link #revoke} perform no capability check
 * of their own.</strong> Both trust that the caller has already been
 * authorized — by an {@code @RequiresCapability} declaration on the calling
 * module's own endpoint, at whatever scope and capability that module's own
 * ADR decides guards device administration (kitchen's is {@code
 * kitchen.station.manage} at {@code LOCATION} scope; a future device class
 * decides its own). This is the identical trust boundary {@link
 * uz.horecaos.platform.iam.application.GrantManagementService#grantSystemInitiated}
 * already draws for {@code TenantOwnerAuthorityGrantorAdapter}: the
 * capability decision was made once, at the call site, and what happens next
 * — granting a fixed, predetermined role at a fixed, predetermined scope — is
 * a mechanical consequence rather than an open choice this port is trusted to
 * re-litigate.
 */
public interface DeviceEnrolmentPort {

    /**
     * Starts an enrolment. Called by the device itself, before it holds any
     * credential at all — there is nothing to authenticate this call with,
     * which is why {@code DeviceEnrolmentController} carries no {@code
     * @RequiresCapability} and is permitted unauthenticated in {@code
     * SecurityConfiguration}, on the same reasoning ADR 0015's pre-account
     * customer identity endpoints already are.
     */
    EnrolmentBeginResult beginEnrolment(BeginEnrolment command, String callerKey);

    /**
     * Polled by the device with the {@code deviceCode} {@link
     * #beginEnrolment} returned. The <strong>first</strong> poll to observe
     * {@link EnrolmentStatus#APPROVED} receives the Keycloak client
     * credential and spends the request atomically, so a credential is
     * handed out exactly once and never persisted anywhere after that.
     *
     * @param callerKey an opaque, ADR 0033 rate-limiting handle for the
     *                  caller — never a Keycloak subject, since neither
     *                  endpoint this port backs has one yet
     */
    EnrolmentPollResult poll(String deviceCode, String callerKey);

    /**
     * Approves a pending enrolment, provisioning the device's Keycloak client
     * and granting {@code command.roleCode()} at {@code command.scope()}.
     *
     * <p><strong>An approval may narrow the class a device asked for and never
     * widen it</strong> (ADR 0151). A request for a class is a claim the approver
     * sees; a device that asked to be a touch {@link
     * DevicePrincipalClass#KITCHEN_KDS} may be approved as a read-only {@link
     * DevicePrincipalClass#KITCHEN_VDU}, and a device that asked to be a wall
     * display cannot be approved as something that can act. The class-to-role
     * mapping is the calling module's; this refuses an {@code approvedClass} the
     * request's class does not allow, with nothing written.
     *
     * @param command        which pending request, which scope (always {@code
     *                       LOCATION} for a kitchen device — see ADR 0079),
     *                       which class it is approved as, which role to grant,
     *                       and a display name
     * @param approverSubject the human staff subject who approved this,
     *                        recorded on the device row and the enrolment
     *                        request for the audit trail the calling module
     *                        writes — never used as {@code granted_by} on the
     *                        underlying {@code iam.grants} row, which records
     *                        a fixed system actor per {@code
     *                        grantSystemInitiated}'s own contract
     */
    DevicePrincipalView approve(ApproveEnrolment command, String approverSubject);

    /**
     * Revokes a device: the {@code iam.grants} row is revoked first — cache
     * evicted immediately, the same mechanism that already stops a revoked
     * staff grant on its very next request — and the Keycloak client is then
     * disabled so no new token can ever be minted for it. Idempotent: revoking
     * an already-revoked device returns {@code false} rather than throwing.
     */
    boolean revoke(UUID devicePrincipalId, String revokerSubject, String reason);

    /** Every device enrolled at one location, active and revoked alike. */
    List<DevicePrincipalView> list(UUID tenantId, UUID locationId);

    /**
     * The pending enrolment a typed code names, so the approver sees the class the device asked for
     * before choosing the class to approve it as (ADR 0151). Empty for an unknown, spent or expired
     * code, indistinguishably: this is a read behind a manager's capability, and a code that answers
     * differently by reason would tell a guesser which codes were ever issued.
     */
    Optional<PendingEnrolmentView> pendingEnrolment(String userCode);

    /**
     * The ACTIVE device whose Keycloak service-account subject is {@code
     * principalSubject} — the caller of a request, when the caller is a device.
     * Empty for a staff subject and for a revoked device, so "am I an enrolled
     * device" is answered from the row that revocation changes, never from a
     * token that may outlive it. {@code iam.device_principals.principal_subject}
     * is unique, so this needs no Keycloak protocol-mapper change (ADR 0151).
     */
    Optional<DevicePrincipalView> activeDeviceOf(String principalSubject);

    /** What a pending enrolment claims: the class it asked for, the label it gave, and when the code dies. */
    record PendingEnrolmentView(
            DevicePrincipalClass requestedClass, @Nullable String requestedLabel, Instant expiresAt) {}

    record BeginEnrolment(
            DevicePrincipalClass deviceClass, @Nullable String requestedLabel) {}

    record EnrolmentBeginResult(String deviceCode, String userCode, Instant expiresAt, int pollIntervalSeconds) {}

    enum EnrolmentStatus {
        PENDING,
        APPROVED,
        DENIED,
        EXPIRED
    }

    /**
     * @param credential present only the one time {@link EnrolmentStatus#APPROVED} is
     *                   observed by the poll that wins the claim; every other
     *                   answer, including a later poll of the same now-{@code
     *                   CLAIMED} request, carries none
     */
    record EnrolmentPollResult(
            EnrolmentStatus status, @Nullable DeviceCredential credential) {}

    /**
     * What a device stores from this point on. Never logged, never re-derivable
     * from any stored row — see ADR 0079's Specification section.
     */
    record DeviceCredential(String tokenEndpoint, String clientId, String clientSecret) {}

    record ApproveEnrolment(
            String userCode,
            ResourceScope scope,
            DevicePrincipalClass approvedClass,
            String roleCode,
            String displayName) {}

    /**
     * @param deviceClass    the class the device was approved as — the one its role follows
     * @param requestedClass the class it asked for; differs from {@code deviceClass} only when the
     *                       approver narrowed it (ADR 0151)
     */
    record DevicePrincipalView(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            DevicePrincipalClass deviceClass,
            DevicePrincipalClass requestedClass,
            String displayName,
            String status,
            String enrolledBy,
            Instant enrolledAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason) {}
}
